package app.spammy.hof.automation.service

import app.spammy.hof.automation.config.AutomationSessionProperties
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Component

data class AutomationTimeSnapshot(
    val current: Int,
    val max: Int,
    val observedAt: Instant,
) {
    init {
        require(current >= 0)
        require(max >= 0)
        require(current <= max)
    }

    fun estimateAt(now: Instant): Int {
        val elapsedMillis = Duration.between(observedAt, now).toMillis().coerceAtLeast(0)
        val recovered = elapsedMillis / TIME_RECOVERY_INTERVAL_MILLIS
        return (current.toLong() + recovered).coerceAtMost(max.toLong()).toInt()
    }

    companion object {
        const val TIME_RECOVERY_INTERVAL_MILLIS = 1_600L
    }
}

sealed interface BattleTimeDecision {
    data class Run(
        val battleCount: Int,
        val estimatedTime: Int?,
        val requiredTime: Int,
        val usedFallback: Boolean = false,
    ) : BattleTimeDecision

    data class Wait(
        val nextRunAt: Instant,
        val estimatedTime: Int?,
        val requiredTime: Int,
        val usedFallback: Boolean = false,
    ) : BattleTimeDecision
}

@Component
class BattleTimePolicy(
    private val sessionProperties: AutomationSessionProperties = AutomationSessionProperties(),
) {
    fun forBattleMap(
        snapshot: AutomationTimeSnapshot?,
        now: Instant,
        targetRemaining: Int,
        supportsThreeBattles: Boolean,
        hasCapacityForThree: Boolean,
    ): BattleTimeDecision = forCombat(
        snapshot = snapshot,
        now = now,
        targetRemaining = targetRemaining,
        supportsThreeBattles = supportsThreeBattles,
        hasCapacityForThree = hasCapacityForThree,
        minimumExecutionTime = BATTLE_MAP_MINIMUM_EXECUTION_TIME,
        unreachableRetryAt = snapshot
            ?.takeIf { it.max < BATTLE_MAP_MINIMUM_EXECUTION_TIME }
            ?.let { now.plus(sessionProperties.reconciliationInterval) },
    )

    fun forQuestCombat(
        snapshot: AutomationTimeSnapshot?,
        now: Instant,
        targetRemaining: Int,
        supportsThreeBattles: Boolean,
        hasCapacityForThree: Boolean,
    ): BattleTimeDecision = forCombat(
        snapshot = snapshot,
        now = now,
        targetRemaining = targetRemaining,
        supportsThreeBattles = supportsThreeBattles,
        hasCapacityForThree = hasCapacityForThree,
        minimumExecutionTime = BATTLE_TIME_PER_ROUND,
        unreachableRetryAt = null,
    )

    private fun forCombat(
        snapshot: AutomationTimeSnapshot?,
        now: Instant,
        targetRemaining: Int,
        supportsThreeBattles: Boolean,
        hasCapacityForThree: Boolean,
        minimumExecutionTime: Int,
        unreachableRetryAt: Instant?,
    ): BattleTimeDecision {
        val estimated = snapshot?.estimateAt(now)
            ?: return missingObservation(now, minimumExecutionTime)
        if (estimated < minimumExecutionTime) {
            return BattleTimeDecision.Wait(
                nextRunAt = unreachableRetryAt
                    ?: recoveryAt(now, minimumExecutionTime - estimated),
                estimatedTime = estimated,
                requiredTime = minimumExecutionTime,
            )
        }
        val count = if (
            estimated >= THREE_BATTLE_TIME &&
            targetRemaining >= THREE_BATTLE_COUNT &&
            supportsThreeBattles &&
            hasCapacityForThree
        ) {
            THREE_BATTLE_COUNT
        } else {
            1
        }
        return BattleTimeDecision.Run(count, estimated, count * BATTLE_TIME_PER_ROUND)
    }

    fun forAdventureMap(
        snapshot: AutomationTimeSnapshot?,
        now: Instant,
        requiredTime: Int?,
    ): BattleTimeDecision {
        val usedFallback = requiredTime == null || requiredTime < 0
        val effectiveRequired = requiredTime?.takeIf { it >= 0 } ?: BATTLE_TIME_PER_ROUND
        if (effectiveRequired == 0) {
            return BattleTimeDecision.Run(1, snapshot?.estimateAt(now), 0, usedFallback)
        }
        val estimated = snapshot?.estimateAt(now)
            ?: return missingObservation(now, effectiveRequired, usedFallback)
        return if (estimated >= effectiveRequired) {
            BattleTimeDecision.Run(1, estimated, effectiveRequired, usedFallback)
        } else {
            wait(now, estimated, effectiveRequired, usedFallback)
        }
    }

    private fun wait(
        now: Instant,
        estimated: Int,
        required: Int,
        fallback: Boolean,
    ) = BattleTimeDecision.Wait(
        nextRunAt = recoveryAt(now, (required - estimated).coerceAtLeast(1)),
        estimatedTime = estimated,
        requiredTime = required,
        usedFallback = fallback,
    )

    private fun missingObservation(
        now: Instant,
        required: Int,
        fallback: Boolean = false,
    ) = BattleTimeDecision.Wait(
        nextRunAt = now.plusSeconds(MISSING_OBSERVATION_RETRY_SECONDS),
        estimatedTime = null,
        requiredTime = required,
        usedFallback = fallback,
    )

    private fun recoveryAt(now: Instant, deficit: Int): Instant =
        now.plusMillis(deficit.toLong() * AutomationTimeSnapshot.TIME_RECOVERY_INTERVAL_MILLIS)

    private companion object {
        const val BATTLE_TIME_PER_ROUND = 100
        const val BATTLE_MAP_MINIMUM_EXECUTION_TIME = 1501
        const val THREE_BATTLE_COUNT = 3
        const val THREE_BATTLE_TIME = 300
        const val MISSING_OBSERVATION_RETRY_SECONDS = 10L
    }
}
