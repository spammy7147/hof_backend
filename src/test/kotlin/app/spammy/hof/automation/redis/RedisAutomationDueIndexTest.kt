package app.spammy.hof.automation.redis

import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.service.AutomationDueTarget
import app.spammy.hof.automation.service.DatabaseAutomationDueStore
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.RedisConnectionFailureException
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ZSetOperations

class RedisAutomationDueIndexTest {
    @ParameterizedTest
    @ValueSource(strings = ["SCHEDULE", "CANCEL"])
    fun `DB 예약과 취소를 마쳤으면 mirror 쓰기 실패가 완료를 뒤집지 않는다`(operation: String) {
        val store = Mockito.mock(DatabaseAutomationDueStore::class.java)
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        Mockito.`when`(redis.opsForZSet()).thenThrow(RedisConnectionFailureException("fixture write outage"))
        val index = RedisAutomationDueIndex(store, redis)
        val target = AutomationDueTarget(7, 21, AutomationWorkType.QUEST, "quest-1")
        val dueAt = Instant.parse("2026-09-09T00:00:00Z")

        if (operation == "SCHEDULE") {
            index.schedule(target, dueAt)
            Mockito.verify(store).schedule(target, dueAt)
        } else {
            index.cancel(target)
            Mockito.verify(store).cancel(target)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["EMPTY", "CLEANUP_FAILURE"])
    fun `빈 mirror와 stale 정리 실패 모두 DB의 due 대상을 보존한다`(scenario: String) {
        val store = Mockito.mock(DatabaseAutomationDueStore::class.java)
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        val zset = Mockito.mock(ZSetOperations::class.java) as ZSetOperations<String, String>
        Mockito.`when`(redis.opsForZSet()).thenReturn(zset)
        val now = Instant.parse("2026-09-09T00:00:00Z")
        val target = AutomationDueTarget(7, 21, AutomationWorkType.QUEST, "quest-1")
        Mockito.`when`(store.dueAtOrBefore(now, 100)).thenReturn(listOf(target))
        Mockito.`when`(zset.rangeByScore("hof:automation:due", Double.NEGATIVE_INFINITY,
            now.toEpochMilli().toDouble(), 0, 100)).thenReturn(if (scenario == "EMPTY") emptySet() else setOf("stale"))
        Mockito.`when`(zset.remove("hof:automation:due", "stale"))
            .thenThrow(RedisConnectionFailureException("fixture cleanup outage"))

        assertEquals(listOf(target), RedisAutomationDueIndex(store, redis).dueAtOrBefore(now))
    }

    @Test
    fun `권위 DB 조회 실패는 빈 due 결과로 감추지 않는다`() {
        val store = Mockito.mock(DatabaseAutomationDueStore::class.java)
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        val now = Instant.parse("2026-09-09T00:00:00Z")
        val failure = DataAccessResourceFailureException("fixture DB outage")
        Mockito.`when`(store.dueAtOrBefore(now, 100)).thenThrow(failure)

        assertSame(failure, assertFailsWith<DataAccessResourceFailureException> {
            RedisAutomationDueIndex(store, redis).dueAtOrBefore(now)
        })
        Mockito.verifyNoInteractions(redis)
    }

    @Test
    fun `Redis를 읽지 못해도 권위 DB의 due 대상을 반환한다`() {
        val store = Mockito.mock(DatabaseAutomationDueStore::class.java)
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        val now = Instant.parse("2026-09-09T00:00:00Z")
        val target = AutomationDueTarget(7, 21, AutomationWorkType.QUEST, "quest-1")
        Mockito.`when`(store.dueAtOrBefore(now, 100)).thenReturn(listOf(target))
        Mockito.`when`(redis.opsForZSet()).thenThrow(RedisConnectionFailureException("fixture outage"))

        assertEquals(listOf(target), RedisAutomationDueIndex(store, redis).dueAtOrBefore(now))
    }

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
