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

import app.spammy.hof.automation.raid.RaidCycleStore

class AutomationRaidBattleDirectReceiptProcessRestartTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest
    @ValueSource(strings = ["ACTIVE", "SHADOW", "LEGACY"])
    fun `레이드 응답 저장 뒤 JVM을 종료해도 원래 종료 시각과 결과를 복구하고 다음 전투를 한 번 실행한다`(mode: String) {
        assertEquals(71, runProcess("crash", mode))
        val mapper = jacksonObjectMapper()
        val interrupted = mapper.readTree(directory.resolve("interrupted.json").readText())
        assertEquals("SUBMITTING", interrupted["status"].asString())
        assertEquals("RAID_BATTLE", interrupted["actionKind"].asString())
        assertEquals("VICTORY", interrupted["outcomes"][0].asString())
        assertEquals("2026-09-11T00:00:00Z", interrupted["finishedAt"].asString())
        assertEquals(1, interrupted["battleSubmissions"].asInt())
        assertEquals(0, interrupted["successfulRuns"].asInt())
        val processes = mutableSetOf(interrupted["pid"].asLong())
        var originalHistory: String? = null
        var bothHistories: String? = null
        for (phase in listOf("resume", "resume-again", "next-battle", "resume-final")) {
            val expectedBattles = if (phase in setOf("next-battle", "resume-final")) 2 else 1
            assertEquals(0, runProcess(phase, mode))
            val result = mapper.readTree(directory.resolve("result.json").readText())
            assertTrue(processes.add(result["pid"].asLong()), "각 복원은 별도 JVM에서 수행한다.")
            assertEquals(interrupted["identity"].asString(), result["identity"].asString())
            assertEquals("SUCCEEDED", result["status"].asString(), result.toString())
            assertEquals(if (mode == "ACTIVE") "APPLIED" else "NONE", result["canonical"].asString())
            assertEquals(if (mode == "SHADOW") "APPLIED" else "", result["shadow"].asString())
            assertEquals(expectedBattles, result["battleSubmissions"].asInt(), result.toString())
            assertEquals(expectedBattles, result["successfulRuns"].asInt())
            assertEquals(expectedBattles, result["successHistory"].size(), phase + ": " + result)
            assertEquals(1, result["homeSubmissions"].asInt())
            assertEquals(1, result["originalSuccessHistory"].size())
            if (originalHistory == null) originalHistory = result["originalSuccessHistory"].toString()
            else assertEquals(originalHistory, result["originalSuccessHistory"].toString())
            if (expectedBattles == 2) {
                if (bothHistories == null) bothHistories = result["successHistory"].toString()
                else assertEquals(bothHistories, result["successHistory"].toString())
            }
            val projection = mapper.readTree(directory.resolve("restored-projection.json").readText())
            assertEquals("LOCAL_FALLBACK", projection["source"].asString())
            assertEquals(interrupted["identity"].asString(), projection["executionIdentity"].asString())
            assertEquals("2026-09-11T00:00:00Z", projection["startedAt"].asString())
            assertEquals("2026-09-11T00:02:00Z", projection["notBefore"].asString())
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
            "-cp", classpath, AutomationRaidBattleDirectReceiptCrashProcess::class.java.name,
            directory.toString(), phase, mode).redirectErrorStream(true).redirectOutput(output).start()
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "child timed out: ${output.readText().takeLast(8000)}")
            return process.exitValue().also { assertTrue(it in setOf(0, 71), output.readText().takeLast(12000)) }
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}

internal data class RaidBattleReceiptHofState(
    var battleSubmissions: Int = 0,
    var homeSubmissions: Int = 0,
    var clockAt: String? = null,
)

/** 원래 응답 commit 직후 종료하고 같은 파일 DB를 새 Spring/JPA/runtime/consumer에서 연다. */
object AutomationRaidBattleDirectReceiptCrashProcess {
    private lateinit var directory: Path
    private lateinit var phase: String
    private lateinit var diagnostics: JdbcTemplate
    private lateinit var raidStore: RaidCycleStore
    private var state = RaidBattleReceiptHofState()
    private val mapper = jacksonObjectMapper()
    private const val HOME_URL = "https://hof.zerosic.com/index.php?menu=quest2"

    @JvmStatic
    fun main(args: Array<String>) {
        directory = Path.of(args[0])
        phase = args[1]
        val mode = args[2]
        if (phase != "crash") state = mapper.readValue(directory.resolve("hof.json").readText(), RaidBattleReceiptHofState::class.java)
        SpringApplicationBuilder(HofApplication::class.java, Remote::class.java).profiles("test").run(
            "--server.port=0", "--hof.automation-convergence.mode=$mode",
            "--spring.datasource.url=jdbc:h2:file:${directory.resolve("database")};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0",
        ).use { context ->
            diagnostics = context.getBean(JdbcTemplate::class.java)
            raidStore = context.getBean(RaidCycleStore::class.java)
            val clock = context.getBean(AutomationRecoveryIntegrationTest.RecoveryClock::class.java)
            clock.current = maxOf(Instant.parse("2026-09-11T00:00:00Z").plusSeconds(if (phase == "crash") 0 else 301),
                state.clockAt?.let(Instant::parse)?.let { if (phase == "next-battle") it.plusSeconds(301) else it } ?: Instant.MIN)
            if (phase == "crash") {
                val em = context.getBean(EntityManager::class.java)
                lateinit var account: HofAccountEntity
                TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).executeWithoutResult {
                    account = HofAccountEntity(loginId = "raid-battle-receipt-process", encryptedPassword = "fixture", createdAt = clock.now())
                    em.persist(account)
                    em.persist(HofCookieEntity(account = account, name = "PHPSESSID", value = "fixture", updatedAt = clock.now()))
                    em.persist(HofStatusSnapshotEntity(account = account, playerName = "테스트", funds = 1,
                        timeCurrent = 100, timeMax = 100, work = "", auction = "", observedAt = clock.now()))
                    val entry = AutomationEntryEntity(account = account, type = AutomationType.RAID, singletonTypeMarker = AutomationType.RAID, priority = 0,
                        enabled = true, createdAt = clock.now(), updatedAt = clock.now())
                    em.persist(entry)
                    em.persist(RaidAutomationTargetEntity(entry = entry, raidId = "RaidGoblin", displayName = "고블린 전투 마차",
                        presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
                    em.persist(RaidAutomationCycleEntity(account = account, entry = entry, raidId = "RaidGoblin",
                        raidName = "고블린 전투 마차", status = RaidAutomationCycleStatus.IN_BATTLE,
                        lastObservedStatus = "전투 중", startedAt = clock.now(), updatedAt = clock.now()))
                    val preset = PartyPresetEntity(account = account, name = "레이드 전투 파티", isPrimary = true,
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
            } else context.getBean(AutomationWakeupPort::class.java).wake(1L, "RAID_BATTLE_PROCESS_RESTART")

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
            check(phase != "crash") { "원래 레이드 전투의 로컬 완료 지점에 도달하지 못했다: $state" }
            val interrupted = mapper.readTree(directory.resolve("interrupted.json").readText())
            val identity = interrupted["identity"].asString()
            val journal = context.getBean(AutomationDecisionJournal::class.java)
            val cycles = buildList {
                var cursor: Long? = null
                do {
                    val page = journal.page(1L, AutomationHistoryQuery(beforeCycleId = cursor))
                    addAll(page.cycles)
                    cursor = page.nextCursor
                } while (cursor != null)
            }
            val events = cycles.flatMap { it.events }
                .filter { it.actionKind == "BATTLE_MAP" && it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED }
            val record = context.getBean(ConvergenceStore::class.java).get(1L, identity)
            val originalIdsPath = directory.resolve("original-event-ids.json")
            if (phase == "resume") originalIdsPath.writeText(mapper.writeValueAsString(events.map { it.id }))
            val originalIds = mapper.readValue(originalIdsPath.readText(), LongArray::class.java).toSet()
            val result = mapOf(
                "pid" to ProcessHandle.current().pid(), "identity" to identity,
                "status" to diagnostics.queryForObject("select status from typed_automation_action_runs where execution_identity = ?", String::class.java, identity),
                "canonical" to (record?.result?.name ?: "NONE"),
                "shadow" to diagnostics.queryForList("select new_result from automation_convergence_shadow_evaluations where execution_identity_hash = ?",
                    String::class.java, ProductionEvidenceShapes.fingerprint(identity)).joinToString(),
                "battleSubmissions" to state.battleSubmissions, "homeSubmissions" to state.homeSubmissions,
                "successfulRuns" to successfulRuns(),
                "successHistory" to events.sortedBy { it.id },
                "originalSuccessHistory" to events.filter { it.id in originalIds },
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

    private fun homePage(): String {
        val heading = if (state.homeSubmissions > 0) "진행중인 작업 목록" else "수락 가능한 작업 목록"
        val action = if (state.homeSubmissions > 0) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"
        return """<div id='menu2'>Funds : $ 1 Time : 100/100</div><h4>$heading</h4><table>
            <tr><td>[A] 독립 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>$action</td></tr></table>"""
    }

    private fun raidPage(): String = requireNotNull(
        AutomationRaidBattleDirectReceiptCrashProcess::class.java.getResource("/fixtures/town/raid/raidpub.html"),
    ).readText().replace("현재사용자", "테스트").replace("현재 상태 : 모집 중", "현재 상태 : 전투 중")

    private fun maps(): String {
        val cooldown = state.battleSubmissions > 0 && (phase != "next-battle" || state.battleSubmissions >= 2)
        return requireNotNull(AutomationRaidBattleDirectReceiptCrashProcess::class.java
            .getResource("/fixtures/raid/raid-complete-absent.html")).readText()
            .replace("<p>진행 중인 전투가 없습니다.</p>", "<div id='mapgroup1'><div>" +
                (if (cooldown) "<span>다음 전투까지 99초 남음</span>" else "") +
                "<a href='index.php?raid_common=RaidGoblin'>고블린 전투 마차</a></div></div>")
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
                    request.url.contains("menu=raidpub") -> {
                        check(request.method == HofHttpMethod.GET)
                        raidPage()
                    }
                    request.url.endsWith("?raid_hunt") -> maps()
                    else -> {
                        check(request.method == HofHttpMethod.POST &&
                            request.url == "https://hof.zerosic.com/index.php?raid_common=RaidGoblin")
                        check((phase == "crash" && state.battleSubmissions == 0) ||
                            (phase == "next-battle" && state.battleSubmissions == 1)) { "원래 전투를 중복 제출했다: " + phase + "/" + state }
                        state.battleSubmissions++
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
            fun crashBeforeRaidBattleCompletion(): BeanPostProcessor = object : BeanPostProcessor {
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
                                val response = receipt.response as AutomationDirectResponse.BattleMap
                                val outcomes = response.outcomes
                                check(outcomes == listOf(BattleAutomationRoundOutcome.VICTORY))
                                directory.resolve("interrupted.json").writeText(mapper.writeValueAsString(mapOf(
                                    "pid" to ProcessHandle.current().pid(), "identity" to identity,
                                    "status" to diagnostics.queryForObject("select status from typed_automation_action_runs where execution_identity = ?", String::class.java, identity),
                                    "actionKind" to receipt.policyContext?.actionKind?.name, "outcomes" to outcomes,
                                    "battleSubmissions" to state.battleSubmissions,
                                    "successfulRuns" to successfulRuns(), "finishedAt" to response.finishedAt.toString(),
                                    "actionId" to diagnostics.queryForObject("select id from typed_automation_action_runs where execution_identity = ?", Long::class.java, identity),
                                )))
                                Runtime.getRuntime().halt(71)
                            }
                            val result = invocation.proceed()
                            if (phase == "resume" && invocation.method.name == "applyDirect" &&
                                managed?.storedAction?.payload is StoredTypedActionPayload.BattleMap) {
                                val gate = requireNotNull(raidStore.load(1L).openCycle?.battleSafetyGate)
                                directory.resolve("restored-projection.json").writeText(mapper.writeValueAsString(gate))
                            }
                            result
                        })
                    }.proxy
                }
            }
        }
    }
}
