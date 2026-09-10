package app.spammy.hof.automation.service

import app.spammy.hof.automation.convergence.ActionConvergenceResult
import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.AutomationActionKind
import app.spammy.hof.automation.convergence.AutomationConvergenceMode
import app.spammy.hof.automation.convergence.AutomationConvergenceController
import app.spammy.hof.automation.convergence.ProductionEvidenceShapes
import app.spammy.hof.automation.entity.TypedAutomationActionStatus
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.HomeQuestAutomationSelectionEntity
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.model.HomeQuestState
import app.spammy.hof.town.home.parser.HomePageParser
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.*

@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyAutomationHomeDirectResponseRecoveryTest : AutomationHomeDirectResponseRecoveryTest() {
    override val mode = AutomationConvergenceMode.LEGACY
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowAutomationHomeDirectResponseRecoveryTest : AutomationHomeDirectResponseRecoveryTest() {
    override val mode = AutomationConvergenceMode.SHADOW
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveAutomationHomeDirectResponseRecoveryTest : AutomationHomeDirectResponseRecoveryTest() {
    override val mode = AutomationConvergenceMode.ACTIVE
}

@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class AutomationHomeDirectResponseRecoveryTest : AutomationRecoveryFixture() {
    @MockitoSpyBean private lateinit var results: AutomationResultCoordinator
    @Autowired private lateinit var mapper: tools.jackson.databind.ObjectMapper
    @Autowired private lateinit var convergence: AutomationConvergenceController

    @ParameterizedTest
    @ValueSource(strings = ["ACCEPT", "CLAIM"])
    fun `수신한 자택 행동의 로컬 완료가 실패해도 원래 성공과 독립 자택을 복구한다`(originalAction: String) {
        verifyRecovery(originalAction)
    }

    @ParameterizedTest
    @CsvSource("ACCEPT,FINGERPRINT", "CLAIM,FINGERPRINT", "ACCEPT,KIND", "CLAIM,KIND")
    fun `손상되거나 종류가 다른 자택 응답은 원래 대상만 보류하고 명시적 해제 뒤에도 재전송하지 않는다`(
        originalAction: String,
        corruption: String,
    ) {
        verifyRecovery(originalAction, corruption)
    }

    private fun verifyRecovery(originalAction: String, corruption: String? = null) {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        val url = "https://hof.zerosic.com/index.php?menu=quest2"
        val accepted = mutableSetOf<String>()
        val originalKind = if (originalAction == "ACCEPT") AutomationActionKind.HOME_ACCEPT else AutomationActionKind.HOME_CLAIM
        val originalPoststate = if (originalAction == "ACCEPT") HomeQuestState.ACTIVE else HomeQuestState.WAITING
        fun page(): String = "<div id='menu2'>Funds : $ 1 Time : 100/100</div>" + listOf("A", "B").joinToString("") { id ->
            val claiming = id == "A" && originalAction == "CLAIM"
            val heading = if (id !in accepted) "수락 가능한 작업 목록" else if (claiming) "대기중인 작업 목록" else "진행중인 작업 목록"
            val action = if (id in accepted) "-" else "<a href='?menu=quest2&amp;action=${if (claiming) "complete" else "get"}&amp;no=$id'>${if (claiming) "보상 수령" else "수락"}</a>"
            """<h4>$heading</h4><table><tr><td>[$id] 응답 복구 $id</td><td>미션 0/1</td>
                <td>-</td><td>-</td><td>$action</td></tr></table>"""
        }
        fun parsed() = HomePageParser().parse(HomeMode.HOME, page(), url, HofFormParser().parse(page(), url)).quests
        val quests = parsed()
        assertEquals(2, quests.size)
        assertTrue(quests.all { it.stateObserved })
        assertEquals(if (originalAction == "ACCEPT") HomeQuestState.AVAILABLE else HomeQuestState.CLAIMABLE, quests.first().state)
        assertEquals(HomeQuestState.AVAILABLE, quests.last().state)
        val originalQuest = quests.first()
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.HOME_QUEST
            entry.singletonTypeMarker = AutomationType.HOME_QUEST
            quests.forEachIndexed { index, quest ->
                entityManager.persist(HomeQuestAutomationSelectionEntity(entry = entry, questId = quest.id,
                    questName = quest.name, enabled = true, sourceOrder = index))
            }
        }
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            assertEquals(HofHttpMethod.GET, request.method)
            assertTrue(request.url.contains("menu=quest2"))
            request.formFields["action"]?.let { action ->
                val target = assertNotNull(request.formFields["no"])
                assertTrue(target in setOf("A", "B"))
                assertEquals(if (target == "A" && originalAction == "CLAIM") "complete" else "get", action)
                assertTrue(accepted.add(target), "같은 자택 행동을 다시 제출하면 안 된다: $target")
            }
            HofHttpResponse(200, url, page(), emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())

        var failureInjected = false
        var originalIdentity: String? = null
        val managedPlaceholder = Mockito.mock(ManagedAutomationAction::class.java)
        Mockito.doAnswer { invocation ->
            val managed = invocation.getArgument<ManagedAutomationAction>(0)
            val payload = managed.storedAction.payload as? StoredTypedActionPayload.HomeQuest
            if (!failureInjected && payload?.questId == originalQuest.id) {
                assertEquals(setOf("A"), accepted)
                assertIs<AutomationActionEvidence.DirectApplied>(invocation.getArgument<AutomationActionEvidence?>(2))
                assertEquals(originalPoststate, parsed().single { it.id == originalQuest.id }.state)
                originalIdentity = managed.storedAction.executionIdentity
                failureInjected = true
                throw IllegalStateException("수신된 자택 응답의 첫 로컬 완료 실패")
            }
            invocation.callRealMethod()
        }.`when`(results).applyDirect(
            Mockito.any<ManagedAutomationAction>() ?: managedPlaceholder,
            Mockito.any<TypedAutomationExecution>() ?: TypedAutomationExecution.Completed,
            Mockito.nullable(AutomationActionEvidence::class.java),
            Mockito.nullable(Long::class.javaObjectType),
        )

        wakeups.wake(accountId, "HOME_DIRECT_RESPONSE_RECOVERY")
        consumeNextWake()
        assertTrue(failureInjected, "실제 수신 응답의 로컬 완료 지점에서만 실패를 주입해야 한다.")
        val identity = assertNotNull(originalIdentity)
        val receiptJson = assertNotNull(jdbc.queryForObject(
            "select direct_response_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
            String::class.java, accountId, identity))
        val receipt = mapper.readValue(receiptJson, StoredAutomationDirectResponse::class.java)
        val received = assertIs<AutomationDirectResponse.HomePage>(receipt.response).quests.single { it.id == originalQuest.id }
        assertEquals(originalPoststate, received.state)
        assertTrue(received.stateObserved, "JSON 왕복 뒤에도 실제 관측 여부를 보존해야 한다.")
        if (corruption == "FINGERPRINT") {
            jdbc.update("update typed_automation_action_runs set direct_response_json = '{}' where account_id = ? and execution_identity = ?",
                accountId, identity)
        } else if (corruption == "KIND") {
            // 올바른 지문의 다른 응답 종류를 주입하여 단순 JSON·지문 오류와 종류 결합 위반을 구분한다.
            val wrongKind = mapper.writeValueAsString(receipt.copy(response = AutomationDirectResponse.QuestPage(emptyList(), true)))
            assertIs<AutomationDirectResponse.QuestPage>(mapper.readValue(wrongKind, StoredAutomationDirectResponse::class.java).response)
            val actionFingerprint = assertNotNull(jdbc.queryForObject(
                "select action_fingerprint from typed_automation_action_runs where account_id = ? and execution_identity = ?",
                String::class.java, accountId, identity))
            val fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("$accountId\n$identity\n$actionFingerprint\n$wrongKind".toByteArray(Charsets.UTF_8)))
            jdbc.update("update typed_automation_action_runs set direct_response_json = ?, direct_response_fingerprint = ? where account_id = ? and execution_identity = ?",
                wrongKind, fingerprint, accountId, identity)
        }
        clock.current = clock.now().plusSeconds(11)
        repeat(4) { consumeNextWake() }

        fun originalEvents() = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .filter { it.targetKey == originalQuest.id && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }
        fun assertOriginalResult() {
            assertEquals(if (corruption == null) "SUCCEEDED" else "RESULT_HELD",
                runs().single { it["execution_identity"] == identity }["status"], runs().toString())
            if (mode == AutomationConvergenceMode.ACTIVE) {
                val record = assertNotNull(store.get(accountId, identity))
                assertEquals(originalKind, record.selection.actionKind)
                assertEquals(ActionConvergenceResult.APPLIED, record.result)
            } else assertNull(store.get(accountId, identity))
            val shadow = jdbc.queryForList(
                "select new_result from automation_convergence_shadow_evaluations where account_id = ? and execution_identity_hash = ?",
                String::class.java, accountId, ProductionEvidenceShapes.fingerprint(identity))
            assertEquals(if (mode == AutomationConvergenceMode.SHADOW && corruption == null) listOf("APPLIED") else emptyList(), shadow)
            assertEquals(if (corruption == null) 1 else 0, originalEvents().size)
            assertEquals(setOf("A", "B"), accepted)
            for (target in listOf("A", "B")) assertEquals(1, requests.count {
                it.formFields["action"] != null && it.formFields["no"] == target
            })
            assertEquals(0, runningWorkCount())
            assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?",
                String::class.java, accountId))
        }
        assertOriginalResult()
        val firstSuccess = originalEvents().singleOrNull()
        if (corruption != null) {
            val held = convergence.get(accountId).localResults.single()
            assertEquals(originalKind.name, held.actionKind)
            assertEquals(TypedAutomationActionStatus.RESULT_HELD, held.status)
            assertEquals("LOCAL_RESULT_INTEGRITY_FAILED", held.reasonCode)
            assertTrue(held.canAllowFreshDecision)
            val evidenceId = assertNotNull(held.evidenceCaseId)
            val snippet = assertNotNull(jdbc.queryForObject(
                "select sanitized_snippet from automation_evidence_cases where id = ?", String::class.java, evidenceId))
            assertTrue(snippet.contains("receiptValid=${corruption == "KIND"}"), snippet)
            assertTrue(held.impactScope.contains(originalQuest.id), held.impactScope)
            val before = requests.size
            assertTrue(convergence.allowLocalFreshDecision(accountId, held.actionId).localResults.isEmpty())
            assertEquals(before, requests.size, "보류 해제 요청 자체는 원격 행동을 제출하지 않는다.")
        }
        repeat(2) { consumeNextWake() }
        assertOriginalResult()
        assertEquals(firstSuccess, originalEvents().singleOrNull())
        assertTrue(convergence.get(accountId).localResults.isEmpty())
    }
}
