package com.vknxworks.callctrl.recording;

/**
 * The call recorder's capture service. It runs in a separate process started by Shizuku (with the shell's
 * identity, which may capture call audio) and only captures call audio into the pipe CallCtrl hands it.
 * There is deliberately nothing else: no commands, no files, no other audio sources.
 */
interface ICallCapture {
    /**
     * Starts capturing call audio into [sink] (the write end of a pipe; raw little-endian PCM, interleaved).
     * Returns the format actually used, as {mode, sampleRate, channels, float (1) or 16-bit (0)}, or an empty
     * array when the phone refused every call-audio source. Never falls back to the microphone.
     */
    int[] start(in ParcelFileDescriptor sink) = 1;

    /** Stops capturing and closes the pipe. */
    void stop() = 2;

    /** The format being captured right now (as returned by start), or an empty array when not capturing. */
    int[] format() = 3;

    /** Shizuku's own "destroy" transaction: stop and end the process. */
    void destroy() = 16777114;
}
