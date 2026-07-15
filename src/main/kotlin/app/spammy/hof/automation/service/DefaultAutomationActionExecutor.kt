package app.spammy.hof.automation.service

import app.spammy.hof.automation.policy.AutomationDecision
import app.spammy.hof.automation.policy.AutomationDecisionType
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleRunService
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.quest.service.QuestGatewayService
import java.time.Instant
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class DefaultAutomationActionExecutor(
    private val questGatewayService: QuestGatewayService,
    private val battleRunService: BattleRunService,
    private val characterQueryRepository: CharacterQueryRepository,
    private val partyPresetQueryRepository: PartyPresetQueryRepository,
    private val objectMapper: ObjectMapper,
    private val sessionRecoveryExecutor: HofSessionRecoveryExecutor,
    private val questHandler: QuestAutomationHandler? = null,
    private val battleHandler: BattleMapAutomationHandler? = null,
    private val battleOutcomeReconciler: BattleOutcomeReconciler? = null,
) : AutomationActionExecutor, TypedAutomationActionExecutor {
    /** 프리셋과 열쇠 퀘스트 blueprint를 실제 캐릭터 ID·패턴 슬롯 요청으로 해석한다. */
    override fun prepare(
        accountId: Long,
        decision: AutomationDecision,
    ): AutomationExecutionPayload = when (decision.type) {
        AutomationDecisionType.ACCEPT_QUEST,
        AutomationDecisionType.CLAIM_QUEST,
        -> AutomationExecutionPayload(
            decision = decision,
            resolvedActionNo = requireActionNo(decision),
        )
        AutomationDecisionType.RUN_BATTLE -> AutomationExecutionPayload(
            decision = decision,
            resolvedBattleRequest = battleRequest(accountId, decision),
        )
        AutomationDecisionType.WAITING_CONFIG,
        AutomationDecisionType.SLEEP,
        -> throw IllegalArgumentException("${decision.type} 결정은 외부 action으로 준비할 수 없습니다.")
    }

    /** 준비 단계에서 저장한 exact request만 사용하며 프리셋·캐릭터 저장소를 다시 조회하지 않는다. */
    override fun execute(
        accountId: Long,
        payload: AutomationExecutionPayload,
    ): String = sessionRecoveryExecutor.execute(accountId) { when (payload.decision.type) {
        AutomationDecisionType.ACCEPT_QUEST -> objectMapper.writeValueAsString(
            questGatewayService.accept(accountId, requirePreparedActionNo(payload)),
        )
        AutomationDecisionType.CLAIM_QUEST -> objectMapper.writeValueAsString(
            questGatewayService.claim(accountId, requirePreparedActionNo(payload)),
        )
        AutomationDecisionType.RUN_BATTLE -> objectMapper.writeValueAsString(
            battleRunService.runBattle(
                accountId,
                payload.resolvedBattleRequest
                    ?: throw AutomationConfigurationException("저장된 전투 실행 정보가 없습니다."),
            ),
        )
        AutomationDecisionType.WAITING_CONFIG,
        AutomationDecisionType.SLEEP,
        -> throw IllegalArgumentException("${payload.decision.type} 결정은 외부 action으로 실행할 수 없습니다.")
    } }

    override fun execute(accountId: Long, action: StoredTypedAutomationActionV1) {
        // The durable runner has already marked this action SUBMITTING. Session recovery may repeat its lambda,
        // which is unsafe for a POST whose outcome is ambiguous, so typed actions are deliberately invoked once.
        when (val payload = action.payload) {
                is StoredTypedActionPayload.QuestClaim -> questGatewayService.claim(accountId, payload.actionNo)
                is StoredTypedActionPayload.QuestAccept -> {
                    questGatewayService.accept(accountId, payload.actionNo)
                    questHandler?.onAcceptSucceeded(accountId, action.executionIdentity, QuestAction.Accept(payload.questCode, payload.actionNo))
                }
                is StoredTypedActionPayload.QuestBattle -> {
                    val result = runTypedBattle(accountId, payload.battleRequest)
                    val proof = exactTerminalProof(accountId, action.executionIdentity, payload.battleRequest, result, BattleAutomationActionSource.QUEST_AUTOMATION)
                    val questAction = QuestAction.Battle(
                        payload.questCode, payload.questCycle, payload.missionKey, payload.missionType,
                        payload.categoryId, payload.mapCode, payload.mapCode,
                        QuestPresetSelection(payload.presetMode, payload.presetId), payload.battleCount,
                    )
                    questHandler?.onBattleCompleted(
                        accountId, proof.resultIdentity, questAction,
                        if (proof.outcomes.single() == BattleAutomationRoundOutcome.VICTORY) QuestBattleOutcome.VICTORY else QuestBattleOutcome.DEFEAT,
                    )
                }
                is StoredTypedActionPayload.BattleMap -> {
                    val result = runTypedBattle(accountId, payload.battleRequest)
                    val prepared = BattleMapAutomationAction(
                        accountId, payload.progressDate, payload.categoryId, payload.mapCode, payload.presetMode,
                        payload.presetId, payload.battleCount, action.executionIdentity,
                    )
                    val outcomes = result.rounds.map { round ->
                        runCatching { BattleAutomationRoundOutcome.valueOf(round.outcome) }.getOrDefault(BattleAutomationRoundOutcome.UNKNOWN)
                    }
                    val resolution = battleHandler?.onBattleCompleted(
                        prepared, BattleAutomationActionSource.BATTLE_MAP_AUTOMATION, action.executionIdentity,
                        outcomes, battleOutcomeReconciler ?: ConservativeBattleOutcomeReconciler(),
                    )
                    if (resolution is BattleOutcomeResolution.Fatal) throw AmbiguousAutomationSubmissionException(resolution.evaluation.message)
                }
                is StoredTypedActionPayload.AdventureMap -> {
                    val result = runTypedBattle(accountId, payload.battleRequest)
                    exactTerminalProof(accountId, action.executionIdentity, payload.battleRequest, result, BattleAutomationActionSource.ADVENTURE_AUTOMATION)
                }
        }
    }

    private fun runTypedBattle(accountId: Long, request: RunBattleRequest) =
        try {
            battleRunService.runBattle(accountId, request)
        } catch (error: Exception) {
            throw AmbiguousAutomationSubmissionException("Battle submission outcome is not provable; it will not be resent.", error)
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
        return when (val reconciliation = battleOutcomeReconciler?.reloadRecentAuthoritativeEvidence(probe)) {
            is BattleOutcomeReconciliation.Proven -> reconciliation.evidence.takeIf { it.binds(probe) && it.isCompleteTerminal() }
                ?.let { TerminalProof(it.resultIdentity, it.outcomes) }
                ?: throw AmbiguousAutomationSubmissionException("Reloaded battle evidence did not bind the submitted action.")
            else -> throw AmbiguousAutomationSubmissionException("Battle response did not prove every requested terminal round.")
        }
    }

    private data class TerminalProof(val resultIdentity: String, val outcomes: List<BattleAutomationRoundOutcome>)

    private fun requireActionNo(decision: AutomationDecision): String = decision.actionNo
        ?: throw AutomationConfigurationException("퀘스트 처리 링크를 다시 불러와 주세요.")

    private fun requirePreparedActionNo(payload: AutomationExecutionPayload): String = payload.resolvedActionNo
        ?: throw AutomationConfigurationException("저장된 퀘스트 처리 정보가 없습니다.")

    private fun battleRequest(
        accountId: Long,
        decision: AutomationDecision,
    ): RunBattleRequest {
        val map = decision.map ?: throw AutomationConfigurationException()
        val keyBattle = decision.keyQuestBattle
        val party = when {
            keyBattle?.blueprint != null -> {
                val charactersByName = characterQueryRepository.findAllByAccountId(accountId).associateBy { it.name }
                val slots = keyBattle.blueprint.slots.map { slot ->
                    val character = charactersByName[slot.characterName]
                        ?: throw AutomationConfigurationException("${slot.characterName} 캐릭터 설정을 확인해 주세요.")
                    character.hofCharacterId to slot.patternSlot
                }
                ResolvedParty(slots, keyBattle.blueprint.battleCount)
            }
            map.partyPresetId != null -> resolvePreset(accountId, map.partyPresetId)
            else -> throw AutomationConfigurationException()
        }
        return RunBattleRequest(
            categoryId = map.categoryId,
            mapCode = map.mapCode,
            characterIds = party.slots.map { it.first },
            patternLoads = party.slots.map { (characterId, patternSlot) ->
                BattlePatternLoadRequest(characterId, patternSlot)
            },
            battleCount = party.battleCount,
        )
    }

    private fun resolvePreset(
        accountId: Long,
        presetId: Long,
    ): ResolvedParty {
        partyPresetQueryRepository.findOwnedByAccountIdAndId(accountId, presetId)
            ?: throw AutomationConfigurationException()
        val slots = partyPresetQueryRepository.findMembersByPresetIds(listOf(presetId))
            .mapNotNull { member ->
                val character = member.character ?: return@mapNotNull null
                val pattern = member.patternSlot?.slotCode?.toIntOrNull()
                    ?: throw AutomationConfigurationException("파티의 저장 패턴을 다시 선택해 주세요.")
                if (member.patternSlot?.canLoad != true) {
                    throw AutomationConfigurationException("파티의 저장 패턴을 다시 선택해 주세요.")
                }
                character.hofCharacterId to pattern
            }
        if (slots.isEmpty()) throw AutomationConfigurationException()
        return ResolvedParty(slots, 1)
    }

    private data class ResolvedParty(
        val slots: List<Pair<String, Int>>,
        val battleCount: Int,
    )
}
