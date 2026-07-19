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
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.service.QuestGatewayService
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.HexFormat
import java.util.UUID
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
    transactionManager: PlatformTransactionManager? = null,
) : TypedAutomationSnapshotLoader {
    private val readTransaction = transactionManager?.let { TransactionTemplate(it).apply { isReadOnly = true } }

    override fun loadTyped(accountId: Long): AutomationCoordinatorSnapshot {
        val before = inReadTransaction { materializeConfiguration(accountId) }
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Typed automation HTTP refresh must run without a transaction."
        }
        val liveQuests = refreshLiveState(accountId, before.categories)
        val after = inReadTransaction { materializeConfiguration(accountId) }
        if (before.version != after.version) throw TypedAutomationConfigurationChangedException()
        return inReadTransaction { assembleSnapshot(accountId, before, liveQuests) }
    }

    private fun refreshLiveState(accountId: Long, categories: List<String>): List<QuestSnapshot> = try {
        sessionRecovery.execute(accountId) {
            categories.forEach { battleMapService.findMaps(accountId, it) }
            questGateway.load(accountId)
        }
    } catch (error: Exception) {
        val causes = generateSequence<Throwable>(error) { it.cause }.toList()
        causes.filterIsInstance<AutomationLoginRequiredException>().firstOrNull()?.let { throw it }
        causes.filterIsInstance<ApiException>()
            .firstOrNull { it.errorCode == ErrorCode.CAPTCHA_REQUIRED }
            ?.let { throw it }
        if (causes.any { it is IOException } || causes.filterIsInstance<ApiException>().any { it.errorCode == ErrorCode.HOF_REQUEST_FAILED }) {
            throw SafeRetryableAutomationException("Transient live-state refresh failure.", error)
        }
        throw FatalAutomationException("Live-state refresh failed and cannot be retried safely.", error)
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
        val membersByPreset = memberConfigs.groupBy { it.presetId }
        val validPresetIds = membersByPreset.filterValues { rows -> rows.isNotEmpty() && rows.all { row ->
            row.characterId != null && row.canLoad && row.patternSlot?.toIntOrNull() != null
        } }.keys
        val parties = validPresetIds.associateWith { id ->
            val rows = membersByPreset.getValue(id).sortedBy { it.slotIndex }
            ResolvedAutomationParty(
                rows.map { requireNotNull(it.characterId) },
                rows.map { BattlePatternLoadRequest(requireNotNull(it.characterId), requireNotNull(it.patternSlot).toInt()) },
            )
        }
        val primary = presets.findPrimaryByAccountId(accountId)?.id?.takeIf { it in validPresetIds }
        val entryRows = typed.findEntries(accountId).sortedWith(compareBy<AutomationEntryEntity> { it.priority }.thenBy { it.id })
        val questEntryIds = entryRows.filter { it.type == AutomationType.QUEST }.map { it.id }
        val battleEntryIds = entryRows.filter { it.type == AutomationType.BATTLE_MAP }.map { it.id }
        val adventureEntryIds = entryRows.filter { it.type == AutomationType.ADVENTURE_MAP }.map { it.id }
        val questSelections = typed.findQuestSelectionsByEntryIds(questEntryIds)
        val selectionsByEntry = questSelections.groupBy { it.entry.id }
        val mapsBySelection = typed.findQuestMaps(questSelections.map { it.id }).groupBy { it.questSelection.id }
        val battleByEntry = typed.findBattleSettingsByEntryIds(battleEntryIds).groupBy { it.entry.id }
        val adventureByEntry = typed.findAdventureSettingsByEntryIds(adventureEntryIds).groupBy { it.entry.id }
        val entries = entryRows.map { entry ->
            val quest = if (entry.type == AutomationType.QUEST) {
                val selections = selectionsByEntry[entry.id].orEmpty()
                selections.map { selection ->
                    DetachedQuestSelection(selection.questCode, selection.enabled, selection.sourceOrder,
                        mapsBySelection[selection.id].orEmpty().map { map ->
                            DetachedQuestMap(map.missionKey, map.categoryId, map.mapCode, map.presetMode, map.partyPreset?.id, map.executionOrder, map.manuallyOverridden)
                        })
                }
            } else emptyList()
            val battle = if (entry.type == AutomationType.BATTLE_MAP) battleByEntry[entry.id].orEmpty().map {
                DetachedBattleSetting(it.categoryId, it.mapCode, it.dailyTargetCount, it.presetMode, it.partyPreset?.id, it.executionOrder)
            } else emptyList()
            val adventure = if (entry.type == AutomationType.ADVENTURE_MAP) adventureByEntry[entry.id].orEmpty().map {
                DetachedAdventureSetting(it.id, it.categoryId, it.mapCode, it.presetMode, it.partyPreset?.id, it.executionOrder)
            } else emptyList()
            DetachedEntry(entry.id, entry.type, entry.priority, entry.enabled, quest, battle, adventure)
        }
        val canonical = buildString {
            append("primary=").append(primary).append('|')
            presetRows.forEach { append("p:").append(it.id).append(':').append(it.updatedAt).append('|') }
            memberConfigs.forEach { append("m:").append(it).append('|') }
            entries.forEach { append("e:").append(it).append('|') }
        }
        val version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
        val categories = entries.filter { it.enabled }.flatMap { entry -> when (entry.type) {
            AutomationType.QUEST -> entry.quest.flatMap { it.maps }.map { it.categoryId }
            AutomationType.BATTLE_MAP -> entry.battle.map { it.categoryId }
            AutomationType.ADVENTURE_MAP -> entry.adventure.map { it.categoryId }
        } }.filter { it.isNotBlank() }.distinct()
        return DetachedConfiguration(entries, primary, validPresetIds, parties, categories, version)
    }

    private fun assembleSnapshot(accountId: Long, config: DetachedConfiguration, quests: List<QuestSnapshot>): AutomationCoordinatorSnapshot {
        val now = timeProvider.now()
        val states = maps.findAllStatesForExecution(accountId)
        val aliases = states.map { it.battleMap.categoryId }.distinct().flatMap(identities::loadAliasCandidates)
        return AutomationCoordinatorSnapshot(config.entries.filter { it.enabled }.map { entry -> when (entry.type) {
            AutomationType.QUEST -> AutomationCoordinatorEntry(entry.id, entry.type, quest = questSnapshot(accountId, entry, quests, states, aliases, config, now))
            AutomationType.BATTLE_MAP -> AutomationCoordinatorEntry(entry.id, entry.type, battle = battleSnapshot(accountId, entry, states, config, now))
            AutomationType.ADVENTURE_MAP -> AutomationCoordinatorEntry(entry.id, entry.type, adventure = adventureSnapshot(accountId, entry, states, config, now))
        } })
    }

    private fun questSnapshot(accountId: Long, entry: DetachedEntry, quests: List<QuestSnapshot>, states: List<AccountBattleMapStateEntity>, aliases: List<BattleMapIdentityCandidate>, config: DetachedConfiguration, now: Instant): QuestAutomationSnapshot {
        val selections = entry.quest.map { selection -> QuestAutomationSelection(selection.questCode, selection.enabled, selection.maps.map { map ->
            val resolved = when (map.presetMode) {
                PresetSelectionMode.PRIMARY -> config.primary
                PresetSelectionMode.EXPLICIT -> map.presetId?.takeIf { it in config.availablePresetIds }
            }
            QuestAutomationMapSelection(map.missionKey, map.categoryId, map.mapCode, map.mapCode,
                QuestPresetSelection(map.presetMode, map.presetId, resolved, true, resolved?.let(config.parties::get)), map.executionOrder, map.manuallyOverridden)
        }) }
        val questCodes = selections.map { it.questCode }.toSet()
        val persistedCycles = typed.findQuestCycles(accountId, questCodes).associateBy { it.questCode }
        val cycles = selections.associate { it.questCode to (persistedCycles[it.questCode]?.currentCycle?.toString() ?: "0") }
        val persistedCounters = typed.findQuestMapCounters(accountId, questCodes).associateBy {
            QuestCounterKey(it.questCode, it.questCycle, it.missionKey, it.categoryId, it.mapCode)
        }
        val counters = linkedMapOf<QuestCounterKey, Int>()
        selections.forEach { selection -> selection.maps.forEach { map ->
            val key = QuestCounterKey(selection.questCode, cycles.getValue(selection.questCode), map.missionKey, map.categoryId, map.mapCode)
            counters[key] = persistedCounters[key]?.successfulRuns ?: 0
        } }
        return QuestAutomationSnapshot(accountId, quests, selections, states.map(::questState), cycles, counters, aliases, now, config.primary, config.primary?.let(config.parties::get))
    }

    private fun battleSnapshot(accountId: Long, entry: DetachedEntry, states: List<AccountBattleMapStateEntity>, config: DetachedConfiguration, now: Instant): BattleMapAutomationSnapshot {
        val settings = entry.battle.map { BattleMapAutomationSetting(true, it.categoryId, it.mapCode, it.dailyTargetCount, BattleMapPresetSelection(it.presetMode, it.presetId), it.executionOrder) }
        val date = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        val progressRows = typed.findBattleProgressRows(accountId, date, BATTLE_MAP_PROGRESS_SOURCE)
            .associateBy { BattleMapProgressIdentity(it.categoryId, it.mapCode) }
        val progress = settings.associate { setting ->
            val key = BattleMapProgressIdentity(setting.categoryId, setting.mapCode)
            key to (progressRows[key]?.successfulRuns ?: 0)
        }
        return BattleMapAutomationSnapshot(accountId, settings, states.map(::battleState), progress, config.primary, config.availablePresetIds, UUID.randomUUID().toString(), now, config.parties)
    }

    private fun adventureSnapshot(accountId: Long, entry: DetachedEntry, states: List<AccountBattleMapStateEntity>, config: DetachedConfiguration, now: Instant): AdventureMapAutomationSnapshot {
        val settings = entry.adventure.map { AdventureMapAutomationSetting(it.id, true, it.categoryId, it.mapCode, AdventureMapPresetSelection(it.presetMode, it.presetId), it.executionOrder) }
        val resolutions = entry.adventure.associate { row -> row.id to when (row.presetMode) {
            PresetSelectionMode.PRIMARY -> config.primary?.let { AdventureMapPresetResolution.Valid(it, config.parties[it]) } ?: AdventureMapPresetResolution.Invalid("Select a primary party preset.")
            PresetSelectionMode.EXPLICIT -> row.presetId?.takeIf { it in config.availablePresetIds }?.let { AdventureMapPresetResolution.Valid(it, config.parties[it]) } ?: AdventureMapPresetResolution.Invalid("The explicit party preset is missing.")
        } }
        return AdventureMapAutomationSnapshot(accountId, settings, states.map(::adventureState), resolutions, entry.adventure.associate { it.id to UUID.randomUUID().toString() }, now)
    }

    private fun <T> inReadTransaction(block: () -> T): T = readTransaction?.execute { block() } ?: block()
    private fun questState(state: AccountBattleMapStateEntity) = AutomationMapState(state.battleMap.categoryId, state.battleMap.mapCode, state.battleMap.name, state.visible, state.battleMap.enabled, state.cooldownUntil, state.winRemaining, state.attemptRemaining, state.availableCount, state.keyMode, state.keyCount)
    private fun battleState(state: AccountBattleMapStateEntity) = BattleMapRunnableState(state.battleMap.categoryId, state.battleMap.mapCode, state.visible, state.battleMap.enabled, state.supportsThreeBattles, state.cooldownUntil, state.availableCount, state.attemptRemaining, state.winRemaining, state.keyMode, state.keyCount, state.battleMap.name)
    private fun adventureState(state: AccountBattleMapStateEntity) = AdventureMapRunnableState(state.battleMap.categoryId, state.battleMap.mapCode, true, state.visible, state.battleMap.enabled, state.cooldownUntil, null, state.attemptRemaining, state.winRemaining, state.availableCount, state.keyMode, state.keyCount, state.battleMap.name)

    private data class DetachedConfiguration(val entries: List<DetachedEntry>, val primary: Long?, val availablePresetIds: Set<Long>, val parties: Map<Long, ResolvedAutomationParty>, val categories: List<String>, val version: String)
    private data class DetachedEntry(val id: Long, val type: AutomationType, val priority: Int, val enabled: Boolean, val quest: List<DetachedQuestSelection>, val battle: List<DetachedBattleSetting>, val adventure: List<DetachedAdventureSetting>)
    private data class DetachedQuestSelection(val questCode: String, val enabled: Boolean, val order: Int, val maps: List<DetachedQuestMap>)
    private data class DetachedQuestMap(val missionKey: String, val categoryId: String, val mapCode: String, val presetMode: PresetSelectionMode, val presetId: Long?, val executionOrder: Int, val manuallyOverridden: Boolean)
    private data class DetachedBattleSetting(val categoryId: String, val mapCode: String, val dailyTargetCount: Int, val presetMode: PresetSelectionMode, val presetId: Long?, val executionOrder: Int)
    private data class DetachedAdventureSetting(val id: Long, val categoryId: String, val mapCode: String, val presetMode: PresetSelectionMode, val presetId: Long?, val executionOrder: Int)
    private data class DetachedMember(val presetId: Long, val slotIndex: Int, val characterId: String?, val patternSlot: String?, val canLoad: Boolean)

}
