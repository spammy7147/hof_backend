package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.policy.AutomationMapState
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.service.BattleMapIdentityResolver
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.quest.service.QuestGatewayService
import java.time.ZoneId
import java.util.UUID
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import org.springframework.stereotype.Component

@Component
class TypedLiveAutomationSnapshotLoader(
    private val questGateway: QuestGatewayService,
    private val typed: TypedAutomationQueryRepository,
    private val maps: BattleMapQueryRepository,
    private val presets: PartyPresetQueryRepository,
    private val identities: BattleMapIdentityResolver,
    private val battleMapService: BattleMapService,
    private val timeProvider: TimeProvider,
) : TypedAutomationSnapshotLoader {
    override fun loadTyped(accountId: Long): AutomationCoordinatorSnapshot {
        val entries = typed.findEntries(accountId).filter { it.enabled }.sortedWith(compareBy<AutomationEntryEntity> { it.priority }.thenBy { it.id })
        val categories = entries.flatMap { entry -> when (entry.type) {
            AutomationType.QUEST -> typed.findQuestMaps(typed.findQuestSelections(entry.id).map { it.id }).map { it.categoryId }
            AutomationType.BATTLE_MAP -> typed.findBattleSettings(entry.id).map { it.categoryId }
            AutomationType.ADVENTURE_MAP -> typed.findAdventureSettings(entry.id).map { it.categoryId }
        } }.distinct()
        // Authoritative GET refreshes happen without an encompassing transaction; their persistence is short-lived downstream.
        val liveQuests = try {
            categories.forEach { battleMapService.findMaps(accountId, it) }
            questGateway.load(accountId)
        } catch (error: Exception) { throw SafeRetryableAutomationException("Unable to refresh live automation state.", error) }
        val now = timeProvider.now()
        val stateEntities = maps.findAllStatesForExecution(accountId)
        val allPresets = presets.findAllByAccountId(accountId)
        val membersByPreset = presets.findMembersByPresetIds(allPresets.map { it.id }).groupBy { it.preset.id }
        val validPresetIds = membersByPreset.filterValues { members -> members.isNotEmpty() && members.all { member ->
                member.character != null && member.patternSlot?.canLoad == true && member.patternSlot?.slotCode?.toIntOrNull() != null
            } }.keys
        val resolvedParties = validPresetIds.associateWith { presetId ->
            val members = membersByPreset.getValue(presetId)
            ResolvedAutomationParty(
                members.map { requireNotNull(it.character).hofCharacterId },
                members.map { BattlePatternLoadRequest(requireNotNull(it.character).hofCharacterId, requireNotNull(it.patternSlot).slotCode.toInt()) },
            )
        }
        val primary = presets.findPrimaryByAccountId(accountId)?.id?.takeIf { it in validPresetIds }
        val availablePresetIds = validPresetIds
        val aliasCandidates = stateEntities.map { it.battleMap.categoryId }.distinct().flatMap(identities::loadAliasCandidates)

        return AutomationCoordinatorSnapshot(entries.map { entry ->
            when (entry.type) {
                AutomationType.QUEST -> AutomationCoordinatorEntry(entry.id, entry.type, quest = questSnapshot(accountId, entry.id, liveQuests, stateEntities, aliasCandidates, primary, availablePresetIds, resolvedParties, now))
                AutomationType.BATTLE_MAP -> AutomationCoordinatorEntry(entry.id, entry.type, battle = battleSnapshot(accountId, entry.id, stateEntities, primary, availablePresetIds, resolvedParties, now))
                AutomationType.ADVENTURE_MAP -> AutomationCoordinatorEntry(entry.id, entry.type, adventure = adventureSnapshot(accountId, entry.id, stateEntities, primary, availablePresetIds, resolvedParties, now))
            }
        })
    }

    private fun questSnapshot(accountId: Long, entryId: Long, quests: List<app.spammy.hof.quest.model.QuestSnapshot>, states: List<AccountBattleMapStateEntity>, aliases: List<app.spammy.hof.battle.service.BattleMapIdentityCandidate>, primary: Long?, available: Set<Long>, parties: Map<Long, ResolvedAutomationParty>, now: java.time.Instant): QuestAutomationSnapshot {
        val selections = typed.findQuestSelections(entryId)
        val mapsBySelection = typed.findQuestMaps(selections.map { it.id }).groupBy { it.questSelection.id }
        val selectionDtos = selections.map { selection ->
            QuestAutomationSelection(selection.questCode, selection.enabled, mapsBySelection[selection.id].orEmpty().map { map ->
                val configured = map.partyPreset?.id
                val resolved = when (map.presetMode) { PresetSelectionMode.PRIMARY -> primary; PresetSelectionMode.EXPLICIT -> configured?.takeIf { it in available } }
                QuestAutomationMapSelection(map.missionKey, map.categoryId, map.mapCode, map.mapCode,
                    QuestPresetSelection(map.presetMode, configured, resolved, true, resolved?.let(parties::get)), map.executionOrder, map.manuallyOverridden)
            })
        }
        val cycles = selections.associate { it.questCode to (typed.findQuestCycle(accountId, it.questCode)?.currentCycle?.toString() ?: "0") }
        val counters = linkedMapOf<QuestCounterKey, Int>()
        selectionDtos.forEach { selection -> selection.maps.forEach { map ->
            val cycle = cycles.getValue(selection.questCode)
            counters[QuestCounterKey(selection.questCode, cycle, map.missionKey, map.categoryId, map.mapCode)] =
                typed.findQuestMapWins(accountId, selection.questCode, cycle, map.missionKey, map.categoryId, map.mapCode)
        } }
        return QuestAutomationSnapshot(accountId, quests, selectionDtos, states.map(::questState), cycles, counters, aliases, now, primary, primary?.let(parties::get))
    }

    private fun battleSnapshot(accountId: Long, entryId: Long, states: List<AccountBattleMapStateEntity>, primary: Long?, available: Set<Long>, parties: Map<Long, ResolvedAutomationParty>, now: java.time.Instant): BattleMapAutomationSnapshot {
        val settings = typed.findBattleSettings(entryId).map { setting ->
            BattleMapAutomationSetting(true, setting.categoryId, setting.mapCode, setting.dailyTargetCount,
                BattleMapPresetSelection(setting.presetMode, setting.partyPreset?.id), setting.executionOrder)
        }
        val date = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        val progress = settings.associate { BattleMapProgressIdentity(it.categoryId, it.mapCode) to typed.findBattleWins(accountId, date, BATTLE_MAP_PROGRESS_SOURCE, it.mapCode, it.categoryId) }
        return BattleMapAutomationSnapshot(accountId, settings, states.map(::battleState), progress, primary, available, UUID.randomUUID().toString(), now, parties)
    }

    private fun adventureSnapshot(accountId: Long, entryId: Long, states: List<AccountBattleMapStateEntity>, primary: Long?, available: Set<Long>, parties: Map<Long, ResolvedAutomationParty>, now: java.time.Instant): AdventureMapAutomationSnapshot {
        val rows = typed.findAdventureSettings(entryId)
        val settings = rows.map { AdventureMapAutomationSetting(it.id, true, it.categoryId, it.mapCode, AdventureMapPresetSelection(it.presetMode, it.partyPreset?.id), it.executionOrder) }
        val resolutions = rows.associate { row -> row.id to when (row.presetMode) {
            PresetSelectionMode.PRIMARY -> primary?.let { AdventureMapPresetResolution.Valid(it, parties[it]) } ?: AdventureMapPresetResolution.Invalid("Select a primary party preset.")
            PresetSelectionMode.EXPLICIT -> row.partyPreset?.id?.takeIf { it in available }?.let { AdventureMapPresetResolution.Valid(it, parties[it]) } ?: AdventureMapPresetResolution.Invalid("The explicit party preset is missing.")
        } }
        return AdventureMapAutomationSnapshot(accountId, settings, states.map(::adventureState), resolutions,
            rows.associate { it.id to UUID.randomUUID().toString() }, now)
    }

    private fun questState(state: AccountBattleMapStateEntity) = AutomationMapState(state.battleMap.categoryId, state.battleMap.mapCode, state.battleMap.name, state.visible, state.battleMap.enabled, state.cooldownUntil, state.winRemaining, state.attemptRemaining, state.availableCount, state.keyCount)
    private fun battleState(state: AccountBattleMapStateEntity) = BattleMapRunnableState(state.battleMap.categoryId, state.battleMap.mapCode, state.visible, state.battleMap.enabled, state.supportsThreeBattles, state.cooldownUntil, state.availableCount, state.attemptRemaining, state.winRemaining, state.keyCount)
    private fun adventureState(state: AccountBattleMapStateEntity) = AdventureMapRunnableState(state.battleMap.categoryId, state.battleMap.mapCode, true, state.visible, state.battleMap.enabled, state.cooldownUntil, null, state.attemptRemaining, state.winRemaining, state.availableCount, state.keyCount)
}
