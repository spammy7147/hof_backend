package app.spammy.hof.automation.service

import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterSyncMode
import app.spammy.hof.character.service.CharacterDeepSyncServiceTest
import app.spammy.hof.character.service.CharacterSyncJobService
import app.spammy.hof.character.service.currentPatternForm
import app.spammy.hof.character.service.savedPatternLoadForm
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.*

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
class CharacterBatchDeepSyncIntegrationTest : AutomationRecoveryFixture() {
    @Autowired lateinit var sync: CharacterSyncJobService

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `전체 수집은 슬롯 내용을 DB에 저장하고 각 원본 복원 후 자동화의 다음 판단까지 이어진다`(stopAfterFirst: Boolean) {
        val ids = TransactionTemplate(transactions).execute {
            entityManager.createQuery("select c from CharacterEntity c where c.account.id = :id order by c.id", CharacterEntity::class.java)
                .setParameter("id", accountId).resultList.mapIndexed { index, character ->
                    character.hofCharacterId = (101 + index).toString()
                    character.hofCharacterId
                }
        }!!
        selectedCharacters = ids
        setupFishing(initialBattle = true)
        jdbc.update("update character_pattern_slots set slot_code = '0' where character_id in (select id from characters where account_id = ?)", accountId)
        var collecting = true
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val fixtures = ids.associateWith {
            CharacterDeepSyncServiceTest.Fixture(beforePost = {
                if (stopAfterFirst && stopped.compareAndSet(false, true)) {
                    val jobId = jdbc.queryForObject("select max(id) from character_sync_jobs where account_id = ?", Long::class.java, accountId)!!
                    sync.stopSyncJob(accountId, jobId)
                }
                assertEquals("PAUSED", jdbc.queryForObject(
                    "select lifecycle_status from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
            })
        }
        val header = "<div id='menu2'>Funds : $ 1 Time : 100/100</div>"
        val roster = header + ids.joinToString("") { "<a href='index.php?char=$it'>캐릭터 $it</a>" }
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            HofHttpResponse(200, request.url, roster, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.argThat { it?.url?.endsWith("index.php") == true }
            ?: HofRequest(HofHttpMethod.GET, "https://fixture.invalid"), Mockito.anyMap())
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            val id = request.url.substringAfter("char=").substringBefore('&')
            if (collecting) fixtures.getValue(id).remoteResponse(request).let {
                it.copy(body = header + it.body.replace("?char=10", "?char=$id"))
            } else HofHttpResponse(200, request.url,
                header + currentPatternForm() + savedPatternLoadForm(request.formFields.getValue("patternno").toInt()), emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.argThat { it?.url?.contains("?char=") == true }
            ?: HofRequest(HofHttpMethod.GET, "https://fixture.invalid"), Mockito.anyMap())

        val started = sync.startSyncJob(accountId, CharacterSyncMode.DEEP)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20)
        var result = sync.findSyncJob(accountId, started.jobId)
        while (result.status in setOf("pending", "running") && System.nanoTime() < deadline) {
            Thread.sleep(25)
            result = sync.findSyncJob(accountId, started.jobId)
        }
        if (stopAfterFirst) {
            assertEquals("stopped", result.status, result.message)
            assertEquals(1, result.syncedCount)
            assertEquals(1, fixtures.values.count { it.posts.isNotEmpty() })
            sync.resumeSyncJob(accountId, result.jobId)
            result = sync.findSyncJob(accountId, started.jobId)
            while (result.status in setOf("pending", "running") && System.nanoTime() < deadline) {
                Thread.sleep(25)
                result = sync.findSyncJob(accountId, started.jobId)
            }
        }
        assertEquals("completed", result.status, result.message)
        assertEquals(3, result.syncedCount)
        assertEquals(3, jdbc.queryForObject("""select count(*) from character_saved_pattern_rows r
            join character_pattern_slots s on s.id = r.pattern_slot_id join characters c on c.id = s.character_id
            where c.account_id = ? and r.skill = '0'""", Int::class.java, accountId))
        assertEquals(3, jdbc.queryForObject("""select count(*) from character_action_patterns r
            join characters c on c.id = r.character_id where c.account_id = ? and r.skill = '9564'""", Int::class.java, accountId))
        assertEquals(3, jdbc.queryForObject("""select count(*) from character_operation_jobs
            where account_id = ? and status = 'COMPLETED' and recovery_status = 'RESTORED' and automation_released = true""", Int::class.java, accountId))
        fixtures.values.forEach { fixture ->
            assertEquals(1, fixture.posts.count { "loadpattern" in it })
            assertEquals(1, fixture.posts.count { "ChangePattern" in it })
            val restored = app.spammy.hof.external.parser.CharacterDetailParser().parsePage("10", fixture.page()).snapshot
            assertEquals("9564", restored.actionPatterns.single().skill)
            assertEquals("front", restored.positionGuard.selectedPosition)
            assertEquals("1", restored.positionGuard.guardValue)
        }
        collecting = false
        publisher.publishBatch()
        assertTrue(battleRequests().any { it.url.contains("common=fishing_12") })
        consumeNextWake()
        Mockito.verify(decisions, Mockito.atLeast(2)).select(accountId)
        assertEquals(0, runningWorkCount())
    }
}
