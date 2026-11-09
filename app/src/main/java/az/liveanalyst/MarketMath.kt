
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

data class OutcomeOdds(
    val name: String,
    val odds: Double
)

data class OutcomeAnalysis(
    val outcome: String,
    val stake: Double,
    val totalReturn: Double,
    val netProfit: Double
)

data class MultiOutcomeResult(
    val isArbitrage: Boolean,
    val probabilitySum: Double,
    val totalStake: Double,
    val outcomes: List<OutcomeAnalysis>,
    val minimumProfit: Double,
    val maximumProfit: Double,
    val profitPercent: Double,
    val message: String
)

object MarketMath {

    private val htFtOutcomes = listOf(
        "1/1",
        "1/X",
        "1/2",
        "X/1",
        "X/X",
        "X/2",
        "2/1",
        "2/X",
        "2/2"
    )

    private fun validOdds(odds: Double): Boolean {
        return odds.isFinite() && odds > 1.0 && odds < 1001.0
    }

    private fun validStake(stake: Double): Boolean {
        return stake.isFinite() && stake > 0.0
    }

    fun inspect(odds: List<Double>): MarketQuality {
        val valid = odds.filter { validOdds(it) }

        if (valid.size < 2 || valid.size != odds.size) {
            return MarketQuality(
                null,
                null,
                "Əmsallar natamam və ya etibarsızdır."
            )
        }

        val sum = valid.sumOf { 1.0 / it }
        val margin = (sum - 1.0) * 100.0

        return MarketQuality(
            sum,
            margin,
            "Təxmini bukmeker marjası: ${
                "%.2f".format(Locale.US, margin)
            }%"
        )
    }

    fun calculateArbitrage(
        odds: List<Double>,
        totalStake: Double
    ): ArbitrageResult? {

        if (odds.size < 2 || !validStake(totalStake)) {
            return null
        }

        if (odds.any { !validOdds(it) }) {
            return null
        }

        val sum = odds.sumOf { 1.0 / it }

        if (!sum.isFinite() || sum <= 0.0) {
            return null
        }

        val stakes = odds.map {
            totalStake * (1.0 / it) / sum
        }

        val returns = odds.mapIndexed { index, odd ->
            stakes[index] * odd
        }

        val minimumReturn = returns.minOrNull() ?: return null
        val netProfit = minimumReturn - totalStake
        val isArbitrage = sum < 1.0

        return ArbitrageResult(
            isArbitrage = isArbitrage,
            totalStake = totalStake,
            stakes = stakes,
            returns = returns,
            netProfit = netProfit,
            profitPercent = netProfit / totalStake * 100.0,
            message = when {
                isArbitrage ->
                    "RİYAZİ ARBİTRAJ VAR. Bazar və bütün nəticələr təsdiqlənməlidir."

                else ->
                    "Arbitraj üstünlüyü yoxdur."
            }
        )
    }

    // Ümumi çoxnəticəli bazar hesablaması.
    // Bütün mümkün nəticələr siyahıda olmalıdır.
    fun calculateMultiOutcomeArbitrage(
        outcomes: List<OutcomeOdds>,
        totalStake: Double
    ): MultiOutcomeResult? {

        if (outcomes.size < 2 || !validStake(totalStake)) {
            return null
        }

        if (outcomes.any {
            it.name.isBlank() || !validOdds(it.odds)
        }) {
            return null
        }

        if (outcomes.map { it.name }.distinct().size != outcomes.size) {
            return null
        }

        val probabilitySum = outcomes.sumOf {
            1.0 / it.odds
        }

        if (!probabilitySum.isFinite() || probabilitySum <= 0.0) {
            return null
        }

        val analyses = outcomes.map { outcome ->
            val stake = totalStake *
                (1.0 / outcome.odds) / probabilitySum

            val totalReturn = stake * outcome.odds

            OutcomeAnalysis(
                outcome = outcome.name,
                stake = stake,
                totalReturn = totalReturn,
                netProfit = totalReturn - totalStake
            )
        }

        val minimumProfit =
            analyses.minOf { it.netProfit }

        val maximumProfit =
            analyses.maxOf { it.netProfit }

        val isArbitrage = probabilitySum < 1.0

        return MultiOutcomeResult(
            isArbitrage = isArbitrage,
            probabilitySum = probabilitySum,
            totalStake = totalStake,
            outcomes = analyses,
            minimumProfit = minimumProfit,
            maximumProfit = maximumProfit,
            profitPercent = minimumProfit / totalStake * 100.0,
            message = if (isArbitrage) {
                "RİYAZİ ARBİTRAJ VAR. Bütün nəticələri və əmsalları yoxla."
            } else {
                "Bütün nəticələr əhatə edilsə belə, arbitraj üstünlüyü yoxdur."
            }
        )
    }

    // Taym/Matç: 9 nəticənin hamısı ayrıca hesablanır.
    fun calculateHtFtArbitrage(
        odds: List<Double>,
        totalStake: Double
    ): MultiOutcomeResult? {

        if (odds.size != 9) return null

        val outcomes = htFtOutcomes.mapIndexed { index, name ->
            OutcomeOdds(
                name = name,
                odds = odds[index]
            )
        }

        return calculateMultiOutcomeArbitrage(
            outcomes = outcomes,
            totalStake = totalStake
        )
    }

    // İki nəticəli bazarda ilkin mərcə qarşı sığorta.
    fun calculateHedge(
        originalStake: Double,
        originalOdds: Double,
        hedgeOdds: Double
    ): HedgeResult? {

        if (!validStake(originalStake)) return null
        if (!validOdds(originalOdds) || !validOdds(hedgeOdds)) {
            return null
        }

        val originalReturn = originalStake * originalOdds
        val hedgeStake = originalReturn / hedgeOdds
        val totalStake = originalStake + hedgeStake
        val hedgeReturn = hedgeStake * hedgeOdds

        if (!hedgeStake.isFinite() || !totalStake.isFinite()) {
            return null
        }

        return HedgeResult(
            hedgeStake = hedgeStake,
            totalStake = totalStake,
            returnIfOriginalWins = originalReturn,
            returnIfHedgeWins = hedgeReturn,
            netIfOriginalWins = originalReturn - totalStake,
            netIfHedgeWins = hedgeReturn - totalStake
        )
    }

    // Hazır portfelin hər mümkün nəticədə xalis gəliri.
    fun calculatePortfolio(
        totalStakes: Double,
        returnsByOutcome: Map<String, Double>
    ): List<OutcomeAnalysis>? {

        if (!totalStakes.isFinite() || totalStakes < 0.0) {
            return null
        }

        if (returnsByOutcome.isEmpty()) return null

        if (returnsByOutcome.any {
            it.key.isBlank() ||
            !it.value.isFinite() ||
            it.value < 0.0
        }) {
            return null
        }

        return returnsByOutcome.map { (name, returned) ->
            OutcomeAnalysis(
                outcome = name,
                stake = 0.0,
                totalReturn = returned,
                netProfit = returned - totalStakes
            )
        }
    }

    fun scenarioNet(
        totalStakes: Double,
        winningReturns: Double
    ): Double? {

        if (!totalStakes.isFinite() ||
            !winningReturns.isFinite() ||
            totalStakes < 0.0 ||
            winningReturns < 0.0
        ) {
            return null
        }

        return winningReturns - totalStakes
    }

    fun formatMoney(value: Double): String {
        if (!value.isFinite()) return "Hesablana bilmədi"

        return "%.2f AZN".format(Locale.US, value)
    }
}
