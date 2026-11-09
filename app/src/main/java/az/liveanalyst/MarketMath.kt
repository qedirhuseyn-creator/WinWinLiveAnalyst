
package az.liveanalyst

import java.util.Locale
import kotlin.math.abs

data class MarketQuality(
    val impliedProbabilitySum: Double,
    val overroundPercent: Double,
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

enum class MarketKind {
    ONE_X_TWO,
    OVER_UNDER,
    BOTH_TEAMS_SCORE,
    HALF_FULL_TIME,
    CORRECT_SCORE,
    NEXT_GOAL,
    CORNERS,
    CARDS,
    HANDICAP,
    DOUBLE_CHANCE,
    OTHER
}

data class MarketOutcome(
    val label: String,
    val odds: Double
)

data class OutcomeCalculation(
    val label: String,
    val odds: Double,
    val probabilityPercent: Double,
    val suggestedStake: Double,
    val returnAmount: Double,
    val netProfit: Double
)

data class MarketAnalysisResult(
    val marketKind: MarketKind,
    val totalStake: Double,
    val impliedProbabilitySum: Double,
    val overroundPercent: Double,
    val isArbitrage: Boolean,
    val guaranteedReturn: Double,
    val guaranteedProfit: Double,
    val profitPercent: Double,
    val outcomes: List<OutcomeCalculation>,
    val message: String
)

object MarketMath {

    fun inspect(odds: List<Double>): MarketQuality? {
        if (odds.isEmpty() || odds.any { !it.isFinite() || it <= 1.0 }) {
            return null
        }

        val sum = odds.sumOf { 1.0 / it }

        if (!sum.isFinite() || sum <= 0.0) return null

        val overround = (sum - 1.0) * 100.0

        val warning = when {
            sum < 1.0 ->
                "Nəzəri arbitraj imkanı var. Əmsalları və bütün nəticələri yoxla."

            sum > 1.0 ->
                "Bukmeker marjası mövcuddur."

            else ->
                "Ümumi ehtimal 100%-dir."
        }

        return MarketQuality(
            impliedProbabilitySum = sum,
            overroundPercent = overround,
            warning = warning
        )
    }

    /**
     * Ümumi bazar hesablaması.
     *
     * Yalnız nəticələr:
     * 1. Tamdırsa;
     * 2. Bir-birini istisna edirsə;
     * 3. Eyni bazara aiddirsə;
     * hesablama arbitraj kimi qiymətləndirilə bilər.
     *
     * Məsələn:
     * 1X2: 1, X, 2
     * Üst/Alt: Üst 2.5, Alt 2.5
     * Hər iki komanda qol vuracaq: Bəli, Xeyr
     */
    fun calculateCompleteMarket(
        marketKind: MarketKind,
        outcomes: List<MarketOutcome>,
        totalStake: Double,
        allOutcomesConfirmed: Boolean,
        mutuallyExclusive: Boolean
    ): MarketAnalysisResult? {

        if (!allOutcomesConfirmed || !mutuallyExclusive) return null
        if (outcomes.size < 2) return null
        if (!totalStake.isFinite() || totalStake <= 0.0) return null

        if (outcomes.any {
                it.label.isBlank() ||
                !it.odds.isFinite() ||
                it.odds <= 1.0
            }) {
            return null
        }

        val uniqueLabels = outcomes.map {
            it.label.trim().lowercase(Locale.ROOT)
        }.toSet()

        if (uniqueLabels.size != outcomes.size) return null

        val probabilitySum = outcomes.sumOf { 1.0 / it.odds }

        if (!probabilitySum.isFinite() || probabilitySum <= 0.0) {
            return null
        }

        val guaranteedReturn = totalStake / probabilitySum
        val guaranteedProfit = guaranteedReturn - totalStake
        val profitPercent = guaranteedProfit / totalStake * 100.0
        val isArbitrage = probabilitySum < 1.0

        val calculations = outcomes.map { outcome ->
            val stake = totalStake *
                (1.0 / outcome.odds) / probabilitySum

            val returned = stake * outcome.odds

            OutcomeCalculation(
                label = outcome.label,
                odds = outcome.odds,
                probabilityPercent = (1.0 / outcome.odds) * 100.0,
                suggestedStake = stake,
                returnAmount = returned,
                netProfit = guaranteedReturn - totalStake
            )
        }

        val message = when {
            isArbitrage ->
                "Riyazi olaraq arbitraj fərqi görünür. Bütün əmsalları, limitləri və hesablaşma qaydalarını təsdiqlə."

            probabilitySum > 1.0 ->
                "Bu bazarda bukmeker marjası var. Riyazi arbitraj aşkarlanmadı."

            else ->
                "Əmsalların cəmi 100%-lik sərhəddədir. Real qazanc üstünlüyü təsdiqlənmir."
        }

        return MarketAnalysisResult(
            marketKind = marketKind,
            totalStake = totalStake,
            impliedProbabilitySum = probabilitySum,
            overroundPercent = (probabilitySum - 1.0) * 100.0,
            isArbitrage = isArbitrage,
            guaranteedReturn = guaranteedReturn,
            guaranteedProfit = guaranteedProfit,
            profitPercent = profitPercent,
            outcomes = calculations,
            message = message
        )
    }

    /**
     * Köhnə ümumi arbitraj funksiyası ilə uyğunluq saxlanılır.
     */
    fun calculateArbitrage(
        odds: List<Double>,
        totalStake: Double
    ): ArbitrageResult? {

        val result = calculateCompleteMarket(
            marketKind = MarketKind.OTHER,
            outcomes = odds.mapIndexed { index, odd ->
                MarketOutcome("Nəticə ${index + 1}", odd)
            },
            totalStake = totalStake,
            allOutcomesConfirmed = true,
            mutuallyExclusive = true
        ) ?: return null

        return ArbitrageResult(
            isArbitrage = result.isArbitrage,
            totalStake = result.totalStake,
            stakes = result.outcomes.map { it.suggestedStake },
            returns = result.outcomes.map { it.returnAmount },
            netProfit = result.guaranteedProfit,
            profitPercent = result.profitPercent,
            message = result.message
        )
    }

    fun calculateHedge(
        originalStake: Double,
        originalOdds: Double,
        hedgeOdds: Double
    ): HedgeResult? {

        if (!originalStake.isFinite() ||
            !originalOdds.isFinite() ||
            !hedgeOdds.isFinite()
        ) return null

        if (originalStake <= 0.0 ||
            originalOdds <= 1.0 ||
            hedgeOdds <= 1.0
        ) return null

        val hedgeStake =
            (originalStake * originalOdds) / hedgeOdds

        val total = originalStake + hedgeStake

        val originalReturn = originalStake * originalOdds
        val hedgeReturn = hedgeStake * hedgeOdds

        return HedgeResult(
            hedgeStake = hedgeStake,
            totalStake = total,
            returnIfOriginalWins = originalReturn,
            returnIfHedgeWins = hedgeReturn,
            netIfOriginalWins = originalReturn - total,
            netIfHedgeWins = hedgeReturn - total
        )
    }

    fun scenarioNet(
        stake: Double,
        odds: Double,
        totalStake: Double
    ): Double {
        if (!stake.isFinite() ||
            !odds.isFinite() ||
            !totalStake.isFinite()
        ) return Double.NaN

        return stake * odds - totalStake
    }

    fun formatMoney(value: Double): String {
        if (!value.isFinite()) return "Hesablana bilmədi"

        return String.format(
            Locale("az", "AZ"),
            "%.2f AZN",
            value
        )
    }

    fun formatPercent(value: Double): String {
        if (!value.isFinite()) return "—"

        return String.format(
            Locale("az", "AZ"),
            "%.2f%%",
            value
        )
    }
}
