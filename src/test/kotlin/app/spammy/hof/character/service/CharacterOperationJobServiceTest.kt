package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.character.entity.CharacterOperationJobEntity
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.entity.CharacterOperationType
import app.spammy.hof.character.repository.CharacterOperationJobCommandRepository
import app.spammy.hof.character.repository.CharacterOperationJobQueryRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.transfer.CharacterTransferService
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
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

    @Test
    fun `application restart resumes a running deep sync job and persists its progress`() {
        val job = CharacterOperationJobEntity(
            id = 7,
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
                anyProgressListener(),
            ),
        ).thenAnswer { invocation ->
            val report = invocation.getArgument<(CharacterDeepSyncProgress) -> Unit>(2)
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
        )

        service.resumeIncompleteJobs()

        assertEquals(CharacterOperationStatus.COMPLETED, job.status)
        assertEquals(now, job.finishedAt)
        assertTrue(job.progressPayload.contains("COMPLETED"))
        assertTrue(job.resultPayload?.contains("\"characterId\":9") == true)
        Mockito.verify(deepSync).synchronize(
            ArgumentMatchers.eq(1L),
            ArgumentMatchers.eq(9L),
            anyProgressListener(),
        )
    }

    @Test
    fun `application restart resumes a running restore job and verifies every restored section`() {
        val job = CharacterOperationJobEntity(
            id = 8,
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
                anyProgressListener(),
            ),
        ).thenAnswer { invocation ->
            val report = invocation.getArgument<(CharacterDeepSyncProgress) -> Unit>(2)
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
        )

        service.resumeIncompleteJobs()

        assertEquals(CharacterOperationStatus.COMPLETED, job.status)
        assertTrue(job.progressPayload.contains("SAVED_PATTERN"))
        assertTrue(job.progressPayload.contains("EQUIPMENT_PRESET"))
        assertTrue(job.resultPayload?.contains("\"characterId\":10") == true)
        Mockito.verify(deepSync).restoreAndSynchronize(
            ArgumentMatchers.eq(1L),
            ArgumentMatchers.eq(10L),
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
