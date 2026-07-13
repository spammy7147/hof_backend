package app.spammy.hof.automation.policy

import java.time.Instant
import org.springframework.stereotype.Component

data class AdventureCandidate(
    val mapCode: String,
    val mapName: String,
    val executionOrder: Int,
    val visible: Boolean,
    val enabled: Boolean,
    val cooldownUntil: Instant?,
    val winRemaining: Int?,
    val attemptRemaining: Int?,
    val availableCount: Int?,
)

@Component
class AdventureMapPolicy {
    fun selectCooldown(
        candidates: List<AdventureCandidate>,
        now: Instant,
    ): AdventureCandidate? = candidates
        .asSequence()
        .filter { it.visible && it.enabled }
        .filter { it.cooldownUntil == null || !it.cooldownUntil.isAfter(now) }
        .sortedWith(compareBy<AdventureCandidate> { it.cooldownUntil ?: Instant.EPOCH }.thenBy { it.executionOrder })
        .firstOrNull()

    fun selectDaily(candidates: List<AdventureCandidate>): AdventureCandidate? = candidates
        .asSequence()
        .filter { it.visible && it.enabled }
        .filter { it.winRemaining == null || it.winRemaining > 0 }
        .filter { it.attemptRemaining == null || it.attemptRemaining > 0 }
        .filter { it.availableCount == null || it.availableCount > 0 }
        .minByOrNull { it.executionOrder }
}
