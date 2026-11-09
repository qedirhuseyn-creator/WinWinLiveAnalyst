
package az.liveanalyst

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import java.util.Locale

data class ParsedMarket(
    val category: String,
    val odds: List<Double>,
    val sourceLine: String,
    val selection: String = "Seçim tanınmadı",
    val top: Int = 0
)

data class ScreenSnapshot(
    val score: String?,
    val minute: String?,
    val markets: List<ParsedMarket>
)

object MarketParser {

    private data class OcrLine(
        val text: String,
        val top: Int
    )

    private val oddsRegex =
        Regex("""(?<![\d.,])(\d{1,3}[.,]\d{2,3})(?!\d)""")

    private val scoreRegex =
        Regex("""(?<![\d:])(\d{1,2})\s*[-–]\s*(\d{1,2})(?![\d:])""")

    private val minuteRegex =
        Regex("""(?<!\d)(\d{1,3})\s*[:.]\s*(\d{2})(?!\d)|(?<!\d)(\d{1,3})\s*['’′]""")

    private val marketPatterns = listOf(
        "taym/matç" to listOf("taym/matç", "ht/ft", "half time/full time"),
        "1X2" to listOf("1x2", "matçın nəticəsi", "oyunun nəticəsi"),
        "İkiqat şans" to listOf("ikiqat şans", "double chance", "1x", "x2", "12"),
        "Üst/Alt" to listOf("üst/alt", "over/under", "total", "qol üst", "qol alt"),
        "Hər iki komanda qol vuracaq" to listOf("hər iki komanda", "both teams to score", "btts"),
        "Dəqiq hesab" to listOf("dəqiq hesab", "correct score"),
        "Növbəti qol" to listOf("növbəti qol", "next goal"),
        "Künc zərbələri" to listOf("künc", "corner", "corners"),
        "Kartlar" to listOf("kart", "cards", "sarı kart", "qırmızı kart"),
        "Handikap" to listOf("handikap", "handicap", "asian handicap"),
        "İlk hissə" to listOf("ilk hissə", "1-ci hissə", "first half"),
        "İkinci hissə" to listOf("ikinci hissə", "2-ci hissə", "second half"),
        "Komanda qolları" to listOf("komanda qolları", "team total"),
        "Qol sayı" to listOf("qol sayı", "goals")
    )

    fun parse(result: Text): ScreenSnapshot {
        val lines = mutableListOf<OcrLine>()

        result.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                lines.add(
                    OcrLine(
                        text = line.text.trim(),
                        top = line.boundingBox?.top ?: 0
                    )
                )
            }
        }

        return parseLines(lines)
    }

    fun parse(text: String): ScreenSnapshot {
        val lines = text.lines().mapIndexed { index, line ->
            OcrLine(line.trim(), index * 20)
        }

        return parseLines(lines)
    }

    private fun parseLines(
        lines: List<OcrLine>
    ): ScreenSnapshot {

        val allText = lines.joinToString(" ") { it.text }

        val minute = findMinute(lines)
        val score = findScore(lines, minute)

        val markets = mutableListOf<ParsedMarket>()

        lines.forEachIndexed { index, line ->
            val values = oddsRegex.findAll(line.text)
                .mapNotNull {
                    it.groupValues[1]
                        .replace(',', '.')
                        .toDoubleOrNull()
                }
                .filter { it in 1.01..1000.0 }
                .distinct()
                .toList()

            if (values.isEmpty()) return@forEachIndexed

            val contextStart = (index - 2).coerceAtLeast(0)
            val contextEnd = (index + 1).coerceAtMost(lines.lastIndex)

            val context = lines
                .subList(contextStart, contextEnd + 1)
                .joinToString(" ") { it.text }

            val category = detectCategory(context)
            val selection = detectSelection(line.text, context)

            markets.add(
                ParsedMarket(
                    category = category,
                    odds = values,
                    sourceLine = line.text,
                    selection = selection,
                    top = line.top
                )
            )
        }

        // Eyni sətirin təkrar oxunmasının qarşısını alır.
        val uniqueMarkets = markets.distinctBy {
            "${it.category}|${it.sourceLine}|${it.odds}"
        }

        return ScreenSnapshot(
            score = score,
            minute = minute,
            markets = uniqueMarkets
        )
    }

    private fun findMinute(lines: List<OcrLine>): String? {
        for (line in lines) {
            val match = minuteRegex.find(line.text) ?: continue

            val first = match.groupValues[1]
            val second = match.groupValues[2]

            if (first.isNotBlank() && second.isNotBlank()) {
                val minute = first.toIntOrNull() ?: continue
                val seconds = second.toIntOrNull() ?: continue

                if (minute <= 150 && seconds <= 59) {
                    return "$minute:$second"
                }
            } else {
                val minute = match.groupValues[3].toIntOrNull()
                    ?: continue

                if (minute <= 150) return "$minute'"
            }
        }

        return null
    }

    private fun findScore(
        lines: List<OcrLine>,
        minute: String?
    ): String? {

        for (line in lines) {
            val lower = line.text.lowercase(Locale.ROOT)

            // Vaxt və statistik göstəriciləri hesab kimi qəbul etmə.
            if (
                lower.contains("dəqiqə") ||
                lower.contains("minute") ||
                lower.contains("time") ||
                lower.contains("saniyə")
            ) continue

            if (minute != null &&
                line.text.contains(minute.replace(":", "-"))
            ) continue

            val match = scoreRegex.find(line.text) ?: continue

            val home = match.groupValues[1].toIntOrNull() ?: continue
            val away = match.groupValues[2].toIntOrNull() ?: continue

            if (home <= 20 && away <= 20) {
                return "$home-$away"
            }
        }

        return null
    }

    private fun detectCategory(context: String): String {
        val normalized = context.lowercase(Locale.ROOT)

        for ((category, patterns) in marketPatterns) {
            if (patterns.any { normalized.contains(it) }) {
                return category
            }
        }

        return "Digər bazar"
    }

    private fun detectSelection(
        line: String,
        context: String
    ): String {
        val lower = context.lowercase(Locale.ROOT)

        return when {
            lower.contains("ev sahibi") ||
                lower.contains("home") -> "Ev sahibi"

            lower.contains("qonaq") ||
                lower.contains("away") -> "Qonaq"

            lower.contains("heç-heçə") ||
                lower.contains("draw") -> "Heç-heçə"

            lower.contains("bəli") ||
                lower.contains("yes") -> "Bəli"

            lower.contains("xeyr") ||
                lower.contains("no") -> "Xeyr"

            lower.contains("üst") ||
                lower.contains("over") -> "Üst"

            lower.contains("alt") ||
                lower.contains("under") -> "Alt"

            else -> line
        }
    }
}

object MarketAdvisor {

    fun render(snapshot: ScreenSnapshot): String {
        val output = StringBuilder()

        output.append("WINWIN LIVE ANALYST\n")
        output.append("----------------------------\n")

        output.append(
            "Hesab: ${snapshot.score ?: "Tanınmadı"}\n"
        )

        output.append(
            "Oyun vaxtı: ${snapshot.minute ?: "Tanınmadı"}\n"
        )

        output.append("----------------------------\n")

        if (snapshot.markets.isEmpty()) {
            output.append(
                "Mərc bazarı hələ tanınmayıb.\n"
            )
        } else {
            snapshot.markets.forEachIndexed { index, market ->
                output.append("${index + 1}. ${market.category}\n")
                output.append("Seçim: ${market.selection}\n")
                output.append(
                    "Əmsallar: ${market.odds.joinToString(" | ")}\n"
                )
                output.append("Mənbə: ${market.sourceLine}\n")
                output.append("----------------------------\n")
            }
        }

        output.append(
            "Yadda saxlanılan səhifə: ${MarketSession.getPageCount()}\n"
        )

        output.append(
            "Qeyd: OCR nəticələrini kuponla tutuşdurmadan mərc qərarı vermə."
        )

        return output.toString()
    }
}
