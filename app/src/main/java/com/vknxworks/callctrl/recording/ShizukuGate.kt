package com.vknxworks.callctrl.recording

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.vknxworks.callctrl.BuildConfig
import com.vknxworks.callctrl.recording.shell.CaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

/** Where Shizuku stands for CallCtrl, shown in Settings > Call recording. */
enum class ShizukuStatus {
    /** The Shizuku app isn't on the phone. */
    NOT_INSTALLED,
    /** Installed but not started (it has to be started again after every restart). */
    NOT_RUNNING,
    /** A Shizuku older than version 11, which CallCtrl can't use. */
    TOO_OLD,
    /** Running; CallCtrl needs your OK in Shizuku's dialog. */
    PERMISSION_NEEDED,
    /** You chose "Deny and don't ask again": only Shizuku's own app can allow CallCtrl now. */
    DENIED,
    /** Running and allowed: calls can be recorded. */
    READY,
}

/**
 * CallCtrl's link to Shizuku: its state, the permission request, and binding the capture service
 * ([CaptureService], a Shizuku user service in its own process). Main thread only. Holds the application context
 * only.
 */
@SuppressLint("StaticFieldLeak")
object ShizukuGate {
    const val PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST = 4207

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private val _status = MutableStateFlow(ShizukuStatus.NOT_INSTALLED)
    val status: StateFlow<ShizukuStatus> = _status
    private var started = false

    /** Called when Shizuku dies (stopped, or the phone killed it). */
    var onDead: (() -> Unit)? = null

    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky({ refresh() }, main)
        Shizuku.addBinderDeadListener({
            refresh()
            onDead?.invoke()
        }, main)
        Shizuku.addRequestPermissionResultListener({ _, _ -> refresh() }, main)
        refresh()
    }

    fun refresh() {
        if (!started) return
        _status.value = compute()
    }

    private fun compute(): ShizukuStatus {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!running) return if (installed()) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) return ShizukuStatus.TOO_OLD
        return runCatching {
            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuStatus.READY
                Shizuku.shouldShowRequestPermissionRationale() -> ShizukuStatus.DENIED
                else -> ShizukuStatus.PERMISSION_NEEDED
            }
        }.getOrDefault(ShizukuStatus.NOT_RUNNING)
    }

    private fun installed(): Boolean = runCatching {
        app.packageManager.getPackageInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Shows Shizuku's own permission dialog for CallCtrl. */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST) }
        refresh()
    }

    /** Opens the Shizuku app (to start it, or to allow CallCtrl there). */
    fun openShizukuIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ------------------------------------------------------------------ the capture service

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(app.packageName, CaptureService::class.java.name))
            .daemon(false)
            .processNameSuffix("capture")
            .tag("callctrl-capture")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private var connection: ServiceConnection? = null
    private var service: ICallCapture? = null
    private val waiting = ArrayList<(ICallCapture?) -> Unit>()

    /** Binds the capture service (if Shizuku is ready) and hands it to [then] (null when it can't). */
    fun bind(then: (ICallCapture?) -> Unit) {
        service?.let { s -> if (s.asBinder().isBinderAlive) { then(s); return } else service = null }
        refresh()
        if (_status.value != ShizukuStatus.READY) {
            then(null)
            return
        }
        waiting += then
        if (connection != null) return
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val s = if (binder != null && binder.pingBinder()) ICallCapture.Stub.asInterface(binder) else null
                service = s
                flush(s)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                connection = null
                flush(null)
            }
        }
        connection = conn
        val ok = runCatching { Shizuku.bindUserService(args, conn) }.isSuccess
        if (!ok) {
            connection = null
            flush(null)
            return
        }
        // Shizuku never answers if it dies while starting the service.
        main.postDelayed({ if (service == null && waiting.isNotEmpty()) flush(null) }, 15_000)
    }

    private fun flush(s: ICallCapture?) {
        val list = ArrayList(waiting)
        waiting.clear()
        list.forEach { runCatching { it(s) } }
    }

    /** Unbinds and ends the capture process. */
    fun unbind() {
        val conn = connection ?: return
        connection = null
        service = null
        runCatching { Shizuku.unbindUserService(args, conn, true) }
        flush(null)
    }

    val bound: Boolean get() = service?.asBinder()?.isBinderAlive == true

    /** Bound or binding. */
    val active: Boolean get() = connection != null
}
