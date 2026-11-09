
package az.liveanalyst

import java.util.Locale

data class MarketQuality(
    val impliedProbabilitySum: Double?,
    val overroundPercent: Double?,
    val warning: String
)

data class ArbitrageResult(
    val isArbitrage: Boolean,
    val totalStake: Double,
    val stakes: List<Double>,
    val returns: List<Double>,
    val netProfit: Double,
    val profitPercent: Double,
    val message: String
)

data class HedgeResult(
    val hedgeStake: Double,
    val totalStake: Double,
    val returnIfOriginalWins: Double,
    val returnIfHedgeWins: Double,
    val netIfOriginalWins: Double,
    val netIfHedgeWins: Double
)

object MarketMath {

    fun inspect(odds: List<Double>): MarketQuality {
        val valid = odds.filter {
            it.isFinite() && it > 1.0 && it < 1001.0
        }

        if (valid.size < 2) {
            return MarketQuality(
                null, null,
                "Tam nəticə dəsti görünmür"
            )
        }

        val sum = valid.sumOf { 1.0 / it }
        val margin = (sum - 1.0) * 100.0

        return MarketQuality(
            sum,
            margin,
            "Təxmini marja: ${
                "%.2f".format(Locale.US, margin)
            }%"
        )
    }

    fun calculateArbitrage(
        odds: List<Double>,
        totalStake: Double
    ): ArbitrageResult? {

        if (odds.size < 2) return null
        if (totalStake <= 0.0) return null

        if (odds.any {
            !it.isFinite() || it <= 1.0 || it >= 1001.0
        }) return null

        val probabilitySum = odds.sumOf { 1.0 / it }

        if (!probabilitySum.isFinite() || probabilitySum <= 0.0) {
            return null
        }

        val stakes = odds.map {
            totalStake * (1.0 / it) / probabilitySum
        }

        val returns = odds.mapIndexed { index, odd ->
            stakes[index] * odd
        }

        val minimumReturn = returns.minOrNull() ?: return null
        val netProfit = minimumReturn - totalStake
        val profitPercent = netProfit / totalStake * 100.0

        val isArbitrage = probabilitySum < 1.0

        val message = when {
            isArbitrage ->
                "RİYAZİ ARBİTRAJ VAR. Əmsalları və bazar uyğunluğunu təsdiqlə."

            probabilitySum == 1.0 ->
                "Arbitraj üstünlüyü yoxdur."

            else ->
                "Arbitraj yoxdur. Bukmeker marjası mövcuddur."
        }

        return ArbitrageResult(
            isArbitrage = isArbitrage,
            totalStake = totalStake,
            stakes = stakes,
            returns = returns,
            netProfit = netProfit,
            profitPercent = profitPercent,
            message = message
        )
    }

    fun calculateHedge(
        originalStake: Double,
        originalOdds: Double,
        hedgeOdds: Double
    ): HedgeResult? {

        if (originalStake <= 0.0) return null
        if (originalOdds <= 1.0 || hedgeOdds <= 1.0) return null

        val originalReturn = originalStake * originalOdds
        val hedgeStake = originalReturn / hedgeOdds
        val totalStake = originalStake + hedgeStake

        val hedgeReturn = hedgeStake * hedgeOdds

        return HedgeResult(
            hedgeStake = hedgeStake,
            totalStake = totalStake,
            returnIfOriginalWins = originalReturn,
            returnIfHedgeWins = hedgeReturn,
            netIfOriginalWins = originalReturn - totalStake,
            netIfHedgeWins = hedgeReturn - totalStake
        )
    }

    fun scenarioNet(
        totalStakes: Double,
        winningReturns: Double
    ): Double? {
        if (totalStakes < 0.0 || winningReturns < 0.0) {
            return null
        }

        return winningReturns - totalStakes
    }

    fun formatMoney(value: Double): String {
        return "%.2f AZN".format(Locale.US, value)
    }
}
