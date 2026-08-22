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
import app.spammy.hof.automation.raid.HofRaidObservationAdapter
import app.spammy.hof.automation.raid.RaidAttempt
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidObservation
import app.spammy.hof.automation.raid.RaidRecordResult
import app.spammy.hof.automation.raid.RaidResultObservation
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.BattleMapResponse
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.battle.service.BattleRunService
import app.spammy.hof.battle.service.SharedBattleCooldownRejectedException
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.battle.service.CurrentBattleMapObservation
import app.spammy.hof.battle.service.CurrentBattleMapObservationStatus
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
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingOutcome
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.fishing.service.FishingService
import app.spammy.hof.town.raid.dto.RaidPubActionRequest
import app.spammy.hof.town.raid.dto.RaidPubResponse
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.service.RaidActionPreconditionChangedException
import app.spammy.hof.town.raid.service.RaidPubService
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
    private val questWorkCycle = Mockito.mock(QuestWorkCycleModule::class.java) { invocation ->
        if (invocation.method.name == "recordObservedResult") QuestRecordResult.Recorded()
        else Mockito.RETURNS_DEFAULTS.answer(invocation)
    }
    private val fishingService = Mockito.mock(FishingService::class.java)
    private val battleRun = Mockito.mock(BattleRunService::class.java)
    private val battleOutcome = Mockito.mock(BattleOutcomeReconciler::class.java)
    private val battleHandler = Mockito.mock(BattleMapAutomationHandler::class.java)
    private val battleMapService = Mockito.mock(BattleMapService::class.java)
    private val executionSignals = Mockito.mock(AutomationExecutionSignals::class.java)
    private val workOwnership = Mockito.mock(AutomationWorkOwnership::class.java)
    private val workLifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
    private val unionProgress = Mockito.mock(UnionAutomationProgressService::class.java)
    private val raidPubService = Mockito.mock(RaidPubService::class.java)
    private val raidCycleModule = Mockito.mock(RaidCycleModule::class.java)
    private val raidObservationAdapter = Mockito.mock(HofRaidObservationAdapter::class.java)
    private val sessionRecovery = HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService))
    private val battleSubmission = AutomationBattleSubmission(battleRun, sessionRecovery, battleOutcome)
    private val module: AutomationActionLifecycleModule = UnifiedAutomationActionLifecycleModule(
        codec = StoredTypedAutomationActionCodec(jacksonObjectMapper()),
        workOwnership = workOwnership,
        homeService = home,
        questGateway = questGateway,
        questWorkCycle = questWorkCycle,
        battleSubmission = battleSubmission,
        battleHandler = battleHandler,
        battleOutcomeReconciler = battleOutcome,
        battleMapService = battleMapService,
        workLifecycle = workLifecycle,
        unionProgress = unionProgress,
        fishingService = fishingService,
        raidPubService = raidPubService,
        raidCycleModule = raidCycleModule,
        raidObservationAdapter = raidObservationAdapter,
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

        Mockito.`when`(home.runHomeQuest(7L, "accept-action"))
            .thenReturn(homeResponse(HomeQuestState.ACTIVE, null))
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
    fun `자택 직접 응답이 행동별 후속 상태를 증명하지 못하면 완료하지 않는다`() {
        val managed = assertNotNull(module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.ACCEPT)))
        Mockito.`when`(home.runHomeQuest(7L, "action-1"))
            .thenReturn(homeResponse(HomeQuestState.AVAILABLE, "action-1"))

        assertFailsWith<AmbiguousAutomationSubmissionException> { managed.execute() }
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
        Mockito.`when`(home.runHomeQuest(7L, "action-1")).thenReturn(
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
        val observed = listOf(quest(QuestState.ACTIVE, null))
        Mockito.`when`(questGateway.accept(7L, "accept-1", HofRequestOrigin.AUTOMATION))
            .thenReturn(observed)

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
        Mockito.verify(questWorkCycle).recordObservedResult(
            7L,
            QuestAttempt.Accept(managed.storedAction.executionIdentity, "quest-1", "accept-1"),
            QuestResultObservation.Page(observed),
        )
    }

    @Test
    fun `일반 퀘스트 보상 수령을 저장하고 정확히 한 번 실행한다`() {
        val action = QuestAction.Claim("quest-1", "claim-1", "첫 번째 퀘스트")
        val observed = emptyList<QuestSnapshot>()
        Mockito.`when`(questGateway.claim(7L, "claim-1", HofRequestOrigin.AUTOMATION))
            .thenReturn(observed)

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
        Mockito.verify(questWorkCycle).recordObservedResult(
            7L,
            QuestAttempt.Claim(managed.storedAction.executionIdentity, "quest-1", "claim-1"),
            QuestResultObservation.Page(observed),
        )
    }

    @Test
    fun `일반 퀘스트 응답이 적용을 증명하지 못하면 완료하지 않고 조정으로 전환한다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, QuestAction.Accept("quest-1", "accept-1")),
        )
        val observed = listOf(quest(QuestState.AVAILABLE, "accept-1"))
        val attempt = QuestAttempt.Accept(
            managed.storedAction.executionIdentity,
            "quest-1",
            "accept-1",
        )
        Mockito.`when`(questGateway.accept(7L, "accept-1", HofRequestOrigin.AUTOMATION))
            .thenReturn(observed)
        Mockito.`when`(
            questWorkCycle.recordObservedResult(
                7L,
                attempt,
                QuestResultObservation.Page(observed),
            ),
        ).thenReturn(QuestRecordResult.NotApplied("still available"))

        assertFailsWith<AmbiguousAutomationSubmissionException> { managed.execute() }

        Mockito.verify(questGateway, Mockito.times(1))
            .accept(7L, "accept-1", HofRequestOrigin.AUTOMATION)
    }

    @Test
    fun `퀘스트 원격 적용 뒤 로컬 cycle 기록 실패는 저장 action 조정으로 전환한다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, QuestAction.Accept("quest-1", "accept-1")),
        )
        val observed = listOf(quest(QuestState.ACTIVE, null))
        val attempt = QuestAttempt.Accept(
            managed.storedAction.executionIdentity,
            "quest-1",
            "accept-1",
        )
        Mockito.`when`(questGateway.accept(7L, "accept-1", HofRequestOrigin.AUTOMATION))
            .thenReturn(observed)
        Mockito.`when`(
            questWorkCycle.recordObservedResult(
                7L,
                attempt,
                QuestResultObservation.Page(observed),
            ),
        ).thenThrow(IllegalStateException("cycle store unavailable"))

        assertFailsWith<AmbiguousAutomationSubmissionException> { managed.execute() }

        Mockito.verify(questGateway, Mockito.times(1))
            .accept(7L, "accept-1", HofRequestOrigin.AUTOMATION)
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
        Mockito.doReturn(
            QuestRecordResult.Recorded(),
            QuestRecordResult.NotApplied("still claimable"),
            QuestRecordResult.NeedsRecheck("not authoritative"),
        ).`when`(questWorkCycle).recordObservedResult(Mockito.eq(7L), anyQuestAttempt(), anyQuestObservation())

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
        Mockito.doReturn(
            QuestRecordResult.Recorded("1"),
            QuestRecordResult.NotApplied("still available"),
            QuestRecordResult.NeedsRecheck("not authoritative"),
        ).`when`(questWorkCycle).recordObservedResult(Mockito.eq(7L), anyQuestAttempt(), anyQuestObservation())

        assertIs<AmbiguousActionResolution.Applied>(managed.reconcile())
        Mockito.verify(questWorkCycle).recordObservedResult(
            7L,
            QuestAttempt.Accept(managed.storedAction.executionIdentity, "quest-1", "accept-1"),
            QuestResultObservation.Page(listOf(quest(QuestState.ACTIVE, null))),
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
            AutomationWorkAssignment(AutomationWorkType.QUEST, "quest-1"),
        )
        Mockito.verify(questWorkCycle).recordObservedResult(
            7L,
            QuestAttempt.Battle(
                managed.storedAction.executionIdentity,
                QuestAction.Battle(
                    "quest-1",
                    "2",
                    "kill-slime",
                    QuestMissionType.MONSTER_KILL,
                    "battle_map",
                    "map-1",
                    "슬라임 동굴",
                    QuestPresetSelection(PresetSelectionMode.PRIMARY, 301L),
                    3,
                    questName = "첫 번째 퀘스트",
                    missionLabel = "몬스터 처치 · 슬라임",
                    missionCurrent = 2,
                    missionRequired = 5,
                ),
            ),
            QuestResultObservation.BattleRounds(
                listOf(
                    BattleAutomationRoundOutcome.VICTORY,
                    BattleAutomationRoundOutcome.DEFEAT,
                    BattleAutomationRoundOutcome.VICTORY,
                ),
            ),
        )
    }

    @Test
    fun `불명확한 퀘스트 전투는 권위 단말 결과를 같은 작업 사이클 모듈에 기록한다`() {
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
        val evidence = BattleAuthoritativeOutcomeEvidence(
            accountId = 7L,
            executionIdentity = managed.storedAction.executionIdentity,
            categoryId = "battle_map",
            mapCode = "map-1",
            battleCount = 1,
            resultIdentity = "quest-result-1",
            outcomes = listOf(BattleAutomationRoundOutcome.VICTORY),
        )
        Mockito.`when`(battleOutcome.reloadRecentAuthoritativeEvidence(anyBattleAction())).thenReturn(
            BattleOutcomeReconciliation.Proven(evidence),
            BattleOutcomeReconciliation.Unproven("terminal proof missing"),
            BattleOutcomeReconciliation.Proven(evidence.copy(mapCode = "other-map")),
        )
        val fallbackPage = listOf(activeQuest(progress = 3))
        Mockito.`when`(questGateway.load(7L, HofRequestOrigin.AUTOMATION)).thenReturn(fallbackPage)

        val applied = assertIs<AmbiguousActionResolution.Applied>(managed.reconcile())
        assertEquals(
            TypedAutomationExecution.BattleCompleted("battle_map", "map-1"),
            applied.execution,
        )
        Mockito.verify(questWorkCycle).recordObservedResult(
            7L,
            QuestAttempt.Battle(
                managed.storedAction.executionIdentity,
                QuestAction.Battle(
                    questKey = "quest-1",
                    questCycle = "2",
                    missionKey = "kill-slime",
                    missionType = QuestMissionType.MONSTER_KILL,
                    categoryId = "battle_map",
                    mapCode = "map-1",
                    mapName = "슬라임 동굴",
                    preset = QuestPresetSelection(PresetSelectionMode.PRIMARY, 301L),
                    battleCount = 1,
                    missionCurrent = 2,
                    missionRequired = 5,
                ),
            ),
            QuestResultObservation.BattleRounds(listOf(BattleAutomationRoundOutcome.VICTORY)),
        )
        assertIs<AmbiguousActionResolution.Applied>(managed.reconcile())
        Mockito.verify(questWorkCycle).recordObservedResult(
            7L,
            QuestAttempt.Battle(
                managed.storedAction.executionIdentity,
                QuestAction.Battle(
                    questKey = "quest-1",
                    questCycle = "2",
                    missionKey = "kill-slime",
                    missionType = QuestMissionType.MONSTER_KILL,
                    categoryId = "battle_map",
                    mapCode = "map-1",
                    mapName = "슬라임 동굴",
                    preset = QuestPresetSelection(PresetSelectionMode.PRIMARY, 301L),
                    battleCount = 1,
                    missionCurrent = 2,
                    missionRequired = 5,
                ),
            ),
            QuestResultObservation.Page(fallbackPage),
        )
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

        Mockito.verifyNoInteractions(questWorkCycle, executionSignals)
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
        Mockito.verifyNoInteractions(questWorkCycle, executionSignals)
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
    fun `일반 전투맵은 준비된 identity와 단말 결과로 진행과 작업을 한 번 완료한다`() {
        val request = battleRequest(3)
        val action = BattleMapAutomationAction(
            accountId = 7L,
            progressDate = java.time.LocalDate.parse("2026-08-21"),
            categoryId = request.categoryId,
            mapCode = request.mapCode,
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 301L,
            battleCount = 3,
            executionIdentity = "battle-execution-1",
            resolvedParty = ResolvedAutomationParty(request.characterIds, request.patternLoads),
            mapName = "슬라임 동굴",
        )
        val result = Mockito.mock(app.spammy.hof.battle.dto.BattleResultResponse::class.java)
        val rounds = listOf("VICTORY", "DEFEAT", "DRAW").map { outcome ->
            Mockito.mock(app.spammy.hof.battle.dto.BattleRoundResponse::class.java).also { round ->
                Mockito.`when`(round.outcome).thenReturn(outcome)
                Mockito.`when`(round.loots).thenReturn(emptyList())
                Mockito.`when`(round.quest).thenReturn(null)
            }
        }
        Mockito.`when`(result.rounds).thenReturn(rounds)
        Mockito.`when`(battleRun.runBattle(7L, request, HofRequestOrigin.AUTOMATION)).thenReturn(result)
        val managed = assertNotNull(module.prepare(7L, 12L, action))

        assertEquals("battle-execution-1", managed.storedAction.executionIdentity)
        val payload = assertIs<StoredTypedActionPayload.BattleMap>(managed.storedAction.payload)
        assertEquals(request, payload.battleRequest)
        assertEquals(StoredActionDisplay(mapName = "슬라임 동굴"), payload.display)
        assertEquals(AutomationType.BATTLE_MAP, managed.descriptor.source)
        assertEquals("BATTLE_MAP", managed.descriptor.actionKind)
        assertEquals("일반 전투 · 맵 슬라임 동굴 · 3회 · 파티 1명", managed.descriptor.context)
        assertEquals(managed.descriptor, module.describe(action))
        Mockito.verify(workOwnership).ensure(
            7L,
            12L,
            AutomationWorkAssignment(AutomationWorkType.BATTLE_MAP, "battle_map/map-1"),
        )

        assertEquals(
            TypedAutomationExecution.BattleCompleted("battle_map", "map-1"),
            managed.execute(),
        )
        Mockito.verify(battleHandler).onBattleCompleted(
            action,
            BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
            "battle-execution-1",
            listOf(
                BattleAutomationRoundOutcome.VICTORY,
                BattleAutomationRoundOutcome.DEFEAT,
                BattleAutomationRoundOutcome.DRAW,
            ),
            battleOutcome,
        )
        Mockito.verify(workLifecycle).completeBattleMapAction(7L, 12L, "battle_map", "map-1")
    }

    @Test
    fun `일반 전투 shared cooldown은 진행과 작업 완료 없이 다음 시각만 반환한다`() {
        val action = battleMapAction()
        val retryAt = now.plusSeconds(45)
        Mockito.`when`(battleRun.runBattle(7L, battleRequest(), HofRequestOrigin.AUTOMATION))
            .thenThrow(SharedBattleCooldownRejectedException(retryAt))

        val managed = assertNotNull(module.prepare(7L, 12L, action))

        assertEquals(
            TypedAutomationExecution.SharedCooldown("battle_map", "map-1", retryAt),
            managed.execute(),
        )
        Mockito.verifyNoInteractions(battleHandler, workLifecycle, unionProgress, executionSignals)
    }

    @Test
    fun `불명확한 일반 전투는 일치하는 권위 결과가 없으면 재실행하지 않는다`() {
        val managed = assertNotNull(module.prepare(7L, 12L, battleMapAction()))
        Mockito.`when`(battleOutcome.reloadRecentAuthoritativeEvidence(anyBattleAction()))
            .thenReturn(BattleOutcomeReconciliation.Unproven("아직 결과가 보이지 않습니다."))
        Mockito.`when`(
            battleMapService.observeCurrentlyAvailableMaps(7L, "battle_map", HofRequestOrigin.AUTOMATION),
        ).thenReturn(CurrentBattleMapObservation(CurrentBattleMapObservationStatus.INCOMPLETE, emptyList()))

        val resolution = assertIs<AmbiguousActionResolution.VerifyLater>(managed.reconcile())

        assertEquals(now.plusSeconds(10), resolution.retryAt)
        Mockito.verifyNoInteractions(battleHandler, workLifecycle, unionProgress)
    }

    @Test
    fun `불명확한 일반 전투는 최신 완전 맵에서 대상이 사라지면 결과 id 없이 수렴한다`() {
        val managed = assertNotNull(module.prepare(7L, 12L, battleMapAction()))
        Mockito.`when`(battleOutcome.reloadRecentAuthoritativeEvidence(anyBattleAction()))
            .thenReturn(BattleOutcomeReconciliation.Unproven("결과 식별자가 없습니다."))
        Mockito.`when`(
            battleMapService.observeCurrentlyAvailableMaps(7L, "battle_map", HofRequestOrigin.AUTOMATION),
        ).thenReturn(
            CurrentBattleMapObservation(
                CurrentBattleMapObservationStatus.OBSERVED,
                listOf(mapResponse(null).copy(mapCode = "other-map")),
            ),
        )

        val resolution = assertIs<AmbiguousActionResolution.Applied>(managed.reconcile())

        assertEquals(
            TypedAutomationExecution.BattleCompleted("battle_map", "map-1"),
            resolution.execution,
        )
        Mockito.verify(workLifecycle).completeBattleMapAction(7L, 12L, "battle_map", "map-1")
        Mockito.verifyNoInteractions(battleHandler, unionProgress)
    }

    @Test
    fun `불명확한 유니온 전투는 최신 맵이 권위 있게 사라지면 한 번의 조회로 적용한다`() {
        val action = battleMapAction().copy(
            categoryId = "union",
            source = BattleAutomationActionSource.UNION_AUTOMATION,
            executionIdentity = "union-ambiguous-1",
            mapName = "고블린 침공군",
        )
        val managed = assertNotNull(module.prepare(7L, 13L, action))
        Mockito.`when`(
            battleMapService.observeCurrentlyAvailableMaps(7L, "union", HofRequestOrigin.AUTOMATION),
        ).thenReturn(CurrentBattleMapObservation(CurrentBattleMapObservationStatus.ABSENT, emptyList()))

        val resolution = assertIs<AmbiguousActionResolution.Applied>(managed.reconcile())

        assertEquals(
            TypedAutomationExecution.BattleCompleted("union", "map-1"),
            resolution.execution,
        )
        Mockito.verify(unionProgress).battleCompleted(7L, 13L, "union", "map-1")
        Mockito.verifyNoInteractions(battleOutcome, battleHandler, workLifecycle)
    }

    @Test
    fun `불명확한 유니온 전투의 최신 맵이 그대로면 전투를 다시 보내지 않고 재확인한다`() {
        val action = battleMapAction().copy(
            categoryId = "union",
            source = BattleAutomationActionSource.UNION_AUTOMATION,
            executionIdentity = "union-ambiguous-2",
            mapName = "고블린 침공군",
        )
        val managed = assertNotNull(module.prepare(7L, 13L, action))
        Mockito.`when`(
            battleMapService.observeCurrentlyAvailableMaps(7L, "union", HofRequestOrigin.AUTOMATION),
        ).thenReturn(
            CurrentBattleMapObservation(
                CurrentBattleMapObservationStatus.OBSERVED,
                listOf(mapResponse(attemptCount = null, categoryId = "union")),
            ),
        )

        val resolution = assertIs<AmbiguousActionResolution.VerifyLater>(managed.reconcile())

        assertEquals(now.plusSeconds(10), resolution.retryAt)
        Mockito.verifyNoInteractions(battleOutcome, battleHandler, workLifecycle, unionProgress)
    }

    @Test
    fun `유니온 전투는 단말 결과 뒤 순환만 이동하고 일반 전투 작업은 완료하지 않는다`() {
        val action = battleMapAction().copy(
            source = BattleAutomationActionSource.UNION_AUTOMATION,
            executionIdentity = "union-execution-1",
            mapName = "유니온 초원",
        )
        val result = terminalBattleResult("VICTORY")
        Mockito.`when`(battleRun.runBattle(7L, battleRequest(), HofRequestOrigin.AUTOMATION)).thenReturn(result)

        val managed = assertNotNull(module.prepare(7L, 13L, action))

        assertEquals(AutomationType.UNION, managed.descriptor.source)
        assertEquals(
            TypedAutomationExecution.BattleCompleted("battle_map", "map-1"),
            managed.execute(),
        )
        Mockito.verify(unionProgress).battleCompleted(7L, 13L, "battle_map", "map-1")
        Mockito.verifyNoInteractions(battleHandler, workLifecycle)
    }

    @Test
    fun `모험맵은 준비된 관측값을 저장하고 단말 결과 뒤 한 작업을 완료한다`() {
        val action = adventureAction()
        val result = terminalBattleResult("VICTORY")
        Mockito.`when`(battleRun.runBattle(7L, battleRequest(), HofRequestOrigin.AUTOMATION))
            .thenReturn(result)

        val managed = assertNotNull(module.prepare(7L, 14L, action))
        val payload = assertIs<StoredTypedActionPayload.AdventureMap>(managed.storedAction.payload)

        assertEquals("adventure-execution-1", managed.storedAction.executionIdentity)
        assertEquals(3, payload.observedAttemptRemaining)
        assertEquals(AutomationType.ADVENTURE_MAP, managed.descriptor.source)
        assertEquals("ADVENTURE_MAP", managed.descriptor.actionKind)
        assertEquals(301L, managed.descriptor.presetId)
        Mockito.verify(workOwnership).ensure(
            7L,
            14L,
            AutomationWorkAssignment(AutomationWorkType.ADVENTURE_MAP, "battle_map/map-1"),
        )
        assertEquals(
            TypedAutomationExecution.BattleCompleted("battle_map", "map-1"),
            managed.execute(),
        )
        Mockito.verify(workLifecycle).completeAdventureAction(7L, 14L, "battle_map", "map-1")
    }

    @Test
    fun `불명확한 모험맵은 권위 횟수 감소만 적용하고 기준이 없으면 재실행하지 않는다`() {
        val decreased = assertNotNull(module.prepare(7L, 14L, adventureAction()))
        val noBaseline = assertNotNull(
            module.prepare(
                7L,
                14L,
                adventureAction().copy(
                    executionIdentity = "adventure-execution-2",
                    observedAttemptRemaining = null,
                    observedWinRemaining = null,
                    observedAvailableCount = null,
                ),
            ),
        )
        Mockito.`when`(battleMapService.findMaps(7L, "battle_map", HofRequestOrigin.AUTOMATION))
            .thenReturn(
                listOf(mapResponse(attemptCount = 2)),
                listOf(mapResponse(attemptCount = 3)),
            )
        Mockito.`when`(battleOutcome.reloadRecentAuthoritativeEvidence(anyBattleAction()))
            .thenReturn(BattleOutcomeReconciliation.Unproven("결과 식별자를 찾지 못했습니다."))

        assertIs<AmbiguousActionResolution.Applied>(decreased.reconcile())
        assertIs<AmbiguousActionResolution.VerifyLater>(noBaseline.reconcile())
        Mockito.verify(workLifecycle, Mockito.times(1))
            .completeAdventureAction(7L, 14L, "battle_map", "map-1")
    }

    @Test
    fun `저장된 전투 모험 낚시 레이드 행동은 identity와 fingerprint를 검증해 복원한다`() {
        val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())
        val payloads = listOf<StoredTypedActionPayload>(
            StoredTypedActionPayload.BattleMap(
                progressDate = java.time.LocalDate.parse("2026-08-21"),
                categoryId = "battle_map",
                mapCode = "map-1",
                presetMode = PresetSelectionMode.PRIMARY,
                presetId = 301L,
                battleCount = 1,
                battleRequest = battleRequest(),
            ),
            StoredTypedActionPayload.BattleMap(
                progressDate = java.time.LocalDate.parse("2026-08-21"),
                categoryId = "battle_map",
                mapCode = "map-1",
                presetMode = PresetSelectionMode.PRIMARY,
                presetId = 301L,
                battleCount = 1,
                battleRequest = battleRequest(),
                source = BattleAutomationActionSource.UNION_AUTOMATION,
            ),
            StoredTypedActionPayload.AdventureMap(
                categoryId = "battle_map",
                mapCode = "map-1",
                presetMode = PresetSelectionMode.PRIMARY,
                presetId = 301L,
                battleCount = 1,
                settingIdentity = 99L,
                battleRequest = battleRequest(),
                observedAttemptRemaining = 3,
            ),
            StoredTypedActionPayload.FishingTown(
                FishingAction.CATCH,
                FishingPrimaryAction.CATCH,
                9,
            ),
            StoredTypedActionPayload.BattleMap(
                progressDate = java.time.LocalDate.parse("2026-08-21"),
                categoryId = "battle_map",
                mapCode = "map-1",
                presetMode = PresetSelectionMode.PRIMARY,
                presetId = 301L,
                battleCount = 1,
                battleRequest = battleRequest(),
                source = BattleAutomationActionSource.RAID_AUTOMATION,
                sourceTargetKey = "RaidGoblin",
            ),
            StoredTypedActionPayload.RaidTown(
                RaidAction.START,
                "RaidGoblin",
                "RaidGoblin",
                StoredActionDisplay(mapName = "고블린 레이드"),
            ),
            StoredTypedActionPayload.RaidCycleAbort(
                "RaidGoblin",
                RaidCycleAbortReason.REGISTRATION_LOST,
            ),
        )

        payloads.forEachIndexed { index, payload ->
            val stored = StoredTypedAutomationAction(12L, "map-execution-$index", payload)
            val restored = assertNotNull(module.restore(actionRow(codec, stored, 100L + index), 7L))

            assertEquals(stored, restored.storedAction)
            assertEquals(payload.kind(), restored.descriptor.storageKind)
        }

        val fishingBattle = StoredTypedAutomationAction(
            12L,
            "fishing-battle",
            assertIs<StoredTypedActionPayload.BattleMap>(payloads.first()).copy(
                source = BattleAutomationActionSource.FISHING_AUTOMATION,
            ),
        )
        assertNotNull(module.restore(actionRow(codec, fishingBattle, 110L), 7L))

        val legacyTargetlessRaid = StoredTypedAutomationAction(
            12L,
            "targetless-raid",
            StoredTypedActionPayload.RaidTown(RaidAction.REFRESH, null, null),
        )
        val restoredTargetlessRaid = assertNotNull(
            module.restore(actionRow(codec, legacyTargetlessRaid, 111L), 7L),
        )
        assertIs<AmbiguousActionResolution.VerifyLater>(restoredTargetlessRaid.reconcile())
        Mockito.verifyNoInteractions(raidObservationAdapter)
    }

    @Test
    fun `낚시 START와 CATCH는 같은 일일 작업으로 저장하고 실행한다`() {
        listOf(FishingAction.START, FishingAction.CATCH).forEachIndexed { index, action ->
            val prepared = FishingTownAutomationAction(
                accountId = 7L,
                action = action,
                observedPrimaryAction = if (action == FishingAction.START) {
                    FishingPrimaryAction.START
                } else {
                    FishingPrimaryAction.CATCH
                },
                observedRemainingCasts = 10 - index,
            )

            val managed = assertNotNull(module.prepare(7L, 15L, prepared))
            val response = if (action == FishingAction.START) {
                fishingResponse(FishingPrimaryAction.CATCH, 10 - index, FishingOutcome.STARTED)
            } else {
                fishingResponse(FishingPrimaryAction.START, 10 - index, FishingOutcome.CAUGHT)
            }
            Mockito.`when`(fishingService.act(7L, action)).thenReturn(response)

            assertEquals(AutomationType.FISHING, managed.descriptor.source)
            assertEquals(action.name, managed.descriptor.actionKind)
            assertEquals(action.name, assertIs<StoredTypedActionPayload.FishingTown>(managed.storedAction.payload).action.name)
            assertEquals(managed.descriptor, module.describe(prepared))
            assertEquals(TypedAutomationExecution.Completed, managed.execute())
            Mockito.verify(fishingService).act(7L, action)
        }
        Mockito.verify(workOwnership, Mockito.times(2)).ensure(
            7L,
            15L,
            AutomationWorkAssignment(AutomationWorkType.FISHING, "DAILY_FISHING"),
        )
    }

    @Test
    fun `낚시 직접 응답이 같은 행동 상태면 generic 성공으로 완료하지 않는다`() {
        val managed = assertNotNull(module.prepare(
            7L,
            15L,
            FishingTownAutomationAction(7L, FishingAction.CATCH, FishingPrimaryAction.CATCH, 10),
        ))
        val unchanged = fishingResponse(FishingPrimaryAction.CATCH, 10, null)
        Mockito.`when`(fishingService.act(7L, FishingAction.CATCH)).thenReturn(unchanged)

        assertFailsWith<AmbiguousAutomationSubmissionException> { managed.execute() }
    }

    @Test
    fun `불명확한 낚시는 권위 상태 변화만 적용하고 관측 기준이 없으면 재확인한다`() {
        val changed = assertNotNull(
            module.prepare(
                7L,
                15L,
                FishingTownAutomationAction(7L, FishingAction.CATCH, FishingPrimaryAction.CATCH, 10),
            ),
        )
        val noBaseline = assertNotNull(
            module.prepare(
                7L,
                15L,
                FishingTownAutomationAction(7L, FishingAction.CATCH, FishingPrimaryAction.CATCH, null),
            ),
        )
        val unchanged = assertNotNull(
            module.prepare(
                7L,
                15L,
                FishingTownAutomationAction(7L, FishingAction.CATCH, FishingPrimaryAction.CATCH, 10),
            ),
        )
        val changedResponse = fishingResponse(FishingPrimaryAction.START, 9)
        val unknownResponse = fishingResponse(FishingPrimaryAction.CATCH, null)
        val unchangedResponse = fishingResponse(FishingPrimaryAction.CATCH, 10)
        Mockito.`when`(fishingService.load(7L)).thenReturn(changedResponse, unknownResponse, unchangedResponse)

        assertIs<AmbiguousActionResolution.Applied>(changed.reconcile())
        assertIs<AmbiguousActionResolution.VerifyLater>(noBaseline.reconcile())
        assertIs<AmbiguousActionResolution.VerifyLater>(unchanged.reconcile())
    }

    @Test
    fun `낚시 방해 전투는 단말 결과만 확인하고 같은 낚시 작업을 유지한다`() {
        val action = battleMapAction().copy(
            source = BattleAutomationActionSource.FISHING_AUTOMATION,
            executionIdentity = "fishing-battle-1",
            mapName = "낚시터 괴물",
        )
        val result = terminalBattleResult("VICTORY")
        Mockito.`when`(battleRun.runBattle(7L, battleRequest(), HofRequestOrigin.AUTOMATION)).thenReturn(result)

        val managed = assertNotNull(module.prepare(7L, 15L, action))

        assertEquals(AutomationType.FISHING, managed.descriptor.source)
        assertEquals(
            TypedAutomationExecution.BattleCompleted("battle_map", "map-1"),
            managed.execute(),
        )
        Mockito.verify(workOwnership).ensure(
            7L,
            15L,
            AutomationWorkAssignment(AutomationWorkType.FISHING, "DAILY_FISHING"),
        )
        Mockito.verifyNoInteractions(battleHandler, workLifecycle, unionProgress)
    }

    @Test
    fun `레이드 전투 source도 같은 lifecycle 경로에서 처리한다`() {
        val action = BattleMapAutomationAction(
            accountId = 7L,
            progressDate = java.time.LocalDate.parse("2026-08-21"),
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = app.spammy.hof.automation.entity.PresetSelectionMode.PRIMARY,
            presetId = 1L,
            battleCount = 1,
            executionIdentity = "battle-1",
            source = BattleAutomationActionSource.RAID_AUTOMATION,
            sourceTargetKey = "RaidGoblin",
            resolvedParty = ResolvedAutomationParty(
                battleRequest().characterIds,
                battleRequest().patternLoads,
            ),
        )

        assertNotNull(module.prepare(7L, 12L, action))
    }

    @Test
    fun `레이드 마을 행동을 저장하고 응답 관측만 레이드 규칙 모듈에 전달한다`() {
        val response = RaidPubResponse(emptyList(), false, false, null, null, emptySet(), null)
        val observation = RaidObservation(emptyList(), false, false)
        Mockito.`when`(
            raidPubService.actionForAutomation(
                7L,
                RaidPubActionRequest(RaidAction.RESET, "RaidGoblin"),
                "RaidGoblin",
            ),
        )
            .thenReturn(response)
        Mockito.`when`(raidObservationAdapter.from(response)).thenReturn(observation)
        Mockito.`when`(
            raidCycleModule.recordObservedResult(
                7L,
                RaidAttempt(13L, RaidIntentKind.RESET, "RaidGoblin", "RaidGoblin"),
                RaidResultObservation.Page(observation),
            ),
        ).thenReturn(RaidRecordResult.Recorded())
        val prepared = RaidTownAutomationAction(
            accountId = 7L,
            action = RaidAction.RESET,
            raidId = "RaidGoblin",
            raidName = "고블린 레이드",
            observedStatus = "보상 확인 종료",
        )

        val managed = assertNotNull(module.prepare(7L, 13L, prepared))
        val payload = assertIs<StoredTypedActionPayload.RaidTown>(managed.storedAction.payload)

        assertEquals(RaidAction.RESET, payload.action)
        assertEquals("RaidGoblin", payload.targetRaidId)
        assertEquals(AutomationType.RAID, managed.descriptor.source)
        assertEquals("RESET", managed.descriptor.actionKind)
        assertEquals("레이드 리셋 단계 · 관측 상태: 보상 확인 종료", managed.descriptor.context)
        assertEquals(managed.descriptor, module.describe(prepared))
        Mockito.verify(workOwnership).ensure(
            7L,
            13L,
            AutomationWorkAssignment(AutomationWorkType.RAID, "RaidGoblin"),
        )

        assertEquals(TypedAutomationExecution.Completed, managed.execute())
        Mockito.verify(raidCycleModule).recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.RESET, "RaidGoblin", "RaidGoblin"),
            RaidResultObservation.Page(observation),
        )
    }

    @Test
    fun `레이드 완료 판단은 규칙 모듈의 결과로만 작업을 닫는다`() {
        val response = RaidPubResponse(emptyList(), true, true, 10_000, null, emptySet(), null)
        val observation = RaidObservation(emptyList(), true, true, 10_000)
        val completion = RaidCycleOutcome(13L, "RaidGoblin", RaidCycleOutcomeKind.COMPLETED)
        Mockito.`when`(
            raidPubService.actionForAutomation(
                7L,
                RaidPubActionRequest(RaidAction.REWARD, null),
                "RaidGoblin",
            ),
        )
            .thenReturn(response)
        Mockito.`when`(raidObservationAdapter.from(response)).thenReturn(observation)
        Mockito.`when`(
            raidCycleModule.recordObservedResult(
                7L,
                RaidAttempt(13L, RaidIntentKind.REWARD, "RaidGoblin", null),
                RaidResultObservation.Page(observation),
            ),
        ).thenReturn(RaidRecordResult.Recorded(completion))
        val managed = assertNotNull(
            module.prepare(
                7L,
                13L,
                RaidTownAutomationAction(7L, RaidAction.REWARD, null, "RaidGoblin", "고블린 레이드"),
            ),
        )

        assertEquals(TypedAutomationExecution.RaidCycleFinished(completion), managed.execute())
        Mockito.verify(workLifecycle).completeRaidCycle(7L, 13L)
    }

    @Test
    fun `최신 레이드 상태에서 저장 행동이 무효하면 상위 런너에 상태 변경으로 알린다`() {
        Mockito.doThrow(RaidActionPreconditionChangedException("대상 레이드가 모집 단계로 변경됐습니다."))
            .`when`(raidPubService)
            .actionForAutomation(
                7L,
                RaidPubActionRequest(RaidAction.REWARD, null),
                "RaidGoblin",
            )
        val managed = assertNotNull(
            module.prepare(
                7L,
                13L,
                RaidTownAutomationAction(7L, RaidAction.REWARD, null, "RaidGoblin", "고블린 레이드"),
            ),
        )

        assertFailsWith<AutomationActionPreconditionChangedException> {
            managed.execute()
        }
        Mockito.verifyNoInteractions(raidObservationAdapter, raidCycleModule, workLifecycle)
    }

    @Test
    fun `불명확한 레이드 행동은 최신 원본 상태를 규칙 모듈에 다시 전달한다`() {
        val observation = RaidObservation(emptyList(), false, false)
        Mockito.`when`(raidObservationAdapter.read(7L)).thenReturn(observation)
        Mockito.`when`(
            raidCycleModule.recordObservedResult(
                7L,
                RaidAttempt(13L, RaidIntentKind.REGISTER, "RaidGoblin", "RaidGoblin"),
                RaidResultObservation.Page(observation),
            ),
        ).thenReturn(RaidRecordResult.NeedsRecheck(now.plusSeconds(30), "아직 확정할 수 없습니다."))
        val managed = assertNotNull(
            module.prepare(
                7L,
                13L,
                RaidTownAutomationAction(7L, RaidAction.REGISTER, "RaidGoblin"),
            ),
        )

        val resolution = assertIs<AmbiguousActionResolution.VerifyLater>(managed.reconcile())

        assertEquals(now.plusSeconds(30), resolution.retryAt)
        Mockito.verify(raidObservationAdapter).read(7L)
        Mockito.verifyNoInteractions(workLifecycle)
    }

    @Test
    fun `불명확한 레이드 전투는 공용 조정 대신 5분 복구 체인으로 인계한다`() {
        val retryAt = now.plusSeconds(300)
        val action = battleMapAction().copy(
            source = BattleAutomationActionSource.RAID_AUTOMATION,
            sourceTargetKey = "RaidGoblin",
            recoveryChainId = "raid-recovery-1",
            raidRetransmissionCount = 2,
            raidSubmittedFromRunnable = true,
        )
        Mockito.`when`(
            raidCycleModule.recordObservedResult(
                7L,
                RaidAttempt(
                    entryId = 13L,
                    kind = RaidIntentKind.BATTLE,
                    raidId = "RaidGoblin",
                    requestRaidId = null,
                    executionIdentity = action.executionIdentity,
                    categoryId = action.categoryId,
                    mapCode = action.mapCode,
                    recoveryChainId = action.recoveryChainId,
                    retransmissionCount = 2,
                    submittedAt = now,
                    submittedFromRunnable = true,
                ),
                RaidResultObservation.BattleAmbiguous("전투 응답 시간 초과"),
            ),
        ).thenReturn(RaidRecordResult.BattleRecoveryStarted(retryAt, "레이드 전투 결과 미확정 · 재전송 2회"))
        val managed = assertNotNull(module.prepare(7L, 13L, action))

        val resolution = assertIs<AmbiguousActionResolution.HandedOff>(
            managed.handoffAmbiguousSubmission(now, "전투 응답 시간 초과"),
        )

        assertEquals(retryAt, resolution.retryAt)
        val payload = assertIs<StoredTypedActionPayload.BattleMap>(managed.storedAction.payload)
        assertEquals("raid-recovery-1", payload.recoveryChainId)
        assertEquals(2, payload.raidRetransmissionCount)
        Mockito.verify(workLifecycle).waitForRaid(
            7L,
            13L,
            "RaidGoblin",
            retryAt,
            "레이드 전투 결과 미확정 · 재전송 2회",
        )
        Mockito.verifyNoInteractions(battleOutcome)
    }

    @Test
    fun `레이드 전투는 정확한 종료 증명 뒤에만 규칙 모듈에 완료를 기록한다`() {
        val action = battleMapAction().copy(
            source = BattleAutomationActionSource.RAID_AUTOMATION,
            sourceTargetKey = "RaidGoblin",
        )
        val managed = assertNotNull(module.prepare(7L, 13L, action))
        val evidence = BattleAuthoritativeOutcomeEvidence(
            accountId = 7L,
            executionIdentity = action.executionIdentity,
            categoryId = action.categoryId,
            mapCode = action.mapCode,
            battleCount = action.battleCount,
            resultIdentity = "raid-result-1",
            outcomes = listOf(BattleAutomationRoundOutcome.VICTORY),
        )
        Mockito.`when`(battleOutcome.reloadRecentAuthoritativeEvidence(anyBattleAction()))
            .thenReturn(BattleOutcomeReconciliation.Unproven("아직 결과 없음"))
            .thenReturn(BattleOutcomeReconciliation.Proven(evidence))
        Mockito.`when`(
            raidCycleModule.recordObservedResult(
                7L,
                RaidAttempt(13L, RaidIntentKind.BATTLE, "RaidGoblin", null),
                RaidResultObservation.BattleCompleted,
            ),
        ).thenReturn(RaidRecordResult.Recorded())

        assertIs<AmbiguousActionResolution.VerifyLater>(managed.reconcile())
        Mockito.verifyNoInteractions(raidCycleModule)

        assertIs<AmbiguousActionResolution.Applied>(managed.reconcile())
        Mockito.verify(raidCycleModule).recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.BATTLE, "RaidGoblin", null),
            RaidResultObservation.BattleCompleted,
        )
        Mockito.verify(workOwnership).ensure(
            7L,
            13L,
            AutomationWorkAssignment(AutomationWorkType.RAID, "RaidGoblin"),
        )
    }

    @Test
    fun `레이드 전투 응답이 모든 회차의 종료를 증명하면 즉시 규칙 모듈에 기록한다`() {
        val action = battleMapAction().copy(
            source = BattleAutomationActionSource.RAID_AUTOMATION,
            sourceTargetKey = "RaidGoblin",
        )
        val result = terminalBattleResult("VICTORY")
        Mockito.`when`(battleRun.runBattle(7L, battleRequest(), HofRequestOrigin.AUTOMATION))
            .thenReturn(result)
        Mockito.`when`(
            raidCycleModule.recordObservedResult(
                7L,
                RaidAttempt(13L, RaidIntentKind.BATTLE, "RaidGoblin", null),
                RaidResultObservation.BattleCompleted,
            ),
        ).thenReturn(RaidRecordResult.Recorded())
        val managed = assertNotNull(module.prepare(7L, 13L, action))

        assertEquals(
            TypedAutomationExecution.BattleCompleted("battle_map", "map-1"),
            managed.execute(),
        )
        Mockito.verify(raidCycleModule).recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.BATTLE, "RaidGoblin", null),
            RaidResultObservation.BattleCompleted,
        )
        Mockito.verifyNoInteractions(battleHandler, unionProgress)
    }

    @Test
    fun `대상 식별자가 없는 과거 레이드 전투도 정확한 종료 증명으로 안전하게 완료한다`() {
        val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())
        val stored = StoredTypedAutomationAction(
            12L,
            "legacy-targetless-raid-battle",
            StoredTypedActionPayload.BattleMap(
                progressDate = java.time.LocalDate.parse("2026-08-21"),
                categoryId = "battle_map",
                mapCode = "map-1",
                presetMode = PresetSelectionMode.PRIMARY,
                presetId = 301L,
                battleCount = 1,
                battleRequest = battleRequest(),
                source = BattleAutomationActionSource.RAID_AUTOMATION,
                sourceTargetKey = null,
            ),
        )
        val managed = assertNotNull(module.restore(actionRow(codec, stored, 112L), 7L))
        val result = terminalBattleResult("VICTORY")
        Mockito.`when`(battleRun.runBattle(7L, battleRequest(), HofRequestOrigin.AUTOMATION))
            .thenReturn(result)
        val evidence = BattleAuthoritativeOutcomeEvidence(
            accountId = 7L,
            executionIdentity = stored.executionIdentity,
            categoryId = "battle_map",
            mapCode = "map-1",
            battleCount = 1,
            resultIdentity = "legacy-result",
            outcomes = listOf(BattleAutomationRoundOutcome.VICTORY),
        )
        Mockito.`when`(battleOutcome.reloadRecentAuthoritativeEvidence(anyBattleAction()))
            .thenReturn(BattleOutcomeReconciliation.Proven(evidence))

        assertEquals(
            TypedAutomationExecution.BattleCompleted("battle_map", "map-1"),
            managed.execute(),
        )
        val resolution = assertIs<AmbiguousActionResolution.Applied>(managed.reconcile())
        assertEquals(
            TypedAutomationExecution.BattleCompleted("battle_map", "map-1"),
            resolution.execution,
        )
        Mockito.verifyNoInteractions(raidCycleModule)
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

    private fun battleMapAction() = BattleMapAutomationAction(
        accountId = 7L,
        progressDate = java.time.LocalDate.parse("2026-08-21"),
        categoryId = "battle_map",
        mapCode = "map-1",
        presetMode = PresetSelectionMode.PRIMARY,
        presetId = 301L,
        battleCount = 1,
        executionIdentity = "battle-execution-1",
        resolvedParty = ResolvedAutomationParty(
            battleRequest().characterIds,
            battleRequest().patternLoads,
        ),
        mapName = "슬라임 동굴",
    )

    private fun adventureAction() = AdventureMapAutomationAction(
        accountId = 7L,
        categoryId = "battle_map",
        mapCode = "map-1",
        presetMode = PresetSelectionMode.PRIMARY,
        presetId = 301L,
        settingIdentity = 99L,
        executionIdentity = "adventure-execution-1",
        resolvedParty = ResolvedAutomationParty(
            battleRequest().characterIds,
            battleRequest().patternLoads,
        ),
        mapName = "모험 동굴",
        observedAttemptRemaining = 3,
    )

    private fun terminalBattleResult(vararg outcomes: String): app.spammy.hof.battle.dto.BattleResultResponse {
        val rounds = outcomes.map { outcome ->
            Mockito.mock(app.spammy.hof.battle.dto.BattleRoundResponse::class.java).also { round ->
                Mockito.`when`(round.outcome).thenReturn(outcome)
                Mockito.`when`(round.loots).thenReturn(emptyList())
                Mockito.`when`(round.quest).thenReturn(null)
            }
        }
        return Mockito.mock(app.spammy.hof.battle.dto.BattleResultResponse::class.java).also { result ->
            Mockito.`when`(result.rounds).thenReturn(rounds)
        }
    }

    private fun mapResponse(
        attemptCount: Int?,
        categoryId: String = "battle_map",
    ) = BattleMapResponse(
        categoryId = categoryId,
        mapCode = "map-1",
        name = "모험 동굴",
        groupName = null,
        groupOrder = 0,
        mapOrder = 0,
        recommendedLevel = null,
        availableCount = null,
        attemptCount = attemptCount,
        winCount = null,
        cooldownRemainingText = null,
        cooldownRemainingSeconds = null,
        keyMode = BattleMapKeyMode.UNLIMITED,
        keyCount = null,
        requiredTime = 0,
        enabled = true,
        resolved = true,
        iconUrl = null,
        rawHref = "?map=map-1",
    )

    private fun fishingResponse(
        primaryAction: FishingPrimaryAction,
        remainingCasts: Int?,
        lastOutcome: FishingOutcome? = null,
    ) =
        Mockito.mock(FishingResponse::class.java).also { response ->
            Mockito.`when`(response.primaryAction).thenReturn(primaryAction)
            Mockito.`when`(response.remainingCasts).thenReturn(remainingCasts)
            Mockito.`when`(response.lastOutcome).thenReturn(lastOutcome)
            Mockito.`when`(response.blockedByBattle).thenReturn(false)
        }

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

    private fun anyQuestAttempt(): QuestAttempt =
        Mockito.any(QuestAttempt::class.java)
            ?: QuestAttempt.Claim("matcher", "matcher", "matcher")

    private fun anyQuestObservation(): QuestResultObservation =
        Mockito.any(QuestResultObservation::class.java)
            ?: QuestResultObservation.Page(emptyList())

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
