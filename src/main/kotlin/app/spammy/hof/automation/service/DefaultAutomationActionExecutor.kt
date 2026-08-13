package app.spammy.hof.automation.service

import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleRunService
import app.spammy.hof.battle.service.SharedBattleCooldownRejectedException
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.town.fishing.service.FishingService
import app.spammy.hof.town.home.service.HomeService
import app.spammy.hof.town.raid.dto.RaidPubActionRequest
import app.spammy.hof.town.raid.dto.RaidPubResponse
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.service.RaidPubService
import java.io.IOException
import org.springframework.stereotype.Service

@Service
class DefaultAutomationActionExecutor(
    private val questGatewayService: QuestGatewayService,
    private val battleRunService: BattleRunService,
    private val questHandler: QuestAutomationHandler,
    private val battleHandler: BattleMapAutomationHandler,
    private val battleOutcomeReconciler: BattleOutcomeReconciler,
    private val sessionRecovery: HofSessionRecoveryExecutor,
    private val executionSignals: AutomationExecutionSignals,
    private val workLifecycle: AutomationWorkLifecycle,
    private val fishingService: FishingService? = null,
    private val raidPubService: RaidPubService? = null,
    private val contentProgress: AutomationContentProgressService? = null,
    private val homeService: HomeService? = null,
) : TypedAutomationActionExecutor {
    override fun execute(accountId: Long, action: StoredTypedAutomationAction): TypedAutomationExecution =
        try {
            when (val payload = action.payload) {
                is StoredTypedActionPayload.QuestClaim -> {
                    runQuestMutation(accountId) {
                        questGatewayService.claim(accountId, payload.actionNo, HofRequestOrigin.AUTOMATION)
                    }
                    TypedAutomationExecution.Completed
                }
                is StoredTypedActionPayload.QuestAccept -> {
                    runQuestMutation(accountId) {
                        questGatewayService.accept(accountId, payload.actionNo, HofRequestOrigin.AUTOMATION)
                    }
                    questHandler.onAcceptSucceeded(accountId, action.executionIdentity, QuestAction.Accept(payload.questKey, payload.actionNo))
                    TypedAutomationExecution.Completed
                }
                is StoredTypedActionPayload.HomeQuest -> {
                    runQuestMutation(accountId) {
                        (homeService ?: error("Home quest automation gateway is unavailable."))
                            .runHomeQuest(accountId, payload.actionId)
                    }
                    TypedAutomationExecution.Completed
                }
                is StoredTypedActionPayload.QuestBattle -> {
                    val result = runTypedBattle(accountId, payload.battleRequest)
                    val proof = exactTerminalProof(accountId, action.executionIdentity, payload.battleRequest, result, BattleAutomationActionSource.QUEST_AUTOMATION)
                    val questAction = QuestAction.Battle(
                        payload.questKey, payload.questCycle, payload.missionKey, payload.missionType,
                        payload.categoryId, payload.mapCode, payload.mapCode,
                        QuestPresetSelection(payload.presetMode, payload.presetId), payload.battleCount,
                    )
                    questHandler.onBattleCompleted(
                        accountId,
                        proof.resultIdentity,
                        questAction,
                        proof.outcomes,
                    )
                    val signalRounds = result.rounds.takeIf(List<*>::isNotEmpty)
                    executionSignals.afterBattle(
                        accountId = accountId,
                        source = BattleAutomationActionSource.QUEST_AUTOMATION,
                        outcomes = proof.outcomes,
                        lootNames = signalRounds?.flatMap { it.loots.map { loot -> loot.name } }
                            ?: result.loots.map { it.name },
                        questTexts = signalRounds?.mapNotNull { it.quest?.takeIf(String::isNotBlank) }
                            ?: listOfNotNull(result.quest?.takeIf(String::isNotBlank)),
                    )
                    TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode)
                }
                is StoredTypedActionPayload.BattleMap -> {
                    val result = runTypedBattle(accountId, payload.battleRequest)
                    val prepared = BattleMapAutomationAction(
                        accountId, payload.progressDate, payload.categoryId, payload.mapCode, payload.presetMode,
                        payload.presetId, payload.battleCount, action.executionIdentity, payload.source,
                    )
                    val outcomes = result.rounds.map { round ->
                        runCatching { BattleAutomationRoundOutcome.valueOf(round.outcome) }.getOrDefault(BattleAutomationRoundOutcome.UNKNOWN)
                    }
                    if (payload.source == BattleAutomationActionSource.BATTLE_MAP_AUTOMATION) {
                        val resolution = battleHandler.onBattleCompleted(
                            prepared, payload.source, action.executionIdentity, outcomes, battleOutcomeReconciler,
                        )
                        if (resolution is BattleOutcomeResolution.Fatal) throw AmbiguousAutomationSubmissionException(resolution.evaluation.message)
                        workLifecycle.completeBattleMapAction(accountId, action.entryId, payload.categoryId, payload.mapCode)
                    } else {
                        exactTerminalProof(accountId, action.executionIdentity, payload.battleRequest, result, payload.source)
                        if (payload.source == BattleAutomationActionSource.UNION_AUTOMATION) {
                            contentProgress?.unionBattleCompleted(accountId, action.entryId, payload.categoryId, payload.mapCode)
                        }
                    }
                    val signalRounds = result.rounds.takeIf(List<*>::isNotEmpty)
                    executionSignals.afterBattle(
                        accountId = accountId,
                        source = BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
                        outcomes = outcomes,
                        lootNames = signalRounds?.flatMap { it.loots.map { loot -> loot.name } }
                            ?: result.loots.map { it.name },
                        questTexts = signalRounds?.mapNotNull { it.quest?.takeIf(String::isNotBlank) }
                            ?: listOfNotNull(result.quest?.takeIf(String::isNotBlank)),
                    )
                    TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode)
                }
                is StoredTypedActionPayload.AdventureMap -> {
                    val result = runTypedBattle(accountId, payload.battleRequest)
                    exactTerminalProof(accountId, action.executionIdentity, payload.battleRequest, result, BattleAutomationActionSource.ADVENTURE_AUTOMATION)
                    workLifecycle.completeAdventureAction(
                        accountId,
                        action.entryId,
                        payload.categoryId,
                        payload.mapCode,
                    )
                    TypedAutomationExecution.BattleCompleted(payload.categoryId, payload.mapCode)
                }
                is StoredTypedActionPayload.FishingTown -> {
                    (fishingService ?: error("Fishing automation gateway is unavailable.")).act(accountId, payload.action)
                    TypedAutomationExecution.Completed
                }
                is StoredTypedActionPayload.RaidTown -> {
                    val response = (raidPubService ?: error("Raid automation gateway is unavailable."))
                        .action(accountId, RaidPubActionRequest(payload.action, payload.raidId))
                    when (payload.action) {
                        app.spammy.hof.town.raid.model.RaidAction.REGISTER -> {
                            val id = requireNotNull(payload.raidId)
                            val raid = response.raids.singleOrNull { it.id == id }
                                ?: error("Registered raid is missing from the response.")
                            (contentProgress ?: error("Raid cycle service is unavailable."))
                                .raidRegistered(accountId, action.entryId, id, raid.name, raid.waitSeconds ?: response.applyWaitSeconds)
                        }
                        app.spammy.hof.town.raid.model.RaidAction.START ->
                            (contentProgress ?: error("Raid cycle service is unavailable.")).raidStarted(accountId, requireNotNull(payload.raidId))
                        app.spammy.hof.town.raid.model.RaidAction.REWARD ->
                            (contentProgress ?: error("Raid cycle service is unavailable.")).raidRewarded(accountId)
                        app.spammy.hof.town.raid.model.RaidAction.REFRESH ->
                            (contentProgress ?: error("Raid cycle service is unavailable.")).raidStatusRefreshed(accountId)
                        app.spammy.hof.town.raid.model.RaidAction.RESET -> {
                            val raidId = requireNotNull(payload.raidId)
                            if (!response.provesSuccessfulReset(raidId)) {
                                throw AmbiguousAutomationSubmissionException(
                                    "Raid reset response did not prove that registration became available.",
                                )
                            }
                            (contentProgress ?: error("Raid cycle service is unavailable."))
                                .raidReset(accountId, raidId)
                            workLifecycle.completeRaidCycle(accountId, action.entryId)
                        }
                        else -> Unit
                    }
                    TypedAutomationExecution.Completed
                }
                is StoredTypedActionPayload.RaidCycleAbort -> {
                    (contentProgress ?: error("Raid cycle service is unavailable.")).raidClosed(accountId, payload.raidId)
                    workLifecycle.completeRaidCycle(accountId, action.entryId)
                    TypedAutomationExecution.Completed
                }
            }
        } catch (cooldown: SharedBattleCooldownRejectedException) {
            val request = action.payload.battleRequestOrNull()
                ?: throw IllegalStateException("Non-battle action returned a battle cooldown.", cooldown)
            TypedAutomationExecution.SharedCooldown(request.categoryId, request.mapCode, cooldown.retryAt)
        }

    private fun <T> runQuestMutation(accountId: Long, operation: () -> T): T =
        try {
            sessionRecovery.execute(accountId, operation)
        } catch (error: Throwable) {
            val causes = generateSequence(error) { it.cause }.toList()
            causes.filterIsInstance<HofAutomationDeferredException>().firstOrNull()?.let { throw it }
            if (causes.any { it is AutomationLoginRequiredException }) throw error
            causes.filterIsInstance<ApiException>().firstOrNull()?.let { api ->
                if (api.errorCode in setOf(
                        ErrorCode.HOF_SESSION_EXPIRED,
                        ErrorCode.HOF_LOGIN_FAILED,
                        ErrorCode.CAPTCHA_REQUIRED,
                    )
                ) {
                    throw error
                }
                if (api.errorCode == ErrorCode.HOF_REQUEST_FAILED) {
                    throw AmbiguousAutomationSubmissionException(
                        "Quest side-effect request outcome is not provable; it will not be resent.",
                        error,
                    )
                }
            }
            if (causes.any { it is IOException }) {
                throw AmbiguousAutomationSubmissionException(
                    "Quest side-effect request outcome is not provable; it will not be resent.",
                    error,
                )
            }
            throw error
        }

    private fun runTypedBattle(accountId: Long, request: RunBattleRequest) =
        sessionRecovery.execute(accountId) {
            runTypedBattleOnce(accountId, request)
        }

    private fun runTypedBattleOnce(accountId: Long, request: RunBattleRequest) =
        try {
            battleRunService.runBattle(accountId, request, HofRequestOrigin.AUTOMATION)
        } catch (error: Exception) {
            generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<SharedBattleCooldownRejectedException>()
                .firstOrNull()
                ?.let { throw it }
            generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<HofAutomationDeferredException>()
                .firstOrNull()
                ?.let { throw it }
            error.findApiException()?.let { api ->
                if (api.errorCode in setOf(
                        ErrorCode.HOF_SESSION_EXPIRED,
                        ErrorCode.HOF_LOGIN_FAILED,
                        ErrorCode.CAPTCHA_REQUIRED,
                    )
                ) {
                    throw api
                }
            }
            throw AmbiguousAutomationSubmissionException("Battle submission outcome is not provable; it will not be resent.", error)
        }

    private fun Throwable.findApiException(): ApiException? =
        generateSequence(this) { it.cause }.filterIsInstance<ApiException>().firstOrNull()

    private fun RaidPubResponse.provesSuccessfulReset(raidId: String): Boolean {
        val resultText = result?.messages.orEmpty().joinToString(" ")
        if (RAID_RESET_SUCCEEDED.containsMatchIn(resultText)) return true
        val raid = raids.singleOrNull { it.id == raidId } ?: return false
        return !raid.joined && RaidAction.REGISTER in raid.actions
    }

    private fun StoredTypedActionPayload.battleRequestOrNull(): RunBattleRequest? = when (this) {
        is StoredTypedActionPayload.QuestBattle -> battleRequest
        is StoredTypedActionPayload.BattleMap -> battleRequest
        is StoredTypedActionPayload.AdventureMap -> battleRequest
        is StoredTypedActionPayload.QuestClaim,
        is StoredTypedActionPayload.QuestAccept,
        is StoredTypedActionPayload.HomeQuest,
        is StoredTypedActionPayload.FishingTown,
        is StoredTypedActionPayload.RaidTown,
        is StoredTypedActionPayload.RaidCycleAbort,
        -> null
    }

    private fun exactTerminalProof(
        accountId: Long,
        executionIdentity: String,
        request: RunBattleRequest,
        result: app.spammy.hof.battle.dto.BattleResultResponse,
        source: BattleAutomationActionSource,
    ): TerminalProof {
        val outcomes = result.rounds.mapNotNull { runCatching { BattleAutomationRoundOutcome.valueOf(it.outcome) }.getOrNull() }
        if (outcomes.size == request.resolvedBattleCount() && outcomes.all {
                it == BattleAutomationRoundOutcome.VICTORY || it == BattleAutomationRoundOutcome.DEFEAT || it == BattleAutomationRoundOutcome.DRAW
            }) return TerminalProof(executionIdentity, outcomes)
        val probe = BattleMapAutomationAction(
            accountId, java.time.LocalDate.now(), request.categoryId, request.mapCode,
            app.spammy.hof.automation.entity.PresetSelectionMode.EXPLICIT, null,
            request.resolvedBattleCount(), executionIdentity, source,
        )
        return when (val reconciliation = battleOutcomeReconciler.reloadRecentAuthoritativeEvidence(probe)) {
            is BattleOutcomeReconciliation.Proven -> reconciliation.evidence.takeIf { it.binds(probe) && it.isCompleteTerminal() }
                ?.let { TerminalProof(it.resultIdentity, it.outcomes) }
                ?: throw AmbiguousAutomationSubmissionException("Reloaded battle evidence did not bind the submitted action.")
            else -> throw AmbiguousAutomationSubmissionException("Battle response did not prove every requested terminal round.")
        }
    }

    private data class TerminalProof(val resultIdentity: String, val outcomes: List<BattleAutomationRoundOutcome>)

    private companion object {
        val RAID_RESET_SUCCEEDED = Regex("전투가\\s*신청\\s*가능\\s*상태로\\s*바뀌었습니다\\.?")
    }

}
