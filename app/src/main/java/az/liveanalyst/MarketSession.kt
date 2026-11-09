
package az.liveanalyst

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedMarketPage(
    val pageNumber: Int,
    val savedAt: Long,
    val snapshot: ScreenSnapshot
)

object MarketSession {

    private val savedPages = mutableListOf<SavedMarketPage>()

    @Synchronized
    fun savePage(snapshot: ScreenSnapshot): SavedMarketPage {
        val page = SavedMarketPage(
            pageNumber = savedPages.size + 1,
            savedAt = System.currentTimeMillis(),
            snapshot = snapshot
        )

        savedPages.add(page)
        return page
    }

    @Synchronized
    fun getPages(): List<SavedMarketPage> {
        return savedPages.toList()
    }

    @Synchronized
    fun getPageCount(): Int {
        return savedPages.size
    }

    @Synchronized
    fun clear() {
        savedPages.clear()
    }

    @Synchronized
    fun createReport(): String {
        if (savedPages.isEmpty()) {
            return "Hələ heç bir mərc səhifəsi yadda saxlanılmayıb."
        }

        val dateFormat = SimpleDateFormat(
            "HH:mm:ss",
            Locale.getDefault()
        )

        val report = StringBuilder()

        report.append("ÜMUMİ MƏRC TƏHLİLİ\n")
        report.append("Yadda saxlanılan səhifələr: ${savedPages.size}\n")
        report.append("--------------------------------\n")

        savedPages.forEach { page ->
            report.append("\nSƏHİFƏ ${page.pageNumber}\n")
            report.append(
                "Vaxt: ${dateFormat.format(Date(page.savedAt))}\n"
            )

            val snapshot = page.snapshot

            report.append(
                "Hesab: ${snapshot.score ?: "Tanınmadı"}\n"
            )

            report.append(
                "Oyun vaxtı: ${snapshot.minute ?: "Tanınmadı"}\n"
            )

            if (snapshot.markets.isEmpty()) {
                report.append("Mərc bazarı tanınmadı.\n")
            } else {
                snapshot.markets.forEach { market ->
                    report.append(
                        "${market.category} | " +
                        "${market.selection} | " +
                        "Əmsal: ${market.odds}\n"
                    )
                }
            }

            report.append("--------------------------------\n")
        }

        return report.toString()
    }
}
