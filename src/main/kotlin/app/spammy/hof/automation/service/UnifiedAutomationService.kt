package app.spammy.hof.automation.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.dto.*
import app.spammy.hof.automation.entity.AdventureAutomationMapEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.automationEntryDisplayNames
import app.spammy.hof.automation.entity.BattleAutomationMapEntity
import app.spammy.hof.automation.entity.FishingAutomationMapEntity
import app.spammy.hof.automation.entity.HomeQuestAutomationSelectionEntity
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.QuestAutomationMapEntity
import app.spammy.hof.automation.entity.QuestAutomationSelectionEntity
import app.spammy.hof.automation.entity.RaidAutomationTargetEntity
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.entity.UnionAutomationMapEntity
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.raid.RaidAttempt
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidRecordResult
import app.spammy.hof.automation.raid.RaidResultObservation
import app.spammy.hof.automation.raid.toBattleRecoveryOrNull
import app.spammy.hof.automation.raid.warningMessage
import app.spammy.hof.automation.repository.AdventureAutomationMapCommandRepository
import app.spammy.hof.automation.repository.AutomationEntryCommandRepository
import app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository
import app.spammy.hof.automation.repository.BattleAutomationMapCommandRepository
import app.spammy.hof.automation.repository.FishingAutomationSettingCommandRepository
import app.spammy.hof.automation.repository.FishingAutomationMapCommandRepository
import app.spammy.hof.automation.repository.HomeQuestAutomationSelectionCommandRepository
import app.spammy.hof.automation.repository.QuestAutomationMapCommandRepository
import app.spammy.hof.automation.repository.QuestAutomationSelectionCommandRepository
import app.spammy.hof.automation.repository.RaidAutomationTargetCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.automation.repository.UnionAutomationMapCommandRepository
import app.spammy.hof.quest.model.QuestIdentityFactory
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.status.service.HofStatusSnapshotService
import java.time.ZoneId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 타입별 자동화 설정과 실행 생명주기의 권위 있는 애플리케이션 서비스다. */
@Service
class UnifiedAutomationService(
    private val accountQueryRepository: AccountQueryRepository,
    private val battleMapQueryRepository: BattleMapQueryRepository,
    private val partyPresetQueryRepository: PartyPresetQueryRepository,
    private val timeProvider: TimeProvider,
    private val typedAutomationQueryRepository: TypedAutomationQueryRepository,
    private val typedLifecycleBridge: TypedAutomationLifecycleBridge,
    private val typedEntryRepository: AutomationEntryCommandRepository,
    private val typedQuestSelectionRepository: QuestAutomationSelectionCommandRepository,
    private val typedQuestMapRepository: QuestAutomationMapCommandRepository,
    private val typedHomeQuestSelectionRepository: HomeQuestAutomationSelectionCommandRepository,
    private val typedBattleMapRepository: BattleAutomationMapCommandRepository,
    private val typedAdventureMapRepository: AdventureAutomationMapCommandRepository,
    private val typedFishingSettingRepository: FishingAutomationSettingCommandRepository,
    private val typedFishingMapRepository: FishingAutomationMapCommandRepository,
    private val typedUnionMapRepository: UnionAutomationMapCommandRepository,
    private val typedRaidTargetRepository: RaidAutomationTargetCommandRepository,
    private val automationOutboxService: AutomationOutboxService,
    private val hofStatusSnapshots: HofStatusSnapshotService,
    private val workLifecycle: AutomationWorkLifecycle,
    private val workSessionQueries: AutomationWorkSessionQueryRepository,
    private val raidCycleModule: RaidCycleModule,
    private val decisionJournal: AutomationDecisionJournal,
    private val actionLifecycleModule: AutomationActionLifecycleModule,
) {
    @Transactional(readOnly = true)
    fun getTyped(accountId: Long): TypedAutomationAggregateResponse {
        accountQueryRepository.findById(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        return buildTypedAggregate(accountId)
    }

    @Transactional
    fun createEntry(accountId: Long, request: CreateAutomationEntryRequest): TypedAutomationAggregateResponse {
        val account = lockTypedAccount(accountId)
        val current = typedAutomationQueryRepository.findEntries(accountId)
        if (current.size >= MAX_AUTOMATION_ENTRIES) invalid("자동화 항목은 계정당 최대 100개까지 만들 수 있습니다.")
        if (!request.type.supportsMultipleEntries() && current.any { it.type == request.type }) {
            invalid("해당 자동화 유형은 이미 존재합니다.")
        }
        val now = timeProvider.now()
        try {
            typedEntryRepository.save(
                AutomationEntryEntity(
                    account = account,
                    type = request.type,
                    priority = current.size,
                    enabled = false,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            typedEntryRepository.flush()
        } catch (error: org.springframework.dao.DataIntegrityViolationException) {
            invalid("해당 자동화 유형은 이미 존재합니다.", error)
        }
        enqueueSettingsWake(accountId)
        return buildTypedAggregate(accountId)
    }

    @Transactional
    fun deleteEntry(
        accountId: Long,
        entryId: Long,
        settingsRevision: String? = null,
    ): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        val target = (
            typedAutomationQueryRepository.findEntryForUpdate(accountId, entryId)
                ?: typedAutomationQueryRepository.findEntry(accountId, entryId)
            )
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "자동화 항목을 찾지 못했습니다.")
        if (target.type.supportsMultipleEntries()) {
            requireSettingsRevision(
                target,
                settingsRevision ?: throw ApiException(
                    ErrorCode.AUTOMATION_SETTINGS_CONFLICT,
                    "맵 묶음 설정이 변경되었습니다. 최신 설정을 다시 불러와 주세요.",
                ),
            )
        }
        cancelUnsubmittedPreparedAction(accountId, target.id)
        val openRaidCycle = typedAutomationQueryRepository.findOpenRaidCycle(accountId)
        if (target.type == AutomationType.RAID && openRaidCycle?.entry?.id == target.id) {
            recordManualRaidHandoff(accountId, target.id, openRaidCycle.raidId)
        }
        workLifecycle.stopForConfigurationChange(accountId, target.id, emptySet(), wholeEntry = true)
        typedEntryRepository.delete(target)
        typedEntryRepository.flush()
        val now = timeProvider.now()
        typedAutomationQueryRepository.findEntries(accountId).forEachIndexed { index, entry ->
            entry.priority = index
            entry.updatedAt = now
        }
        enqueueSettingsWake(accountId)
        return buildTypedAggregate(accountId)
    }

    private fun cancelUnsubmittedPreparedAction(accountId: Long, entryId: Long) {
        val active = typedAutomationQueryRepository.findActiveTypedAction(accountId)
            ?.takeIf { it.entry?.id == entryId }
            ?: return
        val locked = typedAutomationQueryRepository.lockTypedAction(active.id)
            ?.takeIf { it.account.id == accountId && it.entry?.id == entryId }
            ?: return
        if (locked.status != TypedAutomationActionStatus.PREPARED) return
        val now = timeProvider.now()
        locked.status = TypedAutomationActionStatus.FAILED
        locked.nextAttemptAt = null
        locked.lastError = "맵 묶음 삭제 전에 아직 제출되지 않은 행동을 취소했습니다."
        locked.finishedAt = now
        locked.updatedAt = now
    }

    @Transactional
    fun moveMapBetweenGroups(
        accountId: Long,
        targetEntryId: Long,
        request: MoveMapBetweenGroupsRequest,
    ): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        if (request.sourceEntryId == targetEntryId) invalid("같은 맵 묶음으로 이동할 수 없습니다.")
        val source = typedAutomationQueryRepository.findEntry(accountId, request.sourceEntryId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "원본 맵 묶음을 찾지 못했습니다.")
        val target = typedAutomationQueryRepository.findEntry(accountId, targetEntryId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "대상 맵 묶음을 찾지 못했습니다.")
        if (source.type != target.type || !source.type.supportsMultipleEntries()) {
            invalid("같은 유형의 맵 묶음 사이에서만 이동할 수 있습니다.")
        }
        requireSettingsRevision(source, request.sourceSettingsRevision)
        requireSettingsRevision(target, request.targetSettingsRevision)
        val categoryId = bounded(request.categoryId, MAX_CATEGORY_ID_LENGTH, "카테고리")
        val mapCode = bounded(request.mapCode, MAX_MAP_CODE_LENGTH, "맵 코드")
        if (request.targetExecutionOrder < 0) invalid("대상 맵 실행 순서는 0 이상이어야 합니다.")

        when (source.type) {
            AutomationType.BATTLE_MAP -> moveBattleMap(
                accountId,
                source,
                target,
                categoryId,
                mapCode,
                request.targetExecutionOrder,
            )
            AutomationType.ADVENTURE_MAP -> moveAdventureMap(
                accountId,
                source,
                target,
                categoryId,
                mapCode,
                request.targetExecutionOrder,
            )
            else -> invalid("맵 묶음만 맵을 이동할 수 있습니다.")
        }
        enqueueSettingsWake(accountId)
        return buildTypedAggregate(accountId)
    }

    private fun moveBattleMap(
        accountId: Long,
        source: AutomationEntryEntity,
        target: AutomationEntryEntity,
        categoryId: String,
        mapCode: String,
        targetOrder: Int,
    ) {
        val sourceRows = typedAutomationQueryRepository.findBattleSettings(source.id).toMutableList()
        val moved = sourceRows.singleOrNull { it.categoryId == categoryId && it.mapCode == mapCode }
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "이동할 전투 맵을 원본 묶음에서 찾지 못했습니다.")
        val targetRows = typedAutomationQueryRepository.findBattleSettings(target.id).toMutableList()
        if (targetRows.any { it.categoryId == categoryId && it.mapCode == mapCode }) {
            invalid("대상 묶음에 같은 전투 맵이 이미 있습니다.")
        }
        sourceRows.remove(moved)
        targetRows.add(targetOrder.coerceAtMost(targetRows.size), moved)
        workLifecycle.stopForConfigurationChange(
            accountId,
            source.id,
            setOf("$categoryId/$mapCode"),
            wholeEntry = false,
        )
        moved.entry = target
        moved.account = target.account
        sourceRows.forEachIndexed { index, row -> row.executionOrder = index }
        targetRows.forEachIndexed { index, row -> row.executionOrder = index }
        typedBattleMapRepository.saveAll(sourceRows + targetRows)
        source.enabled = source.enabled && sourceRows.isNotEmpty()
        updateTypedEntry(source, source.enabled)
        updateTypedEntry(target, target.enabled)
    }

    private fun moveAdventureMap(
        accountId: Long,
        source: AutomationEntryEntity,
        target: AutomationEntryEntity,
        categoryId: String,
        mapCode: String,
        targetOrder: Int,
    ) {
        val sourceRows = typedAutomationQueryRepository.findAdventureSettings(source.id).toMutableList()
        val moved = sourceRows.singleOrNull { it.categoryId == categoryId && it.mapCode == mapCode }
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "이동할 모험맵을 원본 묶음에서 찾지 못했습니다.")
        val targetRows = typedAutomationQueryRepository.findAdventureSettings(target.id).toMutableList()
        if (targetRows.any { it.categoryId == categoryId && it.mapCode == mapCode }) {
            invalid("대상 묶음에 같은 모험맵이 이미 있습니다.")
        }
        sourceRows.remove(moved)
        targetRows.add(targetOrder.coerceAtMost(targetRows.size), moved)
        workLifecycle.stopForConfigurationChange(
            accountId,
            source.id,
            setOf("$categoryId/$mapCode"),
            wholeEntry = false,
        )
        moved.entry = target
        moved.account = target.account
        sourceRows.forEachIndexed { index, row -> row.executionOrder = index }
        targetRows.forEachIndexed { index, row -> row.executionOrder = index }
        typedAdventureMapRepository.saveAll(sourceRows + targetRows)
        source.enabled = source.enabled && sourceRows.isNotEmpty()
        updateTypedEntry(source, source.enabled)
        updateTypedEntry(target, target.enabled)
    }

    @Transactional
    fun reorderEntries(accountId: Long, request: ReorderAutomationEntriesRequest): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        val current = typedAutomationQueryRepository.findEntries(accountId)
        val requested = request.entryIds
        if (requested.size != requested.toSet().size) invalid("자동화 순서에 같은 항목이 중복되었습니다.")
        if (requested.size != current.size || requested.toSet() != current.map { it.id }.toSet()) {
            invalid("자동화 목록이 변경되었습니다. 목록을 새로고침한 뒤 다시 시도해 주세요.")
        }
        val byId = current.associateBy { it.id }
        val now = timeProvider.now()
        requested.forEachIndexed { index, id ->
            byId.getValue(id).also { it.priority = index; it.updatedAt = now }
        }
        enqueueSettingsWake(accountId)
        return buildTypedAggregate(accountId)
    }

    @Transactional
    fun updateQuest(accountId: Long, request: UpdateQuestAutomationRequest): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        val entry = requireTypedEntry(accountId, AutomationType.QUEST)
        if (request.quests.size > MAX_SETTING_ITEMS) invalid("퀘스트 설정은 최대 100개까지 저장할 수 있습니다.")
        if (request.quests.sumOf { it.maps.size } > MAX_SETTING_ITEMS) {
            invalid("전체 퀘스트 맵은 최대 100개까지 저장할 수 있습니다.")
        }
        val normalized = request.quests.map { selection ->
            if (selection.sourceOrder < 0) invalid("퀘스트 출처 순서는 0 이상이어야 합니다.")
            val questKey = bounded(selection.questKey, MAX_QUEST_CODE_LENGTH, "퀘스트 코드")
            val displayCode = bounded(selection.displayCode, MAX_QUEST_CODE_LENGTH, "퀘스트 표시 코드")
            val questName = bounded(selection.questName, MAX_QUEST_NAME_LENGTH, "퀘스트명")
            if (!QuestIdentityFactory.matches(questKey, displayCode, questName)) {
                invalid("퀘스트 식별자가 표시 코드와 이름에 맞지 않습니다.")
            }
            selection.copy(
                questKey = questKey,
                displayCode = displayCode,
                questName = questName,
                maps = normalizeQuestMaps(selection.maps),
            )
        }
        rejectDuplicates(normalized.map { it.questKey }, "같은 퀘스트를 두 번 설정할 수 없습니다.")
        rejectDuplicates(normalized.map { it.sourceOrder }, "퀘스트 출처 순서를 중복해서 사용할 수 없습니다.")
        val presets = validateMapAndPresetReferences(accountId, normalized.flatMap { it.maps }.map(::mapReference))
        val oldSelections = typedAutomationQueryRepository.findQuestSelections(entry.id)
        val oldMaps = typedAutomationQueryRepository.findQuestMaps(oldSelections.map { it.id })
        val oldMapsBySelection = oldMaps.groupBy { it.questSelection.id }
        val oldConfig = oldSelections.associate { selection ->
            selection.questKey to QuestTargetConfig(
                selection.enabled,
                oldMapsBySelection[selection.id].orEmpty()
                    .sortedBy { it.executionOrder }
                    .map { map ->
                        QuestMapTargetConfig(
                            map.missionKey,
                            map.categoryId,
                            map.mapCode,
                            map.presetMode,
                            map.partyPreset?.id,
                            map.manuallyOverridden,
                        )
                    },
            )
        }
        val newConfig = normalized.associate { selection ->
            selection.questKey to QuestTargetConfig(
                selection.enabled,
                selection.maps.map { map ->
                    QuestMapTargetConfig(
                        map.missionKey,
                        map.categoryId,
                        map.mapCode,
                        map.presetMode,
                        map.partyPresetId,
                        map.manuallyOverridden,
                    )
                },
            )
        }
        workLifecycle.stopForConfigurationChange(
            accountId,
            entry.id,
            changedKeys(oldConfig, newConfig),
            wholeEntry = entry.enabled && !request.enabled,
        )
        if (oldMaps.isNotEmpty()) {
            typedQuestMapRepository.deleteAll(oldMaps)
            typedQuestMapRepository.flush()
        }
        if (oldSelections.isNotEmpty()) {
            typedQuestSelectionRepository.deleteAll(oldSelections)
            typedQuestSelectionRepository.flush()
        }
        normalized.sortedWith(compareBy<QuestSelectionRequest> { it.sourceOrder }.thenBy { it.questKey })
            .forEachIndexed { sourceOrder, selection ->
                val row = typedQuestSelectionRepository.save(
                    QuestAutomationSelectionEntity(
                        entry = entry,
                        questKey = selection.questKey,
                        enabled = selection.enabled,
                        sourceOrder = sourceOrder,
                        displayCode = selection.displayCode,
                        questName = selection.questName,
                    ),
                )
                selection.maps.forEachIndexed { executionOrder, map ->
                    typedQuestMapRepository.save(
                        QuestAutomationMapEntity(
                            questSelection = row,
                            missionKey = map.missionKey,
                            categoryId = map.categoryId,
                            mapCode = map.mapCode,
                            presetMode = map.presetMode,
                            partyPreset = map.partyPresetId?.let(presets::getValue),
                            executionOrder = executionOrder,
                            manuallyOverridden = map.manuallyOverridden,
                        ),
                    )
                }
            }
        updateTypedEntry(entry, request.enabled)
        enqueueSettingsWake(accountId)
        return buildTypedAggregate(accountId)
    }

    @Transactional
    fun updateHomeQuests(accountId: Long, request: UpdateHomeQuestAutomationRequest): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        val entry = requireTypedEntry(accountId, AutomationType.HOME_QUEST)
        val normalized = request.quests.map { selection ->
            selection.copy(
                questId = bounded(selection.questId, 64, "자택 퀘스트 식별자"),
                questName = bounded(selection.questName, 300, "자택 퀘스트명"),
            )
        }
        rejectDuplicates(normalized.map { it.questId }, "같은 자택 퀘스트를 두 번 설정할 수 없습니다.")
        rejectDuplicates(normalized.map { it.sourceOrder }, "자택 퀘스트 순서를 중복해서 사용할 수 없습니다.")
        if (normalized.any { it.sourceOrder < 0 }) invalid("자택 퀘스트 순서는 0 이상이어야 합니다.")

        val old = typedAutomationQueryRepository.findHomeQuestSelections(entry.id)
        val oldConfig = old.associate { it.questId to it.enabled }
        val newConfig = normalized.associate { it.questId to it.enabled }
        workLifecycle.stopForConfigurationChange(
            accountId,
            entry.id,
            changedKeys(oldConfig, newConfig),
            wholeEntry = entry.enabled && !request.enabled,
        )
        if (old.isNotEmpty()) {
            typedHomeQuestSelectionRepository.deleteAll(old)
            typedHomeQuestSelectionRepository.flush()
        }
        normalized.sortedWith(compareBy<HomeQuestSelectionRequest> { it.sourceOrder }.thenBy { it.questId })
            .forEachIndexed { sourceOrder, selection ->
                typedHomeQuestSelectionRepository.save(
                    HomeQuestAutomationSelectionEntity(
                        entry = entry,
                        questId = selection.questId,
                        questName = selection.questName,
                        enabled = selection.enabled,
                        sourceOrder = sourceOrder,
                    ),
                )
            }
        updateTypedEntry(entry, request.enabled)
        enqueueSettingsWake(accountId)
        return buildTypedAggregate(accountId)
    }

    @Transactional
    fun updateBattleMaps(accountId: Long, request: UpdateBattleMapAutomationRequest): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        val entry = requireTypedEntry(accountId, AutomationType.BATTLE_MAP)
        return replaceBattleMaps(accountId, entry, entry.displayName, request.enabled, request.maps)
    }

    @Transactional
    fun updateBattleMapGroup(
        accountId: Long,
        entryId: Long,
        request: UpdateBattleMapGroupRequest,
    ): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        val entry = requireEntryOfType(accountId, entryId, AutomationType.BATTLE_MAP)
        requireSettingsRevision(entry, request.settingsRevision)
        return replaceBattleMaps(
            accountId,
            entry,
            normalizeDisplayName(request.displayName),
            request.enabled,
            request.maps,
        )
    }

    private fun replaceBattleMaps(
        accountId: Long,
        entry: AutomationEntryEntity,
        displayName: String?,
        enabled: Boolean,
        maps: List<BattleMapSettingRequest>,
    ): TypedAutomationAggregateResponse {
        if (maps.size > MAX_SETTING_ITEMS) invalid("전투 맵 설정은 최대 100개까지 저장할 수 있습니다.")
        if (enabled && maps.isEmpty()) invalid("맵이 없는 묶음은 활성화할 수 없습니다.")
        val normalized = maps.map { map ->
            if (map.dailyTargetCount <= 0) invalid("일일 목표 횟수는 1 이상이어야 합니다.")
            if (map.executionOrder < 0) invalid("전투 맵 실행 순서는 0 이상이어야 합니다.")
            map.copy(
                categoryId = bounded(map.categoryId, MAX_CATEGORY_ID_LENGTH, "카테고리"),
                mapCode = bounded(map.mapCode, MAX_MAP_CODE_LENGTH, "맵 코드"),
            )
        }.sortedWith(compareBy<BattleMapSettingRequest> { it.executionOrder }.thenBy { it.categoryId }.thenBy { it.mapCode })
        rejectDuplicates(normalized.map { it.executionOrder }, "전투 맵 실행 순서를 중복해서 사용할 수 없습니다.")
        rejectDuplicates(normalized.map { it.categoryId to it.mapCode }, "같은 맵을 두 번 설정할 수 없습니다.")
        if (normalized.any { it.categoryId == app.spammy.hof.battle.model.BattleCategoryId.ADVENTURE_MAP.value }) {
            invalid("모험맵은 전투 맵 자동화에 설정할 수 없습니다.")
        }
        if (normalized.any { it.categoryId == app.spammy.hof.battle.model.BattleCategoryId.UNION.value }) {
            invalid("유니온은 전투 맵 자동화에 설정할 수 없습니다.")
        }
        if (normalized.any { it.categoryId == app.spammy.hof.battle.model.BattleCategoryId.RAID.value }) {
            invalid("레이드는 전투 맵 자동화에 설정할 수 없습니다.")
        }
        val battleEntryIds = typedAutomationQueryRepository.findEntries(accountId)
            .filter { it.type == AutomationType.BATTLE_MAP }
            .map { it.id }
        val otherSettings = typedAutomationQueryRepository.findBattleSettingsByEntryIds(battleEntryIds)
            .filter { it.entry.id != entry.id }
        if (otherSettings.size + normalized.size > MAX_SETTING_ITEMS) {
            invalid("전투 맵은 모든 묶음을 합쳐 최대 100개까지 저장할 수 있습니다.")
        }
        val occupied = otherSettings.map { it.categoryId to it.mapCode }.toSet()
        if (normalized.any { (it.categoryId to it.mapCode) in occupied }) {
            invalid("선택한 전투 맵은 이미 다른 묶음에 있습니다. 맵 이동을 사용해 주세요.")
        }
        val presets = validateMapAndPresetReferences(accountId, normalized.map(::mapReference))
        val old = typedAutomationQueryRepository.findBattleSettings(entry.id)
        val oldConfig = old.associate { setting ->
            "${setting.categoryId}/${setting.mapCode}" to BattleTargetConfig(
                setting.dailyTargetCount,
                setting.presetMode,
                setting.partyPreset?.id,
            )
        }
        val newConfig = normalized.associate { setting ->
            "${setting.categoryId}/${setting.mapCode}" to BattleTargetConfig(
                setting.dailyTargetCount,
                setting.presetMode,
                setting.partyPresetId,
            )
        }
        workLifecycle.stopForConfigurationChange(
            accountId,
            entry.id,
            changedKeys(oldConfig, newConfig),
            wholeEntry = entry.enabled && !enabled,
        )
        if (old.isNotEmpty()) {
            typedBattleMapRepository.deleteAll(old)
            typedBattleMapRepository.flush()
        }
        normalized.forEachIndexed { executionOrder, map ->
            typedBattleMapRepository.save(
                BattleAutomationMapEntity(
                    entry = entry,
                    categoryId = map.categoryId,
                    mapCode = map.mapCode,
                    dailyTargetCount = map.dailyTargetCount,
                    presetMode = map.presetMode,
                    partyPreset = map.partyPresetId?.let(presets::getValue),
                    executionOrder = executionOrder,
                ),
            )
        }
        updateTypedEntry(entry, enabled, displayName)
        enqueueSettingsWake(accountId)
        return buildTypedAggregate(accountId)
    }

    @Transactional
    fun updateAdventureMaps(
        accountId: Long,
        request: UpdateAdventureMapAutomationRequest,
    ): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        val entry = requireTypedEntry(accountId, AutomationType.ADVENTURE_MAP)
        return replaceAdventureMaps(accountId, entry, entry.displayName, request.enabled, request.maps)
    }

    @Transactional
    fun updateAdventureMapGroup(
        accountId: Long,
        entryId: Long,
        request: UpdateAdventureMapGroupRequest,
    ): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        val entry = requireEntryOfType(accountId, entryId, AutomationType.ADVENTURE_MAP)
        requireSettingsRevision(entry, request.settingsRevision)
        return replaceAdventureMaps(
            accountId,
            entry,
            normalizeDisplayName(request.displayName),
            request.enabled,
            request.maps,
        )
    }

    private fun replaceAdventureMaps(
        accountId: Long,
        entry: AutomationEntryEntity,
        displayName: String?,
        enabled: Boolean,
        maps: List<AdventureMapSettingRequest>,
    ): TypedAutomationAggregateResponse {
        if (maps.size > MAX_SETTING_ITEMS) invalid("모험맵 설정은 최대 100개까지 저장할 수 있습니다.")
        if (enabled && maps.isEmpty()) invalid("맵이 없는 묶음은 활성화할 수 없습니다.")
        val normalized = maps.map { map ->
            if (map.executionOrder < 0) invalid("모험맵 실행 순서는 0 이상이어야 합니다.")
            map.copy(
                categoryId = bounded(map.categoryId, MAX_CATEGORY_ID_LENGTH, "카테고리"),
                mapCode = bounded(map.mapCode, MAX_MAP_CODE_LENGTH, "맵 코드"),
            )
        }.sortedWith(compareBy<AdventureMapSettingRequest> { it.executionOrder }.thenBy { it.categoryId }.thenBy { it.mapCode })
        rejectDuplicates(normalized.map { it.executionOrder }, "모험맵 실행 순서를 중복해서 사용할 수 없습니다.")
        rejectDuplicates(normalized.map { it.categoryId to it.mapCode }, "같은 맵을 두 번 설정할 수 없습니다.")
        if (normalized.any { it.categoryId != app.spammy.hof.battle.model.BattleCategoryId.ADVENTURE_MAP.value }) {
            invalid("모험맵 카테고리의 맵만 설정할 수 있습니다.")
        }
        val adventureEntryIds = typedAutomationQueryRepository.findEntries(accountId)
            .filter { it.type == AutomationType.ADVENTURE_MAP }
            .map { it.id }
        val otherSettings = typedAutomationQueryRepository.findAdventureSettingsByEntryIds(adventureEntryIds)
            .filter { it.entry.id != entry.id }
        if (otherSettings.size + normalized.size > MAX_SETTING_ITEMS) {
            invalid("모험맵은 모든 묶음을 합쳐 최대 100개까지 저장할 수 있습니다.")
        }
        val occupied = otherSettings.map { it.categoryId to it.mapCode }.toSet()
        if (normalized.any { (it.categoryId to it.mapCode) in occupied }) {
            invalid("선택한 모험맵은 이미 다른 묶음에 있습니다. 맵 이동을 사용해 주세요.")
        }
        val presets = validateMapAndPresetReferences(accountId, normalized.map(::mapReference))
        val old = typedAutomationQueryRepository.findAdventureSettings(entry.id)
        val oldConfig = old.associate { setting ->
            "${setting.categoryId}/${setting.mapCode}" to AdventureTargetConfig(
                setting.presetMode,
                setting.partyPreset?.id,
            )
        }
        val newConfig = normalized.associate { setting ->
            "${setting.categoryId}/${setting.mapCode}" to AdventureTargetConfig(
                setting.presetMode,
                setting.partyPresetId,
            )
        }
        workLifecycle.stopForConfigurationChange(
            accountId,
            entry.id,
            changedKeys(oldConfig, newConfig),
            wholeEntry = entry.enabled && !enabled,
        )
        if (old.isNotEmpty()) {
            typedAdventureMapRepository.deleteAll(old)
            typedAdventureMapRepository.flush()
        }
        normalized.forEachIndexed { executionOrder, map ->
            typedAdventureMapRepository.save(
                AdventureAutomationMapEntity(
                    entry = entry,
                    categoryId = map.categoryId,
                    mapCode = map.mapCode,
                    presetMode = map.presetMode,
                    partyPreset = map.partyPresetId?.let(presets::getValue),
                    executionOrder = executionOrder,
                ),
            )
        }
        updateTypedEntry(entry, enabled, displayName)
        enqueueSettingsWake(accountId)
        return buildTypedAggregate(accountId)
    }

    @Transactional
    fun updateFishing(
        accountId: Long,
        request: UpdateFishingAutomationRequest,
    ): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        val entry = requireTypedEntry(accountId, AutomationType.FISHING)
        val old = typedAutomationQueryRepository.findFishingSetting(entry.id)
        val oldMaps = typedAutomationQueryRepository.findFishingMaps(entry.id)
        val normalized = request.maps.sortedWith(compareBy<FishingMapSettingRequest> { it.executionOrder }.thenBy { it.mapCode })
        rejectDuplicates(normalized.map { it.executionOrder }, "낚시 맵 실행 순서를 중복해서 사용할 수 없습니다.")
        rejectDuplicates(normalized.map { it.categoryId to it.mapCode }, "같은 낚시 맵을 두 번 설정할 수 없습니다.")
        if (normalized.any { it.categoryId != FISHING_BATTLE_CATEGORY }) invalid("낚시 전투 맵만 설정할 수 있습니다.")
        val presets = validateMapAndPresetReferences(accountId, normalized.map {
            TypedMapReference(it.categoryId, it.mapCode, it.presetMode, it.partyPresetId)
        })
        val oldConfig = oldMaps.associate { it.categoryId to it.mapCode to (it.presetMode to it.partyPreset?.id) }
        val newConfig = normalized.associate { it.categoryId to it.mapCode to (it.presetMode to it.partyPresetId) }
        val changed = oldConfig != newConfig || old != null
        workLifecycle.stopForConfigurationChange(
            accountId,
            entry.id,
            if (changed) setOf(FISHING_TARGET_KEY) else emptySet(),
            wholeEntry = changed || (entry.enabled && !request.enabled),
        )
        old?.let {
            typedFishingSettingRepository.delete(it)
            typedFishingSettingRepository.flush()
        }
        if (oldMaps.isNotEmpty()) {
            typedFishingMapRepository.deleteAll(oldMaps)
            typedFishingMapRepository.flush()
        }
        normalized.forEachIndexed { executionOrder, map ->
            typedFishingMapRepository.save(FishingAutomationMapEntity(
                entry = entry,
                categoryId = map.categoryId,
                mapCode = map.mapCode,
                presetMode = map.presetMode,
                partyPreset = map.partyPresetId?.let(presets::getValue),
                executionOrder = executionOrder,
            ))
        }
        updateTypedEntry(entry, request.enabled)
        enqueueSettingsWake(accountId)
        return buildTypedAggregate(accountId)
    }

    @Transactional
    fun updateUnion(
        accountId: Long,
        request: UpdateUnionAutomationRequest,
    ): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        val entry = requireTypedEntry(accountId, AutomationType.UNION)
        if (request.maps.size > MAX_SETTING_ITEMS) invalid("유니온 맵 설정은 최대 100개까지 저장할 수 있습니다.")
        val normalized = request.maps.map { map ->
            if (map.executionOrder < 0) invalid("유니온 맵 실행 순서는 0 이상이어야 합니다.")
            map.copy(
                categoryId = bounded(map.categoryId, MAX_CATEGORY_ID_LENGTH, "카테고리"),
                mapCode = bounded(map.mapCode, MAX_MAP_CODE_LENGTH, "맵 코드"),
            )
        }.sortedWith(compareBy<UnionMapSettingRequest> { it.executionOrder }.thenBy { it.mapCode })
        rejectDuplicates(normalized.map { it.executionOrder }, "유니온 맵 실행 순서를 중복해서 사용할 수 없습니다.")
        rejectDuplicates(normalized.map { it.categoryId to it.mapCode }, "같은 유니온 맵을 두 번 설정할 수 없습니다.")
        if (normalized.any { it.categoryId != UNION_CATEGORY }) invalid("유니온 카테고리의 맵만 설정할 수 있습니다.")
        val presets = validateMapAndPresetReferences(accountId, normalized.map(::mapReference))
        val old = typedAutomationQueryRepository.findUnionSettings(entry.id)
        val oldConfig = old.associate { setting ->
            "${setting.categoryId}/${setting.mapCode}" to UnionTargetConfig(setting.presetMode, setting.partyPreset?.id)
        }
        val newConfig = normalized.associate { setting ->
            "${setting.categoryId}/${setting.mapCode}" to UnionTargetConfig(setting.presetMode, setting.partyPresetId)
        }
        workLifecycle.stopForConfigurationChange(
            accountId,
            entry.id,
            changedKeys(oldConfig, newConfig),
            wholeEntry = entry.enabled && !request.enabled,
        )
        if (old.isNotEmpty()) {
            typedUnionMapRepository.deleteAll(old)
            typedUnionMapRepository.flush()
        }
        normalized.forEachIndexed { executionOrder, map ->
            typedUnionMapRepository.save(
                UnionAutomationMapEntity(
                    entry = entry,
                    categoryId = map.categoryId,
                    mapCode = map.mapCode,
                    presetMode = map.presetMode,
                    partyPreset = map.partyPresetId?.let(presets::getValue),
                    executionOrder = executionOrder,
                ),
            )
        }
        updateTypedEntry(entry, request.enabled)
        enqueueSettingsWake(accountId)
        return buildTypedAggregate(accountId)
    }

    @Transactional
    fun updateRaid(
        accountId: Long,
        request: UpdateRaidAutomationRequest,
    ): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        val entry = requireTypedEntry(accountId, AutomationType.RAID)
        if (request.targets.size > MAX_SETTING_ITEMS) invalid("레이드 설정은 최대 100개까지 저장할 수 있습니다.")
        val normalized = request.targets.map { target ->
            if (target.executionOrder < 0) invalid("레이드 실행 순서는 0 이상이어야 합니다.")
            target.copy(
                raidId = bounded(target.raidId, MAX_RAID_ID_LENGTH, "레이드 식별자"),
                displayName = bounded(target.displayName, MAX_QUEST_NAME_LENGTH, "레이드 이름"),
            )
        }.sortedWith(compareBy<RaidTargetSettingRequest> { it.executionOrder }.thenBy { it.raidId })
        rejectDuplicates(normalized.map { it.executionOrder }, "레이드 실행 순서를 중복해서 사용할 수 없습니다.")
        rejectDuplicates(normalized.map { it.raidId }, "같은 레이드를 두 번 설정할 수 없습니다.")
        val openCycle = typedAutomationQueryRepository.findOpenRaidCycle(accountId)
        if (openCycle?.entry?.id == entry.id && normalized.none { it.raidId == openCycle.raidId }) {
            recordManualRaidHandoff(accountId, entry.id, openCycle.raidId)
        }
        val references = normalized.map { target ->
            TypedMapReference(RAID_CATEGORY, target.raidId, target.presetMode, target.partyPresetId)
        }
        val presets = validateMapAndPresetReferences(accountId, references)
        val old = typedAutomationQueryRepository.findRaidTargets(entry.id)
        val oldConfig = old.associate { target ->
            target.raidId to RaidTargetConfig(target.displayName, target.presetMode, target.partyPreset?.id)
        }
        val newConfig = normalized.associate { target ->
            target.raidId to RaidTargetConfig(target.displayName, target.presetMode, target.partyPresetId)
        }
        workLifecycle.stopForConfigurationChange(
            accountId,
            entry.id,
            changedKeys(oldConfig, newConfig),
            wholeEntry = entry.enabled && !request.enabled,
        )
        if (old.isNotEmpty()) {
            typedRaidTargetRepository.deleteAll(old)
            typedRaidTargetRepository.flush()
        }
        normalized.forEachIndexed { executionOrder, target ->
            typedRaidTargetRepository.save(
                RaidAutomationTargetEntity(
                    entry = entry,
                    raidId = target.raidId,
                    displayName = target.displayName,
                    presetMode = target.presetMode,
                    partyPreset = target.partyPresetId?.let(presets::getValue),
                    executionOrder = executionOrder,
                ),
            )
        }
        updateTypedEntry(entry, request.enabled)
        enqueueSettingsWake(accountId)
        return buildTypedAggregate(accountId)
    }

    @Transactional
    fun startTyped(accountId: Long): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        if (typedAutomationQueryRepository.findEntries(accountId).none { it.enabled }) {
            invalid("활성화된 자동화 항목이 없습니다.")
        }
        typedLifecycleBridge.start(accountId, "USER_START")
        return buildTypedAggregate(accountId)
    }

    @Transactional
    fun pauseTyped(accountId: Long): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        typedLifecycleBridge.pause(accountId, "USER_PAUSE")
        return buildTypedAggregate(accountId)
    }

    @Transactional
    fun resumeTyped(accountId: Long): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        if (typedAutomationQueryRepository.findEntries(accountId).none { it.enabled }) {
            invalid("활성화된 자동화 항목이 없습니다.")
        }
        typedLifecycleBridge.resume(accountId, "USER_RESUME")
        return buildTypedAggregate(accountId)
    }

    @Transactional
    fun stopTyped(accountId: Long): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        typedLifecycleBridge.stop(accountId, AutomationStopReason.MANUAL_STOP, "USER_STOP")
        return buildTypedAggregate(accountId)
    }

    private fun buildTypedAggregate(accountId: Long): TypedAutomationAggregateResponse {
        val entries = typedAutomationQueryRepository.findEntries(accountId)
        val activeRaidRecoveryWarning = typedAutomationQueryRepository.findOpenRaidCycle(accountId)
            ?.toBattleRecoveryOrNull()
            ?.warningMessage()
        val holdWarningsByEntry = workSessionQueries.findWaiting(accountId)
            .asSequence()
            .filter { it.workType == AutomationWorkType.RAID }
            .mapNotNull { session -> session.holdMessage?.let { session.entryId to it } }
            .groupBy({ it.first }, { it.second })
        val battleMapProgress = if (entries.any { it.type == AutomationType.BATTLE_MAP }) {
            typedAutomationQueryRepository.findBattleProgressRows(
                accountId,
                timeProvider.now().atZone(KOREA_ZONE).toLocalDate(),
                TypedAutomationQueryRepository.BATTLE_MAP_AUTOMATION_SOURCE,
            ).sortedWith(compareBy({ it.categoryId }, { it.mapCode })).map { progress ->
                BattleMapDailyProgressResponse(
                    categoryId = progress.categoryId,
                    mapCode = progress.mapCode,
                    successfulRuns = progress.successfulRuns,
                )
            }
        } else {
            emptyList()
        }
        val presets = partyPresetQueryRepository.findAllByAccountId(accountId)
        val presetMembers = partyPresetQueryRepository.findMembersByPresetIds(presets.map { it.id })
        val validPresetIds = validAutomationPartyMembersByPreset(
            presetMembers,
            presetId = { it.preset.id },
            characterId = { it.character?.hofCharacterId },
            patternSlotCode = { it.patternSlot?.slotCode },
            canLoadPattern = { it.patternSlot?.canLoad == true },
        ).keys
        val primaryPresetId = presets.singleOrNull { it.isPrimary }?.id
        val responses = entries.map { entry ->
            val quests = if (entry.type == AutomationType.QUEST) {
                typedAutomationQueryRepository.findQuestSelections(entry.id)
            } else {
                emptyList()
            }
            val homeQuests = if (entry.type == AutomationType.HOME_QUEST) {
                typedAutomationQueryRepository.findHomeQuestSelections(entry.id)
            } else {
                emptyList()
            }
            val questMaps = typedAutomationQueryRepository.findQuestMaps(quests.map { it.id })
                .groupBy { it.questSelection.id }
            val battle = if (entry.type == AutomationType.BATTLE_MAP) {
                typedAutomationQueryRepository.findBattleSettings(entry.id)
            } else {
                emptyList()
            }
            val adventure = if (entry.type == AutomationType.ADVENTURE_MAP) {
                typedAutomationQueryRepository.findAdventureSettings(entry.id)
            } else {
                emptyList()
            }
            val fishingMaps = if (entry.type == AutomationType.FISHING) typedAutomationQueryRepository.findFishingMaps(entry.id) else emptyList()
            val union = if (entry.type == AutomationType.UNION) {
                typedAutomationQueryRepository.findUnionSettings(entry.id)
            } else {
                emptyList()
            }
            val raid = if (entry.type == AutomationType.RAID) {
                typedAutomationQueryRepository.findRaidTargets(entry.id)
            } else {
                emptyList()
            }
            val adventureCatalog = if (adventure.isEmpty()) {
                emptyMap()
            } else {
                battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(
                    adventure.map { it.categoryId to it.mapCode }.toSet(),
                ).associateBy { it.categoryId to it.mapCode }
            }
            val unionCatalog = if (union.isEmpty()) {
                emptyMap()
            } else {
                battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(
                    union.map { it.categoryId to it.mapCode }.toSet(),
                ).associateBy { it.categoryId to it.mapCode }
            }
            val fishingCatalog = if (fishingMaps.isEmpty()) emptyMap() else battleMapQueryRepository
                .findMapsByCategoryIdAndMapCodePairs(fishingMaps.map { it.categoryId to it.mapCode }.toSet())
                .associateBy { it.categoryId to it.mapCode }
            val warnings = (
                typedWarnings(entry, quests, homeQuests, questMaps, battle, adventure, fishingMaps, union, raid, primaryPresetId, validPresetIds) +
                    holdWarningsByEntry[entry.id].orEmpty()
                ).distinct()
            TypedAutomationEntryResponse(
                id = entry.id,
                type = entry.type,
                enabled = entry.enabled,
                priority = entry.priority,
                ready = warnings.isEmpty(),
                warnings = warnings,
                quests = quests.map { selection ->
                    QuestSelectionResponse(
                        selection.questKey,
                        selection.enabled,
                        selection.sourceOrder,
                        questMaps[selection.id].orEmpty().map { map ->
                            QuestMapSettingResponse(
                                map.missionKey,
                                map.categoryId,
                                map.mapCode,
                                map.presetMode,
                                map.partyPreset?.id,
                                map.executionOrder,
                                map.manuallyOverridden,
                            )
                        },
                        selection.displayCode,
                        selection.questName,
                    )
                },
                homeQuests = homeQuests.map { selection ->
                    HomeQuestSelectionResponse(selection.questId, selection.questName, selection.enabled, selection.sourceOrder)
                },
                battleMaps = battle.map { map ->
                    BattleMapSettingResponse(
                        map.categoryId,
                        map.mapCode,
                        map.dailyTargetCount,
                        map.presetMode,
                        map.partyPreset?.id,
                        map.executionOrder,
                    )
                },
                battleMapProgress = if (entry.type == AutomationType.BATTLE_MAP) battleMapProgress else emptyList(),
                adventureMaps = adventure.map { map ->
                    AdventureMapSettingResponse(
                        map.categoryId,
                        map.mapCode,
                        map.presetMode,
                        map.partyPreset?.id,
                        map.executionOrder,
                        adventureCatalog[map.categoryId to map.mapCode]?.name,
                    )
                },
                fishingMaps = fishingMaps.map { map -> FishingMapSettingResponse(
                    map.categoryId, map.mapCode, map.presetMode, map.partyPreset?.id, map.executionOrder,
                    fishingCatalog[map.categoryId to map.mapCode]?.name,
                ) },
                unionMaps = union.map { map ->
                    UnionMapSettingResponse(
                        map.categoryId,
                        map.mapCode,
                        map.presetMode,
                        map.partyPreset?.id,
                        map.executionOrder,
                        unionCatalog[map.categoryId to map.mapCode]?.name,
                    )
                },
                raidTargets = raid.map { target ->
                    RaidTargetSettingResponse(
                        target.raidId,
                        target.displayName,
                        target.presetMode,
                        target.partyPreset?.id,
                        target.executionOrder,
                    )
                },
                displayName = entry.displayName,
                settingsRevision = entry.settingsRevision.toString(),
            )
        }
        val runtime = typedAutomationQueryRepository.findRuntimeState(accountId)
        val currentActionRow = if (runtime?.lifecycleStatus == TypedAutomationLifecycle.STOPPED) {
            runtime.stopActionId?.let { typedAutomationQueryRepository.findStoppedTypedAction(accountId, it) }
        } else {
            typedAutomationQueryRepository.findActiveTypedAction(accountId)
        }
        val currentAction = currentActionRow?.let(::typedCurrentAction)
        val today = timeProvider.now().atZone(KOREA_ZONE).toLocalDate()
        val latestRefresh = typedAutomationQueryRepository.findLatestAdventureRefresh(accountId)
        val dailyRefresh = AdventureDailyRefreshResponse(
            status = if (latestRefresh?.refreshDate == today) "COMPLETE" else "PENDING",
            refreshDate = latestRefresh?.refreshDate?.toString(),
            refreshedAt = latestRefresh?.refreshedAt?.toString(),
        )
        val persistedWarnings = runtime?.warningText.orEmpty().lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList() + listOfNotNull(activeRaidRecoveryWarning)
        val configWarnings = responses.flatMap(TypedAutomationEntryResponse::warnings)
        return TypedAutomationAggregateResponse(
            entries = responses,
            hofStatus = hofStatusSnapshots.findLatest(accountId),
            runtime = TypedAutomationRuntimeResponse(
                lifecycle = runtime?.lifecycleStatus ?: TypedAutomationLifecycle.STOPPED,
                stopReason = runtime?.stopReason,
                nextAttemptAt = runtime?.nextAttemptAt?.toString(),
                waitReason = runtime?.waitReason,
                warnings = (persistedWarnings + configWarnings).distinct(),
                lastError = runtime?.lastError,
                currentAction = currentAction,
                dailyRefresh = dailyRefresh,
            ),
        )
    }

    private fun typedCurrentAction(
        row: app.spammy.hof.automation.entity.TypedAutomationActionRunEntity,
    ): TypedAutomationCurrentActionResponse? {
        val descriptor = try {
            actionLifecycleModule.restore(row, row.account.id).descriptor
        } catch (_: RuntimeException) {
            return null
        }
        val display = descriptor.display
        return TypedAutomationCurrentActionResponse(
            source = descriptor.source,
            kind = descriptor.storageKind,
            actionLabel = descriptor.actionLabel,
            entryDisplayName = row.entryDisplayName ?: row.entry?.let { entry ->
                automationEntryDisplayNames(
                    typedAutomationQueryRepository.findEntries(row.account.id),
                )[entry.id]
            },
            questName = display?.questName,
            missionLabel = display?.missionLabel,
            missionCurrent = display?.missionCurrent,
            missionRequired = display?.missionRequired,
            mapName = display?.mapName,
            battleCount = descriptor.battleCount,
        )
    }

    private fun typedWarnings(
        entry: AutomationEntryEntity,
        quests: List<QuestAutomationSelectionEntity>,
        homeQuests: List<HomeQuestAutomationSelectionEntity>,
        questMaps: Map<Long, List<QuestAutomationMapEntity>>,
        battle: List<BattleAutomationMapEntity>,
        adventure: List<AdventureAutomationMapEntity>,
        fishingMaps: List<FishingAutomationMapEntity>,
        union: List<UnionAutomationMapEntity>,
        raid: List<RaidAutomationTargetEntity>,
        primaryPresetId: Long?,
        validPresetIds: Set<Long>,
    ): List<String> {
        if (!entry.enabled) return emptyList()
        val warnings = linkedSetOf<String>()
        when (entry.type) {
            AutomationType.QUEST -> {
                if (quests.none { it.enabled }) warnings += "활성화된 퀘스트가 없습니다."
                quests.filter { it.enabled }.forEach { selection ->
                    val maps = questMaps[selection.id].orEmpty()
                    maps.forEach { map ->
                        presetWarning(
                            map.presetMode,
                            map.partyPreset?.id,
                            primaryPresetId,
                            validPresetIds,
                        )?.let(warnings::add)
                    }
                }
            }
            AutomationType.HOME_QUEST -> {
                if (homeQuests.none { it.enabled }) warnings += "활성화된 자택 퀘스트가 없습니다."
            }
            AutomationType.BATTLE_MAP -> {
                if (battle.isEmpty()) warnings += "전투 맵 설정이 없습니다."
                battle.forEach { map ->
                    presetWarning(
                        map.presetMode,
                        map.partyPreset?.id,
                        primaryPresetId,
                        validPresetIds,
                    )?.let(warnings::add)
                }
            }
            AutomationType.ADVENTURE_MAP -> {
                if (adventure.isEmpty()) warnings += "모험맵 설정이 없습니다."
                adventure.forEach { map ->
                    presetWarning(
                        map.presetMode,
                        map.partyPreset?.id,
                        primaryPresetId,
                        validPresetIds,
                    )?.let(warnings::add)
                }
            }
            AutomationType.RAID -> {
                if (raid.isEmpty()) warnings += "레이드 설정이 없습니다."
                raid.forEach { target ->
                    presetWarning(
                        target.presetMode,
                        target.partyPreset?.id,
                        primaryPresetId,
                        validPresetIds,
                    )?.let(warnings::add)
                }
            }
            AutomationType.UNION -> {
                if (union.isEmpty()) warnings += "유니온 설정이 없습니다."
                union.forEach { map ->
                    presetWarning(
                        map.presetMode,
                        map.partyPreset?.id,
                        primaryPresetId,
                        validPresetIds,
                    )?.let(warnings::add)
                }
            }
            AutomationType.FISHING -> {
                if (primaryPresetId == null || primaryPresetId !in validPresetIds) {
                    warnings += "대표 프리셋 구성을 확인해 주세요."
                }
                fishingMaps.forEach { map ->
                    presetWarning(map.presetMode, map.partyPreset?.id, primaryPresetId, validPresetIds)?.let(warnings::add)
                }
            }
        }
        return warnings.toList()
    }

    private fun presetWarning(
        mode: PresetSelectionMode,
        presetId: Long?,
        primaryPresetId: Long?,
        validPresetIds: Set<Long>,
    ): String? = when (mode) {
        PresetSelectionMode.PRIMARY ->
            if (primaryPresetId !in validPresetIds) "기본 파티의 전투 설정을 완료해 주세요." else null
        PresetSelectionMode.EXPLICIT ->
            if (presetId !in validPresetIds) "선택한 파티의 전투 설정을 확인해 주세요." else null
    }

    private fun normalizeQuestMaps(maps: List<QuestMapSettingRequest>): List<QuestMapSettingRequest> {
        if (maps.size > MAX_SETTING_ITEMS) invalid("퀘스트별 맵은 최대 100개까지 저장할 수 있습니다.")
        val normalized = maps.map { map ->
            if (map.executionOrder < 0) invalid("퀘스트 맵 실행 순서는 0 이상이어야 합니다.")
            map.copy(
                missionKey = bounded(map.missionKey, MAX_QUEST_CODE_LENGTH, "미션 키"),
                categoryId = bounded(map.categoryId, MAX_CATEGORY_ID_LENGTH, "카테고리"),
                mapCode = bounded(map.mapCode, MAX_MAP_CODE_LENGTH, "맵 코드"),
            )
        }.sortedWith(
            compareBy<QuestMapSettingRequest> { it.executionOrder }
                .thenBy { it.missionKey }
                .thenBy { it.categoryId }
                .thenBy { it.mapCode },
        )
        normalized.groupBy { it.missionKey }.values.forEach { missionMaps ->
            rejectDuplicates(
                missionMaps.map { it.executionOrder },
                "퀘스트 맵 실행 순서를 중복해서 사용할 수 없습니다.",
            )
        }
        rejectDuplicates(
            normalized.map { listOf(it.missionKey, it.categoryId, it.mapCode) },
            "같은 미션 맵을 두 번 설정할 수 없습니다.",
        )
        return normalized
    }

    private data class TypedMapReference(
        val categoryId: String,
        val mapCode: String,
        val presetMode: PresetSelectionMode,
        val partyPresetId: Long?,
    )

    private fun mapReference(map: QuestMapSettingRequest) =
        TypedMapReference(map.categoryId, map.mapCode, map.presetMode, map.partyPresetId)

    private fun mapReference(map: BattleMapSettingRequest) =
        TypedMapReference(map.categoryId, map.mapCode, map.presetMode, map.partyPresetId)

    private fun mapReference(map: AdventureMapSettingRequest) =
        TypedMapReference(map.categoryId, map.mapCode, map.presetMode, map.partyPresetId)

    private fun mapReference(map: UnionMapSettingRequest) =
        TypedMapReference(map.categoryId, map.mapCode, map.presetMode, map.partyPresetId)

    private fun validateMapAndPresetReferences(
        accountId: Long,
        references: List<TypedMapReference>,
    ): Map<Long, PartyPresetEntity> {
        if (references.isEmpty()) return emptyMap()
        references.forEach { reference ->
            when (reference.presetMode) {
                PresetSelectionMode.PRIMARY -> if (reference.partyPresetId != null) {
                    invalid("기본 프리셋 모드에는 프리셋 ID를 지정할 수 없습니다.")
                }
                PresetSelectionMode.EXPLICIT -> if (reference.partyPresetId == null) {
                    invalid("명시적 프리셋 모드에는 프리셋 ID가 필요합니다.")
                }
            }
        }
        val requestedMaps = references.map { it.categoryId to it.mapCode }.toSet()
        val foundMaps = battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(requestedMaps)
            .map { it.categoryId to it.mapCode }
            .toSet()
        if (foundMaps != requestedMaps) invalid("선택한 맵을 찾을 수 없습니다. 맵 목록을 새로고침해 주세요.")
        val requestedPresets = references.mapNotNull { it.partyPresetId }.toSet()
        val foundPresets = partyPresetQueryRepository.findOwnedByAccountIdAndIds(accountId, requestedPresets)
            .associateBy { it.id }
        if (foundPresets.keys != requestedPresets) invalid("선택한 파티 프리셋을 찾을 수 없습니다.")
        return foundPresets
    }

    private fun validatePresetSelection(
        accountId: Long,
        mode: PresetSelectionMode,
        presetId: Long?,
    ): PartyPresetEntity? = when (mode) {
        PresetSelectionMode.PRIMARY -> {
            if (presetId != null) invalid("기본 프리셋 모드에는 프리셋 ID를 지정할 수 없습니다.")
            null
        }
        PresetSelectionMode.EXPLICIT -> {
            val id = presetId ?: invalid("명시적 프리셋 모드에는 프리셋 ID가 필요합니다.")
            partyPresetQueryRepository.findOwnedByAccountIdAndIds(accountId, setOf(id)).singleOrNull()
                ?: invalid("선택한 파티 프리셋을 찾을 수 없습니다.")
        }
    }

    private fun bounded(value: String, maxLength: Int, label: String): String {
        val normalized = value.trim()
        if (normalized.isEmpty()) invalid("$label 값이 비어 있습니다.")
        if (normalized.length > maxLength) invalid("$label 값이 허용 길이를 초과했습니다.")
        return normalized
    }

    private fun updateTypedEntry(
        entry: AutomationEntryEntity,
        enabled: Boolean,
        displayName: String? = entry.displayName,
    ) {
        entry.enabled = enabled
        entry.displayName = displayName
        entry.settingsRevision += 1
        entry.updatedAt = timeProvider.now()
        typedEntryRepository.save(entry)
    }

    private fun requireTypedEntry(accountId: Long, type: AutomationType): AutomationEntryEntity {
        val matches = typedAutomationQueryRepository.findEntries(accountId).filter { it.type == type }
        if (matches.isEmpty()) {
            throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "${type.name} 자동화 항목을 찾지 못했습니다.")
        }
        if (matches.size > 1) {
            throw ApiException(
                ErrorCode.AUTOMATION_SETTINGS_CONFLICT,
                "복수 맵 묶음은 최신 앱에서 항목별로 편집해 주세요.",
            )
        }
        return matches.single()
    }

    private fun requireEntryOfType(
        accountId: Long,
        entryId: Long,
        expectedType: AutomationType,
    ): AutomationEntryEntity {
        val entry = typedAutomationQueryRepository.findEntry(accountId, entryId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "자동화 항목을 찾지 못했습니다.")
        if (entry.type != expectedType) invalid("자동화 항목 유형이 요청과 일치하지 않습니다.")
        return entry
    }

    private fun requireSettingsRevision(entry: AutomationEntryEntity, expected: String) {
        if (expected != entry.settingsRevision.toString()) {
            throw ApiException(
                ErrorCode.AUTOMATION_SETTINGS_CONFLICT,
                "설정이 다른 곳에서 변경되었습니다. 최신 설정을 다시 확인해 주세요.",
            )
        }
    }

    private fun normalizeDisplayName(value: String?): String? {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        if (normalized.length > MAX_DISPLAY_NAME_LENGTH) invalid("맵 묶음 이름이 허용 길이를 초과했습니다.")
        return normalized
    }

    private fun lockTypedAccount(accountId: Long) = accountQueryRepository.findByIdForUpdate(accountId)
        ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")

    private fun enqueueSettingsWake(accountId: Long) {
        typedAutomationQueryRepository.lockRuntimeState(accountId)?.let { runtime ->
            runtime.nextAttemptAt = null
            runtime.waitReason = null
            runtime.warningText = null
            runtime.lastError = null
            runtime.updatedAt = timeProvider.now()
        }
        automationOutboxService.enqueue(accountId, "SETTINGS_UPDATED")
    }

    private fun recordManualRaidHandoff(accountId: Long, entryId: Long, raidId: String) {
        val recorded = raidCycleModule.recordObservedResult(
            accountId,
            RaidAttempt(entryId, RaidIntentKind.REFRESH, raidId, null),
            RaidResultObservation.ManualHandoff,
        ) as? RaidRecordResult.Recorded
        recorded?.completion?.let { decisionJournal.appendRaidCycleOutcome(accountId, it) }
    }

    private fun <T> rejectDuplicates(values: List<T>, message: String) {
        if (values.size != values.toSet().size) invalid(message)
    }

    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)

    private fun AutomationType.supportsMultipleEntries(): Boolean =
        this == AutomationType.BATTLE_MAP || this == AutomationType.ADVENTURE_MAP

    private fun <T> changedKeys(old: Map<String, T>, new: Map<String, T>): Set<String> =
        (old.keys + new.keys).filterTo(linkedSetOf()) { old[it] != new[it] }

    private data class BattleTargetConfig(
        val dailyTargetCount: Int,
        val presetMode: PresetSelectionMode,
        val presetId: Long?,
    )

    private data class AdventureTargetConfig(
        val presetMode: PresetSelectionMode,
        val presetId: Long?,
    )

    private data class UnionTargetConfig(
        val presetMode: PresetSelectionMode,
        val presetId: Long?,
    )

    private data class RaidTargetConfig(
        val displayName: String,
        val presetMode: PresetSelectionMode,
        val presetId: Long?,
    )

    private data class QuestTargetConfig(
        val enabled: Boolean,
        val maps: List<QuestMapTargetConfig>,
    )

    private data class QuestMapTargetConfig(
        val missionKey: String,
        val categoryId: String,
        val mapCode: String,
        val presetMode: PresetSelectionMode,
        val presetId: Long?,
        val manuallyOverridden: Boolean,
    )

    private fun invalid(message: String, cause: Throwable): Nothing =
        throw ApiException(ErrorCode.INVALID_REQUEST, message, cause)

    private companion object {
        val KOREA_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        const val MAX_CATEGORY_ID_LENGTH = 50
        const val MAX_MAP_CODE_LENGTH = 100
        const val MAX_DISPLAY_NAME_LENGTH = 100
        const val MAX_QUEST_CODE_LENGTH = 100
        const val MAX_QUEST_NAME_LENGTH = 255
        const val MAX_RAID_ID_LENGTH = 200
        const val MAX_SETTING_ITEMS = 100
        const val MAX_AUTOMATION_ENTRIES = 100
        const val FISHING_TARGET_KEY = "fishing"
        const val FISHING_BATTLE_CATEGORY = "battle_map"
        const val UNION_CATEGORY = "union"
        const val RAID_CATEGORY = "raid"
    }
}
