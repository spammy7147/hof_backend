package app.spammy.hof.automation.convergence

import app.spammy.hof.automation.raid.RaidAuthoritativeState
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.service.AdventureMapAutomationAction
import app.spammy.hof.automation.service.BattleAutomationActionSource
import app.spammy.hof.automation.service.BattleMapAutomationAction
import app.spammy.hof.automation.service.FishingTownAutomationAction
import app.spammy.hof.automation.service.HomeQuestAutomationAction
import app.spammy.hof.automation.service.HomeQuestAutomationActionType
import app.spammy.hof.automation.service.PreparedAutomationAction
import app.spammy.hof.automation.service.QuestAction
import app.spammy.hof.automation.service.QuestAutomationSnapshot
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.automation.service.RaidCycleAbortAutomationAction
import app.spammy.hof.automation.service.RaidTownAutomationAction
import app.spammy.hof.automation.service.StoredTypedActionPayload
import app.spammy.hof.automation.service.StoredTypedAutomationAction
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.raid.model.RaidAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import org.springframework.stereotype.Component

@Component
class StoredActionConvergenceSelectionFactory {
    fun authoritativeRaidBaseline(state: RaidAuthoritativeState) = AuthoritativeConvergenceBaseline(
        scope = scope(AutomationIsolationScopeKind.RAID_ENTRY, state.raidId),
        fingerprint = fingerprint(canonicalRaidState(state)),
    )

    /** 한 퀘스트의 현재 미션들은 같은 격리 범위 안에서 동시에 유효한 기준이다. */
    fun authoritativeQuestBattleBaselines(snapshot: QuestAutomationSnapshot): Map<String, Set<String>> = snapshot.quests
        .filter { it.state == QuestState.ACTIVE }
        .associate { quest -> quest.questKey to quest.missions
            .filter { it.type in setOf(QuestMissionType.MONSTER_KILL, QuestMissionType.MAP_CLEAR) }
            .map { mission -> fingerprint(questBattleBaseline(quest.questKey,
                snapshot.currentCycles[quest.questKey] ?: "0", mission.key,
                mission.progress?.current, mission.progress?.required)) }.toSet()
        }

    fun create(
        stored: StoredTypedAutomationAction,
        legacySuppressionEpoch: String? = null,
    ): SelectedAutomationAction {
        val mapping = map(stored, legacySuppressionEpoch)
        return SelectedAutomationAction(
            entryId = stored.entryId,
            executionIdentity = stored.executionIdentity,
            actionKind = mapping.actionKind,
            scope = mapping.scope,
            policyVersion = POLICY_VERSION,
            baselineFingerprint = fingerprint(mapping.baseline),
        )
    }

    fun createObservationGap(
        entryId: Long,
        actionKind: AutomationActionKind,
        scopeKind: AutomationIsolationScopeKind,
        scopeKey: String,
        baseline: String,
    ): SelectedAutomationAction {
        val baselineFingerprint = fingerprint(baseline)
        val identityMaterial = "gap|$entryId|$actionKind|$scopeKind|$scopeKey|$baselineFingerprint"
        return SelectedAutomationAction(
            entryId = entryId,
            executionIdentity = "observation-gap:${fingerprint(identityMaterial)}",
            actionKind = actionKind,
            scope = scope(scopeKind, scopeKey),
            policyVersion = POLICY_VERSION,
            baselineFingerprint = baselineFingerprint,
            observationOnly = true,
        )
    }

    fun preview(entryId: Long, action: PreparedAutomationAction): ConvergenceSelectionPreview = when (action) {
        is QuestAction.Accept -> preview(
            AutomationActionKind.QUEST_ACCEPT,
            AutomationIsolationScopeKind.QUEST_TARGET,
            action.questKey,
            "quest|accept|${action.questKey}|${action.questCycle}|${action.actionNo}",
        )
        is QuestAction.Claim -> preview(
            AutomationActionKind.QUEST_CLAIM,
            AutomationIsolationScopeKind.QUEST_TARGET,
            action.questKey,
            "quest|claim|${action.questKey}|${action.questCycle}|${action.actionNo}",
        )
        is QuestAction.Battle -> preview(
            AutomationActionKind.QUEST_BATTLE,
            AutomationIsolationScopeKind.QUEST_TARGET,
            action.questKey,
            questBattleBaseline(action.questKey, action.questCycle, action.missionKey, action.missionCurrent, action.missionRequired),
        )
        is HomeQuestAutomationAction -> preview(
            if (action.action == HomeQuestAutomationActionType.ACCEPT) {
                AutomationActionKind.HOME_ACCEPT
            } else {
                AutomationActionKind.HOME_CLAIM
            },
            AutomationIsolationScopeKind.HOME_TARGET,
            action.questId,
            "home|${action.action}|${action.questId}|${action.actionId}",
        )
        is BattleMapAutomationAction -> when (action.source) {
            BattleAutomationActionSource.BATTLE_MAP_AUTOMATION -> preview(
                AutomationActionKind.MAP_BATTLE,
                AutomationIsolationScopeKind.BATTLE_COOLDOWN_SCOPE,
                SHARED_BATTLE_COOLDOWN_SCOPE,
                action.battleBaseline(),
            )
            BattleAutomationActionSource.QUEST_AUTOMATION -> preview(
                AutomationActionKind.QUEST_BATTLE,
                AutomationIsolationScopeKind.QUEST_TARGET,
                action.sourceTargetKey ?: "entry:$entryId:${action.categoryId}:${action.mapCode}",
                action.battleBaseline(),
            )
            BattleAutomationActionSource.ADVENTURE_AUTOMATION -> preview(
                AutomationActionKind.ADVENTURE_BATTLE,
                AutomationIsolationScopeKind.BATTLE_COOLDOWN_SCOPE,
                SHARED_BATTLE_COOLDOWN_SCOPE,
                action.battleBaseline(),
            )
            BattleAutomationActionSource.UNION_AUTOMATION -> preview(
                AutomationActionKind.UNION_BATTLE,
                AutomationIsolationScopeKind.UNION_ENTRY,
                action.sourceTargetKey ?: entryId.toString(),
                action.battleBaseline(),
            )
            BattleAutomationActionSource.FISHING_AUTOMATION -> preview(
                AutomationActionKind.FISHING_OBSTRUCTION_BATTLE,
                AutomationIsolationScopeKind.FISHING_ENTRY,
                entryId.toString(),
                action.battleBaseline(),
            )
            BattleAutomationActionSource.RAID_AUTOMATION -> preview(
                AutomationActionKind.RAID_BATTLE,
                AutomationIsolationScopeKind.RAID_ENTRY,
                action.sourceTargetKey ?: entryId.toString(),
                action.battleBaseline(),
            )
        }
        is AdventureMapAutomationAction -> preview(
            AutomationActionKind.ADVENTURE_BATTLE,
            AutomationIsolationScopeKind.BATTLE_COOLDOWN_SCOPE,
            SHARED_BATTLE_COOLDOWN_SCOPE,
            "adventure|${action.categoryId}|${action.mapCode}|${action.observedCooldownUntil}|" +
                "${action.observedAttemptRemaining}|${action.observedWinRemaining}|${action.observedAvailableCount}",
        )
        is FishingTownAutomationAction -> preview(
            if (action.action == FishingAction.START) {
                AutomationActionKind.FISHING_START
            } else {
                AutomationActionKind.FISHING_CATCH
            },
            AutomationIsolationScopeKind.FISHING_ENTRY,
            entryId.toString(),
            "fishing|${action.progressDate}|${action.action}|${action.observedPrimaryAction}|" +
                "${action.observedRemainingCasts}",
        )
        is RaidTownAutomationAction -> preview(
            action.action.toConvergenceKind(),
            AutomationIsolationScopeKind.RAID_ENTRY,
            action.targetRaidId ?: action.raidId ?: entryId.toString(),
            "raid|${action.action}|${action.targetRaidId}|${action.raidId}",
        )
        is RaidCycleAbortAutomationAction -> preview(
            AutomationActionKind.RAID_CYCLE_ABORT,
            AutomationIsolationScopeKind.RAID_ENTRY,
            action.raidId,
            "raid|abort|${action.raidId}|${action.reason}",
        )
    }

    private fun questBattleBaseline(questKey: String, cycle: String, missionKey: String, current: Int?, required: Int?) =
        "quest|battle|$questKey|$cycle|$missionKey|$current|$required"

    private fun map(
        stored: StoredTypedAutomationAction,
        legacySuppressionEpoch: String?,
    ): Mapping = when (val payload = stored.payload) {
        is StoredTypedActionPayload.QuestAccept -> Mapping(
            AutomationActionKind.QUEST_ACCEPT,
            scope(AutomationIsolationScopeKind.QUEST_TARGET, payload.questKey),
            "quest|accept|${payload.questKey}|${payload.questCycle ?: legacySuppressionEpoch}|${payload.actionNo}",
        )
        is StoredTypedActionPayload.QuestClaim -> Mapping(
            AutomationActionKind.QUEST_CLAIM,
            scope(AutomationIsolationScopeKind.QUEST_TARGET, payload.questKey),
            "quest|claim|${payload.questKey}|${payload.questCycle ?: legacySuppressionEpoch}|${payload.actionNo}",
        )
        is StoredTypedActionPayload.QuestBattle -> Mapping(
            AutomationActionKind.QUEST_BATTLE,
            scope(AutomationIsolationScopeKind.QUEST_TARGET, payload.questKey),
            questBattleBaseline(payload.questKey, payload.questCycle, payload.missionKey, payload.observedCurrent, payload.observedRequired),
        )
        is StoredTypedActionPayload.HomeQuest -> Mapping(
            when (payload.action) {
                HomeQuestAutomationActionType.ACCEPT -> AutomationActionKind.HOME_ACCEPT
                HomeQuestAutomationActionType.CLAIM -> AutomationActionKind.HOME_CLAIM
            },
            scope(AutomationIsolationScopeKind.HOME_TARGET, payload.questId),
            "home|${payload.action}|${payload.questId}|${payload.actionId}",
        )
        is StoredTypedActionPayload.BattleMap -> mapBattle(stored.entryId, payload)
        is StoredTypedActionPayload.AdventureMap -> Mapping(
            AutomationActionKind.ADVENTURE_BATTLE,
            scope(
                AutomationIsolationScopeKind.BATTLE_COOLDOWN_SCOPE,
                SHARED_BATTLE_COOLDOWN_SCOPE,
            ),
            "adventure|${payload.categoryId}|${payload.mapCode}|${payload.observedCooldownUntil}|" +
                "${payload.observedAttemptRemaining}|${payload.observedWinRemaining}|${payload.observedAvailableCount}",
        )
        is StoredTypedActionPayload.FishingTown -> Mapping(
            when (payload.action) {
                FishingAction.START -> AutomationActionKind.FISHING_START
                FishingAction.CATCH -> AutomationActionKind.FISHING_CATCH
                else -> error("Unsupported stored fishing action ${payload.action}.")
            },
            scope(AutomationIsolationScopeKind.FISHING_ENTRY, stored.entryId.toString()),
            "fishing|${payload.progressDate ?: legacySuppressionEpoch}|${payload.action}|${payload.observedPrimaryAction}|" +
                "${payload.observedRemainingCasts}",
        )
        is StoredTypedActionPayload.RaidTown -> Mapping(
            payload.action.toConvergenceKind(),
            scope(
                AutomationIsolationScopeKind.RAID_ENTRY,
                payload.targetRaidId ?: payload.raidId ?: stored.entryId.toString(),
            ),
            "raid|${payload.action}|${payload.targetRaidId}|${payload.raidId}",
        )
        is StoredTypedActionPayload.RaidCycleAbort -> Mapping(
            AutomationActionKind.RAID_CYCLE_ABORT,
            scope(AutomationIsolationScopeKind.RAID_ENTRY, payload.raidId),
            "raid|abort|${payload.raidId}|${payload.reason}",
        )
    }

    private fun mapBattle(
        entryId: Long,
        payload: StoredTypedActionPayload.BattleMap,
    ): Mapping = when (payload.source) {
        BattleAutomationActionSource.BATTLE_MAP_AUTOMATION -> Mapping(
            AutomationActionKind.MAP_BATTLE,
            scope(
                AutomationIsolationScopeKind.BATTLE_COOLDOWN_SCOPE,
                SHARED_BATTLE_COOLDOWN_SCOPE,
            ),
            payload.battleBaseline(),
        )
        BattleAutomationActionSource.QUEST_AUTOMATION -> Mapping(
            AutomationActionKind.QUEST_BATTLE,
            scope(
                AutomationIsolationScopeKind.QUEST_TARGET,
                payload.sourceTargetKey ?: "entry:$entryId:${payload.categoryId}:${payload.mapCode}",
            ),
            payload.battleBaseline(),
        )
        BattleAutomationActionSource.ADVENTURE_AUTOMATION -> Mapping(
            AutomationActionKind.ADVENTURE_BATTLE,
            scope(
                AutomationIsolationScopeKind.BATTLE_COOLDOWN_SCOPE,
                SHARED_BATTLE_COOLDOWN_SCOPE,
            ),
            payload.battleBaseline(),
        )
        BattleAutomationActionSource.UNION_AUTOMATION -> Mapping(
            AutomationActionKind.UNION_BATTLE,
            scope(
                AutomationIsolationScopeKind.UNION_ENTRY,
                payload.sourceTargetKey ?: entryId.toString(),
            ),
            payload.battleBaseline(),
        )
        BattleAutomationActionSource.FISHING_AUTOMATION -> Mapping(
            AutomationActionKind.FISHING_OBSTRUCTION_BATTLE,
            scope(AutomationIsolationScopeKind.FISHING_ENTRY, entryId.toString()),
            payload.battleBaseline(),
        )
        BattleAutomationActionSource.RAID_AUTOMATION -> Mapping(
            AutomationActionKind.RAID_BATTLE,
            scope(
                AutomationIsolationScopeKind.RAID_ENTRY,
                payload.sourceTargetKey ?: entryId.toString(),
            ),
            payload.battleBaseline(),
        )
    }

    private fun StoredTypedActionPayload.BattleMap.battleBaseline(): String =
        "battle|$source|$categoryId|$mapCode|$battleCount|$progressDate|$sourceTargetKey|$recoveryChainId|$raidRetransmissionCount"

    private fun BattleMapAutomationAction.battleBaseline(): String =
        "battle|$source|$categoryId|$mapCode|$battleCount|$progressDate|$sourceTargetKey|$recoveryChainId|$raidRetransmissionCount"

    private fun RaidAction.toConvergenceKind(): AutomationActionKind = when (this) {
        RaidAction.RESET -> AutomationActionKind.RAID_RESET
        RaidAction.REGISTER -> AutomationActionKind.RAID_REGISTER
        RaidAction.START -> AutomationActionKind.RAID_START
        RaidAction.REWARD -> AutomationActionKind.RAID_REWARD
        RaidAction.REFRESH -> AutomationActionKind.RAID_REFRESH
        RaidAction.LEAVE,
        RaidAction.WAIT_RESET,
        -> error("Unsupported stored raid action $this.")
    }

    private fun scope(kind: AutomationIsolationScopeKind, key: String) = AutomationIsolationScope(kind, key)

    private fun preview(
        actionKind: AutomationActionKind,
        scopeKind: AutomationIsolationScopeKind,
        scopeKey: String,
        baseline: String,
    ) = ConvergenceSelectionPreview(
        actionKind,
        scope(scopeKind, scopeKey),
        fingerprint(baseline),
    )

    private fun fingerprint(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private fun canonicalRaidState(state: RaidAuthoritativeState): String {
        val target = state.target
        return encodeCanonicalFields(
            "raid-authoritative-v1",
            state.raidId,
            target?.status?.name,
            target?.joined?.toString(),
            target?.playable?.toString(),
            target?.waitSeconds?.toString(),
            target?.actions?.map(RaidIntentKind::name)?.sorted()?.let(::encodeCanonicalFields),
            target?.battleAvailability?.name,
            target?.battleCategoryId,
            target?.battleMapCode,
            target?.battleCooldownRemainingSeconds?.toString(),
            target?.rewardWindow?.kind?.name,
            target?.rewardWindow?.remainingSeconds?.toString(),
            state.registrationWait.toString(),
            state.registrationWaitSeconds?.toString(),
            encodeCanonicalFields(state.globalActions.map(RaidIntentKind::name).sorted()),
        )
    }

    private fun encodeCanonicalFields(values: Iterable<String?>): String =
        values.joinToString(separator = "") { value ->
            value?.let { "${it.length}:$it" } ?: "-1:"
        }

    private fun encodeCanonicalFields(vararg values: String?): String = encodeCanonicalFields(values.asIterable())

    private data class Mapping(
        val actionKind: AutomationActionKind,
        val scope: AutomationIsolationScope,
        val baseline: String,
    )

    companion object {
        const val POLICY_VERSION = "automation-action-convergence-v1"
        const val SHARED_BATTLE_COOLDOWN_SCOPE = "shared-battle-cooldown"
    }
}

data class ConvergenceSelectionPreview(
    val actionKind: AutomationActionKind,
    val scope: AutomationIsolationScope,
    val baselineFingerprint: String? = null,
)
