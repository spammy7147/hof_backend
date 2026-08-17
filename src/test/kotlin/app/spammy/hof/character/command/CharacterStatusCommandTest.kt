package app.spammy.hof.character.command

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CharacterStatusCommandTest {
    private val calculator = CharacterPatternStatCalculator()

    @Test
    fun `pattern requirement uses only real int and real spd`() {
        val result = calculator.requirement(realInt = 10, realSpd = 13)

        assertEquals(12, result.requirement)
        assertEquals(1, result.additionalPatternCount)
    }

    @Test
    fun `target plus four minimizes int and maximizes spd inside exact threshold`() {
        val result = calculator.recommend(
            realInt = 10,
            realSpd = 13,
            availablePoints = 236,
            desiredAdditionalPatterns = 4,
        )

        val recommendation = assertIs<CharacterPatternStatRecommendationResult.Available>(result).recommendation
        assertEquals(0, recommendation.addInt)
        assertEquals(191, recommendation.addSpd)
        assertEquals(45, recommendation.pointsRemaining)
        assertEquals(50, calculator.requirement(10, 13 + 191).requirement)
    }

    @Test
    fun `unaffordable dropdown choices are unavailable`() {
        assertIs<CharacterPatternStatRecommendationResult.Unavailable>(
            calculator.recommend(0, 0, availablePoints = 1, desiredAdditionalPatterns = 9),
        )
    }
}
