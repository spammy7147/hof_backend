package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationWorkSessionEntity
import app.spammy.hof.automation.entity.AutomationWorkStatus
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.QuestAutomationCycleEntity
import app.spammy.hof.automation.entity.QuestAutomationProcessedResultEntity
import app.spammy.hof.automation.entity.QuestAutomationResultKind
import app.spammy.hof.automation.entity.QuestMapExecutionCounterEntity
import app.spammy.hof.automation.repository.QuestAutomationCycleCommandRepository
import app.spammy.hof.automation.repository.QuestAutomationProcessedResultCommandRepository
import app.spammy.hof.automation.repository.QuestMapExecutionCounterCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.battle.service.BattleMapAliasResolution
import app.spammy.hof.battle.service.BattleMapIdentityCandidate
import app.spammy.hof.battle.service.resolveBattleMapAlias
import app.spammy.hof.battle.model.hasUsableKey
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class QuestPresetSelection(
    val mode: PresetSelectionMode,
    val presetId: Long? = null,
    val resolvedPresetId: Long? = presetId,
    val resolutionChecked: Boolean = false,
    val resolvedParty: ResolvedAutomationParty? = null,
)

sealed interface QuestAction : PreparedAutomationAction {
    val questKey: String

    data class Claim(
        override val questKey: String,
        val actionNo: String,
        val questName: String? = null,
        val questCycle: String? = null,
    ) : QuestAction

    data class Accept(
        override val questKey: String,
        val actionNo: String,
        val questName: String? = null,
        val questCycle: String? = null,
    ) : QuestAction

    data class Battle(
        override val questKey: String,
        val questCycle: String,
        val missionKey: String,
        val missionType: QuestMissionType,
        val categoryId: String,
        val mapCode: String,
        val mapName: String?,
        val preset: QuestPresetSelection,
        val battleCount: Int = 1,
        val resolvedParty: ResolvedAutomationParty? = preset.resolvedParty,
        val questName: String? = null,
        val missionLabel: String? = null,
        val missionCurrent: Int? = null,
        val missionRequired: Int? = null,
    ) : QuestAction
}

data class QuestAutomationMapSelection(
    val missionKey: String,
    val categoryId: String,
    val mapCode: String,
    val preset: QuestPresetSelection,
    val executionOrder: Int,
    val manuallyOverridden: Boolean,
)

data class QuestAutomationSelection(
    val questKey: String,
    val enabled: Boolean,
    val maps: List<QuestAutomationMapSelection>,
)

data class QuestCounterKey(
    val questKey: String,
    val questCycle: String,
    val missionKey: String,
    val categoryId: String,
    val mapCode: String,
)

data class QuestWorkProgressSnapshot(
    val sessionId: Long,
    val questKey: String,
    val questCycle: String,
    val missionKey: String,
    val current: Int,
    val required: Int,
)

data class QuestRunningWorkSnapshot(
    val sessionId: Long,
    val questKey: String,
    val mapClearProgress: QuestWorkProgressSnapshot?,
    val questCycle: String? = mapClearProgress?.questCycle,
    val missionKey: String? = mapClearProgress?.missionKey,
    val missionType: String? = mapClearProgress?.let { QuestMissionType.MAP_CLEAR.name },
    val observedCurrent: Int? = mapClearProgress?.current,
    val observedRequired: Int? = mapClearProgress?.required,
    val confirmedCount: Int = 0,
    val revision: Long = 0,
)

data class QuestAutomationSnapshot(
    val accountId: Long,
    val quests: List<QuestSnapshot>,
    val selections: List<QuestAutomationSelection>,
    val mapStates: List<AutomationMapState>,
    val currentCycles: Map<String, String>,
    val counters: Map<QuestCounterKey, Int>,
    val mapIdentityCandidates: List<BattleMapIdentityCandidate>,
    val now: Instant,
    val primaryPresetId: Long? = null,
    val primaryParty: ResolvedAutomationParty? = null,
    val timeSnapshot: AutomationTimeSnapshot? = null,
    val workSessionId: Long? = null,
    val workSessionRevision: Long? = null,
    val pageComplete: Boolean = true,
)

internal fun QuestMission.displayLabel(): String {
    val typeLabel = when (type) {
        QuestMissionType.MONSTER_KILL -> "몬스터 처치"
        QuestMissionType.MAP_CLEAR -> "맵 클리어"
        QuestMissionType.ITEM_TURN_IN -> "아이템 반납"
        QuestMissionType.IMMEDIATE -> "즉시 완료"
        QuestMissionType.OTHER -> "기타"
    }
    return target?.trim()?.takeIf(String::isNotBlank)?.let { "$typeLabel · $it" } ?: typeLabel
}

interface QuestAutomationProgressStore {
    fun startNewCycle(accountId: Long, resultId: String, questKey: String): String
    fun findRecordedBattleVictoryCount(accountId: Long, resultId: String, action: QuestAction.Battle): Int?
    fun recordBattleResult(accountId: Long, resultId: String, action: QuestAction.Battle, victoryCount: Int)
    fun findRunningWork(accountId: Long, sessionId: Long): QuestRunningWorkSnapshot?
    fun claimMapClearMission(
        accountId: Long,
        work: QuestRunningWorkSnapshot,
        action: QuestAction.Battle,
    ): QuestProgressReconciliation
    fun reconcileMapClearProgress(
        accountId: Long,
        work: QuestWorkProgressSnapshot,
        observedQuestCycle: String,
        current: Int,
        required: Int,
    ): QuestProgressReconciliation
}

class QuestAutomationResultConflictException(resultId: String) : IllegalStateException(
    "Quest result identity '$resultId' is already bound to a different action.",
)

/** Projects one observed quest result atomically; the account lock makes replay and increments deterministic. */
@Service
class JpaQuestAutomationProgressStore(
    private val queryRepository: TypedAutomationQueryRepository,
    private val cycleRepository: QuestAutomationCycleCommandRepository,
    private val processedResultRepository: QuestAutomationProcessedResultCommandRepository,
    private val counterRepository: QuestMapExecutionCounterCommandRepository,
    private val workQueries: AutomationWorkSessionQueryRepository,
    private val workCommands: AutomationWorkSessionCommandRepository,
) : QuestAutomationProgressStore {
    @Transactional
    override fun findRunningWork(accountId: Long, sessionId: Long): QuestRunningWorkSnapshot? {
        val session = workQueries.lockById(accountId, sessionId) ?: return null
        if (
            session.workType != AutomationWorkType.QUEST ||
            session.status != AutomationWorkStatus.RUNNING
        ) return null
        val mapClearProgress = if (session.missionType == QuestMissionType.MAP_CLEAR.name) {
            val questCycle = session.questCycle
            val missionKey = session.missionKey
            val current = session.observedCurrent
            val required = session.observedRequired
            if (questCycle != null && missionKey != null && current != null && required != null) {
                QuestWorkProgressSnapshot(
                    sessionId = session.id,
                    questKey = session.targetKey,
                    questCycle = questCycle,
                    missionKey = missionKey,
                    current = current,
                    required = required,
                )
            } else {
                null
            }
        } else {
            null
        }
        return QuestRunningWorkSnapshot(
            sessionId = session.id,
            questKey = session.targetKey,
            mapClearProgress = mapClearProgress,
            questCycle = session.questCycle,
            missionKey = session.missionKey,
            missionType = session.missionType,
            observedCurrent = session.observedCurrent,
            observedRequired = session.observedRequired,
            confirmedCount = session.confirmedCount,
            revision = requireNotNull(session.version),
        )
    }

    @Transactional
    override fun claimMapClearMission(
        accountId: Long,
        work: QuestRunningWorkSnapshot,
        action: QuestAction.Battle,
    ): QuestProgressReconciliation {
        require(action.missionType == QuestMissionType.MAP_CLEAR)
        queryRepository.lockAccount(accountId)
        val persistedCycle = queryRepository.findQuestCycle(accountId, action.questKey)
            ?.currentCycle
            ?.toString()
            ?: "0"
        if (persistedCycle != action.questCycle || work.questKey != action.questKey) {
            return QuestProgressReconciliation.Stale
        }
        val session = workQueries.lockById(accountId, work.sessionId)
            ?: return QuestProgressReconciliation.Stale
        if (!(
            session.workType == AutomationWorkType.QUEST &&
                session.status == AutomationWorkStatus.RUNNING &&
                session.targetKey == work.questKey
        )) return QuestProgressReconciliation.Stale
        if (!session.matches(work)) return QuestProgressReconciliation.Stale
        if (session.matches(action)) return QuestProgressReconciliation.Applied
        val now = Instant.now()
        session.questCycle = action.questCycle
        session.missionKey = action.missionKey
        session.missionType = QuestMissionType.MAP_CLEAR.name
        session.observedCurrent = action.missionCurrent
        session.observedRequired = action.missionRequired
        session.confirmedCount = 0
        session.lastVerifiedAt = now
        session.updatedAt = now
        workCommands.save(session)
        return QuestProgressReconciliation.Applied
    }

    @Transactional
    override fun startNewCycle(accountId: Long, resultId: String, questKey: String): String {
        validateResultIdentity(resultId)
        val fingerprint = actionFingerprint(QuestAutomationResultKind.ACCEPT, listOf(questKey))
        val account = queryRepository.lockAccount(accountId)
        queryRepository.findQuestProcessedResult(accountId, resultId)?.let {
            it.requireReplayMatches(resultId, QuestAutomationResultKind.ACCEPT, fingerprint)
            return requireNotNull(it.resultValue) { "Processed accept result $resultId has no cycle value." }
        }
        val cycle = queryRepository.findQuestCycle(accountId, questKey)
        val nextCycle = if (cycle == null) {
            cycleRepository.save(QuestAutomationCycleEntity(account = account, questKey = questKey, currentCycle = 1))
            1L
        } else {
            cycle.currentCycle += 1
            cycle.currentCycle
        }
        val resultValue = nextCycle.toString()
        processedResultRepository.save(
            QuestAutomationProcessedResultEntity(
                account = account,
                resultKind = QuestAutomationResultKind.ACCEPT,
                resultIdentity = resultId,
                actionFingerprint = fingerprint,
                resultValue = resultValue,
                processedAt = Instant.now(),
            ),
        )
        return resultValue
    }

    @Transactional
    override fun findRecordedBattleVictoryCount(
        accountId: Long,
        resultId: String,
        action: QuestAction.Battle,
    ): Int? {
        validateResultIdentity(resultId)
        queryRepository.lockAccount(accountId)
        val recorded = queryRepository.findQuestProcessedResult(accountId, resultId) ?: return null
        val replay = recorded.resolveBattleReplay(resultId, action)
        if (replay.legacy) upgradeLegacyBattleReplay(accountId, recorded, action, replay.victoryCount)
        return replay.victoryCount
    }

    @Transactional
    override fun recordBattleResult(
        accountId: Long,
        resultId: String,
        action: QuestAction.Battle,
        victoryCount: Int,
    ) {
        validateResultIdentity(resultId)
        require(victoryCount in 0..action.battleCount) {
            "Quest battle victory count must be between zero and the requested battle count."
        }
        val fingerprint = battleFingerprint(action, victoryCount)
        val account = queryRepository.lockAccount(accountId)
        queryRepository.findQuestProcessedResult(accountId, resultId)?.let {
            val replay = it.resolveBattleReplay(resultId, action)
            if (replay.victoryCount != victoryCount) throw QuestAutomationResultConflictException(resultId)
            if (replay.legacy) upgradeLegacyBattleReplay(accountId, it, action, victoryCount)
            return
        }
        val now = Instant.now()
        processedResultRepository.save(
            QuestAutomationProcessedResultEntity(
                account = account,
                resultKind = QuestAutomationResultKind.BATTLE_VICTORY,
                resultIdentity = resultId,
                actionFingerprint = fingerprint,
                processedAt = now,
            ),
        )
        if (victoryCount > 0) {
            val counter = queryRepository.findQuestCounter(
                accountId,
                action.questKey,
                action.questCycle,
                action.missionKey,
                action.categoryId,
                action.mapCode,
            )
            if (counter == null) {
                counterRepository.save(
                    QuestMapExecutionCounterEntity(
                        account = account,
                        questKey = action.questKey,
                        questCycle = action.questCycle,
                        missionKey = action.missionKey,
                        categoryId = action.categoryId,
                        mapCode = action.mapCode,
                        successfulRuns = victoryCount,
                    ),
                )
            } else {
                counter.successfulRuns += victoryCount
            }
        }
        projectMapClearWorkProgress(accountId, action, victoryCount, now)
    }

    @Transactional
    override fun reconcileMapClearProgress(
        accountId: Long,
        work: QuestWorkProgressSnapshot,
        observedQuestCycle: String,
        current: Int,
        required: Int,
    ): QuestProgressReconciliation {
        require(current >= 0 && required >= 0 && current <= required)
        queryRepository.lockAccount(accountId)
        val persistedCycle = queryRepository.findQuestCycle(accountId, work.questKey)
            ?.currentCycle
            ?.toString()
            ?: "0"
        if (persistedCycle != observedQuestCycle) return QuestProgressReconciliation.Stale
        val session = workQueries.lockById(accountId, work.sessionId)
            ?: return QuestProgressReconciliation.Stale
        if (!(
            session.workType == AutomationWorkType.QUEST &&
                session.status == AutomationWorkStatus.RUNNING &&
                session.targetKey == work.questKey &&
                session.questCycle == work.questCycle &&
                session.missionKey == work.missionKey &&
                session.missionType == QuestMissionType.MAP_CLEAR.name &&
                session.observedCurrent == work.current &&
                session.observedRequired == work.required
        )) return QuestProgressReconciliation.Stale
        val now = Instant.now()
        if (
            session.questCycle == observedQuestCycle &&
            session.observedCurrent == current &&
            session.observedRequired == required
        ) return QuestProgressReconciliation.Applied
        if (session.questCycle != observedQuestCycle) session.confirmedCount = 0
        session.questCycle = observedQuestCycle
        session.observedCurrent = current
        session.observedRequired = required
        session.lastVerifiedAt = now
        session.updatedAt = now
        workCommands.save(session)
        return QuestProgressReconciliation.Applied
    }

    private fun projectMapClearWorkProgress(
        accountId: Long,
        action: QuestAction.Battle,
        victoryCount: Int,
        now: Instant,
    ) {
        if (action.missionType != QuestMissionType.MAP_CLEAR) return
        val persistedCycle = queryRepository.findQuestCycle(accountId, action.questKey)
            ?.currentCycle
            ?.toString()
            ?: "0"
        if (persistedCycle != action.questCycle) return
        val session = findQuestWorkSession(accountId, action.questKey) ?: return
        val identityChanged = !session.matches(action)
        if (identityChanged) {
            if (!session.canInitializeFrom(action)) return
            session.questCycle = action.questCycle
            session.missionKey = action.missionKey
            session.missionType = QuestMissionType.MAP_CLEAR.name
            session.observedCurrent = action.missionCurrent
            session.observedRequired = action.missionRequired
            session.confirmedCount = 0
        }
        val current = session.observedCurrent ?: action.missionCurrent ?: return
        session.confirmedCount += victoryCount
        session.observedCurrent = session.observedRequired
            ?.let { required -> (current + victoryCount).coerceAtMost(required) }
            ?: current + victoryCount
        session.updatedAt = now
        workCommands.save(session)
    }

    private fun upgradeLegacyBattleReplay(
        accountId: Long,
        recorded: QuestAutomationProcessedResultEntity,
        action: QuestAction.Battle,
        victoryCount: Int,
    ) {
        val now = Instant.now()
        recorded.actionFingerprint = battleFingerprint(action, victoryCount)
        processedResultRepository.save(recorded)
        projectLegacyMapClearWorkProgress(accountId, action, victoryCount, now)
    }

    private fun projectLegacyMapClearWorkProgress(
        accountId: Long,
        action: QuestAction.Battle,
        victoryCount: Int,
        now: Instant,
    ) {
        if (action.missionType != QuestMissionType.MAP_CLEAR || victoryCount == 0) return
        val baseline = action.missionCurrent ?: return
        val session = findQuestWorkSession(accountId, action.questKey)?.takeIf { it.matches(action) } ?: return
        val current = session.observedCurrent ?: return
        val projected = session.observedRequired
            ?.let { required -> (baseline + victoryCount).coerceAtMost(required) }
            ?: baseline + victoryCount
        if (current >= projected) return
        session.confirmedCount += victoryCount
        session.observedCurrent = projected
        session.updatedAt = now
        workCommands.save(session)
    }

    private fun findQuestWorkSession(
        accountId: Long,
        questKey: String,
    ) = workQueries.lockOpen(accountId).singleOrNull {
        it.workType == AutomationWorkType.QUEST &&
            it.status == AutomationWorkStatus.RUNNING &&
            it.targetKey == questKey
    }

    private fun AutomationWorkSessionEntity.matches(
        action: QuestAction.Battle,
    ): Boolean =
        questCycle == action.questCycle &&
            missionKey == action.missionKey &&
            missionType == QuestMissionType.MAP_CLEAR.name

    private fun AutomationWorkSessionEntity.matches(
        work: QuestRunningWorkSnapshot,
    ): Boolean =
        questCycle == work.questCycle &&
            missionKey == work.missionKey &&
            missionType == work.missionType &&
        observedCurrent == work.observedCurrent &&
            observedRequired == work.observedRequired &&
            confirmedCount == work.confirmedCount &&
            version == work.revision

    private fun AutomationWorkSessionEntity.canInitializeFrom(
        action: QuestAction.Battle,
    ): Boolean =
        questCycle in setOf(null, action.questCycle) &&
            missionKey in setOf(null, action.missionKey) &&
            missionType in setOf(null, QuestMissionType.MAP_CLEAR.name)

    private fun validateResultIdentity(resultId: String) {
        require(resultId.isNotBlank()) { "Quest result identity must not be blank." }
        require(resultId.length <= QuestAutomationProcessedResultEntity.MAX_RESULT_IDENTITY_LENGTH) {
            "Quest result identity must be at most ${QuestAutomationProcessedResultEntity.MAX_RESULT_IDENTITY_LENGTH} characters."
        }
    }

    private fun QuestAutomationProcessedResultEntity.requireReplayMatches(
        resultId: String,
        expectedKind: QuestAutomationResultKind,
        expectedFingerprint: String,
    ) {
        if (resultKind != expectedKind || actionFingerprint != expectedFingerprint) {
            throw QuestAutomationResultConflictException(resultId)
        }
    }

    private fun actionFingerprint(kind: QuestAutomationResultKind, actionFields: List<String>): String {
        val canonical = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                val fields = listOf(kind.name) + actionFields
                output.writeInt(fields.size)
                fields.forEach { field ->
                    val encoded = field.toByteArray(StandardCharsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
            }
            bytes.toByteArray()
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical))
    }

    private fun battleFingerprint(action: QuestAction.Battle, victoryCount: Int) = actionFingerprint(
        QuestAutomationResultKind.BATTLE_VICTORY,
        listOf(
            action.questKey,
            action.questCycle,
            action.missionKey,
            action.missionType.name,
            action.categoryId,
            action.mapCode,
            action.battleCount.toString(),
            victoryCount.toString(),
        ),
    )

    private fun legacyBattleFingerprint(action: QuestAction.Battle, victoryCount: Int) = actionFingerprint(
        QuestAutomationResultKind.BATTLE_VICTORY,
        listOf(
            action.questKey,
            action.questCycle,
            action.missionKey,
            action.categoryId,
            action.mapCode,
            action.battleCount.toString(),
            victoryCount.toString(),
        ),
    )

    private fun QuestAutomationProcessedResultEntity.resolveBattleReplay(
        resultId: String,
        action: QuestAction.Battle,
    ): BattleReplay {
        if (resultKind != QuestAutomationResultKind.BATTLE_VICTORY) {
            throw QuestAutomationResultConflictException(resultId)
        }
        (0..action.battleCount).singleOrNull { victoryCount ->
            actionFingerprint == battleFingerprint(action, victoryCount)
        }?.let { return BattleReplay(it, legacy = false) }
        (0..action.battleCount).singleOrNull { victoryCount ->
            actionFingerprint == legacyBattleFingerprint(action, victoryCount)
        }?.let { return BattleReplay(it, legacy = true) }
        throw QuestAutomationResultConflictException(resultId)
    }

    private data class BattleReplay(val victoryCount: Int, val legacy: Boolean)
}

/**
 * 일반 퀘스트의 우선순위 판단과 권위 결과 반영을 한 경계에서 처리한다.
 * 외부 요청은 수행하지 않으며 [decideNext]는 최신 권위 진행도를 먼저 수렴한 뒤 다음 지시를 반환한다.
 */
@Service
class DefaultQuestWorkCycleModule(
    private val progressStore: QuestAutomationProgressStore,
    private val timePolicy: BattleTimePolicy = BattleTimePolicy(),
    private val lootSignals: AutomationLootSignalService = AutomationLootSignalService(),
) : QuestWorkCycleModule {
    override fun decideNext(snapshot: QuestAutomationSnapshot): QuestDirective = decideNext(snapshot) { true }

    override fun decideNext(
        snapshot: QuestAutomationSnapshot,
        accepts: (PreparedAutomationAction) -> Boolean,
    ): QuestDirective {
        val work = snapshot.workSessionId?.let { progressStore.findRunningWork(snapshot.accountId, it) }
        if (
            snapshot.workSessionId != null &&
            (snapshot.workSessionRevision == null || work?.revision != snapshot.workSessionRevision)
        ) {
            return staleProgressRecheck(snapshot)
        }
        if (!snapshot.pageComplete) {
            return QuestDirective.Recheck(
                at = snapshot.now.plusSeconds(INCOMPLETE_PAGE_RECHECK_DELAY_SECONDS),
                reasonCode = "QUEST_PAGE_INCOMPLETE",
                message = "퀘스트 목록의 완전성을 확인하지 못해 이 항목을 잠시 뒤 다시 확인합니다.",
            )
        }
        reconcileAuthoritativeMapClearProgress(snapshot, work?.mapClearProgress)?.let { return it }
        val next = evaluateRules(snapshot, accepts)
        if (next is QuestDirective.Hold && work != null) {
            return QuestDirective.WaitForConfiguration(next.message, next.reasonCode)
        }
        if (
            next is QuestDirective.Execute &&
            next.action is QuestAction.Battle &&
            next.action.missionType == QuestMissionType.MAP_CLEAR &&
            work != null
        ) {
            return when (progressStore.claimMapClearMission(snapshot.accountId, work, next.action)) {
                QuestProgressReconciliation.Applied -> next
                QuestProgressReconciliation.Stale -> staleProgressRecheck(snapshot)
            }
        }
        if (next !is QuestDirective.Skip) return next
        return work?.let { evaluateWorkTransition(snapshot, it.questKey) } ?: QuestDirective.Skip
    }

    private fun evaluateWorkTransition(
        snapshot: QuestAutomationSnapshot,
        questKey: String,
    ): QuestDirective {
        val quest = snapshot.quests.singleOrNull { it.questKey == questKey }
            ?: return QuestDirective.CompleteWork
        if (quest.state == QuestState.UNAVAILABLE || quest.section == QuestSection.WAITING) {
            return QuestDirective.CompleteWork
        }
        val material = quest.missions.firstOrNull {
            it.type == QuestMissionType.ITEM_TURN_IN && !it.completable
        }
        val resourceName = material?.target?.takeIf(String::isNotBlank)
            ?: return QuestDirective.CompleteWork
        val missingCount = material.progress?.let { (it.required - it.current).coerceAtLeast(0) }
        return QuestDirective.WaitForResource(
            resourceName = lootSignals.normalize(resourceName),
            missingCount = missingCount,
        )
    }

    private fun reconcileAuthoritativeMapClearProgress(
        snapshot: QuestAutomationSnapshot,
        work: QuestWorkProgressSnapshot?,
    ): QuestDirective.Recheck? {
        work ?: return null
        val observedQuestCycle = snapshot.currentCycles[work.questKey] ?: INITIAL_CYCLE
        val quest = snapshot.quests.singleOrNull { it.questKey == work.questKey } ?: return null
        val progress = quest.missions.singleOrNull {
            it.key == work.missionKey && it.type == QuestMissionType.MAP_CLEAR
        }?.progress ?: return null
        return when (progressStore.reconcileMapClearProgress(
            snapshot.accountId,
            work,
            observedQuestCycle,
            progress.current,
            progress.required,
        )) {
            QuestProgressReconciliation.Applied -> null
            QuestProgressReconciliation.Stale -> staleProgressRecheck(snapshot)
        }
    }

    private fun staleProgressRecheck(snapshot: QuestAutomationSnapshot) = QuestDirective.Recheck(
        at = snapshot.now.plusSeconds(STALE_RECHECK_DELAY_SECONDS),
        reasonCode = "QUEST_PROGRESS_STALE",
        message = "퀘스트 진행 상태가 판단 중 변경되어 최신 상태를 즉시 다시 확인합니다.",
    )

    private fun evaluateRules(
        context: QuestAutomationSnapshot,
        accepts: (PreparedAutomationAction) -> Boolean,
    ): QuestDirective {
        val selections = context.selections
            .asSequence()
            .filter(QuestAutomationSelection::enabled)
            .associateBy(QuestAutomationSelection::questKey)
        val candidates = context.quests
            .filter { it.questKey in selections }
            .sortedBy(QuestSnapshot::sourceOrder)

        var claimFallback: QuestDirective.Hold? = null
        candidates.filter {
            it.state == QuestState.CLAIMABLE && it.canClaimWithoutWastingTime(context.timeSnapshot, context.now)
        }.forEach { quest ->
            quest.actionNo?.let { actionNo ->
                val action = QuestAction.Claim(
                    quest.questKey, actionNo, quest.name,
                    context.currentCycles[quest.questKey] ?: INITIAL_CYCLE,
                )
                if (accepts(action)) return QuestDirective.Execute(action)
                return@forEach
            }
            if (claimFallback == null) {
                claimFallback = QuestDirective.Hold(
                    "Quest ${quest.questKey} has no claim action.",
                    "QUEST_CLAIM_ACTION_MISSING",
                )
            }
        }

        val progressEvaluation = evaluateProgress(candidates, selections, context, accepts)
        if (progressEvaluation is QuestDirective.Execute) return progressEvaluation

        var acceptFallback: QuestDirective.Hold? = null
        candidates.filter { it.state == QuestState.AVAILABLE }.forEach { quest ->
            quest.actionNo?.let { actionNo ->
                val action = QuestAction.Accept(
                    quest.questKey, actionNo, quest.name,
                    context.currentCycles[quest.questKey] ?: INITIAL_CYCLE,
                )
                if (accepts(action)) return QuestDirective.Execute(action)
                return@forEach
            }
            if (acceptFallback == null) {
                acceptFallback = QuestDirective.Hold(
                    "Quest ${quest.questKey} has no accept action.",
                    "QUEST_ACCEPT_ACTION_MISSING",
                )
            }
        }

        return claimFallback ?: progressEvaluation ?: acceptFallback ?: QuestDirective.Skip
    }

    override fun recordObservedResult(
        accountId: Long,
        attempt: QuestAttempt,
        observation: QuestResultObservation,
    ): QuestRecordResult = when (attempt) {
        is QuestAttempt.Accept -> recordAccept(accountId, attempt, observation)
        is QuestAttempt.Claim -> recordClaim(attempt, observation)
        is QuestAttempt.Battle -> recordBattle(accountId, attempt, observation)
    }

    private fun recordAccept(
        accountId: Long,
        attempt: QuestAttempt.Accept,
        observation: QuestResultObservation,
    ): QuestRecordResult {
        val page = observation as? QuestResultObservation.Page
            ?: return QuestRecordResult.NeedsRecheck("Quest accept requires an authoritative quest page.")
        if (!page.complete) return QuestRecordResult.NeedsRecheck("Quest accept page is incomplete.")
        val quests = page.quests
        val quest = quests.singleOrNull { it.questKey == attempt.questKey }
            ?: return QuestRecordResult.NeedsRecheck(
                "Quest ${attempt.questKey} is absent from the authoritative page.",
            )
        return when {
            quest.state in setOf(QuestState.ACTIVE, QuestState.CLAIMABLE, QuestState.COMPLETED) ->
                QuestRecordResult.Recorded(
                    progressStore.startNewCycle(accountId, attempt.resultIdentity, attempt.questKey),
                )
            quest.state == QuestState.AVAILABLE && quest.actionNo == attempt.actionNo ->
                QuestRecordResult.NotApplied("Quest accept is still available with the same action.")
            else -> QuestRecordResult.NeedsRecheck("Quest accept outcome is not yet authoritative.")
        }
    }

    private fun recordClaim(
        attempt: QuestAttempt.Claim,
        observation: QuestResultObservation,
    ): QuestRecordResult {
        val page = observation as? QuestResultObservation.Page
            ?: return QuestRecordResult.NeedsRecheck("Quest claim requires an authoritative quest page.")
        if (!page.complete) return QuestRecordResult.NeedsRecheck("Quest claim page is incomplete.")
        val quests = page.quests
        val quest = quests.singleOrNull { it.questKey == attempt.questKey }
            ?: return QuestRecordResult.Recorded()
        return when {
            quest.state in setOf(QuestState.COMPLETED, QuestState.UNAVAILABLE) ->
                QuestRecordResult.Recorded()
            quest.state == QuestState.CLAIMABLE && quest.actionNo == attempt.actionNo ->
                QuestRecordResult.NotApplied("Quest reward is still claimable with the same action.")
            else -> QuestRecordResult.NeedsRecheck("Quest claim outcome is not yet authoritative.")
        }
    }

    private fun recordBattle(
        accountId: Long,
        attempt: QuestAttempt.Battle,
        observation: QuestResultObservation,
    ): QuestRecordResult {
        if (observation is QuestResultObservation.Page) {
            if (!observation.complete) {
                return QuestRecordResult.NeedsRecheck("Quest battle page is incomplete.")
            }
            return recordBattleFromQuestPage(accountId, attempt, observation.quests)
        }
        val outcomes = (observation as? QuestResultObservation.BattleRounds)?.outcomes
            ?: return QuestRecordResult.NeedsRecheck("Quest battle requires a result observation.")
        require(outcomes.size == attempt.action.battleCount) {
            "Quest battle result count must match the requested battle count."
        }
        require(outcomes.all {
            it == BattleAutomationRoundOutcome.VICTORY ||
                it == BattleAutomationRoundOutcome.DEFEAT ||
                it == BattleAutomationRoundOutcome.DRAW
        }) {
            "Quest battle results must contain only terminal outcomes."
        }
        progressStore.recordBattleResult(
            accountId,
            attempt.resultIdentity,
            attempt.action,
            outcomes.count { it == BattleAutomationRoundOutcome.VICTORY },
        )
        return QuestRecordResult.Recorded()
    }

    private fun recordBattleFromQuestPage(
        accountId: Long,
        attempt: QuestAttempt.Battle,
        quests: List<QuestSnapshot>,
    ): QuestRecordResult {
        val action = attempt.action
        progressStore.findRecordedBattleVictoryCount(accountId, attempt.resultIdentity, action)?.let {
            return QuestRecordResult.Recorded()
        }
        val baseline = action.missionCurrent
            ?: return QuestRecordResult.NeedsRecheck("Stored quest battle has no pre-submit mission progress.")
        val quest = quests.singleOrNull { it.questKey == action.questKey }
            ?: return QuestRecordResult.NeedsRecheck(
                "Quest ${action.questKey} is absent from the authoritative page.",
            )
        val observed = (
            quest.missions.singleOrNull { it.key == action.missionKey }?.progress?.current
                ?: if (quest.state in setOf(QuestState.CLAIMABLE, QuestState.COMPLETED)) {
                    action.missionRequired
                } else {
                    null
                }
            )
            ?: return QuestRecordResult.NeedsRecheck(
                "Quest mission ${action.missionKey} progress is not authoritative yet.",
            )
        val advancement = observed - baseline
        if (advancement !in 1..action.battleCount) {
            if (
                advancement >= 0 &&
                action.missionType in setOf(QuestMissionType.MONSTER_KILL, QuestMissionType.MAP_CLEAR)
            ) {
                return QuestRecordResult.FreshDecision(
                    "Latest complete quest progress is authoritative; the previous battle result " +
                        "will remain unattributed and a fresh action will be selected.",
                )
            }
            return QuestRecordResult.NeedsRecheck(
                "Quest mission progress does not safely bind the stored battle batch.",
            )
        }
        progressStore.recordBattleResult(
            accountId,
            attempt.resultIdentity,
            action,
            advancement,
        )
        return QuestRecordResult.Recorded()
    }

    private fun evaluateProgress(
        quests: List<QuestSnapshot>,
        selections: Map<String, QuestAutomationSelection>,
        context: QuestAutomationSnapshot,
        accepts: (PreparedAutomationAction) -> Boolean,
    ): QuestDirective? {
        val evaluations = mutableListOf<QuestDirective>()
        quests.filter { it.state == QuestState.ACTIVE }.forEach { quest ->
            quest.missions.filter {
                it.type in setOf(QuestMissionType.MONSTER_KILL, QuestMissionType.MAP_CLEAR) && !it.completable
            }.forEach { mission ->
                val selection = selections.getValue(quest.questKey)
                val result = when (mission.type) {
                    QuestMissionType.MONSTER_KILL -> monsterAction(quest, mission, selection, context)
                    QuestMissionType.MAP_CLEAR -> mapClearAction(quest, mission, selection, context)
                    else -> null
                }
                if (result is QuestDirective.Execute) {
                    if (accepts(result.action)) return result
                } else if (result != null) evaluations += result
            }
        }
        if (evaluations.isEmpty()) return null
        return evaluations.filterIsInstance<QuestDirective.Hold>().firstOrNull()
            ?: evaluations.filterIsInstance<QuestDirective.WaitUntil>().minByOrNull { it.nextRunAt }
            ?: QuestDirective.Skip
    }

    private fun monsterAction(
        quest: QuestSnapshot,
        mission: QuestMission,
        selection: QuestAutomationSelection,
        context: QuestAutomationSnapshot,
    ): QuestDirective {
        val configured = selection.maps.filter { it.missionKey == mission.key }
        if (configured.isEmpty()) {
            return QuestDirective.Hold(
                missingBattleMapWarning(quest, mission),
                "QUEST_BATTLE_MAP_MISSING",
            )
        }
        val invalidPresetExists = configured.any { !it.hasValidPreset() }
        val validConfigured = configured.filter { it.hasValidPreset() }
        val stateByMap = context.mapStates.associateBy { it.categoryId to it.mapCode }
        val ready = validConfigured.filter { map ->
            stateByMap[map.categoryId to map.mapCode]?.isRunnable(context.now) == true
        }
        if (ready.isEmpty()) {
            if (invalidPresetExists) {
                return QuestDirective.Hold(
                    "Quest ${quest.questKey} mission ${mission.key} has an invalid preset selection.",
                    "QUEST_PRESET_INVALID",
                )
            }
            val next = validConfigured.mapNotNull { stateByMap[it.categoryId to it.mapCode] }
                .filter { it.isBlockedOnlyByCooldown(context.now) }
                .mapNotNull(AutomationMapState::cooldownUntil)
                .minOrNull()
            return next?.let {
                QuestDirective.WaitUntil(
                    it,
                    "QUEST_BATTLE_COOLDOWN",
                    "퀘스트 전투 맵의 정확한 다음 실행 가능 시각까지 기다립니다.",
                )
            } ?: QuestDirective.Skip
        }
        val cycle = context.currentCycles[quest.questKey] ?: INITIAL_CYCLE
        val selected = ready.minWithOrNull(
            compareBy<QuestAutomationMapSelection> {
                context.counters[QuestCounterKey(quest.questKey, cycle, mission.key, it.categoryId, it.mapCode)] ?: 0
            }.thenBy(QuestAutomationMapSelection::executionOrder),
        )!!
        val selectedState = stateByMap.getValue(selected.categoryId to selected.mapCode)
        return selected.toBattleEvaluation(
            quest,
            cycle,
            mission,
            selectedState,
            context.timeSnapshot,
            context.now,
        )
    }

    private fun mapClearAction(
        quest: QuestSnapshot,
        mission: QuestMission,
        selection: QuestAutomationSelection,
        context: QuestAutomationSnapshot,
    ): QuestDirective {
        val configured = selection.maps.filter { it.missionKey == mission.key }
        val manual = configured.filter(QuestAutomationMapSelection::manuallyOverridden)
            .sortedBy(QuestAutomationMapSelection::executionOrder)
        val selected = if (manual.isNotEmpty()) {
            val states = context.mapStates.associateBy { it.categoryId to it.mapCode }
            manual.firstOrNull { candidate ->
                candidate.hasValidPreset() &&
                    states[candidate.categoryId to candidate.mapCode]?.isRunnable(context.now) == true
            } ?: run {
                if (manual.any { !it.hasValidPreset() }) {
                    return QuestDirective.Hold(
                        "Quest ${quest.questKey} mission ${mission.key} has an invalid preset selection.",
                        "QUEST_PRESET_INVALID",
                    )
                }
                val next = manual.mapNotNull { states[it.categoryId to it.mapCode] }
                    .filter { it.isBlockedOnlyByCooldown(context.now) }
                    .mapNotNull(AutomationMapState::cooldownUntil)
                    .minOrNull()
                return next?.let {
                    QuestDirective.WaitUntil(
                        it,
                        "QUEST_BATTLE_COOLDOWN",
                        "퀘스트 전투 맵의 정확한 다음 실행 가능 시각까지 기다립니다.",
                    )
                } ?: QuestDirective.Skip
            }
        } else {
            val target = mission.target?.takeIf(String::isNotBlank)
                ?: return QuestDirective.Hold(
                    missingBattleMapWarning(quest, mission),
                    "QUEST_BATTLE_MAP_MISSING",
                )
            val categoryId = configured.firstOrNull()?.categoryId ?: DEFAULT_BATTLE_CATEGORY
            val identityCandidates = context.mapIdentityCandidates.filter { it.categoryId == categoryId }
            when (val resolved = resolveBattleMapAlias(target, identityCandidates)) {
                is BattleMapAliasResolution.Resolved -> configured.firstOrNull {
                    it.categoryId == resolved.categoryId && it.mapCode == resolved.mapCode
                } ?: QuestAutomationMapSelection(
                    mission.key,
                    resolved.categoryId,
                    resolved.mapCode,
                    QuestPresetSelection(
                        PresetSelectionMode.PRIMARY,
                        resolvedPresetId = context.primaryPresetId,
                        resolutionChecked = true,
                        resolvedParty = context.primaryParty,
                    ),
                    0,
                    false,
                )
                BattleMapAliasResolution.Missing -> return QuestDirective.Hold(
                    missingBattleMapWarning(quest, mission),
                    "QUEST_BATTLE_MAP_MISSING",
                )
                BattleMapAliasResolution.Ambiguous -> return QuestDirective.Hold(
                    "${quest.name} · $target 맵 후보가 여러 개라 자동으로 고를 수 없습니다. 이 임무에 사용할 맵을 직접 지정해 주세요.",
                    "QUEST_BATTLE_MAP_AMBIGUOUS",
                )
            }
        }
        val state = context.mapStates.firstOrNull {
            it.categoryId == selected.categoryId && it.mapCode == selected.mapCode
        }
        if (!selected.hasValidPreset()) {
            return QuestDirective.Hold(
                "Quest ${quest.questKey} mission ${mission.key} has an invalid preset selection.",
                "QUEST_PRESET_INVALID",
            )
        }
        if (state?.isRunnable(context.now) != true) {
            return state?.takeIf { it.isBlockedOnlyByCooldown(context.now) }?.cooldownUntil
                ?.let {
                    QuestDirective.WaitUntil(
                        it,
                        "QUEST_BATTLE_COOLDOWN",
                        "퀘스트 전투 맵의 정확한 다음 실행 가능 시각까지 기다립니다.",
                    )
                }
                ?: QuestDirective.Skip
        }
        return selected.toBattleEvaluation(
            quest,
            context.currentCycles[quest.questKey] ?: INITIAL_CYCLE,
            mission,
            state,
            context.timeSnapshot,
            context.now,
        )
    }

    private fun missingBattleMapWarning(quest: QuestSnapshot, mission: QuestMission): String {
        val targetOrKey = mission.target?.takeIf(String::isNotBlank) ?: mission.key
        return "${quest.name} · $targetOrKey 전투 맵 설정이 없습니다."
    }

    private fun QuestAutomationMapSelection.toBattleEvaluation(
        quest: QuestSnapshot,
        cycle: String,
        mission: QuestMission,
        liveMapState: AutomationMapState,
        timeSnapshot: AutomationTimeSnapshot?,
        now: Instant,
    ): QuestDirective {
        if (!hasValidPreset()) {
            return QuestDirective.Hold(
                "Quest ${quest.questKey} mission ${mission.key} has an invalid preset selection.",
                "QUEST_PRESET_INVALID",
            )
        }
        val remaining = mission.progress?.let {
            (it.required - it.current).coerceAtLeast(0)
        } ?: 1
        val decision = if (categoryId == ADVENTURE_MAP_CATEGORY) {
            timePolicy.forAdventureMap(timeSnapshot, now, liveMapState.requiredTime)
        } else {
            timePolicy.forQuestCombat(
                timeSnapshot,
                now,
                remaining,
                liveMapState.supportsThreeBattles,
                liveMapState.hasCapacityForThree(),
            )
        }
        if (decision is BattleTimeDecision.Wait) {
            return QuestDirective.WaitUntil(
                decision.nextRunAt,
                "QUEST_TIME_WAIT",
                "퀘스트 전투에 필요한 Time이 회복될 때까지 기다립니다.",
            )
        }
        val run = decision as BattleTimeDecision.Run
        return QuestDirective.Execute(
            QuestAction.Battle(
                quest.questKey,
                cycle,
                mission.key,
                mission.type,
                categoryId,
                mapCode,
                liveMapState.displayName(),
                preset,
                battleCount = run.battleCount,
                questName = quest.name,
                missionLabel = mission.displayLabel(),
                missionCurrent = mission.progress?.current,
                missionRequired = mission.progress?.required,
            ),
        )
    }

    private fun AutomationMapState.isRunnable(now: Instant): Boolean =
        visible && enabled && keyMode.hasUsableKey(keyCount) &&
            (cooldownUntil == null || !cooldownUntil.isAfter(now)) &&
            (winRemaining == null || winRemaining > 0) &&
            (attemptRemaining == null || attemptRemaining > 0) &&
            (availableCount == null || availableCount > 0)

    private fun AutomationMapState.isBlockedOnlyByCooldown(now: Instant): Boolean =
        visible && enabled && keyMode.hasUsableKey(keyCount) &&
            cooldownUntil?.isAfter(now) == true &&
            (winRemaining == null || winRemaining > 0) &&
            (attemptRemaining == null || attemptRemaining > 0) &&
            (availableCount == null || availableCount > 0)

    private fun AutomationMapState.hasCapacityForThree(): Boolean =
        listOf(availableCount, attemptRemaining, winRemaining).none { it != null && it < 3 } &&
            when (keyMode) {
                BattleMapKeyMode.LIMITED -> keyCount != null && keyCount >= 3
                BattleMapKeyMode.NOT_REQUIRED,
                BattleMapKeyMode.UNLIMITED,
                BattleMapKeyMode.UNKNOWN,
                -> true
            }

    private fun AutomationMapState.displayName(): String? = mapName.trim().takeIf(String::isNotBlank)

    private fun QuestSnapshot.canClaimWithoutWastingTime(
        timeSnapshot: AutomationTimeSnapshot?,
        now: Instant,
    ): Boolean = TimeRewardClaimPolicy.canReceive(rewards, timeSnapshot, now)

    private fun QuestAutomationMapSelection.hasValidPreset(): Boolean =
        when (preset.mode) {
            PresetSelectionMode.EXPLICIT -> preset.presetId != null && (!preset.resolutionChecked || preset.resolvedPresetId == preset.presetId)
            PresetSelectionMode.PRIMARY -> preset.presetId == null && (!preset.resolutionChecked || preset.resolvedPresetId != null)
        }

    private companion object {
        const val INITIAL_CYCLE = "0"
        const val DEFAULT_BATTLE_CATEGORY = "battle_map"
        const val ADVENTURE_MAP_CATEGORY = "adventure_map"
        const val STALE_RECHECK_DELAY_SECONDS = 10L
        const val INCOMPLETE_PAGE_RECHECK_DELAY_SECONDS = 10L
    }
}
