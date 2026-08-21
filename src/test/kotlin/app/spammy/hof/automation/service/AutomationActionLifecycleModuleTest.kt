package app.spammy.hof.automation.service

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleRunService
import app.spammy.hof.battle.service.SharedBattleCooldownRejectedException
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.town.home.dto.HomeQuestResponse
import app.spammy.hof.town.home.dto.HomeResponse
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.model.HomeQuestState
import app.spammy.hof.town.home.service.HomeService
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper

class AutomationActionLifecycleModuleTest {
    private val now = Instant.parse("2026-08-21T00:00:00Z")
    private val accountService = Mockito.mock(HofAccountService::class.java)
    private val home = Mockito.mock(HomeService::class.java)
    private val questGateway = Mockito.mock(QuestGatewayService::class.java)
    private val questHandler = Mockito.mock(QuestAutomationHandler::class.java)
    private val battleRun = Mockito.mock(BattleRunService::class.java)
    private val battleOutcome = Mockito.mock(BattleOutcomeReconciler::class.java)
    private val executionSignals = Mockito.mock(AutomationExecutionSignals::class.java)
    private val workOwnership = Mockito.mock(AutomationWorkOwnership::class.java)
    private val sessionRecovery = HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService))
    private val battleSubmission = AutomationBattleSubmission(battleRun, sessionRecovery, battleOutcome)
    private val module: AutomationActionLifecycleModule = UnifiedAutomationActionLifecycleModule(
        codec = StoredTypedAutomationActionCodec(jacksonObjectMapper()),
        workOwnership = workOwnership,
        homeService = home,
        questGateway = questGateway,
        questHandler = questHandler,
        battleSubmission = battleSubmission,
        executionSignals = executionSignals,
        sessionRecovery = sessionRecovery,
        timeProvider = TimeProvider { now },
    )

    @Test
    fun `자택 퀘스트 수락을 저장하고 같은 descriptor로 정확히 한 번 실행한다`() {
        val prepared = HomeQuestAutomationAction(
            accountId = 7L,
            questId = "home-1",
            questName = "빗자루 제작",
            actionId = "accept-action",
            action = HomeQuestAutomationActionType.ACCEPT,
        )

        val managed = assertNotNull(module.prepare(7L, 12L, prepared))
        val payload = assertIs<StoredTypedActionPayload.HomeQuest>(managed.storedAction.payload)

        assertEquals("home-1", payload.questId)
        assertEquals("accept-action", payload.actionId)
        assertEquals(HomeQuestAutomationActionType.ACCEPT, payload.action)
        assertEquals(AutomationType.HOME_QUEST, managed.descriptor.source)
        assertEquals("HOME_ACCEPT", managed.descriptor.actionKind)
        assertEquals("자택 퀘스트 수락 · 빗자루 제작", managed.descriptor.context)
        assertEquals("home-1", managed.descriptor.targetKey)
        assertEquals("빗자루 제작", managed.descriptor.targetName)
        assertEquals(managed.descriptor, module.describe(prepared))
        Mockito.verify(workOwnership).ensure(
            7L,
            12L,
            AutomationWorkAssignment(AutomationWorkType.HOME_QUEST, "home-1"),
        )

        assertEquals(TypedAutomationExecution.Completed, managed.execute())
        Mockito.verify(home, Mockito.times(1)).runHomeQuest(7L, "accept-action")
    }

    @Test
    fun `권위 있는 자택 퀘스트 상태로 적용 재제출 재확인을 구분한다`() {
        val accept = assertNotNull(module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.ACCEPT)))
        Mockito.`when`(home.load(7L, HomeMode.HOME)).thenReturn(
            homeResponse(HomeQuestState.ACTIVE, null),
            homeResponse(HomeQuestState.AVAILABLE, "action-1"),
            homeResponse(HomeQuestState.AVAILABLE, "changed-action"),
        )

        assertIs<AmbiguousActionResolution.Applied>(accept.reconcile())
        assertIs<AmbiguousActionResolution.Resubmit>(accept.reconcile())
        val verifyLater = assertIs<AmbiguousActionResolution.VerifyLater>(accept.reconcile())
        assertEquals(now.plusSeconds(10), verifyLater.retryAt)

        val claim = assertNotNull(module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.CLAIM)))
        Mockito.`when`(home.load(7L, HomeMode.HOME)).thenReturn(
            HomeResponse(HomeMode.HOME, emptyList(), emptyList(), null, null),
        )
        assertIs<AmbiguousActionResolution.Applied>(claim.reconcile())
        assertEquals(TypedAutomationExecution.Completed, claim.execute())
        Mockito.verify(home).runHomeQuest(7L, "action-1")
    }

    @Test
    fun `자택 퀘스트 제출 결과가 불명확하면 즉시 재실행하지 않고 조정 대상으로 남긴다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.ACCEPT)),
        )
        Mockito.doThrow(IllegalStateException("connection closed", IOException("connection closed")))
            .`when`(home).runHomeQuest(7L, "action-1")

        assertFailsWith<AmbiguousAutomationSubmissionException> { managed.execute() }
        Mockito.verify(home, Mockito.times(1)).runHomeQuest(7L, "action-1")
    }

    @Test
    fun `일반 퀘스트 수락을 저장하고 같은 descriptor와 작업 귀속을 사용한다`() {
        val action = QuestAction.Accept("quest-1", "accept-1", "첫 번째 퀘스트")

        val managed = assertNotNull(module.prepare(7L, 12L, action))

        assertEquals(
            StoredTypedActionPayload.QuestAccept(
                "quest-1",
                "accept-1",
                StoredActionDisplay(questName = "첫 번째 퀘스트"),
            ),
            managed.storedAction.payload,
        )
        assertEquals(AutomationType.QUEST, managed.descriptor.source)
        assertEquals("QUEST_ACCEPT", managed.descriptor.actionKind)
        assertEquals("퀘스트 수락 · 첫 번째 퀘스트", managed.descriptor.context)
        assertEquals(managed.descriptor, module.describe(action))
        Mockito.verify(workOwnership).ensure(
            7L,
            12L,
            AutomationWorkAssignment(AutomationWorkType.QUEST, "quest-1"),
        )
        assertEquals(TypedAutomationExecution.Completed, managed.execute())
        Mockito.verify(questGateway).accept(7L, "accept-1", HofRequestOrigin.AUTOMATION)
        Mockito.verify(questHandler).onAcceptSucceeded(
            7L,
            managed.storedAction.executionIdentity,
            QuestAction.Accept("quest-1", "accept-1"),
        )
    }

    @Test
    fun `일반 퀘스트 보상 수령을 저장하고 정확히 한 번 실행한다`() {
        val action = QuestAction.Claim("quest-1", "claim-1", "첫 번째 퀘스트")

        val managed = assertNotNull(module.prepare(7L, 12L, action))

        assertEquals(
            StoredTypedActionPayload.QuestClaim(
                "quest-1",
                "claim-1",
                StoredActionDisplay(questName = "첫 번째 퀘스트"),
            ),
            managed.storedAction.payload,
        )
        assertEquals("QUEST_CLAIM", managed.descriptor.actionKind)
        assertEquals("퀘스트 보상 수령 · 첫 번째 퀘스트", managed.descriptor.context)
        assertEquals(TypedAutomationExecution.Completed, managed.execute())
        Mockito.verify(questGateway).claim(7L, "claim-1", HofRequestOrigin.AUTOMATION)
    }

    @Test
    fun `일반 퀘스트 보상 결과를 권위 상태로 조정한다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, QuestAction.Claim("quest-1", "claim-1")),
        )
        Mockito.`when`(questGateway.load(7L, HofRequestOrigin.AUTOMATION)).thenReturn(
            emptyList(),
            listOf(quest(QuestState.CLAIMABLE, "claim-1")),
            listOf(quest(QuestState.ACTIVE, null)),
        )

        assertIs<AmbiguousActionResolution.Applied>(managed.reconcile())
        assertIs<AmbiguousActionResolution.Resubmit>(managed.reconcile())
        val verifyLater = assertIs<AmbiguousActionResolution.VerifyLater>(managed.reconcile())
        assertEquals(now.plusSeconds(10), verifyLater.retryAt)
    }

    @Test
    fun `일반 퀘스트 수락 결과를 권위 상태로 조정한다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, QuestAction.Accept("quest-1", "accept-1")),
        )
        Mockito.`when`(questGateway.load(7L, HofRequestOrigin.AUTOMATION)).thenReturn(
            listOf(quest(QuestState.ACTIVE, null)),
            listOf(quest(QuestState.AVAILABLE, "accept-1")),
            listOf(quest(QuestState.UNAVAILABLE, null)),
        )

        assertIs<AmbiguousActionResolution.Applied>(managed.reconcile())
        Mockito.verify(questHandler).onAcceptSucceeded(
            7L,
            managed.storedAction.executionIdentity,
            QuestAction.Accept("quest-1", "accept-1"),
        )
        assertIs<AmbiguousActionResolution.Resubmit>(managed.reconcile())
        assertIs<AmbiguousActionResolution.VerifyLater>(managed.reconcile())
    }

    @Test
    fun `일반 퀘스트 전송 결과가 불명확하면 같은 행동을 즉시 반복하지 않는다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, QuestAction.Claim("quest-1", "claim-1")),
        )
        Mockito.`when`(questGateway.claim(7L, "claim-1", HofRequestOrigin.AUTOMATION))
            .thenThrow(ApiException(ErrorCode.HOF_REQUEST_FAILED, "upstream result unknown"))

        assertFailsWith<AmbiguousAutomationSubmissionException> { managed.execute() }

        Mockito.verify(questGateway, Mockito.times(1))
            .claim(7L, "claim-1", HofRequestOrigin.AUTOMATION)
    }

    @Test
    fun `만료된 세션은 복구한 뒤 준비된 퀘스트 행동만 한 번 다시 제출한다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, QuestAction.Accept("quest-1", "accept-1")),
        )
        Mockito.`when`(questGateway.accept(7L, "accept-1", HofRequestOrigin.AUTOMATION))
            .thenThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
            .thenReturn(emptyList())

        assertEquals(TypedAutomationExecution.Completed, managed.execute())

        Mockito.verify(accountService).reauthenticate(7L, HofRequestOrigin.AUTOMATION)
        Mockito.verify(questGateway, Mockito.times(2))
            .accept(7L, "accept-1", HofRequestOrigin.AUTOMATION)
    }

    @Test
    fun `퀘스트 전투는 요청한 모든 회차의 단말 결과를 증명한 뒤 진행을 반영한다`() {
        val request = battleRequest(3)
        val action = QuestAction.Battle(
            questKey = "quest-1",
            questCycle = "2",
            missionKey = "kill-slime",
            missionType = QuestMissionType.MONSTER_KILL,
            categoryId = request.categoryId,
            mapCode = request.mapCode,
            mapName = "슬라임 동굴",
            preset = QuestPresetSelection(PresetSelectionMode.PRIMARY, 301L),
            battleCount = 3,
            resolvedParty = ResolvedAutomationParty(request.characterIds, request.patternLoads),
            questName = "첫 번째 퀘스트",
            missionLabel = "몬스터 처치 · 슬라임",
            missionCurrent = 2,
            missionRequired = 5,
        )
        val managed = assertNotNull(module.prepare(7L, 12L, action))
        val result = Mockito.mock(app.spammy.hof.battle.dto.BattleResultResponse::class.java)
        val rounds = listOf("VICTORY", "DEFEAT", "VICTORY").map { outcome ->
            Mockito.mock(app.spammy.hof.battle.dto.BattleRoundResponse::class.java).also { round ->
                Mockito.`when`(round.outcome).thenReturn(outcome)
                Mockito.`when`(round.loots).thenReturn(emptyList())
                Mockito.`when`(round.quest).thenReturn(null)
            }
        }
        Mockito.`when`(result.rounds).thenReturn(rounds)
        Mockito.`when`(battleRun.runBattle(7L, request, HofRequestOrigin.AUTOMATION)).thenReturn(result)

        assertEquals(
            TypedAutomationExecution.BattleCompleted("battle_map", "map-1"),
            managed.execute(),
        )

        val payload = assertIs<StoredTypedActionPayload.QuestBattle>(managed.storedAction.payload)
        assertEquals(request, payload.battleRequest)
        assertEquals("QUEST_BATTLE", managed.descriptor.actionKind)
        assertEquals(3, managed.descriptor.battleCount)
        assertEquals(managed.descriptor, module.describe(action))
        Mockito.verify(workOwnership).ensure(
            7L,
            12L,
            AutomationWorkAssignment(
                type = AutomationWorkType.QUEST,
                targetKey = "quest-1",
                questCycle = "2",
                missionKey = "kill-slime",
                missionType = QuestMissionType.MONSTER_KILL.name,
                observedCurrent = 2,
                observedRequired = 5,
            ),
        )
        Mockito.verify(questHandler).onBattleCompleted(
            7L,
            managed.storedAction.executionIdentity,
            QuestAction.Battle(
                "quest-1",
                "2",
                "kill-slime",
                QuestMissionType.MONSTER_KILL,
                "battle_map",
                "map-1",
                "map-1",
                QuestPresetSelection(PresetSelectionMode.PRIMARY, 301L),
                3,
            ),
            listOf(
                BattleAutomationRoundOutcome.VICTORY,
                BattleAutomationRoundOutcome.DEFEAT,
                BattleAutomationRoundOutcome.VICTORY,
            ),
        )
    }

    @Test
    fun `불명확한 퀘스트 전투는 권위 mission 진행으로 적용 여부를 조정한다`() {
        val request = battleRequest()
        val managed = assertNotNull(
            module.prepare(
                7L,
                12L,
                QuestAction.Battle(
                    questKey = "quest-1",
                    questCycle = "2",
                    missionKey = "kill-slime",
                    missionType = QuestMissionType.MONSTER_KILL,
                    categoryId = request.categoryId,
                    mapCode = request.mapCode,
                    mapName = "슬라임 동굴",
                    preset = QuestPresetSelection(PresetSelectionMode.PRIMARY, 301L),
                    battleCount = 1,
                    resolvedParty = ResolvedAutomationParty(request.characterIds, request.patternLoads),
                    missionCurrent = 2,
                    missionRequired = 5,
                ),
            ),
        )
        Mockito.`when`(questGateway.load(7L, HofRequestOrigin.AUTOMATION)).thenReturn(
            listOf(activeQuest(progress = 3)),
            listOf(activeQuest(progress = 2)),
            listOf(activeQuest(progress = null)),
        )

        val applied = assertIs<AmbiguousActionResolution.Applied>(managed.reconcile())
        assertEquals(
            TypedAutomationExecution.BattleCompleted("battle_map", "map-1"),
            applied.execution,
        )
        val unchanged = assertIs<AmbiguousActionResolution.VerifyLater>(managed.reconcile())
        assertEquals(now.plusSeconds(10), unchanged.retryAt)
        val verifyLater = assertIs<AmbiguousActionResolution.VerifyLater>(managed.reconcile())
        assertEquals(now.plusSeconds(10), verifyLater.retryAt)
    }

    @Test
    fun `일부 전투 회차만 단말 상태이면 퀘스트 진행을 기록하지 않는다`() {
        val request = battleRequest(3)
        val managed = assertNotNull(module.prepare(7L, 12L, questBattleAction(request, 3)))
        val result = Mockito.mock(app.spammy.hof.battle.dto.BattleResultResponse::class.java)
        val round = Mockito.mock(app.spammy.hof.battle.dto.BattleRoundResponse::class.java)
        Mockito.`when`(round.outcome).thenReturn("VICTORY")
        Mockito.`when`(result.rounds).thenReturn(listOf(round))
        Mockito.`when`(battleRun.runBattle(7L, request, HofRequestOrigin.AUTOMATION)).thenReturn(result)
        Mockito.`when`(battleOutcome.reloadRecentAuthoritativeEvidence(anyBattleAction()))
            .thenReturn(BattleOutcomeReconciliation.Unproven("terminal proof missing"))

        assertFailsWith<AmbiguousAutomationSubmissionException> { managed.execute() }

        Mockito.verifyNoInteractions(questHandler, executionSignals)
    }

    @Test
    fun `퀘스트 전투 shared cooldown은 진행 없이 정확한 다음 시각을 반환한다`() {
        val request = battleRequest()
        val retryAt = now.plusSeconds(45)
        val managed = assertNotNull(module.prepare(7L, 12L, questBattleAction(request, 1)))
        Mockito.`when`(battleRun.runBattle(7L, request, HofRequestOrigin.AUTOMATION))
            .thenThrow(SharedBattleCooldownRejectedException(retryAt))

        assertEquals(
            TypedAutomationExecution.SharedCooldown("battle_map", "map-1", retryAt),
            managed.execute(),
        )
        Mockito.verifyNoInteractions(questHandler, executionSignals)
    }

    @Test
    fun `기존 일반 퀘스트 저장 action은 fingerprint와 identity를 검증해 복원한다`() {
        val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())
        val payloads = listOf<StoredTypedActionPayload>(
            StoredTypedActionPayload.QuestAccept("quest-1", "accept-1"),
            StoredTypedActionPayload.QuestClaim("quest-1", "claim-1"),
            StoredTypedActionPayload.QuestBattle(
                questKey = "quest-1",
                questCycle = "2",
                missionKey = "kill-slime",
                missionType = QuestMissionType.MONSTER_KILL,
                categoryId = "battle_map",
                mapCode = "map-1",
                presetMode = PresetSelectionMode.PRIMARY,
                presetId = 301L,
                battleCount = 1,
                battleRequest = battleRequest(),
                observedCurrent = 2,
                observedRequired = 5,
            ),
        )

        payloads.forEachIndexed { index, payload ->
            val stored = StoredTypedAutomationAction(12L, "quest-execution-$index", payload)
            val row = actionRow(codec, stored, 80L + index)
            val restored = assertNotNull(module.restore(row, 7L))

            assertEquals(stored, restored.storedAction)
            assertEquals(payload.kind(), restored.descriptor.storageKind)
        }
    }

    @Test
    fun `아직 이전하지 않은 행동군은 기존 경로를 위해 처리하지 않는다`() {
        val action = BattleMapAutomationAction(
            accountId = 7L,
            progressDate = java.time.LocalDate.parse("2026-08-21"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = app.spammy.hof.automation.entity.PresetSelectionMode.PRIMARY,
            presetId = 1L,
            battleCount = 1,
            executionIdentity = "battle-1",
        )

        assertNull(module.prepare(7L, 12L, action))
    }

    @Test
    fun `기존 자택 퀘스트 저장 action은 무결성을 검증한 뒤 복원한다`() {
        val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())
        val stored = StoredTypedAutomationAction(
            12L,
            "legacy-home-execution",
            StoredTypedActionPayload.HomeQuest(
                "home-1",
                "action-1",
                HomeQuestAutomationActionType.CLAIM,
            ),
        )
        val encoded = codec.encode(stored)
        val account = HofAccountEntity(7L, "login", "encrypted", now)
        val entry = AutomationEntryEntity(12L, account, AutomationType.HOME_QUEST, 0, true, now, now)
        val row = TypedAutomationActionRunEntity(
            88L,
            account,
            entry,
            stored.executionIdentity,
            "HOME_QUEST",
            encoded.json,
            encoded.fingerprint,
            TypedAutomationActionStatus.PREPARED,
            leaseToken = "token",
            createdAt = now,
            updatedAt = now,
        )

        val restored = assertNotNull(module.restore(row, 7L))

        assertEquals(stored, restored.storedAction)
        assertEquals("HOME_CLAIM", restored.descriptor.actionKind)
        val tampered = TypedAutomationActionRunEntity(
            89L,
            account,
            entry,
            stored.executionIdentity,
            "HOME_QUEST",
            encoded.json,
            "0".repeat(64),
            TypedAutomationActionStatus.PREPARED,
            leaseToken = "token",
            createdAt = now,
            updatedAt = now,
        )
        assertFailsWith<IllegalArgumentException> {
            module.restore(tampered, 7L)
        }
    }

    private fun homeAction(type: HomeQuestAutomationActionType) = HomeQuestAutomationAction(
        accountId = 7L,
        questId = "home-1",
        questName = "빗자루 제작",
        actionId = "action-1",
        action = type,
    )

    private fun homeResponse(state: HomeQuestState, actionId: String?) = HomeResponse(
        mode = HomeMode.HOME,
        quests = listOf(
            HomeQuestResponse("home-1", "빗자루 제작", state, null, null, emptyList(), actionId),
        ),
        actions = emptyList(),
        restStatus = null,
        result = null,
    )

    private fun quest(state: QuestState, actionNo: String?) = QuestSnapshot(
        questKey = "quest-1",
        name = "첫 번째 퀘스트",
        state = state,
        progress = null,
        actionNo = actionNo,
    )

    private fun activeQuest(progress: Int?) = QuestSnapshot(
        questKey = "quest-1",
        name = "첫 번째 퀘스트",
        state = QuestState.ACTIVE,
        section = app.spammy.hof.quest.model.QuestSection.ACTIVE,
        sourceOrder = 0,
        missions = listOf(
            QuestMission(
                key = "kill-slime",
                type = QuestMissionType.MONSTER_KILL,
                target = "slime",
                progress = progress?.let { QuestProgress(it, 5) },
                completable = false,
            ),
        ),
        actionNo = null,
    )

    private fun battleRequest(count: Int = 1) = RunBattleRequest(
        categoryId = "battle_map",
        mapCode = "map-1",
        characterIds = listOf("character-1"),
        patternLoads = listOf(BattlePatternLoadRequest("character-1", 1)),
        battleCount = count,
    )

    private fun questBattleAction(request: RunBattleRequest, count: Int) = QuestAction.Battle(
        questKey = "quest-1",
        questCycle = "2",
        missionKey = "kill-slime",
        missionType = QuestMissionType.MONSTER_KILL,
        categoryId = request.categoryId,
        mapCode = request.mapCode,
        mapName = "슬라임 동굴",
        preset = QuestPresetSelection(PresetSelectionMode.PRIMARY, 301L),
        battleCount = count,
        resolvedParty = ResolvedAutomationParty(request.characterIds, request.patternLoads),
        missionCurrent = 2,
        missionRequired = 5,
    )

    private fun anyBattleAction(): BattleMapAutomationAction =
        Mockito.any(BattleMapAutomationAction::class.java) ?: BattleMapAutomationAction(
            accountId = 7L,
            progressDate = java.time.LocalDate.parse("2026-08-21"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.EXPLICIT,
            presetId = null,
            battleCount = 3,
            executionIdentity = "matcher",
            source = BattleAutomationActionSource.QUEST_AUTOMATION,
        )

    private fun actionRow(
        codec: StoredTypedAutomationActionCodec,
        stored: StoredTypedAutomationAction,
        rowId: Long,
    ): TypedAutomationActionRunEntity {
        val encoded = codec.encode(stored)
        val account = HofAccountEntity(7L, "login", "encrypted", now)
        val entry = AutomationEntryEntity(12L, account, AutomationType.QUEST, 0, true, now, now)
        return TypedAutomationActionRunEntity(
            rowId,
            account,
            entry,
            stored.executionIdentity,
            stored.payload.kind(),
            encoded.json,
            encoded.fingerprint,
            TypedAutomationActionStatus.PREPARED,
            leaseToken = "token",
            createdAt = now,
            updatedAt = now,
        )
    }
}
