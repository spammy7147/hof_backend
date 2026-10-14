package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.character.dto.CharacterPatternSlotResponse
import app.spammy.hof.character.dto.CharacterResponse
import app.spammy.hof.character.dto.CharacterSyncEventResponse
import app.spammy.hof.character.entity.CharacterSyncFailureEntity
import app.spammy.hof.character.entity.CharacterSyncJobEntity
import app.spammy.hof.character.entity.CharacterSyncJobStatus
import app.spammy.hof.character.entity.CharacterSyncMode
import app.spammy.hof.character.entity.CharacterOperationType
import app.spammy.hof.character.entity.CharacterOperationStatus
import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.character.dto.CharacterOperationJobResponse
import kotlin.test.assertFailsWith
import app.spammy.hof.character.repository.CharacterSyncFailureCommandRepository
import app.spammy.hof.character.repository.CharacterSyncJobQueryRepository
import app.spammy.hof.character.repository.CharacterSyncJobRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.client.testAccountHofGateway
import app.spammy.hof.external.model.HofCharacter
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.CharacterRosterParser
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

class CharacterSyncJobServiceTest {
    private val now = Instant.parse("2026-07-08T00:00:00Z")
    private val account = HofAccountEntity(
        id = 1L,
        loginId = "abcd12",
        encryptedPassword = "qwer12",
        createdAt = now,
    )
    private val accountQueryRepository = Mockito.mock(AccountQueryRepository::class.java)
    private val cookieQueryRepository = Mockito.mock(CookieQueryRepository::class.java)
    private val syncJobRepository = Mockito.mock(CharacterSyncJobRepository::class.java)
    private val syncFailureRepository = Mockito.mock(CharacterSyncFailureCommandRepository::class.java)
    private val syncJobQueryRepository = Mockito.mock(CharacterSyncJobQueryRepository::class.java)
    private val characterService = Mockito.mock(CharacterService::class.java)
    private val snapshotSynchronizer = Mockito.mock(CharacterSnapshotSynchronizer::class.java)
    private val eventService = Mockito.mock(CharacterSyncEventService::class.java)
    private val gateway = FakeHofGateway()
    private val savedJobs = mutableMapOf<Long, CharacterSyncJobEntity>()
    private val savedFailures = mutableListOf<CharacterSyncFailureEntity>()
    private val savedCharacters = linkedMapOf<String, CharacterResponse>()
    private val publishedEvents = mutableListOf<CharacterSyncEventResponse>()
    private var requestStopAfterFirstCharacter = false
    private val batchDeepSync = Mockito.mock(CharacterBatchDeepSync::class.java)
    private val service = createService(SyncTaskExecutor())

    private fun createService(taskExecutor: TaskExecutor) = CharacterSyncJobService(
        accountQueryRepository = accountQueryRepository,
        cookieQueryRepository = cookieQueryRepository,
        syncJobRepository = syncJobRepository,
        syncFailureRepository = syncFailureRepository,
        syncJobQueryRepository = syncJobQueryRepository,
        characterService = characterService,
        requestFactory = HofRequestFactory(),
        gateway = testAccountHofGateway(gateway, TimeProvider { now }),
        rosterParser = CharacterRosterParser(),
        snapshotSynchronizer = snapshotSynchronizer,
        eventService = eventService,
        taskExecutor = taskExecutor,
        timeProvider = TimeProvider { now },
        mutationFence = app.spammy.hof.town.common.service.AccountHofMutationFence(),
        batchDeepSync = batchDeepSync,
        observedIdentities = org.mockito.Mockito.mock(app.spammy.hof.character.identity.CharacterObservedIdentityService::class.java),
    )

    @Test
    fun startSyncJobReusesTheActiveAccountJob() {
        arrangeRepositories()
        val pendingService = createService(TaskExecutor { })

        val first = pendingService.startSyncJob(1L)
        val second = pendingService.startSyncJob(1L)

        assertEquals(first.jobId, second.jobId)
        Mockito.verify(syncJobRepository, Mockito.times(1)).save(anySyncJob())
    }

    @Test
    fun startSyncJobStartsParsingWithoutSseAndPublishesCharactersOneByOne() {
        arrangeRepositories()

        val started = service.startSyncJob(1L)
        val snapshot = service.findSyncJob(accountId = 1L, jobId = started.jobId)

        assertEquals("completed", snapshot.status)
        assertEquals(2, snapshot.rosterCount)
        assertEquals(2, snapshot.syncedCount)
        assertEquals(emptyList(), snapshot.failedCharacterIds)
        assertEquals(listOf("111", "222"), snapshot.characters.map { it.hofCharacterId })
        assertEquals(
            listOf(
                "https://hof.zerosic.com/index.php",
            ),
            gateway.requests.map { it.url },
        )
        assertEquals(
            listOf("started", "rosterParsed", "characterSynced", "characterSynced", "completed"),
            publishedEvents.map { it.eventType },
        )
        assertEquals(listOf(null, null, "111", "222", null), publishedEvents.map { it.character?.hofCharacterId })
        assertEquals(
            listOf(null, null, "https://hof.zerosic.com/image/char/sknight02.gif", "https://hof.zerosic.com/image/char/cavalry.gif", null),
            publishedEvents.map { it.character?.imageUrl },
        )
        assertEquals(List(5) { emptyList() }, publishedEvents.map { it.failedCharacterIds })
        Mockito.verify(snapshotSynchronizer, Mockito.times(2)).synchronize(
            anyAccount(),
            Mockito.anyMap(),
            anyHofCharacter(),
            Mockito.eq(true),
            Mockito.anySet(),
        )
        Mockito.verify(characterService, Mockito.never())
            .deleteCharactersAbsentFromRoster(Mockito.anyLong(), Mockito.anySet())
        Mockito.verify(eventService).complete(started.jobId)
    }

    @Test
    fun detailFailureAppendsOneOrderedRowAndKeepsIncrementalSseBehavior() {
        arrangeRepositories()
        gateway.failedCharacterId = "222"

        val started = service.startSyncJob(1L)
        val snapshot = service.findSyncJob(accountId = 1L, jobId = started.jobId)

        assertEquals("completed", snapshot.status)
        assertEquals(2, snapshot.rosterCount)
        assertEquals(1, snapshot.syncedCount)
        assertEquals(listOf("222"), snapshot.failedCharacterIds)
        assertEquals(listOf("111", "222"), snapshot.characters.map { it.hofCharacterId })
        assertNull(snapshot.message)
        assertEquals(1, savedFailures.size)
        assertEquals(0, savedFailures.single().failureOrder)
        assertEquals("222", savedFailures.single().hofCharacterId)
        assertEquals(
            listOf("started", "rosterParsed", "characterSynced", "characterFailed", "completed"),
            publishedEvents.map { it.eventType },
        )
        assertEquals(
            listOf(emptyList(), emptyList(), emptyList(), listOf("222"), listOf("222")),
            publishedEvents.map { it.failedCharacterIds },
        )
        assertEquals("222", publishedEvents.first { it.eventType == "characterFailed" }.character?.hofCharacterId)
    }

    @Test
    fun fatalJobFailurePreservesMessageAndPublishesFailedEvent() {
        arrangeRepositories()
        gateway.failHome = true

        val started = service.startSyncJob(1L)
        val snapshot = service.findSyncJob(accountId = 1L, jobId = started.jobId)

        assertEquals("failed", snapshot.status)
        assertEquals("home failed", snapshot.message)
        assertEquals(emptyList(), snapshot.failedCharacterIds)
        assertEquals(listOf("started", "failed"), publishedEvents.map { it.eventType })
        assertEquals("home failed", publishedEvents.last().message)
        Mockito.verify(eventService).complete(started.jobId)
    }

    @Test
    fun stopRequestFinishesCurrentCharacterAndCheckpointsBeforeStopping() {
        arrangeRepositories()
        requestStopAfterFirstCharacter = true

        val started = service.startSyncJob(1L)
        val snapshot = service.findSyncJob(1L, started.jobId)

        assertEquals("stopped", snapshot.status)
        assertEquals(listOf("111"), snapshot.characters.map { it.hofCharacterId })
        assertEquals(0, snapshot.lastCompletedRosterIndex)
        assertNull(snapshot.currentHofCharacterId)
        assertEquals(listOf("started", "rosterParsed", "characterSynced", "stopped"), publishedEvents.map { it.eventType })
    }

    @Test
    fun resumedJobSkipsCompletedCharactersAndRestartsAtNextCheckpoint() {
        arrangeRepositories()
        savedJobs[12L] = CharacterSyncJobEntity(
            id = 12L,
            account = account,
            status = CharacterSyncJobStatus.PENDING,
            syncedCount = 1,
            startedAt = now,
            lastCompletedRosterIndex = 0,
            currentHofCharacterId = "222",
        )
        savedCharacters["111"] = CharacterResponse(111L, "111", "소셜", "", null, 0, null, revision = now)

        service.startSyncJob(1L)
        val snapshot = service.findSyncJob(1L, 12L)

        assertEquals("completed", snapshot.status)
        assertEquals(2, snapshot.syncedCount)
        assertEquals(1, snapshot.lastCompletedRosterIndex)
        assertEquals(listOf("111", "222"), snapshot.characters.map { it.hofCharacterId })
        assertEquals(listOf("222"), publishedEvents.mapNotNull { it.character?.hofCharacterId })
    }

    @Test
    fun `전체 설정은 각 캐릭터 복원 후 다음 캐릭터를 수집한다`() {
        arrangeDeepSync()
        val started = service.startSyncJob(1, CharacterSyncMode.DEEP)
        val result = service.findSyncJob(1, started.jobId)
        assertEquals("completed", result.status)
        assertEquals(CharacterSyncMode.DEEP, result.syncMode)
        assertEquals(2, result.syncedCount)
        assertEquals("[111,222]", savedJobs[12L]?.deepSyncTargets)
        val order = Mockito.inOrder(batchDeepSync)
        order.verify(batchDeepSync).prepare(anySyncJob(), Mockito.eq(111L))
        order.verify(batchDeepSync).execute(1, 111)
        order.verify(batchDeepSync).prepare(anySyncJob(), Mockito.eq(222L))
        order.verify(batchDeepSync).execute(1, 222)
        Mockito.verifyNoInteractions(snapshotSynchronizer)
    }

    @Test
    fun `수집 또는 복원 실패 시 다음 캐릭터를 건드리지 않는다`() {
        arrangeDeepSync()
        Mockito.`when`(batchDeepSync.execute(1, 111)).thenReturn(deepResult(111).copy(
            status = CharacterOperationStatus.FAILED, recoveryStatus = CharacterRecoveryStatus.REQUIRED,
            message = "원본 복구 필요"))
        val started = service.startSyncJob(1, CharacterSyncMode.DEEP)
        val result = service.findSyncJob(1, started.jobId)
        assertEquals("failed", result.status)
        assertEquals(0, result.syncedCount)
        assertEquals(listOf("111"), result.failedCharacterIds)
        assertEquals("원본 복구 필요", result.message)
        Mockito.verify(batchDeepSync, Mockito.never()).execute(1, 222)
    }

    @Test
    fun `중지 후 순서와 게임 ID가 바뀌어도 저장한 내부 ID로 나머지만 수집한다`() {
        arrangeDeepSync()
        Mockito.`when`(batchDeepSync.execute(1, 111)).thenAnswer {
            service.stopSyncJob(1, 12)
            deepResult(111)
        }
        service.startSyncJob(1, CharacterSyncMode.DEEP)
        assertEquals("stopped", service.findSyncJob(1, 12).status)
        assertEquals(1, service.findSyncJob(1, 12).syncedCount)
        savedCharacters["222"] = savedCharacters.getValue("222").copy(hofCharacterId = "333", rosterOrder = 0)
        savedCharacters["111"] = savedCharacters.getValue("111").copy(rosterOrder = 1)
        service.resumeSyncJob(1, 12)
        assertEquals("completed", service.findSyncJob(1, 12).status)
        assertEquals(2, service.findSyncJob(1, 12).syncedCount)
        Mockito.verify(batchDeepSync, Mockito.times(1)).execute(1, 111)
        Mockito.verify(batchDeepSync, Mockito.times(1)).execute(1, 222)
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun `상세 동기화 실행 중 깊은 동기화를 요청하면 다른 작업으로 오인하지 않는다`() {
        arrangeRepositories()
        val queued = createService(TaskExecutor { })
        queued.startSyncJob(1)
        assertFailsWith<app.spammy.hof.common.error.ApiException> { queued.startSyncJob(1, CharacterSyncMode.DEEP) }
    }

    @Test
    fun `재시작은 전체 수집을 중지하고 복구 완료된 자식은 중복 수집하지 않는다`() {
        arrangeDeepSync()
        savedJobs[12] = CharacterSyncJobEntity(id = 12, account = account, status = CharacterSyncJobStatus.RUNNING,
            startedAt = now, syncMode = CharacterSyncMode.DEEP, deepSyncTargets = "[111,222]", rosterCount = 2,
            currentOperationJobId = 111, currentHofCharacterId = "111")
        Mockito.`when`(syncJobQueryRepository.findActiveDeepSyncs()).thenReturn(listOf(savedJobs.getValue(12)))
        Mockito.`when`(batchDeepSync.current(1, 111)).thenReturn(deepResult(111))
        service.markInterruptedDeepSyncs()
        assertEquals("stopped", service.findSyncJob(1, 12).status)
        service.resumeSyncJob(1, 12)
        assertEquals("completed", service.findSyncJob(1, 12).status)
        assertEquals(2, service.findSyncJob(1, 12).syncedCount)
    }

    private fun deepResult(id: Long) = CharacterOperationJobResponse(id = id,
        operationType = CharacterOperationType.DEEP_SYNC, status = CharacterOperationStatus.COMPLETED,
        sourceCharacterId = null, targetCharacterId = id, updatedAt = now,
        recoveryStatus = CharacterRecoveryStatus.RESTORED)

    private fun arrangeDeepSync() {
        arrangeRepositories()
        for (id in listOf(111L, 222L)) {
            savedCharacters[id.toString()] = CharacterResponse(id, id.toString(), "캐릭터 $id", "전사", 10, 1, null, revision = now)
            Mockito.`when`(batchDeepSync.prepare(anySyncJob(), Mockito.eq(id))).thenAnswer {
                (it.arguments[0] as CharacterSyncJobEntity).currentOperationJobId = id
                id
            }
            Mockito.`when`(batchDeepSync.execute(1, id)).thenReturn(deepResult(id))
        }
        Mockito.doAnswer { syncJobRepository.save(it.arguments[0] as CharacterSyncJobEntity); null }
            .`when`(batchDeepSync).saveProgress(anySyncJob())
    }

    private fun arrangeRepositories() {
        Mockito.`when`(eventService.connect(Mockito.eq(12L), anySyncEvent()))
            .thenReturn(SseEmitter())
        Mockito.doAnswer { invocation ->
            publishedEvents += invocation.arguments[0] as CharacterSyncEventResponse
            null
        }.`when`(eventService).publish(anySyncEvent())
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(accountQueryRepository.findByIdForUpdate(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findValueMapByAccountId(1L))
            .thenReturn(mapOf("PHPSESSID" to "abc"))
        Mockito.`when`(syncJobRepository.save(anySyncJob()))
            .thenAnswer { invocation ->
                val job = invocation.arguments[0] as CharacterSyncJobEntity
                val savedJob = if (job.id == 0L) {
                    CharacterSyncJobEntity(
                        id = 12L,
                        account = job.account,
                        status = job.status,
                        rosterCount = job.rosterCount,
                        syncedCount = job.syncedCount,
                        message = job.message,
                        startedAt = job.startedAt,
                        finishedAt = job.finishedAt,
                        stopRequested = job.stopRequested,
                        lastCompletedRosterIndex = job.lastCompletedRosterIndex,
                        currentHofCharacterId = job.currentHofCharacterId,
                        syncMode = job.syncMode,
                    )
                } else {
                    job
                }
                savedJobs[savedJob.id] = savedJob
                savedJob
            }
        Mockito.`when`(syncJobQueryRepository.findById(12L)).thenAnswer { savedJobs[12L] }
        Mockito.`when`(syncJobQueryRepository.findNewestActiveByAccountId(1L)).thenAnswer {
            savedJobs.values.lastOrNull { it.status == CharacterSyncJobStatus.PENDING || it.status == CharacterSyncJobStatus.RUNNING }
        }
        Mockito.`when`(syncJobQueryRepository.findByAccountIdAndId(1L, 12L)).thenAnswer { savedJobs[12L] }
        Mockito.`when`(syncJobQueryRepository.findFailuresByJobId(12L)).thenAnswer {
            savedFailures.sortedWith(compareBy(CharacterSyncFailureEntity::failureOrder, CharacterSyncFailureEntity::id))
        }
        Mockito.`when`(
            syncJobQueryRepository.findFailureByJobIdAndHofCharacterId(Mockito.eq(12L), Mockito.anyString()),
        ).thenAnswer { invocation ->
            val characterId = invocation.arguments[1] as String
            savedFailures.find { it.hofCharacterId == characterId }
        }
        Mockito.`when`(syncFailureRepository.save(anySyncFailure()))
            .thenAnswer { invocation ->
                val failure = invocation.arguments[0] as CharacterSyncFailureEntity
                val saved = CharacterSyncFailureEntity(
                    id = 100L + savedFailures.size,
                    syncJob = failure.syncJob,
                    failureOrder = failure.failureOrder,
                    hofCharacterId = failure.hofCharacterId,
                )
                if (savedFailures.none { it.hofCharacterId == saved.hofCharacterId }) {
                    savedFailures += saved
                }
                saved
            }
        Mockito.`when`(
            characterService.upsertCharacterSnapshot(
                anyAccount(),
                anyHofCharacter(),
                anyHofCharacter(),
            ),
        ).thenAnswer { invocation ->
            val roster = invocation.arguments[1] as HofCharacter
            val detail = invocation.arguments[2] as HofCharacter
            val response = CharacterResponse(
                id = roster.id.toLong(),
                hofCharacterId = roster.id,
                name = detail.name.ifBlank { roster.name.ifBlank { "(이름없음)" } },
                job = detail.job,
                level = detail.level,
                patternSlotCount = detail.patternSlots.size,
                imageUrl = detail.imageUrl.ifBlank { null },
                revision = now,
                patternSlots = detail.patternSlots.map { slot ->
                    CharacterPatternSlotResponse(slot = slot.slot, label = slot.label, canLoad = slot.canLoad)
                },
            )
            savedCharacters[response.hofCharacterId] = response
            response
        }
        Mockito.`when`(characterService.findAll(1L)).thenAnswer {
            savedCharacters.values.sortedWith(compareBy(CharacterResponse::name, CharacterResponse::id))
        }
        Mockito.`when`(
            snapshotSynchronizer.synchronize(
                anyAccount(),
                Mockito.anyMap(),
                anyHofCharacter(),
                Mockito.anyBoolean(),
                Mockito.anySet(),
            ),
        ).thenAnswer { invocation ->
            val roster = invocation.arguments[2] as HofCharacter
            val response = CharacterResponse(
                id = roster.id.toLong(),
                hofCharacterId = roster.id,
                name = roster.name,
                job = "",
                level = null,
                patternSlotCount = 0,
                imageUrl = when (roster.id) {
                    "111" -> "https://hof.zerosic.com/image/char/sknight02.gif"
                    else -> "https://hof.zerosic.com/image/char/cavalry.gif"
                },
                revision = now,
                patternSlots = emptyList(),
            )
            savedCharacters[response.hofCharacterId] = response
            if (gateway.failedCharacterId == roster.id) error("detail failed")
            if (requestStopAfterFirstCharacter && roster.id == "111") {
                savedJobs[12L]?.stopRequested = true
            }
            response
        }
    }

    private fun anySyncEvent(): CharacterSyncEventResponse =
        Mockito.any(CharacterSyncEventResponse::class.java)
            ?: CharacterSyncEventResponse(
                eventId = 0L,
                eventType = "",
                jobId = 0L,
                accountId = 0L,
                status = "",
                rosterCount = 0,
                syncedCount = 0,
                failedCharacterIds = emptyList(),
                character = null,
                message = null,
                emittedAt = now,
            )

    private fun anySyncJob(): CharacterSyncJobEntity =
        Mockito.any(CharacterSyncJobEntity::class.java)
            ?: CharacterSyncJobEntity(
                account = account,
                status = CharacterSyncJobStatus.PENDING,
                startedAt = now,
            )

    private fun anySyncFailure(): CharacterSyncFailureEntity =
        Mockito.any(CharacterSyncFailureEntity::class.java)
            ?: CharacterSyncFailureEntity(
                syncJob = CharacterSyncJobEntity(
                    account = account,
                    status = CharacterSyncJobStatus.PENDING,
                    startedAt = now,
                ),
                failureOrder = 0,
                hofCharacterId = "",
            )

    private fun anyAccount(): HofAccountEntity =
        Mockito.any(HofAccountEntity::class.java) ?: account

    private fun anyHofCharacter(): HofCharacter =
        Mockito.any(HofCharacter::class.java) ?: HofCharacter(id = "")

    private class FakeHofGateway : HofGateway {
        val requests = mutableListOf<HofRequest>()
        var failedCharacterId: String? = null
        var failHome: Boolean = false

        override fun execute(
            accountId: Long,
            request: HofRequest,
            cookies: Map<String, String>,
        ): HofHttpResponse {
            requests += request
            if (failHome && request.url.endsWith("index.php")) error("home failed")
            if (failedCharacterId != null && request.url.endsWith("char=$failedCharacterId")) error("detail failed")

            val body = when {
                request.url.endsWith("index.php") -> """
                    <a href="index.php?char=111">소셜</a>
                    <a href="index.php?char=222">카발</a>
                """.trimIndent()

                request.url.endsWith("char=111") -> """
                    <div class="carpet_frame"><img src="https://hof.zerosic.com/image/char/sknight02.gif">소셜 Lv.60 Social Knight</div>
                    <form><input name="patternno" value="0"><input name="loadpattern" value="LOAD"></form>
                """.trimIndent()

                request.url.endsWith("char=222") -> """
                    <div class="carpet_frame"><img src="https://hof.zerosic.com/image/char/cavalry.gif">카발 Lv.60 Cavalry</div>
                    <form><input name="patternno" value="0"><input name="loadpattern" value="LOAD"></form>
                """.trimIndent()

                else -> error("Unexpected request: ${request.url}")
            }

            return HofHttpResponse(
                statusCode = 200,
                finalUrl = request.url,
                body = body,
                setCookies = emptyMap(),
            )
        }
    }
}
