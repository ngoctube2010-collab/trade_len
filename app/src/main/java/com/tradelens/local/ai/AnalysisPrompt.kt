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
) {
    val riskCategory: String
        get() = when (risk) {
            in 1..3 -> "Low"
            in 4..6 -> "Moderate"
            in 7..10 -> "High"
            else -> "Unrated"
        }
}

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
        "You are a technical analysis assistant. Given stock or crypto screen text, extract " +
            "ticker, price, change, indicators. Give 3-sentence neutral read and risk 1-10. " +
            "Never recommend buying or selling. Respond ONLY with a valid JSON object in this format:\n" +
            """{"ticker":"","price":"","change":"","indicators":[],"summary":"","risk":5}""" +
            "\n\nScreen text:\n${screenText.take(4_000)}\n\nJSON:"

    fun retry(screenText: String): String =
        "Respond ONLY with the JSON object, no explanation:\n${build(screenText)}"

    fun parse(response: String): AnalysisOutput? {
        val trimmed = response.trim()
        if (trimmed.isEmpty()) return null

        // 1. Remove markdown code blocks if present
        val stripped = trimmed
            .replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()

        val firstBrace = stripped.indexOf('{')
        val lastBrace = stripped.lastIndexOf('}')

        // 2. Try standard JSON parsing if braces exist
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            val jsonCandidate = stripped.substring(firstBrace, lastBrace + 1)
            val parsed = runCatching {
                val json = JSONObject(jsonCandidate)
                val indicatorsJson = json.optJSONArray("indicators") ?: JSONArray()
                val indicators = buildList {
                    for (index in 0 until indicatorsJson.length()) {
                        val value = indicatorsJson.optString(index).trim()
                        if (value.isNotEmpty()) add(value)
                    }
                }
                val summary = json.optString("summary").trim()
                if (summary.isNotEmpty()) {
                    AnalysisOutput(
                        ticker = json.optString("ticker").trim(),
                        price = json.optString("price").trim(),
                        change = json.optString("change").trim(),
                        indicators = indicators,
                        summary = summary,
                        risk = json.optInt("risk", 5).coerceIn(1, 10)
                    )
                } else null
            }.getOrNull()

            if (parsed != null) return parsed
        }

        // 3. Resilient regex fallback for malformed/truncated JSON or unescaped newlines
        return parseWithRegexFallback(stripped)
    }

    private fun parseWithRegexFallback(text: String): AnalysisOutput? {
        val ticker = extractField(text, "ticker")
        val price = extractField(text, "price")
        val change = extractField(text, "change")
        val risk = Regex(""""risk"\s*:\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: 5

        // Extract indicators array
        val indicators = mutableListOf<String>()
        val indMatch = Regex(""""indicators"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(text)
        if (indMatch != null) {
            val rawList = indMatch.groupValues[1]
            Regex(""""([^"]+)"""").findAll(rawList).forEach { match ->
                val item = match.groupValues[1].trim()
                if (item.isNotEmpty()) indicators.add(item)
            }
        }

        // Extract summary field or fallback to non-empty text
        var summary = extractField(text, "summary")
        if (summary.isBlank()) {
            // If model returned plain text rather than JSON
            val cleanText = text.replace(Regex("""\{[^}]*\}"""), "").trim()
            if (cleanText.length >= 15) {
                summary = cleanText.lines().filter { it.isNotBlank() }.take(3).joinToString(" ")
            }
        }

        if (summary.isBlank() && ticker.isBlank() && price.isBlank()) return null

        return AnalysisOutput(
            ticker = ticker,
            price = price,
            change = change,
            indicators = indicators,
            summary = summary.ifBlank { "Analysis extracted from screen." },
            risk = risk.coerceIn(1, 10)
        )
    }

    private fun extractField(source: String, fieldName: String): String {
        // Matches "fieldName": "value" allowing escaped quotes and newlines
        val pattern = Regex(""""$fieldName"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val match = pattern.find(source)
        if (match != null) {
            return match.groupValues[1]
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .trim()
        }
        // Also check if field was cut off at the end without closing quote
        val unclosedPattern = Regex(""""$fieldName"\s*:\s*"([^"\r\n]+)""")
        return unclosedPattern.find(source)?.groupValues?.get(1)?.trim().orEmpty()
    }
}
