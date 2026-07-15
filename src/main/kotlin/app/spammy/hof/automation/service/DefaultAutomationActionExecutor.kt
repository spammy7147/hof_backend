package app.spammy.hof.automation.service

import app.spammy.hof.automation.policy.AutomationDecision
import app.spammy.hof.automation.policy.AutomationDecisionType
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleRunService
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.quest.service.QuestGatewayService
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
) : AutomationActionExecutor {
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
