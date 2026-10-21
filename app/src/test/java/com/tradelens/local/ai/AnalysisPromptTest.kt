package com.tradelens.local.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisPromptTest {

    @Test
    fun parseStandardJson_returnsExpectedOutput() {
        val json = """
            {
                "ticker": "AAPL",
                "price": "$178.50",
                "change": "+1.2%",
                "indicators": ["RSI 58", "MACD Bullish Cross"],
                "summary": "Apple is showing steady consolidation above 175 support. Volume indicates accumulation. Momentum remains positive.",
                "risk": 4
            }
        """.trimIndent()

        val output = AnalysisPrompt.parse(json)
        assertNotNull(output)
        assertEquals("AAPL", output?.ticker)
        assertEquals("$178.50", output?.price)
        assertEquals("+1.2%", output?.change)
        assertEquals(2, output?.indicators?.size)
        assertEquals(4, output?.risk)
        assertEquals("Moderate", output?.riskCategory)
    }

    @Test
    fun parseMarkdownFencedJson_stripsFencesCorrectly() {
        val markdown = """
            Here is the analysis:
            ```json
            {
                "ticker": "TSLA",
                "price": "218.40",
                "change": "-0.8%",
                "indicators": ["EMA 20", "VWAP"],
                "summary": "Tesla tested the 220 resistance and pulled back slightly. Volatility is compressing near VWAP.",
                "risk": 7
            }
            ```
            Hope this helps!
        """.trimIndent()

        val output = AnalysisPrompt.parse(markdown)
        assertNotNull(output)
        assertEquals("TSLA", output?.ticker)
        assertEquals("218.40", output?.price)
        assertEquals(7, output?.risk)
        assertEquals("High", output?.riskCategory)
    }

    @Test
    fun parseJsonWithUnescapedNewlinesInSummary_succeedsViaFallback() {
        val raw = """
            {
                "ticker": "BTC",
                "price": "$64,200",
                "change": "+4.1%",
                "indicators": ["RSI 66"],
                "summary": "Bitcoin broke out from range.
                Momentum is accelerating towards 65k.
                Watch 63k support on retest.",
                "risk": 6
            }
        """.trimIndent()

        val output = AnalysisPrompt.parse(raw)
        assertNotNull(output)
        assertEquals("BTC", output?.ticker)
        assertEquals("$64,200", output?.price)
        assertEquals(6, output?.risk)
        assertTrue(output?.summary?.contains("Bitcoin broke out") == true)
    }

    @Test
    fun parseTruncatedJson_recoversFields() {
        // Truncated at max tokens limit before closing braces
        val truncated = """
            {"ticker": "NVDA", "price": "$125.00", "change": "+3.5%", "indicators": ["SMA 50"], "summary": "Nvidia rallied off 120 key support level."
        """.trimIndent()

        val output = AnalysisPrompt.parse(truncated)
        assertNotNull(output)
        assertEquals("NVDA", output?.ticker)
        assertEquals("$125.00", output?.price)
        assertEquals("+3.5%", output?.change)
        assertTrue(output?.summary?.contains("Nvidia rallied") == true)
    }

    @Test
    fun riskCategory_classifiesAccurately() {
        val low = AnalysisOutput("A", "1", "0", emptyList(), "Test", 2)
        val mod = AnalysisOutput("B", "2", "0", emptyList(), "Test", 5)
        val high = AnalysisOutput("C", "3", "0", emptyList(), "Test", 8)

        assertEquals("Low", low.riskCategory)
        assertEquals("Moderate", mod.riskCategory)
        assertEquals("High", high.riskCategory)
    }
}
