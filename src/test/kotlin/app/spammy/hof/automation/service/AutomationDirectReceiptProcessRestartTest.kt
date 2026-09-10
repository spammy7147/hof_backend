package app.spammy.hof.automation.service

import app.spammy.hof.HofApplication
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.auth.service.RefreshTokenService
import app.spammy.hof.auth.service.JwtTokenService
import app.spammy.hof.auth.config.AuthProperties
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.outbox.AutomationOutboxPublisher
import app.spammy.hof.automation.outbox.AutomationOutboxTransport
import app.spammy.hof.automation.outbox.AutomationOutboxEntity
import app.spammy.hof.automation.outbox.AutomationOutboxQueryRepository
import app.spammy.hof.automation.outbox.AutomationOutboxPublishMarker
import app.spammy.hof.automation.outbox.AutomationWakeupEvent
import app.spammy.hof.automation.outbox.AutomationConsumedEventService
import app.spammy.hof.automation.lease.AccountAutomationLeaseService
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.quest.parser.QuestPageParser
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.parser.HomePageParser
import jakarta.persistence.EntityManager
import java.net.URLClassLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Instant
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.aopalliance.intercept.MethodInterceptor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mockito
import org.springframework.aop.framework.ProxyFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper

/** 실제 수신·후처리 seam에서 JVM을 종료하고 새 Spring/JPA 프로세스로 재개한다. */
class AutomationDirectReceiptProcessRestartTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @CsvSource("ACTIVE,history-after-result", "SHADOW,history-after-result", "LEGACY,history-after-result",
        "ACTIVE,history-during-insert", "SHADOW,history-during-insert", "LEGACY,history-during-insert")
    fun `결과 확정 직후 종료해도 성공 이력을 복구하고 다음 판단을 이어간다`(mode: String, interruption: String) {
        assertEquals(71, runProcess("crash", mode, interruption))
        val mapper = jacksonObjectMapper()
        val committed = mapper.readTree(directory.resolve("history-commit.json").readText())
        assertEquals("SUCCEEDED", committed["status"].asString(), committed.toString())
        assertEquals(1, committed["processed"].asInt(), committed.toString())
        assertEquals(if (interruption == "history-during-insert") 1 else 0, committed["history"].asInt(), committed.toString())
        for (phase in listOf("resume", "resume-again")) {
            assertEquals(0, runProcess(phase, mode, interruption))
            val result = mapper.readTree(directory.resolve("result.json").readText())
            assertEquals("SUCCEEDED", result["actionStatus"].asString(), result.toString())
            if (mode == "ACTIVE") assertEquals("APPLIED", result["originalConvergence"].asString(), result.toString())
            assertEquals(1, result["originalAcceptResults"].asInt(), result.toString())
            assertEquals(1, result["acceptSuccessHistory"].asInt(), result.toString())
            assertEquals(1, result["questSubmissions"].asInt(), result.toString())
            assertEquals(1, result["homeSubmissions"].asInt(), result.toString())
        }
    }

    @Test
    fun `늦은 원본의 행동 번호 변경은 원래 성공 없이 새 수락으로 이어진다`() {
        assertEquals(71, runProcess("crash", "ACTIVE", "late-advanced"))
        val mapper = jacksonObjectMapper()
        for (phase in listOf("resume", "resume-again")) {
            assertEquals(0, runProcess(phase, "ACTIVE", "late-advanced"))
            val result = mapper.readTree(directory.resolve("result.json").readText())
            assertEquals("FAILED", result["actionStatus"].asString(), result.toString())
            assertEquals("SUPERSEDED", result["originalConvergence"].asString(), result.toString())
            assertEquals(0, result["originalAcceptResults"].asInt(), result.toString())
            assertEquals(1, result["questSubmissions"].asInt(), result.toString())
            assertEquals(1, result["advancedQuestSubmissions"].asInt(), result.toString())
            assertEquals(0, result["successHistoryBeforeAdvancedSubmission"].asInt(), result.toString())
            assertEquals(1, result["acceptSuccessHistory"].asInt(), result.toString())
            assertEquals(1, result["homeSubmissions"].asInt(), result.toString())
        }
    }

    @ParameterizedTest
    @CsvSource("ACTIVE", "SHADOW", "LEGACY")
    fun `복구가 응답 부재를 읽은 뒤 저장된 원래 응답도 재시작 후 반영한다`(mode: String) {
        assertEquals(71, runProcess("crash", mode, "late-during-reconciliation"))
        val mapper = jacksonObjectMapper()
        val interrupted = mapper.readTree(directory.resolve("late-reconciliation.json").readText())
        assertEquals(1, interrupted["receipts"].asInt(), interrupted.toString())
        assertEquals(0, interrupted["processed"].asInt(), interrupted.toString())
        for (phase in listOf("resume", "resume-again")) {
            assertEquals(0, runProcess(phase, mode, "late-during-reconciliation"))
            val result = mapper.readTree(directory.resolve("result.json").readText())
            assertEquals("SUCCEEDED", result["actionStatus"].asString(), result.toString())
            if (mode == "ACTIVE") assertEquals("APPLIED", result["originalConvergence"].asString(), result.toString())
            assertEquals(1, result["originalAcceptResults"].asInt(), result.toString())
            assertEquals(1, result["acceptSuccessHistory"].asInt(), result.toString())
            assertEquals(1, result["questSubmissions"].asInt(), result.toString())
            assertEquals(1, result["homeSubmissions"].asInt(), result.toString())
        }
    }

    @Test
    fun `종결 뒤 도착한 불완전 응답은 재시작 후 원래 보류와 독립 판단을 보존한다`() {
        assertEquals(71, runProcess("crash", "ACTIVE", "late-incomplete"))
        val mapper = jacksonObjectMapper()
        for (phase in listOf("resume", "resume-again")) {
            assertEquals(0, runProcess(phase, "ACTIVE", "late-incomplete"))
            val result = mapper.readTree(directory.resolve("result.json").readText())
            assertEquals("AMBIGUOUS", result["actionStatus"].asString(), result.toString())
            assertEquals("HELD", result["originalConvergence"].asString(), result.toString())
            assertEquals(0, result["originalAcceptResults"].asInt(), result.toString())
            assertEquals(0, result["acceptSuccessHistory"].asInt(), result.toString())
            assertEquals(1, result["questSubmissions"].asInt(), result.toString())
            assertEquals(1, result["homeSubmissions"].asInt(), result.toString())
        }
    }

    @ParameterizedTest
    @CsvSource("ACTIVE", "SHADOW", "LEGACY")
    fun `다른 작업자가 종결한 뒤 도착한 원래 응답도 재시작 후 반영한다`(mode: String) {
        assertEquals(71, runProcess("crash", mode, "late-before"))
        val mapper = jacksonObjectMapper()
        val terminal = mapper.readTree(directory.resolve("late-terminal.json").readText())
        assertEquals("AMBIGUOUS", terminal["status"].asString())
        assertEquals(0, terminal["processed"].asInt())
        for (phase in listOf("resume", "resume-again")) {
            assertEquals(0, runProcess(phase, mode, "late-before"))
            val result = mapper.readTree(directory.resolve("result.json").readText())
            assertEquals("SUCCEEDED", result["actionStatus"].asString(), result.toString())
            if (mode == "ACTIVE") assertEquals("APPLIED", result["originalConvergence"].asString(), result.toString())
            assertEquals(1, result["originalQuestCycles"].asInt(), result.toString())
            assertEquals(1, result["originalAcceptResults"].asInt(), result.toString())
            assertEquals(1, result["acceptSuccessHistory"].asInt(), result.toString())
            assertEquals(1, result["questSubmissions"].asInt(), result.toString())
            assertEquals(1, result["homeSubmissions"].asInt(), result.toString())
        }
    }

    @Test
    fun `직접 적용 저장이 실패하면 후처리 대기도 rollback하고 원래 응답으로 재시작한다`() {
        assertEquals(71, runProcess("crash", "ACTIVE", "projection-result-write"))
        assertEquals(0, runProcess("resume", "ACTIVE", "projection-result-write"))
        val pending = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals("RESULT_PENDING", pending["actionStatus"].asString(), pending.toString())
        assertEquals("APPLIED", pending["originalConvergence"].asString(), pending.toString())
        assertEquals(0, pending["questCycles"].asInt(), pending.toString())
        assertEquals(0, pending["acceptSuccessHistory"].asInt(), pending.toString())
        assertEquals(1, pending["questSubmissions"].asInt(), pending.toString())
        assertEquals(1, pending["homeSubmissions"].asInt(), pending.toString())
        assertEquals(0, runProcess("repair", "ACTIVE", "projection-result-write"))
        val recovered = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals("SUCCEEDED", recovered["actionStatus"].asString(), recovered.toString())
        assertEquals("APPLIED", recovered["originalConvergence"].asString(), recovered.toString())
        assertEquals(1, recovered["questCycles"].asInt(), recovered.toString())
        assertEquals(1, recovered["acceptSuccessHistory"].asInt(), recovered.toString())
        assertEquals(1, recovered["questSubmissions"].asInt(), recovered.toString())
        assertEquals(1, recovered["homeSubmissions"].asInt(), recovered.toString())
    }

    @ParameterizedTest
    @CsvSource("ACTIVE", "SHADOW", "LEGACY")
    fun `손상된 후처리 보류는 사용자에게 보이고 명시적 허용 뒤 새 판단을 이어간다`(mode: String) {
        verifyHeldRelease(mode, "projection-corrupt")
    }

    @Test
    fun `손상된 행동 종류 대신 검증된 원래 행동으로 보류를 설명한다`() {
        verifyHeldRelease("ACTIVE", "projection-kind-corrupt")
    }

    @ParameterizedTest
    @CsvSource("ACTIVE", "SHADOW", "LEGACY")
    fun `로컬 보류 해제는 독립 행동의 미래 예약을 보존한다`(mode: String) {
        verifyHeldRelease(mode, "projection-wait-corrupt")
    }

    @Test
    fun `원격 미확정인 로컬 보류 해제는 과거 성공을 만들지 않고 새 판단을 이어간다`() {
        verifyHeldRelease("ACTIVE", "projection-before-corrupt")
    }

    private fun verifyHeldRelease(mode: String, interruption: String) {
        assertEquals(71, runProcess("crash", mode, interruption))
        assertEquals(0, runProcess("resume", mode, interruption))
        assertEquals(0, runProcess("resume-again", mode, interruption))
        val result = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals("RESULT_HELD", result["actionStatus"].asString(), result.toString())
        assertEquals(0, result["originalQuestCycles"].asInt(), result.toString())
        assertEquals(0, result["questClaims"].asInt(), result.toString())
        assertEquals(1, result["questSubmissions"].asInt(), result.toString())
        assertEquals(1, result["independentQuestSubmissions"].asInt(), result.toString())
        assertEquals(1, result["independentQuestClaims"].asInt(), result.toString())
        assertEquals(1, result["homeSubmissions"].asInt(), result.toString())
        val localResults = result["convergenceStatus"].path("localResults")
        assertEquals(1, localResults.size(), result.toString())
        val held = localResults[0]
        assertEquals("RESULT_HELD", held["status"].asString(), result.toString())
        assertEquals("QUEST_ACCEPT", held["actionKind"].asString(), result.toString())
        assertTrue(held["canAllowFreshDecision"].asBoolean(), result.toString())
        assertTrue(held["impactScope"].asString().startsWith("퀘스트 대상 "), result.toString())
        assertTrue(held["reasonMessage"].asString().isNotBlank(), result.toString())
        assertTrue(held["releaseCondition"].asString().contains("새 행동 판단"), result.toString())
        val evidenceId = held.path("evidenceCaseId").asString()
        assertTrue(evidenceId.isNotBlank(), result.toString())
        val evidence = result["localEvidence"]
        assertEquals(evidenceId, evidence["id"].asString(), result.toString())
        assertEquals("LOCAL_RESULT_INTEGRITY", evidence["source"].asString(), result.toString())
        assertEquals("LOCAL_RESULT_INTEGRITY_FAILED", evidence["reason"].asString(), result.toString())
        assertTrue(evidence["build"].asString().isNotBlank(), result.toString())
        assertTrue(evidence["responseFingerprint"].asString().length == 64, result.toString())
        assertEquals(held["actionId"].asLong(), evidence["actionId"].asLong(), result.toString())
        assertEquals(30, evidence["retentionDays"].asInt(), result.toString())
        assertEquals("automation-action-convergence-v1", evidence["policy"].asString(), result.toString())
        assertTrue(evidence["snippet"].asString().contains("actionKind=QUEST_ACCEPT"), result.toString())
        assertTrue(evidence["snippet"].asString().contains("payloadValid=false"), result.toString())
        assertTrue(evidence["snippet"].asString().contains("receiptValid=true"), result.toString())
        if (mode == "ACTIVE") assertEquals(if (interruption == "projection-before-corrupt") "PENDING" else "APPLIED",
            result["originalConvergence"].asString(), result.toString())
        assertEquals(0, runProcess("allow-fresh", mode, interruption))
        assertEquals(0, runProcess("released-restart", mode, interruption))
        val released = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals(0, released["convergenceStatus"]["localResults"].size(), released.toString())
        assertEquals("RESULT_HELD", released["actionStatus"].asString(), released.toString())
        assertEquals(evidence, released["localEvidence"], released.toString())
        assertEquals(1, released["questSubmissions"].asInt(), released.toString())
        assertEquals(1, released["questClaims"].asInt(), released.toString())
        assertEquals(1, released["independentQuestSubmissions"].asInt(), released.toString())
        assertEquals(1, released["independentQuestClaims"].asInt(), released.toString())
        assertEquals(1, released["homeSubmissions"].asInt(), released.toString())
        if (mode == "ACTIVE") assertEquals(if (interruption == "projection-before-corrupt") "RESULT_UNOBSERVED" else "APPLIED",
            released["originalConvergence"].asString(), released.toString())
    }

    @ParameterizedTest
    @CsvSource("ACTIVE", "SHADOW", "LEGACY")
    fun `원래 수락 후처리 재시도가 독립 퀘스트의 작업권을 양보시키지 않는다`(mode: String) {
        assertEquals(71, runProcess("crash", mode, "projection-independent"))
        assertEquals(0, runProcess("resume", mode, "projection-independent"))
        val pending = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals("RUNNING", pending["independentWorkAfterRetry"].asString(), pending.toString())
        assertEquals(1, pending["independentQuestSubmissions"].asInt(), pending.toString())
        assertEquals(1, pending["independentQuestClaims"].asInt(), pending.toString())
        assertEquals(0, pending["originalQuestCycles"].asInt(), pending.toString())
        assertEquals(1, pending["questSubmissions"].asInt(), pending.toString())
        assertEquals(0, runProcess("repair", mode, "projection-independent"))
        val recovered = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals("SUCCEEDED", recovered["actionStatus"].asString(), recovered.toString())
        assertEquals(1, recovered["originalQuestCycles"].asInt(), recovered.toString())
        assertEquals(2, recovered["acceptSuccessHistory"].asInt(), recovered.toString())
        assertEquals(1, recovered["questSubmissions"].asInt(), recovered.toString())
        assertEquals(1, recovered["independentQuestSubmissions"].asInt(), recovered.toString())
        assertEquals(1, recovered["independentQuestClaims"].asInt(), recovered.toString())
        assertEquals(1, recovered["homeSubmissions"].asInt(), recovered.toString())
    }

    @ParameterizedTest
    @CsvSource("ACTIVE", "SHADOW", "LEGACY")
    fun `후처리 재시도가 매번 due여도 독립 자택을 유한한 판단 안에 실행한다`(mode: String) {
        assertEquals(71, runProcess("crash", mode, "projection-delayed"))
        assertEquals(0, runProcess("resume", mode, "projection-delayed"))
        val pending = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals(1, pending["homeSubmissions"].asInt(), pending.toString())
        assertTrue(pending["resumedWakesToHome"].asInt() in 1..2, pending.toString())
        assertEquals(0, pending["questCycles"].asInt(), pending.toString())
        assertEquals(1, pending["questSubmissions"].asInt(), pending.toString())
        assertEquals("RESULT_PENDING", pending["actionStatus"].asString(), pending.toString())
        assertEquals(0, pending["acceptSuccessHistory"].asInt(), pending.toString())
        if (mode == "ACTIVE") assertEquals("APPLIED", pending["originalConvergence"].asString(), pending.toString())
        assertEquals(0, runProcess("repair", mode, "projection-delayed"))
        val recovered = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals("SUCCEEDED", recovered["actionStatus"].asString(), recovered.toString())
        assertEquals(1, recovered["questCycles"].asInt(), recovered.toString())
        assertEquals(1, recovered["acceptSuccessHistory"].asInt(), recovered.toString())
        assertEquals(1, recovered["questSubmissions"].asInt(), recovered.toString())
        assertEquals(1, recovered["homeSubmissions"].asInt(), recovered.toString())
        assertEquals(0, recovered["runningWorks"].asInt(), recovered.toString())
        if (mode == "ACTIVE") assertEquals("APPLIED", recovered["originalConvergence"].asString(), recovered.toString())
    }

    @ParameterizedTest
    @CsvSource("ACTIVE", "SHADOW", "LEGACY")
    fun `수락 진행 저장이 실패한 퀘스트의 수령을 막고 독립 자택을 실행한다`(mode: String) {
        assertEquals(71, runProcess("crash", mode, "projection-claimable"))
        assertEquals(0, runProcess("resume", mode, "projection-claimable"))
        val pending = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals(0, pending["questClaims"].asInt(), "수락 후처리보다 수령을 먼저 제출하지 않는다. $pending")
        assertEquals(1, pending["homeSubmissions"].asInt(), pending.toString())
        assertEquals(0, pending["questCycles"].asInt(), pending.toString())
        assertEquals(1, pending["questSubmissions"].asInt(), pending.toString())
        assertEquals(0, runProcess("repair", mode, "projection-claimable"))
        val recovered = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals("SUCCEEDED", recovered["actionStatus"].asString(), recovered.toString())
        assertEquals(1, recovered["questCycles"].asInt(), recovered.toString())
        assertEquals(1, recovered["acceptSuccessHistory"].asInt(), recovered.toString())
        assertEquals(1, recovered["questSubmissions"].asInt(), recovered.toString())
        assertEquals(1, recovered["questClaims"].asInt(), "후처리 완료 뒤 정상 수령은 계속한다. $recovered")
        assertEquals(1, recovered["homeSubmissions"].asInt(), recovered.toString())
        assertEquals(0, recovered["runningWorks"].asInt(), recovered.toString())
    }

    @Test
    fun `퀘스트 진행 저장이 계속 실패해도 자택을 실행하고 복구 뒤 원래 수락을 한 번 반영한다`() {
        assertEquals(71, runProcess("crash", "ACTIVE", "projection-failure"))
        assertEquals(0, runProcess("resume", "ACTIVE", "projection-failure"))
        val blocked = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals(1, blocked["homeSubmissions"].asInt(), blocked.toString())
        assertEquals(0, blocked["questCycles"].asInt(), blocked.toString())
        assertEquals(0, blocked["successHistory"].asInt(), blocked.toString())
        assertEquals(1, blocked["questSubmissions"].asInt(), blocked.toString())
        assertEquals(0, runProcess("repair", "ACTIVE", "projection-failure"))
        val recovered = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals("SUCCEEDED", recovered["actionStatus"].asString(), recovered.toString())
        assertEquals(1, recovered["questCycles"].asInt(), recovered.toString())
        assertEquals(1, recovered["successHistory"].asInt(), recovered.toString())
        assertEquals(1, recovered["questSubmissions"].asInt(), recovered.toString())
        assertEquals(1, recovered["homeSubmissions"].asInt(), recovered.toString())
        assertEquals(0, recovered["runningWorks"].asInt(), recovered.toString())
    }

    @ParameterizedTest
    @CsvSource("SHADOW,incomplete", "LEGACY,incomplete", "SHADOW,same-state", "LEGACY,same-state")
    fun `비확정 직접 응답은 같은 입력을 무한 재생하지 않고 독립 자택을 실행한다`(mode: String, interruption: String) {
        assertEquals(71, runProcess("crash", mode, interruption))
        assertEquals(0, runProcess("resume", mode, interruption))
        val result = jacksonObjectMapper().readTree(directory.resolve("result.json").readText())
        assertEquals(1, result["homeSubmissions"].asInt(), result.toString())
        assertTrue(result["actionStatus"].asString() in setOf("AMBIGUOUS", "FAILED"), result.toString())
        assertEquals(0, result["successHistory"].asInt(), result.toString())
        assertEquals(1, result["questSubmissions"].asInt(), result.toString())
        assertEquals(0, result["runningWorks"].asInt(), result.toString())
    }

    @ParameterizedTest
    @CsvSource("ACTIVE,before", "SHADOW,before", "LEGACY,before", "ACTIVE,after", "SHADOW,after", "LEGACY,after")
    fun `퀘스트 수락 응답과 후처리 사이에서 종료해도 한 번 완료하고 다음 판단을 실행한다`(mode: String, interruption: String) {
        assertEquals(71, runProcess("crash", mode, interruption))
        val mapper = jacksonObjectMapper()
        assertEquals(1, mapper.readTree(directory.resolve("hof.json").readText())["questSubmissions"].asInt())
        assertEquals(0, runProcess("resume", mode, interruption))
        val result = mapper.readTree(directory.resolve("result.json").readText())
        assertEquals("SUCCEEDED", result["actionStatus"].asString(), result.toString())
        assertEquals(1, result["questCycles"].asInt(), result.toString())
        assertEquals(1, result["successHistory"].asInt(), result.toString())
        assertEquals(1, result["questSubmissions"].asInt(), "수락 GET을 재전송하지 않는다.")
        assertEquals(1, result["homeSubmissions"].asInt(), "독립 자택도 실제 후속 wake에서 실행한다. $result")
        assertTrue(result["decisions"].asInt() >= 2, result.toString())
        assertEquals(0, result["runningWorks"].asInt(), result.toString())
    }

    private fun runProcess(phase: String, mode: String, interruption: String): Int {
        val classpath = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { Path.of(it.toURI()).toString() }.toList()
            .plus(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .distinct().joinToString(java.io.File.pathSeparator)
        val output = directory.resolve("$phase.log").toFile()
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m",
            "-cp", classpath, AutomationDirectReceiptCrashProcess::class.java.name, directory.toString(), phase, mode, interruption)
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "child timed out: ${output.readText().takeLast(8000)}")
            return process.exitValue().also { assertTrue(it in setOf(0, 71), output.readText().takeLast(12000)) }
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}

internal data class ReceiptHofState(var questSubmissions: Int = 0, var homeSubmissions: Int = 0, var questClaims: Int = 0,
    var resumedWakesToHome: Int = 0, var clockAt: String? = null,
    var independentQuestSubmissions: Int = 0, var independentQuestClaims: Int = 0,
    var advancedQuestSubmissions: Int = 0,
    var successHistoryBeforeAdvancedSubmission: Int? = null,
    var independentWorkAfterRetry: String? = null,
    val requests: MutableList<String> = mutableListOf())

object AutomationDirectReceiptCrashProcess {
    private lateinit var directory: Path
    private lateinit var phase: String
    private lateinit var interruption: String
    private var state = ReceiptHofState()
    private lateinit var deliveryClock: AutomationRecoveryIntegrationTest.RecoveryClock
    private lateinit var diagnostics: JdbcTemplate
    private var resumedWakes = 0
    private val lateResponseReady = CountDownLatch(1)
    private val returnLateResponse = CountDownLatch(1)
    private val receiptAbsenceRead = CountDownLatch(1)
    private val continueRecovery = CountDownLatch(1)
    private val receiptCommitted = CountDownLatch(1)
    private val recoveryFinished = CountDownLatch(1)
    @Volatile private var lateRecovery = false
    private val mapper = jacksonObjectMapper()
    private const val QUEST_URL = "https://hof.zerosic.com/index.php?menu=quest"
    private const val HOME_URL = "https://hof.zerosic.com/index.php?menu=quest2"

    private fun haltAfterHistorySnapshot() {
        val status = diagnostics.queryForObject("select status from typed_automation_action_runs where id = (select min(id) from typed_automation_action_runs)", String::class.java)
        val processed = diagnostics.queryForObject("select count(*) from quest_automation_processed_results where result_kind = 'ACCEPT'", Int::class.java)
        val history = diagnostics.queryForObject("select count(*) from automation_decision_events where action_kind = 'QUEST_ACCEPT' and event_kind = 'ACTION_SUCCEEDED'", Int::class.java)
        directory.resolve("history-commit.json").writeText(mapper.writeValueAsString(
            mapOf("status" to status, "processed" to processed, "history" to history)))
        Runtime.getRuntime().halt(71)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        directory = Path.of(args[0])
        phase = args[1]
        val mode = args[2]
        interruption = args[3]
        if (phase != "crash") state = mapper.readValue(directory.resolve("hof.json").readText(), ReceiptHofState::class.java)
        SpringApplicationBuilder(HofApplication::class.java, Remote::class.java).profiles("test").run(
            "--server.port=0", "--hof.automation-convergence.mode=$mode",
            "--spring.datasource.url=jdbc:h2:file:${directory.resolve("database")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0",
        ).use { context ->
            fun convergenceRequest(path: String = "", method: String = "GET"): tools.jackson.databind.JsonNode {
                // HTTP 인증 decoder의 실제 시각과 맞추되 자동화의 결정 시각은 고정한다.
                val token = JwtTokenService(context.getBean(JwtEncoder::class.java),
                    context.getBean(AuthProperties::class.java), TimeProvider { Instant.now() }).issue(1L).value
                val port = context.environment.getRequiredProperty("local.server.port")
                val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/automation/unified/convergence$path"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer $token")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build()
                val response = HttpClient.newHttpClient().use { it.send(request, HttpResponse.BodyHandlers.ofString()) }
                check(response.statusCode() == 200) { "사용자 결과 조회/해제 실패: ${response.statusCode()} ${response.body()}" }
                return mapper.readTree(response.body())
            }
            diagnostics = context.getBean(JdbcTemplate::class.java)
            val clock = context.getBean(AutomationRecoveryIntegrationTest.RecoveryClock::class.java)
            deliveryClock = clock
            clock.current = maxOf(Instant.parse("2026-09-10T10:00:00Z").plusSeconds(if (phase != "crash") 301 else 0),
                state.clockAt?.let(Instant::parse) ?: Instant.MIN)
            val em = context.getBean(EntityManager::class.java)
            if (phase == "crash") {
                lateinit var account: HofAccountEntity
                TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).executeWithoutResult {
                    account = HofAccountEntity(loginId = "receipt-process", encryptedPassword = "fixture", createdAt = clock.now())
                    em.persist(account)
                    em.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = clock.now()))
                    em.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                        timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
                    val questEntry = AutomationEntryEntity(account = account, type = AutomationType.QUEST, priority = 0,
                        enabled = true, createdAt = clock.now(), updatedAt = clock.now())
                    em.persist(questEntry)
                    val quests = QuestPageParser().parseObservation(questPage(false), QUEST_URL).also { check(it.complete) }
                        .quests.filter { it.displayCode in setOf("0001", "0002") }.sortedBy { it.displayCode }
                    quests.forEachIndexed { index, quest ->
                        em.persist(QuestAutomationSelectionEntity(entry = questEntry, questKey = quest.questKey,
                            enabled = true, sourceOrder = index, questName = quest.name, displayCode = quest.displayCode))
                    }
                    val homeEntry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 1,
                        enabled = true, createdAt = clock.now(), updatedAt = clock.now())
                    em.persist(homeEntry)
                    val home = HomePageParser().parse(HomeMode.HOME, homePage(), HOME_URL, HofFormParser().parse(homePage(), HOME_URL)).quests.single()
                    check(home.state == app.spammy.hof.town.home.model.HomeQuestState.AVAILABLE)
                    em.persist(HomeQuestAutomationSelectionEntity(entry = homeEntry, questId = home.id,
                        questName = home.name, enabled = true, sourceOrder = 0))
                }
                if (interruption.startsWith("projection-")) {
                    context.getBean(JdbcTemplate::class.java).execute("alter table quest_automation_processed_results add constraint fixture_reject_accept check (result_kind <> 'ACCEPT')")
                }
                if (interruption == "projection-result-write") {
                    diagnostics.execute("alter table automation_action_convergences add constraint fixture_reject_applied check (result <> 'APPLIED')")
                }
                context.getBean(RefreshTokenService::class.java).issue(account, "NATIVE")
                context.getBean(UnifiedAutomationService::class.java).startTyped(account.id)
            } else {
                if (phase == "resume" && interruption == "projection-result-write") {
                    diagnostics.execute("alter table automation_action_convergences drop constraint fixture_reject_applied")
                }
                if (phase == "resume" && interruption in setOf("projection-independent", "projection-corrupt", "projection-kind-corrupt", "projection-wait-corrupt", "projection-before-corrupt")) {
                    val originalIdentity = diagnostics.queryForObject(
                        "select execution_identity from typed_automation_action_runs where action_kind = 'QUEST_ACCEPT' order by id limit 1",
                        String::class.java)!!
                    check(originalIdentity.matches(Regex("[A-Za-z0-9:-]+")))
                    diagnostics.execute("alter table quest_automation_processed_results drop constraint fixture_reject_accept")
                    diagnostics.execute("alter table quest_automation_processed_results add constraint fixture_reject_accept check (result_identity <> '$originalIdentity')")
                    if (interruption == "projection-kind-corrupt") {
                        diagnostics.update("update typed_automation_action_runs set action_kind = 'QUEST_CLAIM' where execution_identity = ?", originalIdentity)
                    }
                    if (interruption in setOf("projection-corrupt", "projection-wait-corrupt", "projection-before-corrupt")) {
                        diagnostics.update("update typed_automation_action_runs set payload_json = '{}' where execution_identity = ?", originalIdentity)
                    }
                }
                if (phase == "repair") {
                    context.getBean(JdbcTemplate::class.java).execute("alter table quest_automation_processed_results drop constraint fixture_reject_accept")
                }
                if (phase == "allow-fresh") {
                    val heldId = convergenceRequest()["localResults"].single()["actionId"].asLong()
                    val runtime = context.getBean(TypedAutomationRuntimeService::class.java)
                    val independentRetryAt = if (interruption == "projection-wait-corrupt") {
                        val next = diagnostics.queryForObject("select next_attempt_at from typed_automation_runtime_states where account_id = 1",
                            java.sql.Timestamp::class.java)?.toInstant()
                        if (next != null) clock.current = maxOf(clock.now(), next)
                        val original = requireNotNull(TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).execute {
                            val row = em.createQuery("select action from TypedAutomationActionRunEntity action where action.account.id = 1 and action.id <> :heldId and action.actionKind = 'QUEST_ACCEPT' and action.status = :status",
                                TypedAutomationActionRunEntity::class.java)
                                .setParameter("heldId", heldId).setParameter("status", TypedAutomationActionStatus.SUCCEEDED).singleResult
                            context.getBean(StoredTypedAutomationActionCodec::class.java).verifyPersisted(row, 1L)
                        })
                        val right = assertIs<TypedRuntimeAcquisition.Acquired>(runtime.acquire(1L)).execution
                        val prepared = assertIs<TypedRuntimePreparation.Ready>(runtime.persistPrepared(right,
                            original.copy(executionIdentity = "independent-wait-fixture"))).execution
                        val retryAt = clock.now().plusSeconds(90)
                        check(runtime.complete(prepared, TypedRuntimeOutcome.ScheduledWait(retryAt,
                            AutomationWaitReason.HOF_CONNECTION, wakeReason = "INDEPENDENT_WAIT_FIXTURE")).applied)
                        check(runtime.acquire(1L) == TypedRuntimeAcquisition.Busy)
                        retryAt
                    } else null
                    val released = convergenceRequest("/local-results/$heldId/allow-fresh-decision", "POST")
                    check(released["localResults"].isEmpty()) { released.toString() }
                    if (independentRetryAt != null) {
                        check(runtime.acquire(1L) == TypedRuntimeAcquisition.Busy) { "로컬 보류 해제가 독립 행동의 미래 예약을 당겼다" }
                        check(diagnostics.queryForObject("select next_attempt_at from typed_automation_runtime_states where account_id = 1",
                            java.sql.Timestamp::class.java)?.toInstant() == independentRetryAt)
                    }
                    check(state.questSubmissions == 1 && state.questClaims == 0) { "해제 요청 자체가 원격 행동을 제출하면 안 된다: $state" }
                } else context.getBean(AutomationWakeupPort::class.java).wake(1L, "PROCESS_RESTART")
            }
            val publisher = context.getBean(AutomationOutboxPublisher::class.java)
            val jdbc = context.getBean(JdbcTemplate::class.java)
            if (phase == "crash" && interruption.startsWith("late-")) {
                val outbox = context.getBean(AutomationOutboxQueryRepository::class.java)
                val marker = context.getBean(AutomationOutboxPublishMarker::class.java)
                val otherTransport = AutomationRecoveryIntegrationTest.Config().consumerReplayTransport(mapper,
                    context.getBean(AutomationConsumedEventService::class.java),
                    context.getBean(AccountAutomationLeaseService::class.java),
                    context.getBean(UnifiedAutomationRunner::class.java), clock)
                val otherPublisher = AutomationOutboxPublisher(outbox, marker, otherTransport, clock)
                Executors.newFixedThreadPool(2).use { executor ->
                    val oldWorker = executor.submit { publisher.publishBatch() }
                    try {
                        check(lateResponseReady.await(15, TimeUnit.SECONDS)) { "수락의 정상 파싱과 요청 잠금 해제에 도달해야 한다." }
                        val dispatched = outbox.findUnpublished(clock.now()).single {
                            mapper.readValue(it.payload, AutomationWakeupEvent::class.java).reason == "USER_START"
                        }
                        marker.markPublished(dispatched.id)
                        check(jdbc.queryForObject("select status from typed_automation_action_runs where id = (select min(id) from typed_automation_action_runs)",
                            String::class.java) == "SUBMITTING")
                        lateRecovery = true
                        clock.current = clock.now().plusSeconds(301)
                        context.getBean(AutomationWakeupPort::class.java).wake(1L, "LATE_RECEIPT_LEASE_RECOVERY")
                        if (interruption == "late-during-reconciliation") {
                            val recoveryWorker = executor.submit { otherPublisher.publishBatch() }
                            check(receiptAbsenceRead.await(15, TimeUnit.SECONDS)) { "복구 worker가 저장된 응답의 부재를 먼저 읽어야 한다." }
                            returnLateResponse.countDown()
                            check(receiptCommitted.await(15, TimeUnit.SECONDS)) { "과거 관측으로 복구하기 전에 원래 응답이 commit되어야 한다." }
                            check(jdbc.queryForObject("select status from typed_automation_action_runs where id = (select min(id) from typed_automation_action_runs)",
                                String::class.java) == "RECONCILING")
                            continueRecovery.countDown()
                            recoveryWorker.get(15, TimeUnit.SECONDS)
                            val recoveredStatus = jdbc.queryForObject("select status from typed_automation_action_runs where id = (select min(id) from typed_automation_action_runs)", String::class.java)
                            val receipts = jdbc.queryForObject("select count(*) from typed_automation_action_runs where direct_response_json is not null", Int::class.java)
                            val processed = jdbc.queryForObject("select count(*) from quest_automation_processed_results where result_kind = 'ACCEPT'", Int::class.java)
                            directory.resolve("late-reconciliation.json").writeText(mapper.writeValueAsString(
                                mapOf("status" to recoveredStatus, "receipts" to receipts, "processed" to processed)))
                            recoveryFinished.countDown()
                            oldWorker.get(15, TimeUnit.SECONDS)
                            error("원래 응답 commit과 복구 관측 종결 뒤 crash 지점에 도달해야 한다.")
                        }
                        repeat(4) {
                            otherPublisher.publishBatch()
                            val due = jdbc.queryForObject("select min(available_at) from automation_outbox where published_at is null",
                                java.sql.Timestamp::class.java)?.toInstant()
                            if (due != null) clock.current = maxOf(clock.now(), due)
                        }
                        val terminal = jdbc.queryForObject("select status from typed_automation_action_runs where id = (select min(id) from typed_automation_action_runs)",
                            String::class.java)
                        val processed = jdbc.queryForObject("select count(*) from quest_automation_processed_results where result_kind = 'ACCEPT'", Int::class.java)
                        check(terminal == "AMBIGUOUS" && processed == 0) { "실제 복구 worker가 원래 후처리 전에 종결해야 한다: $terminal/$processed" }
                        directory.resolve("late-terminal.json").writeText(mapper.writeValueAsString(mapOf("status" to terminal, "processed" to processed)))
                    } finally {
                        returnLateResponse.countDown()
                        continueRecovery.countDown()
                        recoveryFinished.countDown()
                    }
                    oldWorker.get(15, TimeUnit.SECONDS)
                    error("늦은 응답 저장 직후 crash 지점에 도달해야 한다.")
                }
            }
            repeat(5) {
                val due = jdbc.queryForObject("select min(available_at) from automation_outbox where published_at is null",
                    java.sql.Timestamp::class.java)?.toInstant()
                if (due != null) clock.current = maxOf(clock.now(), due)
                if (phase == "crash" && interruption == "projection-result-write") {
                    val failure = runCatching { publisher.publishBatch() }.exceptionOrNull()
                    check(generateSequence(failure) { it.cause }.any {
                        it.message?.contains("fixture_reject_applied", ignoreCase = true) == true
                    }) { "직접 적용 저장 제약에서 실패해야 한다: $failure" }
                    check(state.questSubmissions == 1)
                    check(jdbc.queryForObject("select status from typed_automation_action_runs where action_kind = 'QUEST_ACCEPT'", String::class.java) == "SUBMITTING")
                    check(jdbc.queryForObject("select result from automation_action_convergences", String::class.java) == "PENDING")
                    check(jdbc.queryForObject("select count(*) from typed_automation_action_runs where direct_response_json is not null", Int::class.java) == 1)
                    check(jdbc.queryForObject("select count(*) from quest_automation_cycles", Int::class.java) == 0)
                    Runtime.getRuntime().halt(71)
                }
                publisher.publishBatch()
                if (phase == "crash" && interruption.startsWith("projection-")) {
                    check(state.questSubmissions == 1 && state.independentQuestSubmissions == 0)
                    check(jdbc.queryForObject("select count(*) from quest_automation_cycles", Int::class.java) == 0)
                    check(jdbc.queryForObject("select count(*) from typed_automation_action_runs where direct_response_json is not null", Int::class.java) == 1)
                    Runtime.getRuntime().halt(71)
                }
            }
            check(phase != "crash") { "직접 수락 응답의 후처리에 도달하지 못했다: $state; " +
                jdbc.queryForList("select status, last_error from typed_automation_action_runs").toString() +
                jdbc.queryForList("select reason_code, message from automation_decision_events").toString() }
            val actionId = jdbc.queryForObject("select min(id) from typed_automation_action_runs", Long::class.java)!!
            val convergenceStatus = convergenceRequest()
            val evidenceIdFile = directory.resolve("local-evidence-id.txt")
            val evidenceId = convergenceStatus.path("localResults").firstOrNull()?.path("evidenceCaseId")?.asString()?.takeIf { it.isNotBlank() }
            if (evidenceId != null) evidenceIdFile.writeText(evidenceId)
            val retainedEvidenceId = if (java.nio.file.Files.exists(evidenceIdFile)) evidenceIdFile.readText() else null
            val evidence = retainedEvidenceId?.let { id -> jdbc.queryForList("""select id as "id", evidence_source as "source",
                reason_code as "reason", policy_version as "policy", build_version as "build", typed_action_id as "actionId",
                response_shape_fingerprint as "responseFingerprint", sanitized_snippet as "snippet",
                datediff('DAY', created_at, expires_at) as "retentionDays" from automation_evidence_cases where id = ?""", id).singleOrNull() }
            val result = mapOf(
                "localEvidence" to evidence,
                "convergenceStatus" to convergenceStatus,
                "actionStatus" to jdbc.queryForObject("select status from typed_automation_action_runs where id = ?", String::class.java, actionId),
                "originalConvergence" to jdbc.queryForList("""select c.result from automation_action_convergences c
                    join automation_action_attempts a on a.id = c.attempt_id
                    join typed_automation_action_runs r on r.execution_identity = a.execution_identity and r.account_id = a.account_id
                    where r.id = ?""".trimIndent(), String::class.java, actionId).singleOrNull(),
                "questCycles" to jdbc.queryForObject("select coalesce(max(current_cycle),0) from quest_automation_cycles", Int::class.java),
                "originalQuestCycles" to jdbc.queryForObject("select coalesce(max(current_cycle),0) from quest_automation_cycles where quest_code in (select quest_code from quest_automation_selections where display_code = '0001')", Int::class.java),
                "originalAcceptResults" to jdbc.queryForObject("select count(*) from quest_automation_processed_results where result_kind = 'ACCEPT' and result_identity = (select execution_identity from typed_automation_action_runs where id = ?)", Int::class.java, actionId),
                "successHistory" to jdbc.queryForObject("select count(*) from automation_decision_events where automation_type = 'QUEST' and event_kind = 'ACTION_SUCCEEDED'", Int::class.java),
                "acceptSuccessHistory" to jdbc.queryForObject("select count(*) from automation_decision_events where automation_type = 'QUEST' and action_kind = 'QUEST_ACCEPT' and event_kind = 'ACTION_SUCCEEDED'", Int::class.java),
                "decisions" to jdbc.queryForObject("select count(*) from automation_decision_cycles", Int::class.java),
                "runningWorks" to jdbc.queryForObject("select count(*) from automation_work_sessions where status = 'RUNNING'", Int::class.java),
                "questSubmissions" to state.questSubmissions, "homeSubmissions" to state.homeSubmissions,
                "advancedQuestSubmissions" to state.advancedQuestSubmissions,
                "successHistoryBeforeAdvancedSubmission" to state.successHistoryBeforeAdvancedSubmission,
                "questClaims" to state.questClaims,
                "resumedWakesToHome" to state.resumedWakesToHome,
                "independentQuestSubmissions" to state.independentQuestSubmissions,
                "independentQuestClaims" to state.independentQuestClaims,
                "independentWorkAfterRetry" to state.independentWorkAfterRetry,
                "requests" to state.requests,
                "history" to jdbc.queryForList("select reason_code, message from automation_decision_events"),
                "runtime" to jdbc.queryForList("select lifecycle_status, requested_lifecycle, auth_suspended, next_attempt_at, lease_until, last_error from typed_automation_runtime_states"),
                "outbox" to jdbc.queryForList("select payload, available_at, published_at from automation_outbox order by id"),
            )
            directory.resolve("result.json").writeText(mapper.writeValueAsString(result))
            state.clockAt = clock.now().toString()
            directory.resolve("hof.json").writeText(mapper.writeValueAsString(state))
        }
    }

    private fun questPage(previouslyAccepted: Boolean): String {
        val advanced = interruption == "late-advanced" && state.questSubmissions > 0
        val accepted = if (advanced) state.advancedQuestSubmissions > 0 else previouslyAccepted
        val actionNo = if (advanced) "3" else "1"
        val header = "<tr><td>퀘스트명</td><td>타입</td><td>제한</td><td>보상</td><td>행동</td></tr>"
        val empty = requireNotNull(javaClass.classLoader.getResource("fixtures/quest/quest-complete-empty.html")).readText()
        if (state.questClaims > 0) return empty
        val claimable = accepted && interruption in setOf("projection-claimable", "projection-corrupt", "projection-kind-corrupt", "projection-wait-corrupt", "projection-before-corrupt")
        val action = when {
            claimable -> "<a href='?menu=quest&amp;action=complete&amp;no=1'>완료</a>"
            accepted -> "진행중"
            else -> "<a href='?menu=quest&amp;action=get&amp;no=$actionNo'>수락</a>"
        }
        val row = "<tr><td class='td7s'>[0001] 수신 기록 의뢰</td><td>미션 : 몬스터 처치 ${if (claimable) 1 else 0}/1</td><td>-</td><td>-</td><td>$action</td></tr>"
        val section = if (accepted) "진행중인" else "수락 가능한"
        var page = empty.replace("<h4>$section 퀘스트 목록</h4>\n  <table>$header</table>",
            "<h4>$section 퀘스트 목록</h4><table>$header$row</table>")
        if (interruption in setOf("projection-independent", "projection-corrupt", "projection-kind-corrupt", "projection-wait-corrupt", "projection-before-corrupt") && state.independentQuestClaims == 0) {
            val otherAccepted = state.independentQuestSubmissions > 0
            val otherAction = if (otherAccepted) "complete" else "get"
            val otherSection = if (otherAccepted) "진행중인" else "수락 가능한"
            val otherRow = "<tr><td class='td7s'>[0002] 독립 의뢰</td><td>미션 : 몬스터 처치 ${if (otherAccepted) 1 else 0}/1</td><td>-</td><td>-</td><td><a href='?menu=quest&amp;action=$otherAction&amp;no=2'>${if (otherAccepted) "완료" else "수락"}</a></td></tr>"
            page = page.replace(Regex("(<h4>$otherSection 퀘스트 목록</h4>\\s*<table>.*?)</table>", RegexOption.DOT_MATCHES_ALL), "$1$otherRow</table>")
        }
        return page
    }

    private fun homePage(): String = """<div id='menu2'>Funds : $ 1 Time : 100/100</div>
        <h4>${if (state.homeSubmissions > 0) "진행중인" else "수락 가능한"} 작업 목록</h4><table>
        <tr><td>[A] 독립 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>
        ${if (state.homeSubmissions > 0) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"}</td></tr></table>"""

    @TestConfiguration
    @Import(AutomationRecoveryIntegrationTest.Config::class)
    class Remote {
        @Bean @Primary
        fun preflight(): AutomationDailyPreflight = Mockito.mock(AutomationDailyPreflight::class.java).also {
            Mockito.`when`(it.ensureReady(Mockito.anyLong())).thenReturn(AutomationDailyPreflight.Result.Ready)
        }

        @Bean @Primary
        fun hof(): HofGateway = object : HofGateway {
            override fun execute(accountId: Long, request: HofRequest, cookies: Map<String, String>): HofHttpResponse {
                state.requests += "${request.url} ${request.formFields}"
                val body = when {
                    request.url.contains("menu=quest2") || request.url.contains("menu=housing") -> {
                        if (request.formFields["action"] == "get") {
                            state.homeSubmissions++
                            if (phase == "resume") state.resumedWakesToHome = resumedWakes
                        }
                        homePage()
                    }
                    request.url.contains("menu=quest") -> {
                        val submitting = request.formFields["action"] == "get"
                        if (request.formFields["no"] == "2") {
                            if (submitting) state.independentQuestSubmissions++
                            if (request.formFields["action"] == "complete") state.independentQuestClaims++
                        } else if (request.formFields["no"] == "3") {
                            if (submitting) {
                                state.successHistoryBeforeAdvancedSubmission = diagnostics.queryForObject(
                                    "select count(*) from automation_decision_events where automation_type = 'QUEST' and action_kind = 'QUEST_ACCEPT' and event_kind = 'ACTION_SUCCEEDED'",
                                    Int::class.java)
                                state.advancedQuestSubmissions++
                            }
                        } else {
                            if (submitting) state.questSubmissions++
                            if (request.formFields["action"] == "complete") state.questClaims++
                        }
                        when {
                            lateRecovery -> "<div id='contents'>퀘스트 목록</div>"
                            submitting && interruption == "late-incomplete" -> "<div id='contents'>퀘스트 목록</div>"
                            submitting && interruption == "incomplete" -> "<div id='contents'>퀘스트 목록</div>"
                            submitting && interruption == "same-state" -> questPage(false)
                            else -> questPage(state.questSubmissions > 0)
                        }
                    }
                    else -> error("Unexpected HOF request ${request.method} ${request.url}")
                }
                directory.resolve("hof.json").writeText(mapper.writeValueAsString(state))
                return HofHttpResponse(200, request.url, body, cookies)
            }
        }

        companion object {
            @Bean @JvmStatic
            fun crashBeforeQuestProjection(): BeanPostProcessor = object : BeanPostProcessor {
                override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                    if (bean is app.spammy.hof.automation.history.AutomationDecisionEventCommandRepository && interruption == "history-during-insert") {
                        return ProxyFactory(bean).apply {
                            addAdvice(MethodInterceptor { invocation ->
                                val result = invocation.proceed()
                                val event = result as? app.spammy.hof.automation.history.AutomationDecisionEventEntity
                                if (phase == "crash" && invocation.method.name == "save" && event?.id != null && event.id > 0 &&
                                    event.actionKind == "QUEST_ACCEPT" && event.kind == app.spammy.hof.automation.history.AutomationHistoryEventKind.ACTION_SUCCEEDED
                                ) haltAfterHistorySnapshot()
                                result
                            })
                        }.proxy
                    }
                    if (bean is TypedAutomationRuntimeService && interruption == "history-after-result") {
                        return ProxyFactory(bean).apply {
                            isProxyTargetClass = true
                            addAdvice(MethodInterceptor { invocation ->
                                val result = invocation.proceed()
                                if (phase == "crash" && invocation.method.name == "complete" &&
                                    invocation.arguments.getOrNull(1) is TypedRuntimeOutcome.ActionSucceeded
                                ) {
                                    haltAfterHistorySnapshot()
                                }
                                result
                            })
                        }.proxy
                    }
                    if (bean is AutomationDirectResponseStore && interruption in setOf("late-incomplete", "late-during-reconciliation", "late-advanced")) {
                        return ProxyFactory(bean).apply {
                            isProxyTargetClass = true
                            addAdvice(MethodInterceptor { invocation ->
                                val result = invocation.proceed()
                                if (phase == "crash" && interruption == "late-during-reconciliation" && lateRecovery &&
                                    invocation.method.name == "load" && result == null && receiptAbsenceRead.count > 0
                                ) {
                                    receiptAbsenceRead.countDown()
                                    check(continueRecovery.await(30, TimeUnit.SECONDS))
                                }
                                if (phase == "crash" && invocation.method.name == "record") {
                                    if (interruption == "late-during-reconciliation") {
                                        receiptCommitted.countDown()
                                        check(recoveryFinished.await(30, TimeUnit.SECONDS))
                                        check(java.nio.file.Files.exists(directory.resolve("late-reconciliation.json")))
                                    } else check(java.nio.file.Files.exists(directory.resolve("late-terminal.json")))
                                    check(diagnostics.queryForObject("select count(*) from typed_automation_action_runs where direct_response_json is not null", Int::class.java) == 1)
                                    Runtime.getRuntime().halt(71)
                                }
                                result
                            })
                        }.proxy
                    }
                    if (bean is QuestGatewayService && interruption.startsWith("late-")) {
                        return ProxyFactory(bean).apply {
                            addAdvice(MethodInterceptor { invocation ->
                                val result = invocation.proceed()
                                if (phase == "crash" && invocation.method.name == "acceptObservation") {
                                    lateResponseReady.countDown()
                                    check(returnLateResponse.await(30, TimeUnit.SECONDS))
                                }
                                result
                            })
                        }.proxy
                    }
                    if (bean is AutomationRecoveryIntegrationTest.ConsumerReplayTransport) {
                        return object : AutomationOutboxTransport {
                            override val supportedTopics = bean.supportedTopics
                            override fun publish(row: AutomationOutboxEntity) {
                                if (interruption in setOf("projection-delayed", "projection-independent")) deliveryClock.current = deliveryClock.now().plusSeconds(11)
                                if (phase == "resume") resumedWakes++
                                val retryBefore = if (interruption == "projection-independent") diagnostics.queryForObject(
                                    "select coalesce(max(retry_attempt),0) from typed_automation_action_runs where action_kind = 'QUEST_ACCEPT' and id = (select min(id) from typed_automation_action_runs where action_kind = 'QUEST_ACCEPT')",
                                    Int::class.java)!! else 0
                                bean.publish(row)
                                if (interruption == "projection-independent" && phase == "resume" &&
                                    state.independentQuestSubmissions == 1 && state.independentQuestClaims == 0 &&
                                    diagnostics.queryForObject("select retry_attempt from typed_automation_action_runs where id = (select min(id) from typed_automation_action_runs where action_kind = 'QUEST_ACCEPT')", Int::class.java)!! > retryBefore
                                ) {
                                    state.independentWorkAfterRetry = diagnostics.queryForObject(
                                        "select status from automation_work_sessions where target_key in (select quest_code from quest_automation_selections where display_code = '0002') order by id desc limit 1",
                                        String::class.java)
                                }
                            }
                        }
                    }
                    if (bean !is QuestWorkCycleModule) return bean
                    return ProxyFactory(bean).apply {
                        addAdvice(MethodInterceptor { invocation ->
                            if (phase == "crash" && !interruption.startsWith("history-") &&
                                (!interruption.startsWith("projection-") || interruption == "projection-before-corrupt") &&
                                invocation.method.name == "recordObservedResult") {
                                check(state.questSubmissions == 1)
                                if (interruption == "late-before") {
                                    check(java.nio.file.Files.exists(directory.resolve("late-terminal.json")))
                                    check(diagnostics.queryForObject("select count(*) from typed_automation_action_runs where direct_response_json is not null", Int::class.java) == 1)
                                    check(diagnostics.queryForObject("select count(*) from quest_automation_processed_results where result_kind = 'ACCEPT'", Int::class.java) == 0)
                                }
                                if (interruption == "after") invocation.proceed()
                                Runtime.getRuntime().halt(71)
                            }
                            invocation.proceed()
                        })
                    }.proxy
                }
            }
        }
    }
}
