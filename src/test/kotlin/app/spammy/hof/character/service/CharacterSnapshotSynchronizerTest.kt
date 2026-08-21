package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.dto.CharacterResponse
import app.spammy.hof.character.dto.CharacterDetailResponse
import app.spammy.hof.character.entity.CharacterSectionSyncStateEntity
import app.spammy.hof.character.entity.CharacterSectionSyncStatus
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofCharacter
import app.spammy.hof.external.model.HofPatternSlot
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.external.parser.CharacterPageParseResult
import app.spammy.hof.town.common.service.AccountHofMutationFence
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito

class CharacterSnapshotSynchronizerTest {
    @Test
    fun `same id reappearing from missing state always refreshes detail immediately`() {
        val now = Instant.parse("2026-08-17T00:00:00Z")
        val account = HofAccountEntity(1L, "account", "encrypted", now)
        val character = CharacterEntity(
            id = 7L,
            account = account,
            hofCharacterId = "1",
            name = "returned",
            job = "job",
            updatedAt = now,
            lifecycle = CharacterLifecycle.MISSING,
        )
        val accountQuery = Mockito.mock(AccountQueryRepository::class.java)
        val cookieQuery = Mockito.mock(CookieQueryRepository::class.java)
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val characters = Mockito.mock(CharacterService::class.java)
        val writer = Mockito.mock(CharacterSnapshotWriter::class.java)
        val gateway = Mockito.mock(AccountHofGateway::class.java)
        val response = CharacterResponse(7L, "1", "returned", "job", 60, 0, null, revision = now)
        Mockito.`when`(query.findByAccountIdAndHofCharacterId(1L, "1")).thenReturn(character)
        Mockito.`when`(characters.findAll(1L)).thenReturn(listOf(response))
        Mockito.`when`(gateway.execute(Mockito.eq(1L), anyHofRequest(), Mockito.anyMap())).thenReturn(
            HofHttpResponse(
                200,
                "http://sic.zerosic.com/ZeroHOF/index.php?char=1",
                "<div class='carpet_frame'>returned Lv.60 job</div>",
                emptyMap(),
            ),
        )
        val service = CharacterSnapshotSynchronizer(
            accountQuery,
            cookieQuery,
            query,
            characters,
            writer,
            HofRequestFactory(),
            gateway,
            CharacterDetailParser(),
            TimeProvider { now },
            app.spammy.hof.town.common.service.AccountHofMutationFence(),
        )

        assertEquals(response, service.synchronize(account, mapOf("PHPSESSID" to "x"), HofCharacter("1", "returned")))
        Mockito.verify(gateway).execute(Mockito.eq(1L), anyHofRequest(), Mockito.anyMap())
    }

    @Test
    fun `snapshot write waits until the account identity command fence is released`() {
        val now = Instant.parse("2026-08-17T00:00:00Z")
        val account = HofAccountEntity(1L, "account", "encrypted", now)
        val character = CharacterEntity(
            id = 7L,
            account = account,
            hofCharacterId = "1",
            name = "character",
            job = "job",
            updatedAt = now,
        )
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val characters = Mockito.mock(CharacterService::class.java)
        val writer = Mockito.mock(CharacterSnapshotWriter::class.java)
        val fence = AccountHofMutationFence()
        val snapshotEntered = CountDownLatch(1)
        Mockito.`when`(query.findByAccountIdAndHofCharacterId(1L, "1")).thenAnswer {
            snapshotEntered.countDown()
            character
        }
        Mockito.`when`(characters.findDetail(1L, "1"))
            .thenReturn(Mockito.mock(CharacterDetailResponse::class.java))
        val service = CharacterSnapshotSynchronizer(
            Mockito.mock(AccountQueryRepository::class.java),
            Mockito.mock(CookieQueryRepository::class.java),
            query,
            characters,
            writer,
            HofRequestFactory(),
            Mockito.mock(AccountHofGateway::class.java),
            CharacterDetailParser(),
            TimeProvider { now },
            fence,
        )
        val commandStarted = CountDownLatch(1)
        val releaseCommand = CountDownLatch(1)
        val snapshotAttempted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        try {
            val command = pool.submit {
                fence.execute(1L) {
                    commandStarted.countDown()
                    assertTrue(releaseCommand.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(commandStarted.await(1, TimeUnit.SECONDS))
            val snapshot = pool.submit {
                snapshotAttempted.countDown()
                service.writeParsed(1L, "1", page("character"))
            }

            assertTrue(snapshotAttempted.await(1, TimeUnit.SECONDS))
            assertFalse(snapshotEntered.await(200, TimeUnit.MILLISECONDS))
            releaseCommand.countDown()

            command.get(1, TimeUnit.SECONDS)
            snapshot.get(1, TimeUnit.SECONDS)
            Mockito.verify(writer).write(
                character,
                page("character"),
                now,
                CharacterSnapshotSynchronizer.NORMAL_SECTIONS,
            )
        } finally {
            releaseCommand.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `refresh resolves the current hof id only after acquiring the account identity fence`() {
        val now = Instant.parse("2026-08-17T00:00:00Z")
        val account = HofAccountEntity(1L, "account", "encrypted", now)
        val character = CharacterEntity(
            id = 7L,
            account = account,
            hofCharacterId = "old-id",
            name = "character",
            job = "job",
            updatedAt = now,
        )
        val accountQuery = Mockito.mock(AccountQueryRepository::class.java)
        val cookieQuery = Mockito.mock(CookieQueryRepository::class.java)
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val characters = Mockito.mock(CharacterService::class.java)
        val writer = Mockito.mock(CharacterSnapshotWriter::class.java)
        val gateway = Mockito.mock(AccountHofGateway::class.java)
        val fence = AccountHofMutationFence()
        val stableLookupEntered = CountDownLatch(1)
        Mockito.`when`(accountQuery.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQuery.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenAnswer {
            stableLookupEntered.countDown()
            character
        }
        Mockito.`when`(query.findByAccountIdAndHofCharacterId(1L, "new-id")).thenReturn(character)
        Mockito.`when`(characters.findAll(1L)).thenReturn(
            listOf(CharacterResponse(7L, "new-id", "character", "job", 60, 0, null, revision = now)),
        )
        Mockito.`when`(characters.findDetailById(1L, 7L))
            .thenReturn(Mockito.mock(CharacterDetailResponse::class.java))
        var requestedUrl: String? = null
        Mockito.`when`(gateway.execute(Mockito.eq(1L), anyHofRequest(), Mockito.anyMap())).thenAnswer { invocation ->
            val url = invocation.getArgument<HofRequest>(1).url
            requestedUrl = url
            HofHttpResponse(200, url, "<div class='carpet_frame'>character Lv.60 job</div>", emptyMap())
        }
        val service = CharacterSnapshotSynchronizer(
            accountQuery,
            cookieQuery,
            query,
            characters,
            writer,
            HofRequestFactory(),
            gateway,
            CharacterDetailParser(),
            TimeProvider { now },
            fence,
        )
        val commandStarted = CountDownLatch(1)
        val releaseCommand = CountDownLatch(1)
        val refreshAttempted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        try {
            val command = pool.submit {
                fence.execute(1L) {
                    commandStarted.countDown()
                    assertTrue(releaseCommand.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(commandStarted.await(1, TimeUnit.SECONDS))
            val refresh = pool.submit {
                refreshAttempted.countDown()
                service.refresh(1L, 7L)
            }
            assertTrue(refreshAttempted.await(1, TimeUnit.SECONDS))
            assertFalse(stableLookupEntered.await(200, TimeUnit.MILLISECONDS))

            character.hofCharacterId = "new-id"
            releaseCommand.countDown()

            command.get(1, TimeUnit.SECONDS)
            refresh.get(1, TimeUnit.SECONDS)
            assertTrue(requireNotNull(requestedUrl).endsWith("char=new-id"))
        } finally {
            releaseCommand.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `normal snapshot is fresh for thirty minutes inclusive`() {
        val now = Instant.parse("2026-08-17T00:30:00Z")
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val character = character(now)
        Mockito.`when`(query.findSectionStates(character.id)).thenReturn(
            CharacterSnapshotSynchronizer.NORMAL_SECTIONS.map { section ->
                CharacterSectionSyncStateEntity(
                    character = character,
                    section = section,
                    status = CharacterSectionSyncStatus.SUCCESS,
                    parserVersion = "test",
                    lastAttemptedAt = now.minusSeconds(1_800),
                    lastSucceededAt = now.minusSeconds(1_800),
                )
            },
        )

        assertTrue(synchronizer(query, now).isFresh(character.id))
    }

    @Test
    fun `normal snapshot becomes stale after thirty minutes or when one section failed`() {
        val now = Instant.parse("2026-08-17T00:30:01Z")
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val character = character(now)
        val states = CharacterSnapshotSynchronizer.NORMAL_SECTIONS.map { section ->
            CharacterSectionSyncStateEntity(
                character = character,
                section = section,
                status = CharacterSectionSyncStatus.SUCCESS,
                parserVersion = "test",
                lastAttemptedAt = now.minusSeconds(1_801),
                lastSucceededAt = now.minusSeconds(1_801),
            )
        }
        Mockito.`when`(query.findSectionStates(character.id)).thenReturn(states)
        assertFalse(synchronizer(query, now).isFresh(character.id))

        states.first().status = CharacterSectionSyncStatus.FAILED
        states.forEach { it.lastSucceededAt = now }
        assertFalse(synchronizer(query, now).isFresh(character.id))
    }

    @Test
    fun `deep sync loads and captures all nine saved patterns then restores original state`() {
        val original = page(
            "original",
            (0..8).map { index -> HofPatternSlot(index.toString(), "slot-$index", canLoad = true) },
        )
        val remote = FakeDeepRemote(original)
        val store = RecordingStore()
        val progress = mutableListOf<CharacterDeepSyncProgress>()

        CharacterDeepSyncOrchestrator().synchronize(remote, store, progress::add)

        assertEquals(
            listOf("capture", *(0..8).map { "pattern:$it" }.toTypedArray(), "equipment:1", "equipment:2", "restore"),
            remote.operations,
        )
        assertEquals((0..8).map(Int::toString), store.patterns)
        assertEquals(listOf(1, 2), store.equipment)
        assertEquals(listOf("original", "restored"), store.current)
        assertEquals(13, progress.last().totalSteps)
        assertEquals(CharacterDeepSyncPhase.COMPLETED, progress.last().phase)
    }

    @Test
    fun `deep sync restores original state when a middle pattern capture fails`() {
        val original = page(
            "original",
            (0..2).map { index -> HofPatternSlot(index.toString(), "slot-$index", canLoad = true) },
        )
        val remote = FakeDeepRemote(original, failingPattern = "1")
        val store = RecordingStore()

        assertFailsWith<IllegalStateException> {
            CharacterDeepSyncOrchestrator().synchronize(remote, store)
        }

        assertEquals(listOf("capture", "pattern:0", "pattern:1", "restore"), remote.operations)
        assertEquals(listOf("original", "restored"), store.current)
    }

    private class FakeDeepRemote(
        private val original: CharacterPageParseResult,
        private val failingPattern: String? = null,
    ) : CharacterDeepSyncRemote {
        val operations = mutableListOf<String>()

        override fun captureCurrent(): CharacterPageParseResult = original.also { operations += "capture" }

        override fun loadSavedPattern(slotCode: String): CharacterPageParseResult {
            operations += "pattern:$slotCode"
            check(slotCode != failingPattern) { "pattern load failed" }
            return page("pattern-$slotCode")
        }

        override fun loadEquipmentPreset(slotNumber: Int): CharacterPageParseResult =
            page("equipment-$slotNumber").also { operations += "equipment:$slotNumber" }

        override fun restoreCurrent(original: CharacterPageParseResult): CharacterPageParseResult =
            page("restored").also { operations += "restore" }
    }

    private class RecordingStore : CharacterDeepSyncStore {
        val current = mutableListOf<String>()
        val patterns = mutableListOf<String>()
        val equipment = mutableListOf<Int>()

        override fun saveCurrent(snapshot: CharacterPageParseResult) {
            current += snapshot.snapshot.name
        }

        override fun savePatternSlot(slotCode: String, snapshot: CharacterPageParseResult) {
            patterns += slotCode
        }

        override fun saveEquipmentPreset(slotNumber: Int, snapshot: CharacterPageParseResult) {
            equipment += slotNumber
        }
    }

    private companion object {
        fun page(name: String, slots: List<HofPatternSlot> = emptyList()) =
            CharacterPageParseResult(HofCharacter(id = "1", name = name, patternSlots = slots), emptyMap())

        fun character(now: Instant): CharacterEntity {
            val account = HofAccountEntity(
                id = 1L,
                loginId = "account",
                encryptedPassword = "encrypted",
                createdAt = now,
            )
            return CharacterEntity(
                id = 7L,
                account = account,
                hofCharacterId = "1",
                name = "character",
                job = "job",
                updatedAt = now,
            )
        }

        fun synchronizer(query: CharacterQueryRepository, now: Instant) = CharacterSnapshotSynchronizer(
            accountQueryRepository = Mockito.mock(AccountQueryRepository::class.java),
            cookieQueryRepository = Mockito.mock(CookieQueryRepository::class.java),
            characterQueryRepository = query,
            characterService = Mockito.mock(CharacterService::class.java),
            snapshotWriter = Mockito.mock(CharacterSnapshotWriter::class.java),
            requestFactory = HofRequestFactory(),
            gateway = Mockito.mock(AccountHofGateway::class.java),
            detailParser = CharacterDetailParser(),
            timeProvider = TimeProvider { now },
            mutationFence = app.spammy.hof.town.common.service.AccountHofMutationFence(),
        )

        fun anyHofRequest(): HofRequest =
            Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, "http://localhost")

    }
}
