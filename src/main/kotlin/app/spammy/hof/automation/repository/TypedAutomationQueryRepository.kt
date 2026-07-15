package app.spammy.hof.automation.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.QHofAccountEntity.hofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.QAutomationEntryEntity.automationEntryEntity
import app.spammy.hof.automation.entity.QBattleAutomationDailyProgressEntity.battleAutomationDailyProgressEntity
import app.spammy.hof.automation.entity.QQuestAutomationCycleEntity.questAutomationCycleEntity
import app.spammy.hof.automation.entity.QQuestAutomationProcessedResultEntity.questAutomationProcessedResultEntity
import app.spammy.hof.automation.entity.QQuestMapExecutionCounterEntity.questMapExecutionCounterEntity
import app.spammy.hof.automation.entity.QuestAutomationCycleEntity
import app.spammy.hof.automation.entity.QuestAutomationProcessedResultEntity
import app.spammy.hof.automation.entity.QuestMapExecutionCounterEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
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

    /** Account-row fencing serializes cycle/counter writes across JVMs on H2 and PostgreSQL. */
    fun lockAccount(accountId: Long): HofAccountEntity =
        queryFactory.selectFrom(hofAccountEntity)
            .where(hofAccountEntity.id.eq(accountId))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetchOne() ?: error("Account $accountId does not exist.")

    fun findQuestCycle(accountId: Long, questCode: String): QuestAutomationCycleEntity? =
        queryFactory.selectFrom(questAutomationCycleEntity)
            .where(
                questAutomationCycleEntity.account.id.eq(accountId),
                questAutomationCycleEntity.questCode.eq(questCode),
            )
            .fetchOne()

    fun findQuestProcessedResult(
        accountId: Long,
        resultIdentity: String,
    ): QuestAutomationProcessedResultEntity? =
        queryFactory.selectFrom(questAutomationProcessedResultEntity)
            .where(
                questAutomationProcessedResultEntity.account.id.eq(accountId),
                questAutomationProcessedResultEntity.resultIdentity.eq(resultIdentity),
            )
            .fetchOne()

    fun findQuestCounter(
        accountId: Long,
        questCode: String,
        questCycle: String,
        missionKey: String,
        categoryId: String,
        mapCode: String,
    ): QuestMapExecutionCounterEntity? =
        queryFactory.selectFrom(questMapExecutionCounterEntity)
            .where(
                questMapExecutionCounterEntity.account.id.eq(accountId),
                questMapExecutionCounterEntity.questCode.eq(questCode),
                questMapExecutionCounterEntity.questCycle.eq(questCycle),
                questMapExecutionCounterEntity.missionKey.eq(missionKey),
                questMapExecutionCounterEntity.categoryId.eq(categoryId),
                questMapExecutionCounterEntity.mapCode.eq(mapCode),
            )
            .fetchOne()

    fun findQuestMapWins(
        accountId: Long,
        questCode: String,
        questCycle: String,
        missionKey: String,
        categoryId: String,
        mapCode: String,
    ): Int = findQuestCounter(accountId, questCode, questCycle, missionKey, categoryId, mapCode)?.successfulRuns ?: 0

    companion object {
        const val BATTLE_MAP_CATEGORY = "battle_map"
        const val BATTLE_MAP_AUTOMATION_SOURCE = "battle_map"
    }
}
