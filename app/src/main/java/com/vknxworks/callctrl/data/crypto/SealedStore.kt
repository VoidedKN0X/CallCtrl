package com.vknxworks.callctrl.data.crypto

import java.io.File

/**
 * One small file of personal data (call notes, call-back reminders), sealed with the app's data key the same
 * way finished recordings are ([SealedFile], marker `CALLCTRL-REST1`). Everything here is slow (it may ask the
 * phone's secure hardware): call it off the main thread, and never on the incoming-call or screening path.
 *
 * A file that exists but can't be opened (the key is missing, or the file is damaged) is never written over:
 * [read] says so, and [write] refuses until [read] has returned its content once.
 */
class SealedStore(private val file: File, private val keys: DataKeyStore) {
    private val tmp = File(file.path + ".tmp")

    sealed interface Content {
        /** The file's text, or null when there is no file yet. */
        data class Text(val text: String?) : Content
        /** There is a file, and it can't be opened right now. It is left exactly as it is. */
        data object Unreadable : Content
    }

    /** Whether writing is safe: nothing is on disk that couldn't be read. */
    @Volatile private var mayWrite = false

    /** [retryKey]: ask the secure hardware for the data key again, after it couldn't be had earlier in this run. */
    @Synchronized
    fun read(retryKey: Boolean = false): Content {
        mayWrite = false
        if (retryKey) keys.reset()
        try {
            if (!file.exists()) {
                mayWrite = true
                return Content.Text(null)
            }
            val bytes = file.readBytes()
            if (!SealedFile.isSealed(bytes)) return Content.Unreadable
            val key = keys.get(create = false) ?: return Content.Unreadable
            val plain = SealedFile.open(key, bytes)
            mayWrite = true
            return Content.Text(plain.decodeToString())
        } catch (e: Throwable) {
            return Content.Unreadable
        }
    }

    /** Seals [text] and puts it in the file's place in one step. False when it wasn't saved. */
    @Synchronized
    fun write(text: String): Boolean {
        if (!mayWrite) return false
        return try {
            val key = keys.get(create = true) ?: return false
            val sealed = SealedFile.sealChecked(key, text.encodeToByteArray()) ?: return false
            SealedFile.writeAtomic(file, tmp, sealed)
            true
        } catch (e: Throwable) {
            runCatching { tmp.delete() }
            false
        }
    }

    /** Removes the file (the data is empty again). Only what could be read, or was never there, is removed. */
    @Synchronized
    fun clear(): Boolean {
        if (!mayWrite) return false
        return runCatching { !file.exists() || file.delete() }.getOrDefault(false)
    }
}
