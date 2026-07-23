package app.spammy.hof.automation.redis

import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.service.AutomationDueTarget
import app.spammy.hof.automation.service.DatabaseAutomationDueStore
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations

class RedisAutomationDueIndexTest {
    @Test
    fun `schedule writes DB first and mirrors epoch millis to sorted set`() {
        val store = Mockito.mock(DatabaseAutomationDueStore::class.java)
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        val zset = Mockito.mock(ZSetOperations::class.java) as ZSetOperations<String, String>
        Mockito.`when`(redis.opsForZSet()).thenReturn(zset)
        val index = RedisAutomationDueIndex(store, redis)
        val target = AutomationDueTarget(7, 21, AutomationWorkType.QUEST, "quest-1")
        val dueAt = Instant.parse("2026-07-23T00:30:00Z")

        index.schedule(target, dueAt)

        val order = Mockito.inOrder(store, zset)
        order.verify(store).schedule(target, dueAt)
        order.verify(zset).add("hof:automation:due", "7:21:QUEST:quest-1", dueAt.toEpochMilli().toDouble())
    }
}
