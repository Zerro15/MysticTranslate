package io.github.zerro15.mystictranslate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPolicyTest {
    @Test
    fun normalizesOcrWhitespaceAndRecognizesHan() {
        assertEquals("愚者 你好", normalized("  愚者\n\t你好 "))
        assertTrue(han.containsMatchIn("ABC 注"))
        assertFalse(han.containsMatchIn("123 MysticTranslate"))
    }

    @Test
    fun glossaryUsesLongestMatchAndKeepsPunctuation() {
        val terms = parseTerms("# comment\n愚者 = Шут\n愚者途径 = Путь Шута")
        assertEquals(
            listOf(Segment("打开", false), Segment("Путь Шута", true),
                Segment("，", false), Segment("Шут", true), Segment("。", false)),
            segments("打开愚者途径，愚者。", terms),
        )
    }

    @Test
    fun glossaryRejectsMalformedEntriesAndKeepsFirstDuplicate() {
        assertThrows(IllegalArgumentException::class.java) { parseTerms("愚者 = ") }
        assertThrows(IllegalArgumentException::class.java) { parseTerms("NoHan = Шут") }
        assertEquals(listOf(Term("愚者", "Шут")), parseTerms("愚者 = Шут\n愚者 = Дурак"))
    }

    @Test
    fun identicalHashDoesNotCollapseDistinctTranslationsAndLruEvictsColdEntry() {
        val cache = TranslationCache<String>(2)
        assertEquals("Aa".hashCode(), "BB".hashCode())
        cache["Aa"] = "первый"
        cache["BB"] = "второй"
        assertEquals("первый", cache["Aa"])
        cache["C"] = "третий"
        assertNull(cache["BB"])
        assertEquals("первый", cache["Aa"])
    }

    @Test
    fun networkEndpointsRequireHttpsAndRejectEmbeddedCredentials() {
        assertNull(httpsEndpoint("http://example.test", "chat/completions"))
        assertNull(httpsEndpoint("https://user:secret@example.test/v1", "chat/completions"))
        assertNull(httpsEndpoint("https://example.test/v1?secret=value", "chat/completions"))
        assertEquals("https://example.test/v1/chat/completions",
            httpsEndpoint("https://example.test/v1/", "chat/completions").toString())
        assertEquals("https://example.test/api/ocr",
            httpsEndpoint("https://example.test/api/ocr", "api/ocr").toString())
    }
}
