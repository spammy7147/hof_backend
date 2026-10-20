package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.dto.*
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.history.*
import app.spammy.hof.automation.outbox.*
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.*
import app.spammy.hof.quest.parser.QuestPageParser
import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import jakarta.persistence.EntityManager
import java.io.IOException
import java.time.OffsetDateTime
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
abstract class ColosseumAutomationIntegrationTest {
    @Autowired private lateinit var em: EntityManager
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var clock: AutomationRecoveryIntegrationTest.RecoveryClock
    @Autowired private lateinit var publisher: AutomationOutboxPublisher
    @Autowired private lateinit var outbox: AutomationOutboxQueryRepository
    @Autowired private lateinit var transport: AutomationRecoveryIntegrationTest.ConsumerReplayTransport
    @Autowired private lateinit var application: UnifiedAutomationService
    @Autowired private lateinit var journal: AutomationDecisionJournal
    @Autowired private lateinit var convergence: ConvergenceStore
    @MockitoSpyBean private lateinit var work: AutomationWorkSessionService
    @MockitoBean private lateinit var gateway: HofGateway
    @MockitoBean private lateinit var preflight: AutomationDailyPreflight
    @MockitoBean private lateinit var authorization: AccountExecutionAuthorizationReader
    private var accountId = 0L
    private var entryId = 0L
    private var cooldown = false
    private var missingTeam = false
    private var incomplete = false
    private var loseResponse = false
    private var changeTeamBeforeSubmit = false
    private var rankReads = 0
    private var claimed = false
    private val requests = mutableListOf<HofRequest>()
    private val rankUrl = "https://hof.zerosic.com/index.php?menu=rank"
    private val questUrl = "https://hof.zerosic.com/index.php?menu=quest"

    @BeforeEach fun setup() {
        transport.delivered.clear()
        clock.current = Instant.parse("2026-10-20T01:00:00Z")
        TransactionTemplate(transactions).executeWithoutResult {
            val account = HofAccountEntity(loginId = "colosseum-${UUID.randomUUID()}", encryptedPassword = "fixture", createdAt = clock.now())
            em.persist(account); em.flush(); accountId = account.id
            em.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = clock.now()))
            em.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1, timeCurrent = 100, timeMax = 200, work = "", auction = "", observedAt = clock.now()))
        }
        Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
        Mockito.`when`(preflight.ensureReady(accountId)).thenReturn(AutomationDailyPreflight.Result.Ready)
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            assertEquals(HofRequestOrigin.AUTOMATION, request.origin)
            val html = when {
                request.url.contains("menu=rank") && request.method == HofHttpMethod.POST -> {
                    assertEquals(listOf(HofFormField("ChallengeRank", "challenge!")), request.formEntries)
                    cooldown = true
                    if (loseResponse) throw IOException("fixture response lost")
                    if (incomplete) "<div id='menu2'>결과 확인 중</div>" else fixture("town/pvp/rank-result.html")
                }
                request.url.contains("menu=rank") -> {
                    rankReads++
                    rankPage()
                }
                request.url.contains("menu=quest") -> {
                    if (request.formFields["action"] == "complete") claimed = true
                    questPage()
                }
                else -> error("Unexpected HOF request: ${request.url}")
            }
            HofHttpResponse(200, request.url, html, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
        application.createEntry(accountId, CreateAutomationEntryRequest(AutomationType.COLOSSEUM))
        application.updateColosseum(accountId, UpdateColosseumAutomationRequest(true))
        entryId = application.getTyped(accountId).entries.single().id
        application.createEntry(accountId, CreateAutomationEntryRequest(AutomationType.QUEST))
        val quest = QuestPageParser().parseObservation(questPage(), questUrl).quests.single { it.actionNo == "claim-a" }
        application.updateQuest(accountId, UpdateQuestAutomationRequest(true, listOf(
            QuestSelectionRequest(quest.questKey, quest.displayCode, quest.name, true, 0, emptyList()),
        )))
        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            application.createEntry(accountId, CreateAutomationEntryRequest(AutomationType.COLOSSEUM))
        }
    }

    @AfterEach fun cleanup() {
        transport.delivered.forEach { jdbc.update("delete from automation_consumed_events where event_id = ?", it) }
        if (accountId != 0L) jdbc.update("delete from hof_accounts where id = ?", accountId)
    }

    @Test fun `실제 wakeup에서 콜로세움을 한 번 수행한 뒤 쿨다운을 건너뛰고 다음 항목을 실행한다`() {
        application.startTyped(accountId)
        consumeUntil { rankPosts().size == 1 }
        consumeUntil { claimed }
        assertEquals(1, rankPosts().size)
        assertEquals(0, jdbc.queryForObject("select count(*) from automation_work_sessions where account_id = ? and status = 'RUNNING'", Int::class.java, accountId))
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.any { cycle -> cycle.events.any { it.type == AutomationType.COLOSSEUM } })
        cooldown = false
        consumeUntil { rankPosts().size == 2 }
        assertEquals(2, rankPosts().size, "다음 판단에서 새 도전이 실행되어야 한다.")
    }

    @Test fun `팀 누락은 다른 실행 가능 항목을 막지 않는다`() {
        missingTeam = true
        application.startTyped(accountId)
        consumeUntil { claimed }
        assertTrue(rankPosts().isEmpty())
    }

    @Test fun `콜로세움 비활성화 뒤 다른 항목은 계속 실행한다`() {
        application.updateColosseum(accountId, UpdateColosseumAutomationRequest(false))
        application.startTyped(accountId)
        consumeUntil { claimed }
        assertTrue(rankPosts().isEmpty())
        assertFalse(application.getTyped(accountId).entries.single { it.type == AutomationType.COLOSSEUM }.enabled)
    }

    @Test fun `캡차 전투 관문은 콜로세움 도전을 막고 비전투 퀘스트는 허용한다`() {
        convergence.openBattleGate(accountId, null, "CAPTCHA_REQUIRED", clock.now())
        application.startTyped(accountId)
        consumeUntil { claimed }
        assertTrue(rankPosts().isEmpty())
        assertNotNull(convergence.activeBattleGate(accountId))
    }

    @Test fun `제출 전 팀이 바뀌면 이전 관측으로 전투를 보내지 않는다`() {
        changeTeamBeforeSubmit = true
        application.startTyped(accountId)
        consumeUntil { claimed }
        assertTrue(rankReads >= 2)
        assertTrue(rankPosts().isEmpty())
    }

    @Test fun `일시정지 중 새 도전을 보내지 않고 재개 뒤 최신 상태에서 판단한다`() {
        application.startTyped(accountId)
        consumeUntil { rankPosts().size == 1 }
        application.pauseTyped(accountId)
        cooldown = false
        publisher.publishBatch()
        assertEquals(1, rankPosts().size)
        assertEquals("PAUSED", application.getTyped(accountId).runtime.lifecycle.name)
        application.resumeTyped(accountId)
        consumeUntil { rankPosts().size == 2 }
    }

    @Test fun `전투 응답 유실 뒤 저장 요청을 재전송하지 않고 독립 항목을 실행한다`() {
        loseResponse = true
        application.startTyped(accountId)
        consumeUntil { claimed }
        assertEquals(1, rankPosts().size)
        assertTrue(jdbc.queryForObject("select count(*) from typed_automation_action_runs where account_id = ? and action_kind = 'COLOSSEUM'", Int::class.java, accountId)!! >= 1)
    }

    @Test fun `불완전 직접 응답을 성공 처리하거나 반복 전송하지 않는다`() {
        incomplete = true
        application.startTyped(accountId)
        consumeUntil { claimed }
        assertEquals(1, rankPosts().size)
        assertEquals(0, jdbc.queryForObject("select count(*) from typed_automation_action_runs where account_id = ? and action_kind = 'COLOSSEUM' and status = 'SUCCEEDED'", Int::class.java, accountId))
    }

    @Test fun `로컬 완료 실패 뒤 저장된 응답으로 복구하고 전투는 재전송하지 않는다`() {
        var failed = false
        Mockito.doAnswer { call ->
            if (!failed) { failed = true; throw IllegalStateException("fixture local completion unavailable") }
            call.callRealMethod()
        }.`when`(work).completeColosseumCycle(Mockito.eq(accountId), Mockito.eq(entryId), Mockito.anyString())
        application.startTyped(accountId)
        consumeUntil { claimed }
        consumeUntil { jdbc.queryForObject("select count(*) from typed_automation_action_runs where account_id = ? and action_kind = 'COLOSSEUM' and status = 'SUCCEEDED'", Int::class.java, accountId) == 1 }
        assertTrue(failed)
        assertEquals(1, rankPosts().size)
        assertEquals(0, jdbc.queryForObject("select count(*) from automation_work_sessions where account_id = ? and work_type = 'COLOSSEUM' and status = 'RUNNING'", Int::class.java, accountId))
    }

    private fun consumeUntil(done: () -> Boolean) {
        repeat(40) { if (done()) return; consumeNextWake() }
        assertTrue(done(), "후속 wakeup이 실제 행동으로 이어져야 한다. runtime=${application.getTyped(accountId).runtime}")
    }
    private fun consumeNextWake() {
        val next = jdbc.queryForObject("select min(available_at) from automation_outbox where account_id = ? and topic = ? and published_at is null",
            OffsetDateTime::class.java, accountId, AutomationOutboxService.WAKEUP_TOPIC)?.toInstant()
        assertNotNull(next)
        clock.current = maxOf(clock.now(), next)
        publisher.publishBatch()
        assertTrue(transport.delivered.all { outbox.consumed(it) })
    }
    private fun rankPosts() = requests.filter { it.url.contains("menu=rank") && it.method == HofHttpMethod.POST }
    private fun rankPage(): String {
        var html = fixture("town/pvp/rank.html")
        if (cooldown) html = html.replace("name=\"ChallengeRank\"", "name=\"ChallengeRank\" disabled").replace("<h4>랭킹", "<div>Time left to Next : 0:00:30</div><h4>랭킹")
        if (missingTeam) html = html.replace(" checked", "")
        if (changeTeamBeforeSubmit && rankReads >= 2) html = html.replace("name=\"char_105\" value=\"1\" checked", "name=\"char_105\" value=\"1\"")
        return html
    }
    private fun questPage(): String {
        val row = if (claimed) "" else """<tr><td class="td7s">[0777] 독립 퀘스트</td><td>미션 : 즉시 완료</td><td>-</td><td>Time x10</td><td><a href="?menu=quest&amp;action=complete&amp;no=claim-a">완료</a></td></tr>"""
        return "<div id='menu2'>Funds : $ 1 Time : 100/200</div>" + fixture("quest/quest-complete-empty.html").replaceFirst("</table>", "$row</table>")
    }
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/$name")).readText()
    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, rankUrl)
}

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
class ActiveColosseumAutomationIntegrationTest : ColosseumAutomationIntegrationTest()
@SpringBootTest(properties = ["hof.automation-convergence.mode=SHADOW"])
class ShadowColosseumAutomationIntegrationTest : ColosseumAutomationIntegrationTest()
@SpringBootTest(properties = ["hof.automation-convergence.mode=LEGACY"])
class LegacyColosseumAutomationIntegrationTest : ColosseumAutomationIntegrationTest()
