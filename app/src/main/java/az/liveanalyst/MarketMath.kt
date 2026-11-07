
package az.liveanalyst

import java.util.Locale

data class MarketQuality(
    val impliedProbabilitySum: Double?,
    val overroundPercent: Double?,
    val warning: String
)

object MarketMath {

    // Yalnız ekranda görünən əmsallardan istifadə edir.
    // Real ehtimal və qazanc zəmanəti hesablamır.

    fun inspect(odds: List<Double>): MarketQuality {

        val valid = odds.filter {
            it.isFinite() && it > 1.0
        }

        if (valid.size < 2) {
            return MarketQuality(
                null,
                null,
                "Tam nəticə dəsti görünmür"
            )
        }

        val sum = valid.sumOf { 1.0 / it }

        if (!sum.isFinite() || sum <= 0.0) {
            return MarketQuality(
                null,
                null,
                "Əmsallar oxunmayıb"
            )
        }

        val margin = (sum - 1.0) * 100.0

        return MarketQuality(
            sum,
            margin,
            "Təxmini bukmeker marjası: ${
                "%.1f".format(Locale.US, margin)
            }%"
        )
    }

    // Qarşı mərc üçün nəzəri məbləğ.
    fun hedgeStake(
        existingStake: Double,
        existingOdds: Double,
        hedgeOdds: Double
    ): Double? {

        if (
            existingStake <= 0 ||
            existingOdds <= 1 ||
            hedgeOdds <= 1
        ) {
            return null
        }

        return existingStake * existingOdds / hedgeOdds
    }

    // Ssenarinin xalis maliyyə nəticəsi.
    fun scenarioNet(
        totalStakes: Double,
        winningReturns: Double
    ): Double? {

        if (totalStakes < 0 || winningReturns < 0) {
            return null
        }

        return winningReturns - totalStakes
    }
}
