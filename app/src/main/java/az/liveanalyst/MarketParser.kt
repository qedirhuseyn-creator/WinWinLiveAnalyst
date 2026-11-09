
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
        val bounds: Rect
    )

    private val oddsRegex =
        Regex("""(?<![\d.,])(\d{1,3}[.,]\d{2,3})(?!\d)""")

    private val scoreRegex =
        Regex("""(?<!\d)(\d{1,2})\s*[-–]\s*(\d{1,2})(?!\d)""")

    private val minuteRegex =
        Regex("""(?<!\d)(\d{1,3})\s*[:.]\s*(\d{2})(?!\d)|(?<!\d)(\d{1,3})\s*['’′]""")

    private val categories = linkedMapOf(
        "TAYM/MATÇ" to listOf(
            "taym/matç", "ht/ft", "half time/full time",
            "hissə/matç", "hissə/matç nəticəsi"
        ),
        "1X2" to listOf(
            "1x2", "oyun nəticəsi", "match result",
            "matç nəticəsi"
        ),
        "İKİLİ ŞANS" to listOf(
            "ikili şans", "double chance"
        ),
        "QOL ÜST/ALT" to listOf(
            "üst/alt", "over/under", "toplam qol",
            "total goals", "toplam"
        ),
        "HƏR İKİ KOMANDA QOL VURAR" to listOf(
            "hər iki komanda", "both teams", "btts"
        ),
        "HANDİKAP" to listOf(
            "handikap", "handicap"
        ),
        "DƏQİQ HESAB" to listOf(
            "dəqiq hesab", "correct score"
        ),
        "NÖVBƏTİ QOL" to listOf(
            "növbəti qol", "next goal"
        ),
        "KÜNC ZƏRBƏLƏRİ" to listOf(
            "künc", "corner"
        ),
        "VƏRƏQƏLƏR" to listOf(
            "vərəq", "cards"
        ),
        "HİSSƏ NƏTİCƏSİ" to listOf(
            "1-ci hissə", "2-ci hissə",
            "birinci hissə", "ikinci hissə"
        )
    )

    fun parse(result: Text): ScreenSnapshot {
        val lines = mutableListOf<OcrLine>()

        result.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val bounds = line.boundingBox ?: Rect(0, 0, 0, 0)
                lines.add(
                    OcrLine(
                        line.text.trim(),
                        Rect(bounds)
                    )
                )
            }
        }

        return parseLines(lines)
    }

    fun parse(text: String): ScreenSnapshot {
        val lines = text.lines()
            .filter { it.isNotBlank() }
            .mapIndexed { index, value ->
                OcrLine(
                    value.trim(),
                    Rect(0, index * 50, 1000, index * 50 + 40)
                )
            }

        return parseLines(lines)
    }

    private fun parseLines(lines: List<OcrLine>): ScreenSnapshot {

        // Oyun vaxtını ayrıca tapırıq.
        val minute = lines.firstNotNullOfOrNull { line ->
            val match = minuteRegex.find(line.text) ?: return@firstNotNullOfOrNull null

            val minuteValue = match.groupValues[1]
                .ifBlank { match.groupValues[3] }

            val secondValue = match.groupValues[2]

            val value = minuteValue.toIntOrNull()
                ?: return@firstNotNullOfOrNull null

            if (value in 0..150) {
                if (secondValue.isNotBlank()) {
                    "$minuteValue:$secondValue"
                } else {
                    "$minuteValue′"
                }
            } else null
        }

        // Hesab yalnız tire ilə ayrılmış və real futbol hesabı
        // diapazonunda olan rəqəmlərdən seçilir.
        val score = lines.firstNotNullOfOrNull { line ->
            val match = scoreRegex.find(line.text)
                ?: return@firstNotNullOfOrNull null

            val home = match.groupValues[1].toIntOrNull()
                ?: return@firstNotNullOfOrNull null

            val away = match.groupValues[2].toIntOrNull()
                ?: return@firstNotNullOfOrNull null

            if (home in 0..15 && away in 0..15) {
                "$home-$away"
            } else {
                null
            }
        }

        val marketLines = lines.mapNotNull { line ->
            val odds = oddsRegex.findAll(line.text)
                .mapNotNull {
                    it.groupValues[1]
                        .replace(',', '.')
                        .toDoubleOrNull()
                }
                .filter {
                    it.isFinite() && it > 1.0 && it < 1001.0
                }
                .toList()

            if (odds.isEmpty()) null else line to odds
        }

        val markets = marketLines.map { (oddsLine, odds) ->

            val nearby = lines
                .filter { candidate ->
                    candidate !== oddsLine &&
                        candidate.bounds.centerY() > 0 &&
                        absDistance(
                            candidate.bounds.centerY(),
                            oddsLine.bounds.centerY()
                        ) < 130
                }
                .sortedBy {
                    absDistance(
                        it.bounds.centerY(),
                        oddsLine.bounds.centerY()
                    )
                }

            val categoryLine = nearby.firstOrNull { candidate ->
                findCategory(candidate.text) != null
            }

            val category = categoryLine?.let {
                findCategory(it.text)
            } ?: "TANINMAYAN BAZAR"

            val selection = nearby.firstOrNull { candidate ->
                candidate.bounds.centerY() <= oddsLine.bounds.centerY() &&
                    candidate.text.length in 2..70 &&
                    findCategory(candidate.text) == null &&
                    oddsRegex.find(candidate.text) == null &&
                    scoreRegex.find(candidate.text) == null
            }?.text ?: "Seçim tanınmadı"

            ParsedMarket(
                category = category,
                odds = odds,
                sourceLine = oddsLine.text,
                selection = selection,
                top = oddsLine.bounds.top
            )
        }

        return ScreenSnapshot(
            score = score,
            minute = minute,
            markets = markets
        )
    }

    private fun findCategory(text: String): String? {
        val normalized = text.lowercase(Locale.ROOT)

        return categories.entries.firstOrNull { entry ->
            entry.value.any { term ->
                normalized.contains(term)
            }
        }?.key
    }

    private fun absDistance(a: Int, b: Int): Int {
        return kotlin.math.abs(a - b)
    }
}

object MarketAdvisor {

    fun render(snapshot: ScreenSnapshot): String {
        val out = StringBuilder()

        out.append("LIVE ANALYST • AĞILLI OCR\n")
        out.append("Hesab: ${snapshot.score ?: "təsdiqlənmədi"}\n")
        out.append("Dəqiqə: ${snapshot.minute ?: "təsdiqlənmədi"}\n")
        out.append("Əmsal sətrləri: ${snapshot.markets.size}\n\n")

        if (snapshot.markets.isEmpty()) {
            out.append("Əmsal aşkarlanmadı.\n")
        } else {
            snapshot.markets
                .sortedBy { it.top }
                .take(15)
                .forEach { market ->

                    val odds = market.odds.joinToString(" / ") {
                        "%.2f".format(Locale.US, it)
                    }

                    out.append("${market.category}\n")
                    out.append("Seçim: ${market.selection}\n")
                    out.append("Əmsal: $odds\n")
                    out.append("OCR: ${market.sourceLine}\n\n")
                }
        }

        out.append("HESABLAMA STATUSU\n")
        out.append(
            "OCR nəticələri yoxlanılmalıdır. " +
                "Bazarın bütün seçimləri təsdiqlənmədən arbitraj hesablanmır.\n"
        )

        return out.toString()
    }
}
