package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.service.BattleMapIdentityCandidate
import app.spammy.hof.battle.service.BattleMapIdentityResolver
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.parser.QuestPageObservation
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.status.service.HofStatusSnapshotService
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.service.FishingAutomationObservation
import app.spammy.hof.town.fishing.service.FishingService
import app.spammy.hof.town.home.dto.HomeResponse
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.service.HomeService
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.HexFormat
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

class TypedAutomationConfigurationChangedException : RuntimeException("Typed automation configuration changed during live refresh.")

@Component
class TypedLiveAutomationSnapshotLoader(
    private val questGateway: QuestGatewayService,
    private val typed: TypedAutomationQueryRepository,
    private val maps: BattleMapQueryRepository,
    private val presets: PartyPresetQueryRepository,
    private val identities: BattleMapIdentityResolver,
    private val battleMapService: BattleMapService,
    private val timeProvider: TimeProvider,
    private val sessionRecovery: HofSessionRecoveryExecutor,
    private val hofStatusSnapshots: HofStatusSnapshotService,
    transactionManager: PlatformTransactionManager? = null,
    private val fishingService: FishingService? = null,
    private val homeService: HomeService? = null,
) : TypedAutomationSnapshotLoader, DecisionScopedTypedAutomationSnapshotLoader {
    private val readTransaction = transactionManager?.let { TransactionTemplate(it).apply { isReadOnly = true } }
    private val activeDecision = ThreadLocal<DecisionSnapshotLoader?>()

    override fun beginDecision(accountId: Long): AutoCloseable {
        check(activeDecision.get() == null) { "Typed automation decision scopes cannot be nested." }
        val decision = createDecision(accountId)
        activeDecision.set(decision)
        return AutoCloseable {
            if (activeDecision.get() === decision) activeDecision.remove()
        }
    }

    override fun loadEntry(
        accountId: Long,
        entryId: Long,
        targetKey: String?,
        questOverride: List<QuestSnapshot>?,
    ): AutomationEntrySnapshot {
        val active = activeDecision.get()
        return (active ?: createDecision(accountId)).loadEntry(accountId, entryId, targetKey, questOverride)
    }

    override fun openDecision(accountId: Long): TypedAutomationSnapshotLoader = createDecision(accountId)

    private fun createDecision(accountId: Long) = DecisionSnapshotLoader(
        accountId = accountId,
        before = inReadTransaction { materializeConfiguration(accountId) },
    )

    private inner class DecisionSnapshotLoader(
        private val accountId: Long,
        private val before: DetachedConfiguration,
    ) : TypedAutomationSnapshotLoader {
        private val cache = DecisionLiveStateCache()

        override fun loadEntry(
            accountId: Long,
            entryId: Long,
            targetKey: String?,
            questOverride: List<QuestSnapshot>?,
        ): AutomationEntrySnapshot {
            require(accountId == this.accountId) { "A decision snapshot belongs to one account." }
            val scopedBefore = before.scoped(entryId, targetKey)
            check(!TransactionSynchronizationManager.isActualTransactionActive()) {
                "Typed automation HTTP refresh must run without a transaction."
            }
            val live = if (questOverride != null) {
                refreshLiveState(accountId, scopedBefore, cache, includeQuests = false).copy(
                    quests = questOverride,
                    questPageComplete = true,
                )
            } else {
                refreshLiveState(
                    accountId,
                    scopedBefore,
                    cache,
                    includeQuests = scopedBefore.entries.single().type == AutomationType.QUEST,
                )
            }
            val after = inReadTransaction { materializeConfiguration(accountId) }
            if (before.version != after.version) throw TypedAutomationConfigurationChangedException()
            return inReadTransaction {
                assembleEntries(accountId, after.scoped(entryId, targetKey), live).single()
            }
        }
    }

    private fun refreshLiveState(
        accountId: Long,
        config: DetachedConfiguration,
        cache: DecisionLiveStateCache,
        includeQuests: Boolean = true,
    ): LiveAutomationState = try {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Typed automation HTTP refresh must run without a transaction."
        }
        sessionRecovery.execute(accountId) {
            config.categories.filter { it !in cache.refreshedCategories }.forEach { category ->
                battleMapService.findMaps(accountId, category, HofRequestOrigin.AUTOMATION)
                cache.refreshedCategories += category
            }
            val questObservation = if (includeQuests) {
                cache.questObservation ?: questGateway.loadObservation(accountId, HofRequestOrigin.AUTOMATION).also {
                    cache.questObservation = it
                }
            } else {
                QuestPageObservation(emptyList(), complete = true)
            }
            val includesFishing = config.entries.any { it.enabled && it.type == AutomationType.FISHING }
            val includesHome = config.entries.any { it.enabled && it.type == AutomationType.HOME_QUEST }
            LiveAutomationState(
                questObservation.quests,
                questObservation.complete,
                if (includesFishing) cache.loadFishing(accountId) else null,
                if (includesHome) cache.loadHome(accountId) else null,
            )
        }
    } catch (error: Exception) {
        val causes = generateSequence<Throwable>(error) { it.cause }.toList()
        causes.filterIsInstance<HofAutomationDeferredException>().firstOrNull()?.let { throw it }
        causes.filterIsInstance<AutomationLoginRequiredException>().firstOrNull()?.let { throw it }
        causes.filterIsInstance<ApiException>()
            .firstOrNull { it.errorCode == ErrorCode.CAPTCHA_REQUIRED }
            ?.let { throw it }
        if (causes.any { it is IOException } || causes.filterIsInstance<ApiException>().any { it.errorCode == ErrorCode.HOF_REQUEST_FAILED }) {
            throw SafeRetryableAutomationException("Transient live-state refresh failure.", error)
        }
        throw FatalAutomationException("Live-state refresh failed and cannot be retried safely.", error)
    }

    private inner class DecisionLiveStateCache {
        val refreshedCategories = linkedSetOf<String>()
        var questObservation: QuestPageObservation? = null
        private var fishingLoaded = false
        private var fishing: FishingAutomationObservation? = null
        private var homeLoaded = false
        private var home: HomeResponse? = null

        fun loadFishing(accountId: Long): FishingAutomationObservation? {
            if (!fishingLoaded) {
                fishing = fishingService?.loadForAutomation(accountId)
                fishingLoaded = true
            }
            return fishing
        }

        fun loadHome(accountId: Long): HomeResponse? {
            if (!homeLoaded) {
                home = homeService?.load(accountId, HomeMode.HOME, HofRequestOrigin.AUTOMATION)
                homeLoaded = true
            }
            return home
        }
    }

    private fun materializeConfiguration(accountId: Long): DetachedConfiguration {
        val presetRows = presets.findAllByAccountId(accountId).sortedBy { it.id }
        val members = presets.findMembersByPresetIds(presetRows.map { it.id })
            .sortedWith(compareBy({ it.preset.id }, { it.slotIndex }))
        val memberConfigs = members.map {
            DetachedMember(
                it.preset.id, it.slotIndex, it.character?.hofCharacterId,
                it.patternSlot?.slotCode, it.patternSlot?.canLoad == true,
            )
        }
        val validMembersByPreset = validAutomationPartyMembersByPreset(
            memberConfigs,
            presetId = { it.presetId },
            characterId = { it.characterId },
            patternSlotCode = { it.patternSlot },
            canLoadPattern = { it.canLoad },
        )
        val validPresetIds = validMembersByPreset.keys
        val parties = validPresetIds.associateWith { id ->
            val rows = validMembersByPreset.getValue(id).sortedBy { it.slotIndex }
            ResolvedAutomationParty(
                rows.map { requireNotNull(it.characterId) },
                rows.map { BattlePatternLoadRequest(requireNotNull(it.characterId), requireNotNull(it.patternSlot).toInt()) },
            )
        }
        val primary = presets.findPrimaryByAccountId(accountId)?.id?.takeIf { it in validPresetIds }
        val entryRows = typed.findEntries(accountId).sortedWith(compareBy<AutomationEntryEntity> { it.priority }.thenBy { it.id })
        val questEntryIds = entryRows.filter { it.type == AutomationType.QUEST }.map { it.id }
        val homeQuestEntryIds = entryRows.filter { it.type == AutomationType.HOME_QUEST }.map { it.id }
        val battleEntryIds = entryRows.filter { it.type == AutomationType.BATTLE_MAP }.map { it.id }
        val adventureEntryIds = entryRows.filter { it.type == AutomationType.ADVENTURE_MAP }.map { it.id }
        val questSelections = typed.findQuestSelectionsByEntryIds(questEntryIds)
        val homeQuestSelectionsByEntry = typed.findHomeQuestSelectionsByEntryIds(homeQuestEntryIds).groupBy { it.entry.id }
        val selectionsByEntry = questSelections.groupBy { it.entry.id }
        val mapsBySelection = typed.findQuestMaps(questSelections.map { it.id }).groupBy { it.questSelection.id }
        val battleByEntry = typed.findBattleSettingsByEntryIds(battleEntryIds).groupBy { it.entry.id }
        val adventureByEntry = typed.findAdventureSettingsByEntryIds(adventureEntryIds).groupBy { it.entry.id }
        val entries = entryRows.map { entry ->
            val quest = if (entry.type == AutomationType.QUEST) {
                val selections = selectionsByEntry[entry.id].orEmpty()
                selections.map { selection ->
                    DetachedQuestSelection(selection.questKey, selection.enabled, selection.sourceOrder,
                        mapsBySelection[selection.id].orEmpty().map { map ->
                            DetachedQuestMap(map.missionKey, map.categoryId, map.mapCode, map.presetMode, map.partyPreset?.id, map.executionOrder, map.manuallyOverridden)
                        })
                }
            } else emptyList()
            val homeQuests = if (entry.type == AutomationType.HOME_QUEST) {
                homeQuestSelectionsByEntry[entry.id].orEmpty().map {
                    DetachedHomeQuestSelection(it.questId, it.questName, it.enabled, it.sourceOrder)
                }
            } else emptyList()
            val battle = if (entry.type == AutomationType.BATTLE_MAP) battleByEntry[entry.id].orEmpty().map {
                DetachedBattleSetting(it.categoryId, it.mapCode, it.dailyTargetCount, it.presetMode, it.partyPreset?.id, it.executionOrder)
            } else emptyList()
            val adventure = if (entry.type == AutomationType.ADVENTURE_MAP) adventureByEntry[entry.id].orEmpty().map {
                DetachedAdventureSetting(it.id, it.categoryId, it.mapCode, it.presetMode, it.partyPreset?.id, it.executionOrder)
            } else emptyList()
            val union = if (entry.type == AutomationType.UNION) typed.findUnionSettings(entry.id).map {
                DetachedUnionSetting(it.categoryId, it.mapCode, it.presetMode, it.partyPreset?.id, it.executionOrder)
            } else emptyList()
            val fishingMaps = if (entry.type == AutomationType.FISHING) typed.findFishingMaps(entry.id).map {
                DetachedFishingMap(it.categoryId, it.mapCode, it.presetMode, it.partyPreset?.id)
            } else emptyList()
            val rotation = if (entry.type == AutomationType.UNION) typed.findRotationState(entry.id)?.currentTargetKey else null
            DetachedEntry(
                entry.id,
                entry.type,
                entry.priority,
                entry.enabled,
                entry.displayName,
                entry.settingsRevision,
                quest,
                homeQuests,
                battle,
                adventure,
                union,
                fishingMaps,
                rotation,
            )
        }
        val canonical = buildString {
            append("primary=").append(primary).append('|')
            presetRows.forEach { append("p:").append(it.id).append(':').append(it.updatedAt).append('|') }
            memberConfigs.forEach { append("m:").append(it).append('|') }
            entries.forEach { append("e:").append(it).append('|') }
        }
        val version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
        val categories = entries.filter { it.enabled }.flatMap { entry -> when (entry.type) {
            AutomationType.QUEST -> entry.quest.asSequence().filter { it.enabled }
                .flatMap { it.maps.asSequence() }.map { it.categoryId }.toList()
            AutomationType.HOME_QUEST -> emptyList()
            AutomationType.BATTLE_MAP -> entry.battle.map { it.categoryId }
            AutomationType.ADVENTURE_MAP -> entry.adventure.map { it.categoryId }
            AutomationType.RAID -> emptyList()
            AutomationType.UNION -> entry.union.map { it.categoryId }
            // 정상 START/CATCH에서는 낚시 응답만으로 완전하므로 방해 전투 map을 미리 읽지 않는다.
            AutomationType.FISHING -> emptyList()
        } }.filter { it.isNotBlank() }.distinct()
        return DetachedConfiguration(entries, primary, validPresetIds, parties, categories, version)
    }

    private fun assembleEntries(
        accountId: Long,
        config: DetachedConfiguration,
        live: LiveAutomationState,
    ): List<AutomationEntrySnapshot> {
        val now = timeProvider.now()
        val states = maps.findAllStatesForExecution(accountId)
        val aliases = states.map { it.battleMap.categoryId }.distinct().flatMap(identities::loadAliasCandidates)
        val timeSnapshot = hofStatusSnapshots.findLatest(accountId)?.let {
            AutomationTimeSnapshot(it.timeCurrent, it.timeMax, it.observedAt)
        }
        val runnableEntries = config.entries.filter { it.enabled && it.type != AutomationType.RAID }
        return runnableEntries.map { entry -> when (entry.type) {
            AutomationType.QUEST -> AutomationEntrySnapshot(
                entry.id,
                entry.type,
                quest = questSnapshot(
                    accountId,
                    entry,
                    live.quests,
                    live.questPageComplete,
                    states,
                    aliases,
                    config,
                    now,
                    timeSnapshot,
                ),
            )
            AutomationType.HOME_QUEST -> AutomationEntrySnapshot(
                entry.id,
                entry.type,
                homeQuest = live.home?.let { home ->
                    HomeQuestAutomationSnapshot(
                        accountId,
                        home.quests,
                        entry.homeQuests.map { HomeQuestAutomationSelection(it.questId, it.questName, it.enabled, it.sourceOrder) },
                        now,
                    )
                },
            )
            AutomationType.BATTLE_MAP -> AutomationEntrySnapshot(entry.id, entry.type, battle = battleSnapshot(accountId, entry, states, config, now, timeSnapshot))
            AutomationType.ADVENTURE_MAP -> AutomationEntrySnapshot(entry.id, entry.type, adventure = adventureSnapshot(accountId, entry, states, config, now, timeSnapshot))
            AutomationType.UNION -> AutomationEntrySnapshot(entry.id, entry.type, union = UnionAutomationSnapshot(
                accountId, entry.union.map { setting ->
                    val resolved = resolvePreset(setting.presetMode, setting.presetId, config)
                    UnionAutomationSetting("${setting.categoryId}:${setting.mapCode}", setting.categoryId, setting.mapCode,
                        setting.presetMode, resolved, setting.executionOrder, resolved?.let(config.parties::get))
                }, states.map(::battleState), entry.rotationTarget, now,
            ))
            AutomationType.FISHING -> AutomationEntrySnapshot(entry.id, entry.type, fishing = live.fishing?.let { observation ->
                val state = observation.response
                val primary = config.primary?.let { presetId ->
                    FishingAutomationPreset(PresetSelectionMode.PRIMARY, presetId, config.parties[presetId])
                }
                FishingAutomationSnapshot(accountId, state, entry.fishingMaps.map { setting ->
                    val resolved = resolvePreset(setting.presetMode, setting.presetId, config)
                    FishingAutomationMapSetting(setting.categoryId, setting.mapCode, setting.presetMode, resolved, resolved?.let(config.parties::get))
                }, primary, now, observation)
            })
            AutomationType.RAID -> AutomationEntrySnapshot(entry.id, entry.type)
        } }
    }

    private fun resolvePreset(mode: PresetSelectionMode, presetId: Long?, config: DetachedConfiguration): Long? = when (mode) {
        PresetSelectionMode.PRIMARY -> config.primary
        PresetSelectionMode.EXPLICIT -> presetId?.takeIf { it in config.availablePresetIds }
    }

    private fun questSnapshot(accountId: Long, entry: DetachedEntry, quests: List<QuestSnapshot>, pageComplete: Boolean, states: List<AccountBattleMapStateEntity>, aliases: List<BattleMapIdentityCandidate>, config: DetachedConfiguration, now: Instant, timeSnapshot: AutomationTimeSnapshot?): QuestAutomationSnapshot {
        val selections = entry.quest.map { selection -> QuestAutomationSelection(selection.questKey, selection.enabled, selection.maps.map { map ->
            val resolved = when (map.presetMode) {
                PresetSelectionMode.PRIMARY -> config.primary
                PresetSelectionMode.EXPLICIT -> map.presetId?.takeIf { it in config.availablePresetIds }
            }
            QuestAutomationMapSelection(map.missionKey, map.categoryId, map.mapCode,
                QuestPresetSelection(map.presetMode, map.presetId, resolved, true, resolved?.let(config.parties::get)), map.executionOrder, map.manuallyOverridden)
        }) }
        val questKeys = selections.map { it.questKey }.toSet()
        val persistedCycles = typed.findQuestCycles(accountId, questKeys).associateBy { it.questKey }
        val cycles = selections.associate { it.questKey to (persistedCycles[it.questKey]?.currentCycle?.toString() ?: "0") }
        val persistedCounters = typed.findQuestMapCounters(accountId, questKeys).associateBy {
            QuestCounterKey(it.questKey, it.questCycle, it.missionKey, it.categoryId, it.mapCode)
        }
        val counters = linkedMapOf<QuestCounterKey, Int>()
        selections.forEach { selection -> selection.maps.forEach { map ->
            val key = QuestCounterKey(selection.questKey, cycles.getValue(selection.questKey), map.missionKey, map.categoryId, map.mapCode)
            counters[key] = persistedCounters[key]?.successfulRuns ?: 0
        } }
        return QuestAutomationSnapshot(
            accountId,
            quests,
            selections,
            states.map(::questState),
            cycles,
            counters,
            aliases,
            now,
            config.primary,
            config.primary?.let(config.parties::get),
            timeSnapshot,
            pageComplete = pageComplete,
        )
    }

    private fun battleSnapshot(accountId: Long, entry: DetachedEntry, states: List<AccountBattleMapStateEntity>, config: DetachedConfiguration, now: Instant, timeSnapshot: AutomationTimeSnapshot?): BattleMapAutomationSnapshot {
        val settings = entry.battle.map { BattleMapAutomationSetting(true, it.categoryId, it.mapCode, it.dailyTargetCount, BattleMapPresetSelection(it.presetMode, it.presetId), it.executionOrder) }
        val date = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        val progressRows = typed.findBattleProgressRows(accountId, date, BATTLE_MAP_PROGRESS_SOURCE)
            .associateBy { BattleMapProgressIdentity(it.categoryId, it.mapCode) }
        val progress = settings.associate { setting ->
            val key = BattleMapProgressIdentity(setting.categoryId, setting.mapCode)
            key to (progressRows[key]?.successfulRuns ?: 0)
        }
        return BattleMapAutomationSnapshot(accountId, settings, states.map(::battleState), progress, config.primary, config.availablePresetIds, UUID.randomUUID().toString(), now, config.parties, timeSnapshot)
    }

    private fun adventureSnapshot(accountId: Long, entry: DetachedEntry, states: List<AccountBattleMapStateEntity>, config: DetachedConfiguration, now: Instant, timeSnapshot: AutomationTimeSnapshot?): AdventureMapAutomationSnapshot {
        val settings = entry.adventure.map { AdventureMapAutomationSetting(it.id, true, it.categoryId, it.mapCode, AdventureMapPresetSelection(it.presetMode, it.presetId), it.executionOrder) }
        val statesByIdentity = states.associateBy { it.battleMap.categoryId to it.battleMap.mapCode }
        settings.filter(AdventureMapAutomationSetting::enabled).forEach { setting ->
            val state = statesByIdentity[setting.categoryId to setting.mapCode]
            if (state?.battleMap?.requiredTime == null) {
                log.warn(
                    "Automation adventure TIME cost missing accountId={} categoryId={} mapCode={} fallbackTime=100",
                    accountId,
                    setting.categoryId,
                    setting.mapCode,
                )
            }
        }
        val resolutions = entry.adventure.associate { row -> row.id to when (row.presetMode) {
            PresetSelectionMode.PRIMARY -> config.primary?.let { AdventureMapPresetResolution.Valid(it, config.parties[it]) } ?: AdventureMapPresetResolution.Invalid("Select a primary party preset.")
            PresetSelectionMode.EXPLICIT -> row.presetId?.takeIf { it in config.availablePresetIds }?.let { AdventureMapPresetResolution.Valid(it, config.parties[it]) } ?: AdventureMapPresetResolution.Invalid("The explicit party preset is missing.")
        } }
        return AdventureMapAutomationSnapshot(accountId, settings, states.map(::adventureState), resolutions, entry.adventure.associate { it.id to UUID.randomUUID().toString() }, now, timeSnapshot)
    }

    private fun <T> inReadTransaction(block: () -> T): T = readTransaction?.execute { block() } ?: block()

    private fun DetachedConfiguration.scoped(entryId: Long, targetKey: String?): DetachedConfiguration {
        val entry = entries.singleOrNull { it.id == entryId }
            ?: throw AutomationConfigurationException("Automation entry $entryId is missing or duplicated.")
        val scopedEntry = targetKey?.let { entry.scopedToTarget(it) } ?: entry
        val scopedEntries = listOf(scopedEntry)
        return copy(entries = scopedEntries, categories = categoriesFor(scopedEntries))
    }

    private fun DetachedEntry.scopedToTarget(targetKey: String): DetachedEntry = when (type) {
        AutomationType.QUEST -> copy(quest = quest.filter { it.questKey == targetKey })
        AutomationType.HOME_QUEST -> copy(homeQuests = homeQuests.filter { it.questId == targetKey })
        AutomationType.BATTLE_MAP -> copy(battle = battle.filter { "${it.categoryId}/${it.mapCode}" == targetKey })
        AutomationType.ADVENTURE_MAP -> copy(adventure = adventure.filter { "${it.categoryId}/${it.mapCode}" == targetKey })
        AutomationType.RAID -> this
        AutomationType.UNION -> copy(union = union.filter { "${it.categoryId}/${it.mapCode}" == targetKey || "${it.categoryId}:${it.mapCode}" == targetKey })
        AutomationType.FISHING -> this
    }

    private fun categoriesFor(entries: List<DetachedEntry>): List<String> = entries.flatMap { entry ->
        when (entry.type) {
            AutomationType.QUEST -> entry.quest.asSequence().filter { it.enabled }
                .flatMap { it.maps.asSequence() }.map { it.categoryId }.toList()
            AutomationType.HOME_QUEST -> emptyList()
            AutomationType.BATTLE_MAP -> entry.battle.map { it.categoryId }
            AutomationType.ADVENTURE_MAP -> entry.adventure.map { it.categoryId }
            AutomationType.RAID -> emptyList()
            AutomationType.UNION -> entry.union.map { it.categoryId }
            AutomationType.FISHING -> emptyList()
        }
    }.filter(String::isNotBlank).distinct()

    private fun questState(state: AccountBattleMapStateEntity) = AutomationMapState(state.battleMap.categoryId, state.battleMap.mapCode, state.battleMap.name, state.visible, state.battleMap.enabled, state.cooldownUntil, state.winRemaining, state.attemptRemaining, state.availableCount, state.keyMode, state.keyCount, state.supportsThreeBattles, state.battleMap.requiredTime)
    private fun battleState(state: AccountBattleMapStateEntity) = BattleMapRunnableState(state.battleMap.categoryId, state.battleMap.mapCode, state.visible, state.battleMap.enabled, state.supportsThreeBattles, state.cooldownUntil, state.availableCount, state.attemptRemaining, state.winRemaining, state.keyMode, state.keyCount, state.battleMap.name)
    private fun adventureState(state: AccountBattleMapStateEntity) = AdventureMapRunnableState(state.battleMap.categoryId, state.battleMap.mapCode, true, state.visible, state.battleMap.enabled, state.cooldownUntil, null, state.attemptRemaining, state.winRemaining, state.availableCount, state.keyMode, state.keyCount, state.battleMap.name, state.battleMap.requiredTime)

    private data class DetachedConfiguration(val entries: List<DetachedEntry>, val primary: Long?, val availablePresetIds: Set<Long>, val parties: Map<Long, ResolvedAutomationParty>, val categories: List<String>, val version: String)
    private data class DetachedEntry(
        val id: Long, val type: AutomationType, val priority: Int, val enabled: Boolean,
        val displayName: String?, val settingsRevision: Long,
        val quest: List<DetachedQuestSelection>, val homeQuests: List<DetachedHomeQuestSelection>,
        val battle: List<DetachedBattleSetting>, val adventure: List<DetachedAdventureSetting>,
        val union: List<DetachedUnionSetting>, val fishingMaps: List<DetachedFishingMap>, val rotationTarget: String?,
    )
    private data class DetachedQuestSelection(val questKey: String, val enabled: Boolean, val order: Int, val maps: List<DetachedQuestMap>)
    private data class DetachedHomeQuestSelection(val questId: String, val questName: String, val enabled: Boolean, val sourceOrder: Int)
    private data class DetachedQuestMap(val missionKey: String, val categoryId: String, val mapCode: String, val presetMode: PresetSelectionMode, val presetId: Long?, val executionOrder: Int, val manuallyOverridden: Boolean)
    private data class DetachedBattleSetting(val categoryId: String, val mapCode: String, val dailyTargetCount: Int, val presetMode: PresetSelectionMode, val presetId: Long?, val executionOrder: Int)
    private data class DetachedAdventureSetting(val id: Long, val categoryId: String, val mapCode: String, val presetMode: PresetSelectionMode, val presetId: Long?, val executionOrder: Int)
    private data class DetachedUnionSetting(val categoryId: String, val mapCode: String, val presetMode: PresetSelectionMode, val presetId: Long?, val executionOrder: Int)
    private data class DetachedFishingMap(val categoryId: String, val mapCode: String, val presetMode: PresetSelectionMode, val presetId: Long?)
    private data class DetachedMember(val presetId: Long, val slotIndex: Int, val characterId: String?, val patternSlot: String?, val canLoad: Boolean)
    private data class LiveAutomationState(
        val quests: List<QuestSnapshot>,
        val questPageComplete: Boolean,
        val fishing: FishingAutomationObservation?,
        val home: HomeResponse?,
    )

    private companion object {
        val log = LoggerFactory.getLogger(TypedLiveAutomationSnapshotLoader::class.java)
    }
}
