
package az.liveanalyst

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import java.util.Locale
import kotlin.math.abs

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
        Regex("""(?<!\d)(\d{1,2})\s*[-:]\s*(\d{1,2})(?!\d)""")

    private val minuteRegex =
        Regex("""(?<!\d)(\d{1,3})\s*['’′]""")

    private val categories = linkedMapOf(
        "TAYM/MATÇ" to listOf("taym/matç", "ht/ft", "half time/full time"),
        "1X2" to listOf("1x2", "oyun nəticəsi", "match result"),
        "İKİLİ ŞANS" to listOf("ikili şans", "double chance"),
        "QOL ÜST/ALT" to listOf("üst/alt", "over/under", "toplam", "total goals"),
        "HƏR İKİ KOMANDA QOL VURAR" to listOf("hər iki komanda", "both teams", "btts"),
        "HANDİKAP" to listOf("handikap", "handicap"),
        "DƏQİQ HESAB" to listOf("dəqiq hesab", "correct score"),
        "NÖVBƏTİ QOL" to listOf("növbəti qol", "next goal"),
        "KÜNC ZƏRBƏLƏRİ" to listOf("künc", "corner"),
        "VƏRƏQƏLƏR" to listOf("vərəq", "cards"),
        "HİSSƏ NƏTİCƏSİ" to listOf("1-ci hissə", "2-ci hissə", "birinci hissə", "ikinci hissə")
    )

    fun parse(result: Text): ScreenSnapshot {
        val lines = mutableListOf<OcrLine>()

        result.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val bounds = line.boundingBox ?: Rect(0, 0, 0, 0)
                lines.add(OcrLine(line.text.trim(), Rect(bounds)))
            }
        }

        return parseLines(lines)
    }

    fun parse(text: String): ScreenSnapshot {
        val lines = text.lines()
            .filter { it.isNotBlank() }
            .mapIndexed { index, value ->
                OcrLine(value.trim(), Rect(0, index * 50, 1000, index * 50 + 40))
            }

        return parseLines(lines)
    }

    private fun parseLines(lines: List<OcrLine>): ScreenSnapshot {
        val score = lines.firstNotNullOfOrNull {
            scoreRegex.find(it.text)?.let { match ->
                "${match.groupValues[1]}-${match.groupValues[2]}"
            }
        }

        val minute = lines.firstNotNullOfOrNull {
            minuteRegex.find(it.text)?.groupValues?.get(1)?.let { value ->
                "$value′"
            }
        }

        val marketLines = lines.mapNotNull { line ->
            val odds = oddsRegex.findAll(line.text)
                .mapNotNull {
                    it.groupValues[1].replace(',', '.').toDoubleOrNull()
                }
                .filter { it > 1.0 && it < 1001.0 }
                .toList()

            if (odds.isEmpty()) null else line to odds
        }

        val markets = marketLines.map { (oddsLine, odds) ->
            val nearby = lines
                .filter { candidate ->
                    candidate !== oddsLine &&
                    abs(candidate.bounds.centerY() - oddsLine.bounds.centerY()) < 180
                }
                .sortedBy {
                    abs(it.bounds.centerY() - oddsLine.bounds.centerY())
                }

            val categoryLine = nearby.firstOrNull { candidate ->
                categories.values.flatten().any {
                    candidate.text.lowercase(Locale.ROOT).contains(it)
                }
            }

            val category = categoryLine?.let { candidate ->
                categories.entries.firstOrNull { (_, terms) ->
                    terms.any {
                        candidate.text.lowercase(Locale.ROOT).contains(it)
                    }
                }?.key
            } ?: "TANINMAYAN BAZAR"

            val selection = nearby.firstOrNull { candidate ->
                candidate.bounds.centerY() <= oddsLine.bounds.centerY() &&
                candidate.text.length < 80 &&
                categories.values.flatten().none {
                    candidate.text.lowercase(Locale.ROOT).contains(it)
                } &&
                oddsRegex.find(candidate.text) == null
            }?.text ?: "Seçim tanınmadı"

            ParsedMarket(
                category = category,
                odds = odds,
                sourceLine = oddsLine.text,
                selection = selection,
                top = oddsLine.bounds.top
            )
        }

        return ScreenSnapshot(score, minute, markets)
    }
}

object MarketAdvisor {

    fun render(snapshot: ScreenSnapshot): String {
        val out = StringBuilder()

        out.append("LIVE ANALYST • AĞILLI OCR\n")
        out.append("Hesab: ${snapshot.score ?: "tanınmadı"}\n")
        out.append("Dəqiqə: ${snapshot.minute ?: "tanınmadı"}\n")
        out.append("Əmsal sətrləri: ${snapshot.markets.size}\n\n")

        if (snapshot.markets.isEmpty()) {
            out.append("Əmsal aşkarlanmadı.\n")
        } else {
            snapshot.markets
                .sortedBy { it.top }
                .take(12)
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
        out.append("Bütün nəticələr və bazar uyğunluğu təsdiqlənmədən arbitraj siqnalı verilmir.\n")
        out.append("Avtomatik mərc tövsiyəsi verilmir.")

        return out.toString()
    }
}
