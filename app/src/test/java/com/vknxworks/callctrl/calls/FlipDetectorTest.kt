package com.vknxworks.callctrl.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlipDetectorTest {
    @Test fun faceUpThenFaceDownSilencesAfterHolding() {
        val d = FlipDetector()
        assertFalse(d.feed(0f, 0f, 9.8f, 0))
        assertFalse(d.feed(0f, 5f, 2f, 100))
        assertFalse(d.feed(0.2f, 0.1f, -9.7f, 200))
        assertFalse(d.feed(0.2f, 0.1f, -9.7f, 400))
        assertTrue(d.feed(0.2f, 0.1f, -9.7f, 200 + FlipDetector.HOLD_MS))
    }

    @Test fun aPhoneAlreadyFaceDownDoesNotSilenceByItself() {
        val d = FlipDetector()
        for (t in 0L..5000L step 100L) assertFalse(d.feed(0f, 0f, -9.8f, t))
        // Picked up, looked at, put back down: that does count.
        assertFalse(d.feed(0f, 3f, 8f, 5100))
        assertFalse(d.feed(0f, 0f, -9.8f, 5200))
        assertTrue(d.feed(0f, 0f, -9.8f, 5200 + FlipDetector.HOLD_MS))
    }

    @Test fun aShortSwingOrATiltedPhoneDoesNotCount() {
        val d = FlipDetector()
        assertFalse(d.feed(0f, 0f, 9.8f, 0))
        // Face down for a moment only.
        assertFalse(d.feed(0f, 0f, -9.8f, 100))
        assertFalse(d.feed(0f, 0f, 1f, 200))
        assertFalse(d.feed(0f, 0f, -9.8f, 300))
        assertFalse(d.feed(0f, 0f, -9.8f, 300 + FlipDetector.HOLD_MS - 1))
        // Screen down but held at an angle (in a hand, in a pocket).
        val tilted = FlipDetector()
        assertFalse(tilted.feed(0f, 0f, 9.8f, 0))
        for (t in 100L..3000L step 100L) assertFalse(tilted.feed(5.5f, 0f, -8.1f, t))
    }
}
