package com.vknxworks.callctrl.data.screening

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The screening rules, kept in CallCtrl's own files (`screening.json`, written atomically). Read once per process;
 * the screening service reads the same copy.
 */
class ScreeningStore(private val context: Context, scope: CoroutineScope) {
    private val file = AtomicFile(File(context.filesDir, "screening.json"))
    private val lock = Mutex()
    private val _config = MutableStateFlow(ScreeningConfig())
    val config: StateFlow<ScreeningConfig> = _config

    @Volatile private var loaded = false

    init {
        scope.launch(Dispatchers.IO) { ensureLoaded() }
    }

    /** The rules, read from disk the first time (call off the main thread). */
    fun current(): ScreeningConfig {
        ensureLoaded()
        return _config.value
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        val read = runCatching {
            if (!file.baseFile.exists()) null
            else json.decodeFromString(ScreeningConfig.serializer(), file.readFully().decodeToString())
        }.getOrNull()
        if (read != null) _config.value = read
        loaded = true
    }

    /** Saves a change. If the phone's storage fails, the change is kept for this session only. */
    suspend fun update(transform: (ScreeningConfig) -> ScreeningConfig) = withContext(Dispatchers.IO) {
        lock.withLock {
            ensureLoaded()
            val next = transform(_config.value)
            _config.value = next
            runCatching {
                val out = file.startWrite()
                try {
                    out.write(json.encodeToString(ScreeningConfig.serializer(), next).encodeToByteArray())
                    file.finishWrite(out)
                } catch (e: Exception) {
                    file.failWrite(out)
                    throw e
                }
            }
        }
        Unit
    }

    /** "Forgot PIN?" resets CallCtrl's own settings, the screening rules included. */
    suspend fun reset() = update { ScreeningConfig() }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
