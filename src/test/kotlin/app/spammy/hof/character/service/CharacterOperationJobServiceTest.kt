package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.character.entity.CharacterOperationJobEntity
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.entity.CharacterOperationType
import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.character.repository.CharacterOperationJobCommandRepository
import app.spammy.hof.character.repository.CharacterOperationJobQueryRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.transfer.CharacterTransferService
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import org.springframework.core.task.TaskExecutor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.core.task.SyncTaskExecutor
import tools.jackson.module.kotlin.jacksonObjectMapper

class CharacterOperationJobServiceTest {
    private val now = Instant.parse("2026-08-17T00:00:00Z")
    private val account = HofAccountEntity(
        id = 1,
        loginId = "operation-job",
        encryptedPassword = "x",
        createdAt = now,
    )
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val characters = Mockito.mock(CharacterQueryRepository::class.java)
    private val commands = Mockito.mock(CharacterOperationJobCommandRepository::class.java)
    private val queries = Mockito.mock(CharacterOperationJobQueryRepository::class.java)
    private val deepSync = Mockito.mock(CharacterDeepSyncService::class.java)
    private val transfers = Mockito.mock(CharacterTransferService::class.java)
    private val sessionRecovery = HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))
    private val mapper = jacksonObjectMapper()
    private val automation = Mockito.mock(CharacterOperationAutomation::class.java)

    @Test
    fun `session recovery completes inside the same automation pause`() {
        val fixture = CharacterDeepSyncServiceTest.Fixture(expireFirstCapture = true)
        val job = CharacterOperationJobEntity(id = 17, account = account, operationType = CharacterOperationType.DEEP_SYNC,
            targetCharacterId = 7, status = CharacterOperationStatus.PENDING, recoveryStatus = CharacterRecoveryStatus.NOT_STARTED,
            startedAt = now, updatedAt = now)
        Mockito.`when`(queries.findIncomplete()).thenReturn(listOf(job))
        Mockito.`when`(queries.findById(job.id)).thenReturn(job)
        Mockito.`when`(queries.findByAccountIdAndId(account.id, job.id)).thenReturn(job)
        val service = CharacterOperationJobService(accounts, characters, commands, queries, fixture.service, transfers,
            fixture.sessionRecovery, mapper, TimeProvider { now }, SyncTaskExecutor(), automation)

        service.resumeIncompleteJobs()

        assertEquals(CharacterOperationStatus.COMPLETED, service.find(account.id, job.id).status)
        assertEquals(1, fixture.reauthenticationCalls)
        fixture.assertOriginalRestored()
    }

    @Test
    fun `recovery retry keeps the job identity and queues only one execution`() {
        val job = CharacterOperationJobEntity(id = 50, account = account, operationType = CharacterOperationType.DEEP_SYNC,
            targetCharacterId = 9, status = CharacterOperationStatus.FAILED, recoveryStatus = CharacterRecoveryStatus.RESTORING,
            startedAt = now, updatedAt = now, progressPayload = "[]", automationIntentRevision = 2)
        Mockito.`when`(accounts.findByIdForUpdate(account.id)).thenReturn(account)
        Mockito.`when`(queries.findByAccountIdAndId(account.id, job.id)).thenReturn(job)
        val tasks = mutableListOf<Runnable>()
        Mockito.`when`(queries.findRestoreAttempts(job.id)).thenReturn(3)
        val service = CharacterOperationJobService(accounts, characters, commands, queries, deepSync, transfers,
            sessionRecovery, mapper, TimeProvider { now }, TaskExecutor { tasks += it }, automation)

        val first = service.retryRecovery(account.id, job.id)
        val second = service.retryRecovery(account.id, job.id)

        assertEquals(job.id, first.id)
        assertEquals(CharacterOperationStatus.PENDING, first.status)
        assertEquals(first.id, second.id)
        assertEquals(1, tasks.size)
        assertEquals(6, job.restoreAttemptLimit)
        assertEquals(2, job.automationIntentRevision)
        Mockito.verifyNoInteractions(deepSync)
    }

    @ParameterizedTest
    @CsvSource("RESTORED", "ACCEPTED")
    fun `resolved jobs are returned without queuing a second restoration`(status: CharacterRecoveryStatus) {
        val job = CharacterOperationJobEntity(id = 51, account = account, operationType = CharacterOperationType.DEEP_SYNC,
            targetCharacterId = 9, status = CharacterOperationStatus.FAILED, recoveryStatus = status,
            startedAt = now, updatedAt = now, automationReleased = true)
        Mockito.`when`(accounts.findByIdForUpdate(account.id)).thenReturn(account)
        Mockito.`when`(queries.findByAccountIdAndId(account.id, job.id)).thenReturn(job)
        val tasks = mutableListOf<Runnable>()
        val service = CharacterOperationJobService(accounts, characters, commands, queries, deepSync, transfers,
            sessionRecovery, mapper, TimeProvider { now }, TaskExecutor { tasks += it }, automation)

        assertEquals(status, service.retryRecovery(account.id, job.id).recoveryStatus)
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `missing original and other account cannot retry a recovery`() {
        val job = CharacterOperationJobEntity(id = 52, account = account, operationType = CharacterOperationType.DEEP_SYNC,
            targetCharacterId = 9, status = CharacterOperationStatus.FAILED, recoveryStatus = CharacterRecoveryStatus.UNAVAILABLE,
            startedAt = now, updatedAt = now)
        Mockito.`when`(accounts.findByIdForUpdate(account.id)).thenReturn(account)
        Mockito.`when`(queries.findByAccountIdAndId(account.id, job.id)).thenReturn(job)
        val tasks = mutableListOf<Runnable>()
        val service = CharacterOperationJobService(accounts, characters, commands, queries, deepSync, transfers,
            sessionRecovery, mapper, TimeProvider { now }, TaskExecutor { tasks += it }, automation)

        assertEquals(ErrorCode.CHARACTER_RECOVERY_REQUIRED,
            assertFailsWith<ApiException> { service.retryRecovery(account.id, job.id) }.errorCode)
        assertFailsWith<ApiException> { service.retryRecovery(account.id + 1, job.id) }
        assertTrue(tasks.isEmpty())
    }

    @ParameterizedTest
    @CsvSource("DEEP_SYNC, PENDING", "DEEP_SYNC, RUNNING", "RESTORE, PENDING", "RESTORE, RUNNING")
    fun `legacy incomplete sync without a recovery original requires review instead of replay`(
        type: CharacterOperationType,
        status: CharacterOperationStatus,
    ) {
        val job = CharacterOperationJobEntity(
            id = 17,
            account = account,
            operationType = type,
            status = status,
            targetCharacterId = 9,
            startedAt = now,
            updatedAt = now,
        )
        Mockito.`when`(queries.findIncomplete()).thenReturn(listOf(job))
        Mockito.`when`(queries.findById(job.id)).thenReturn(job)
        Mockito.`when`(queries.findByAccountIdAndId(account.id, job.id)).thenReturn(job)
        Mockito.`when`(commands.save(anyJob())).thenAnswer { it.arguments[0] }
        val service = CharacterOperationJobService(
            accounts, characters, commands, queries, deepSync, transfers, sessionRecovery,
            mapper, TimeProvider { now }, SyncTaskExecutor(),
            automation,
        )

        service.resumeIncompleteJobs()

        val result = service.find(account.id, job.id)
        assertEquals(CharacterOperationStatus.FAILED, result.status)
        assertEquals(CharacterRecoveryStatus.UNAVAILABLE, result.recoveryStatus)
        assertTrue(result.message.orEmpty().contains("원본"))
        Mockito.verifyNoInteractions(deepSync)
    }

    @Test
    fun `기존 형식의 실행 중 가져오기는 원래 패턴을 추정해 재전송하지 않는다`() {
        val request = app.spammy.hof.character.dto.CharacterTransferExecuteRequest(8L, 9L,
            app.spammy.hof.character.transfer.CharacterTransferRequest(
                savedPatternMappings = listOf(app.spammy.hof.character.transfer.CharacterSavedPatternMapping("0", "0"))))
        val job = CharacterOperationJobEntity(id = 17, account = account, operationType = CharacterOperationType.TRANSFER,
            status = CharacterOperationStatus.RUNNING, sourceCharacterId = 8L, targetCharacterId = 9L,
            requestPayload = mapper.writeValueAsString(request), startedAt = now, updatedAt = now)
        Mockito.`when`(queries.findIncomplete()).thenReturn(listOf(job))
        Mockito.`when`(queries.findById(job.id)).thenReturn(job)
        Mockito.`when`(queries.findByAccountIdAndId(account.id, job.id)).thenReturn(job)
        val tasks = mutableListOf<Runnable>()
        val service = CharacterOperationJobService(accounts, characters, commands, queries, deepSync, transfers,
            sessionRecovery, mapper, TimeProvider { now }, TaskExecutor(tasks::add), automation)

        service.resumeIncompleteJobs()

        assertTrue(tasks.isEmpty())
        assertEquals(CharacterOperationStatus.FAILED, service.find(account.id, job.id).status)
        assertTrue(job.message.orEmpty().contains("원래"))
        Mockito.verifyNoInteractions(transfers)
    }

    @Test
    fun `application restart resumes a deep sync before its first mutation and persists its progress`() {
        val job = CharacterOperationJobEntity(
            id = 7,
            recoveryStatus = CharacterRecoveryStatus.NOT_STARTED,
            account = account,
            operationType = CharacterOperationType.DEEP_SYNC,
            status = CharacterOperationStatus.RUNNING,
            targetCharacterId = 9,
            startedAt = now,
            updatedAt = now,
        )
        Mockito.`when`(queries.findIncomplete()).thenReturn(listOf(job))
        Mockito.`when`(queries.findById(7)).thenReturn(job)
        Mockito.`when`(commands.save(anyJob())).thenAnswer { it.arguments[0] }
        Mockito.`when`(
            deepSync.synchronize(
                ArgumentMatchers.eq(1L),
                ArgumentMatchers.eq(9L),
                ArgumentMatchers.eq(7L),
                anyProgressListener(),
            ),
        ).thenAnswer { invocation ->
            val report = invocation.getArgument<(CharacterDeepSyncProgress) -> Unit>(3)
            val progress = listOf(
                CharacterDeepSyncProgress(CharacterDeepSyncPhase.CURRENT, 1, 2),
                CharacterDeepSyncProgress(CharacterDeepSyncPhase.COMPLETED, 2, 2),
            )
            progress.forEach(report)
            CharacterDeepSyncResponse(9, progress)
        }
        val service = CharacterOperationJobService(
            accounts,
            characters,
            commands,
            queries,
            deepSync,
            transfers,
            sessionRecovery,
            mapper,
            TimeProvider { now },
            SyncTaskExecutor(),
            automation,
        )

        service.resumeIncompleteJobs()

        assertEquals(CharacterOperationStatus.COMPLETED, job.status)
        assertEquals(now, job.finishedAt)
        assertTrue(job.progressPayload.contains("COMPLETED"))
        assertTrue(job.resultPayload?.contains("\"characterId\":9") == true)
        Mockito.verify(deepSync).synchronize(
            ArgumentMatchers.eq(1L),
            ArgumentMatchers.eq(9L),
            ArgumentMatchers.eq(7L),
            anyProgressListener(),
        )
    }

    @Test
    fun `recovery retains collected slot results from the previous process`() {
        val collected = CharacterDeepSyncProgress(CharacterDeepSyncPhase.SAVED_PATTERN, 2, 5, patternSlotCode = "0")
        val job = CharacterOperationJobEntity(
            id = 18, account = account, operationType = CharacterOperationType.DEEP_SYNC,
            status = CharacterOperationStatus.RUNNING, targetCharacterId = 9,
            startedAt = now, updatedAt = now, recoveryStatus = CharacterRecoveryStatus.RESTORING,
            progressPayload = mapper.writeValueAsString(listOf(collected)),
        )
        Mockito.`when`(queries.findIncomplete()).thenReturn(listOf(job))
        Mockito.`when`(queries.findById(job.id)).thenReturn(job)
        Mockito.`when`(queries.findByAccountIdAndId(account.id, job.id)).thenReturn(job)
        Mockito.`when`(commands.save(anyJob())).thenAnswer { it.arguments[0] }
        Mockito.`when`(deepSync.synchronize(ArgumentMatchers.eq(1L), ArgumentMatchers.eq(9L), ArgumentMatchers.eq(18L), anyProgressListener()))
            .thenAnswer { invocation ->
                val report = invocation.getArgument<(CharacterDeepSyncProgress) -> Unit>(3)
                val progress = listOf(CharacterDeepSyncProgress(CharacterDeepSyncPhase.RESTORE, 5, 5),
                    CharacterDeepSyncProgress(CharacterDeepSyncPhase.COMPLETED, 5, 5))
                progress.forEach(report)
                CharacterDeepSyncResponse(9L, progress)
            }
        val service = CharacterOperationJobService(accounts, characters, commands, queries, deepSync, transfers,
            sessionRecovery, mapper, TimeProvider { now }, SyncTaskExecutor(), automation)

        service.resumeIncompleteJobs()

        val result = service.find(account.id, job.id)
        assertTrue(requireNotNull(result.deepSync).progress.contains(collected))
    }

    @Test
    fun `restart after restoration and automation release does not recapture a character already used by automation`() {
        val progress = listOf(CharacterDeepSyncProgress(CharacterDeepSyncPhase.COMPLETED, 5, 5))
        val job = CharacterOperationJobEntity(
            id = 19, account = account, operationType = CharacterOperationType.DEEP_SYNC,
            status = CharacterOperationStatus.RUNNING, targetCharacterId = 9,
            startedAt = now, updatedAt = now, recoveryStatus = CharacterRecoveryStatus.RESTORED,
            automationIntentRevision = 1, automationReleased = true,
            progressPayload = mapper.writeValueAsString(progress),
        )
        Mockito.`when`(queries.findIncomplete()).thenReturn(listOf(job))
        Mockito.`when`(queries.findById(job.id)).thenReturn(job)
        Mockito.`when`(queries.findByAccountIdAndId(account.id, job.id)).thenReturn(job)
        Mockito.`when`(commands.save(anyJob())).thenAnswer { it.arguments[0] }
        val service = CharacterOperationJobService(accounts, characters, commands, queries, deepSync, transfers,
            sessionRecovery, mapper, TimeProvider { now }, SyncTaskExecutor(), automation)

        service.resumeIncompleteJobs()

        assertEquals(CharacterOperationStatus.COMPLETED, service.find(account.id, job.id).status)
        Mockito.verifyNoInteractions(deepSync)
    }

    @Test
    fun `application restart resumes a restore before its first mutation and persists observed sections`() {
        val job = CharacterOperationJobEntity(
            id = 8,
            recoveryStatus = CharacterRecoveryStatus.NOT_STARTED,
            account = account,
            operationType = CharacterOperationType.RESTORE,
            status = CharacterOperationStatus.RUNNING,
            targetCharacterId = 10,
            startedAt = now,
            updatedAt = now,
        )
        Mockito.`when`(queries.findIncomplete()).thenReturn(listOf(job))
        Mockito.`when`(queries.findById(8)).thenReturn(job)
        Mockito.`when`(commands.save(anyJob())).thenAnswer { it.arguments[0] }
        Mockito.`when`(
            deepSync.restoreAndSynchronize(
                ArgumentMatchers.eq(1L),
                ArgumentMatchers.eq(10L),
                ArgumentMatchers.eq(8L),
                anyProgressListener(),
            ),
        ).thenAnswer { invocation ->
            val report = invocation.getArgument<(CharacterDeepSyncProgress) -> Unit>(3)
            val progress = listOf(
                CharacterDeepSyncProgress(CharacterDeepSyncPhase.CURRENT, 1, 4),
                CharacterDeepSyncProgress(CharacterDeepSyncPhase.SAVED_PATTERN, 2, 4),
                CharacterDeepSyncProgress(CharacterDeepSyncPhase.EQUIPMENT_PRESET, 3, 4),
                CharacterDeepSyncProgress(CharacterDeepSyncPhase.COMPLETED, 4, 4),
            )
            progress.forEach(report)
            CharacterDeepSyncResponse(10, progress)
        }
        val service = CharacterOperationJobService(
            accounts,
            characters,
            commands,
            queries,
            deepSync,
            transfers,
            sessionRecovery,
            mapper,
            TimeProvider { now },
            SyncTaskExecutor(),
            automation,
        )

        service.resumeIncompleteJobs()

        assertEquals(CharacterOperationStatus.COMPLETED, job.status)
        assertTrue(job.progressPayload.contains("SAVED_PATTERN"))
        assertTrue(job.progressPayload.contains("EQUIPMENT_PRESET"))
        assertTrue(job.resultPayload?.contains("\"characterId\":10") == true)
        Mockito.verify(deepSync).restoreAndSynchronize(
            ArgumentMatchers.eq(1L),
            ArgumentMatchers.eq(10L),
            ArgumentMatchers.eq(8L),
            anyProgressListener(),
        )
    }

    private fun anyJob(): CharacterOperationJobEntity {
        ArgumentMatchers.any(CharacterOperationJobEntity::class.java)
        return CharacterOperationJobEntity(
            account = account,
            operationType = CharacterOperationType.DEEP_SYNC,
            targetCharacterId = 0,
            startedAt = now,
            updatedAt = now,
        )
    }

    private fun anyProgressListener(): (CharacterDeepSyncProgress) -> Unit {
        ArgumentMatchers.any<(CharacterDeepSyncProgress) -> Unit>()
        return {}
    }
}
