package app.spammy.hof.automation.service

import app.spammy.hof.HofApplication
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.auth.service.RefreshTokenService
import app.spammy.hof.automation.convergence.AutomationActionEvidence
import app.spammy.hof.automation.convergence.ConvergenceStore
import app.spammy.hof.automation.convergence.ProductionEvidenceShapes
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.outbox.AutomationOutboxPublisher
import app.spammy.hof.automation.outbox.AutomationOutboxQueryRepository
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
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
import org.junit.jupiter.params.provider.ValueSource
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

class AutomationFishingBattleDirectReceiptProcessRestartTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @ValueSource(strings = ["ACTIVE", "SHADOW", "LEGACY"])
    fun `낚시 전투 응답 저장 뒤 JVM을 종료해도 원래 결과와 독립 자택을 복구하고 새 낚시를 한 번 실행한다`(mode: String) {
        assertEquals(71, runProcess("crash", mode))
        val mapper = jacksonObjectMapper()
        val interrupted = mapper.readTree(directory.resolve("interrupted.json").readText())
        assertEquals("SUBMITTING", interrupted["status"].asString())
        assertEquals("FISHING_OBSTRUCTION_BATTLE", interrupted["actionKind"].asString())
        assertEquals("VICTORY", interrupted["outcomes"][0].asString())
        assertEquals(1, interrupted["battleSubmissions"].asInt())
        assertEquals(0, interrupted["successfulRuns"].asInt())
        assertEquals(0, interrupted["completedFishingWorks"].asInt())
        val processes = mutableSetOf(interrupted["pid"].asLong())
        var originalHistory: String? = null
        var castHistory: String? = null
        for (phase in listOf("resume", "resume-again", "next-cast", "resume-final")) {
            val expectedCasts = if (phase in setOf("next-cast", "resume-final")) 1 else 0
            assertEquals(0, runProcess(phase, mode))
            val result = mapper.readTree(directory.resolve("result.json").readText())
            assertTrue(processes.add(result["pid"].asLong()), "각 복원은 별도 JVM에서 수행해야 한다.")
            assertEquals(interrupted["identity"].asString(), result["identity"].asString())
            assertEquals("SUCCEEDED", result["status"].asString(), result.toString())
            assertEquals(if (mode == "ACTIVE") "APPLIED" else "NONE", result["canonical"].asString())
            assertEquals(if (mode == "SHADOW") "APPLIED" else "", result["shadow"].asString())
            assertEquals(1, result["battleSubmissions"].asInt(), result.toString())
            assertEquals(expectedCasts, result["startSubmissions"].asInt())
            assertEquals(expectedCasts, result["catchSubmissions"].asInt())
            assertEquals(1, result["homeSubmissions"].asInt(), result.toString())
            assertEquals(1, result["successfulRuns"].asInt())
            assertEquals(expectedCasts * 2, result["successfulCastRuns"].asInt())
            assertEquals(expectedCasts + 1, result["completedFishingWorks"].asInt())
            assertEquals(1, result["successHistory"].size(), result.toString())
            if (originalHistory == null) originalHistory = result["successHistory"].toString()
            else assertEquals(originalHistory, result["successHistory"].toString())
            assertEquals(expectedCasts * 2, result["castSuccessHistory"].size())
            if (expectedCasts == 1) {
                if (castHistory == null) castHistory = result["castSuccessHistory"].toString()
                else assertEquals(castHistory, result["castSuccessHistory"].toString())
            }
            assertTrue(result["storedResponseRestored"].asBoolean())
            assertEquals(0, result["runningWorks"].asInt())
            assertEquals(0, result["accountLeases"].asInt())
            assertTrue(result["runtimeLeaseReleased"].asBoolean())
            assertTrue(result["consumedWakes"].asInt() >= 5)
        }
    }

    private fun runProcess(phase: String, mode: String): Int {
        val classpath = generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.map { Path.of(it.toURI()).toString() }.toList()
            .plus(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
            .distinct().joinToString(java.io.File.pathSeparator)
        val output = directory.resolve("$phase.log").toFile()
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx512m",
            "-cp", classpath, AutomationFishingBattleDirectReceiptCrashProcess::class.java.name,
            directory.toString(), phase, mode).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "child timed out: ${output.readText().takeLast(8000)}")
            return process.exitValue().also { assertTrue(it in setOf(0, 71), output.readText().takeLast(12000)) }
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}

internal data class FishingBattleReceiptHofState(
    var battleSubmissions: Int = 0,
    var homeSubmissions: Int = 0,
    var startSubmissions: Int = 0,
    var catchSubmissions: Int = 0,
    var clockAt: String? = null,
)

/** 원래 응답 commit 직후 종료하고 같은 파일 DB를 새 Spring/JPA/runtime/consumer에서 연다. */
object AutomationFishingBattleDirectReceiptCrashProcess {
    private lateinit var directory: Path
    private lateinit var phase: String
    private lateinit var diagnostics: JdbcTemplate
    private var state = FishingBattleReceiptHofState()
    private val mapper = jacksonObjectMapper()
    private const val HOME_URL = "https://hof.zerosic.com/index.php?menu=quest2"

    @JvmStatic
    fun main(args: Array<String>) {
        directory = Path.of(args[0])
        phase = args[1]
        val mode = args[2]
        if (phase != "crash") state = mapper.readValue(directory.resolve("hof.json").readText(), FishingBattleReceiptHofState::class.java)
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
                    account = HofAccountEntity(loginId = "fishing-battle-receipt-process", encryptedPassword = "fixture", createdAt = clock.now())
                    em.persist(account)
                    em.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = clock.now()))
                    em.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                        timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
                    val entry = AutomationEntryEntity(account = account, type = AutomationType.FISHING, singletonTypeMarker = AutomationType.FISHING, priority = 0,
                        enabled = true, createdAt = clock.now(), updatedAt = clock.now())
                    em.persist(entry)
                    val preset = PartyPresetEntity(account = account, name = "낚시 전투 파티", isPrimary = true,
                        createdAt = clock.now(), updatedAt = clock.now())
                    em.persist(preset)
                    repeat(1) { index ->
                        val character = CharacterEntity(account = account, hofCharacterId = "process-$index",
                            name = "process-$index", job = "Knight", updatedAt = clock.now())
                        em.persist(character)
                        val pattern = CharacterPatternSlotEntity(character = character, slotCode = "1", label = "기본", canLoad = true)
                        em.persist(pattern)
                        em.persist(PartyPresetMemberEntity(preset, index, character, pattern))
                    }
                    val home = HomePageParser().parse(HomeMode.HOME, homePage(), HOME_URL,
                        HofFormParser().parse(homePage(), HOME_URL)).quests.single()
                    val homeEntry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 1,
                        enabled = true, createdAt = clock.now(), updatedAt = clock.now())
                    em.persist(homeEntry)
                    em.persist(HomeQuestAutomationSelectionEntity(entry = homeEntry, questId = home.id,
                        questName = home.name, enabled = true, sourceOrder = 0))
                }
                context.getBean(RefreshTokenService::class.java).issue(account, "NATIVE")
                context.getBean(UnifiedAutomationService::class.java).startTyped(account.id)
            } else context.getBean(AutomationWakeupPort::class.java).wake(1L, "FISHING_BATTLE_PROCESS_RESTART")

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
            check(phase != "crash") { "원래 낚시 전투의 로컬 완료 지점에 도달하지 못했다: $state" }
            val interrupted = mapper.readTree(directory.resolve("interrupted.json").readText())
            val identity = interrupted["identity"].asString()
            val cycles = context.getBean(AutomationDecisionJournal::class.java).page(1L, AutomationHistoryQuery()).cycles
            val events = cycles.flatMap { it.events }
                .filter { it.actionKind == "BATTLE_MAP" && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }
            val record = context.getBean(ConvergenceStore::class.java).get(1L, identity)
            val result = mapOf(
                "pid" to ProcessHandle.current().pid(), "identity" to identity,
                "status" to diagnostics.queryForObject("select status from typed_automation_action_runs where execution_identity = ?", String::class.java, identity),
                "canonical" to (record?.result?.name ?: "NONE"),
                "shadow" to diagnostics.queryForList("select new_result from automation_convergence_shadow_evaluations where execution_identity_hash = ?",
                    String::class.java, ProductionEvidenceShapes.fingerprint(identity)).joinToString(),
                "battleSubmissions" to state.battleSubmissions, "homeSubmissions" to state.homeSubmissions,
                "successfulRuns" to successfulRuns(), "completedFishingWorks" to completedFishingWorks(),
                "startSubmissions" to state.startSubmissions, "catchSubmissions" to state.catchSubmissions,
                "successfulCastRuns" to diagnostics.queryForObject(
                    "select count(*) from typed_automation_action_runs where action_kind = 'FISHING_TOWN' and status = 'SUCCEEDED'", Int::class.java),
                "successHistory" to events,
                "castSuccessHistory" to cycles.flatMap { it.events }.filter {
                    it.actionKind in setOf("START", "CATCH") && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED
                }.sortedBy { it.id },
                "storedResponseRestored" to cycles.flatMap { it.events }.any { it.reasonCode == "STORED_DIRECT_RESPONSE_RESTORE" && it.actionKind == "BATTLE_MAP" },
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

    private fun successfulRuns() = diagnostics.queryForObject(
        "select count(*) from typed_automation_action_runs where account_id = 1 and action_kind = 'BATTLE_MAP' and status = 'SUCCEEDED'", Int::class.java)

    private fun completedFishingWorks() = diagnostics.queryForObject(
        "select count(*) from automation_work_sessions where work_type = 'FISHING' and status = 'COMPLETED'", Int::class.java)

    private fun fishingPage(): String {
        fun fixture(name: String) = requireNotNull(
            AutomationFishingBattleDirectReceiptCrashProcess::class.java.getResource("/fixtures/town/fishing/$name.html"),
        ).readText()
        return when {
            state.startSubmissions > state.catchSubmissions -> fixture("waiting")
            phase == "next-cast" && state.startSubmissions == 0 -> fixture("reset")
            else -> fixture("reset").replace("18회", "0회")
        }
    }

    private fun homePage(): String {
        val heading = if (state.homeSubmissions > 0) "진행중인 작업 목록" else "수락 가능한 작업 목록"
        val action = if (state.homeSubmissions > 0) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"
        return """<div id='menu2'>Funds : $ 1 Time : 100/100</div><h4>$heading</h4><table>
            <tr><td>[A] 독립 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>$action</td></tr></table>"""
    }

    private fun maps(): String = """<html><body><div id='menu2'>Funds : $ 1 Time : 100/100</div>
        <div id='contents'><div id='mapgroup1'><a href='index.php?common=0001'>일반 맵</a></div>
        ${if (state.battleSubmissions == 0) "<a href='index.php?common=fishing_12'>Fishing- 악어</a>" else ""}</div>
        <div id='foot'><h5>Copy Right sanitized fixture</h5><h6>H.O.F Korean Ver sanitized fixture</h6>
        <img src='image/zerohof.gif'></div></body></html>"""

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
                val body = when {
                    request.url.contains("?char=") -> "<div>Funds : $ 1 Time : 100/100</div>" +
                        app.spammy.hof.character.service.currentPatternForm() + app.spammy.hof.character.service.savedPatternLoadForm(1)
                    request.url.contains("menu=housing") || request.url.contains("menu=quest2") -> {
                        if (request.formFields["action"] == "get") {
                            check(request.method == HofHttpMethod.GET && request.formFields["no"] == "A")
                            check(state.homeSubmissions++ == 0) { "독립 자택을 중복 제출했다." }
                        }
                        homePage()
                    }
                    "FStart" in request.formFields -> {
                        check(phase == "next-cast" && state.battleSubmissions == 1)
                        check(request.method == HofHttpMethod.POST && request.formFields["FStart"] == "낚시를 시작한다")
                        check(state.startSubmissions++ == 0) { "새 낚시 START를 중복 제출했다." }
                        fishingPage()
                    }
                    "FCatch" in request.formFields -> {
                        check(phase == "next-cast" && state.startSubmissions == 1)
                        check(request.method == HofHttpMethod.POST && request.formFields["FCatch"] == "낚는다")
                        check(state.catchSubmissions++ == 0) { "새 낚시 CATCH를 중복 제출했다." }
                        requireNotNull(AutomationFishingBattleDirectReceiptCrashProcess::class.java
                            .getResource("/fixtures/town/fishing/caught.html")).readText()
                    }
                    request.url.contains("menu=fishing") -> fishingPage()
                    request.method == HofHttpMethod.GET -> maps()
                    else -> {
                        check(request.method == HofHttpMethod.POST &&
                            request.url == "https://hof.zerosic.com/index.php?common=fishing_12")
                        check(state.battleSubmissions++ == 0) { "이미 완료한 낚시 전투를 중복 제출했다." }
                        """<div id="menu2">Funds : $ 1 Time : 100/100</div><h2>Show Detail( 1 turns. )</h2><h1>테스트은(는) 승리했다!</h1>
                            <div>남은 HP : 0/100 생존자 : 0/1 총 데미지 : 0</div>
                            <div>남은 HP : 100/100 생존자 : 1/1 총 데미지 : 100 턴 : 1/100 획득 경험치 : 1 획득 Funds : $ 1</div>"""
                    }
                }
                directory.resolve("hof.json").writeText(mapper.writeValueAsString(state))
                return HofHttpResponse(200, request.url, "<div id='menu2'>Funds : $ 1 Time : 100/100</div>" + body, emptyMap())
            }
        }

        companion object {
            @Bean @JvmStatic
            fun crashBeforeFishingBattleCompletion(): BeanPostProcessor = object : BeanPostProcessor {
                override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                    if (bean !is AutomationResultCoordinator) return bean
                    return ProxyFactory(bean).apply {
                        isProxyTargetClass = true
                        addAdvice(MethodInterceptor { invocation ->
                            val managed = invocation.arguments.firstOrNull() as? ManagedAutomationAction
                            if (phase == "crash" && invocation.method.name == "applyDirect" &&
                                managed?.storedAction?.payload is StoredTypedActionPayload.BattleMap
                            ) {
                                check(invocation.arguments[2] is AutomationActionEvidence.DirectApplied)
                                check(state.battleSubmissions == 1 && state.homeSubmissions == 0)
                                val identity = managed.storedAction.executionIdentity
                                val json = requireNotNull(diagnostics.queryForObject(
                                    "select direct_response_json from typed_automation_action_runs where execution_identity = ?", String::class.java, identity))
                                val receipt = mapper.readValue(json, StoredAutomationDirectResponse::class.java)
                                val outcomes = (receipt.response as AutomationDirectResponse.BattleMap).outcomes
                                check(outcomes == listOf(BattleAutomationRoundOutcome.VICTORY))
                                directory.resolve("interrupted.json").writeText(mapper.writeValueAsString(mapOf(
                                    "pid" to ProcessHandle.current().pid(), "identity" to identity,
                                    "status" to diagnostics.queryForObject("select status from typed_automation_action_runs where execution_identity = ?", String::class.java, identity),
                                    "actionKind" to receipt.policyContext?.actionKind?.name, "outcomes" to outcomes,
                                    "battleSubmissions" to state.battleSubmissions,
                                    "successfulRuns" to successfulRuns(), "completedFishingWorks" to completedFishingWorks(),
                                    "actionId" to diagnostics.queryForObject("select id from typed_automation_action_runs where execution_identity = ?", Long::class.java, identity),
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
