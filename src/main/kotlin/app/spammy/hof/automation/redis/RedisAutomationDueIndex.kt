package app.spammy.hof.automation.redis

import app.spammy.hof.automation.service.AutomationDueIndex
import app.spammy.hof.automation.service.AutomationDueTarget
import app.spammy.hof.automation.service.DatabaseAutomationDueStore
import java.time.Instant
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "hof.automation-session",
    name = ["redis-enabled"],
    havingValue = "true",
)
class RedisAutomationDueIndex(
    private val store: DatabaseAutomationDueStore,
    private val redis: StringRedisTemplate,
) : AutomationDueIndex {
    override fun schedule(target: AutomationDueTarget, dueAt: Instant) {
        store.schedule(target, dueAt)
        redis.opsForZSet().add(KEY, target.member(), dueAt.toEpochMilli().toDouble())
    }

    override fun cancel(target: AutomationDueTarget) {
        store.cancel(target)
        redis.opsForZSet().remove(KEY, target.member())
    }

    override fun dueAtOrBefore(now: Instant, limit: Int): List<AutomationDueTarget> {
        val mirroredMembers = redis.opsForZSet()
            .rangeByScore(KEY, Double.NEGATIVE_INFINITY, now.toEpochMilli().toDouble(), 0, limit.toLong())
            .orEmpty()
        val authoritative = store.dueAtOrBefore(now, limit)
        if (mirroredMembers.isNotEmpty()) {
            val valid = authoritative.mapTo(mutableSetOf()) { it.member() }
            val stale = mirroredMembers.filterNot { it in valid }
            if (stale.isNotEmpty()) redis.opsForZSet().remove(KEY, *stale.toTypedArray())
        }
        // The mirror may be cold or partially written after a Redis outage. DB remains authoritative.
        return authoritative
    }

    private fun AutomationDueTarget.member(): String = "$accountId:$sessionId:$workType:$targetKey"

    private companion object {
        const val KEY = "hof:automation:due"
    }
}
