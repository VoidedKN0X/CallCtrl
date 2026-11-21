package com.vknxworks.callctrl.calls

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.vknxworks.callctrl.container
import com.vknxworks.callctrl.data.Phone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Decides from the phone's motion sensor when it was turned face down. Pure Kotlin, unit-tested.
 *
 * The phone must first have been seen not lying face down (so a phone that already lies on its screen when
 * the call comes in doesn't silence it by itself), and must then lie face down, flat and still enough, for
 * [HOLD_MS] in a row.
 */
class FlipDetector {
    private var sawUp = false
    private var downSince = -1L

    /**
     * One reading of the accelerometer: [x], [y], [z] in m/s² (z is about +9.8 with the screen up and -9.8 with
     * the screen down), at [timeMs]. True once, when the phone has been turned face down.
     */
    fun feed(x: Float, y: Float, z: Float, timeMs: Long): Boolean {
        val faceDown = z < -DOWN && abs(x) < FLAT && abs(y) < FLAT
        if (!sawUp) {
            if (z > -UP) sawUp = true
            return false
        }
        if (!faceDown) {
            downSince = -1L
            return false
        }
        if (downSince < 0) {
            downSince = timeMs
            return false
        }
        return timeMs - downSince >= HOLD_MS
    }

    companion object {
        const val DOWN = 8.0f
        const val UP = 3.0f
        const val FLAT = 4.0f
        const val HOLD_MS = 350L
    }
}

/**
 * "Flip to silence": while a call rings, turning the phone face down stops the ringing. The call itself keeps
 * coming in and can still be answered. Uses the accelerometer only while a call is ringing; it needs no
 * permission. Android's own ringer is silenced through [android.telecom.TelecomManager.silenceRinger], which the
 * default phone app is allowed to call.
 */
object FlipToSilence : SensorEventListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    private var sensors: SensorManager? = null
    private var detector: FlipDetector? = null
    private var starting: Job? = null
    private var watching = false
    /** The setting was looked up for the call that is ringing now (so it isn't read again for every change of the call). */
    private var checked = false

    /** Called whenever the calls change: [ringing] says whether one is ringing now. */
    fun onCalls(context: Context, ringing: Boolean) {
        if (!ringing) {
            stop()
            return
        }
        if (watching || checked || starting != null) return
        val app = context.applicationContext
        appContext = app
        starting = scope.launch {
            // The saved setting, not the placeholder a freshly started app has for a moment.
            val on = runCatching { app.container.settings.load().flipToSilence }.getOrDefault(false)
            starting = null
            checked = true
            if (on && CallManager.calls.value.any { it.ringing }) start(app)
        }
    }

    private fun start(context: Context) {
        if (watching) return
        val manager = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        detector = FlipDetector()
        sensors = manager
        watching = runCatching { manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) }.getOrDefault(false)
    }

    private fun stop() {
        starting?.cancel()
        starting = null
        checked = false
        if (watching) runCatching { sensors?.unregisterListener(this) }
        watching = false
        detector = null
        sensors = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val d = detector ?: return
        if (event.values.size < 3) return
        if (d.feed(event.values[0], event.values[1], event.values[2], SystemClock.elapsedRealtime())) {
            val context = appContext
            stop()
            // Done for this call: don't start watching again while it keeps ringing silently.
            checked = true
            if (context != null) runCatching { Phone.telecom(context)?.silenceRinger() }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
