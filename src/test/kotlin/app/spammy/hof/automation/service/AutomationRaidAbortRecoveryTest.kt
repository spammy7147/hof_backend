package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.raid.RaidCycleStore
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.parser.HomePageParser
import app.spammy.hof.town.raid.model.RaidStatus
import app.spammy.hof.town.raid.parser.RaidPubParser
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.*
import java.time.Instant

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationRaidAbortRecoveryTest : AutomationRaidAbortRecoveryTest() {
    override val mode = AutomationConvergenceMode.LEGACY
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationRaidAbortRecoveryTest : AutomationRaidAbortRecoveryTest() {
    override val mode = AutomationConvergenceMode.SHADOW

    @Test
    fun `복원 결과의 SHADOW 해석 실패가 실제 중단 완료와 독립 판단을 막지 않는다`() {
        verifyStoredAbort(RaidCycleAbortReason.CLOSED, joined = true, failShadowComparison = true)
    }
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationRaidAbortRecoveryTest : AutomationRaidAbortRecoveryTest() {
    override val mode = AutomationConvergenceMode.ACTIVE
}

@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationRaidAbortRecoveryTest : AutomationRecoveryFixture() {
    @Autowired private lateinit var runtime: TypedAutomationRuntimeService
    @Autowired private lateinit var results: AutomationResultCoordinator
    @Autowired private lateinit var raidStore: RaidCycleStore
    @MockitoSpyBean private lateinit var interpreter: ProductionActionEvidenceInterpreter

    @ParameterizedTest
    @CsvSource("CLOSED,true", "REGISTRATION_LOST,true", "CLOSED,false", "REGISTRATION_LOST,false")
    fun `정책 문맥을 가진 저장 레이드 중단을 복원하고 독립 자택과 다음 판단을 이어간다`(reason: RaidCycleAbortReason, joined: Boolean) =
        verifyStoredAbort(reason, joined)

    protected fun verifyStoredAbort(reason: RaidCycleAbortReason, joined: Boolean, failShadowComparison: Boolean = false) {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        val homeUrl = "https://hof.zerosic.com/index.php?menu=quest2"
        var homeAccepted = false
        fun homePage(): String {
            val heading = if (homeAccepted) "진행중인 작업 목록" else "수락 가능한 작업 목록"
            val action = if (homeAccepted) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"
            return """<div id='menu2'>Funds : $ 1 Time : 100/100</div><h4>$heading</h4><table>
                <tr><td>[A] 독립 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>$action</td></tr></table>"""
        }
        val home = HomePageParser().parse(HomeMode.HOME, homePage(), homeUrl, HofFormParser().parse(homePage(), homeUrl)).quests.single()
        val raidPage = requireNotNull(javaClass.getResource("/fixtures/town/raid/raidpub.html")).readText()
            .replace("현재사용자", "테스트")
            .let { if (joined) it else it.replace("[《테스트 길드》테스트]", "[다른 참가자]") }
            .replace("Funds : $ 1,000", "Funds : $ 1,000 Time : 100/100")
            .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
            .replace("현재 상태 : 모집 중", "현재 상태 : 종료")
            .replace("name=\"reward_nonce\" value=\"보상 확인\"", "name=\"refresh_nonce\" value=\"상태 갱신\"")
        val raidUrl = "https://hof.zerosic.com/index.php?menu=raidpub"
        val parsedRaid = RaidPubParser().parse(raidPage, raidUrl, HofFormParser().parse(raidPage, raidUrl))
        assertTrue(parsedRaid.pageComplete)
        assertEquals(RaidStatus.CLOSED, parsedRaid.raids.single { it.id == "RaidGoblin" }.status)
        assertEquals(joined, parsedRaid.raids.single { it.id == "RaidGoblin" }.joined)
        val cycleId = assertNotNull(TransactionTemplate(transactions).execute {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.RAID
            entry.singletonTypeMarker = AutomationType.RAID
            entityManager.persist(RaidAutomationTargetEntity(entry = entry, raidId = "RaidGoblin", displayName = "고블린 전투 마차",
                presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
            val cycle = RaidAutomationCycleEntity(account = entry.account, entry = entry, raidId = "RaidGoblin",
                raidName = "고블린 전투 마차", status = RaidAutomationCycleStatus.IN_BATTLE,
                lastObservedStatus = "전투 중", startedAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(cycle)
            val homeEntry = AutomationEntryEntity(account = entry.account, type = AutomationType.HOME_QUEST,
                priority = 1, enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(homeEntry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = homeEntry, questId = home.id,
                questName = home.name, enabled = true, sourceOrder = 0))
            entityManager.flush()
            cycle.id
        })
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            if (request.method == HofHttpMethod.POST) {
                assertEquals("상태 갱신", request.formFields["refresh_nonce"], "후속 새 갱신 이외의 원격 행동을 제출하면 안 된다.")
            }
            val body = when {
                request.url.contains("menu=quest2") -> {
                    if (request.formFields["action"] == "get") {
                        assertEquals("A", request.formFields["no"])
                        homeAccepted = true
                    }
                    homePage()
                }
                request.url.contains("menu=raidpub") || request.formFields.containsKey("refresh_nonce") -> raidPage
                request.url.endsWith("?raid_hunt") -> requireNotNull(javaClass.getResource("/fixtures/raid/raid-complete-absent.html")).readText()
                else -> error("예상하지 않은 HOF 요청: ${request.url}")
            }
            HofHttpResponse(200, if (request.formFields.containsKey("refresh_nonce")) "https://hof.zerosic.com/index.php?menu=raidpub" else request.url, body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())

        // 과거 저장 형식의 로컬 중단 의도만 준비한다. 현재 selector에 새 중단 행동을 추가하지 않는다.
        val identity = "stored-raid-abort-${reason.name}"
        val stored = assertNotNull(results.newSelection(StoredTypedAutomationAction(entryId, identity,
            StoredTypedActionPayload.RaidCycleAbort("RaidGoblin", reason)))).stored
        val acquired = assertIs<TypedRuntimeAcquisition.Acquired>(runtime.acquire(accountId)).execution
        val prepared = assertIs<TypedRuntimePreparation.Ready>(runtime.persistPrepared(acquired, stored)).execution
        assertIs<TypedRuntimeSubmission.Started>(runtime.beginSubmission(prepared))
        assertTrue(runtime.complete(prepared, TypedRuntimeOutcome.SubmissionAmbiguous("로컬 중단 저장 완료 여부를 복원한다.")).applied)
        assertEquals("RECONCILING", runs().single()["status"])
        assertEquals(cycleId, assertNotNull(raidStore.load(accountId).openCycle).id)
        assertTrue(requests.isEmpty())

        if (failShadowComparison) {
            val selection = assertNotNull(stored.policyContext).selection(entryId, identity)
            Mockito.doThrow(IllegalStateException("SHADOW 저장 정책 해석 실패"))
                .`when`(interpreter).fromReconciliation(
                    Mockito.eq(selection) ?: selection,
                    Mockito.any<AmbiguousActionResolution>() ?: AmbiguousActionResolution.Applied(TypedAutomationExecution.Completed),
                    Mockito.any<Instant>() ?: clock.now(),
                )
        }

        consumeNextWake()

        assertTrue(requests.isEmpty(), "저장된 로컬 중단의 복원 자체는 HOF 요청을 만들지 않는다.")

        val expectedStatus = when (reason) {
            RaidCycleAbortReason.CLOSED -> "ABORTED_CLOSED"
            RaidCycleAbortReason.REGISTRATION_LOST -> "ABORTED_REGISTRATION_LOST"
        }
        fun closedCycle() = jdbc.queryForMap("select status, open_marker, finished_at from raid_automation_cycles where account_id = ? and id = ?", accountId, cycleId)
        val closed = closedCycle()
        assertEquals(expectedStatus, closed["status"])
        assertNull(closed["open_marker"])
        assertNotNull(closed["finished_at"])
        fun assertOriginalResult(afterFollowup: Boolean = false) {
            val row = runs().single { it["execution_identity"] == identity }
            assertEquals(if (mode == AutomationConvergenceMode.ACTIVE) "AMBIGUOUS" else "SUCCEEDED", row["status"])
            val record = store.get(accountId, identity)
            if (mode == AutomationConvergenceMode.ACTIVE) {
                assertNotNull(record)
                assertEquals(AutomationActionKind.RAID_CYCLE_ABORT, record.selection.actionKind)
                assertEquals(ActionConvergenceResult.APPLIED, record.result)
            } else assertNull(record)
            val shadowResults = when {
                mode != AutomationConvergenceMode.SHADOW -> emptyList()
                !failShadowComparison -> listOf("APPLIED")
                // 비교 실패로 미완료인 진단은 같은 scope의 다음 선택이 대체한다. 실제 성공은 유지한다.
                afterFollowup -> listOf("SUPERSEDED")
                else -> emptyList()
            }
            assertEquals(shadowResults,
                jdbc.queryForList("select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                    String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity)))
        }
        assertOriginalResult()
        val originalHistory = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
        assertTrue(originalHistory.any { it.actionKind == "CYCLE_ABORT" && it.targetKey == "RaidGoblin" })

        repeat(4) { consumeNextWake() }

        assertTrue(homeAccepted, journal.page(accountId, AutomationHistoryQuery()).cycles.toString())
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.formFields.containsKey("refresh_nonce") })
        assertOriginalResult(afterFollowup = true)
        assertEquals(closed, closedCycle())
        assertNull(raidStore.load(accountId).openCycle)
        assertEquals(1, jdbc.queryForObject("select count(*) from raid_automation_cycles where account_id = ?", Int::class.java, accountId))
        val latestEvents = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
        val closedWait = latestEvents.single { it.reasonCode == "RAID_CLOSED_RECHECK" }
        assertEquals(closedWait.occurredAt.plusSeconds(30), closedWait.nextRunAt)
        originalHistory.forEach { event -> assertEquals(event, latestEvents.single { it.id == event.id }) }
        assertEquals(0, runningWorkCount())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
    }
}
