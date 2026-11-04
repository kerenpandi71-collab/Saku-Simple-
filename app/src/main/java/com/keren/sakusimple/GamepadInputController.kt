package com.keren.sakusimple

import android.view.MotionEvent
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes Shizuku input IPC and coalesces high-frequency analog MOVE updates. */
class GamepadInputController(
    private val displayId: () -> Int,
    private val displayWidth: () -> Int,
    private val displayHeight: () -> Int,
    private val inject: (displayId: Int, action: Int, pointerIds: IntArray, xs: FloatArray, ys: FloatArray) -> Boolean
) : AutoCloseable {
    private data class Point(val x: Float, val y: Float)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SakuGamepadInput").apply { isDaemon = true }
    }
    private val active = LinkedHashMap<Int, Point>()
    private val pendingLock = Any()
    private val pendingMoves = HashMap<Int, Point>()
    private val moveDrainQueued = AtomicBoolean(false)
    @Volatile private var closed = false

    fun press(pointerId: Int, point: ControlPoint) {
        if (closed || pointerId !in 0..31) return
        submit {
            if (pointerId in active || active.size >= MAX_POINTERS) return@submit
            val wasEmpty = active.isEmpty()
            active[pointerId] = normalized(point)
            val action = if (wasEmpty) MotionEvent.ACTION_DOWN else pointerAction(MotionEvent.ACTION_POINTER_DOWN, pointerId)
            if (!send(action)) active.remove(pointerId)
        }
    }

    fun move(pointerId: Int, point: ControlPoint) {
        if (closed || pointerId !in 0..31) return
        synchronized(pendingLock) { pendingMoves[pointerId] = normalized(point) }
        scheduleMoveDrain()
    }

    fun release(pointerId: Int) {
        if (closed) return
        synchronized(pendingLock) { pendingMoves.remove(pointerId) }
        submit { releaseOnWorker(pointerId) }
    }

    /** Releases every held pointer, used when pausing, editing a profile, or leaving the screen. */
    fun releaseAll(afterRelease: () -> Unit = {}) {
        synchronized(pendingLock) { pendingMoves.clear() }
        submit {
            releaseAllOnWorker()
            runCatching(afterRelease)
        }
    }

    private fun scheduleMoveDrain() {
        if (!moveDrainQueued.compareAndSet(false, true)) return
        submit {
            val updates = synchronized(pendingLock) {
                pendingMoves.toMap().also { pendingMoves.clear() }
            }
            var changed = false
            updates.forEach { (pointerId, point) ->
                if (active.containsKey(pointerId)) {
                    active[pointerId] = point
                    changed = true
                }
            }
            if (changed) send(MotionEvent.ACTION_MOVE)
            moveDrainQueued.set(false)
            val hasMore = synchronized(pendingLock) { pendingMoves.isNotEmpty() }
            if (hasMore) scheduleMoveDrain()
        }
    }

    private fun releaseOnWorker(pointerId: Int) {
        if (!active.containsKey(pointerId)) return
        val action = if (active.size == 1) MotionEvent.ACTION_UP else pointerAction(MotionEvent.ACTION_POINTER_UP, pointerId)
        if (send(action)) {
            active.remove(pointerId)
        } else if (send(MotionEvent.ACTION_CANCEL)) {
            active.clear()
        }
    }

    private fun releaseAllOnWorker() {
        while (active.isNotEmpty()) {
            val countBefore = active.size
            releaseOnWorker(active.keys.first())
            if (active.size == countBefore) break
        }
    }

    private fun pointerAction(maskedAction: Int, pointerId: Int): Int {
        val index = active.keys.sorted().indexOf(pointerId).coerceAtLeast(0)
        return maskedAction or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    }

    private fun send(action: Int): Boolean {
        if (active.isEmpty()) return false
        val ordered = active.entries.sortedBy { it.key }
        val width = displayWidth().coerceAtLeast(1)
        val height = displayHeight().coerceAtLeast(1)
        val id = displayId()
        if (id < 0) return false
        return runCatching {
            inject(
                id,
                action,
                ordered.map { it.key }.toIntArray(),
                ordered.map { it.value.x * width }.toFloatArray(),
                ordered.map { it.value.y * height }.toFloatArray()
            )
        }.getOrDefault(false)
    }

    private fun normalized(point: ControlPoint): Point =
        Point(point.x.coerceIn(0.02f, 0.98f), point.y.coerceIn(0.02f, 0.98f))

    private fun submit(action: () -> Unit) {
        if (closed) return
        try {
            executor.execute(action)
        } catch (_: RejectedExecutionException) {
            // Activity shutdown can race an already-dispatched touch event.
        }
    }

    override fun close() {
        if (closed) return
        synchronized(pendingLock) { pendingMoves.clear() }
        try {
            executor.execute { releaseAllOnWorker() }
        } catch (_: RejectedExecutionException) {
        }
        closed = true
        executor.shutdown()
        runCatching { executor.awaitTermination(350, TimeUnit.MILLISECONDS) }
    }

    companion object {
        private const val MAX_POINTERS = 5
    }
}
