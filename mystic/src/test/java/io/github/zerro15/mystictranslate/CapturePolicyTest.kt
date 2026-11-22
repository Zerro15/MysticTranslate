package io.github.zerro15.mystictranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePolicyTest {
    @Test
    fun firstFrameAndChangedFrameAreReadWithoutWaitingForARepeat() {
        val tracker = FrameDeduplication()
        assertTrue(tracker.shouldRead(81, 0, 100))
        tracker.record(81, 0, 100, hasText = true)
        assertFalse(tracker.shouldRead(81, 0, 200))
        assertTrue(tracker.shouldRead(82, 0, 200))
        assertTrue(tracker.shouldRead(81, 1, 200))
        // Периодический проход подхватывает мелкий текст между пикселями выборки.
        assertTrue(tracker.shouldRead(81, 0, 13_000))
    }

    @Test
    fun emptyStaticFrameCanBeRetriedForContrastFallback() {
        val tracker = FrameDeduplication()
        tracker.record(77, 0, 100, hasText = false)
        assertFalse(tracker.shouldRead(77, 0, 200))
        assertTrue(tracker.shouldRead(77, 0, 4500))
    }

    @Test
    fun chineseSamplesKeepSingleGlyphsAndNormalizeMultilineDialogue() {
        val samples = listOf("任务", "接受", "道具详情", "新邮件", "退", "今天\u200b天气\n很好。")
        samples.forEach { assertTrue(han.containsMatchIn(normalizeRecognition(it))) }
        assertEquals("今天天气 很好。", normalizeRecognition(samples.last()))
        assertFalse(han.containsMatchIn(normalizeRecognition("12:30 · MysticTranslate")))
    }

    @Test
    fun cropWithScaledOcrCoordinatesMapsBackToLandscapeFrame() {
        val area = PixelBox(0, 360, 1280, 720)
        assertEquals(PixelBox(300, 400, 1000, 520),
            sourceBounds(PixelBox(150, 20, 500, 80), area, 640, 180))
        assertEquals(PixelBox(0, 360, 1280, 720),
            sourceBounds(PixelBox(-20, -30, 700, 210), area, 640, 180))
        assertNull(sourceBounds(PixelBox(700, 1, 740, 5), area, 640, 180))
    }

    @Test
    fun panelAnchorRemainsInSafeRangeWhenAvailableSpaceChanges() {
        val portrait = PanelRange(8, 530)
        val target = portrait.fraction(269)
        assertEquals(0.5f, target, 0.001f)
        assertEquals(602, PanelRange(16, 1188).at(target))
        assertEquals(1f, portrait.fraction(800), 0.001f)
        assertEquals(0f, PanelRange(5, 5).fraction(15), 0.001f)
    }

    @Test
    fun diagnosticReportOmitsChineseTextAndTranslation() {
        val report = DiagnosticSnapshot(
            active = true, width = 1280, height = 720, frameCount = 3,
            lastSource = "你好。我是克莱恩·莫雷蒂。", lastTranslation = Dialogue("你好", "Клейн Моретти"),
            ocrMs = 120, translateMs = 59, models = ModelState.READY,
        ).report()
        assertTrue(report.contains("1280×720"))
        assertTrue(report.contains("120 мс"))
        assertFalse(report.contains("你好"))
        assertFalse(report.contains("Клейн"))
    }
}
