
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
        Regex("""(?<![\d.,])(?:\d{1,3}[.,]\d{2,3})(?!\d)""")

    private val scoreRegex =
        Regex("""(?<!\d)(\d{1,2})\s*[-:]\s*(\d{1,2})(?!\d)""")

    private val minuteRegex =
        Regex("""(?<!\d)(\d{1,3})\s*['’′]""")

    private val categories = linkedMapOf(
        "TAYM/MATÇ" to listOf("taym/matç", "ht/ft", "half time/full time"),
        "1X2 / OYUN NƏTİCƏSİ" to listOf("1x2", "oyun nəticəsi", "matç nəticəsi", "match result"),
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

    fun parse(text: String): ScreenSnapshot {
        val lines = text.lines()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }

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

        var currentCategory = "TANINMAYAN BAZAR"
        val markets = mutableListOf<ParsedMarket>()

        for (line in lines) {
            val lower = line.lowercase()

            val detectedCategory = categories.entries.firstOrNull {
                (_, terms) -> terms.any { lower.contains(it) }
            }?.key

            if (detectedCategory != null) {
                currentCategory = detectedCategory
            }

            val odds = oddsRegex.findAll(line)
                .mapNotNull {
                    it.value.replace(',', '.').toDoubleOrNull()
                }
                .filter { it > 1.0 && it < 1001.0 }
                .toList()

            if (odds.isNotEmpty()) {
                markets.add(
                    ParsedMarket(
                        category = currentCategory,
                        odds = odds,
                        sourceLine = line
                    )
                )
            }
        }

        return ScreenSnapshot(score, minute, markets)
    }
}

object MarketAdvisor {
    fun render(snapshot: ScreenSnapshot): String {
        val out = StringBuilder()

        out.append("LIVE ANALYST • OCR\n")
        out.append("Hesab: ${snapshot.score ?: "tanınmadı"}\n")
        out.append("Dəqiqə: ${snapshot.minute ?: "tanınmadı"}\n")
        out.append("Tapılan əmsal sətrləri: ${snapshot.markets.size}\n\n")

        if (snapshot.markets.isEmpty()) {
            out.append("Əmsal aşkarlanmadı. Bazarları ekranda açıq saxla.\n")
        } else {
            snapshot.markets.take(12).forEach { market ->
                val odds = market.odds.joinToString(" / ") {
                    "%.2f".format(java.util.Locale.US, it)
                }

                out.append("${market.category}\n")
                out.append("Əmsallar: $odds\n")
                out.append("Mənbə: ${market.sourceLine}\n\n")
            }
        }

        out.append("TƏHLİL:\n")
        out.append("Əmsalların tam nəticə dəsti və alternativ bukmeker qiymətləri olmadan arbitraj təsdiqlənmir.\n")
        out.append("Sığorta məbləği yalnız bütün mümkün nəticələr və xalis gəlir hesablandıqdan sonra müəyyən edilə bilər.\n")
        out.append("Bu məlumat avtomatik mərc siqnalı deyil.")

        return out.toString()
    }
}
