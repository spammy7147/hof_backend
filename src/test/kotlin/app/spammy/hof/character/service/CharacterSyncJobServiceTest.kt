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
import app.spammy.hof.character.repository.CharacterSyncFailureCommandRepository
import app.spammy.hof.character.repository.CharacterSyncJobQueryRepository
import app.spammy.hof.character.repository.CharacterSyncJobRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofCharacter
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.external.parser.CharacterRosterParser
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.task.SyncTaskExecutor
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
    private val eventService = Mockito.mock(CharacterSyncEventService::class.java)
    private val gateway = FakeHofGateway()
    private val savedJobs = mutableMapOf<Long, CharacterSyncJobEntity>()
    private val savedFailures = mutableListOf<CharacterSyncFailureEntity>()
    private val savedCharacters = linkedMapOf<String, CharacterResponse>()
    private val publishedEvents = mutableListOf<CharacterSyncEventResponse>()
    private val service = CharacterSyncJobService(
        accountQueryRepository = accountQueryRepository,
        cookieQueryRepository = cookieQueryRepository,
        syncJobRepository = syncJobRepository,
        syncFailureRepository = syncFailureRepository,
        syncJobQueryRepository = syncJobQueryRepository,
        characterService = characterService,
        requestFactory = HofRequestFactory(),
        gateway = gateway,
        rosterParser = CharacterRosterParser(),
        detailParser = CharacterDetailParser(),
        eventService = eventService,
        taskExecutor = SyncTaskExecutor(),
        timeProvider = TimeProvider { now },
    )

    @Test
    fun startSyncJobReusesTheActiveAccountJob() {
        arrangeRepositories()

        val first = service.startSyncJob(1L)
        val second = service.startSyncJob(1L)

        assertEquals(first.jobId, second.jobId)
        Mockito.verify(syncJobRepository, Mockito.times(1)).save(anySyncJob())
    }

    @Test
    fun streamSyncJobEventsStartsParsingAndPublishesCharactersOneByOne() {
        arrangeRepositories()

        val started = service.startSyncJob(1L)

        assertEquals("pending", started.status)
        assertEquals(emptyList(), started.failedCharacterIds)
        assertEquals(emptyList(), savedFailures)
        assertEquals(emptyList(), gateway.requests.map { it.url })
        assertEquals(emptyList(), publishedEvents.map { it.eventType })

        service.streamSyncJobEvents(accountId = 1L, jobId = started.jobId)
        val snapshot = service.findSyncJob(accountId = 1L, jobId = started.jobId)

        assertEquals("completed", snapshot.status)
        assertEquals(2, snapshot.rosterCount)
        assertEquals(2, snapshot.syncedCount)
        assertEquals(emptyList(), snapshot.failedCharacterIds)
        assertEquals(listOf("111", "222"), snapshot.characters.map { it.hofCharacterId })
        assertEquals(
            listOf(
                "http://sic.zerosic.com/ZeroHOF/index.php",
                "http://sic.zerosic.com/ZeroHOF/index.php?char=111",
                "http://sic.zerosic.com/ZeroHOF/index.php?char=222",
            ),
            gateway.requests.map { it.url },
        )
        assertEquals(
            listOf("started", "rosterParsed", "characterSynced", "characterSynced", "completed"),
            publishedEvents.map { it.eventType },
        )
        assertEquals(listOf(null, null, "111", "222", null), publishedEvents.map { it.character?.hofCharacterId })
        assertEquals(
            listOf(null, null, "http://sic.zerosic.com/ZeroHOF/image/char/sknight02.gif", "http://sic.zerosic.com/ZeroHOF/image/char/cavalry.gif", null),
            publishedEvents.map { it.character?.imageUrl },
        )
        assertEquals(List(5) { emptyList() }, publishedEvents.map { it.failedCharacterIds })
        Mockito.verify(eventService).complete(started.jobId)
    }

    @Test
    fun detailFailureAppendsOneOrderedRowAndKeepsIncrementalSseBehavior() {
        arrangeRepositories()
        gateway.failedCharacterId = "222"

        val started = service.startSyncJob(1L)
        service.streamSyncJobEvents(accountId = 1L, jobId = started.jobId)
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
        service.streamSyncJobEvents(accountId = 1L, jobId = started.jobId)
        val snapshot = service.findSyncJob(accountId = 1L, jobId = started.jobId)

        assertEquals("failed", snapshot.status)
        assertEquals("home failed", snapshot.message)
        assertEquals(emptyList(), snapshot.failedCharacterIds)
        assertEquals(listOf("started", "failed"), publishedEvents.map { it.eventType })
        assertEquals("home failed", publishedEvents.last().message)
        Mockito.verify(eventService).complete(started.jobId)
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

        override fun execute(request: HofRequest, cookies: Map<String, String>): HofHttpResponse {
            requests += request
            if (failHome && request.url.endsWith("index.php")) error("home failed")
            if (failedCharacterId != null && request.url.endsWith("char=$failedCharacterId")) error("detail failed")

            val body = when {
                request.url.endsWith("index.php") -> """
                    <a href="index.php?char=111">소셜</a>
                    <a href="index.php?char=222">카발</a>
                """.trimIndent()

                request.url.endsWith("char=111") -> """
                    <div class="carpet_frame"><img src="http://sic.zerosic.com/ZeroHOF/image/char/sknight02.gif">소셜 Lv.60 Social Knight</div>
                    <form><input name="patternno" value="0"><input name="loadpattern" value="LOAD"></form>
                """.trimIndent()

                request.url.endsWith("char=222") -> """
                    <div class="carpet_frame"><img src="http://sic.zerosic.com/ZeroHOF/image/char/cavalry.gif">카발 Lv.60 Cavalry</div>
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
