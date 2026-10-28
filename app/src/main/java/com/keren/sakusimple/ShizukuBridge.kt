package com.keren.sakusimple

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Surface
import rikka.shizuku.Shizuku

/**
 * Owns the app-side connection to the Shizuku UserService. The user must explicitly grant
 * Shizuku permission in the Shizuku app; the bridge never assumes elevated access is available.
 */
class ShizukuBridge(
    private val context: Context,
    private val onStatus: (String) -> Unit
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var remote: IVirtualDisplayService? = null
    private var binding = false
    private var wantsConnection = false
    private var permissionRequestPending = false
    private val pendingCallbacks = mutableListOf<(Boolean) -> Unit>()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuUserService::class.java.name)
    ).processNameSuffix("saku_virtual_display")
        .tag("saku-virtual-display-v1")
        .version(2)
        .daemon(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            remote = IVirtualDisplayService.Stub.asInterface(binder)
            binding = false
            onStatus("Shizuku terhubung (\${runCatching { "UID \${Shizuku.getUid()}" }.getOrDefault("UID tidak diketahui")}).")
            finishPending(remote != null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            binding = false
            onStatus("Koneksi Shizuku terputus. Hubungkan ulang.")
            finishPending(false)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        mainHandler.post { tryConnectIfRequested() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        mainHandler.post {
            remote = null
            binding = false
            onStatus("Shizuku berhenti. Nyalakan kembali Shizuku.")
            finishPending(false)
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_PERMISSION_CODE) return@OnRequestPermissionResultListener
        permissionRequestPending = false
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            mainHandler.post { tryConnectIfRequested() }
        } else {
            wantsConnection = false
            onStatus("Izin Shizuku ditolak. Izinkan Saku Simple di aplikasi Shizuku.")
            finishPending(false)
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    fun connect(callback: (Boolean) -> Unit = {}) {
        if (remote != null) {
            callback(true)
            return
        }
        pendingCallbacks += callback
        wantsConnection = true
        tryConnectIfRequested()
    }

    private fun tryConnectIfRequested() {
        if (!wantsConnection || remote != null || binding) return
        if (!Shizuku.pingBinder()) {
            onStatus("Shizuku belum aktif. Jalankan Shizuku dahulu, lalu tekan Hubungkan Shizuku.")
            return
        }

        val permissionGranted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (error: Throwable) {
            onStatus("Belum dapat memeriksa izin Shizuku: \${error.message ?: error.javaClass.simpleName}")
            false
        }
        if (!permissionGranted) {
            if (!permissionRequestPending) {
                permissionRequestPending = true
                try {
                    Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
                    onStatus("Menunggu izin Shizuku…")
                } catch (error: Throwable) {
                    permissionRequestPending = false
                    onStatus("Tidak dapat meminta izin Shizuku: \${error.message ?: error.javaClass.simpleName}")
                    finishPending(false)
                }
            }
            return
        }

        try {
            binding = true
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            onStatus("Memulai Shizuku UserService…")
        } catch (error: Throwable) {
            binding = false
            onStatus("Gagal mengikat UserService: \${error.message ?: error.javaClass.simpleName}")
            finishPending(false)
        }
    }

    private fun finishPending(connected: Boolean) {
        val callbacks = pendingCallbacks.toList()
        pendingCallbacks.clear()
        callbacks.forEach { callback -> runCatching { callback(connected) } }
    }

    fun createVirtualDisplay(
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        callback: (displayId: Int, error: String) -> Unit
    ) {
        connect { connected ->
            if (!connected) {
                callback(-1, "Shizuku belum terhubung atau izinnya belum diberikan.")
                return@connect
            }
            try {
                val service = remote ?: throw IllegalStateException("UserService belum siap.")
                val id = service.createVirtualDisplay(width, height, densityDpi, surface)
                val error = service.lastError ?: if (id >= 0) "OK" else "Pembuatan display gagal."
                callback(id, error)
            } catch (error: Throwable) {
                Log.e(TAG, "createVirtualDisplay call failed", error)
                callback(-1, "\${error.cause?.message ?: error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun injectTap(displayId: Int, x: Float, y: Float): Boolean {
        return try {
            remote?.injectTap(displayId, x, y) ?: false
        } catch (error: Throwable) {
            Log.e(TAG, "injectTap failed", error)
            onStatus("Tap via Shizuku gagal: \${error.cause?.message ?: error.message}")
            false
        }
    }

    fun injectSwipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 220L): Boolean {
        return try {
            remote?.injectSwipe(displayId, x1, y1, x2, y2, durationMs) ?: false
        } catch (error: Throwable) {
            Log.e(TAG, "injectSwipe failed", error)
            onStatus("Swipe via Shizuku gagal: \${error.cause?.message ?: error.message}")
            false
        }
    }

    fun launchSettingsOnDisplay(activity: Activity, displayId: Int): Boolean {
        if (displayId < 0) {
            onStatus("Buat virtual display terlebih dahulu.")
            return false
        }
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            activity.startActivity(intent, options.toBundle())
            true
        } catch (error: Throwable) {
            onStatus("Android menolak membuka Pengaturan di display $displayId: \${error.cause?.message ?: error.message}")
            false
        }
    }

    fun releaseVirtualDisplay() {
        try {
            remote?.releaseVirtualDisplay()
        } catch (error: Throwable) {
            Log.w(TAG, "releaseVirtualDisplay failed", error)
        }
    }

    override fun close() {
        releaseVirtualDisplay()
        wantsConnection = false
        val wasBound = binding || remote != null
        remote = null
        try { Shizuku.removeBinderReceivedListener(binderReceivedListener) } catch (_: Throwable) {}
        try { Shizuku.removeBinderDeadListener(binderDeadListener) } catch (_: Throwable) {}
        try { Shizuku.removeRequestPermissionResultListener(permissionListener) } catch (_: Throwable) {}
        if (wasBound) {
            try { Shizuku.unbindUserService(userServiceArgs, serviceConnection, true) } catch (_: Throwable) {}
        }
        binding = false
        pendingCallbacks.clear()
    }

    companion object {
        private const val TAG = "SakuShizukuBridge"
        private const val REQUEST_PERMISSION_CODE = 24681
    }
}
