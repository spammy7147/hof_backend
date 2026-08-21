package app.spammy.hof.character.command

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.repository.CharacterQueryRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.mockito.Mockito
import app.spammy.hof.automation.dto.TypedAutomationAggregateResponse
import app.spammy.hof.automation.dto.TypedAutomationRuntimeResponse
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.service.UnifiedAutomationService
import java.time.Duration

class CharacterCommandExecutorTest {
    private val revision = Instant.parse("2026-08-17T00:00:00Z")
    private val account = HofAccountEntity(1L, "account", "encrypted", revision)

    @Test
    fun `valid command crosses automation bridge and adapter with current hof id`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val character = character()
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character)
        var bridged = false
        var received: CharacterCommandContext? = null
        val expected = CharacterCommandResult.Completed(7L, revision, listOf("완료"))
        val service = CharacterCommandExecutor(
            query,
            gate { accountId ->
                assertEquals(1L, accountId)
                bridged = true
            },
            remote { context, _ ->
                received = context
                CharacterCommandObservation.Applied(listOf("완료"))
            },
        )

        val result = service.execute(1L, CharacterCommand.Pray(7L, revision))

        assertEquals(expected, result)
        assertEquals(true, bridged)
        assertEquals(CharacterCommandContext(1L, 7L, "hof-10"), received)
    }

    @Test
    fun `command reloads the character after automation is paused and uses the latest hof id`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val beforePause = character()
        val afterPause = character().apply { hofCharacterId = "hof-11" }
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(beforePause, afterPause, afterPause)
        var received: CharacterCommandContext? = null
        val expected = CharacterCommandResult.Completed(7L, revision, listOf("완료"))
        val service = CharacterCommandExecutor(
            query,
            passThroughGate(),
            remote { context, _ ->
                received = context
                CharacterCommandObservation.Applied(listOf("완료"))
            },
        )

        val result = service.execute(1L, CharacterCommand.Pray(7L, revision))

        assertEquals(expected, result)
        assertEquals(CharacterCommandContext(1L, 7L, "hof-11"), received)
    }

    @Test
    fun `latest character validation runs inside the serialized remote session`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        var reads = 0
        var inRemoteSession = false
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenAnswer {
            reads += 1
            if (reads > 1) assertTrue(inRemoteSession)
            character()
        }
        val remote = object : CharacterCommandRemote {
            override fun <T> withSession(accountId: Long, operation: (CharacterCommandRemoteSession) -> T): T {
                inRemoteSession = true
                return try {
                    operation(CharacterCommandRemoteSession { _, _ -> CharacterCommandObservation.Applied() })
                } finally {
                    inRemoteSession = false
                }
            }
        }
        val service = CharacterCommandExecutor(query, passThroughGate(), remote)

        assertIs<CharacterCommandResult.Completed>(
            service.execute(1L, CharacterCommand.Pray(7L, revision)),
        )
    }

    @Test
    fun `character archived while automation pauses is rejected before remote execution`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(
            character(),
            character(CharacterLifecycle.ARCHIVED),
        )
        var called = false
        val service = CharacterCommandExecutor(
            query,
            passThroughGate(),
            remote { _, _ ->
                called = true
                error("must not execute")
            },
        )

        val result = service.execute(1L, CharacterCommand.Pray(7L, revision))

        assertEquals("CHARACTER_NOT_ACTIVE", assertIs<CharacterCommandResult.Rejected>(result).code)
        assertEquals(false, called)
    }

    @Test
    fun `revision changed while automation pauses returns conflict before remote execution`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(
            character(),
            character().apply { updatedAt = revision.plusSeconds(1) },
        )
        var called = false
        val service = CharacterCommandExecutor(
            query,
            passThroughGate(),
            remote { _, _ ->
                called = true
                error("must not execute")
            },
        )

        val result = service.execute(1L, CharacterCommand.Pray(7L, revision))

        assertEquals(revision.plusSeconds(1), assertIs<CharacterCommandResult.Conflict>(result).currentRevision)
        assertEquals(false, called)
    }

    @Test
    fun `applied remote observation is projected with the refreshed authoritative revision`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val refreshedRevision = revision.plusSeconds(2)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(
            character(),
            character(),
            character().apply { updatedAt = refreshedRevision },
        )
        val service = CharacterCommandExecutor(
            query,
            passThroughGate(),
            remote { _, _ -> CharacterCommandObservation.Applied(listOf("완료")) },
        )

        val result = assertIs<CharacterCommandResult.Completed>(
            service.execute(1L, CharacterCommand.Pray(7L, revision)),
        )

        assertEquals(refreshedRevision, result.revision)
        assertEquals(listOf("완료"), result.messages)
    }

    @Test
    fun `unavailable automation gate returns refresh required without remote execution`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character())
        var called = false
        val unavailableGate = object : CharacterAutomationGate {
            override fun <T> execute(accountId: Long, unavailable: () -> T, operation: () -> T): T = unavailable()
        }
        val service = CharacterCommandExecutor(
            query,
            unavailableGate,
            remote { _, _ ->
                called = true
                error("must not execute")
            },
        )

        val result = service.execute(1L, CharacterCommand.Pray(7L, revision))

        assertIs<CharacterCommandResult.RefreshRequired>(result)
        assertEquals(false, called)
    }

    @Test
    fun `stale revision returns conflict without crossing adapter`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character())
        var called = false
        val service = CharacterCommandExecutor(
            query,
            passThroughGate(),
            remote { _, _ -> called = true; error("must not execute") },
        )
        val stale = revision.minusSeconds(1)

        val result = service.execute(1L, CharacterCommand.Pray(7L, stale))

        assertIs<CharacterCommandResult.Conflict>(result)
        assertEquals(revision, result.currentRevision)
        assertEquals(false, called)
    }

    @Test
    fun `stale revision after background sync still lets a confirmed kick reach adapter`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character())
        var received: CharacterCommand? = null
        val expected = CharacterCommandResult.Completed(7L, revision, listOf("캐릭터를 삭제했습니다."))
        val service = CharacterCommandExecutor(
            query,
            passThroughGate(),
            remote { _, command ->
                received = command
                CharacterCommandObservation.Applied(listOf("캐릭터를 삭제했습니다."))
            },
        )
        val command = CharacterCommand.Kick(7L, revision.minusSeconds(1), "소셜")

        val result = service.execute(1L, command)

        assertEquals(expected, result)
        assertEquals(command, received)
    }

    @Test
    fun `missing and archived characters are rejected before remote observation`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character(CharacterLifecycle.ARCHIVED))
        val service = CharacterCommandExecutor(
            query,
            passThroughGate(),
            remote { _, _ -> error("must not execute") },
        )

        val result = service.execute(1L, CharacterCommand.Pray(7L, revision))

        assertEquals("CHARACTER_NOT_ACTIVE", assertIs<CharacterCommandResult.Rejected>(result).code)
    }

    @Test
    fun `confirmation is checked against the latest name after automation pauses`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(
            character(),
            character().apply { name = "바뀐이름" },
        )
        var called = false
        val service = CharacterCommandExecutor(
            query,
            passThroughGate(),
            remote { _, _ ->
                called = true
                error("must not execute")
            },
        )

        val result = service.execute(1L, CharacterCommand.Kick(7L, revision, "소셜"))

        assertEquals("CONFIRMATION_MISMATCH", assertIs<CharacterCommandResult.Rejected>(result).code)
        assertEquals(false, called)
    }

    @Test
    fun `running automation drains pauses executes and resumes its previous state`() {
        val automation = Mockito.mock(UnifiedAutomationService::class.java)
        val states = ArrayDeque(
            listOf(
                TypedAutomationLifecycle.RUNNING,
                TypedAutomationLifecycle.DRAINING,
                TypedAutomationLifecycle.PAUSED,
            ),
        )
        Mockito.`when`(automation.getTyped(1L)).thenAnswer {
            aggregate(if (states.isEmpty()) TypedAutomationLifecycle.PAUSED else states.removeFirst())
        }
        val waits = mutableListOf<Duration>()
        var executed = false
        val bridge = TypedAutomationCharacterCommandBridge(automation, CharacterCommandPauseWaiter(waits::add))

        val result = bridge.execute(1L, unavailable = { error("must become available") }) {
            executed = true
            CharacterCommandResult.Completed(7L, revision)
        }

        assertIs<CharacterCommandResult.Completed>(result)
        assertEquals(true, executed)
        assertEquals(listOf(Duration.ofMillis(100)), waits)
        Mockito.verify(automation).pauseTyped(1L)
        Mockito.verify(automation).resumeTyped(1L)
    }

    @Test
    fun `user requested draining waits for pause and does not resume after character work`() {
        val automation = Mockito.mock(UnifiedAutomationService::class.java)
        val states = ArrayDeque(
            listOf(
                TypedAutomationLifecycle.DRAINING,
                TypedAutomationLifecycle.DRAINING,
                TypedAutomationLifecycle.PAUSED,
            ),
        )
        Mockito.`when`(automation.getTyped(1L)).thenAnswer {
            aggregate(if (states.isEmpty()) TypedAutomationLifecycle.PAUSED else states.removeFirst())
        }
        var executed = false
        val bridge = TypedAutomationCharacterCommandBridge(automation, CharacterCommandPauseWaiter { })

        bridge.execute(1L, unavailable = { error("must wait") }) { executed = true }

        assertEquals(true, executed)
        Mockito.verify(automation, Mockito.never()).pauseTyped(1L)
        Mockito.verify(automation, Mockito.never()).resumeTyped(1L)
    }

    private fun character(lifecycle: CharacterLifecycle = CharacterLifecycle.ACTIVE) = CharacterEntity(
        id = 7L,
        account = account,
        hofCharacterId = "hof-10",
        name = "소셜",
        job = "Social Knight",
        updatedAt = revision,
        lifecycle = lifecycle,
    )

    private fun aggregate(lifecycle: TypedAutomationLifecycle) = TypedAutomationAggregateResponse(
        entries = emptyList(),
        runtime = TypedAutomationRuntimeResponse(lifecycle),
    )

    private fun passThroughGate(): CharacterAutomationGate = gate { }

    private fun gate(before: (Long) -> Unit): CharacterAutomationGate = object : CharacterAutomationGate {
        override fun <T> execute(accountId: Long, unavailable: () -> T, operation: () -> T): T {
            before(accountId)
            return operation()
        }
    }

    private fun remote(
        execute: (CharacterCommandContext, CharacterCommand) -> CharacterCommandObservation,
    ): CharacterCommandRemote = object : CharacterCommandRemote {
        override fun <T> withSession(accountId: Long, operation: (CharacterCommandRemoteSession) -> T): T =
            operation(CharacterCommandRemoteSession(execute))
    }
}
