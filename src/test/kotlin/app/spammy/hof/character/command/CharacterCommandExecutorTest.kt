package app.spammy.hof.character.command

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.repository.CharacterQueryRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
            CharacterAutomationCommandBridge { accountId, characterId, command ->
                assertEquals(1L, accountId)
                assertEquals(7L, characterId)
                bridged = true
                command()
            },
            CharacterCommandAdapter { context, _ -> received = context; expected },
        )

        val result = service.execute(1L, CharacterCommand.Pray(7L, revision))

        assertEquals(expected, result)
        assertEquals(true, bridged)
        assertEquals(CharacterCommandContext(1L, 7L, "hof-10"), received)
    }

    @Test
    fun `stale revision returns conflict without crossing adapter`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character())
        var called = false
        val service = CharacterCommandExecutor(
            query,
            CharacterAutomationCommandBridge { _, _, command -> command() },
            CharacterCommandAdapter { _, _ -> called = true; error("must not execute") },
        )
        val stale = revision.minusSeconds(1)

        val result = service.execute(1L, CharacterCommand.Pray(7L, stale))

        assertIs<CharacterCommandResult.Conflict>(result)
        assertEquals(revision, result.currentRevision)
        assertEquals(false, called)
    }

    @Test
    fun `missing and archived characters are rejected before remote observation`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character(CharacterLifecycle.ARCHIVED))
        val service = CharacterCommandExecutor(
            query,
            CharacterAutomationCommandBridge { _, _, command -> command() },
            CharacterCommandAdapter { _, _ -> error("must not execute") },
        )

        val result = service.execute(1L, CharacterCommand.Pray(7L, revision))

        assertEquals("CHARACTER_NOT_ACTIVE", assertIs<CharacterCommandResult.Rejected>(result).code)
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

        val result = bridge.execute(1L, 7L) {
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
}
