package app.spammy.hof.automation.outbox

import app.spammy.hof.automation.outbox.QAutomationConsumedEventEntity.automationConsumedEventEntity
import app.spammy.hof.automation.outbox.QAutomationOutboxEntity.automationOutboxEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class AutomationOutboxQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun findUnpublished(
        now: Instant,
        limit: Long = 100,
        topics: Collection<String>? = null,
        excludedTopics: Collection<String> = emptySet(),
    ): List<AutomationOutboxEntity> =
        queryFactory.selectFrom(automationOutboxEntity)
            .where(
                automationOutboxEntity.publishedAt.isNull,
                automationOutboxEntity.availableAt.loe(now),
                topics?.let { automationOutboxEntity.topic.`in`(it) },
                excludedTopics.takeIf { it.isNotEmpty() }?.let { automationOutboxEntity.topic.notIn(it) },
            )
            .orderBy(automationOutboxEntity.id.asc())
            .limit(limit)
            .fetch()

    fun findById(id: Long): AutomationOutboxEntity? = queryFactory.selectFrom(automationOutboxEntity)
        .where(automationOutboxEntity.id.eq(id))
        .fetchOne()

    fun deleteUnpublishedWakesForAccount(accountId: Long): Long = queryFactory.delete(automationOutboxEntity)
        .where(
            automationOutboxEntity.account.id.eq(accountId),
            automationOutboxEntity.topic.eq(AutomationOutboxService.WAKEUP_TOPIC),
            automationOutboxEntity.publishedAt.isNull,
        )
        .execute()

    fun consumed(eventId: String): Boolean = queryFactory.selectOne()
        .from(automationConsumedEventEntity)
        .where(automationConsumedEventEntity.eventId.eq(eventId))
        .fetchFirst() != null
}
