package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.character.entity.*
import app.spammy.hof.character.service.*
import app.spammy.hof.external.model.*
import app.spammy.hof.external.parser.CharacterDetailParser
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
class CharacterObservedIdentityAutomationTest : AutomationRecoveryFixture() {
    @Autowired lateinit var operations: CharacterOperationJobService
    @Autowired lateinit var sync: CharacterSyncJobService
    @Autowired lateinit var characterService: CharacterService

    @ParameterizedTest
    @ValueSource(strings = ["DETAIL", "DEEP", "DEEP_STOP"])
    fun `인게임 ID 변경을 동기화로 판별한 뒤 원본을 유지하고 기존 프리셋의 새 ID 전투와 다음 판단을 실행한다`(mode: String) {
        val oldId = TransactionTemplate(transactions).execute {
            val rows = entityManager.createQuery("select c from CharacterEntity c where c.account.id=:id order by c.id", CharacterEntity::class.java)
                .setParameter("id", accountId).resultList
            rows.forEachIndexed { i, c -> c.hofCharacterId = listOf("10", "30", "40")[i] }
            rows.first().name = "fixture"
            rows.first().level = 60
            entityManager.persist(CharacterHofIdHistoryEntity(character = rows.first(), account = rows.first().account,
                hofCharacterId = "10", validFrom = clock.now(), linkReason = CharacterHofIdLinkReason.INITIAL_SYNC, userConfirmed = true))
            rows.first().id
        }!!
        selectedCharacters = listOf("10")
        setupFishing(initialBattle = true)
        jdbc.update("update character_pattern_slots set slot_code='0' where character_id=?", oldId)
        var collecting = true
        val stopped = AtomicBoolean(false)
        val fixtures = mapOf("10" to CharacterDeepSyncServiceTest.Fixture(),
            "20" to CharacterDeepSyncServiceTest.Fixture(beforePost = {
                assertEquals("PAUSED", jdbc.queryForObject("select lifecycle_status from typed_automation_runtime_states where account_id=?", String::class.java, accountId))
                if (mode == "DEEP_STOP" && stopped.compareAndSet(false, true)) {
                    sync.stopSyncJob(accountId, jdbc.queryForObject("select max(id) from character_sync_jobs where account_id=?", Long::class.java, accountId)!!)
                }
            }), "30" to CharacterDeepSyncServiceTest.Fixture())
        val header = "<div id='menu2'>Funds : $ 1 Time : 100/100</div>"
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            val id = request.url.substringAfter("char=").substringBefore('&')
            if (collecting) fixtures.getValue(id).remoteResponse(request).let {
                it.copy(body = header + it.body.replace("?char=10", "?char=$id")
                    .let { body -> if (id == "30") body.replace("fixture Lv.60", "peer Lv.60") else body })
            } else HofHttpResponse(200, request.url, header + currentPatternForm() + savedPatternLoadForm(0), emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.argThat { it?.url?.contains("?char=") == true }
            ?: HofRequest(HofHttpMethod.GET, "https://fixture.invalid"), Mockito.anyMap())

        val baseline = operations.startDeepSync(accountId, oldId)
        val baselineDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (operations.find(accountId, baseline.id).status in setOf(CharacterOperationStatus.PENDING, CharacterOperationStatus.RUNNING)
            && System.nanoTime() < baselineDeadline) Thread.sleep(25)
        assertEquals(CharacterOperationStatus.COMPLETED, operations.find(accountId, baseline.id).status)
        val patternId = jdbc.queryForObject("select id from character_pattern_slots where character_id=? and slot_code='0'", Long::class.java, oldId)!!
        val savedRowId = jdbc.queryForObject("select id from character_saved_pattern_rows where pattern_slot_id=?", Long::class.java, patternId)!!
        clock.current = clock.now().plusSeconds(1)
        TransactionTemplate(transactions).execute {
            characterService.reconcileObservedRoster(entityManager.find(HofAccountEntity::class.java, accountId), listOf(
                HofCharacter(id = "20", name = "fixture", job = "Knight", level = 60, rosterOrder = 0),
                HofCharacter(id = "30", name = "peer", job = "Knight", level = 60, rosterOrder = 1),
            ), clock.now())
        }
        val provisionalId = jdbc.queryForObject("select id from characters where account_id=? and current_hof_character_id='20'", Long::class.java, accountId)!!
        assertNotEquals(oldId, provisionalId)
        assertEquals("MISSING", jdbc.queryForObject("select lifecycle from characters where id=?", String::class.java, oldId))
        val roster = header + "<a href='index.php?char=20'>fixture</a><a href='index.php?char=30'>peer</a>"
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            HofHttpResponse(200, request.url, roster, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), Mockito.argThat { it?.url?.endsWith("index.php") == true }
            ?: HofRequest(HofHttpMethod.GET, "https://fixture.invalid"), Mockito.anyMap())
        requests.clear()
        val parent = sync.startSyncJob(accountId, if (mode == "DETAIL") CharacterSyncMode.DETAIL else CharacterSyncMode.DEEP)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        fun awaitParent() = run {
            var result = sync.findSyncJob(accountId, parent.jobId)
            while (result.status in setOf("pending", "running") && System.nanoTime() < deadline) {
                Thread.sleep(25)
                result = sync.findSyncJob(accountId, parent.jobId)
            }
            result
        }
        var result = awaitParent()
        if (mode == "DEEP_STOP") {
            assertEquals("stopped", result.status, result.message)
            assertEquals(1, result.syncedCount)
            assertEquals("[$oldId,${result.characters.single { it.hofCharacterId == "30" }.id}]",
                jdbc.queryForObject("select deep_sync_targets from character_sync_jobs where id=?", String::class.java, parent.jobId))
            sync.resumeSyncJob(accountId, parent.jobId)
            result = awaitParent()
        }
        assertEquals("completed", result.status, result.message)
        assertEquals(2, result.syncedCount)
        assertEquals(oldId, result.characters.single { it.hofCharacterId == "20" }.id)
        assertFalse(result.characters.any { it.id == provisionalId })
        assertEquals("ACTIVE", jdbc.queryForObject("select lifecycle from characters where id=?", String::class.java, oldId))
        assertEquals(0, jdbc.queryForObject("select roster_order from characters where id=?", Int::class.java, oldId))
        assertEquals(savedRowId, jdbc.queryForObject("select id from character_saved_pattern_rows where pattern_slot_id=?", Long::class.java, patternId))
        assertEquals(oldId, jdbc.queryForObject("select character_id from party_preset_members where pattern_slot_id=?", Long::class.java, patternId))
        assertEquals(2, jdbc.queryForObject("select count(*) from character_operation_jobs where target_character_id=? and status='COMPLETED' and recovery_status='RESTORED'", Int::class.java, oldId))
        assertEquals(1, fixtures.getValue("20").posts.count { "loadpattern" in it })
        val restored = CharacterDetailParser().parsePage("20", fixtures.getValue("20").page()).snapshot
        assertEquals("9564", restored.actionPatterns.single().skill)
        assertEquals("front", restored.positionGuard.selectedPosition)
        assertEquals("1", restored.positionGuard.guardValue)
        assertTrue(requests.filter { it.method == HofHttpMethod.POST && it.url.contains("?char=") }.none { it.url.contains("?char=10") })

        collecting = false
        selectedCharacters = listOf("20")
        publisher.publishBatch()
        val battle = battleRequests().single()
        assertTrue(battle.formEntries.any { it.name == "char_20" })
        assertFalse(battle.formEntries.any { it.name == "char_10" })
        consumeNextWake()
        Mockito.verify(decisions, Mockito.atLeast(2)).select(accountId)
        assertEquals(0, runningWorkCount())
    }
}
