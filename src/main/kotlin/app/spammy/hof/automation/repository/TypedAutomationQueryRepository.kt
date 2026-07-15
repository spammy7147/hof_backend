package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.QAutomationEntryEntity.automationEntryEntity
import app.spammy.hof.automation.entity.QBattleAutomationDailyProgressEntity.battleAutomationDailyProgressEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import java.time.LocalDate
import org.springframework.stereotype.Repository

@Repository
class TypedAutomationQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun findEntries(accountId: Long): List<AutomationEntryEntity> =
        queryFactory.selectFrom(automationEntryEntity)
            .where(automationEntryEntity.account.id.eq(accountId))
            .orderBy(automationEntryEntity.priority.asc(), automationEntryEntity.id.asc())
            .fetch()

    fun findBattleWins(
        accountId: Long,
        progressDate: LocalDate,
        source: String,
        mapCode: String,
    ): Int = queryFactory.select(battleAutomationDailyProgressEntity.successfulRuns)
        .from(battleAutomationDailyProgressEntity)
        .where(
            battleAutomationDailyProgressEntity.account.id.eq(accountId),
            battleAutomationDailyProgressEntity.progressDate.eq(progressDate),
            battleAutomationDailyProgressEntity.categoryId.eq(BATTLE_MAP_CATEGORY),
            battleAutomationDailyProgressEntity.mapCode.eq(mapCode),
            battleAutomationDailyProgressEntity.source.eq(source),
        )
        .fetchOne() ?: 0

    companion object {
        const val BATTLE_MAP_CATEGORY = "battle_map"
        const val BATTLE_MAP_AUTOMATION_SOURCE = "battle_map"
    }
}
