package app.spammy.hof.automation.service

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.policy.AutomationDecision
import app.spammy.hof.automation.policy.AutomationDecisionType
import app.spammy.hof.automation.policy.AutomationMapCandidate
import app.spammy.hof.automation.policy.KeyQuestMapCandidate
import app.spammy.hof.automation.policy.PartyBlueprint
import app.spammy.hof.automation.policy.PartyBlueprintSlot
import app.spammy.hof.automation.policy.QuestDecision
import app.spammy.hof.battle.service.BattleRunService
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.quest.service.QuestGatewayService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper

class DefaultAutomationActionExecutorTest {
    private val questGatewayService = Mockito.mock(QuestGatewayService::class.java)
    private val battleRunService = Mockito.mock(BattleRunService::class.java)
    private val characterQueryRepository = Mockito.mock(CharacterQueryRepository::class.java)
    private val partyPresetQueryRepository = Mockito.mock(PartyPresetQueryRepository::class.java)
    private val sessionRecoveryExecutor = HofSessionRecoveryExecutor(
        HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java)),
    )
    private val executor = DefaultAutomationActionExecutor(
        questGatewayService,
        battleRunService,
        characterQueryRepository,
        partyPresetQueryRepository,
        jacksonObjectMapper(),
        sessionRecoveryExecutor,
    )

    @Test
    fun `prepare resolves preset characters and patterns before the action is checkpointed`() {
        val now = Instant.parse("2026-07-14T00:00:00Z")
        val account = HofAccountEntity(7L, "login", "encrypted", now)
        val preset = PartyPresetEntity(301L, account, "원본 파티", now, now)
        val character = CharacterEntity(
            id = 401L,
            account = account,
            hofCharacterId = "original-character",
            name = "기사",
            job = "Knight",
            level = 60,
            updatedAt = now,
        )
        val pattern = CharacterPatternSlotEntity(501L, character, "2", "패턴 2", true)
        val member = PartyPresetMemberEntity(preset, 0, character, pattern)
        val decision = battleDecision(now)
        Mockito.`when`(partyPresetQueryRepository.findOwnedByAccountIdAndId(7L, 301L)).thenReturn(preset)
        Mockito.`when`(partyPresetQueryRepository.findMembersByPresetIds(listOf(301L))).thenReturn(listOf(member))

        val prepared = executor.prepare(7L, decision)
        character.hofCharacterId = "changed-character"
        pattern.slotCode = "4"

        assertEquals(listOf("original-character"), prepared.resolvedBattleRequest?.characterIds)
        assertEquals(2, prepared.resolvedBattleRequest?.patternLoads?.single()?.slot)
    }

    @Test
    fun `prepare resolves a key quest blueprint to exact character ids and battle count`() {
        val now = Instant.parse("2026-07-14T00:00:00Z")
        val account = HofAccountEntity(7L, "login", "encrypted", now)
        val character = CharacterEntity(
            id = 401L,
            account = account,
            hofCharacterId = "bard-id",
            name = "바드",
            job = "Bard",
            level = 60,
            updatedAt = now,
        )
        val map = KeyQuestMapCandidate("Noble102", "저택", null, 0, null, "adventure_map")
        val keyBattle = QuestDecision.Battle(
            questId = "0563",
            map = map,
            blueprint = PartyBlueprint(listOf(PartyBlueprintSlot("바드", 4)), battleCount = 3),
        )
        val decision = AutomationDecision(
            type = AutomationDecisionType.RUN_BATTLE,
            moduleType = AutomationModuleType.KEY_QUEST,
            moduleConfigId = 20L,
            moduleRevision = now,
            questId = "0563",
            map = AutomationMapCandidate("Noble102", "저택", 0, null, "adventure_map"),
            keyQuestBattle = keyBattle,
        )
        Mockito.`when`(characterQueryRepository.findAllByAccountId(7L)).thenReturn(listOf(character))

        val prepared = executor.prepare(7L, decision)

        assertEquals(listOf("bard-id"), prepared.resolvedBattleRequest?.characterIds)
        assertEquals(4, prepared.resolvedBattleRequest?.patternLoads?.single()?.slot)
        assertEquals(3, prepared.resolvedBattleRequest?.battleCount)
    }

    @Test
    fun `execute uses the battle request fixed during prepare even when preset data changes`() {
        val decision = battleDecision(Instant.parse("2026-07-14T00:00:00Z"))
        val prepared = AutomationExecutionPayload(
            decision = decision,
            resolvedBattleRequest = app.spammy.hof.battle.dto.RunBattleRequest(
                categoryId = "battle_map",
                mapCode = "gb0",
                characterIds = listOf("original-character"),
                patternLoads = listOf(
                    app.spammy.hof.battle.dto.BattlePatternLoadRequest("original-character", 2),
                ),
                battleCount = 3,
            ),
        )
        val preparedRequest = requireNotNull(prepared.resolvedBattleRequest)
        Mockito.`when`(battleRunService.runBattle(7L, preparedRequest))
            .thenReturn(Mockito.mock(app.spammy.hof.battle.dto.BattleResultResponse::class.java))

        executor.execute(7L, prepared)

        Mockito.verify(battleRunService).runBattle(7L, preparedRequest)
        Mockito.verifyNoInteractions(partyPresetQueryRepository, characterQueryRepository)
        assertEquals("original-character", prepared.resolvedBattleRequest.characterIds.single())
        assertEquals(2, prepared.resolvedBattleRequest.patternLoads.single().slot)
        assertEquals(3, prepared.resolvedBattleRequest.battleCount)
    }

    private fun battleDecision(revision: Instant) = AutomationDecision(
        type = AutomationDecisionType.RUN_BATTLE,
        moduleType = AutomationModuleType.TIME_BURN,
        moduleConfigId = 10L,
        moduleRevision = revision,
        map = AutomationMapCandidate("gb0", "고블린", 0, 301L),
    )
}
