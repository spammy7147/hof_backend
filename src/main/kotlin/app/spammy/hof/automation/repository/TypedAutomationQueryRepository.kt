package app.spammy.hof.automation.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.QHofAccountEntity.hofAccountEntity
import app.spammy.hof.account.entity.QHofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.BattleAutomationDailyProgressEntity
import app.spammy.hof.automation.entity.BattleAutomationProcessedResultEntity
import app.spammy.hof.automation.entity.QAutomationEntryEntity.automationEntryEntity
import app.spammy.hof.automation.entity.QAutomationEntryEntity
import app.spammy.hof.automation.entity.QBattleAutomationDailyProgressEntity.battleAutomationDailyProgressEntity
import app.spammy.hof.automation.entity.QBattleAutomationProcessedResultEntity.battleAutomationProcessedResultEntity
import app.spammy.hof.automation.entity.QQuestAutomationCycleEntity.questAutomationCycleEntity
import app.spammy.hof.automation.entity.QQuestAutomationProcessedResultEntity.questAutomationProcessedResultEntity
import app.spammy.hof.automation.entity.QQuestMapExecutionCounterEntity.questMapExecutionCounterEntity
import app.spammy.hof.automation.entity.QTypedAutomationActionRunEntity.typedAutomationActionRunEntity
import app.spammy.hof.automation.entity.QTypedAutomationRuntimeStateEntity.typedAutomationRuntimeStateEntity
import app.spammy.hof.automation.entity.QQuestAutomationSelectionEntity.questAutomationSelectionEntity
import app.spammy.hof.automation.entity.QQuestAutomationMapEntity.questAutomationMapEntity
import app.spammy.hof.automation.entity.QBattleAutomationMapEntity.battleAutomationMapEntity
import app.spammy.hof.automation.entity.QAdventureAutomationMapEntity.adventureAutomationMapEntity
import app.spammy.hof.automation.entity.QAdventureDailyRefreshEntity.adventureDailyRefreshEntity
import app.spammy.hof.party.entity.QPartyPresetEntity.partyPresetEntity
import app.spammy.hof.automation.entity.QuestAutomationCycleEntity
import app.spammy.hof.automation.entity.QuestAutomationProcessedResultEntity
import app.spammy.hof.automation.entity.QuestMapExecutionCounterEntity
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.automation.entity.TypedAutomationRuntimeStateEntity
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.entity.QuestAutomationSelectionEntity
import app.spammy.hof.automation.entity.QuestAutomationMapEntity
import app.spammy.hof.automation.entity.BattleAutomationMapEntity
import app.spammy.hof.automation.entity.AdventureAutomationMapEntity
import app.spammy.hof.automation.entity.AdventureDailyRefreshEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
import java.time.LocalDate
import java.time.Instant
import org.springframework.stereotype.Repository

@Repository
class TypedAutomationQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    private val actionAccount = QHofAccountEntity("typedActionAccount")
    private val actionEntryAccount = QHofAccountEntity("typedActionEntryAccount")
    private val actionEntry = QAutomationEntryEntity("typedActionEntry")
    /** A persisted typed runtime or any typed entry permanently selects the typed engine for this account. */
    fun hasTypedAutomation(accountId: Long): Boolean =
        queryFactory.selectOne().from(typedAutomationRuntimeStateEntity)
            .where(typedAutomationRuntimeStateEntity.accountId.eq(accountId)).fetchFirst() != null ||
            queryFactory.selectOne().from(automationEntryEntity)
                .where(automationEntryEntity.account.id.eq(accountId)).fetchFirst() != null

    fun findRuntimeState(accountId: Long): TypedAutomationRuntimeStateEntity? =
        queryFactory.selectFrom(typedAutomationRuntimeStateEntity)
            .where(typedAutomationRuntimeStateEntity.accountId.eq(accountId)).fetchOne()

    fun findLatestAdventureRefresh(accountId: Long): AdventureDailyRefreshEntity? =
        queryFactory.selectFrom(adventureDailyRefreshEntity)
            .where(adventureDailyRefreshEntity.account.id.eq(accountId))
            .orderBy(
                adventureDailyRefreshEntity.refreshDate.desc(),
                adventureDailyRefreshEntity.refreshedAt.desc(),
                adventureDailyRefreshEntity.id.desc(),
            )
            .fetchFirst()

    fun findRecoverableRuntimeAccountIds(now: Instant): List<Long> =
        queryFactory.select(typedAutomationRuntimeStateEntity.accountId)
            .from(typedAutomationRuntimeStateEntity)
            .where(
                typedAutomationRuntimeStateEntity.lifecycleStatus.eq(app.spammy.hof.automation.entity.TypedAutomationLifecycle.RUNNING),
                typedAutomationRuntimeStateEntity.nextAttemptAt.isNotNull
                    .and(typedAutomationRuntimeStateEntity.nextAttemptAt.loe(now))
                    .and(typedAutomationRuntimeStateEntity.leaseToken.isNull)
                    .or(
                        typedAutomationRuntimeStateEntity.leaseUntil.isNotNull
                            .and(typedAutomationRuntimeStateEntity.leaseUntil.loe(now)),
                    ),
            )
            .orderBy(typedAutomationRuntimeStateEntity.accountId.asc()).fetch()

    fun lockRuntimeState(accountId: Long): TypedAutomationRuntimeStateEntity? =
        queryFactory.selectFrom(typedAutomationRuntimeStateEntity)
            .where(typedAutomationRuntimeStateEntity.accountId.eq(accountId))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE).fetchOne()

    fun findActiveTypedAction(accountId: Long): TypedAutomationActionRunEntity? =
        queryFactory.selectFrom(typedAutomationActionRunEntity)
            .join(typedAutomationActionRunEntity.account, actionAccount).fetchJoin()
            .leftJoin(typedAutomationActionRunEntity.entry, actionEntry).fetchJoin()
            .leftJoin(actionEntry.account, actionEntryAccount).fetchJoin()
            .where(
                typedAutomationActionRunEntity.account.id.eq(accountId),
                typedAutomationActionRunEntity.status.`in`(TypedAutomationActionStatus.PREPARED, TypedAutomationActionStatus.SUBMITTING),
            )
            .orderBy(typedAutomationActionRunEntity.id.desc()).fetchFirst()

    fun findStoppedTypedAction(accountId: Long, actionId: Long): TypedAutomationActionRunEntity? =
        queryFactory.selectFrom(typedAutomationActionRunEntity)
            .join(typedAutomationActionRunEntity.account, actionAccount).fetchJoin()
            .leftJoin(typedAutomationActionRunEntity.entry, actionEntry).fetchJoin()
            .leftJoin(actionEntry.account, actionEntryAccount).fetchJoin()
            .where(
                typedAutomationActionRunEntity.account.id.eq(accountId),
                typedAutomationActionRunEntity.id.eq(actionId),
                typedAutomationActionRunEntity.status.`in`(
                    TypedAutomationActionStatus.FAILED,
                    TypedAutomationActionStatus.AMBIGUOUS,
                ),
            )
            .fetchOne()

    fun lockTypedAction(actionId: Long): TypedAutomationActionRunEntity? =
        queryFactory.selectFrom(typedAutomationActionRunEntity)
            .join(typedAutomationActionRunEntity.account, actionAccount).fetchJoin()
            .leftJoin(typedAutomationActionRunEntity.entry, actionEntry).fetchJoin()
            .leftJoin(actionEntry.account, actionEntryAccount).fetchJoin()
            .where(typedAutomationActionRunEntity.id.eq(actionId))
            .setLockMode(LockModeType.PESSIMISTIC_WRITE).fetchOne()

    fun findEntry(accountId: Long, entryId: Long): AutomationEntryEntity? =
        queryFactory.selectFrom(automationEntryEntity)
            .where(automationEntryEntity.account.id.eq(accountId), automationEntryEntity.id.eq(entryId)).fetchOne()

    fun findQuestSelections(entryId: Long): List<QuestAutomationSelectionEntity> =
        queryFactory.selectFrom(questAutomationSelectionEntity)
            .where(questAutomationSelectionEntity.entry.id.eq(entryId))
            .orderBy(questAutomationSelectionEntity.sourceOrder.asc(), questAutomationSelectionEntity.id.asc()).fetch()

    fun findQuestSelectionsByEntryIds(entryIds: Collection<Long>): List<QuestAutomationSelectionEntity> {
        if (entryIds.isEmpty()) return emptyList()
        return queryFactory.selectFrom(questAutomationSelectionEntity)
            .where(questAutomationSelectionEntity.entry.id.`in`(entryIds.toSet()))
            .orderBy(
                questAutomationSelectionEntity.entry.id.asc(),
                questAutomationSelectionEntity.sourceOrder.asc(),
                questAutomationSelectionEntity.id.asc(),
            ).fetch()
    }

    fun findQuestMaps(selectionIds: Collection<Long>): List<QuestAutomationMapEntity> {
        if (selectionIds.isEmpty()) return emptyList()
        return queryFactory.selectFrom(questAutomationMapEntity)
            .leftJoin(questAutomationMapEntity.partyPreset, partyPresetEntity).fetchJoin()
            .where(questAutomationMapEntity.questSelection.id.`in`(selectionIds))
            .orderBy(questAutomationMapEntity.executionOrder.asc(), questAutomationMapEntity.id.asc()).fetch()
    }

    fun findBattleSettings(entryId: Long): List<BattleAutomationMapEntity> =
        queryFactory.selectFrom(battleAutomationMapEntity)
            .leftJoin(battleAutomationMapEntity.partyPreset, partyPresetEntity).fetchJoin()
            .where(battleAutomationMapEntity.entry.id.eq(entryId))
            .orderBy(battleAutomationMapEntity.executionOrder.asc(), battleAutomationMapEntity.id.asc()).fetch()

    fun findBattleSettingsByEntryIds(entryIds: Collection<Long>): List<BattleAutomationMapEntity> {
        if (entryIds.isEmpty()) return emptyList()
        return queryFactory.selectFrom(battleAutomationMapEntity)
            .leftJoin(battleAutomationMapEntity.partyPreset, partyPresetEntity).fetchJoin()
            .where(battleAutomationMapEntity.entry.id.`in`(entryIds.toSet()))
            .orderBy(battleAutomationMapEntity.entry.id.asc(), battleAutomationMapEntity.executionOrder.asc(), battleAutomationMapEntity.id.asc()).fetch()
    }

    fun findAdventureSettings(entryId: Long): List<AdventureAutomationMapEntity> =
        queryFactory.selectFrom(adventureAutomationMapEntity)
            .leftJoin(adventureAutomationMapEntity.partyPreset, partyPresetEntity).fetchJoin()
            .where(adventureAutomationMapEntity.entry.id.eq(entryId))
            .orderBy(adventureAutomationMapEntity.executionOrder.asc(), adventureAutomationMapEntity.id.asc()).fetch()
    fun findAdventureSettingsByEntryIds(entryIds: Collection<Long>): List<AdventureAutomationMapEntity> {
        if (entryIds.isEmpty()) return emptyList()
        return queryFactory.selectFrom(adventureAutomationMapEntity)
            .leftJoin(adventureAutomationMapEntity.partyPreset, partyPresetEntity).fetchJoin()
            .where(adventureAutomationMapEntity.entry.id.`in`(entryIds.toSet()))
            .orderBy(adventureAutomationMapEntity.entry.id.asc(), adventureAutomationMapEntity.executionOrder.asc(), adventureAutomationMapEntity.id.asc()).fetch()
    }
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
        categoryId: String = BATTLE_MAP_CATEGORY,
    ): Int = queryFactory.select(battleAutomationDailyProgressEntity.successfulRuns)
        .from(battleAutomationDailyProgressEntity)
        .where(
            battleAutomationDailyProgressEntity.account.id.eq(accountId),
            battleAutomationDailyProgressEntity.progressDate.eq(progressDate),
            battleAutomationDailyProgressEntity.categoryId.eq(categoryId),
            battleAutomationDailyProgressEntity.mapCode.eq(mapCode),
            battleAutomationDailyProgressEntity.source.eq(source),
        )
        .fetchOne() ?: 0

    fun findBattleProgress(
        accountId: Long,
        progressDate: LocalDate,
        categoryId: String,
        mapCode: String,
        source: String,
    ): BattleAutomationDailyProgressEntity? = queryFactory.selectFrom(battleAutomationDailyProgressEntity)
        .where(
            battleAutomationDailyProgressEntity.account.id.eq(accountId),
            battleAutomationDailyProgressEntity.progressDate.eq(progressDate),
            battleAutomationDailyProgressEntity.categoryId.eq(categoryId),
            battleAutomationDailyProgressEntity.mapCode.eq(mapCode),
            battleAutomationDailyProgressEntity.source.eq(source),
        )
        .fetchOne()

    fun findBattleProcessedResult(accountId: Long, resultIdentity: String): BattleAutomationProcessedResultEntity? =
        queryFactory.selectFrom(battleAutomationProcessedResultEntity)
            .where(
                battleAutomationProcessedResultEntity.account.id.eq(accountId),
                battleAutomationProcessedResultEntity.resultIdentity.eq(resultIdentity),
            )
            .fetchOne()

    fun findBattleProcessedExecution(accountId: Long, executionIdentity: String): BattleAutomationProcessedResultEntity? =
        queryFactory.selectFrom(battleAutomationProcessedResultEntity)
            .where(
                battleAutomationProcessedResultEntity.account.id.eq(accountId),
                battleAutomationProcessedResultEntity.executionIdentity.eq(executionIdentity),
            )
            .fetchOne()

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

    fun findQuestCycles(accountId: Long, questCodes: Collection<String>): List<QuestAutomationCycleEntity> {
        if (questCodes.isEmpty()) return emptyList()
        return queryFactory.selectFrom(questAutomationCycleEntity)
            .where(
                questAutomationCycleEntity.account.id.eq(accountId),
                questAutomationCycleEntity.questCode.`in`(questCodes.toSet()),
            ).fetch()
    }

    fun findQuestMapCounters(accountId: Long, questCodes: Collection<String>): List<QuestMapExecutionCounterEntity> {
        if (questCodes.isEmpty()) return emptyList()
        return queryFactory.selectFrom(questMapExecutionCounterEntity)
            .where(
                questMapExecutionCounterEntity.account.id.eq(accountId),
                questMapExecutionCounterEntity.questCode.`in`(questCodes.toSet()),
            ).fetch()
    }

    fun findBattleProgressRows(accountId: Long, progressDate: LocalDate, source: String): List<BattleAutomationDailyProgressEntity> =
        queryFactory.selectFrom(battleAutomationDailyProgressEntity)
            .where(
                battleAutomationDailyProgressEntity.account.id.eq(accountId),
                battleAutomationDailyProgressEntity.progressDate.eq(progressDate),
                battleAutomationDailyProgressEntity.source.eq(source),
            ).fetch()

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
