package app.spammy.hof.automation.service

import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.automation.dto.*
import app.spammy.hof.automation.entity.AdventureAutomationMapEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.BattleAutomationMapEntity
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.QuestAutomationMapEntity
import app.spammy.hof.automation.entity.QuestAutomationSelectionEntity
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.automation.repository.AdventureAutomationMapCommandRepository
import app.spammy.hof.automation.repository.AutomationEntryCommandRepository
import app.spammy.hof.automation.repository.BattleAutomationMapCommandRepository
import app.spammy.hof.automation.repository.QuestAutomationMapCommandRepository
import app.spammy.hof.automation.repository.QuestAutomationSelectionCommandRepository
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
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
    private val typedBattleMapRepository: BattleAutomationMapCommandRepository,
    private val typedAdventureMapRepository: AdventureAutomationMapCommandRepository,
    private val automationOutboxService: AutomationOutboxService,
    private val storedActionCodec: StoredTypedAutomationActionCodec,
    private val hofStatusSnapshots: HofStatusSnapshotService,
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
        if (current.any { it.type == request.type }) invalid("해당 자동화 유형은 이미 존재합니다.")
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
    fun deleteEntry(accountId: Long, entryId: Long): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        val target = typedAutomationQueryRepository.findEntry(accountId, entryId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "자동화 항목을 찾지 못했습니다.")
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
            val questCode = bounded(selection.questCode, MAX_QUEST_CODE_LENGTH, "퀘스트 코드")
            selection.copy(questCode = questCode, maps = normalizeQuestMaps(selection.maps))
        }
        rejectDuplicates(normalized.map { it.questCode }, "같은 퀘스트를 두 번 설정할 수 없습니다.")
        rejectDuplicates(normalized.map { it.sourceOrder }, "퀘스트 출처 순서를 중복해서 사용할 수 없습니다.")
        val presets = validateMapAndPresetReferences(accountId, normalized.flatMap { it.maps }.map(::mapReference))
        val oldSelections = typedAutomationQueryRepository.findQuestSelections(entry.id)
        val oldMaps = typedAutomationQueryRepository.findQuestMaps(oldSelections.map { it.id })
        if (oldMaps.isNotEmpty()) {
            typedQuestMapRepository.deleteAll(oldMaps)
            typedQuestMapRepository.flush()
        }
        if (oldSelections.isNotEmpty()) {
            typedQuestSelectionRepository.deleteAll(oldSelections)
            typedQuestSelectionRepository.flush()
        }
        normalized.sortedWith(compareBy<QuestSelectionRequest> { it.sourceOrder }.thenBy { it.questCode })
            .forEachIndexed { sourceOrder, selection ->
                val row = typedQuestSelectionRepository.save(
                    QuestAutomationSelectionEntity(
                        entry = entry,
                        questCode = selection.questCode,
                        enabled = selection.enabled,
                        sourceOrder = sourceOrder,
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
    fun updateBattleMaps(accountId: Long, request: UpdateBattleMapAutomationRequest): TypedAutomationAggregateResponse {
        lockTypedAccount(accountId)
        val entry = requireTypedEntry(accountId, AutomationType.BATTLE_MAP)
        if (request.maps.size > MAX_SETTING_ITEMS) invalid("전투 맵 설정은 최대 100개까지 저장할 수 있습니다.")
        val normalized = request.maps.map { map ->
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
        val presets = validateMapAndPresetReferences(accountId, normalized.map(::mapReference))
        val old = typedAutomationQueryRepository.findBattleSettings(entry.id)
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
        updateTypedEntry(entry, request.enabled)
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
        if (request.maps.size > MAX_SETTING_ITEMS) invalid("모험맵 설정은 최대 100개까지 저장할 수 있습니다.")
        val normalized = request.maps.map { map ->
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
        val presets = validateMapAndPresetReferences(accountId, normalized.map(::mapReference))
        val old = typedAutomationQueryRepository.findAdventureSettings(entry.id)
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
        if (typedAutomationQueryRepository.findRuntimeState(accountId)?.lifecycleStatus == TypedAutomationLifecycle.STOPPED) {
            invalid("중지된 자동화는 명시적으로 재개해 주세요.")
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
            val adventureCatalog = if (adventure.isEmpty()) {
                emptyMap()
            } else {
                battleMapQueryRepository.findMapsByCategoryIdAndMapCodePairs(
                    adventure.map { it.categoryId to it.mapCode }.toSet(),
                ).associateBy { it.categoryId to it.mapCode }
            }
            val warnings = typedWarnings(entry, quests, questMaps, battle, adventure, primaryPresetId, validPresetIds)
            TypedAutomationEntryResponse(
                id = entry.id,
                type = entry.type,
                enabled = entry.enabled,
                priority = entry.priority,
                ready = warnings.isEmpty(),
                warnings = warnings,
                quests = quests.map { selection ->
                    QuestSelectionResponse(
                        selection.questCode,
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
                    )
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
            .toList()
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
        val decoded = try {
            storedActionCodec.verifyPersisted(row, row.account.id)
        } catch (_: RuntimeException) {
            null
        }
        val payload = decoded?.payload
        val source = row.entry?.type ?: when (payload) {
            is StoredTypedActionPayload.QuestClaim,
            is StoredTypedActionPayload.QuestAccept,
            is StoredTypedActionPayload.QuestBattle,
            -> AutomationType.QUEST
            is StoredTypedActionPayload.BattleMap -> AutomationType.BATTLE_MAP
            is StoredTypedActionPayload.AdventureMap -> AutomationType.ADVENTURE_MAP
            null -> when {
                row.actionKind.startsWith("QUEST_") -> AutomationType.QUEST
                row.actionKind == "BATTLE_MAP" -> AutomationType.BATTLE_MAP
                row.actionKind == "ADVENTURE_MAP" -> AutomationType.ADVENTURE_MAP
                else -> null
            }
        } ?: return null
        val display = payload?.display
        val battleCount = when (payload) {
            is StoredTypedActionPayload.QuestBattle -> payload.battleCount
            is StoredTypedActionPayload.BattleMap -> payload.battleCount
            is StoredTypedActionPayload.AdventureMap -> payload.battleCount
            else -> null
        }
        return TypedAutomationCurrentActionResponse(
            source = source,
            kind = row.actionKind,
            actionLabel = when {
                payload is StoredTypedActionPayload.BattleMap && display == null -> "전투 진행 중"
                row.actionKind == "QUEST_CLAIM" -> "퀘스트 완료"
                row.actionKind == "QUEST_ACCEPT" -> "퀘스트 수락"
                row.actionKind == "QUEST_BATTLE" -> "퀘스트 전투"
                row.actionKind == "BATTLE_MAP" -> "전투맵"
                row.actionKind == "ADVENTURE_MAP" -> "모험맵"
                display != null -> "자동화 실행 중"
                else -> "전투 진행 중"
            },
            questName = display?.questName,
            missionLabel = display?.missionLabel,
            missionCurrent = display?.missionCurrent,
            missionRequired = display?.missionRequired,
            mapName = display?.mapName,
            battleCount = battleCount,
        )
    }

    private fun typedWarnings(
        entry: AutomationEntryEntity,
        quests: List<QuestAutomationSelectionEntity>,
        questMaps: Map<Long, List<QuestAutomationMapEntity>>,
        battle: List<BattleAutomationMapEntity>,
        adventure: List<AdventureAutomationMapEntity>,
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

    private fun bounded(value: String, maxLength: Int, label: String): String {
        val normalized = value.trim()
        if (normalized.isEmpty()) invalid("$label 값이 비어 있습니다.")
        if (normalized.length > maxLength) invalid("$label 값이 허용 길이를 초과했습니다.")
        return normalized
    }

    private fun updateTypedEntry(entry: AutomationEntryEntity, enabled: Boolean) {
        entry.enabled = enabled
        entry.updatedAt = timeProvider.now()
        typedEntryRepository.save(entry)
    }

    private fun requireTypedEntry(accountId: Long, type: AutomationType): AutomationEntryEntity =
        typedAutomationQueryRepository.findEntries(accountId).singleOrNull { it.type == type }
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "${type.name} 자동화 항목을 찾지 못했습니다.")

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

    private fun <T> rejectDuplicates(values: List<T>, message: String) {
        if (values.size != values.toSet().size) invalid(message)
    }

    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)

    private fun invalid(message: String, cause: Throwable): Nothing =
        throw ApiException(ErrorCode.INVALID_REQUEST, message, cause)

    private companion object {
        val KOREA_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        const val MAX_CATEGORY_ID_LENGTH = 50
        const val MAX_MAP_CODE_LENGTH = 100
        const val MAX_QUEST_CODE_LENGTH = 100
        const val MAX_SETTING_ITEMS = 100
    }
}
