package app.spammy.hof.automation.service

import app.spammy.hof.HofApplication
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.auth.service.RefreshTokenService
import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.ConvergenceStore
import app.spammy.hof.automation.convergence.ProductionEvidenceShapes
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.HomeQuestAutomationSelectionEntity
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.outbox.AutomationOutboxPublisher
import app.spammy.hof.automation.outbox.AutomationOutboxQueryRepository
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.parser.HomePageParser
import jakarta.persistence.EntityManager
import java.net.URLClassLoader
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.aopalliance.intercept.MethodInterceptor
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper

class AutomationHomeDirectReceiptProcessRestartTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @CsvSource("ACTIVE,ACCEPT", "ACTIVE,CLAIM", "SHADOW,ACCEPT", "SHADOW,CLAIM", "LEGACY,ACCEPT", "LEGACY,CLAIM")
    fun `자택 직접 응답 수신 뒤 JVM을 종료해도 원래 성공과 독립 판단을 한 번씩 복구한다`(mode: String, action: String) {
        assertEquals(71, runProcess("crash", mode, action))
        val mapper = jacksonObjectMapper()
        val interrupted = mapper.readTree(directory.resolve("interrupted.json").readText())
        assertEquals("SUBMITTING", interrupted["status"].asString())
        assertEquals("HOME_$action", interrupted["actionKind"].asString())
        assertEquals(1, interrupted["submissions"].asInt())
        assertTrue(interrupted["stateObserved"].asBoolean())
        val processes = mutableSetOf(interrupted["pid"].asLong())
        var originalHistory: String? = null
        for (phase in listOf("resume", "resume-again")) {
            assertEquals(0, runProcess(phase, mode, action))
            val result = mapper.readTree(directory.resolve("result.json").readText())
            assertTrue(processes.add(result["pid"].asLong()), "복원은 별도 JVM에서 수행해야 한다.")
            assertEquals(interrupted["identity"].asString(), result["identity"].asString())
            assertEquals("SUCCEEDED", result["status"].asString(), result.toString())
            assertEquals(if (mode == "ACTIVE") "APPLIED" else "NONE", result["canonical"].asString())
            assertEquals(if (mode == "SHADOW") "APPLIED" else "", result["shadow"].asString())
            assertEquals(1, result["originalSubmissions"].asInt(), result.toString())
            assertEquals(1, result["independentSubmissions"].asInt(), result.toString())
            assertEquals(1, result["successHistory"].size(), result.toString())
            if (originalHistory == null) originalHistory = result["successHistory"].toString()
            else assertEquals(originalHistory, result["successHistory"].toString())
            assertEquals(0, result["runningWorks"].asInt())
            assertEquals(0, result["accountLeases"].asInt())
            assertTrue(result["runtimeLeaseReleased"].asBoolean())
            assertTrue(result["consumedWakes"].asInt() >= 5)
        }
    }

    private fun runProcess(phase: String, mode: String, action: String): Int {
        val classpath = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { Path.of(it.toURI()).toString() }.toList()
            .plus(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .distinct().joinToString(java.io.File.pathSeparator)
        val output = directory.resolve("$phase.log").toFile()
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m",
            "-cp", classpath, AutomationHomeDirectReceiptCrashProcess::class.java.name, directory.toString(), phase, mode, action)
            .redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "child timed out: ${output.readText().takeLast(8000)}")
            return process.exitValue().also { assertTrue(it in setOf(0, 71), output.readText().takeLast(12000)) }
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}

internal data class HomeReceiptHofState(val submissions: MutableList<String> = mutableListOf(), var clockAt: String? = null)

/** 같은 파일 DB를 새 JVM의 실제 Spring/JPA/runtime/consumer로 열며 HOF만 fixture로 대체한다. */
object AutomationHomeDirectReceiptCrashProcess {
    private lateinit var directory: Path
    private lateinit var phase: String
    private lateinit var action: String
    private lateinit var diagnostics: JdbcTemplate
    private var state = HomeReceiptHofState()
    private val mapper = jacksonObjectMapper()
    private const val HOME_URL = "https://hof.zerosic.com/index.php?menu=quest2"

    @JvmStatic
    fun main(args: Array<String>) {
        directory = Path.of(args[0])
        phase = args[1]
        val mode = args[2]
        action = args[3]
        if (phase != "crash") state = mapper.readValue(directory.resolve("hof.json").readText(), HomeReceiptHofState::class.java)
        SpringApplicationBuilder(HofApplication::class.java, Remote::class.java).profiles("test").run(
            "--server.port=0", "--hof.automation-convergence.mode=$mode",
            "--spring.datasource.url=jdbc:h2:file:${directory.resolve("database")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0",
        ).use { context ->
            diagnostics = context.getBean(JdbcTemplate::class.java)
            val clock = context.getBean(AutomationRecoveryIntegrationTest.RecoveryClock::class.java)
            clock.current = maxOf(Instant.parse("2026-09-11T00:00:00Z").plusSeconds(if (phase == "crash") 0 else 301),
                state.clockAt?.let(Instant::parse) ?: Instant.MIN)
            if (phase == "crash") {
                val em = context.getBean(EntityManager::class.java)
                lateinit var account: HofAccountEntity
                TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).executeWithoutResult {
                    account = HofAccountEntity(loginId = "home-receipt-process", encryptedPassword = "fixture", createdAt = clock.now())
                    em.persist(account)
                    em.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = clock.now()))
                    em.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                        timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
                    val entry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 0,
                        enabled = true, createdAt = clock.now(), updatedAt = clock.now())
                    em.persist(entry)
                    val quests = HomePageParser().parse(HomeMode.HOME, page(), HOME_URL, HofFormParser().parse(page(), HOME_URL)).quests
                    check(quests.size == 2 && quests.all { it.stateObserved })
                    quests.forEachIndexed { index, quest -> em.persist(HomeQuestAutomationSelectionEntity(entry = entry,
                        questId = quest.id, questName = quest.name, enabled = true, sourceOrder = index)) }
                }
                context.getBean(RefreshTokenService::class.java).issue(account, "NATIVE")
                context.getBean(UnifiedAutomationService::class.java).startTyped(account.id)
            } else context.getBean(AutomationWakeupPort::class.java).wake(1L, "HOME_PROCESS_RESTART")

            val publisher = context.getBean(AutomationOutboxPublisher::class.java)
            val transport = context.getBean(AutomationRecoveryIntegrationTest.ConsumerReplayTransport::class.java)
            val outbox = context.getBean(AutomationOutboxQueryRepository::class.java)
            repeat(5) {
                val due = requireNotNull(diagnostics.queryForObject(
                    "select min(available_at) from automation_outbox where published_at is null",
                    java.sql.Timestamp::class.java)).toInstant()
                clock.current = maxOf(clock.now(), due)
                val before = transport.delivered.size
                publisher.publishBatch()
                check(transport.delivered.size > before)
                check(transport.delivered.all { outbox.consumed(it) })
            }
            check(phase != "crash") { "실제 수신 응답의 로컬 완료 지점에 도달하지 못했다: $state" }
            val interrupted = mapper.readTree(directory.resolve("interrupted.json").readText())
            val identity = interrupted["identity"].asString()
            val target = interrupted["target"].asString()
            val events = context.getBean(AutomationDecisionJournal::class.java).page(1L, AutomationHistoryQuery())
                .cycles.flatMap { it.events }.filter { it.targetKey == target && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }
            val record = context.getBean(ConvergenceStore::class.java).get(1L, identity)
            val result = mapOf(
                "pid" to ProcessHandle.current().pid(), "identity" to identity,
                "status" to diagnostics.queryForObject("select status from typed_automation_action_runs where execution_identity = ?", String::class.java, identity),
                "canonical" to (record?.result?.name ?: "NONE"),
                "shadow" to diagnostics.queryForList("select new_result from automation_convergence_shadow_evaluations where execution_identity_hash = ?",
                    String::class.java, ProductionEvidenceShapes.fingerprint(identity)).joinToString(),
                "originalSubmissions" to state.submissions.count { it.endsWith(":A") },
                "independentSubmissions" to state.submissions.count { it.endsWith(":B") },
                "successHistory" to events,
                "runningWorks" to diagnostics.queryForObject("select count(*) from automation_work_sessions where running_slot is not null", Int::class.java),
                "accountLeases" to diagnostics.queryForObject("select count(*) from account_automation_leases", Int::class.java),
                "runtimeLeaseReleased" to (diagnostics.queryForObject("select lease_token from typed_automation_runtime_states where account_id = 1", String::class.java) == null),
                "consumedWakes" to transport.delivered.size,
            )
            directory.resolve("result.json").writeText(mapper.writeValueAsString(result))
            state.clockAt = clock.now().toString()
            directory.resolve("hof.json").writeText(mapper.writeValueAsString(state))
        }
    }

    private fun page(): String = "<div id='menu2'>Funds : $ 1 Time : 100/100</div>" + listOf("A", "B").joinToString("") { id ->
        val submitted = state.submissions.any { it.endsWith(":$id") }
        val claiming = id == "A" && action == "CLAIM"
        val heading = if (!submitted) "수락 가능한 작업 목록" else if (claiming) "대기중인 작업 목록" else "진행중인 작업 목록"
        val link = if (submitted) "-" else "<a href='?menu=quest2&amp;action=${if (claiming) "complete" else "get"}&amp;no=$id'>${if (claiming) "보상 수령" else "수락"}</a>"
        """<h4>$heading</h4><table><tr><td>[$id] 프로세스 복원 $id</td><td>미션 0/1</td><td>-</td><td>-</td><td>$link</td></tr></table>"""
    }

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
                check(request.method == HofHttpMethod.GET && request.url.contains("menu=quest2"))
                request.formFields["action"]?.let { requested ->
                    val target = request.formFields.getValue("no")
                    check(target in setOf("A", "B"))
                    check(requested == if (target == "A" && action == "CLAIM") "complete" else "get")
                    val submitted = "$requested:$target"
                    check(submitted !in state.submissions) { "원래 자택 행동을 다시 제출했다: $submitted" }
                    state.submissions += submitted
                }
                directory.resolve("hof.json").writeText(mapper.writeValueAsString(state))
                return HofHttpResponse(200, HOME_URL, page(), emptyMap())
            }
        }

        companion object {
            @Bean @JvmStatic
            fun crashBeforeHomeCompletion(): BeanPostProcessor = object : BeanPostProcessor {
                override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                    if (bean !is AutomationResultCoordinator) return bean
                    return ProxyFactory(bean).apply {
                        isProxyTargetClass = true
                        addAdvice(MethodInterceptor { invocation ->
                            if (phase == "crash" && invocation.method.name == "applyDirect") {
                                val managed = invocation.arguments[0] as ManagedAutomationAction
                                val payload = managed.storedAction.payload as StoredTypedActionPayload.HomeQuest
                                check(invocation.arguments[2] is AutomationActionEvidence.DirectApplied)
                                check(state.submissions.size == 1 && state.submissions.single().endsWith(":A"))
                                val identity = managed.storedAction.executionIdentity
                                val json = requireNotNull(diagnostics.queryForObject(
                                    "select direct_response_json from typed_automation_action_runs where execution_identity = ?", String::class.java, identity))
                                val receipt = mapper.readValue(json, StoredAutomationDirectResponse::class.java)
                                val received = (receipt.response as AutomationDirectResponse.HomePage).quests.single { it.id == payload.questId }
                                check(received.stateObserved)
                                directory.resolve("interrupted.json").writeText(mapper.writeValueAsString(mapOf(
                                    "pid" to ProcessHandle.current().pid(), "identity" to identity, "target" to payload.questId,
                                    "status" to diagnostics.queryForObject("select status from typed_automation_action_runs where execution_identity = ?", String::class.java, identity),
                                    "actionKind" to receipt.policyContext?.actionKind?.name,
                                    "submissions" to state.submissions.size, "stateObserved" to received.stateObserved,
                                )))
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
