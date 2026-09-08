package app.spammy.hof.character.service

import app.spammy.hof.HofApplication
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.history.AutomationDecisionJournal
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.automation.outbox.AutomationOutboxPollingScheduler
import app.spammy.hof.automation.outbox.AutomationOutboxPublisher
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterEquipmentSavedItemEntity
import app.spammy.hof.character.entity.CharacterEquipmentSavedSlotEntity
import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.parser.HomePageParser
import jakarta.persistence.EntityManager
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper

/** HTTP부터 영속 원본·실제 명령·복구·로컬 자동화 깨우기 소비까지 연결한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["hof.automation-convergence.mode=ACTIVE"])
@ActiveProfiles("test")
@Import(CharacterSyncApplicationFixture::class)
class CharacterSyncApplicationTest {
    @Autowired private lateinit var context: ApplicationContext
    @Value("\${local.server.port}") private var port = 0
    private val http = HttpClient.newHttpClient()
    private val mapper = jacksonObjectMapper()

    @Test
    fun `실제 API로 수집한 서버 설정과 복원 원본을 확인하고 다음 자동화 판단까지 진행한다`() = verify(false)

    @Test
    fun `수집 요청이 거부돼도 실제 원본을 복구하고 실패 상태와 자동화 후속 판단을 보존한다`() = verify(true)

    private fun verify(fail: Boolean) {
        val loginId = "sync-${UUID.randomUUID()}"
        val login = request("/api/auth/login", body = """{"loginId":"$loginId","password":"fixture-only","clientType":"NATIVE"}""")
        val token = login["accessToken"].asString()
        val prepared = CharacterSyncAppHarness.prepare(context, loginId, fail)
        val started = request("/api/characters/records/${prepared.characterId}/deep-sync-jobs", token, "{}")
        val jobId = started["id"].asLong()
        var terminal = started
        CharacterSyncAppHarness.await {
            terminal = request("/api/characters/operation-jobs/$jobId", token)
            terminal["status"].asString() in setOf("FAILED", "COMPLETED")
        }
        assertEquals(if (fail) "FAILED" else "COMPLETED", terminal["status"].asString(), terminal.toString())
        assertEquals("RESTORED", terminal["recoveryStatus"].asString(), terminal.toString())
        val evidence = CharacterSyncAppHarness.verify(context, prepared)
        assertEquals(jobId, evidence["jobId"])
        val detail = request("/api/characters/records/${prepared.characterId}", token)
        assertEquals(10, detail["actionPatterns"].size())
        assertEquals("9564", detail["actionPatterns"][0]["skill"].asString())
        assertTrue((evidence["followingDecisionIds"] as List<*>).size >= 2)
    }

    private fun request(path: String, token: String? = null, body: String? = null): JsonNode {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        token?.let { builder.header("Authorization", "Bearer $it") }
        if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), "API $path 응답 상태")
        return mapper.readTree(response.body())
    }
}

@TestConfiguration(proxyBeanMethods = false)
class CharacterSyncApplicationFixture {
    @Bean
    fun syncOutboxPolling(publisher: AutomationOutboxPublisher) = AutomationOutboxPollingScheduler(publisher)

    @Bean @Primary
    fun characterSyncHofFixture(jdbc: JdbcTemplate) = CharacterSyncHofFixture().apply {
        beforeCharacterMutation = { accountId ->
            check(jdbc.queryForObject("select lifecycle_status from typed_automation_runtime_states where account_id = ?", String::class.java, accountId) == "PAUSED") {
                "캐릭터 변경은 자동화 일시정지 안에서만 실행해야 한다."
            }
        }
    }
}

/** 같은 fixture를 서버 통합 검사와 실제 앱 버튼 검증에서 사용한다. 제품 API는 추가하지 않는다. */
object CharacterSyncAppHarness {
    data class Prepared(val accountId: Long, val characterId: Long, val original: CharacterRestoreState)

    fun prepare(context: ApplicationContext, loginId: String, fail: Boolean): Prepared {
        val jdbc = context.getBean(JdbcTemplate::class.java)
        val accountId = requireNotNull(jdbc.queryForObject("select id from hof_accounts where login_id = ?", Long::class.java, loginId))
        val fixture = context.getBean(CharacterSyncHofFixture::class.java)
        fixture.state(accountId).failSecondPreset = fail
        val current = fixture.current(accountId)
        val original = CharacterRestoreState.capture(current)
        val now = Instant.now()
        val manager = context.getBean(EntityManager::class.java)
        val query = context.getBean(CharacterQueryRepository::class.java)
        val characterId = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java)).execute {
            val account = manager.find(HofAccountEntity::class.java, accountId)
            val character = query.findByAccountIdAndHofCharacterId(accountId, "10") ?: CharacterEntity(
                account = account, hofCharacterId = "10", name = "동기화 검증", job = "Knight", updatedAt = now,
            ).also(manager::persist)
            manager.flush()
            context.getBean(CharacterSnapshotWriter::class.java).write(character, current, now)
            val oldSlot = CharacterEquipmentSavedSlotEntity(character = character, slotNumber = 2, observedAt = now.minusSeconds(86400))
            manager.persist(oldSlot)
            manager.persist(CharacterEquipmentSavedItemEntity(equipmentSavedSlot = oldSlot, itemOrder = 0,
                equipmentPart = "Old", name = "Old item", iconUrl = "", description = ""))
            val entry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST,
                singletonTypeMarker = AutomationType.HOME_QUEST, priority = 0, enabled = true, createdAt = now, updatedAt = now)
            manager.persist(entry)
            val url = "https://hof.zerosic.com/index.php?menu=quest2"
            val page = fixture.homePage(fixture.state(accountId))
            val quest = HomePageParser().parse(HomeMode.HOME, page, url, HofFormParser().parse(page, url)).quests.single()
            manager.persist(HomeQuestAutomationSelectionEntity(entry = entry, questId = quest.id, questName = quest.name, enabled = true, sourceOrder = 0))
            manager.persist(AdventureDailyRefreshEntity(account = account, refreshDate = LocalDate.now(ZoneId.of("Asia/Seoul")), refreshedAt = now))
            val runtime = manager.find(TypedAutomationRuntimeStateEntity::class.java, accountId)
            if (runtime == null) manager.persist(TypedAutomationRuntimeStateEntity(accountId = accountId, account = account,
                lifecycleStatus = TypedAutomationLifecycle.RUNNING, createdAt = now, updatedAt = now))
            else runtime.lifecycleStatus = TypedAutomationLifecycle.RUNNING
            character.id
        }
        return Prepared(accountId, requireNotNull(characterId), original)
    }

    fun verify(context: ApplicationContext, prepared: Prepared): Map<String, Any> {
        val jobs = context.getBean(CharacterOperationJobService::class.java)
        val fixture = context.getBean(CharacterSyncHofFixture::class.java)
        val journal = context.getBean(AutomationDecisionJournal::class.java)
        val job = requireNotNull(jobs.findCurrent(prepared.accountId, prepared.characterId))
        check(job.recoveryStatus == CharacterRecoveryStatus.RESTORED) { "원본 복구 미완료: ${job.status} / ${job.message}" }
        val saved = requireNotNull(context.getBean(CharacterDeepSyncRecovery::class.java).load(job.id, prepared.accountId, prepared.characterId))
        check(saved.original == prepared.original)
        check(CharacterRestoreState.capture(fixture.current(prepared.accountId)) == prepared.original)
        try {
            await { synchronized(fixture.state(prepared.accountId)) { fixture.state(prepared.accountId).acceptedQuest } && journal.page(prepared.accountId, AutomationHistoryQuery()).cycles.size >= 2 }
        } catch (failure: IllegalStateException) {
            val jdbc = context.getBean(JdbcTemplate::class.java)
            val reasons = journal.page(prepared.accountId, AutomationHistoryQuery(limit = 2)).cycles.flatMap { it.events }.map { it.reasonCode }
            error("${failure.message} runtime=${jdbc.queryForList("select lifecycle_status, auth_suspended from typed_automation_runtime_states where account_id = ?", prepared.accountId)} reasons=$reasons")
        }
        val cycles = journal.page(prepared.accountId, AutomationHistoryQuery()).cycles
        val submissions = synchronized(fixture.state(prepared.accountId)) { fixture.state(prepared.accountId).submissions.toList() }
        check(submissions.count { it.fields["action"] == "get" } == 1)
        val actionAt = submissions.single { it.fields["action"] == "get" }.at
        check(submissions.filter { "action" !in it.fields }.all { it.at < actionAt })
        check(cycles.any { it.startedAt > actionAt })
        check(cycles.flatMap { it.events }.any { it.kind.name == "ACTION_SUCCEEDED" })
        val query = context.getBean(CharacterQueryRepository::class.java)
        val patternSlot = requireNotNull(query.findPatternSlot(prepared.characterId, "0"))
        check(query.findSavedPatternRows(patternSlot.id).all { it.judge == "1" && it.quantity == "7" && it.skill == "0" })
        val equipmentSlot = requireNotNull(query.findEquipmentSavedSlot(prepared.characterId, 2))
        val archivedItems = query.findEquipmentSavedItems(equipmentSlot.id)
        if (fixture.state(prepared.accountId).failSecondPreset) check(archivedItems.single().name == "Old item")
        else check(archivedItems.isEmpty())
        return mapOf("jobId" to job.id, "status" to job.status.name, "recovery" to job.recoveryStatus.name,
            "patternRows" to prepared.original.patterns.size, "equipmentCount" to prepared.original.equipment.size,
            "followingDecisionIds" to cycles.map { it.id }, "characterPosts" to submissions.filter { "action" !in it.fields }.size,
            "automaticActionCount" to 1, "originalUnchanged" to true, "sourceMatchesOriginal" to true)
    }

    fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos()
        while (!condition()) {
            check(System.nanoTime() < until) { "전체 흐름의 다음 상태에 30초 안에 도달하지 못했습니다." }
            Thread.sleep(25)
        }
    }

    /** 실행 후 앱으로 로그인하고 stdin의 prepare/verify/quit만 사용한다. */
    @JvmStatic fun main(args: Array<String>) {
        val context = SpringApplicationBuilder(HofApplication::class.java, CharacterSyncApplicationFixture::class.java)
            .profiles("test").run(
                "--server.port=18080", "--server.address=127.0.0.1", "--hof.automation-convergence.mode=ACTIVE",
                "--spring.datasource.url=jdbc:h2:mem:sync_app;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
                "--hof.auth.allowed-origins=http://localhost:18081,http://127.0.0.1:18081",
            )
        val prepared = mutableMapOf<String, Prepared>()
        println("SYNC_APP_READY")
        try {
            while (true) {
                val command = readlnOrNull()?.trim()?.split(' ') ?: break
                if (command.first() == "quit") break
                runCatching {
                    val loginId = command[1]
                    when (command.first()) {
                        "prepare" -> {
                            prepared[loginId] = prepare(context, loginId, command.getOrNull(2) == "fail")
                            println("SYNC_APP_PREPARED $loginId")
                        }
                        "verify" -> println("SYNC_APP_VERIFIED " + jacksonObjectMapper().writeValueAsString(verify(context, requireNotNull(prepared[loginId]))))
                        else -> error("알 수 없는 검증 명령")
                    }
                }.onFailure { System.err.println("SYNC_APP_FAILED ${it.message}") }
            }
        } finally { context.close() }
    }
}
