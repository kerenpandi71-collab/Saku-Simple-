package com.keren.sakusimple

import android.view.MotionEvent
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes all touch transitions through one worker. MOVE events are coalesced,
 * but DOWN/UP/CANCEL transitions are never coalesced or reordered.
 */
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
    // This is the authoritative set of pointers currently held on the virtual display.
    private val active = LinkedHashMap<Int, Point>()
    private val pendingLock = Any()
    private val pendingMoves = HashMap<Int, Point>()
    private val moveDrainQueued = AtomicBoolean(false)
    @Volatile private var closed = false

    fun press(pointerId: Int, point: ControlPoint) {
        if (closed || pointerId !in 0..31) return
        submit {
            // A stale pointer must never silently block a new physical press.
            if (pointerId in active) {
                if (!cancelActiveStream()) active.clear()
            }
            if (active.size >= MAX_POINTERS) return@submit
            val wasEmpty = active.isEmpty()
            active[pointerId] = normalized(point)
            val action = if (wasEmpty) MotionEvent.ACTION_DOWN
                else pointerAction(MotionEvent.ACTION_POINTER_DOWN, pointerId)
            if (!send(action)) {
                recoverAfterRejectedTransition()
            }
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
            cancelActiveStream()
            active.clear()
            runCatching(afterRelease)
        }
    }

    private fun scheduleMoveDrain() {
        if (!moveDrainQueued.compareAndSet(false, true)) return
        submit {
            try {
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
                if (changed && !send(MotionEvent.ACTION_MOVE)) {
                    // A rejected MOVE is not a release. Keep the stream alive; the next
                    // position update or the corresponding UP/CANCEL will close it.
                }
            } finally {
                moveDrainQueued.set(false)
                val hasMore = synchronized(pendingLock) { pendingMoves.isNotEmpty() }
                if (hasMore) scheduleMoveDrain()
            }
        }
    }

    private fun releaseOnWorker(pointerId: Int) {
        if (!active.containsKey(pointerId)) return
        val action = if (active.size == 1) MotionEvent.ACTION_UP
            else pointerAction(MotionEvent.ACTION_POINTER_UP, pointerId)
        if (send(action)) {
            active.remove(pointerId)
        } else {
            // Do not leave a rejected pointer transition in the local active map.
            // A failed POINTER_UP otherwise causes future presses of that control to be
            // ignored indefinitely and can leave analog + action buttons stuck together.
            recoverAfterRejectedTransition()
        }
    }

    private fun recoverAfterRejectedTransition() {
        cancelActiveStream()
        active.clear()
        synchronized(pendingLock) { pendingMoves.clear() }
    }

    /**
     * CANCEL is the only valid single event that releases an arbitrary multi-pointer
     * stream. Retry briefly because some OEM InputManager builds reject the first
     * secondary-display transition while the display/focus is changing.
     */
    private fun cancelActiveStream(): Boolean {
        if (active.isEmpty()) return true
        repeat(CANCEL_RETRIES) {
            if (send(MotionEvent.ACTION_CANCEL)) return true
            try {
                Thread.sleep(CANCEL_RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun pointerAction(maskedAction: Int, pointerId: Int): Int {
        val index = active.keys.sorted().indexOf(pointerId)
        if (index < 0) return maskedAction
        return maskedAction or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    }

    private fun send(action: Int): Boolean {
        if (active.isEmpty()) return false
        val ordered = active.entries.sortedBy { it.key }
        val width = displayWidth().coerceAtLeast(1)
        val height = displayHeight().coerceAtLeast(1)
        val id = displayId()
        if (id < 0) return false
        val masked = action and MotionEvent.ACTION_MASK
        // A one-pointer stream uses DOWN/UP, never POINTER_DOWN/POINTER_UP.
        val safeAction = when {
            ordered.size == 1 && masked == MotionEvent.ACTION_POINTER_DOWN -> MotionEvent.ACTION_DOWN
            ordered.size == 1 && masked == MotionEvent.ACTION_POINTER_UP -> MotionEvent.ACTION_UP
            else -> action
        }
        return runCatching {
            inject(
                id,
                safeAction,
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
            executor.execute {
                cancelActiveStream()
                active.clear()
            }
        } catch (_: RejectedExecutionException) {
        }
        closed = true
        executor.shutdown()
        runCatching { executor.awaitTermination(500, TimeUnit.MILLISECONDS) }
    }

    companion object {
        private const val MAX_POINTERS = 5
        private const val CANCEL_RETRIES = 3
        private const val CANCEL_RETRY_DELAY_MS = 12L
    }
}
