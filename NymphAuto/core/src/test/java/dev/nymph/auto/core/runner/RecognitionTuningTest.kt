package dev.nymph.auto.core.runner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionTuningTest {

    @Test
    fun `默认帧同步33ms 且快于旧版`() {
        val t = RecognitionTuning()
        assertEquals(PollMode.FRAME_33, t.pollMode)
        assertEquals(33L, t.intervalMs)
        assertTrue(t.intervalMs < RecognitionTuning.LEGACY.intervalMs)
    }

    @Test
    fun `旧版档位完全对齐v2_6行为`() {
        val l = RecognitionTuning.LEGACY
        assertEquals(300L, l.intervalMs)
        assertEquals(false, l.motionPrediction)
        assertEquals(false, l.narrowRoi)
    }

    @Test
    fun `各帧同步档位取值`() {
        assertEquals(16L, RecognitionTuning(pollMode = PollMode.FRAME_16).intervalMs)
        assertEquals(50L, RecognitionTuning(pollMode = PollMode.FRAME_50).intervalMs)
        assertEquals(100L, RecognitionTuning(pollMode = PollMode.LEGACY_100).intervalMs)
        assertEquals(33L, RecognitionTuning(pollMode = PollMode.CUSTOM, customIntervalMs = 33).intervalMs)
    }

    @Test
    fun `自定义间隔被钳制到合法区间`() {
        assertEquals(
            RecognitionTuning.MIN_INTERVAL_MS,
            RecognitionTuning(pollMode = PollMode.CUSTOM, customIntervalMs = 0).intervalMs,
        )
        assertEquals(
            RecognitionTuning.MAX_INTERVAL_MS,
            RecognitionTuning(pollMode = PollMode.CUSTOM, customIntervalMs = 99_999).intervalMs,
        )
    }

    @Test
    fun `sanitized 归一化非法值`() {
        val s = RecognitionTuning(
            pollMode = PollMode.CUSTOM,
            customIntervalMs = -5,
            retryWindowMs = -1,
            conditionFailWaitMs = 999_999,
        ).sanitized()
        assertEquals(RecognitionTuning.MIN_INTERVAL_MS, s.customIntervalMs)
        assertEquals(0L, s.retryWindowMs)
        assertEquals(10_000L, s.conditionFailWaitMs)
    }
}
