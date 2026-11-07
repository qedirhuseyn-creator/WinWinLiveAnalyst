
package az.liveanalyst

data class ParsedMarket(
    val category: String,
    val odds: List<Double>,
    val sourceLine: String
)

data class ScreenSnapshot(
    val score: String?,
    val minute: String?,
    val markets: List<ParsedMarket>
)

object MarketParser {

    private val oddsRegex =
        Regex("""(?<![\d.,])(?:[1-9]\d{0,2}|[1-9]\d?)[.,]\d{2,3}(?!\d)""")

    private val scoreRegex =
        Regex("""(?<!\d)(\d{1,2})\s*[-:]\s*(\d{1,2})(?!\d)""")

    private val minuteRegex =
        Regex("""(?<!\d)(\d{1,3})\s*['’′]""")

    private val categories = linkedMapOf(
        "1X2 / OYUN NƏTİCƏSİ" to listOf(
            "1x2", "oyun nətic", "matç nətic",
            "match result", "full time result"
        ),
        "İKİLİ ŞANS" to listOf(
            "ikiqat şans", "double chance"
        ),
        "QOL ÜST/ALT" to listOf(
            "üst/alt", "over/under", "total goals",
            "ümumi qol", "qol sayı"
        ),
        "HƏR İKİ KOMANDA QOL VURAR" to listOf(
            "hər iki komanda", "both teams to score", "btts"
        ),
        "HANDİKAP" to listOf(
            "handikap", "handicap", "asian handicap"
        ),
        "TAYM/MATÇ" to listOf(
            "taym/matç", "half time/full time", "ht/ft"
        ),
        "HİSSƏ NƏTİCƏSİ" to listOf(
            "1-ci hissə", "birinci hissə",
            "2-ci hissə", "ikinci hissə",
            "half time", "2nd half"
        ),
        "NÖVBƏTİ QOL" to listOf(
            "növbəti qol", "next goal", "team to score next"
        ),
        "DƏQİQ HESAB" to listOf(
            "dəqiq hesab", "correct score"
        ),
        "KÜNC ZƏRBƏLƏRİ" to listOf(
            "künc", "corner", "corners"
        ),
        "VƏRƏQƏLƏR" to listOf(
            "sarı vərəq", "qırmızı vərəq",
            "cards", "booking points"
        ),
        "OYUNÇU BAZARLARI" to listOf(
            "player", "oyunçu", "shots",
            "shots on target", "assist"
        )
    )

    fun parse(text: String): ScreenSnapshot {

        val lines = text.lines()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .distinct()

        val score = lines.firstNotNullOfOrNull {
            scoreRegex.find(it)?.let { match ->
                "${match.groupValues[1]}-${match.groupValues[2]}"
            }
        }

        val minute = lines.firstNotNullOfOrNull {
            minuteRegex.find(it)?.groupValues?.get(1)?.let { value ->
                "$value′"
            }
        }

        val markets = lines.mapNotNull { line ->

            val odds = oddsRegex.findAll(line)
                .mapNotNull {
                    it.value.replace(',', '.')
                        .toDoubleOrNull()
                        ?.takeIf { number ->
                            number > 1.0 && number < 1001.0
                        }
                }
                .toList()

            if (odds.isEmpty()) return@mapNotNull null

            val lower = line.lowercase()

            val category = categories.entries.firstOrNull {
                (_, terms) ->
                terms.any { lower.contains(it) }
            }?.key ?: "DİGƏR / TANINMAYAN BAZAR"

            ParsedMarket(category, odds, line)
        }

        return ScreenSnapshot(score, minute, markets)
    }
}

object MarketAdvisor {

    fun render(snapshot: ScreenSnapshot): String {

        val out = StringBuilder()

        out.append("LIVE ANALYST • OCR\n")
        out.append(
            "Hesab: ${snapshot.score ?: "tanınmadı"} | " +
            "Dəqiqə: ${snapshot.minute ?: "tanınmadı"}\n"
        )

        out.append(
            "Görünən bazar sətrləri: ${snapshot.markets.size}\n\n"
        )

        if (snapshot.markets.isEmpty()) {

            out.append(
                "Əmsal tapılmadı. Bazarı ekranda açıq saxla.\n"
            )

        } else {

            snapshot.markets.take(8).forEach { market ->

                val odds = market.odds.joinToString(" / ") {
                    "%.2f".format(java.util.Locale.US, it)
                }

                val quality = MarketMath.inspect(market.odds)

                out.append("${market.category}\n")
                out.append("$odds\n")
                out.append("${quality.warning}\n\n")
            }
        }

        out.append(
            "QƏRAR: Əmsal təkbaşına qazanc üstünlüyünü sübut etmir.\n"
        )

        out.append(
            "OCR səhvləri və görünməyən seçimlər mümkündür. " +
            "Tam bazar yoxlanmadan mərc siqnalı verilmir."
        )

        return out.toString()
    }
}
