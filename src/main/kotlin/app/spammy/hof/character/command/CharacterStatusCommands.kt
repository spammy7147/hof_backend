package app.spammy.hof.character.command

data class CharacterPatternRequirement(
    val requirement: Int,
    val additionalPatternCount: Int,
)

data class CharacterPatternStatRecommendation(
    val additionalPatternCount: Int,
    val targetRequirement: Int,
    val addInt: Int,
    val addSpd: Int,
    val pointsUsed: Int,
    val pointsRemaining: Int,
)

sealed interface CharacterPatternStatRecommendationResult {
    data class Available(val recommendation: CharacterPatternStatRecommendation) : CharacterPatternStatRecommendationResult
    data class Unavailable(val reason: String) : CharacterPatternStatRecommendationResult
}

/** Real INT와 Real SPD만으로 패턴 요구 수치와 SPD 우선 배분을 계산한다. */
class CharacterPatternStatCalculator {
    fun requirement(realInt: Int, realSpd: Int): CharacterPatternRequirement {
        require(realInt >= 0 && realSpd >= 0)
        val value = realInt + realSpd / 5
        return CharacterPatternRequirement(value, THRESHOLDS.count { value >= it })
    }

    fun recommend(
        realInt: Int,
        realSpd: Int,
        availablePoints: Int,
        desiredAdditionalPatterns: Int,
    ): CharacterPatternStatRecommendationResult {
        if (desiredAdditionalPatterns !in 1..THRESHOLDS.size) {
            return CharacterPatternStatRecommendationResult.Unavailable("선택할 수 없는 추가 패턴 수입니다.")
        }
        val target = THRESHOLDS[desiredAdditionalPatterns - 1]
        if (requirement(realInt, realSpd).requirement > target) {
            return CharacterPatternStatRecommendationResult.Unavailable("현재 Real Stat이 선택한 목표 요구 수치를 이미 넘었습니다.")
        }
        (0..availablePoints).forEach { addInt ->
            val targetSpdMin = maxOf(0, 5 * (target - realInt - addInt) - realSpd)
            val targetSpdMax = maxOf(-1, 5 * (target - realInt - addInt + 1) - 1 - realSpd)
            val addSpd = minOf(availablePoints - addInt, targetSpdMax)
            if (addSpd >= targetSpdMin && requirement(realInt + addInt, realSpd + addSpd).requirement == target) {
                val used = addInt + addSpd
                return CharacterPatternStatRecommendationResult.Available(
                    CharacterPatternStatRecommendation(
                        desiredAdditionalPatterns,
                        target,
                        addInt,
                        addSpd,
                        used,
                        availablePoints - used,
                    ),
                )
            }
        }
        return CharacterPatternStatRecommendationResult.Unavailable("현재 포인트로 선택한 추가 패턴 수를 달성할 수 없습니다.")
    }

    companion object {
        val THRESHOLDS = listOf(10, 15, 30, 50, 80, 120, 160, 200, 250)
    }
}
