package app.spammy.hof.automation.service

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.ActionConvergenceResult
import app.spammy.hof.automation.convergence.ConvergenceDirective
import app.spammy.hof.automation.convergence.DefaultActionEvidencePolicies
import app.spammy.hof.automation.convergence.DefaultAutomationActionConvergenceModule
import app.spammy.hof.automation.convergence.InMemoryConvergenceStore
import app.spammy.hof.automation.convergence.ProductionActionEvidenceInterpreter
import app.spammy.hof.automation.convergence.StoredActionConvergenceSelectionFactory
import app.spammy.hof.automation.raid.HofRaidObservationAdapter
import app.spammy.hof.automation.raid.RaidAttempt
import app.spammy.hof.automation.raid.RaidCycleModule
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.automation.raid.RaidObservation
import app.spammy.hof.automation.raid.RaidObservedStatus
import app.spammy.hof.automation.raid.RaidObservedTarget
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
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.quest.parser.QuestPageObservation
import app.spammy.hof.quest.parser.QuestPageParser
import app.spammy.hof.town.home.dto.HomeQuestResponse
import app.spammy.hof.town.home.dto.HomeResponse
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.model.HomeQuestState
import app.spammy.hof.town.home.parser.HomePageParser
import app.spammy.hof.town.home.service.HomeService
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingOutcome
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.fishing.service.FishingService
import app.spammy.hof.town.raid.dto.RaidPubActionRequest
import app.spammy.hof.town.raid.dto.RaidBattleTargetResponse
import app.spammy.hof.town.raid.dto.RaidPubRaidResponse
import app.spammy.hof.town.raid.dto.RaidPubResponse
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.town.raid.model.RaidBattleObservationStatus
import app.spammy.hof.town.raid.model.RaidStatus
import app.spammy.hof.town.raid.service.RaidActionPreconditionChangedException
import app.spammy.hof.town.raid.service.RaidPubService
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.AccountHofMutationFence
import app.spammy.hof.town.common.service.ResolvedTownLocation
import app.spammy.hof.town.common.service.TownActionGuard
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.raid.parser.RaidPubParser
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
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
    private val module: AutomationActionLifecycleModule = lifecycleModule()

    private fun lifecycleModule(
        questCycle: QuestWorkCycleModule = questWorkCycle,
        questGatewayOverride: QuestGatewayService = questGateway,
        raidPubOverride: RaidPubService = raidPubService,
        homeOverride: HomeService = home,
    ): AutomationActionLifecycleModule = UnifiedAutomationActionLifecycleModule(
        codec = StoredTypedAutomationActionCodec(jacksonObjectMapper()),
        workOwnership = workOwnership,
        homeService = homeOverride,
        questGateway = questGatewayOverride,
        questWorkCycle = questCycle,
        battleSubmission = battleSubmission,
        battleHandler = battleHandler,
        battleOutcomeReconciler = battleOutcome,
        battleMapService = battleMapService,
        workLifecycle = workLifecycle,
        unionProgress = unionProgress,
        fishingService = fishingService,
        raidPubService = raidPubOverride,
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

        Mockito.`when`(home.runHomeQuest(7L, "accept-action", HofRequestOrigin.AUTOMATION))
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

        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())
        managed.applyLegacyExecution(execution)
        Mockito.verify(home, Mockito.times(1)).runHomeQuest(7L, "accept-action", HofRequestOrigin.AUTOMATION)
    }

    @ParameterizedTest
    @CsvSource("ACCEPT,ACTIVE", "ACCEPT,CLAIMABLE", "CLAIM,WAITING", "CLAIM,COMPLETED")
    fun `자택의 직접 사후 상태는 공용 문구 UNKNOWN이어도 적용 증거다`(
        action: HomeQuestAutomationActionType,
        state: HomeQuestState,
    ) {
        val url = "https://hof.zerosic.com/index.php?menu=housing"
        val heading = when (state) {
            HomeQuestState.ACTIVE -> "진행중인 작업 목록"
            HomeQuestState.CLAIMABLE -> "완료 가능한 퀘스트"
            HomeQuestState.WAITING -> "대기중인 작업 목록"
            HomeQuestState.COMPLETED -> "완료한 작업 목록"
            else -> error("적용 사후 상태가 아님")
        }
        val link = if (state == HomeQuestState.CLAIMABLE) "<a href='?menu=housing&amp;action=complete&amp;no=A'>완료</a>" else "-"
        val html = """<h4>$heading</h4><table><tr><td>[A] 검증</td><td>미션 1/1</td><td>-</td><td>-</td><td>$link</td></tr></table>"""
        val response = HomeResponse.from(HomePageParser().parse(HomeMode.HOME, html, url,
            HofFormParser().parse(html, url), HofResultParser().parse(html)))
        val quest = response.quests.single()
        val managed = assertNotNull(module.prepare(7L, 12L,
            HomeQuestAutomationAction(7L, quest.id, quest.name, "action-1", action)))
        Mockito.`when`(home.runHomeQuest(7L, "action-1", HofRequestOrigin.AUTOMATION))
            .thenReturn(response)

        val execution = managed.execute()
        val evidence = policyEvidence(managed, execution)

        assertIs<AutomationActionEvidence.DirectApplied>(evidence)
        assertEquals("UNKNOWN", response.result?.status)
        assertTrue(!jacksonObjectMapper().writeValueAsString(response).contains("stateObserved"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING", "DUPLICATE", "UNCHANGED"])
    fun `UNKNOWN 자택 응답의 대상 부재 중복 같은 상태는 적용으로 추정하지 않는다`(kind: String) {
        val managed = assertNotNull(module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.ACCEPT)))
        val response = homeResponse(HomeQuestState.CLAIMABLE, "claim-action").let { it.copy(
            quests = it.quests.map { quest -> quest.copy(stateObserved = true) },
        ) }
        val quests = when (kind) {
            "MISSING" -> emptyList()
            "DUPLICATE" -> response.quests + response.quests.single().copy(name = "중복")
            else -> homeResponse(HomeQuestState.AVAILABLE, "action-1").quests
        }
        Mockito.`when`(home.runHomeQuest(7L, "action-1", HofRequestOrigin.AUTOMATION))
            .thenReturn(response.copy(quests = quests, result = TownActionResultResponse("UNKNOWN", emptyList(), emptyList())))

        assertIs<AutomationActionEvidence.IncompleteObservation>(policyEvidence(managed, managed.execute()))
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING_ACTION", "AMBIGUOUS_SECTION", "INVALID_CLAIM_LINK", "TRUNCATED_ROWS"])
    fun `수락 링크가 사라진 불완전 자택 HTML의 기본 ACTIVE는 적용 증거가 아니다`(kind: String) {
        val url = "https://hof.zerosic.com/index.php?menu=housing"
        val before = """<h4>수락 가능한 퀘스트</h4><table><tr><td>[A] 검증</td><td>미션 0/1</td>
            <td>-</td><td>-</td><td><a href='?menu=housing&amp;action=get&amp;no=A'>수락</a></td></tr></table>"""
        val heading = if (kind == "TRUNCATED_ROWS") "진행중인 작업 목록" else "수락 가능한 퀘스트"
        val span = if (kind == "TRUNCATED_ROWS") "rowspan='3'" else ""
        val actionCell = when (kind) {
            "MISSING_ACTION" -> ""
            "INVALID_CLAIM_LINK" -> "<td><a href='https://invalid.test/?action=complete&amp;no=A'>완료</a></td>"
            else -> "<td>-</td>"
        }
        val after = """<h4>$heading</h4><table><tr><td $span>[A] 검증</td><td>미션 0/1</td>
            <td>-</td><td>-</td>$actionCell</tr></table>"""
        val locations = Mockito.mock(TownLocationResolver::class.java)
        Mockito.`when`(locations.resolve(TownFeatureId.HOME_MANAGEMENT, null))
            .thenReturn(ResolvedTownLocation(TownFeatureId.HOME_MANAGEMENT, url))
        val liveHome = HomeService(remoteExecutor(
            HofHttpResponse(200, url, before, emptyMap()), HofHttpResponse(200, url, after, emptyMap()),
        ), locations, HomePageParser())
        val quest = HomePageParser().parse(HomeMode.HOME, before, url, HofFormParser().parse(before, url)).quests.single()
        val managed = assertNotNull(lifecycleModule(homeOverride = liveHome).prepare(7L, 12L,
            HomeQuestAutomationAction(7L, quest.id, quest.name, assertNotNull(quest.actionId), HomeQuestAutomationActionType.ACCEPT)))

        assertIs<AutomationActionEvidence.IncompleteObservation>(policyEvidence(managed, managed.execute()))
    }

    @Test
    fun `자택 adapter는 같은 상태 응답도 버리지 않고 policy가 미적용으로 판정한다`() {
        val managed = assertNotNull(module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.ACCEPT)))
        Mockito.`when`(home.runHomeQuest(7L, "action-1", HofRequestOrigin.AUTOMATION))
            .thenReturn(homeResponse(HomeQuestState.AVAILABLE, "action-1"))

        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())

        assertIs<AutomationActionEvidence.SameState>(policyEvidence(managed, execution))
        assertFailsWith<AmbiguousAutomationSubmissionException> {
            managed.applyLegacyExecution(execution)
        }
    }

    @Test
    fun `자택 adapter의 중복 target 실제 응답은 unknown shape evidence로 보존한다`() {
        val managed = assertNotNull(module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.ACCEPT)))
        val response = homeResponse(HomeQuestState.AVAILABLE, "action-1")
        Mockito.`when`(home.runHomeQuest(7L, "action-1", HofRequestOrigin.AUTOMATION)).thenReturn(
            response.copy(quests = response.quests + response.quests.single().copy(name = "중복")),
        )

        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())
        val evidence = assertIs<AutomationActionEvidence.IncompleteObservation>(
            policyEvidence(managed, execution),
        )

        assertEquals("UNKNOWN_RESPONSE_SHAPE", evidence.reason)
        assertTrue(evidence.sanitizedSnippet?.contains("targetMultiplicity=MULTIPLE") == true)
    }

    @Test
    fun `자택 제출 직전 원본 action이 바뀌면 공통 precondition 변경으로 분류한다`() {
        val managed = assertNotNull(module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.ACCEPT)))
        Mockito.doThrow(ApiException(ErrorCode.INVALID_REQUEST, "현재 action을 찾지 못했습니다."))
            .`when`(home)
            .runHomeQuest(7L, "action-1", HofRequestOrigin.AUTOMATION)

        assertFailsWith<AutomationActionPreconditionChangedException> { managed.execute() }
    }

    @Test
    fun `권위 있는 자택 퀘스트 상태로 적용 재제출 재확인을 구분한다`() {
        val accept = assertNotNull(module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.ACCEPT)))
        Mockito.`when`(home.load(7L, HomeMode.HOME, HofRequestOrigin.AUTOMATION)).thenReturn(
            homeResponse(HomeQuestState.ACTIVE, null),
            homeResponse(HomeQuestState.AVAILABLE, "action-1"),
            homeResponse(HomeQuestState.AVAILABLE, "changed-action"),
        )

        assertIs<AmbiguousActionResolution.Applied>(accept.reconcile())
        assertIs<AmbiguousActionResolution.Resubmit>(accept.reconcile())
        val verifyLater = assertIs<AmbiguousActionResolution.VerifyLater>(accept.reconcile())
        assertEquals(now.plusSeconds(10), verifyLater.retryAt)

        val claim = assertNotNull(module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.CLAIM)))
        Mockito.`when`(home.load(7L, HomeMode.HOME, HofRequestOrigin.AUTOMATION)).thenReturn(
            HomeResponse(HomeMode.HOME, emptyList(), emptyList(), null, null),
        )
        Mockito.`when`(home.runHomeQuest(7L, "action-1", HofRequestOrigin.AUTOMATION)).thenReturn(
            HomeResponse(HomeMode.HOME, emptyList(), emptyList(), null, null),
        )
        assertIs<AmbiguousActionResolution.VerifyLater>(claim.reconcile())
        val emptyClaimExecution = assertIs<TypedAutomationExecution.ActionCompleted>(claim.execute())
        assertEquals(
            "UNKNOWN_RESPONSE_SHAPE",
            assertIs<AutomationActionEvidence.IncompleteObservation>(
                policyEvidence(claim, emptyClaimExecution),
            ).reason,
        )
        Mockito.verify(home).runHomeQuest(7L, "action-1", HofRequestOrigin.AUTOMATION)
    }

    @Test
    fun `빈 퀘스트 parser 결과는 보상 성공이 아니라 unknown shape로 보류한다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, QuestAction.Claim("quest-1", "claim-1")),
        )
        Mockito.`when`(questGateway.claimObservation(7L, "claim-1", HofRequestOrigin.AUTOMATION))
            .thenReturn(QuestPageObservation(emptyList(), complete = false))

        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())
        val evidence = assertIs<AutomationActionEvidence.IncompleteObservation>(
            policyEvidence(managed, execution),
        )

        assertEquals("UNKNOWN_RESPONSE_SHAPE", evidence.reason)
    }

    @Test
    fun `자택 퀘스트 제출 결과가 불명확하면 즉시 재실행하지 않고 조정 대상으로 남긴다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.ACCEPT)),
        )
        Mockito.doThrow(IllegalStateException("connection closed", IOException("connection closed")))
            .`when`(home).runHomeQuest(7L, "action-1", HofRequestOrigin.AUTOMATION)

        assertFailsWith<AmbiguousAutomationSubmissionException> { managed.execute() }
        Mockito.verify(home, Mockito.times(1)).runHomeQuest(7L, "action-1", HofRequestOrigin.AUTOMATION)
    }

    @Test
    fun `일반 퀘스트 수락을 저장하고 같은 descriptor와 작업 귀속을 사용한다`() {
        val action = QuestAction.Accept("quest-1", "accept-1", "첫 번째 퀘스트")
        val observed = listOf(quest(QuestState.ACTIVE, null))
        Mockito.`when`(questGateway.acceptObservation(7L, "accept-1", HofRequestOrigin.AUTOMATION))
            .thenReturn(QuestPageObservation(observed, complete = true))
        Mockito.`when`(questWorkCycle.recordObservedResult(Mockito.eq(7L), anyQuestAttempt(), anyQuestObservation()))
            .thenReturn(QuestRecordResult.Recorded("1"))

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
        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())
        managed.applyPolicyAcceptedExecution(execution)
        Mockito.verify(questGateway).acceptObservation(7L, "accept-1", HofRequestOrigin.AUTOMATION)
        Mockito.verify(questWorkCycle).recordObservedResult(
            7L,
            QuestAttempt.Accept(managed.storedAction.executionIdentity, "quest-1", "accept-1"),
            QuestResultObservation.Page(observed, complete = true),
        )
    }

    @Test
    fun `일반 퀘스트 보상 수령을 저장하고 정확히 한 번 실행한다`() {
        val action = QuestAction.Claim("quest-1", "claim-1", "첫 번째 퀘스트")
        val observed = emptyList<QuestSnapshot>()
        Mockito.`when`(questGateway.claimObservation(7L, "claim-1", HofRequestOrigin.AUTOMATION))
            .thenReturn(QuestPageObservation(observed, complete = true))
        Mockito.`when`(questWorkCycle.recordObservedResult(Mockito.eq(7L), anyQuestAttempt(), anyQuestObservation()))
            .thenReturn(QuestRecordResult.Recorded("1"))

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
        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())
        assertIs<AutomationActionEvidence.DirectApplied>(policyEvidence(managed, execution))
        managed.applyPolicyAcceptedExecution(execution)
        Mockito.verify(questGateway).claimObservation(7L, "claim-1", HofRequestOrigin.AUTOMATION)
        Mockito.verify(questWorkCycle).recordObservedResult(
            7L,
            QuestAttempt.Claim(managed.storedAction.executionIdentity, "quest-1", "claim-1"),
            QuestResultObservation.Page(observed, complete = true),
        )
    }

    @Test
    fun `실제 비대상 quest fixture는 HTTP 경계부터 durable applied까지 직접 수렴한다`() {
        val html = checkNotNull(
            javaClass.classLoader.getResource("fixtures/quest/quest-complete-empty.html"),
        ).readText()
        val current = """
            <div id="contents"><h4>진행중인 퀘스트 목록</h4><table><tr>
              <td class="td7s">[0777] fixture quest</td><td>미션 : 즉시 완료</td>
              <td><a href="?menu=quest&amp;action=complete&amp;no=claim-1">완료</a></td>
            </tr></table></div>
        """.trimIndent()
        val liveGateway = QuestGatewayService(
            remoteExecutor(
                HofHttpResponse(200, QUEST_URL, current, emptyMap()),
                HofHttpResponse(200, QUEST_URL, html, emptyMap()),
            ),
            QuestPageParser(),
        )
        val action = QuestAction.Claim("fixture-quest", "claim-1", "fixture quest")
        val durableQuestCycle = DefaultQuestWorkCycleModule(
            Mockito.mock(QuestAutomationProgressStore::class.java),
        )
        val managed = assertNotNull(
            lifecycleModule(durableQuestCycle, questGatewayOverride = liveGateway).prepare(7L, 12L, action),
        )

        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())
        val evidence = assertIs<AutomationActionEvidence.DirectApplied>(policyEvidence(managed, execution))
        assertIs<TypedAutomationExecution.ActionCompleted>(managed.applyPolicyAcceptedExecution(execution))

        val store = InMemoryConvergenceStore()
        val convergence = DefaultAutomationActionConvergenceModule(store, TimeProvider { now })
        val selection = StoredActionConvergenceSelectionFactory().create(managed.storedAction)
        val attemptId = assertIs<ConvergenceDirective.Submit>(convergence.prepare(7L, selection)).attemptId

        assertIs<ConvergenceDirective.ContinueSelection>(convergence.record(attemptId, evidence))
        assertEquals(ActionConvergenceResult.APPLIED, store.get(attemptId)?.result)
        assertEquals("DIRECT_RESPONSE_APPLIED", store.get(attemptId)?.reasonCode)
    }

    @Test
    fun `일반 퀘스트 adapter는 미적용 응답을 policy까지 전달한다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, QuestAction.Accept("quest-1", "accept-1")),
        )
        val observed = listOf(quest(QuestState.AVAILABLE, "accept-1"))
        val attempt = QuestAttempt.Accept(
            managed.storedAction.executionIdentity,
            "quest-1",
            "accept-1",
        )
        Mockito.`when`(questGateway.acceptObservation(7L, "accept-1", HofRequestOrigin.AUTOMATION))
            .thenReturn(QuestPageObservation(observed, complete = true))
        Mockito.`when`(
            questWorkCycle.recordObservedResult(
                7L,
                attempt,
                QuestResultObservation.Page(observed, complete = true),
            ),
        ).thenReturn(QuestRecordResult.NotApplied("still available"))

        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())

        assertIs<AutomationActionEvidence.SameState>(policyEvidence(managed, execution))
        assertFailsWith<AmbiguousAutomationSubmissionException> {
            managed.applyLegacyExecution(execution)
        }

        Mockito.verify(questGateway, Mockito.times(1))
            .acceptObservation(7L, "accept-1", HofRequestOrigin.AUTOMATION)
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
        Mockito.`when`(questGateway.acceptObservation(7L, "accept-1", HofRequestOrigin.AUTOMATION))
            .thenReturn(QuestPageObservation(observed, complete = true))
        Mockito.`when`(
            questWorkCycle.recordObservedResult(
                7L,
                attempt,
                QuestResultObservation.Page(observed, complete = true),
            ),
        ).thenThrow(IllegalStateException("cycle store unavailable"))

        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())
        assertFailsWith<AmbiguousAutomationSubmissionException> {
            managed.applyPolicyAcceptedExecution(execution)
        }

        Mockito.verify(questGateway, Mockito.times(1))
            .acceptObservation(7L, "accept-1", HofRequestOrigin.AUTOMATION)
    }

    @Test
    fun `일반 퀘스트 보상 결과를 권위 상태로 조정한다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, QuestAction.Claim("quest-1", "claim-1")),
        )
        Mockito.`when`(questGateway.loadObservation(7L, HofRequestOrigin.AUTOMATION)).thenReturn(
            QuestPageObservation(emptyList(), complete = false),
            QuestPageObservation(listOf(quest(QuestState.CLAIMABLE, "claim-1")), complete = true),
            QuestPageObservation(listOf(quest(QuestState.ACTIVE, null)), complete = true),
        )
        Mockito.doReturn(
            QuestRecordResult.NotApplied("still claimable"),
            QuestRecordResult.NeedsRecheck("not authoritative"),
        ).`when`(questWorkCycle).recordObservedResult(Mockito.eq(7L), anyQuestAttempt(), anyQuestObservation())

        assertIs<AmbiguousActionResolution.VerifyLater>(managed.reconcile())
        assertIs<AmbiguousActionResolution.Resubmit>(managed.reconcile())
        val verifyLater = assertIs<AmbiguousActionResolution.VerifyLater>(managed.reconcile())
        assertEquals(now.plusSeconds(10), verifyLater.retryAt)
    }

    @Test
    fun `일반 퀘스트 수락 결과를 권위 상태로 조정한다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, QuestAction.Accept("quest-1", "accept-1")),
        )
        Mockito.`when`(questGateway.loadObservation(7L, HofRequestOrigin.AUTOMATION)).thenReturn(
            QuestPageObservation(listOf(quest(QuestState.ACTIVE, null)), complete = true),
            QuestPageObservation(listOf(quest(QuestState.AVAILABLE, "accept-1")), complete = true),
            QuestPageObservation(listOf(quest(QuestState.UNAVAILABLE, null)), complete = true),
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
            QuestResultObservation.Page(listOf(quest(QuestState.ACTIVE, null)), complete = true),
        )
        assertIs<AmbiguousActionResolution.Resubmit>(managed.reconcile())
        assertIs<AmbiguousActionResolution.VerifyLater>(managed.reconcile())
    }

    @Test
    fun `일반 퀘스트 전송 결과가 불명확하면 같은 행동을 즉시 반복하지 않는다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, QuestAction.Claim("quest-1", "claim-1")),
        )
        Mockito.`when`(questGateway.claimObservation(7L, "claim-1", HofRequestOrigin.AUTOMATION))
            .thenThrow(ApiException(ErrorCode.HOF_REQUEST_FAILED, "upstream result unknown"))

        assertFailsWith<AmbiguousAutomationSubmissionException> { managed.execute() }

        Mockito.verify(questGateway, Mockito.times(1))
            .claimObservation(7L, "claim-1", HofRequestOrigin.AUTOMATION)
    }

    @Test
    fun `만료된 세션은 복구한 뒤 준비된 퀘스트 행동만 한 번 다시 제출한다`() {
        val managed = assertNotNull(
            module.prepare(7L, 12L, QuestAction.Accept("quest-1", "accept-1")),
        )
        Mockito.`when`(questGateway.acceptObservation(7L, "accept-1", HofRequestOrigin.AUTOMATION))
            .thenThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
            .thenReturn(QuestPageObservation(emptyList(), complete = false))

        assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())

        Mockito.verify(accountService).reauthenticate(7L, HofRequestOrigin.AUTOMATION)
        Mockito.verify(questGateway, Mockito.times(2))
            .acceptObservation(7L, "accept-1", HofRequestOrigin.AUTOMATION)
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
        Mockito.`when`(questWorkCycle.recordObservedResult(Mockito.eq(7L), anyQuestAttempt(), anyQuestObservation()))
            .thenReturn(QuestRecordResult.Recorded("1"))

        val execution = managed.execute()
        assertTerminalBattle(execution)
        managed.applyPolicyAcceptedExecution(execution)

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
            BattleOutcomeReconciliation.Unproven("terminal proof still missing"),
        )
        val fallbackPage = listOf(activeQuest(progress = 3))
        Mockito.`when`(questGateway.loadObservation(7L, HofRequestOrigin.AUTOMATION)).thenReturn(
            QuestPageObservation(fallbackPage, complete = true),
        )

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
            QuestResultObservation.Page(fallbackPage, complete = true),
        )
        val verifyLater = assertIs<AmbiguousActionResolution.VerifyLater>(managed.reconcile())
        assertEquals(now.plusSeconds(10), verifyLater.retryAt)

        Mockito.`when`(
            questWorkCycle.recordObservedResult(Mockito.eq(7L), anyQuestAttempt(), anyQuestObservation()),
        ).thenReturn(QuestRecordResult.FreshDecision("latest quest progress is authoritative"))
        val freshDecision = assertIs<AmbiguousActionResolution.FreshDecision>(managed.reconcile())
        assertEquals("latest quest progress is authoritative", freshDecision.reason)
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

        Mockito.`when`(result.responseShapeFingerprint).thenReturn("f".repeat(64))
        Mockito.`when`(result.sanitizedResponseSnippet).thenReturn("BattleHttpResponse|status=200|rounds=1")

        val error = assertFailsWith<AmbiguousAutomationSubmissionException> { managed.execute() }

        assertEquals("f".repeat(64), error.responseShapeFingerprint)
        assertEquals("BattleHttpResponse|status=200|rounds=1", error.sanitizedSnippet)
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

        assertTerminalBattle(managed.execute())
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
        Mockito.verify(workLifecycle).completeBattleMapAction(7L, 12L, "battle_map", "map-1", managed.storedAction.executionIdentity)
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
    fun `불명확한 일반 전투의 최신 맵 소멸은 내 적용이 아니라 외부 변경으로 닫는다`() {
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

        assertIs<AmbiguousActionResolution.Superseded>(managed.reconcile())

        Mockito.verifyNoInteractions(battleHandler, workLifecycle, unionProgress)
    }

    @Test
    fun `전투 제출 직전 최신 맵 관측이 불완전하면 POST 전에 보류한다`() {
        val managed = assertNotNull(module.prepare(7L, 12L, battleMapAction()))
        Mockito.`when`(
            battleMapService.observeCurrentlyAvailableMaps(7L, "battle_map", HofRequestOrigin.AUTOMATION),
        ).thenReturn(CurrentBattleMapObservation(CurrentBattleMapObservationStatus.INCOMPLETE, emptyList()))

        assertFailsWith<AutomationPreSubmitObservationIncompleteException> {
            managed.validateBeforeSubmission()
        }

        Mockito.verifyNoInteractions(battleRun)
    }

    @Test
    fun `전투 제출 직전 최신 완전 맵에서 대상이 사라지면 저장 행동을 폐기한다`() {
        val managed = assertNotNull(module.prepare(7L, 12L, battleMapAction()))
        Mockito.`when`(
            battleMapService.observeCurrentlyAvailableMaps(7L, "battle_map", HofRequestOrigin.AUTOMATION),
        ).thenReturn(
            CurrentBattleMapObservation(
                CurrentBattleMapObservationStatus.OBSERVED,
                listOf(mapResponse(null).copy(mapCode = "other-map")),
            ),
        )

        assertFailsWith<AutomationActionPreconditionChangedException> {
            managed.validateBeforeSubmission()
        }

        Mockito.verifyNoInteractions(battleRun)
    }

    @Test
    fun `모든 원격 행동 family는 공통 제출 직전 GET에서 바뀐 상태를 POST 없이 폐기한다`() {
        val homeManaged = assertNotNull(module.prepare(7L, 12L, homeAction(HomeQuestAutomationActionType.ACCEPT)))
        Mockito.`when`(
            home.load(7L, HomeMode.HOME, HofRequestOrigin.AUTOMATION),
        ).thenReturn(homeResponse(HomeQuestState.ACTIVE, null))
        assertFailsWith<AutomationActionPreconditionChangedException> {
            homeManaged.validateBeforeSubmission()
        }

        val questManaged = assertNotNull(module.prepare(7L, 12L, QuestAction.Accept("quest-1", "accept-1")))
        Mockito.`when`(questGateway.loadObservation(7L, HofRequestOrigin.AUTOMATION)).thenReturn(
            app.spammy.hof.quest.parser.QuestPageObservation(
                listOf(quest(QuestState.ACTIVE, null)),
                complete = true,
            ),
        )
        assertFailsWith<AutomationActionPreconditionChangedException> {
            questManaged.validateBeforeSubmission()
        }

        val fishingManaged = assertNotNull(module.prepare(
            7L,
            15L,
            FishingTownAutomationAction(7L, FishingAction.CATCH, FishingPrimaryAction.CATCH, 10),
        ))
        val changedFishing = fishingResponse(FishingPrimaryAction.START, 9, FishingOutcome.CAUGHT)
        Mockito.`when`(
            fishingService.load(7L, HofRequestOrigin.AUTOMATION),
        ).thenReturn(changedFishing)
        assertFailsWith<AutomationActionPreconditionChangedException> {
            fishingManaged.validateBeforeSubmission()
        }

        val raidManaged = assertNotNull(module.prepare(
            7L,
            13L,
            RaidTownAutomationAction(7L, RaidAction.REFRESH, null, "RaidGoblin"),
        ))
        Mockito.`when`(raidPubService.load(7L, HofRequestOrigin.AUTOMATION)).thenReturn(
            RaidPubResponse(emptyList(), false, false, null, null, emptySet(), null, pageComplete = true),
        )
        assertFailsWith<AutomationActionPreconditionChangedException> {
            raidManaged.validateBeforeSubmission()
        }

        val adventureManaged = assertNotNull(module.prepare(7L, 14L, adventureAction()))
        Mockito.`when`(
            battleMapService.observeCurrentlyAvailableMaps(7L, "battle_map", HofRequestOrigin.AUTOMATION),
        ).thenReturn(CurrentBattleMapObservation(CurrentBattleMapObservationStatus.INCOMPLETE, emptyList()))
        assertFailsWith<AutomationPreSubmitObservationIncompleteException> {
            adventureManaged.validateBeforeSubmission()
        }

        Mockito.verify(home, Mockito.never()).runHomeQuest(7L, "action-1")
        Mockito.verify(questGateway, Mockito.never()).accept(7L, "accept-1", HofRequestOrigin.AUTOMATION)
        Mockito.verify(fishingService, Mockito.never()).act(7L, FishingAction.CATCH)
        Mockito.verify(raidPubService, Mockito.never()).actionForAutomation(
            7L,
            RaidPubActionRequest(RaidAction.REFRESH, null),
            "RaidGoblin",
        )
        Mockito.verifyNoInteractions(battleRun)
    }

    @Test
    fun `퀘스트 제출 직전 페이지가 불완전하면 대상 부재로 단정하지 않는다`() {
        val managed = assertNotNull(module.prepare(7L, 12L, QuestAction.Accept("quest-1", "accept-1")))
        Mockito.`when`(questGateway.loadObservation(7L, HofRequestOrigin.AUTOMATION)).thenReturn(
            app.spammy.hof.quest.parser.QuestPageObservation(emptyList(), complete = false),
        )

        assertFailsWith<AutomationPreSubmitObservationIncompleteException> {
            managed.validateBeforeSubmission()
        }
    }

    @Test
    fun `불명확한 유니온 전투에서 다른 사람이 맵을 제거하면 내 적용으로 귀속하지 않는다`() {
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

        assertIs<AmbiguousActionResolution.Superseded>(managed.reconcile())

        Mockito.verifyNoInteractions(battleOutcome, battleHandler, workLifecycle)
        Mockito.verifyNoInteractions(unionProgress)
    }

    @Test
    fun `불명확한 유니온 전투의 개인 쿨다운은 내 성공으로 귀속하지 않는다`() {
        val action = battleMapAction().copy(
            categoryId = "union",
            source = BattleAutomationActionSource.UNION_AUTOMATION,
            executionIdentity = "union-ambiguous-cooldown",
            mapName = "고블린 침공군",
        )
        val managed = assertNotNull(module.prepare(7L, 13L, action))
        Mockito.`when`(
            battleMapService.observeCurrentlyAvailableMaps(7L, "union", HofRequestOrigin.AUTOMATION),
        ).thenReturn(
            CurrentBattleMapObservation(
                CurrentBattleMapObservationStatus.OBSERVED,
                listOf(mapResponse(attemptCount = null, categoryId = "union").copy(cooldownRemainingSeconds = 30)),
            ),
        )

        assertIs<AmbiguousActionResolution.Superseded>(managed.reconcile())

        Mockito.verifyNoInteractions(unionProgress)
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
    fun `유니온 전투는 단말 결과 뒤 실제 맵 다음으로 순환하고 유니온 작업 사이클을 완료한다`() {
        val action = battleMapAction().copy(
            source = BattleAutomationActionSource.UNION_AUTOMATION,
            executionIdentity = "union-execution-1",
            mapName = "유니온 초원",
        )
        val result = terminalBattleResult("VICTORY")
        Mockito.`when`(battleRun.runBattle(7L, battleRequest(), HofRequestOrigin.AUTOMATION)).thenReturn(result)

        val managed = assertNotNull(module.prepare(7L, 13L, action))

        assertEquals(AutomationType.UNION, managed.descriptor.source)
        assertTerminalBattle(managed.execute())
        Mockito.verify(unionProgress).battleCompleted(7L, 13L, "battle_map", "map-1")
        Mockito.verify(workLifecycle).completeUnionCycle(7L, 13L, managed.storedAction.executionIdentity)
        Mockito.verifyNoInteractions(battleHandler)
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
        assertTerminalBattle(managed.execute())
        Mockito.verify(workLifecycle).completeAdventureAction(7L, 14L, "battle_map", "map-1", managed.storedAction.executionIdentity)
    }

    @Test
    fun `불명확한 모험맵은 권위 상태가 변해도 내 성공으로 귀속하지 않는다`() {
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
        Mockito.`when`(
            battleMapService.observeCurrentlyAvailableMaps(7L, "battle_map", HofRequestOrigin.AUTOMATION),
        )
            .thenReturn(
                CurrentBattleMapObservation(
                    CurrentBattleMapObservationStatus.OBSERVED,
                    listOf(mapResponse(attemptCount = 2)),
                ),
                CurrentBattleMapObservation(
                    CurrentBattleMapObservationStatus.OBSERVED,
                    listOf(mapResponse(attemptCount = 3)),
                ),
            )
        Mockito.`when`(battleOutcome.reloadRecentAuthoritativeEvidence(anyBattleAction()))
            .thenReturn(BattleOutcomeReconciliation.Unproven("결과 식별자를 찾지 못했습니다."))

        assertIs<AmbiguousActionResolution.Superseded>(decreased.reconcile())
        assertIs<AmbiguousActionResolution.VerifyLater>(noBaseline.reconcile())
        Mockito.verifyNoInteractions(workLifecycle)
    }

    @Test
    fun `불명확한 모험맵은 전체 기준이 같을 때만 동일 상태이며 증가한 횟수는 대체 상태다`() {
        val unchanged = assertNotNull(module.prepare(7L, 14L, adventureAction()))
        val reset = assertNotNull(
            module.prepare(
                7L,
                14L,
                adventureAction().copy(executionIdentity = "adventure-reset"),
            ),
        )
        Mockito.`when`(
            battleMapService.observeCurrentlyAvailableMaps(7L, "battle_map", HofRequestOrigin.AUTOMATION),
        )
            .thenReturn(
                CurrentBattleMapObservation(
                    CurrentBattleMapObservationStatus.OBSERVED,
                    listOf(mapResponse(attemptCount = 3)),
                ),
                CurrentBattleMapObservation(
                    CurrentBattleMapObservationStatus.OBSERVED,
                    listOf(mapResponse(attemptCount = 4)),
                ),
            )
        Mockito.`when`(battleOutcome.reloadRecentAuthoritativeEvidence(anyBattleAction()))
            .thenReturn(BattleOutcomeReconciliation.Unproven("결과 식별자를 찾지 못했습니다."))

        assertIs<AmbiguousActionResolution.Resubmit>(unchanged.reconcile())
        assertIs<AmbiguousActionResolution.Superseded>(reset.reconcile())
        Mockito.verifyNoInteractions(workLifecycle)
    }

    @Test
    fun `불명확한 모험맵의 비권위 페이지는 과거 catalog로 적용 판정하지 않는다`() {
        val managed = assertNotNull(module.prepare(7L, 14L, adventureAction()))
        Mockito.`when`(battleOutcome.reloadRecentAuthoritativeEvidence(anyBattleAction()))
            .thenReturn(BattleOutcomeReconciliation.Unproven("결과 식별자를 찾지 못했습니다."))
        Mockito.`when`(
            battleMapService.observeCurrentlyAvailableMaps(7L, "battle_map", HofRequestOrigin.AUTOMATION),
        ).thenReturn(
            CurrentBattleMapObservation(CurrentBattleMapObservationStatus.INCOMPLETE, emptyList()),
        )

        assertIs<AmbiguousActionResolution.VerifyLater>(managed.reconcile())
        Mockito.verifyNoInteractions(workLifecycle)
        Mockito.verify(battleMapService, Mockito.never())
            .findMaps(7L, "battle_map", HofRequestOrigin.AUTOMATION)
    }

    @Test
    fun `불명확한 모험맵 대상이 완전한 최신 목록에서 사라지면 다음 대상 선택으로 넘긴다`() {
        val managed = assertNotNull(module.prepare(7L, 14L, adventureAction()))
        Mockito.`when`(battleOutcome.reloadRecentAuthoritativeEvidence(anyBattleAction()))
            .thenReturn(BattleOutcomeReconciliation.Unproven("결과 식별자를 찾지 못했습니다."))
        Mockito.`when`(
            battleMapService.observeCurrentlyAvailableMaps(7L, "battle_map", HofRequestOrigin.AUTOMATION),
        ).thenReturn(
            CurrentBattleMapObservation(
                CurrentBattleMapObservationStatus.OBSERVED,
                listOf(mapResponse(mapCode = "map-2", attemptCount = 3)),
            ),
        )

        assertIs<AmbiguousActionResolution.Superseded>(managed.reconcile())
        Mockito.verifyNoInteractions(workLifecycle)
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
    fun `배포 전 저장된 보상 payload는 제출 직전 폐기하고 POST하지 않는다`() {
        val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())
        val legacy = StoredTypedAutomationAction(
            entryId = 12L,
            executionIdentity = "legacy-reward",
            payload = StoredTypedActionPayload.RaidTown(
                action = RaidAction.REWARD,
                raidId = null,
                targetRaidId = "RaidGoblin",
                display = StoredActionDisplay(mapName = "고블린 레이드"),
                selectedAt = null,
            ),
        )
        val managed = assertNotNull(module.restore(actionRow(codec, legacy, 112L), 7L))

        assertFailsWith<AutomationActionPreconditionChangedException> {
            managed.validateBeforeSubmission()
        }
        Mockito.verify(raidPubService).load(7L, HofRequestOrigin.AUTOMATION)
        Mockito.verify(raidPubService, Mockito.never()).actionForAutomation(
            7L,
            RaidPubActionRequest(RaidAction.REWARD, null),
            "RaidGoblin",
        )
        Mockito.verifyNoInteractions(raidObservationAdapter, raidCycleModule)
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
            Mockito.`when`(fishingService.act(7L, action, HofRequestOrigin.AUTOMATION)).thenReturn(response)

            assertEquals(AutomationType.FISHING, managed.descriptor.source)
            assertEquals(action.name, managed.descriptor.actionKind)
            assertEquals(action.name, assertIs<StoredTypedActionPayload.FishingTown>(managed.storedAction.payload).action.name)
            assertEquals(managed.descriptor, module.describe(prepared))
            val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())
            managed.applyLegacyExecution(execution)
            if (action == FishingAction.CATCH) {
                Mockito.verify(workLifecycle).completeFishingCycle(7L, 15L, managed.storedAction.executionIdentity)
            }
            Mockito.verify(fishingService).act(7L, action, HofRequestOrigin.AUTOMATION)
        }
        Mockito.verify(workOwnership, Mockito.times(2)).ensure(
            7L,
            15L,
            AutomationWorkAssignment(AutomationWorkType.FISHING, "DAILY_FISHING"),
        )
    }

    @Test
    fun `낚시 START 직접 응답은 공통 결과가 UNKNOWN이어도 CATCH 사후 상태로 적용한다`() {
        val managed = assertNotNull(module.prepare(
            7L,
            15L,
            FishingTownAutomationAction(7L, FishingAction.START, FishingPrimaryAction.START, 10),
        ))
        val started = fishingResponse(
            FishingPrimaryAction.CATCH,
            10,
            resultStatus = "UNKNOWN",
        )
        Mockito.`when`(
            fishingService.act(7L, FishingAction.START, HofRequestOrigin.AUTOMATION),
        ).thenReturn(started)

        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())

        assertIs<AutomationActionEvidence.DirectApplied>(policyEvidence(managed, execution))
    }

    @Test
    fun `CATCH 직접 응답에 방해 전투가 생겨도 낚시 작업을 완료한다`() {
        val managed = assertNotNull(module.prepare(
            7L,
            15L,
            FishingTownAutomationAction(7L, FishingAction.CATCH, FishingPrimaryAction.CATCH, 10),
        ))
        val blocked = fishingResponse(FishingPrimaryAction.NONE, 9, FishingOutcome.CAUGHT, blockedByBattle = true)
        Mockito.`when`(
            fishingService.act(7L, FishingAction.CATCH, HofRequestOrigin.AUTOMATION),
        ).thenReturn(blocked)

        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())
        managed.applyLegacyExecution(execution)

        Mockito.verify(workLifecycle).completeFishingCycle(7L, 15L, managed.storedAction.executionIdentity)
    }

    @Test
    fun `낚시 adapter는 같은 행동 상태를 policy까지 전달해 미적용으로 판정한다`() {
        val managed = assertNotNull(module.prepare(
            7L,
            15L,
            FishingTownAutomationAction(7L, FishingAction.CATCH, FishingPrimaryAction.CATCH, 10),
        ))
        val unchanged = fishingResponse(FishingPrimaryAction.CATCH, 10, null)
        Mockito.`when`(
            fishingService.act(7L, FishingAction.CATCH, HofRequestOrigin.AUTOMATION),
        ).thenReturn(unchanged)

        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())

        assertIs<AutomationActionEvidence.SameState>(policyEvidence(managed, execution))
        assertFailsWith<AmbiguousAutomationSubmissionException> {
            managed.applyLegacyExecution(execution)
        }
    }

    @Test
    fun `불명확한 낚시는 권위 상태 변화만 적용하고 같은 상태는 공통 관측 예산으로 넘긴다`() {
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
        Mockito.`when`(
            fishingService.load(7L, HofRequestOrigin.AUTOMATION),
        ).thenReturn(changedResponse, unknownResponse, unchangedResponse)

        assertIs<AmbiguousActionResolution.Applied>(changed.reconcile())
        assertIs<AmbiguousActionResolution.Resubmit>(noBaseline.reconcile())
        assertIs<AmbiguousActionResolution.Resubmit>(unchanged.reconcile())
        Mockito.verify(workLifecycle).completeFishingCycle(7L, 15L)
        Mockito.verify(workLifecycle, Mockito.never()).waitFishingCycle(
            Mockito.eq(7L), Mockito.eq(15L), Mockito.isNull(), Mockito.anyString(),
        )
    }

    @Test
    fun `불명확한 낚시는 CATCH를 이어가고 방해 전투는 성공 귀속 없이 작업을 닫는다`() {
        fun managed(action: FishingAction, primary: FishingPrimaryAction, remaining: Int?) = assertNotNull(
            module.prepare(
                7L,
                15L,
                FishingTownAutomationAction(7L, action, primary, remaining),
            ),
        )
        val startToCatch = managed(FishingAction.START, FishingPrimaryAction.START, 10)
        val startToBattle = managed(FishingAction.START, FishingPrimaryAction.START, 10)
        val startIndeterminate = managed(FishingAction.START, FishingPrimaryAction.START, 10)
        val startEscaped = managed(FishingAction.START, FishingPrimaryAction.START, 10)
        val catchCompleted = managed(FishingAction.CATCH, FishingPrimaryAction.CATCH, 9)
        val catchState = fishingResponse(FishingPrimaryAction.CATCH, 10, FishingOutcome.STARTED)
        val battleState = fishingResponse(FishingPrimaryAction.NONE, 10, blockedByBattle = true)
        val indeterminateState = fishingResponse(FishingPrimaryAction.START, 10)
        val escapedState = fishingResponse(FishingPrimaryAction.START, 10, FishingOutcome.ESCAPED)
        val caughtState = fishingResponse(FishingPrimaryAction.START, 8, FishingOutcome.CAUGHT)
        Mockito.`when`(fishingService.load(7L, HofRequestOrigin.AUTOMATION)).thenReturn(
            catchState,
            battleState,
            indeterminateState,
            escapedState,
            caughtState,
        )

        assertIs<AmbiguousActionResolution.Applied>(startToCatch.reconcile())
        assertIs<AmbiguousActionResolution.Superseded>(startToBattle.reconcile())
        assertIs<AmbiguousActionResolution.Resubmit>(startIndeterminate.reconcile())
        assertIs<AmbiguousActionResolution.Applied>(startEscaped.reconcile())
        assertIs<AmbiguousActionResolution.Applied>(catchCompleted.reconcile())

        Mockito.verify(workLifecycle, Mockito.times(3)).completeFishingCycle(7L, 15L)
    }

    @Test
    fun `낚시 방해 전투가 끝나면 한 번 낚시 작업을 종료한다`() {
        val action = battleMapAction().copy(
            source = BattleAutomationActionSource.FISHING_AUTOMATION,
            executionIdentity = "fishing-battle-1",
            mapName = "낚시터 괴물",
        )
        val result = terminalBattleResult("VICTORY")
        Mockito.`when`(battleRun.runBattle(7L, battleRequest(), HofRequestOrigin.AUTOMATION)).thenReturn(result)

        val managed = assertNotNull(module.prepare(7L, 15L, action))

        assertEquals(AutomationType.FISHING, managed.descriptor.source)
        assertTerminalBattle(managed.execute())
        Mockito.verify(workOwnership).ensure(
            7L,
            15L,
            AutomationWorkAssignment(AutomationWorkType.FISHING, "DAILY_FISHING"),
        )
        Mockito.verify(workLifecycle).completeFishingCycle(7L, 15L, managed.storedAction.executionIdentity)
        Mockito.verifyNoInteractions(battleHandler, unionProgress)
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
    fun `레이드 전투 제출 직전 참여 중인 공유 전투 상태를 다시 확인한다`() {
        val managed = assertNotNull(module.prepare(
            7L,
            12L,
            battleMapAction().copy(
                source = BattleAutomationActionSource.RAID_AUTOMATION,
                sourceTargetKey = "RaidGoblin",
            ),
        ))
        Mockito.`when`(raidPubService.load(7L, HofRequestOrigin.AUTOMATION)).thenReturn(raidBattleResponse())

        managed.validateBeforeSubmission()

        Mockito.verify(raidPubService).load(7L, HofRequestOrigin.AUTOMATION)
        Mockito.verifyNoInteractions(battleRun)
    }

    @Test
    fun `레이드 전투 제출 직전 외부 완료로 공유 상태가 바뀌면 POST하지 않는다`() {
        val managed = assertNotNull(module.prepare(
            7L,
            12L,
            battleMapAction().copy(
                source = BattleAutomationActionSource.RAID_AUTOMATION,
                sourceTargetKey = "RaidGoblin",
            ),
        ))
        Mockito.`when`(raidPubService.load(7L, HofRequestOrigin.AUTOMATION)).thenReturn(
            raidBattleResponse(status = RaidStatus.COMPLETED, targetPresent = false),
        )

        assertFailsWith<AutomationActionPreconditionChangedException> {
            managed.validateBeforeSubmission()
        }

        Mockito.verifyNoInteractions(battleRun)
    }

    @Test
    fun `레이드 전투 제출 직전 공유 화면이 불완전하면 관측 보류하고 POST하지 않는다`() {
        val managed = assertNotNull(module.prepare(
            7L,
            12L,
            battleMapAction().copy(
                source = BattleAutomationActionSource.RAID_AUTOMATION,
                sourceTargetKey = "RaidGoblin",
            ),
        ))
        Mockito.`when`(raidPubService.load(7L, HofRequestOrigin.AUTOMATION)).thenReturn(
            raidBattleResponse(observationStatus = RaidBattleObservationStatus.INCOMPLETE),
        )

        assertFailsWith<AutomationPreSubmitObservationIncompleteException> {
            managed.validateBeforeSubmission()
        }

        Mockito.verifyNoInteractions(battleRun)
    }

    @Test
    fun `레이드 마을 행동을 저장하고 응답 관측만 레이드 규칙 모듈에 전달한다`() {
        val response = RaidPubResponse(
            emptyList(), false, false, null, null, emptySet(), null, pageComplete = true,
        )
        val observation = RaidObservation(emptyList(), false, false)
        Mockito.`when`(
            raidPubService.actionForAutomation(
                7L,
                RaidPubActionRequest(RaidAction.RESET, "RaidGoblin"),
                "RaidGoblin",
            ),
        )
            .thenReturn(response)
        Mockito.`when`(raidObservationAdapter.from(response, RaidAction.RESET)).thenReturn(observation)
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

        val observedExecution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())
        Mockito.verifyNoInteractions(raidObservationAdapter, raidCycleModule)
        managed.applyPolicyAcceptedExecution(observedExecution)
        Mockito.verify(raidCycleModule).recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.RESET, "RaidGoblin", "RaidGoblin"),
            RaidResultObservation.Page(observation),
        )
    }

    @Test
    fun `상태 갱신 직접 응답이 entry wait이면 레이드 작업권을 정확한 시각까지 놓는다`() {
        val response = RaidPubResponse(
            emptyList(), false, false, null, null, emptySet(), null, pageComplete = true,
        )
        val observation = RaidObservation(emptyList(), true, false)
        val retryAt = now.plusSeconds(600)
        Mockito.`when`(
            raidPubService.actionForAutomation(
                7L,
                RaidPubActionRequest(RaidAction.REFRESH, null),
                "RaidGoblin",
            ),
        ).thenReturn(response)
        Mockito.`when`(raidObservationAdapter.from(response, RaidAction.REFRESH)).thenReturn(observation)
        Mockito.`when`(
            raidCycleModule.recordObservedResult(
                7L,
                RaidAttempt(13L, RaidIntentKind.REFRESH, "RaidGoblin", null),
                RaidResultObservation.Page(observation),
            ),
        ).thenReturn(RaidRecordResult.EntryWait(
            retryAt,
            "RaidGoblin",
            "10분 뒤 다시 확인",
            reasonCode = "RAID_MANUAL_UNCONFIGURED_ACTIVE",
            releaseCondition = "수동 레이드 종료 뒤 상태 갱신",
        ))
        val managed = assertNotNull(module.prepare(
            7L,
            13L,
            RaidTownAutomationAction(
                accountId = 7L,
                action = RaidAction.REFRESH,
                raidId = null,
                targetRaidId = "RaidGoblin",
                raidName = "고블린 레이드",
            ),
        ))

        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())
        val waiting = assertIs<TypedAutomationExecution.ActionCompleted>(
            managed.applyPolicyAcceptedExecution(execution),
        ).raidWait

        assertEquals("RAID_MANUAL_UNCONFIGURED_ACTIVE", waiting?.reasonCode)
        assertEquals(retryAt, waiting?.retryAt)
        assertEquals("수동 레이드 종료 뒤 상태 갱신", waiting?.releaseCondition)
        Mockito.verify(workLifecycle).waitForRaid(
            7L,
            13L,
            "RaidGoblin",
            retryAt,
            "10분 뒤 다시 확인",
        )
        Mockito.verify(workLifecycle, Mockito.never()).completeRaidCycle(Mockito.eq(7L), Mockito.eq(13L), Mockito.nullable(String::class.java))
    }

    @Test
    fun `레이드 전역 등록 쿨타임은 작업권을 놓지만 사용자 경고로 저장하지 않는다`() {
        val response = RaidPubResponse(
            emptyList(), false, false, null, null, emptySet(), null, pageComplete = true,
        )
        val observation = RaidObservation(emptyList(), true, false)
        val retryAt = now.plusSeconds(600)
        Mockito.`when`(
            raidPubService.actionForAutomation(
                7L,
                RaidPubActionRequest(RaidAction.REFRESH, null),
                "RaidGoblin",
            ),
        ).thenReturn(response)
        Mockito.`when`(raidObservationAdapter.from(response, RaidAction.REFRESH)).thenReturn(observation)
        Mockito.`when`(
            raidCycleModule.recordObservedResult(
                7L,
                RaidAttempt(13L, RaidIntentKind.REFRESH, "RaidGoblin", null),
                RaidResultObservation.Page(observation),
            ),
        ).thenReturn(RaidRecordResult.EntryWait(
            retryAt,
            "RaidGoblin",
            "레이드 전역 등록 쿨타임 종료 뒤 상태를 다시 갱신합니다.",
            reasonCode = "RAID_GLOBAL_REGISTRATION_COOLDOWN",
            releaseCondition = "전역 등록 쿨타임 종료 뒤 상태 갱신",
        ))
        val managed = assertNotNull(module.prepare(
            7L,
            13L,
            RaidTownAutomationAction(
                accountId = 7L,
                action = RaidAction.REFRESH,
                raidId = null,
                targetRaidId = "RaidGoblin",
                raidName = "고블린 레이드",
            ),
        ))

        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())
        val waiting = assertIs<TypedAutomationExecution.ActionCompleted>(
            managed.applyPolicyAcceptedExecution(execution),
        ).raidWait

        assertEquals("RAID_GLOBAL_REGISTRATION_COOLDOWN", waiting?.reasonCode)
        assertEquals(retryAt, waiting?.retryAt)
        assertEquals("전역 등록 쿨타임 종료 뒤 상태 갱신", waiting?.releaseCondition)
        Mockito.verify(workLifecycle).waitForRaid(
            7L,
            13L,
            "RaidGoblin",
            retryAt,
            null,
        )
        Mockito.verify(workLifecycle, Mockito.never()).completeRaidCycle(Mockito.eq(7L), Mockito.eq(13L), Mockito.nullable(String::class.java))
    }

    @Test
    fun `active 수렴의 등록 충돌 거절도 레이드 복구 상태 projection을 기록한다`() {
        val conflictMessage = "이미 전투 중입니다. 퇴치/보상 확인/상태 갱신을 해주세요."
        val base = raidBattleResponse(status = RaidStatus.RECRUITING, targetPresent = false)
        val response = base.copy(
            raids = base.raids.map { it.copy(joined = false) },
            result = app.spammy.hof.town.fishing.dto.TownActionResultResponse(
                status = "FAILURE",
                messages = listOf(conflictMessage),
                items = emptyList(),
            ),
        )
        val observation = RaidObservation(
            raids = emptyList(),
            applied = false,
            registrationWait = false,
            resultMessages = listOf(conflictMessage),
        )
        Mockito.`when`(
            raidPubService.actionForAutomation(
                7L,
                RaidPubActionRequest(RaidAction.REGISTER, "RaidGoblin"),
                "RaidGoblin",
            ),
        ).thenReturn(response)
        Mockito.`when`(raidObservationAdapter.from(response, RaidAction.REGISTER)).thenReturn(observation)
        Mockito.`when`(
            raidCycleModule.recordObservedResult(
                7L,
                RaidAttempt(13L, RaidIntentKind.REGISTER, "RaidGoblin", "RaidGoblin"),
                RaidResultObservation.Page(observation),
            ),
        ).thenReturn(RaidRecordResult.Recorded())
        val managed = assertNotNull(module.prepare(
            7L,
            13L,
            RaidTownAutomationAction(7L, RaidAction.REGISTER, "RaidGoblin", "RaidGoblin"),
        ))

        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())
        val evidence = assertIs<AutomationActionEvidence.DirectRejected>(policyEvidence(managed, execution))
        managed.applyPolicyResolvedExecution(execution, evidence)

        Mockito.verify(raidCycleModule).recordObservedResult(
            7L,
            RaidAttempt(13L, RaidIntentKind.REGISTER, "RaidGoblin", "RaidGoblin"),
            RaidResultObservation.Page(observation),
        )
    }

    @Test
    fun `잘린 레이드 초기화 직접 응답은 보충 GET으로 성공을 만들지 않는다`() {
        val fixture = checkNotNull(
            javaClass.classLoader.getResource("fixtures/town/raid/raidpub.html"),
        ).readText()
        val resettable = fixture
            .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
            .replace("현재 상태 : 모집 중", "현재 상태 : 보상 확인 종료(리셋 가능)")
            .replace("[《테스트 길드》현재사용자]", "[다른 신청자]")
            .replace(
                "<input type=\"submit\" name=\"leave_goblin\" value=\"파티에서 나온다\">",
                "<input type=\"submit\" name=\"leave_goblin\" value=\"파티에서 나온다\">" +
                    "<input type=\"submit\" name=\"reset_goblin\" value=\"전투를 리셋한다\">",
            )
        val afterReset = fixture
            .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
            .replace("현재 상태 : 모집 중", "현재 상태 : 파티 모집 중 (신청 안됨)")
            .replace("[《테스트 길드》현재사용자]", "[다른 신청자]")
        val formOnly = afterReset.replace("<a href=\"?menu=raidlog\">Battle Log 전부표시</a>", "")
        val liveRaidPub = realRaidPubService(resettable, formOnly, afterReset)
        val managed = assertNotNull(
            lifecycleModule(raidPubOverride = liveRaidPub).prepare(
                7L,
                13L,
                RaidTownAutomationAction(
                    accountId = 7L,
                    action = RaidAction.RESET,
                    raidId = "RaidGoblin",
                    targetRaidId = "RaidGoblin",
                ),
            ),
        )

        val failure = assertFailsWith<AmbiguousAutomationSubmissionException> { managed.execute() }

        assertEquals(ErrorCode.HOF_REQUEST_FAILED, assertIs<ApiException>(failure.cause).errorCode)
    }

    @Test
    fun `불완전 raid response의 target 부재는 reset이나 reward 성공이 아니다`() {
        listOf(RaidAction.RESET to "RaidGoblin", RaidAction.REWARD to null).forEach { (action, raidId) ->
            val response = RaidPubResponse(
                emptyList(), false, false, null, null, emptySet(), null, pageComplete = false,
            )
            Mockito.`when`(
                raidPubService.actionForAutomation(
                    7L,
                    RaidPubActionRequest(action, raidId),
                    "RaidGoblin",
                ),
            ).thenReturn(response)
            val managed = assertNotNull(
                module.prepare(
                    7L,
                    13L,
                    RaidTownAutomationAction(7L, action, raidId, "RaidGoblin"),
                ),
            )

            val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())

            assertIs<AutomationActionEvidence.IncompleteObservation>(policyEvidence(managed, execution))
        }
    }

    @Test
    fun `공용 result UNKNOWN은 레이드 action별 사후 상태 판정을 막지 않는다`() {
        val cases = listOf(
            Triple(RaidAction.REGISTER, RaidStatus.RECRUITING, AutomationActionEvidence.DirectApplied::class),
            Triple(RaidAction.START, RaidStatus.IN_BATTLE, AutomationActionEvidence.StateAdvanced::class),
            Triple(RaidAction.REWARD, RaidStatus.COMPLETED, AutomationActionEvidence.StateAdvanced::class),
        )
        cases.forEach { (action, status, expectedEvidence) ->
            val requestRaidId = if (action == RaidAction.REWARD) null else "RaidGoblin"
            val base = raidBattleResponse(status = status, targetPresent = false)
            val response = base.copy(
                raids = base.raids.map { it.copy(joined = true) },
                globalActions = emptySet(),
                result = app.spammy.hof.town.fishing.dto.TownActionResultResponse(
                    status = "UNKNOWN",
                    messages = emptyList(),
                    items = emptyList(),
                ),
            )
            Mockito.`when`(
                raidPubService.actionForAutomation(
                    7L,
                    RaidPubActionRequest(action, requestRaidId),
                    "RaidGoblin",
                ),
            ).thenReturn(response)
            val managed = assertNotNull(
                module.prepare(
                    7L,
                    13L,
                    RaidTownAutomationAction(
                        accountId = 7L,
                        action = action,
                        raidId = requestRaidId,
                        targetRaidId = "RaidGoblin",
                    ),
                ),
            )

            val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())

            assertEquals(expectedEvidence, policyEvidence(managed, execution)::class, action.name)
        }
    }

    @Test
    fun `레이드 상태 갱신의 완전한 등록 대기 직접 응답은 공용 안내 없이 적용된다`() {
        val page = raidRefreshPage()
        val before = page.replace("현재 상태 : 모집 중", "현재 상태 : 보상 확인 종료(리셋 가능)")
        val after = page.replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)",
            "현재 상태는 신청 대기입니다.(신청 가능 까지 1시간 37분 58초)")
        val evidence = refreshEvidence(before, after)

        assertIs<AutomationActionEvidence.DirectApplied>(evidence)
    }

    @Test
    fun `레이드 상태 갱신의 등록 가능 직접 응답은 공용 안내 없이 적용된다`() {
        val page = raidRefreshPage()
        val before = page.replace("현재 상태 : 모집 중", "현재 상태 : 보상 확인 종료(리셋 가능)")
        val after = page.replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
        val evidence = refreshEvidence(before, after)

        assertIs<AutomationActionEvidence.DirectApplied>(evidence)
    }

    @ParameterizedTest
    @ValueSource(strings = ["MISSING_REGISTRATION", "INVALID_WAIT", "UNKNOWN_OTHER_RAID", "QUOTED_REGISTRATION"])
    fun `레이드 갱신의 불완전 상태는 공용 SUCCESS가 있어도 직접 응답으로 받지 않는다`(variant: String) {
        val page = raidRefreshPage()
        val header = "현재 상태는 신청 대기 (신청 가능까지 6분 58초)"
        val incomplete = when (variant) {
            "MISSING_REGISTRATION" -> page.replace(header, "")
            "INVALID_WAIT" -> page.replace(header, "현재 상태는 신청 대기 (신청 가능까지 확인 중)")
            "UNKNOWN_OTHER_RAID" -> page.replace("418초 후 출발", "확인되지 않은 단계")
            "QUOTED_REGISTRATION" -> page.replace(header, "<blockquote>$header</blockquote>")
            else -> error(variant)
        }.replace("</body>", "<div class=\"notice\">전투 정보실 안내를 확인했습니다.</div></body>")
        val managed = assertNotNull(lifecycleModule(raidPubOverride = realRaidPubService(page, page, incomplete))
            .prepare(7L, 13L, RaidTownAutomationAction(7L, RaidAction.REFRESH, null, "RaidGoblin")))
        managed.validateBeforeSubmission()

        val failure = assertFailsWith<AmbiguousAutomationSubmissionException> { managed.execute() }
        assertEquals(ErrorCode.HOF_REQUEST_FAILED, assertIs<ApiException>(failure.cause).errorCode)
    }

    @ParameterizedTest
    @ValueSource(strings = ["BLOCKQUOTE", "HIDDEN", "RAID_SECTION"])
    fun `레이드 갱신은 계정 영역 밖 문구로 등록 대기를 만들지 않는다`(location: String) {
        val page = raidRefreshPage()
            .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
        val unrelated = "신청 완료. 신청 대기 (신청 가능까지 99분)"
        val after = when (location) {
            "BLOCKQUOTE" -> page.replace("현재 상태는 신청 가능", "현재 상태는 신청 가능 <blockquote>$unrelated</blockquote>")
            "HIDDEN" -> page.replace("현재 상태는 신청 가능", "현재 상태는 신청 가능 <span hidden>$unrelated</span>")
            "RAID_SECTION" -> page.replace("현재 상태 : 모집 중", "$unrelated 현재 상태 : 모집 중")
            else -> error(location)
        }

        val response = realRaidPubService(page, after).actionForAutomation(7L, RaidPubActionRequest(RaidAction.REFRESH), "RaidGoblin")

        assertFalse(response.applyWait)
        assertNull(response.applyWaitSeconds)
        assertFalse(response.applied)
        assertTrue(response.registrationStateObserved)
    }

    @Test
    fun `레이드 상태 갱신은 이미 신청한 현재 계정의 직접 상태도 적용한다`() {
        val page = raidRefreshPage(joined = true)
            .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청한 상태")
        val evidence = refreshEvidence(page, page)

        assertIs<AutomationActionEvidence.DirectApplied>(evidence)
    }

    @Test
    fun `레이드 시작의 같은 READY 직접 응답은 상태 진전이 아닌 결과 확인 대기다`() {
        val ready = checkNotNull(javaClass.classLoader.getResource("fixtures/town/raid/raidpub.html")).readText()
            .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
            .replace("현재 상태 : 모집 중", "현재 상태 : 출발 가능")
            .replace("name=\"register_goblin\" value=\"등록한다\"", "name=\"start_goblin\" value=\"전투를 시작한다\"")
        val managed = assertNotNull(lifecycleModule(raidPubOverride = realRaidPubService(ready, ready, ready))
            .prepare(7L, 13L, RaidTownAutomationAction(7L, RaidAction.START, "RaidGoblin", "RaidGoblin")))
        managed.validateBeforeSubmission()

        val evidence = policyEvidence(managed, managed.execute())

        assertIs<AutomationActionEvidence.SameState>(evidence)
        val store = InMemoryConvergenceStore()
        val convergence = DefaultAutomationActionConvergenceModule(store, TimeProvider { now })
        val selection = StoredActionConvergenceSelectionFactory().create(managed.storedAction)
        val attemptId = assertIs<ConvergenceDirective.Submit>(convergence.prepare(7L, selection)).attemptId
        assertIs<ConvergenceDirective.WaitUntil>(convergence.record(attemptId, evidence))
        assertEquals(ActionConvergenceResult.PENDING, store.get(attemptId)?.result)
    }

    @Test
    fun `공용 안내가 있는 외부 레이드 시작은 현재 START 요청의 성공이 아니다`() {
        val ready = checkNotNull(javaClass.classLoader.getResource("fixtures/town/raid/raidpub.html")).readText()
            .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
            .replace("현재 상태 : 모집 중", "현재 상태 : 출발 가능")
            .replace("name=\"register_goblin\" value=\"등록한다\"", "name=\"start_goblin\" value=\"전투를 시작한다\"")
        val externalStart = ready.replace("현재 상태 : 출발 가능", "현재 상태 : 전투 중")
            .replace("</body>", "<div class=\"notice\">전투 정보실 안내를 확인했습니다.</div></body>")
        val managed = assertNotNull(lifecycleModule(raidPubOverride = realRaidPubService(ready, ready, externalStart))
            .prepare(7L, 13L, RaidTownAutomationAction(7L, RaidAction.START, "RaidGoblin", "RaidGoblin")))
        managed.validateBeforeSubmission()

        val evidence = policyEvidence(managed, managed.execute())

        assertIs<AutomationActionEvidence.StateAdvanced>(evidence)
    }

    @Test
    fun `수령 가능한 보상 없음 응답은 실패 문자열이어도 레이드 보상 완료다`() {
        val base = raidBattleResponse(status = RaidStatus.COMPLETED, targetPresent = false)
        val response = base.copy(
            globalActions = setOf(RaidAction.REWARD),
            result = app.spammy.hof.town.fishing.dto.TownActionResultResponse(
                status = "FAILURE",
                messages = listOf("수령 가능한 보상이 없습니다."),
                items = emptyList(),
            ),
        )
        Mockito.`when`(
            raidPubService.actionForAutomation(
                7L,
                RaidPubActionRequest(RaidAction.REWARD, null),
                "RaidGoblin",
            ),
        ).thenReturn(response)
        val managed = assertNotNull(
            module.prepare(
                7L,
                13L,
                RaidTownAutomationAction(
                    accountId = 7L,
                    action = RaidAction.REWARD,
                    raidId = null,
                    targetRaidId = "RaidGoblin",
                ),
            ),
        )

        val execution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())

        assertIs<AutomationActionEvidence.DirectApplied>(policyEvidence(managed, execution))
    }

    @Test
    fun `레이드 완료 판단은 규칙 모듈의 결과로만 작업을 닫는다`() {
        val response = RaidPubResponse(
            emptyList(), true, true, 10_000, null, emptySet(), null, pageComplete = true,
        )
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
        val managed = assertNotNull(
            module.prepare(
                7L,
                13L,
                RaidTownAutomationAction(7L, RaidAction.REWARD, null, "RaidGoblin", "고블린 레이드"),
            ),
        )

        Mockito.`when`(raidObservationAdapter.from(response, RaidAction.REWARD)).thenReturn(observation)
        Mockito.`when`(
            raidCycleModule.recordObservedResult(
                7L,
                RaidAttempt(13L, RaidIntentKind.REWARD, "RaidGoblin", null, executionIdentity = managed.storedAction.executionIdentity),
                RaidResultObservation.Page(observation),
            ),
        ).thenReturn(RaidRecordResult.Recorded(completion))
        val observedExecution = assertIs<TypedAutomationExecution.ActionCompleted>(managed.execute())
        val acceptedExecution = assertIs<TypedAutomationExecution.ActionCompleted>(
            managed.applyPolicyAcceptedExecution(observedExecution),
        )
        assertEquals(
            completion,
            acceptedExecution.raidOutcome,
        )
        Mockito.verify(workLifecycle).completeRaidCycle(7L, 13L, managed.storedAction.executionIdentity)
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
        Mockito.`when`(raidObservationAdapter.refresh(7L, "RaidGoblin")).thenReturn(observation)
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
        Mockito.verify(raidObservationAdapter).refresh(7L, "RaidGoblin")
        Mockito.verifyNoInteractions(workLifecycle)
    }

    @Test
    fun `성공 표식 없이 다른 참가자가 시작한 레이드는 현재 START 요청의 성공으로 귀속하지 않는다`() {
        val observation = RaidObservation(
            raids = listOf(
                RaidObservedTarget(
                    id = "RaidGoblin",
                    name = "고블린 레이드",
                    playable = true,
                    status = RaidObservedStatus.IN_BATTLE,
                    joined = true,
                    actions = emptySet(),
                ),
            ),
            applied = true,
            registrationWait = false,
            actionSuccessMarker = false,
        )
        Mockito.`when`(raidObservationAdapter.read(7L)).thenReturn(observation)
        val managed = assertNotNull(
            module.prepare(
                7L,
                13L,
                RaidTownAutomationAction(
                    accountId = 7L,
                    action = RaidAction.START,
                    raidId = "RaidGoblin",
                    targetRaidId = "RaidGoblin",
                ),
            ),
        )

        val resolution = assertIs<AmbiguousActionResolution.Superseded>(managed.reconcile())

        assertTrue(resolution.reason.contains("다른 참가자의 시작"))
        Mockito.verifyNoInteractions(raidCycleModule, workLifecycle)
    }

    @Test
    fun `미적용 보상 결과는 저장 payload 재전송이 아니라 fresh 판단으로 닫는다`() {
        val observation = RaidObservation(emptyList(), false, false)
        Mockito.`when`(raidObservationAdapter.read(7L)).thenReturn(observation)
        val managed = assertNotNull(
            module.prepare(
                7L,
                13L,
                RaidTownAutomationAction(7L, RaidAction.REWARD, null, "RaidGoblin", "고블린 레이드"),
            ),
        )
        Mockito.`when`(
            raidCycleModule.recordObservedResult(
                7L,
                RaidAttempt(
                    13L,
                    RaidIntentKind.REWARD,
                    "RaidGoblin",
                    null,
                    executionIdentity = managed.storedAction.executionIdentity,
                ),
                RaidResultObservation.Page(observation),
            ),
        ).thenReturn(RaidRecordResult.NotApplied("보상 요청이 적용되지 않았습니다."))

        val resolution = assertIs<AmbiguousActionResolution.Superseded>(managed.reconcile())

        assertEquals("보상 요청이 적용되지 않았습니다.", resolution.reason)
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
                completedRaidBattleAttempt(action),
                RaidResultObservation.BattleCompleted,
            ),
        ).thenReturn(RaidRecordResult.Recorded())

        assertIs<AmbiguousActionResolution.VerifyLater>(managed.reconcile())
        Mockito.verifyNoInteractions(raidCycleModule)

        assertIs<AmbiguousActionResolution.Applied>(managed.reconcile())
        Mockito.verify(raidCycleModule).recordObservedResult(
            7L,
            completedRaidBattleAttempt(action),
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
                completedRaidBattleAttempt(action),
                RaidResultObservation.BattleCompleted,
            ),
        ).thenReturn(RaidRecordResult.Recorded())
        val managed = assertNotNull(module.prepare(7L, 13L, action))

        assertTerminalBattle(managed.execute())
        Mockito.verify(raidCycleModule).recordObservedResult(
            7L,
            completedRaidBattleAttempt(action),
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

        assertTerminalBattle(managed.execute())
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

    private fun completedRaidBattleAttempt(action: BattleMapAutomationAction) = RaidAttempt(
        entryId = 13L,
        kind = RaidIntentKind.BATTLE,
        raidId = "RaidGoblin",
        requestRaidId = null,
        executionIdentity = action.executionIdentity,
        categoryId = action.categoryId,
        mapCode = action.mapCode,
        recoveryChainId = action.recoveryChainId,
        retransmissionCount = action.raidRetransmissionCount,
        finishedAt = now,
        submittedFromRunnable = action.raidSubmittedFromRunnable,
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

    private fun raidBattleResponse(
        status: RaidStatus = RaidStatus.IN_BATTLE,
        observationStatus: RaidBattleObservationStatus = RaidBattleObservationStatus.OBSERVED,
        targetPresent: Boolean = true,
    ) = RaidPubResponse(
        raids = listOf(
            RaidPubRaidResponse(
                id = "RaidGoblin",
                name = "고블린 레이드",
                playable = true,
                difficulty = null,
                maxPartySize = null,
                rewardDamage = null,
                status = status,
                statusText = null,
                waitSeconds = null,
                applicants = emptyList(),
                joined = true,
                actions = emptySet(),
                battleTarget = if (targetPresent) {
                    RaidBattleTargetResponse("battle_map", "map-1", 0)
                } else {
                    null
                },
            ),
        ),
        applied = false,
        applyWait = false,
        applyWaitSeconds = null,
        myStatus = null,
        globalActions = emptySet(),
        result = null,
        pageComplete = true,
        battleObservationStatus = observationStatus,
    )

    private fun assertTerminalBattle(execution: TypedAutomationExecution) {
        val completed = assertIs<TypedAutomationExecution.BattleCompleted>(execution)
        assertEquals("battle_map", completed.categoryId)
        assertEquals("map-1", completed.mapCode)
        assertTrue(completed.terminalOutcomes.isNotEmpty())
        assertTrue(completed.terminalOutcomes.all { it in setOf("VICTORY", "DEFEAT", "DRAW") })
    }

    private fun policyEvidence(
        managed: ManagedAutomationAction,
        execution: TypedAutomationExecution,
    ): AutomationActionEvidence = ProductionActionEvidenceInterpreter(DefaultActionEvidencePolicies()).fromExecution(
        StoredActionConvergenceSelectionFactory().create(managed.storedAction),
        execution,
        now,
    )

    private fun mapResponse(
        attemptCount: Int?,
        categoryId: String = "battle_map",
        mapCode: String = "map-1",
    ) = BattleMapResponse(
        categoryId = categoryId,
        mapCode = mapCode,
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
        rawHref = "?map=$mapCode",
    )

    private fun fishingResponse(
        primaryAction: FishingPrimaryAction,
        remainingCasts: Int?,
        lastOutcome: FishingOutcome? = null,
        blockedByBattle: Boolean = false,
        resultStatus: String? = null,
    ) =
        Mockito.mock(FishingResponse::class.java).also { response ->
            Mockito.`when`(response.primaryAction).thenReturn(primaryAction)
            Mockito.`when`(response.remainingCasts).thenReturn(remainingCasts)
            Mockito.`when`(response.lastOutcome).thenReturn(lastOutcome)
            Mockito.`when`(response.blockedByBattle).thenReturn(blockedByBattle)
            Mockito.`when`(response.battleObservationComplete).thenReturn(true)
            Mockito.`when`(response.result).thenReturn(
                resultStatus?.let { TownActionResultResponse(it, emptyList(), emptyList()) },
            )
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
            ?: QuestResultObservation.Page(emptyList(), complete = false)

    private fun raidRefreshPage(joined: Boolean = false): String =
        checkNotNull(javaClass.classLoader.getResource("fixtures/town/raid/raidpub.html")).readText()
            .replace("<input type=\"submit\" name=\"reward_nonce\" value=\"보상 확인\">",
                "<input type=\"submit\" name=\"refresh_nonce\" value=\"상태 갱신\">")
            .let { if (joined) it else it.replace("[《테스트 길드》현재사용자]", "[다른 신청자]") }

    private fun refreshEvidence(before: String, after: String): AutomationActionEvidence {
        val managed = assertNotNull(lifecycleModule(raidPubOverride = realRaidPubService(before, before, after))
            .prepare(7L, 13L, RaidTownAutomationAction(7L, RaidAction.REFRESH, null, "RaidGoblin")))
        managed.validateBeforeSubmission()
        return policyEvidence(managed, managed.execute())
    }

    private fun realRaidPubService(vararg responseBodies: String): RaidPubService {
        val locations = Mockito.mock(TownLocationResolver::class.java)
        val battleMaps = Mockito.mock(BattleMapService::class.java)
        Mockito.`when`(battleMaps.observeCurrentlyAvailableMaps(7L, "raid", HofRequestOrigin.AUTOMATION))
            .thenReturn(CurrentBattleMapObservation(CurrentBattleMapObservationStatus.OBSERVED,
                listOf(mapResponse(null, "raid", "RaidGoblin"))))
        Mockito.`when`(locations.resolve(TownFeatureId.RAID_INFO, null)).thenReturn(
            ResolvedTownLocation(TownFeatureId.RAID_INFO, RAID_PUB_URL),
        )
        return RaidPubService(
            remoteExecutor(
                *responseBodies.map { body ->
                    HofHttpResponse(200, RAID_PUB_URL, body, emptyMap())
                }.toTypedArray(),
            ),
            locations,
            RaidPubParser(),
            battleMaps,
        )
    }

    private fun remoteExecutor(vararg responses: HofHttpResponse): TownAuthenticatedExecutor {
        require(responses.isNotEmpty())
        val accounts = Mockito.mock(AccountQueryRepository::class.java)
        val cookies = Mockito.mock(CookieQueryRepository::class.java)
        val gateway = Mockito.mock(AccountHofGateway::class.java)
        Mockito.`when`(accounts.findById(7L)).thenReturn(HofAccountEntity(7L, "fixture", "encrypted", now))
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "fixture-session"))
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyHofRequest(), anyHofCookies())).thenReturn(
            responses.first(),
            *responses.drop(1).toTypedArray(),
        )
        return TownAuthenticatedExecutor(
            accounts,
            cookies,
            HofRequestFactory(),
            gateway,
            LoginStateParser(),
            HofFormParser(),
            HofResultParser(),
            TownActionGuard(),
            AccountHofMutationFence(),
        )
    }

    private fun anyHofRequest(): HofRequest = Mockito.any(HofRequest::class.java)
        ?: HofRequest(HofHttpMethod.GET, QUEST_URL)

    private fun anyHofCookies(): Map<String, String> =
        Mockito.anyMap<String, String>() ?: emptyMap()

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

    private companion object {
        const val QUEST_URL = "https://hof.zerosic.com/index.php?menu=quest"
        const val RAID_PUB_URL = "https://hof.zerosic.com/index.php?menu=raidpub"
    }
}
