package com.tradelens.local.ai

import org.json.JSONArray
import org.json.JSONObject

data class AnalysisOutput(
    val ticker: String,
    val price: String,
    val change: String,
    val indicators: List<String>,
    val summary: String,
    val risk: Int
)

fun AnalysisOutput.toDisplayText(): String = buildString {
    append(summary)
    if (change.isNotBlank()) append("\n\nChange: ").append(change)
    if (indicators.isNotEmpty()) {
        append("\n\nIndicators: ")
        append(indicators.joinToString(", "))
    }
}

object AnalysisPrompt {
    fun build(screenText: String): String =
        "You are a technical analysis assistant. Given stock screen text, extract " +
            "ticker, price, change, indicators. Give 3-sentence neutral read and risk 1-10. " +
            "Never recommend buying or selling. Respond ONLY with JSON: " +
            """{"ticker":"","price":"","change":"","indicators":[],"summary":"","risk":0}""" +
            "\nScreen text:\n${screenText.take(4_000)}"

    fun retry(screenText: String): String =
        "Respond only with JSON:\n${build(screenText)}"

    fun parse(response: String): AnalysisOutput? {
        val cleaned = response.trim()
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
        val firstBrace = cleaned.indexOf('{')
        val lastBrace = cleaned.lastIndexOf('}')
        if (firstBrace < 0 || lastBrace <= firstBrace) return null

        return runCatching {
            val json = JSONObject(cleaned.substring(firstBrace, lastBrace + 1))
            val indicatorsJson = json.optJSONArray("indicators") ?: JSONArray()
            val indicators = buildList {
                for (index in 0 until indicatorsJson.length()) {
                    val value = indicatorsJson.optString(index).trim()
                    if (value.isNotEmpty()) add(value)
                }
            }
            val summary = json.optString("summary").trim()
            if (summary.isEmpty()) return null
            AnalysisOutput(
                ticker = json.optString("ticker").trim(),
                price = json.optString("price").trim(),
                change = json.optString("change").trim(),
                indicators = indicators,
                summary = summary,
                risk = json.optInt("risk", 1).coerceIn(1, 10)
            )
        }.getOrNull()
    }
}
