package app.spammy.hof.automation.service

import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.beans.factory.annotation.Autowired
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.convergence.*
import app.spammy.hof.automation.raid.*
import app.spammy.hof.automation.history.AutomationHistoryEventKind
import app.spammy.hof.automation.history.AutomationHistoryQuery
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.parser.HomePageParser
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.quest.parser.QuestPageParser
import tools.jackson.module.kotlin.jacksonObjectMapper
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.port.AutomationWakeupPort
import app.spammy.hof.automation.kafka.AutomationWakeupConsumer
import app.spammy.hof.automation.kafka.KafkaAutomationWakeupAdapter
import app.spammy.hof.automation.lease.AccountAutomationLeaseService
import app.spammy.hof.automation.outbox.*
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterOperationJobEntity
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.entity.CharacterOperationType
import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.kafka.support.Acknowledgment
import tools.jackson.databind.ObjectMapper

@SpringBootTest(properties = ["hof.automation-convergence.mode=ACTIVE"])
@ActiveProfiles("test")
@Import(AutomationRecoveryIntegrationTest.Config::class)
class AutomationRecoveryIntegrationTest : AutomationRecoveryFixture() {
    private var incompleteRefreshPost = false
    private var raidPageTransform: (String) -> String = { it }
    @Autowired private lateinit var dueStore: DatabaseAutomationDueStore
    @Autowired private lateinit var accountMutations: app.spammy.hof.town.common.service.AccountHofMutationFence
    @Autowired private lateinit var recoveryActions: app.spammy.hof.character.service.CharacterOperationRecoveryService
    @Autowired private lateinit var characterAutomation: app.spammy.hof.character.service.CharacterOperationAutomation
    @Autowired private lateinit var characterIdentity: app.spammy.hof.character.identity.CharacterLifecycleService
    @Autowired private lateinit var characterJobs: app.spammy.hof.character.service.CharacterOperationJobService
    @Autowired private lateinit var characterRecovery: app.spammy.hof.character.service.CharacterDeepSyncRecovery
    @Autowired private lateinit var characterGate: app.spammy.hof.character.command.CharacterAutomationGate
    @Autowired private lateinit var refreshTokens: app.spammy.hof.auth.service.RefreshTokenService
    @MockitoSpyBean private lateinit var raidModule: RaidCycleModule
    @Autowired private lateinit var recoveryQuery: app.spammy.hof.automation.recovery.AutomationRecoveryDueAccountQuery
    @Autowired private lateinit var battleMaps: app.spammy.hof.battle.service.BattleMapService
    @Autowired private lateinit var fishingService: app.spammy.hof.town.fishing.service.FishingService
    @Autowired private lateinit var runner: UnifiedAutomationRunner
    @Autowired private lateinit var workSessions: app.spammy.hof.automation.repository.AutomationWorkSessionQueryRepository

    @ParameterizedTest
    @ValueSource(strings = ["CURRENT", "RETRY", "STARTUP", "NOT_STARTED", "NOT_STARTED_RETRY", "USER_STOP"])
    fun `completed recovery releases a stranded automation hold without another HOF request`(entry: String) {
        val job = TransactionTemplate(transactions).execute {
            val character = entityManager.createQuery("select c from CharacterEntity c where c.account.id = :accountId", CharacterEntity::class.java)
                .setParameter("accountId", accountId).resultList.first()
            CharacterOperationJobEntity(account = entityManager.getReference(HofAccountEntity::class.java, accountId),
                operationType = CharacterOperationType.DEEP_SYNC, targetCharacterId = character.id,
                recoveryStatus = CharacterRecoveryStatus.NOT_STARTED, startedAt = clock.now(), updatedAt = clock.now(),
            ).also(entityManager::persist)
        }
        characterAutomation.begin(accountId, job.id)
        if (!entry.startsWith("NOT_STARTED")) {
            val original = app.spammy.hof.character.service.CharacterRestoreState(
                "recovery-1", listOf(app.spammy.hof.external.model.HofActionPatternRow(0, judge = "0", quantity = "0", skill = "0")),
                emptyList(), "front", "0")
            characterRecovery.save(job.id, accountId, job.targetCharacterId,
                app.spammy.hof.character.service.CharacterDeepSyncCheckpoint(original, status = CharacterRecoveryStatus.RESTORED,
                    collectionComplete = true))
        }
        // Persist the state left when remote recovery committed but the finish transaction failed.
        jdbc.update("update character_operation_jobs set status = 'FAILED', message = 'finish transaction unavailable' where id = ?", job.id)
        if (entry == "USER_STOP") application.stopTyped(accountId)
        when (entry) {
            "RETRY", "NOT_STARTED_RETRY" -> characterJobs.retryRecovery(accountId, job.id)
            "STARTUP" -> characterJobs.resumeIncompleteJobs()
            else -> characterJobs.findCurrent(accountId)
        }
        assertTrue(jdbc.queryForObject("select automation_released from character_operation_jobs where id = ?", Boolean::class.java, job.id)!!)
        assertTrue(requests.isEmpty(), "Finalizing a persisted recovery must not read or mutate HOF again")
        assertEquals(if (entry == "USER_STOP") "STOPPED" else "RUNNING", jdbc.queryForObject(
            "select lifecycle_status from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
        if (entry != "USER_STOP") {
            failedPattern = -1
            publisher.publishBatch()
            assertTrue(battleRequests().isNotEmpty())
            consumeNextWake()
            Mockito.verify(decisions, Mockito.atLeast(2)).select(accountId)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["UNAVAILABLE", "REQUIRED"])
    fun `현재 상태를 명시적으로 수락하면 원본과 수집 실패를 보존하고 자동화를 정지한다`(recovery: String) {
        val jobId = reviewableJob(recovery)
        assertEquals(jobId, characterJobs.findCurrent(accountId)?.id)
        assertNull(characterJobs.findCurrent(accountId + 99999))
        val original = if (recovery == "REQUIRED") {
            val job = characterJobs.find(accountId, jobId)
            val state = app.spammy.hof.character.service.CharacterRestoreState(
                characters.first(), listOf(app.spammy.hof.external.model.HofActionPatternRow(0, judge = "1000", quantity = "0", skill = "9564")),
                emptyList(), "front", "1")
            jdbc.update("update character_operation_jobs set recovery_status = 'NOT_STARTED' where id = ?", jobId)
            characterRecovery.save(jobId, accountId, job.targetCharacterId,
                app.spammy.hof.character.service.CharacterDeepSyncCheckpoint(state))
            state
        } else null
        val observed = app.spammy.hof.character.service.CharacterDeepSyncServiceTest.Fixture()
        reviewPage { observed.page() }

        val preview = recoveryActions.preview(accountId, jobId)
        assertTrue(preview.patterns.isNotEmpty())
        assertEquals(jobId, preview.jobId)
        val accepted = recoveryActions.accept(accountId, jobId, preview.confirmationToken)
        assertEquals(CharacterOperationStatus.STOPPED, accepted.status)
        assertEquals(CharacterRecoveryStatus.ACCEPTED, accepted.recoveryStatus)
        assertEquals("수집 중단", accepted.message)
        assertEquals("STOPPED", jdbc.queryForObject("select lifecycle_status from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
        assertNotNull(jdbc.queryForObject("select recovery_accepted_at from character_operation_jobs where id = ?", java.sql.Timestamp::class.java, jobId))
        if (original != null) assertEquals(original, characterRecovery.load(jobId, accountId, accepted.targetCharacterId)?.original)
        characterAutomation.requireAvailable(accountId)
        val requestCount = requests.size
        assertEquals(accepted, recoveryActions.accept(accountId, jobId, preview.confirmationToken))
        assertEquals(requestCount, requests.size, "이미 수락한 요청은 원격 관측도 반복하지 않는다.")
        assertTrue(requests.all { it.method == HofHttpMethod.GET })
        wakeups.wake(accountId, "STALE_WAKE_AFTER_ACCEPTED_RECOVERY")
        publisher.publishBatch()
        assertEquals(requestCount, requests.size, "보호를 해제해도 자동화를 저절로 재개하지 않는다.")
    }

    @ParameterizedTest
    @ValueSource(strings = ["CHANGED", "EXPIRED", "WRONG_TOKEN", "OTHER_ACCOUNT", "RUNNING", "INCOMPLETE", "OTHER_JOB"])
    fun `다른 상태나 만료된 관측 또는 다른 소유권은 현재 상태 수락을 허용하지 않는다`(reason: String) {
        val jobId = reviewableJob("UNAVAILABLE")
        val observed = app.spammy.hof.character.service.CharacterDeepSyncServiceTest.Fixture()
        var html = observed.page()
        reviewPage { html }
        val preview = recoveryActions.preview(accountId, jobId)
        when (reason) {
            "CHANGED" -> html = html.replace("value='9564' selected", "value='7777' selected").also { assertNotEquals(html, it) }
            "EXPIRED" -> clock.current = clock.now().plusSeconds(301)
            "RUNNING" -> jdbc.update("update character_operation_jobs set status = 'RUNNING' where id = ?", jobId)
            "INCOMPLETE" -> html = "<div>현재 캐릭터를 확인하지 못했습니다.</div>"
        }
        val targetJobId = if (reason == "OTHER_JOB") reviewableJob("UNAVAILABLE") else jobId
        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            recoveryActions.accept(if (reason == "OTHER_ACCOUNT") accountId + 99999 else accountId,
                targetJobId, if (reason == "WRONG_TOKEN") "wrong-token" else preview.confirmationToken)
        }
        assertEquals(CharacterRecoveryStatus.UNAVAILABLE, characterJobs.find(accountId, jobId).recoveryStatus)
        assertFailsWith<app.spammy.hof.common.error.ApiException> { characterAutomation.requireAvailable(accountId) }
        assertTrue(requests.all { it.method == HofHttpMethod.GET })
    }

    private fun reviewableJob(recovery: String): Long = TransactionTemplate(transactions).execute {
        val character = entityManager.createQuery("select c from CharacterEntity c where c.account.id = :accountId order by c.id", CharacterEntity::class.java)
            .setParameter("accountId", accountId).resultList.first()
        val job = CharacterOperationJobEntity(account = entityManager.getReference(HofAccountEntity::class.java, accountId),
            operationType = CharacterOperationType.DEEP_SYNC, status = CharacterOperationStatus.FAILED,
            targetCharacterId = character.id, recoveryStatus = CharacterRecoveryStatus.valueOf(recovery),
            message = "수집 중단", startedAt = clock.now(), updatedAt = clock.now())
        entityManager.persist(job)
        entityManager.flush()
        job.id
    }

    private fun reviewPage(body: () -> String) {
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            check(request.method == HofHttpMethod.GET)
            HofHttpResponse(200, request.url, body(), emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
    }

    @ParameterizedTest
    @ValueSource(strings = ["REQUIRED", "RESTORING", "UNAVAILABLE", "LEGACY"])
    fun `미복원 캐릭터 작업은 재시작 깨우기에서 새 자동화 판단과 제출을 막는다`(recovery: String) {
        failedPattern = -1
        TransactionTemplate(transactions).executeWithoutResult {
            val character = entityManager.createQuery("select c from CharacterEntity c where c.account.id = :accountId", CharacterEntity::class.java)
                .setParameter("accountId", accountId).resultList.first()
            entityManager.persist(CharacterOperationJobEntity(
                account = entityManager.getReference(HofAccountEntity::class.java, accountId),
                operationType = CharacterOperationType.DEEP_SYNC,
                status = if (recovery == "LEGACY") CharacterOperationStatus.RUNNING else CharacterOperationStatus.FAILED,
                targetCharacterId = character.id,
                recoveryStatus = recovery.takeUnless { it == "LEGACY" }?.let(CharacterRecoveryStatus::valueOf),
                startedAt = clock.now(), updatedAt = clock.now(),
            ))
        }

        wakeups.wake(accountId, "STARTUP_WITH_CHARACTER_RECOVERY")
        publisher.publishBatch()

        assertTrue(requests.isEmpty(), "미복원 상태에서는 HOF 요청을 시작하지 않아야 한다.")
        Mockito.verify(decisions, Mockito.never()).select(accountId)
    }

    @ParameterizedTest
    @ValueSource(strings = ["RESTORED", "REQUIRED", "COLLECTION_FAILED_RESTORED", "USER_PAUSE", "USER_STOP", "AUTH",
        "INITIAL_PAUSED", "INITIAL_STOPPED", "RETRY_RESTORED", "AUTH_RELOGIN"])
    fun `동기화 일시정지는 원본 복원과 최신 사용자 의도가 허용할 때만 후속 판단으로 복귀한다`(outcome: String) {
        val job = TransactionTemplate(transactions).execute {
            val character = entityManager.createQuery("select c from CharacterEntity c where c.account.id = :accountId", CharacterEntity::class.java)
                .setParameter("accountId", accountId).resultList.first()
            CharacterOperationJobEntity(
                account = entityManager.getReference(HofAccountEntity::class.java, accountId),
                operationType = CharacterOperationType.DEEP_SYNC, targetCharacterId = character.id,
                recoveryStatus = CharacterRecoveryStatus.NOT_STARTED, startedAt = clock.now(), updatedAt = clock.now(),
            ).also(entityManager::persist)
        }
        val original = app.spammy.hof.character.service.CharacterRestoreState(
            "recovery-1", listOf(app.spammy.hof.external.model.HofActionPatternRow(0, judge = "0", quantity = "0", skill = "0")),
            emptyList(), "front", "0",
        )
        when (outcome) {
            "INITIAL_PAUSED" -> application.pauseTyped(accountId)
            "INITIAL_STOPPED" -> application.stopTyped(accountId)
            "RETRY_RESTORED" -> assertFailsWith<IllegalStateException> {
                characterGate.executeJob(accountId, job.id, { error("pause did not complete") }) {
                    characterRecovery.save(job.id, accountId, job.targetCharacterId,
                        app.spammy.hof.character.service.CharacterDeepSyncCheckpoint(original))
                    error("first recovery interrupted")
                }
            }
        }
        try {
            characterGate.executeJob(accountId, job.id, { error("pause did not complete") }) {
                characterRecovery.save(job.id, accountId, job.targetCharacterId,
                    app.spammy.hof.character.service.CharacterDeepSyncCheckpoint(original))
                when (outcome) {
                    "USER_PAUSE" -> application.pauseTyped(accountId)
                    "USER_STOP" -> application.stopTyped(accountId)
                    "AUTH", "AUTH_RELOGIN" -> TransactionTemplate(transactions).executeWithoutResult {
                        lifecycle.suspendForAuthentication(accountId, "LAST_APP_SESSION_ENDED")
                        if (outcome == "AUTH_RELOGIN") lifecycle.resumeAfterAuthentication(accountId, "APP_SESSION_ACTIVATED")
                    }
                }
                if (outcome != "REQUIRED") {
                    characterRecovery.save(job.id, accountId, job.targetCharacterId,
                        app.spammy.hof.character.service.CharacterDeepSyncCheckpoint(original,
                            status = CharacterRecoveryStatus.RESTORED, collectionComplete = outcome != "COLLECTION_FAILED_RESTORED"))
                }
                if (outcome in setOf("REQUIRED", "COLLECTION_FAILED_RESTORED")) error("fixture collection failed")
            }
        } catch (error: IllegalStateException) {
            assertEquals("fixture collection failed", error.message)
        }

        val shouldResume = outcome in setOf("RESTORED", "COLLECTION_FAILED_RESTORED", "RETRY_RESTORED")
        val expected = if (shouldResume) "RUNNING" else if (outcome in setOf("USER_STOP", "INITIAL_STOPPED")) "STOPPED" else "PAUSED"
        assertEquals(expected, jdbc.queryForObject(
            "select lifecycle_status from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
        failedPattern = -1
        if (!shouldResume) wakeups.wake(accountId, "STALE_WAKE_AFTER_CHARACTER_RECOVERY")
        publisher.publishBatch()
        if (shouldResume) {
            assertTrue(battleRequests().isNotEmpty(), "복원 뒤 실제 깨우기가 다음 행동을 실행해야 한다.")
            consumeNextWake()
            Mockito.verify(decisions, Mockito.atLeast(2)).select(accountId)
        } else {
            assertTrue(requests.isEmpty(), "사용자 의도나 미복원 상태를 우회해 실행하면 안 된다.")
        }
    }

    @ParameterizedTest
    @EnumSource(value = CharacterOperationType::class, names = ["DEEP_SYNC", "RESTORE"])
    fun `미복원 작업이 있으면 같은 계정의 새 캐릭터 작업을 만들지 않는다`(type: CharacterOperationType) {
        val targetId = TransactionTemplate(transactions).execute {
            val target = entityManager.createQuery("select c from CharacterEntity c where c.account.id = :accountId", CharacterEntity::class.java)
                .setParameter("accountId", accountId).resultList.first()
            entityManager.persist(CharacterOperationJobEntity(account = target.account,
                operationType = CharacterOperationType.DEEP_SYNC, status = CharacterOperationStatus.FAILED,
                targetCharacterId = target.id, recoveryStatus = CharacterRecoveryStatus.REQUIRED,
                startedAt = clock.now(), updatedAt = clock.now()))
            target.id
        }

        val error = assertFailsWith<app.spammy.hof.common.error.ApiException> {
            TransactionTemplate(transactions).executeWithoutResult { transaction ->
                if (type == CharacterOperationType.RESTORE) characterJobs.startRestore(accountId, targetId)
                else characterJobs.startDeepSync(accountId, targetId)
                transaction.setRollbackOnly()
            }
        }

        assertEquals(app.spammy.hof.common.error.ErrorCode.CHARACTER_RECOVERY_REQUIRED, error.errorCode)
        assertTrue(requests.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["ARCHIVE", "DELETE", "LINK"])
    fun `미복원 원본을 가진 캐릭터의 삭제나 식별 변경을 막는다`(action: String) {
        val targetId = TransactionTemplate(transactions).execute {
            val target = entityManager.createQuery("select c from CharacterEntity c where c.account.id = :accountId", CharacterEntity::class.java)
                .setParameter("accountId", accountId).resultList.first()
            if (action == "DELETE") target.lifecycle = app.spammy.hof.character.entity.CharacterLifecycle.ARCHIVED
            entityManager.persist(CharacterOperationJobEntity(account = target.account,
                operationType = CharacterOperationType.DEEP_SYNC, status = CharacterOperationStatus.FAILED,
                targetCharacterId = target.id, recoveryStatus = CharacterRecoveryStatus.REQUIRED,
                startedAt = clock.now(), updatedAt = clock.now()))
            target.id
        }

        val error = assertFailsWith<app.spammy.hof.common.error.ApiException> {
            when (action) {
                "ARCHIVE" -> characterIdentity.archive(accountId, targetId)
                "DELETE" -> characterIdentity.deletePermanently(accountId, targetId)
                else -> characterIdentity.link(accountId, targetId, "new-hof-id",
                    app.spammy.hof.character.entity.CharacterHofIdLinkReason.KNOCKBACK, true)
            }
        }

        assertEquals(app.spammy.hof.common.error.ErrorCode.CHARACTER_RECOVERY_REQUIRED, error.errorCode)
        assertEquals(1, jdbc.queryForObject("select count(*) from character_operation_jobs where account_id = ?", Int::class.java, accountId))
    }

    @Test
    fun `다른 계정의 미복원 작업은 이 계정의 자동화와 새 동기화를 막지 않는다`() {
        val otherId = TransactionTemplate(transactions).execute {
            val other = HofAccountEntity(loginId = "blocked-peer-${UUID.randomUUID()}", encryptedPassword = "fixture", createdAt = clock.now())
            entityManager.persist(other)
            val target = CharacterEntity(account = other, hofCharacterId = "peer-1", name = "peer", job = "Knight", updatedAt = clock.now())
            entityManager.persist(target)
            entityManager.persist(CharacterOperationJobEntity(account = other, operationType = CharacterOperationType.DEEP_SYNC,
                status = CharacterOperationStatus.FAILED, targetCharacterId = target.id,
                recoveryStatus = CharacterRecoveryStatus.REQUIRED, startedAt = clock.now(), updatedAt = clock.now()))
            other.id
        }
        try {
            failedPattern = -1
            wakeups.wake(accountId, "UNRELATED_ACCOUNT_RECOVERY")
            publisher.publishBatch()
            assertEquals(1, battleRequests().size)
            consumeNextWake()
            Mockito.verify(decisions, Mockito.atLeast(2)).select(accountId)

            TransactionTemplate(transactions).executeWithoutResult { transaction ->
                val target = entityManager.createQuery("select c from CharacterEntity c where c.account.id = :accountId", CharacterEntity::class.java)
                    .setParameter("accountId", accountId).resultList.first()
                assertEquals(CharacterOperationStatus.PENDING, characterJobs.startDeepSync(accountId, target.id).status)
                transaction.setRollbackOnly()
            }
        } finally {
            jdbc.update("delete from hof_accounts where id = ?", otherId)
        }
    }

    @ParameterizedTest
    @EnumSource(value = CharacterOperationType::class, names = ["DEEP_SYNC", "TRANSFER"])
    fun `캐릭터 작업의 일시정지 대기는 기존 보호 행동의 깨우기와 결과 수렴을 막지 않는다`(type: CharacterOperationType) {
        setupFishing(obstruction = true, lostFishingResponse = "FCatch")
        wakeups.wake(accountId, "FISHING_BEFORE_SYNC")
        publisher.publishBatch()
        assertEquals("RECONCILING", runs().last()["status"])
        val job = TransactionTemplate(transactions).execute {
            val target = entityManager.createQuery("select c from CharacterEntity c where c.account.id = :accountId", CharacterEntity::class.java)
                .setParameter("accountId", accountId).resultList.first()
            CharacterOperationJobEntity(account = target.account, operationType = type,
                targetCharacterId = target.id, recoveryStatus = if (type == CharacterOperationType.TRANSFER) null else CharacterRecoveryStatus.NOT_STARTED,
                startedAt = clock.now(), updatedAt = clock.now()).also(entityManager::persist)
        }
        var waits = 0
        val gate = app.spammy.hof.character.command.TypedAutomationCharacterCommandBridge(application,
            app.spammy.hof.character.command.CharacterCommandPauseWaiter {
                check(waits++ < 5) { "보호 행동이 유한한 후속 확인으로 끝나야 한다." }
                nextRun()
            }, characterAutomation, accountMutations)

        gate.executeJob(accountId, job.id, { error("protected action did not settle") }) {
            assertEquals("PAUSED", jdbc.queryForObject("select lifecycle_status from typed_automation_runtime_states where account_id = ?",
                String::class.java, accountId))
            assertTrue(runs().none { it["status"] in setOf("SUBMITTING", "RECONCILING") })
        }

        assertTrue(waits > 0)
        val expected = if (type == CharacterOperationType.TRANSFER) "PAUSED" else "RUNNING"
        assertEquals(expected, jdbc.queryForObject("select lifecycle_status from typed_automation_runtime_states where account_id = ?",
            String::class.java, accountId), "가져오기는 최종 결과가 확정되기 전까지 복귀하지 않는다.")
    }

    @Test
    fun `Redis 장애 중 시작 복구가 DB의 due 작업을 깨우고 다음 판단까지 이어진다`() {
        failedPattern = -1
        TransactionTemplate(transactions).executeWithoutResult {
            entityManager.persist(AutomationWorkSessionEntity(
                account = entityManager.getReference(HofAccountEntity::class.java, accountId),
                entry = entityManager.getReference(AutomationEntryEntity::class.java, entryId),
                workType = AutomationWorkType.UNION, targetKey = "0003", status = AutomationWorkStatus.WAITING_COOLDOWN,
                configVersion = "fixture", nextCheckAt = clock.now(), createdAt = clock.now(), updatedAt = clock.now()))
        }
        val redis = Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate::class.java)
        Mockito.`when`(redis.opsForZSet()).thenThrow(org.springframework.data.redis.RedisConnectionFailureException("fixture outage"))
        val index = app.spammy.hof.automation.redis.RedisAutomationDueIndex(dueStore, redis)
        val scheduler = app.spammy.hof.automation.recovery.AutomationSessionReconciliationScheduler(index, wakeups, clock)

        scheduler.recoverOnStartup()
        publisher.publishBatch()

        assertEquals(1, battleRequests().size)
        assertEquals("SUCCEEDED", runs().single()["status"])
        consumeNextWake()
        assertEquals(2, battleRequests().size)
        Mockito.verify(decisions, Mockito.atLeast(2)).select(accountId)
    }

    @Test
    fun `깨우기로 실행한 전투도 패턴 응답 쿠키를 이어받고 후속 판단을 소비한다`() {
        failedPattern = -1
        rotatePatternCookies = true

        wakeups.wake(accountId, "PATTERN_COOKIE_ROTATION")
        publisher.publishBatch()

        assertEquals(listOf("test-session", "rotated-1", "rotated-2", "rotated-3"),
            requests.zip(requestCookies).filter { (request, _) -> request.method == HofHttpMethod.POST }
                .map { (_, cookies) -> cookies["PHPSESSID"]!! })
        assertEquals("SUCCEEDED", runs().single()["status"])

        consumeNextWake()

        assertEquals(2, battleRequests().size)
        assertTrue(runs().all { it["status"] == "SUCCEEDED" })
        Mockito.verify(decisions, Mockito.atLeast(2)).select(accountId)
    }

    @Test
    fun `패턴 IO 실패는 미전송으로 끝나고 새 판단에서 유니온 전투를 한 번만 제출한다`() {
        wakeups.wake(accountId, "CONTINUITY_BASELINE")
        publisher.publishBatch()

        assertEquals(2, patternCalls)
        assertEquals(0, battleRequests().size)
        val failed = runs().single()
        assertEquals("FAILED", failed["status"])
        assertNull(failed["submitted_at"])
        assertTrue(store.findSuppressedBaselines(accountId).isEmpty())
        assertTrue(store.findActiveScopes(accountId).isEmpty())
        val retryAt = jdbc.queryForObject("select next_attempt_at from typed_automation_runtime_states where account_id = ?",
            java.time.OffsetDateTime::class.java, accountId)!!.toInstant()
        assertEquals(clock.now().plusSeconds(10), retryAt)
        assertScheduledWake("HOF_503_COOLDOWN", retryAt)
        assertEquals("HOF_CONNECTION", jdbc.queryForObject(
            "select wait_reason from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))

        failedPattern = -1
        clock.current = retryAt
        publisher.publishBatch()

        val completed = runs().last()
        assertEquals("SUCCEEDED", completed["status"], completed.toString())
        assertNotEquals(failed["execution_identity"], completed["execution_identity"])
        assertEquals(1, battleRequests().size)
        assertEquals(listOf(characters[0], characters[1], characters[1], characters[2]),
            requests.filter { it.url.contains("?char=") }.map { it.url.substringAfter("?char=") })
        assertTrue(store.findSuppressedBaselines(accountId).isEmpty())
        assertEquals(2, transport.delivered.size)
        assertTrue(transport.delivered.all { outbox.consumed(it) })
        assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
    }

    @ParameterizedTest
    @EnumSource(value = AutomationType::class, names = ["BATTLE_MAP", "ADVENTURE_MAP", "UNION"])
    fun `영속 대기는 시각 전 제출을 막고 소비 뒤 전투와 다음 판단으로 이어진다`(type: AutomationType) {
        failedPattern = -1
        val deadline = clock.now().plusSeconds(30)
        val category = when (type) {
            AutomationType.BATTLE_MAP -> "battle_map"
            AutomationType.ADVENTURE_MAP -> "adventure_map"
            else -> "union"
        }
        val query = when (type) {
            AutomationType.BATTLE_MAP -> "common"
            AutomationType.ADVENTURE_MAP -> "sp_common"
            else -> "union"
        }
        mapPage = """<html><body><div id="contents"><div>공유 지역 (2)</div><div id="mapgroup1">
            <p><a href='index.php?$query=0003'>도적소탕</a> 2 가능</p></div></div>
            <div id="foot"><h5>Copy Right sanitized fixture</h5><h6>H.O.F Korean Ver sanitized fixture</h6>
            <img src="image/zerohof.gif"></div></body></html>"""
        TransactionTemplate(transactions).executeWithoutResult {
            entityManager.find(AutomationEntryEntity::class.java, entryId).apply {
                this.type = type
                singletonTypeMarker = type.takeIf { it == AutomationType.UNION }
                if (type == AutomationType.BATTLE_MAP) {
                    entityManager.persist(BattleAutomationMapEntity(entry = this, categoryId = category,
                        mapCode = "0003", dailyTargetCount = 1, presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
                }
            }
        }
        val party = ResolvedAutomationParty(characters, characters.map { BattlePatternLoadRequest(it, 1) })
        val action: PreparedAutomationAction = if (type == AutomationType.ADVENTURE_MAP) {
            AdventureMapAutomationAction(accountId, category, "0003", PresetSelectionMode.EXPLICIT, 1L,
                settingIdentity = 1L, executionIdentity = UUID.randomUUID().toString(), resolvedParty = party)
        } else battle().copy(categoryId = category, source = if (type == AutomationType.UNION)
            BattleAutomationActionSource.UNION_AUTOMATION else BattleAutomationActionSource.BATTLE_MAP_AUTOMATION)
        var selections = 0
        Mockito.doAnswer {
            when (++selections) {
                1 -> AutomationCoordination.Unavailable(deadline, emptyList())
                2 -> AutomationCoordination.Runnable(entryId, action, emptyList())
                else -> AutomationCoordination.Idle(emptyList())
            }
        }.`when`(decisions).select(accountId)

        wakeups.wake(accountId, "DURABLE_WAIT_BASELINE")
        publisher.publishBatch()
        assertScheduledWake("TYPED_UNAVAILABLE", deadline)
        val due = outbox.findUnpublished(deadline).single { it.account.id == accountId }
        clock.current = deadline.minusMillis(1)
        publisher.publishBatch()
        assertEquals(1, selections)
        assertTrue(battleRequests().isEmpty())

        clock.current = deadline
        publisher.publishBatch()
        assertEquals(2, selections)
        assertEquals(1, battleRequests().size)
        assertEquals("SUCCEEDED", runs().single()["status"])
        assertEquals(2, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        transport.publish(due)
        assertEquals(2, selections, "중복 전달이 같은 행동을 다시 선택하면 안 된다.")
        assertEquals(1, battleRequests().size)

        consumeNextWake()
        assertEquals(3, selections)
        assertEquals(3, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(1, battleRequests().size)
        assertScheduledWake("TYPED_NEXT_ROUND", clock.now().plusSeconds(3))
    }


    @Test
    fun `실제 전투 응답 유실은 미전송으로 바꾸거나 전투를 즉시 재제출하지 않는다`() {
        failedPattern = -1
        failBattle = true
        wakeups.wake(accountId, "LOST_BATTLE_BASELINE")
        publisher.publishBatch()
        assertEquals(1, battleRequests().size)
        assertEquals("AMBIGUOUS", runs().single()["status"])
        assertNotNull(runs().single()["submitted_at"])
        consumeNextWake()
        assertEquals(1, battleRequests().size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["START_AUTOMATION", "START_GENERAL", "CATCH_AUTOMATION", "CATCH_GENERAL"])
    fun `일반 선택 복구 조회는 같은 현재 낚시 전투를 확인한다`(scenario: String) {
        val fixture = if (scenario.startsWith("START")) "reset" else "waiting"
        val fishingHtml = requireNotNull(javaClass.getResource("/fixtures/town/fishing/$fixture.html")).readText()
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            assertEquals(HofHttpMethod.GET, request.method)
            val body = when {
                request.url.contains("menu=fishing") -> fishingHtml
                request.url == "https://hof.zerosic.com/index.php?hunt" -> completeFishingMapPage("<a href='index.php?common=Fish03'>Fishing- 악어</a>")
                else -> error("Unexpected query: ${request.url}")
            }
            HofHttpResponse(200, request.url, "<div id='menu2'>Funds : $ 1 Time : 100/100</div>" + body, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
        val paths = if (scenario.endsWith("AUTOMATION")) listOf("selection", "general", "recovery")
            else listOf("general", "recovery", "selection")
        val responses = paths.map { path ->
            when (path) {
                "general" -> fishingService.load(accountId)
                "recovery" -> fishingService.load(accountId, app.spammy.hof.external.model.HofRequestOrigin.AUTOMATION)
                else -> fishingService.loadForAutomation(accountId).response
            }
        }
        responses.forEach {
            assertEquals(true, it.blockedByBattle)
            assertEquals("Fish03", it.battleTarget?.mapCode)
            assertEquals("NONE", it.primaryAction.name)
            assertTrue(it.availableActions.isEmpty())
        }
        assertEquals(1, responses.distinct().size)
        assertEquals(3, requests.count { it.url == "https://hof.zerosic.com/index.php?hunt" })
    }

    @ParameterizedTest
    @ValueSource(strings = ["EMPTY", "PARTIAL", "HTTP", "IO"])
    fun `전투 목록 미확인은 모든 조회에서 낚시 가능이나 전투 부재가 아니다`(failure: String) {
        val fishingHtml = requireNotNull(javaClass.getResource("/fixtures/town/fishing/reset.html")).readText()
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            assertEquals(HofHttpMethod.GET, request.method)
            if (request.url.contains("menu=fishing")) HofHttpResponse(200, request.url, fishingHtml, emptyMap())
            else {
                if (failure == "IO") throw IOException("unavailable")
                val body = if (failure == "PARTIAL") "<div id='contents'><a href='?common=0001'>일반 맵</a>" else "<div>unavailable</div>"
                HofHttpResponse(if (failure == "HTTP") 503 else 200, request.url, body, emptyMap())
            }
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
        val responses = listOf(
            fishingService.load(accountId),
            fishingService.loadForAutomation(accountId).response,
            fishingService.load(accountId, app.spammy.hof.external.model.HofRequestOrigin.AUTOMATION),
        )
        responses.forEach {
            assertEquals("NONE", it.primaryAction.name)
            assertTrue(it.availableActions.isEmpty())
            assertFalse(it.blockedByBattle, "미확인을 전투가 있다는 사실로 만들어 내지 않는다")
            assertNull(it.battleTarget)
        }
        assertEquals(1, responses.distinct().size)
    }

    @ParameterizedTest
    @ValueSource(strings = ["MANUAL", "CYCLE", "OBSERVED"])
    fun `현재 전투가 있으면 낚시 제출 진입점도 START를 보내지 않는다`(path: String) {
        val html = requireNotNull(javaClass.getResource("/fixtures/town/fishing/reset.html")).readText()
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            HofHttpResponse(200, request.url, if (request.url.contains("menu=fishing")) html
                else completeFishingMapPage("<a href='?common=Fish03'>Fishing- 악어</a>"), emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
        assertFailsWith<app.spammy.hof.common.error.ApiException> {
            when (path) {
                "MANUAL" -> fishingService.act(accountId, app.spammy.hof.town.fishing.model.FishingAction.START)
                "CYCLE" -> fishingService.executeOneCastForAutomation(accountId) { error("CATCH must not be prepared") }
                else -> fishingService.executeObservedActionForAutomation(accountId,
                    app.spammy.hof.town.fishing.model.FishingAction.START, fishingService.loadForAutomation(accountId))
            }
        }
        assertTrue(requests.none { it.method == HofHttpMethod.POST })
    }

    @ParameterizedTest
    @ValueSource(strings = ["KNOWN", "START", "RESTART"])
    fun `숨은 낚시 전투를 해소한 뒤 새 낚시와 하위 항목까지 스케줄러가 진행한다`(scenario: String) {
        var accepted = false
        val homeUrl = "https://hof.zerosic.com/index.php?menu=quest2"
        fun homeHtml() = """<h4>수락 가능한 퀘스트</h4><table><tr><td>[A] 후속 작업</td>
            <td>미션 0/1</td><td>-</td><td>-</td><td>${if (accepted) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"}</td>
            </tr></table>${if (accepted) "<div id='result'>수락했습니다.</div>" else ""}"""
        val quest = HomePageParser().parse(HomeMode.HOME, homeHtml(), homeUrl, HofFormParser().parse(homeHtml(), homeUrl)).quests.single()
        setupFishing(initialBattle = scenario == "KNOWN", startObstruction = scenario != "KNOWN",
            hiddenBattle = true, castsAfterBattle = 1, homeResponse = { request ->
                if (request.formFields["action"] == "get") accepted = true
                homeHtml()
            })
        val homeId = TransactionTemplate(transactions).execute {
            val account = entityManager.getReference(HofAccountEntity::class.java, accountId)
            val entry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST,
                singletonTypeMarker = AutomationType.HOME_QUEST, priority = 1, enabled = true,
                createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(entry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = entry, questId = quest.id,
                questName = quest.name, enabled = true, sourceOrder = 0))
            entityManager.flush()
            entry.id
        }
        val start = clock.now()
        wakeups.wake(accountId, "HIDDEN_FISHING_RECOVERY")
        publisher.publishBatch()
        if (scenario != "KNOWN") {
            assertEquals(listOf("FStart"), fishingPosts())
            assertEquals(0, runningWorkCount())
            if (scenario == "RESTART") {
                jdbc.update("update typed_automation_runtime_states set lease_token = ?, lease_until = ?, next_attempt_at = ? where account_id = ?",
                    UUID.randomUUID().toString(), java.sql.Timestamp.from(clock.now().minusSeconds(1)),
                    java.sql.Timestamp.from(clock.now().minusSeconds(1)), accountId)
                app.spammy.hof.automation.recovery.AutomationRecoveryScheduler(recoveryQuery, wakeups, clock).recoverOnStartup()
            }
            consumeNextWake()
        }
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        consumeFishingWakeUntil { "FCatch" in fishingPosts() }
        assertEquals(listOf("FStart", "FCatch"), fishingPosts().takeLast(2))
        val startIndex = requests.indexOfLast { "FStart" in it.formFields }
        val catchIndex = requests.indexOfLast { "FCatch" in it.formFields }
        val castRequests = requests.subList(startIndex - 2, catchIndex + 1)
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.GET, HofHttpMethod.POST, HofHttpMethod.POST), castRequests.map { it.method })
        assertEquals(0, runningWorkCount())
        consumeFishingWakeUntil { accepted }
        val cycles = journal.page(accountId, AutomationHistoryQuery()).cycles
        assertEquals(1, cycles.count { it.selectedEntryId == homeId }, cycles.toString())
        assertTrue(accepted)
        assertEquals(1, requests.count { it.formFields["action"] == "get" })
        assertEquals(if (scenario == "KNOWN") listOf("FStart", "FCatch") else listOf("FStart", "FStart", "FCatch"), fishingPosts())
        assertTrue(clock.now() <= start.plusSeconds(30), "후속 판단의 긴 지연을 그대로 따라가 통과시키지 않는다")
    }

    @ParameterizedTest
    @ValueSource(strings = ["IO", "INCOMPLETE", "NO_PRESET", "INVALID_PRESET", "TIME"])
    fun `낚시 전투 관측과 준비가 불가능해도 하위 항목은 진행한다`(failure: String) {
        var accepted = false
        val homeUrl = "https://hof.zerosic.com/index.php?menu=quest2"
        fun homeHtml() = """<h4>수락 가능한 퀘스트</h4><table><tr><td>[A] 독립 작업</td>
            <td>미션 0/1</td><td>-</td><td>-</td><td>${if (accepted) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"}</td>
            </tr></table>${if (accepted) "<div id='result'>수락했습니다.</div>" else ""}"""
        val quest = HomePageParser().parse(HomeMode.HOME, homeHtml(), homeUrl, HofFormParser().parse(homeHtml(), homeUrl)).quests.single()
        val fishingState = setupFishing(initialBattle = true, hiddenBattle = true,
            mapFailure = failure.takeIf { it in setOf("IO", "INCOMPLETE") }, homeResponse = { request ->
                if (request.formFields["action"] == "get") accepted = true
                homeHtml()
            })
        val homeId = TransactionTemplate(transactions).execute {
            val account = entityManager.getReference(HofAccountEntity::class.java, accountId)
            val entry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST,
                singletonTypeMarker = AutomationType.HOME_QUEST, priority = 1, enabled = true,
                createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(entry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = entry, questId = quest.id,
                questName = quest.name, enabled = true, sourceOrder = 0))
            entityManager.flush()
            entry.id
        }
        if (failure == "TIME") fishingState.currentTime = 0
        if (failure == "NO_PRESET") jdbc.update("update party_presets set is_primary = false, primary_marker = null where account_id = ?", accountId)
        if (failure == "INVALID_PRESET") jdbc.update("update character_pattern_slots set can_load = false where character_id in (select id from characters where account_id = ?)", accountId)
        wakeups.wake(accountId, "FISHING_SCOPE_ISOLATION")
        publisher.publishBatch()
        assertTrue(accepted, journal.page(accountId, AutomationHistoryQuery()).toString())
        assertEquals(homeId, journal.page(accountId, AutomationHistoryQuery()).cycles.first().selectedEntryId)
        assertTrue(fishingPosts().isEmpty())
        assertEquals(0, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        if (failure == "TIME") {
            val wait = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
                .single { it.reasonCode == "TIME_INSUFFICIENT" }
            assertEquals(clock.now().plusSeconds(160), wait.nextRunAt, "0→100 Time 회복은 기존 1.6초 규칙의 160초 이내다.")
            clock.current = assertNotNull(wait.nextRunAt)
            fishingState.currentTime = 100
            consumeFishingWakeUntil { requests.any { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") } }
            assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        }
        if (failure in setOf("NO_PRESET", "INVALID_PRESET")) {
            if (failure == "NO_PRESET") jdbc.update("update party_presets set is_primary = true, primary_marker = 1 where account_id = ?", accountId)
            else jdbc.update("update character_pattern_slots set can_load = true where character_id in (select id from characters where account_id = ?)", accountId)
            consumeFishingWakeUntil { requests.any { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") } }
            assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        }
        if (failure in setOf("IO", "INCOMPLETE")) {
            clock.current = clock.now().plusSeconds(121)
            consumeNextWake()
            consumeNextWake()
            assertTrue(store.findSuppressedBaselines(accountId).isNotEmpty(), "유한 관측 예산 뒤 낚시 범위가 보류되어야 한다")
            fishingState.mapFailure = null
            fishingState.battle = false
            consumeFishingWakeUntil { "FCatch" in fishingPosts() }
            assertTrue(store.findSuppressedBaselines(accountId).isEmpty(), "완전한 최신 상태로 낚시 가능 조건이 확인되면 관측 보류를 해소한다")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["FISHING_BATTLE_RECOVERED_FROM_START", "FISHING_CATCH_APPLIED"])
    fun `낚시 결과 이력 저장 실패는 확인한 결과와 후속 실행을 바꾸지 않는다`(failedRecord: String) {
        val recovery = failedRecord == "FISHING_BATTLE_RECOVERED_FROM_START"
        setupFishing(startObstruction = recovery, hiddenBattle = true)
        Mockito.doAnswer { invocation ->
            val trace = invocation.arguments[1] as app.spammy.hof.automation.history.AutomationActionTrace
            if (trace.reasonCode == failedRecord) throw IllegalStateException("diagnostic persistence unavailable")
            invocation.callRealMethod()
        }.`when`(journal).appendActionResult(Mockito.anyLong(), Mockito.any<app.spammy.hof.automation.history.AutomationActionTrace>()
            ?: app.spammy.hof.automation.history.AutomationActionTrace(AutomationHistoryEventKind.WAITING, "matcher", "matcher"))
        wakeups.wake(accountId, "FISHING_HISTORY_FAILURE")
        publisher.publishBatch()
        assertEquals(if (recovery) listOf("FAILED") else listOf("SUCCEEDED", "SUCCEEDED"), runs().map { it["status"] })
        assertEquals(0, runningWorkCount())
        assertFalse(journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .any { it.reasonCode == "FISHING_STAGE_AMBIGUOUS" }, "진단 저장 실패를 원격 행동 결과 유실로 기록하면 안 된다")
        consumeNextWake()
        assertEquals(if (recovery) listOf("FStart") else listOf("FStart", "FCatch"), fishingPosts())
        if (recovery) assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
    }

    @ParameterizedTest
    @ValueSource(strings = ["AUTOMATION", "MANUAL"])
    fun `전투 사전 조회의 쿠키 갱신은 다음 낚시 제출까지 이어진다`(path: String) {
        val state = setupFishing(rotatingCookies = true)
        if (path == "MANUAL") fishingService.act(accountId, app.spammy.hof.town.fishing.model.FishingAction.START)
        else {
            wakeups.wake(accountId, "FISHING_COOKIE_CHAIN")
            publisher.publishBatch()
        }
        val hunt = requests.indexOfFirst { it.url.endsWith("?hunt") }
        val start = requests.indexOfFirst { "FStart" in it.formFields }
        assertTrue(hunt >= 0 && start > hunt)
        assertEquals("fishing-cookie", state.requestCookies[hunt]["PHPSESSID"])
        assertEquals("hunt-cookie", state.requestCookies[start]["PHPSESSID"])
        if (path == "AUTOMATION") {
            assertEquals("start-cookie", state.requestCookies[start + 1]["PHPSESSID"])
            assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["FStart", "FCatch"])
    fun `불확실한 직접 낚시 응답도 후속 관측 전에 당시 진단으로 보존한다`(action: String) {
        val state = setupFishing(unconfirmedResponse = action)
        if (action == "FCatch") state.phase = "waiting"
        wakeups.wake(accountId, "FISHING_UNCONFIRMED_DIRECT")
        publisher.publishBatch()
        assertEquals(listOf(action), fishingPosts())
        val event = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .firstOrNull { it.reasonCode == "FISHING_DIRECT_RESULT_UNCONFIRMED" }
        val node = jacksonObjectMapper().readTree(assertNotNull(assertNotNull(event).diagnosticContext))
        assertEquals("DIRECT_RESPONSE", node["source"].asString())
        assertTrue(node["fishing"].isObject)
    }

    @Test
    fun `정상 낚시는 START CATCH 뒤 작업권을 놓고 후속 유휴 판단을 계속 소비한다`() {
        setupFishing()
        wakeups.wake(accountId, "FISHING_CONTINUITY_BASELINE")
        publisher.publishBatch()

        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED"), runs().map { it["status"] }, runs().toString())
        assertEquals(2, runs().map { it["execution_identity"] }.distinct().size)
        assertEquals(0, runningWorkCount())
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.GET, HofHttpMethod.POST, HofHttpMethod.POST), requests.map { it.method })
        assertEquals(listOf("?menu=fishing", "?hunt", "?menu=fishing", "?menu=fishing"), requests.map { it.url.substringAfter("index.php") })
        consumeNextWake()
        val firstIdle = journal.page(accountId, AutomationHistoryQuery()).cycles
        assertEquals(2, firstIdle.size)
        val idleAt = clock.now()
        assertScheduledWake("TYPED_NEXT_ROUND", idleAt.plusSeconds(3))
        consumeNextWake()
        assertEquals(idleAt.plusSeconds(3), clock.now())
        assertEquals(3, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(0, runningWorkCount())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `CATCH 방해 전투는 새 판단에서 최신 맵을 확인하고 별도 사이클로 실행한다`(mapPreviouslyObserved: Boolean) {
        val fishingState = setupFishing(obstruction = true, hiddenBattle = true)
        if (mapPreviouslyObserved) {
            fishingState.battle = true
            assertTrue(battleMaps.findMaps(accountId, "battle_map").any { it.mapCode == "fishing_12" })
            fishingState.battle = false
            requests.clear()
        }
        wakeups.wake(accountId, "FISHING_OBSTRUCTION_BASELINE")
        publisher.publishBatch()

        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED"), runs().map { it["status"] }, runs().toString())
        assertEquals(2, runs().map { it["execution_identity"] }.distinct().size)
        assertEquals(0, runningWorkCount())
        assertEquals(0, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        val fishingCycle = journal.page(accountId, AutomationHistoryQuery()).cycles.single()
        val requestBoundary = requests.size
        assertScheduledWake("TYPED_FISHING_CYCLE_COMPLETED", clock.now())

        consumeNextWake()

        val cycles = journal.page(accountId, AutomationHistoryQuery()).cycles
        assertEquals(2, cycles.size)
        assertEquals(1, cycles.count { it.id == fishingCycle.id })
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED", "SUCCEEDED"), runs().map { it["status"] }, runs().toString())
        assertEquals(3, runs().map { it["execution_identity"] }.distinct().size)
        val battleRequests = requests.drop(requestBoundary)
        val battlePost = battleRequests.indexOfFirst { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") }
        assertTrue(battlePost >= 0, "새 판단에서 전투를 한 번 제출해야 한다.")
        assertTrue(battleRequests.take(battlePost).any { it.method == HofHttpMethod.GET && it.url == "https://hof.zerosic.com/index.php?hunt" },
            "전투 제출 전에 최신 맵 관측을 거쳐야 한다.")
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        assertEquals(0, runningWorkCount())
        assertScheduledWake("TYPED_ACTION_COMPLETED", clock.now())

        consumeNextWake()
        assertEquals(3, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        assertEquals(0, runningWorkCount())
    }

    @Test
    fun `일반 다음 판단의 완전한 전투 부재는 이전 복구 반복을 끊는다`() {
        val state = setupFishing(startObstruction = true, hiddenBattle = true)
        wakeups.wake(accountId, "FISHING_EXTERNAL_RECOVERY")
        publisher.publishBatch()
        assertEquals(listOf("FStart"), fishingPosts())

        // 원본 서버에서 이전 전투가 해소되고, 다음 START 이후 별개의 전투가 나타난다.
        state.battle = false
        state.phase = "reset"
        state.revealOnStart = true
        consumeNextWake()

        val events = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }.sortedBy { it.id }
        val recovered = events.filter { it.reasonCode == "FISHING_BATTLE_RECOVERED_FROM_START" }
        assertEquals(2, recovered.size)
        assertEquals(listOf(1, 1), recovered.map {
            jacksonObjectMapper().readTree(it.diagnosticContext).path("repetition").path("count").asInt()
        }, "일반 선택 조회에서 확인한 부재도 새 반복 사건의 경계다.")
        val resolved = events.single { it.reasonCode == "FISHING_RECOVERY_RESOLVED" }
        assertTrue(resolved.id > recovered.first().id && resolved.id < recovered.last().id)
        assertEquals("ENTRY_EVALUATION", jacksonObjectMapper().readTree(resolved.diagnosticContext).path("stage").asString())
        assertEquals(listOf("FStart", "FStart"), fishingPosts())
        assertTrue(events.none { it.reasonCode == "FISHING_RECOVERY_REPEATED" })
    }

    @Test
    fun `START에서 발견한 이전 전투는 성공 귀속 없이 새 판단의 별도 전투로 실행한다`() {
        setupFishing(startObstruction = true, hiddenBattle = true)
        wakeups.wake(accountId, "FISHING_START_OBSTRUCTION")
        publisher.publishBatch()

        assertEquals(listOf("FStart"), fishingPosts())
        assertEquals(listOf("FAILED"), runs().map { it["status"] }, runs().toString())
        assertEquals(0, runningWorkCount())
        assertEquals(listOf("COMPLETED"), jdbc.queryForList(
            "select status from automation_work_sessions where account_id = ?", String::class.java, accountId))
        assertEquals(1, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        val selection = jdbc.queryForList(
            "select result from automation_action_convergences where account_id = ?", String::class.java, accountId)
        assertEquals(listOf("SUPERSEDED"), selection)
        assertEquals(0, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        assertScheduledWake("ACTION_SUPERSEDED_BY_FRESH_STATE", clock.now())
        val recoveryEvent = journal.page(accountId, AutomationHistoryQuery()).cycles.single().events.single {
            it.reasonCode == "FISHING_BATTLE_RECOVERED_FROM_START"
        }
        val diagnostic = assertNotNull(recoveryEvent.diagnosticContext)
        val context = jacksonObjectMapper().readTree(diagnostic)
        assertEquals("DIRECT_RESPONSE", context["source"].asString())
        assertEquals("fishing_12", context["fishing"]["battleMapCode"].asString())
        assertTrue(context["fishing"]["blockedByBattle"].asBoolean())
        assertEquals("fishing_12", recoveryEvent.targetKey)
        assertNotNull(context["workSessionId"])
        assertTrue(context["recheckRequired"].asBoolean())
        val boundary = requests.size

        consumeNextWake()

        assertEquals(diagnostic, journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }.single { it.id == recoveryEvent.id }.diagnosticContext)
        assertEquals(listOf("FAILED", "SUCCEEDED"), runs().map { it["status"] }, runs().toString())
        assertEquals(2, runs().map { it["execution_identity"] }.distinct().size)
        assertEquals(2, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        val following = requests.drop(boundary)
        val postIndex = following.indexOfFirst { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") }
        assertTrue(postIndex >= 0)
        assertTrue(following.take(postIndex).any { it.method == HofHttpMethod.GET && it.url == "https://hof.zerosic.com/index.php?hunt" })
        assertEquals(0, runningWorkCount())
        assertEquals(listOf("COMPLETED", "COMPLETED"), jdbc.queryForList(
            "select status from automation_work_sessions where account_id = ? order by id", String::class.java, accountId))
        assertScheduledWake("TYPED_ACTION_COMPLETED", clock.now())
        consumeNextWake()
        assertEquals(3, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(listOf("FStart"), fishingPosts())
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
    }

    @ParameterizedTest
    @ValueSource(strings = ["FStart", "FCatch"])
    fun `유실된 낚시 응답의 최신 전투 관측은 이전 행동과 별도 작업으로 수렴한다`(lostAction: String) {
        setupFishing(obstruction = true, startObstruction = lostAction == "FStart", lostFishingResponse = lostAction, hiddenBattle = true)
        wakeups.wake(accountId, "FISHING_LOST_RESPONSE")
        publisher.publishBatch()
        val expectedPosts = if (lostAction == "FStart") listOf("FStart") else listOf("FStart", "FCatch")
        assertEquals(expectedPosts, fishingPosts())
        assertEquals("RECONCILING", runs().last()["status"], runs().toString())
        assertNotNull(runs().last()["submitted_at"])
        assertEquals(0, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })

        // 저장 시도 복원은 이 깨우기에서 원래 시각·예산으로 재확인한다.
        nextRun()

        assertEquals(expectedPosts, fishingPosts())
        val results = jdbc.queryForList(
            "select result from automation_action_convergences where account_id = ? order by id", String::class.java, accountId)
        assertEquals(if (lostAction == "FStart") listOf("SUPERSEDED") else listOf("APPLIED", "SUPERSEDED"), results)
        val unresolvedRuns = if (lostAction == "FStart") listOf("AMBIGUOUS") else listOf("SUCCEEDED", "AMBIGUOUS")
        assertEquals(unresolvedRuns, runs().map { it["status"] })
        val recoveryDiagnostics = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .mapNotNull { it.diagnosticContext }.map { jacksonObjectMapper().readTree(it) }
        assertTrue(recoveryDiagnostics.any { it.path("source").asString() == "LATEST_OBSERVATION" }, "유실 복구 관측도 이력에 보존해야 한다")
        assertEquals(listOf("COMPLETED"), jdbc.queryForList(
            "select status from automation_work_sessions where account_id = ?", String::class.java, accountId))
        assertEquals(0, runningWorkCount())

        assertEquals(0, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        assertScheduledWake("TYPED_CONVERGENCE_CONTINUE", clock.now())

        consumeNextWake()
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        assertEquals(expectedPosts, fishingPosts())
        assertEquals(listOf("COMPLETED", "COMPLETED"), jdbc.queryForList(
            "select status from automation_work_sessions where account_id = ? order by id", String::class.java, accountId))
        assertEquals(unresolvedRuns + "SUCCEEDED", runs().map { it["status"] })
        assertScheduledWake("TYPED_ACTION_COMPLETED", clock.now())
        val before = journal.page(accountId, AutomationHistoryQuery()).cycles.size
        consumeNextWake()
        assertEquals(before + 1, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertScheduledWake("TYPED_NEXT_ROUND", clock.now().plusSeconds(3))
        assertEquals(expectedPosts, fishingPosts())
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
    }

    @Test
    fun `CATCH 뒤 새로 실행 가능한 상위 항목이 방해 전투보다 먼저 선택된다`() {
        val url = "https://hof.zerosic.com/index.php?menu=quest2"
        var ready = false
        var accepted = false
        fun homePage(showQuest: Boolean = ready) = """<h4>수락 가능한 퀘스트</h4><table>
            ${if (showQuest) """<tr><td>[A] 상위 작업</td><td>미션 0/1</td><td>-</td><td>-</td><td>${if (accepted) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=A'>수락</a>"}</td></tr>""" else ""}
            </table>${if (accepted) "<div id='result'>수락했습니다.</div>" else ""}"""
        val html = homePage(showQuest = true)
        val quest = HomePageParser().parse(HomeMode.HOME, html, url, HofFormParser().parse(html, url)).quests.single()
        setupFishing(obstruction = true, homeResponse = { request ->
            if (request.formFields["action"] == "get") accepted = true
            homePage()
        })
        val homeEntryId = TransactionTemplate(transactions).execute {
            val account = entityManager.find(HofAccountEntity::class.java, accountId)
            entityManager.find(AutomationEntryEntity::class.java, entryId).priority = 1
            entityManager.flush()
            val entry = AutomationEntryEntity(account = account, type = AutomationType.HOME_QUEST, priority = 0,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(entry)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = entry, questId = quest.id,
                questName = quest.name, enabled = true, sourceOrder = 0))
            entityManager.flush()
            entry.id
        }
        wakeups.wake(accountId, "FISHING_PRIORITY_BOUNDARY")
        publisher.publishBatch()
        assertEquals(entryId, journal.page(accountId, AutomationHistoryQuery()).cycles.single().selectedEntryId)
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(0, runningWorkCount())

        ready = true
        assertScheduledWake("TYPED_FISHING_CYCLE_COMPLETED", clock.now())
        consumeNextWake()
        val afterHome = journal.page(accountId, AutomationHistoryQuery()).cycles
        assertEquals(2, afterHome.size)
        assertEquals(homeEntryId, afterHome.first().selectedEntryId, afterHome.toString())
        val acceptance = requests.single { it.formFields["action"] == "get" }
        assertEquals(HofHttpMethod.GET, acceptance.method)
        assertEquals("A", acceptance.formFields["no"])
        assertEquals(0, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })

        consumeNextWake()
        assertEquals(3, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(0, runningWorkCount())
        assertEquals(0, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        assertEquals(listOf("WAITING_COOLDOWN"), jdbc.queryForList(
            "select status from automation_work_sessions where account_id = ? and automation_entry_id = ?",
            String::class.java, accountId, homeEntryId))

        consumeNextWake()
        assertEquals(4, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(entryId, journal.page(accountId, AutomationHistoryQuery()).cycles.first().selectedEntryId)
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(0, runningWorkCount())
        consumeNextWake()
        assertEquals(5, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(1, requests.count { it.formFields["action"] == "get" })
        assertEquals(1, requests.count { it.method == HofHttpMethod.POST && it.url.contains("common=fishing_12") })
    }

    @ParameterizedTest
    @ValueSource(strings = ["SETTINGS", "PAUSE", "AUTH"])
    fun `설정 정지와 사용자 일시정지와 인증 중단 뒤 최신 상태로 낚시 판단을 재개한다`(boundary: String) {
        setupFishing()
        wakeups.wake(accountId, "BEFORE_CONTROL_CHANGE")
        when (boundary) {
            "SETTINGS" -> application.updateFishing(accountId, app.spammy.hof.automation.dto.UpdateFishingAutomationRequest(false))
            "PAUSE" -> application.pauseTyped(accountId)
            "AUTH" -> {
                Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(false)
                TransactionTemplate(transactions).executeWithoutResult {
                    lifecycle.suspendForAuthentication(accountId, "AUTH_SUSPEND_BASELINE")
                }
            }
        }
        consumeNextWake()
        assertTrue(fishingPosts().isEmpty())
        assertTrue(runs().isEmpty())
        val beforeResume = journal.page(accountId, AutomationHistoryQuery()).cycles.size
        when (boundary) {
            "SETTINGS" -> application.updateFishing(accountId, app.spammy.hof.automation.dto.UpdateFishingAutomationRequest(true))
            "PAUSE" -> application.resumeTyped(accountId)
            "AUTH" -> {
                Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(true)
                TransactionTemplate(transactions).executeWithoutResult {
                    assertTrue(lifecycle.resumeAfterAuthentication(accountId, "AUTH_RESUME_BASELINE"))
                }
            }
        }
        consumeNextWake()
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED"), runs().map { it["status"] })
        assertEquals(0, runningWorkCount())
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.size > beforeResume)
        val beforeIdle = journal.page(accountId, AutomationHistoryQuery()).cycles.size
        consumeNextWake()
        assertEquals(beforeIdle + 1, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
    }

    @Test
    fun `실제 마지막 token 폐기 뒤 깨우기는 제출하지 않고 새 family에서 낚시와 다음 판단을 이어간다`() {
        setupFishing()
        Mockito.doCallRealMethod().`when`(authorization).isExecutionAllowed(accountId)
        val account = TransactionTemplate(transactions).execute {
            entityManager.find(HofAccountEntity::class.java, accountId)
        }
        val original = refreshTokens.issue(account, "NATIVE")
        wakeups.wake(accountId, "BEFORE_REAL_LOGOUT")
        auth.logout(original.value)

        consumeNextWake()
        assertTrue(fishingPosts().isEmpty())
        assertFalse(authorization.isExecutionAllowed(accountId))
        val beforeResume = journal.page(accountId, AutomationHistoryQuery()).cycles.size

        refreshTokens.issue(account, "NATIVE")
        assertTrue(authorization.isExecutionAllowed(accountId))
        consumeNextWake()
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED"), runs().map { it["status"] })
        assertEquals(0, runningWorkCount())
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.size > beforeResume)
        val beforeIdle = journal.page(accountId, AutomationHistoryQuery()).cycles.size
        consumeNextWake()
        assertEquals(beforeIdle + 1, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
    }

    @Test
    fun `프로세스 시작 복구는 만료된 runtime lease의 계정을 깨워 행동과 후속 판단을 이어간다`() {
        setupFishing()
        jdbc.update("update typed_automation_runtime_states set lease_token = ?, lease_until = ?, next_attempt_at = ? where account_id = ?",
            UUID.randomUUID().toString(), java.sql.Timestamp.from(clock.now().minusSeconds(1)),
            java.sql.Timestamp.from(clock.now().minusSeconds(1)), accountId)
        assertTrue(recoveryQuery.findDueAccountIds(clock.now()).contains(accountId))
        app.spammy.hof.automation.recovery.AutomationRecoveryScheduler(recoveryQuery, wakeups, clock).recoverOnStartup()
        consumeNextWake()
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED"), runs().map { it["status"] })
        consumeNextWake()
        assertEquals(2, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(listOf("FStart", "FCatch"), fishingPosts())
        assertNull(jdbc.queryForObject("select lease_token from typed_automation_runtime_states where account_id = ?", String::class.java, accountId))
    }

    private fun consumeFishingWakeUntil(done: () -> Boolean) {
        val deadline = clock.now().plusSeconds(30)
        repeat(4) {
            if (done()) return
            consumeNextWake()
            assertTrue(clock.now() <= deadline, "낚시 후속 소비가 30초를 넘었다")
        }
        assertTrue(done(), "네 번의 실제 후속 소비 안에 진전해야 한다")
    }

    @Test
    fun `첫 패턴 실패 뒤 맵이 사라지면 전투를 보내지 않는다`() {
        failedPattern = 1
        runner.runOne(accountId)
        assertEquals("FAILED", runs().single()["status"])
        assertEquals(0, battleRequests().size)
        clock.current = nextRetryAt()
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            HofHttpResponse(200, request.url, "<div id='menu2'>Funds : $ 1 Time : 100/100</div>아무것도 없다", emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
        runner.runOne(accountId)
        assertEquals(0, battleRequests().size)
        assertTrue(store.findSuppressedBaselines(accountId).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(value = ActionConvergenceResult::class, names = ["HELD", "PENDING"])
    fun `자택 A만 보류이면 실제 후보 B를 선택 제출하고 A 제외 이유를 이력에 남긴다`(result: ActionConvergenceResult) {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        val url = "https://hof.zerosic.com/index.php?menu=housing"
        var accepted = false
        fun page() = """<div id="menu2">Funds : $ 1 Time : 100/100</div>
            <h4>수락 가능한 퀘스트</h4><table>
            <tr><td>[A] 작업 A</td><td>미션 0/1</td><td>-</td><td>-</td><td><a href="?menu=housing&amp;action=get&amp;no=A">수락</a></td></tr>
            <tr><td>[B] 작업 B</td><td>미션 0/1</td><td>-</td><td>-</td><td>${if (accepted) "-" else "<a href='?menu=housing&amp;action=get&amp;no=B'>수락</a>"}</td></tr>
            </table>"""
        val quests = HomePageParser().parse(HomeMode.HOME, page(), url, HofFormParser().parse(page(), url)).quests
        assertEquals(2, quests.size)
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.HOME_QUEST
            entry.singletonTypeMarker = AutomationType.HOME_QUEST
            quests.forEachIndexed { order, quest ->
                entityManager.persist(HomeQuestAutomationSelectionEntity(entry = entry,
                    questId = quest.id, questName = quest.name, enabled = true, sourceOrder = order))
            }
        }
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            if (request.formFields["action"] == "get" && request.formFields["no"] == "B") accepted = true
            HofHttpResponse(200, url, page(), emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
        val a = quests.first()
        val action = HomeQuestAutomationAction(accountId, a.id, a.name, requireNotNull(a.actionId), HomeQuestAutomationActionType.ACCEPT)
        val preview = StoredActionConvergenceSelectionFactory().preview(entryId, action)
        val old = store.createOrGet(accountId, SelectedAutomationAction(entryId, UUID.randomUUID().toString(),
            preview.actionKind, preview.scope, "automation-action-convergence-v1", requireNotNull(preview.baselineFingerprint)), clock.now())
        old.result = result
        old.nextProbeAt = clock.now().plusSeconds(60)
        old.finishedAt = clock.now().takeIf { result == ActionConvergenceResult.HELD }
        store.save(old)

        val selected = assertIs<AutomationCoordination.Runnable>(decisions.select(accountId))
        assertEquals(quests[1].id, assertIs<HomeQuestAutomationAction>(selected.action).questId)
        wakeups.wake(accountId, "CANDIDATE_CONTINUITY_BASELINE")
        publisher.publishBatch()

        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" })
        assertEquals(0, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(result, store.get(old.attemptId)?.result)
        val history = journal.page(accountId, AutomationHistoryQuery()).cycles.single()
        assertEquals(1, history.topLevelStepCount)
        val diagnostic = jacksonObjectMapper().readTree(requireNotNull(history.steps.first().event.diagnosticContext))
        assertEquals(a.id, diagnostic["excludedCandidates"][0]["targetKey"].asString())
        assertEquals(if (result == ActionConvergenceResult.HELD) "이전 행동 결과를 확정하지 못해 해당 범위를 보류했습니다. 최신 상태의 복구 조건을 확인하면 해제합니다."
            else "이전 행동 결과를 확인 중이라 해당 범위만 잠시 건너뜁니다.", diagnostic["excludedCandidates"][0]["reasonMessage"].asString())
        assertEquals(1, transport.delivered.size)
        consumeNextWake()
        assertEquals(2, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" })
        assertEquals(0, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(0, jdbc.queryForObject("select count(*) from account_automation_leases where account_id = ?", Int::class.java, accountId))
    }

    @ParameterizedTest
    @EnumSource(value = ActionConvergenceResult::class, names = ["HELD", "PENDING"])
    fun `일반 퀘스트 A만 보류이면 실제 후보 B를 제출하고 B의 수락 사이클만 증가한다`(result: ActionConvergenceResult) {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        val url = "https://hof.zerosic.com/index.php?menu=quest"
        val header = "<tr><td>퀘스트명</td><td>타입</td><td>제한</td><td>보상</td><td>행동</td></tr>"
        var accepted = false
        fun row(id: String, active: Boolean = false) = """<tr><td class="td7s">[Q00$id] 작업 $id</td><td>미션 : 즉시 완료</td><td>-</td><td>-</td><td>${if (active) "-" else "<a href='?menu=quest&amp;action=get&amp;no=$id'>수락</a>"}</td></tr>"""
        val emptyPage = requireNotNull(javaClass.getResource("/fixtures/quest/quest-complete-empty.html")).readText()
        fun page() = emptyPage.replace(
            "<h4>진행중인 퀘스트 목록</h4>\n  <table>$header</table>",
            "<h4>진행중인 퀘스트 목록</h4><table>$header${if (accepted) row("B", true) else ""}</table>",
        ).replace(
            "<h4>수락 가능한 퀘스트 목록</h4>\n  <table>$header</table>",
            "<h4>수락 가능한 퀘스트 목록</h4><table>$header${row("A")}${if (accepted) "" else row("B")}</table>",
        )
        val observation = QuestPageParser().parseObservation(page(), url)
        assertTrue(observation.complete)
        val quests = observation.quests.filter { it.actionNo in listOf("A", "B") }
        assertEquals(2, quests.size)
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.QUEST
            entry.singletonTypeMarker = AutomationType.QUEST
            quests.forEachIndexed { order, quest ->
                entityManager.persist(QuestAutomationSelectionEntity(entry = entry,
                    questKey = quest.questKey, enabled = true, sourceOrder = order))
            }
        }
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            if (request.formFields["action"] == "get" && request.formFields["no"] == "B") accepted = true
            HofHttpResponse(200, url, page(), emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
        val a = quests.first()
        val preview = StoredActionConvergenceSelectionFactory().preview(entryId, QuestAction.Accept(a.questKey, "A", a.name, "0"))
        val old = store.createOrGet(accountId, SelectedAutomationAction(entryId, UUID.randomUUID().toString(),
            preview.actionKind, preview.scope, "automation-action-convergence-v1", requireNotNull(preview.baselineFingerprint)), clock.now())
        old.result = result
        old.nextProbeAt = clock.now().plusSeconds(60)
        old.finishedAt = clock.now().takeIf { result == ActionConvergenceResult.HELD }
        store.save(old)

        val selected = assertIs<AutomationCoordination.Runnable>(decisions.select(accountId))
        assertEquals(quests[1].questKey, assertIs<QuestAction.Accept>(selected.action).questKey)
        wakeups.wake(accountId, "WAITING_CONTINUITY_BASELINE")
        publisher.publishBatch()

        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" })
        assertEquals(0, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
        assertEquals(result, store.get(old.attemptId)?.result)
        assertEquals(listOf(quests[1].questKey), jdbc.queryForList(
            "select quest_code from quest_automation_cycles where account_id = ?", String::class.java, accountId))
        val history = journal.page(accountId, AutomationHistoryQuery()).cycles.single()
        assertEquals(1, history.topLevelStepCount)
        val diagnostic = jacksonObjectMapper().readTree(requireNotNull(history.steps.first().event.diagnosticContext))
        assertEquals(a.questKey, diagnostic["excludedCandidates"][0]["targetKey"].asString())
        assertEquals(if (result == ActionConvergenceResult.HELD) "이전 행동 결과를 확정하지 못해 해당 범위를 보류했습니다. 최신 상태의 복구 조건을 확인하면 해제합니다."
            else "이전 행동 결과를 확인 중이라 해당 범위만 잠시 건너뜁니다.", diagnostic["excludedCandidates"][0]["reasonMessage"].asString())
        consumeNextWake()
        assertEquals(2, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" })
        assertEquals(0, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "A" })
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `외부 레이드 재확인은 오래된 경고를 해제하고 하위 행동과 다음 재확인까지 진행한다`(loseRefreshResponse: Boolean) {
        setupRaid()
        val oldWarning = "다른 사용자가 진행 중인 설정 레이드는 자동 행동 없이 다시 확인합니다."
        val homeUrl = "https://hof.zerosic.com/index.php?menu=quest2"
        var accepted = false
        var responseLost = false
        fun homePage() = """<div id="menu2">Funds : $ 1 Time : 100/100</div><h4>${if (accepted) "진행 중인 퀘스트" else "수락 가능한 퀘스트"}</h4><table>
            <tr><td>[B] 하위 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>${if (accepted) "-" else "<a href='?menu=quest2&amp;action=get&amp;no=B'>수락</a>"}</td></tr></table>"""
        val quest = HomePageParser().parse(HomeMode.HOME, homePage(), homeUrl, HofFormParser().parse(homePage(), homeUrl)).quests.single()
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entityManager.persist(AutomationWorkSessionEntity(account = entry.account, entry = entry,
                workType = AutomationWorkType.RAID, targetKey = "RaidGoblin", status = AutomationWorkStatus.WAITING_COOLDOWN,
                configVersion = entry.updatedAt.toString(), nextCheckAt = clock.now(), holdMessage = oldWarning,
                createdAt = clock.now(), updatedAt = clock.now()))
            val home = AutomationEntryEntity(account = entry.account, type = AutomationType.HOME_QUEST, priority = 1,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(home)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = home, questId = quest.id,
                questName = quest.name, enabled = true, sourceOrder = 0))
        }
        assertContains(application.getTyped(accountId).runtime.warnings, oldWarning)
        val externalRaid = raidHtml(false).replace("현재 상태 : 파티 모집 중 (신청 안됨)", "현재 상태 : 전투 중 (신청 안됨)")
        Mockito.doAnswer { invocation ->
            val request = invocation.getArgument<HofRequest>(1)
            requests += request
            if (request.formFields["action"] == "get" && request.formFields["no"] == "B") accepted = true
            if (loseRefreshResponse && !responseLost && "refresh_nonce" in request.formFields) {
                responseLost = true
                throw IOException("외부 레이드 상태 갱신 직접 응답 유실")
            }
            val isHome = request.url.contains("menu=quest2") || request.formFields["no"] == "B"
            HofHttpResponse(200, if (isHome) homeUrl else "https://hof.zerosic.com/index.php?menu=raidpub",
                if (isHome) homePage() else externalRaid, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())

        fun waitSession() = workSessions.findWaiting(accountId).single { it.workType == AutomationWorkType.RAID }
        fun refreshCount() = requests.count { "refresh_nonce" in it.formFields }
        fun consumeUntil(done: () -> Boolean) {
            repeat(32) { if (done()) return; consumeNextWake() }
            assertTrue(done(), "영속 wakeup을 소비해 재확인과 하위 행동이 진전해야 한다. accepted=$accepted, wait=${waitSession()}, requests=${requests.takeLast(5).map { it.url to it.formFields }}")
        }
        wakeups.wake(accountId, "EXTERNAL_RAID_WARNING_RECHECK")
        consumeUntil { accepted && waitSession().holdMessage == null &&
            waitSession().nextCheckAt!! >= clock.now().plusSeconds(600) }
        val firstWait = waitSession()
        assertTrue(application.getTyped(accountId).runtime.warnings.isEmpty())
        assertTrue(application.getTyped(accountId).entries.first { it.id == entryId }.ready)
        val history = journal.page(accountId, AutomationHistoryQuery()).cycles
        val raidEvent = assertNotNull(history.flatMap { it.events }.firstOrNull { oldWarning in it.message },
            "외부 레이드 재확인 사유를 이력에서 확인할 수 있어야 한다. ${history.flatMap { it.events }}")
        assertEquals("RAID_EXTERNAL_CONFIGURED_ACTIVE", raidEvent.reasonCode)
        assertEquals(firstWait.nextCheckAt, raidEvent.nextRunAt)
        assertEquals(AutomationImpactScope.RAID_ONLY, raidEvent.impactScope)
        assertFalse(raidEvent.releaseCondition.isNullOrBlank())
        assertTrue(history.flatMap { it.events }.any {
            it.kind == AutomationHistoryEventKind.ACTION_SUCCEEDED && it.type == AutomationType.HOME_QUEST
        })
        val previousCycles = history.map { it.id }.toSet()
        val previousRefreshes = refreshCount()
        clock.current = assertNotNull(firstWait.nextCheckAt)
        app.spammy.hof.automation.recovery.AutomationRecoveryScheduler(recoveryQuery, wakeups, clock).recoverOnStartup()
        consumeUntil { refreshCount() > previousRefreshes && waitSession().nextCheckAt!! > firstWait.nextCheckAt }
        consumeNextWake()

        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.any { it.id !in previousCycles })
        val recheckEvent = journal.page(accountId, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .first { it.reasonCode == "RAID_EXTERNAL_CONFIGURED_ACTIVE" }
        assertContains(recheckEvent.message, oldWarning)
        assertEquals(waitSession().nextCheckAt, recheckEvent.nextRunAt)
        assertNull(waitSession().holdMessage)
        assertNull(workSessions.findRunning(accountId))
        assertTrue(application.getTyped(accountId).runtime.warnings.isEmpty())
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" })
        assertTrue(requests.filter { it.method == HofHttpMethod.POST }.all {
            "refresh_nonce" in it.formFields || (it.formFields["action"] == "get" && it.formFields["no"] == "B")
        }, "외부 레이드에는 참가·전투·보상·리셋을 제출하지 않는다.")
        assertEquals(loseRefreshResponse, responseLost)
    }

    @ParameterizedTest
    @EnumSource(RaidWaitReason::class)
    fun `레이드 대기 사유를 저장하면서 하위 자택을 실행하고 재확인 시각을 보존한다`(reason: RaidWaitReason) {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        val retryAt = clock.now().plusSeconds(120)
        val directive = RaidDirective.WaitUntil(retryAt, reason, "레이드 상태 재확인", entryId, "RaidGoblin",
            impactScope = AutomationImpactScope.RAID_ONLY, releaseCondition = "최신 레이드 상태 재확인")
        Mockito.doReturn(RaidDecision(directive)).`when`(raidModule).decide(accountId)
        val url = "https://hof.zerosic.com/index.php?menu=housing"
        var accepted = false
        fun page() = """<div id="menu2">Funds : $ 1 Time : 100/100</div><h4>수락 가능한 퀘스트</h4><table>
            <tr><td>[B] 하위 자택</td><td>미션 0/1</td><td>-</td><td>-</td><td>${if (accepted) "-" else "<a href='?menu=housing&amp;action=get&amp;no=B'>수락</a>"}</td></tr></table>"""
        val quest = HomePageParser().parse(HomeMode.HOME, page(), url, HofFormParser().parse(page(), url)).quests.single()
        TransactionTemplate(transactions).executeWithoutResult {
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.RAID
            entry.singletonTypeMarker = AutomationType.RAID
            val home = AutomationEntryEntity(account = entry.account, type = AutomationType.HOME_QUEST, priority = 1,
                enabled = true, createdAt = clock.now(), updatedAt = clock.now())
            entityManager.persist(home)
            entityManager.persist(HomeQuestAutomationSelectionEntity(entry = home,
                questId = quest.id, questName = quest.name, enabled = true, sourceOrder = 0))
        }
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            if (request.formFields["action"] == "get" && request.formFields["no"] == "B") accepted = true
            HofHttpResponse(200, url, page(), emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())

        wakeups.wake(accountId, "WAITING_CONTINUITY_BASELINE")
        publisher.publishBatch()

        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" })
        val history = journal.page(accountId, AutomationHistoryQuery()).cycles.single()
        val raidEvent = history.steps.first().event
        val expectedKind = if (reason in setOf(RaidWaitReason.BATTLE_RECOVERY_RECHECK,
                RaidWaitReason.REWARD_CONFIRMATION, RaidWaitReason.POST_REWARD_CHECK)) {
            AutomationHistoryEventKind.WAITING
        } else AutomationHistoryEventKind.SKIPPED
        assertEquals(2, history.topLevelStepCount)
        assertEquals(expectedKind, raidEvent.kind)
        assertEquals(reason.name, raidEvent.reasonCode)
        assertEquals("RaidGoblin", raidEvent.targetKey)
        assertEquals(retryAt, raidEvent.nextRunAt)
        assertNotNull(raidEvent.diagnosticContext)
        assertEquals(retryAt, jdbc.queryForObject(
            "select next_check_at from automation_work_sessions where account_id = ? and work_type = 'RAID'",
            java.sql.Timestamp::class.java, accountId)?.toInstant())
        consumeNextWake()
        assertEquals(2, journal.page(accountId, AutomationHistoryQuery()).cycles.size)
        assertEquals(1, requests.count { it.formFields["action"] == "get" && it.formFields["no"] == "B" })
        assertEquals(raidEvent, journal.page(accountId, AutomationHistoryQuery()).cycles.first { it.id == history.id }.steps.first().event)
    }

    @ParameterizedTest
    @ValueSource(strings = ["runnable", "runnable-sentence", "cooldown", "incomplete"])
    fun `상태 갱신 보류가 있어도 실제 갱신과 후속 판단에서 현재 레이드 상태를 처리한다`(state: String) {
        setupRaid()
        val registration = holdRegistration()
        val refresh = StoredActionConvergenceSelectionFactory().preview(entryId,
            RaidTownAutomationAction(accountId, RaidAction.REFRESH, null, "RaidGoblin"))
        val old = store.createOrGet(accountId, SelectedAutomationAction(entryId, "held-refresh-$accountId",
            refresh.actionKind, refresh.scope, "automation-action-convergence-v1",
            requireNotNull(refresh.baselineFingerprint)), clock.now())
        old.result = ActionConvergenceResult.HELD
        old.reasonCode = "PENDING_BUDGET_EXHAUSTED"
        old.finishedAt = clock.now()
        store.save(old)
        if (state == "cooldown") raidPageTransform = {
            it.replace("현재 상태는 신청 가능", "현재 상태는 신청 대기 (신청 가능까지 6분 58초)")
        }
        if (state == "runnable-sentence") raidPageTransform = {
            it.replace("현재 상태는 신청 가능", "<div class=\"result\">현재 상태는 신청 가능 상태입니다.</div>")
        }
        incompleteRefreshPost = state == "incomplete"

        wakeups.wake(accountId, "RAID_HELD_REFRESH_RECOVERY")
        publisher.publishBatch()

        assertEquals(1, requests.count { it.formFields.containsKey("refresh_nonce") },
            journal.page(accountId, AutomationHistoryQuery()).cycles.toString())
        assertFalse(old.selection.baselineFingerprint in store.findSuppressedBaselines(accountId)[old.selection.scope].orEmpty())
        assertEquals(ActionConvergenceResult.HELD, store.get(old.attemptId)?.result)
        assertTrue(runs().none { it["execution_identity"] == old.selection.executionIdentity })
        val firstCycle = journal.page(accountId, AutomationHistoryQuery()).cycles.single().id
        repeat(3) { consumeNextWake() }
        assertTrue(journal.page(accountId, AutomationHistoryQuery()).cycles.any { it.id != firstCycle })
        assertEquals(if (state.startsWith("runnable")) 1 else 0, registerRequests().size)
        assertEquals(!state.startsWith("runnable"), registration.selection.baselineFingerprint in
            store.findSuppressedBaselines(accountId)[registration.selection.scope].orEmpty())
        assertEquals(0, runningWorkCount())
    }

    @Test
    fun `과거 신청 보류는 실제 갱신 뒤 해제되고 새 신청은 한 번만 제출된다`() {
        setupRaid()
        runner.runOne(accountId) // 먼저 사이클을 연다.
        val old = holdRegistration()
        requests.clear()
        nextRun() // 기존 사이클도 신청 전에 REFRESH
        assertTrue(requests.any { it.formEntries.any { field -> field.name == "refresh_nonce" } })
        assertFalse(store.findSuppressedBaselines(accountId).values.any { old.selection.baselineFingerprint in it })
        assertEquals(ActionConvergenceResult.HELD, store.get(old.attemptId)?.result)
        assertEquals("RAID_REGISTRATION_FRESH_DECISION_RELEASED", store.get(old.attemptId)?.reasonCode)
        val previousIdentities = runs().map { it["execution_identity"] }.toSet()
        nextRun()
        assertEquals(1, registerRequests().size)
        val registrationRun = runs().single { it["execution_identity"] !in previousIdentities }
        val identity = assertIs<String>(registrationRun["execution_identity"])
        assertEquals("SUCCEEDED", registrationRun["status"])
        val payload = jacksonObjectMapper().readTree(assertNotNull(jdbc.queryForObject(
            "select payload_json from typed_automation_action_runs where account_id = ? and execution_identity = ?",
            String::class.java, accountId, identity)))["payload"]
        assertEquals("RAID_TOWN", payload["kind"].asString())
        assertEquals("REGISTER", payload["action"].asString())
        assertEquals("RaidGoblin", payload["targetRaidId"].asString())
        fun assertRegistrationApplied() {
            val applied = assertNotNull(store.get(accountId, identity))
            assertEquals(AutomationActionKind.RAID_REGISTER, applied.selection.actionKind)
            assertEquals(ActionConvergenceResult.APPLIED, applied.result)
            assertEquals("DIRECT_RESPONSE_APPLIED", applied.reasonCode)
            assertEquals(ActionConvergenceResult.HELD, store.get(old.attemptId)?.result)
        }
        assertRegistrationApplied()
        val registrationCycle = journal.page(accountId, AutomationHistoryQuery()).cycles.single { cycle ->
            cycle.events.any { it.actionKind == "REGISTER" && it.reasonCode == "RAID_WAITING_TO_START" }
        }
        val waiting = registrationCycle.events.single { it.actionKind == "REGISTER" && it.reasonCode == "RAID_WAITING_TO_START" }
        assertEquals(AutomationHistoryEventKind.WAITING, waiting.kind)
        assertEquals("RaidGoblin", waiting.targetKey)
        assertEquals(clock.now().plusSeconds(418), waiting.nextRunAt)
        assertTrue(runs().none { it["execution_identity"] == old.selection.executionIdentity })
        nextRun()
        assertEquals(1, registerRequests().size)
        assertRegistrationApplied()
        assertEquals("SUCCEEDED", runs().single { it["execution_identity"] == identity }["status"])
        assertEquals(waiting, journal.page(accountId, AutomationHistoryQuery()).cycles.single {
            it.id == registrationCycle.id
        }.events.single { it.actionKind == "REGISTER" && it.reasonCode == "RAID_WAITING_TO_START" })
        assertFalse(store.findSuppressedBaselines(accountId).values.any { old.selection.baselineFingerprint in it })
    }

    @Test
    fun `신청 응답 유실은 갱신 후 새 실행으로 신청하며 동일 상태 보류에 갇히지 않는다`() {
        setupRaid(loseFirstRegistration = true)
        runner.runOne(accountId) // REFRESH
        nextRun() // REGISTER 응답 유실
        assertEquals(1, registerRequests().size)
        nextRun() // 미확정 범위의 작업권을 양보한다.
        nextRun() // 실제 REFRESH로 결과 재판단
        assertTrue(store.findSuppressedBaselines(accountId).isEmpty())
        assertTrue(jdbc.queryForList("select result from automation_action_convergences where account_id = ?", accountId)
            .any { it["result"] == "RESULT_UNOBSERVED" }, runs().toString() + jdbc.queryForList("select result, reason_code from automation_action_convergences where account_id = ?", accountId).toString())
        clock.current = jdbc.queryForObject("select min(next_check_at) from automation_work_sessions where account_id = ?",
            java.time.OffsetDateTime::class.java, accountId)?.toInstant() ?: clock.now()
        nextRun() // 기존 작업 재확인 예약에서 새 REGISTER
        assertEquals(2, registerRequests().size)
        val registrationRuns = jdbc.queryForList(
            "select execution_identity from automation_action_attempts where account_id = ? and action_kind = 'RAID_REGISTER'", accountId)
        assertEquals(2, registrationRuns.map { it["execution_identity"] }.distinct().size)
        nextRun()
        assertEquals(2, registerRequests().size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `신청 응답 유실 뒤 참가 대기와 전역 쿨다운은 과거 신청 성공으로 귀속하지 않는다`(alreadyJoined: Boolean) {
        setupRaid(loseFirstRegistration = true, joinOnLost = alreadyJoined)
        runner.runOne(accountId)
        nextRun()
        val attemptId = jdbc.queryForObject(
            "select id from automation_action_attempts where account_id = ? and action_kind = 'RAID_REGISTER'",
            Long::class.java, accountId)!!
        if (!alreadyJoined) {
            raidPageTransform = { it.replace("현재 상태는 신청 가능", "현재 상태는 신청 대기 (신청 가능까지 6분 58초)") }
        }
        repeat(2) { nextRun() }
        assertEquals(ActionConvergenceResult.SUPERSEDED, store.get(attemptId)?.result)
        assertEquals(1, registerRequests().size)
        assertNotNull(jdbc.queryForObject("select next_check_at from automation_work_sessions where account_id = ?",
            java.time.OffsetDateTime::class.java, accountId))
    }

    @Test
    fun `갱신 POST만 불완전하면 완전한 GET이 있어도 신청 보류를 해제하지 않는다`() {
        setupRaid()
        runner.runOne(accountId)
        val old = holdRegistration()
        incompleteRefreshPost = true
        repeat(3) { nextRun() }
        assertEquals(0, registerRequests().size)
        assertTrue(old.selection.baselineFingerprint in store.findSuppressedBaselines(accountId)[old.selection.scope].orEmpty())
    }

    @Test
    fun `신청 결과 확인 중 로그아웃하면 갱신 POST도 보내지 않는다`() {
        setupRaid(loseFirstRegistration = true)
        runner.runOne(accountId)
        nextRun()
        assertEquals(1, registerRequests().size)
        assertTrue(store.findActiveScopes(accountId).isNotEmpty(), "로그아웃은 신청 결과가 미확정인 경계에서 발생해야 한다.")
        val postsBeforeLogout = requests.count { it.method == HofHttpMethod.POST }
        Mockito.`when`(authorization.isExecutionAllowed(accountId)).thenReturn(false)
        nextRun()
        assertEquals(postsBeforeLogout, requests.count { it.method == HofHttpMethod.POST })
        assertEquals(1, registerRequests().size)
        assertTrue(store.findActiveScopes(accountId).isNotEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["cooldown", "incomplete", "different-target"])
    fun `쿨다운 불완전 화면 다른 대상은 과거 신청 보류를 해제하지 않는다`(state: String) {
        setupRaid()
        val old = holdRegistration()
        raidPageTransform = { page -> when (state) {
            "cooldown" -> page.replace("현재 상태는 신청 가능", "현재 상태는 신청 대기 (신청 가능까지 6분 58초)")
            "incomplete" -> page.substringBefore("<div id=\"foot\"")
            else -> page.replace("RaidGoblin", "RaidOther")
        } }
        runner.runOne(accountId)
        assertEquals(0, registerRequests().size)
        assertTrue(old.selection.baselineFingerprint in store.findSuppressedBaselines(accountId)[old.selection.scope].orEmpty())
    }

    @Test
    fun `패턴 실패 뒤 새 판단의 파티를 사용하고 기존 작업권은 대기 상태로 양보한다`() {
        runner.runOne(accountId)
        assertEquals("WAITING_COOLDOWN", jdbc.queryForObject(
            "select status from automation_work_sessions where account_id = ?", String::class.java, accountId))
        selectedCharacters = listOf(characters.last())
        failedPattern = -1
        clock.current = nextRetryAt()
        runner.runOne(accountId)
        assertEquals("SUCCEEDED", runs().last()["status"])
        assertEquals(characters.last(), requests.last { it.url.contains("?char=") }.url.substringAfter("?char="))
        val submittedCharacters = battleRequests().single().formEntries.map { it.name.removePrefix("char_") }.filter { it in characters }
        assertEquals(listOf(characters.last()), submittedCharacters)
    }

    private fun setupRaid(loseFirstRegistration: Boolean = false, joinOnLost: Boolean = false) {
        Mockito.doCallRealMethod().`when`(decisions).select(accountId)
        TransactionTemplate(transactions).executeWithoutResult {
            val account = entityManager.find(HofAccountEntity::class.java, accountId)
            val entry = entityManager.find(AutomationEntryEntity::class.java, entryId)
            entry.type = AutomationType.RAID
            entry.singletonTypeMarker = AutomationType.RAID
            val preset = PartyPresetEntity(account = account, name = "복구 파티", createdAt = clock.now(), updatedAt = clock.now(), isPrimary = true)
            entityManager.persist(preset)
            val character = entityManager.createQuery("select c from CharacterEntity c where c.account.id = :id", CharacterEntity::class.java)
                .setParameter("id", accountId).resultList.first()
            val pattern = CharacterPatternSlotEntity(character = character, slotCode = "1", label = "기본", canLoad = true)
            entityManager.persist(pattern)
            entityManager.persist(PartyPresetMemberEntity(preset, 0, character, pattern))
            entityManager.persist(RaidAutomationTargetEntity(entry = entry, raidId = "RaidGoblin", displayName = "고블린 전투 마차",
                presetMode = PresetSelectionMode.PRIMARY, executionOrder = 0))
        }
        var joined = false
        var lost = false
        Mockito.doAnswer { invocation ->
            val request = invocation.arguments[1] as HofRequest
            requests += request
            if (request.formEntries.any { it.name == "register_goblin" }) {
                if (loseFirstRegistration && !lost) {
                    lost = true
                    joined = joinOnLost
                    throw IOException("registration response lost")
                }
                joined = true
            }
            val html = if (request.url.contains("raidpub") || request.method == HofHttpMethod.POST) {
                raidPageTransform(raidHtml(joined)).let { page ->
                    if (incompleteRefreshPost && request.formEntries.any { it.name == "refresh_nonce" }) {
                        page.substringBefore("<div id=\"foot\"")
                    } else page
                }
            } else "<div id='menu2'>Funds : $ 1 Time : 100/100</div>아무것도 없다"
            HofHttpResponse(200, request.url.takeIf { it.contains("raidpub") } ?: "https://hof.zerosic.com/index.php?menu=raidpub", html, emptyMap())
        }.`when`(gateway).execute(Mockito.eq(accountId), anyRequest(), Mockito.anyMap())
    }

    private fun raidHtml(joined: Boolean): String = requireNotNull(javaClass.getResource("/fixtures/town/raid/raidpub.html")).readText()
        .replace("Funds : $ 1,000", "Funds : $ 1,000 Time : 100/100")
        .replace("현재 상태는 신청 대기 (신청 가능까지 6분 58초)", "현재 상태는 신청 가능")
        .replace("현재 상태 : 모집 중", if (joined) "현재 상태 : 418초 후 출발" else "현재 상태 : 파티 모집 중 (신청 안됨)")
        .replace("[《테스트 길드》현재사용자]", if (joined) "[《테스트 길드》현재사용자]" else "[다른 신청자]")
        .replace("<input type=\"submit\" name=\"reward_nonce\"", "<input type=\"submit\" name=\"refresh_nonce\" value=\"상태 갱신\"><input type=\"submit\" name=\"reward_nonce\"")

    private fun holdRegistration(): ActionConvergenceRecord {
        val action = RaidTownAutomationAction(accountId, RaidAction.REGISTER, "RaidGoblin", targetRaidId = "RaidGoblin")
        val preview = StoredActionConvergenceSelectionFactory().preview(entryId, action)
        val old = store.createOrGet(accountId, SelectedAutomationAction(entryId, UUID.randomUUID().toString(),
            AutomationActionKind.RAID_REGISTER, preview.scope, "automation-action-convergence-v1", requireNotNull(preview.baselineFingerprint)), clock.now())
        old.result = ActionConvergenceResult.HELD
        old.reasonCode = "PENDING_BUDGET_EXHAUSTED"
        old.finishedAt = clock.now()
        store.save(old)
        return old
    }

    private fun registerRequests() = requests.filter { it.formEntries.any { field -> field.name == "register_goblin" } }
    private fun nextRetryAt() = jdbc.queryForObject("select next_attempt_at from typed_automation_runtime_states where account_id = ?",
        java.time.OffsetDateTime::class.java, accountId)?.toInstant() ?: clock.now().plusSeconds(1)
    private fun nextRun() {
        val probeAt = jdbc.queryForObject("select min(next_probe_at) from automation_action_convergences where account_id = ? and active_marker = 1",
            java.time.OffsetDateTime::class.java, accountId)?.toInstant() ?: clock.now()
        clock.current = maxOf(nextRetryAt(), probeAt)
        consumeNextWake()
    }

    private fun assertScheduledWake(reason: String, at: Instant) {
        val payloads = jdbc.queryForList(
            "select payload from automation_outbox where account_id = ? and topic = ? and available_at = ? and published_at is null",
            String::class.java, accountId, AutomationOutboxService.WAKEUP_TOPIC, java.sql.Timestamp.from(at))
        assertTrue(payloads.any { jacksonObjectMapper().readTree(it)["reason"].asString() == reason },
            "$reason wake가 $at 에 예약되어 있어야 한다.")
    }

    class RecoveryClock(var current: Instant = Instant.parse("2026-09-04T00:00:00Z")) : TimeProvider {
        override fun now(): Instant = current
    }

    /** Only broker delivery is replaced; production consumer, lease and runtime are real. */
    class ConsumerReplayTransport(private val consumer: AutomationWakeupConsumer) : AutomationOutboxTransport {
        override val supportedTopics = setOf(AutomationOutboxService.WAKEUP_TOPIC)
        val delivered = mutableListOf<String>()
        override fun publish(row: AutomationOutboxEntity) {
            var acknowledged = false
            consumer.consume(row.payload, Acknowledgment { acknowledged = true })
            check(acknowledged) { "Wake was not consumed: ${row.eventId}" }
            delivered += row.eventId
        }
    }

    @TestConfiguration
    class Config {
        @Bean @Primary fun recoveryClock() = RecoveryClock()
        @Bean @Primary
        fun durableWakeups(outbox: AutomationOutboxService): AutomationWakeupPort = KafkaAutomationWakeupAdapter(outbox)
        @Bean @Primary
        fun consumerReplayTransport(
            mapper: ObjectMapper,
            consumed: AutomationConsumedEventService,
            lease: AccountAutomationLeaseService,
            runner: UnifiedAutomationRunner,
            clock: TimeProvider,
        ) = ConsumerReplayTransport(AutomationWakeupConsumer(mapper, consumed, lease, runner, clock))
    }
}
