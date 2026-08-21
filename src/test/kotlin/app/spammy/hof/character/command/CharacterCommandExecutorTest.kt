package app.spammy.hof.character.command

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.identity.CharacterIdentityResolver
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
        val service = commandExecutor(
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
        val service = commandExecutor(
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
                    operation(
                        object : CharacterCommandRemoteSession {
                            override fun observeRoster() = emptyList<CharacterCommandObservedIdentity>()
                            override fun execute(context: CharacterCommandContext, command: CharacterCommand) =
                                CharacterCommandObservation.Applied()
                            override fun refreshSnapshot(hofCharacterId: String) = true
                        },
                    )
                } finally {
                    inRemoteSession = false
                }
            }
        }
        val service = commandExecutor(query, passThroughGate(), remote)

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
        val service = commandExecutor(
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
        val service = commandExecutor(
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
        val service = commandExecutor(
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
        val service = commandExecutor(
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
        val service = commandExecutor(
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
        val service = commandExecutor(
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
        val service = commandExecutor(
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
        val service = commandExecutor(
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
    fun `destructive command refuses a stale hof id when the authoritative roster names another character`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character())
        var submitted = false
        val service = commandExecutor(
            query,
            passThroughGate(),
            remote(
                rosterBefore = listOf(
                    CharacterCommandObservedIdentity("hof-10", "다른 캐릭터", "Mage", 10),
                ),
            ) { _, _ ->
                submitted = true
                error("must not submit")
            },
        )

        val result = service.execute(1L, CharacterCommand.Knockback(7L, revision, "소셜"))

        assertEquals("REMOTE_IDENTITY_MISMATCH", assertIs<CharacterCommandResult.Rejected>(result).code)
        assertEquals(false, submitted)
    }

    @Test
    fun `destructive command pauses when its target disappeared from the authoritative pre roster`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character())
        var submitted = false
        val service = commandExecutor(
            query,
            passThroughGate(),
            remote(
                rosterBefore = listOf(CharacterCommandObservedIdentity("hof-20", "다른 캐릭터")),
            ) { _, _ ->
                submitted = true
                error("must not submit")
            },
        )

        val result = service.execute(1L, CharacterCommand.Kick(7L, revision, "소셜"))

        assertIs<CharacterCommandResult.RefreshRequired>(result)
        assertEquals(false, submitted)
    }

    @Test
    fun `destructive command pauses when the authoritative roster contains the same name twice`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character())
        var submitted = false
        val service = commandExecutor(
            query,
            passThroughGate(),
            remote(
                rosterBefore = listOf(
                    CharacterCommandObservedIdentity("hof-10", "소셜", "Social Knight", 60),
                    CharacterCommandObservedIdentity("hof-20", "소셜", "Mage", 40),
                ),
            ) { _, _ ->
                submitted = true
                error("must not submit")
            },
        )

        val result = service.execute(1L, CharacterCommand.Kick(7L, revision, "소셜"))

        assertIs<CharacterCommandResult.RefreshRequired>(result)
        assertEquals(false, submitted)
    }

    @Test
    fun `destructive command rejects a matching name with different job evidence`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character())
        var submitted = false
        val service = commandExecutor(
            query,
            passThroughGate(),
            remote(
                rosterBefore = listOf(CharacterCommandObservedIdentity("hof-10", "소셜", "Mage", 60)),
            ) { _, _ ->
                submitted = true
                error("must not submit")
            },
        )

        val result = service.execute(1L, CharacterCommand.Knockback(7L, revision, "소셜"))

        assertEquals("REMOTE_IDENTITY_MISMATCH", assertIs<CharacterCommandResult.Rejected>(result).code)
        assertEquals(false, submitted)
    }

    @Test
    fun `kick archives the stable character only after the authoritative roster proves removal`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val target = character()
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(target)
        val identityProjection = Mockito.mock(CharacterCommandIdentityProjection::class.java)
        Mockito.doAnswer {
            target.lifecycle = CharacterLifecycle.ARCHIVED
            target.updatedAt = revision.plusSeconds(1)
            CharacterCommandIdentityProjectionResult.Applied
        }.`when`(identityProjection).recordKick(1L, 7L, "hof-10", setOf("hof-20"))
        val service = commandExecutor(
            query,
            passThroughGate(),
            remote { _, _ ->
                CharacterCommandObservation.KickApplied(
                    rosterAfter = listOf(CharacterCommandObservedIdentity("hof-20", "다른 캐릭터")),
                    messages = listOf("캐릭터를 삭제했습니다."),
                )
            },
            identityResolver = CharacterIdentityResolver(),
            identityProjection = identityProjection,
        )

        val result = assertIs<CharacterCommandResult.Completed>(
            service.execute(1L, CharacterCommand.Kick(7L, revision, "소셜")),
        )

        assertEquals(revision.plusSeconds(1), result.revision)
        Mockito.verify(identityProjection).recordKick(1L, 7L, "hof-10", setOf("hof-20"))
    }

    @Test
    fun `kick remains unconfirmed and never archives when target is still in authoritative roster`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character())
        val identityProjection = Mockito.mock(CharacterCommandIdentityProjection::class.java)
        val service = commandExecutor(
            query,
            passThroughGate(),
            remote { _, _ ->
                CharacterCommandObservation.KickApplied(
                    listOf(CharacterCommandObservedIdentity("hof-10", "소셜", "Social Knight", 60)),
                )
            },
            identityResolver = CharacterIdentityResolver(),
            identityProjection = identityProjection,
        )

        val result = service.execute(1L, CharacterCommand.Kick(7L, revision, "소셜"))

        assertIs<CharacterCommandResult.RefreshRequired>(result)
        Mockito.verifyNoInteractions(identityProjection)
    }

    @Test
    fun `applied identity action with an unavailable followup locks the target without resubmitting`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val target = character()
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(target)
        val identityProjection = projection()
        var submissions = 0
        val service = commandExecutor(
            query,
            passThroughGate(),
            remote { _, _ ->
                submissions += 1
                CharacterCommandObservation.IdentityAppliedRosterUnconfirmed(revision)
            },
            identityProjection = identityProjection,
        )

        val result = service.execute(1L, CharacterCommand.Kick(7L, revision, "소셜"))

        assertIs<CharacterCommandResult.RefreshRequired>(result)
        assertEquals(1, submissions)
        Mockito.verify(identityProjection).recordUnconfirmedIdentityAction(1L, 7L, "hof-10", revision)
    }

    @Test
    fun `confirmed knockback links the stable character to the observed replacement and refreshes it`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val target = character().apply { level = 60 }
        val other = character().apply {
            hofCharacterId = "hof-20"
            name = "다른 캐릭터"
        }
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(target)
        Mockito.`when`(query.findAllByAccountId(1L)).thenReturn(listOf(target, other))
        val identityProjection = Mockito.mock(CharacterCommandIdentityProjection::class.java)
        var refreshedId: String? = null
        Mockito.doAnswer {
            target.hofCharacterId = "hof-11"
            target.updatedAt = revision.plusSeconds(1)
            CharacterCommandIdentityProjectionResult.Applied
        }.`when`(identityProjection).recordConfirmedKnockback(
            1L,
            7L,
            "hof-10",
            "hof-11",
            setOf("hof-10", "hof-20"),
            setOf("hof-20", "hof-11"),
        )
        val service = commandExecutor(
            query,
            passThroughGate(),
            remote(
                rosterBefore = listOf(
                    CharacterCommandObservedIdentity("hof-10", "소셜", "Social Knight", 60),
                    CharacterCommandObservedIdentity("hof-20", "다른 캐릭터"),
                ),
                refresh = { hofCharacterId -> refreshedId = hofCharacterId; true },
            ) { _, _ ->
                CharacterCommandObservation.KnockbackApplied(
                    rosterAfter = listOf(
                        CharacterCommandObservedIdentity("hof-20", "다른 캐릭터"),
                        CharacterCommandObservedIdentity("hof-11", "소셜", "Social Knight", 60),
                    ),
                    messages = listOf("캐릭터를 맨 뒤로 이동했습니다."),
                )
            },
            identityResolver = CharacterIdentityResolver(),
            identityProjection = identityProjection,
        )

        val result = assertIs<CharacterCommandResult.Completed>(
            service.execute(1L, CharacterCommand.Knockback(7L, revision, "소셜")),
        )

        assertEquals(revision.plusSeconds(1), result.revision)
        Mockito.verify(identityProjection).recordConfirmedKnockback(
            1L,
            7L,
            "hof-10",
            "hof-11",
            setOf("hof-10", "hof-20"),
            setOf("hof-20", "hof-11"),
        )
        assertEquals("hof-11", refreshedId)
    }

    @Test
    fun `ambiguous knockback returns candidates and never guesses a replacement`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val target = character().apply { level = 60 }
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(target)
        Mockito.`when`(query.findAllByAccountId(1L)).thenReturn(listOf(target))
        val identityProjection = projection()
        val service = commandExecutor(
            query,
            passThroughGate(),
            remote { _, _ ->
                CharacterCommandObservation.KnockbackApplied(
                    rosterAfter = listOf(
                        CharacterCommandObservedIdentity("hof-11", "소셜", "Social Knight", 60),
                        CharacterCommandObservedIdentity("hof-12", "소셜", "Social Knight", 60),
                    ),
                )
            },
            identityResolver = CharacterIdentityResolver(),
            identityProjection = identityProjection,
        )

        val result = assertIs<CharacterCommandResult.IdentityResolutionRequired>(
            service.execute(1L, CharacterCommand.Knockback(7L, revision, "소셜")),
        )

        assertEquals(setOf("hof-11", "hof-12"), result.candidates.mapTo(linkedSetOf()) { it.hofCharacterId })
        Mockito.verify(identityProjection).recordObservedRoster(1L, 7L, "hof-10", setOf("hof-11", "hof-12"))
    }

    @Test
    fun `unresolved knockback exposes the observed roster without inventing matching evidence`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val target = character().apply { level = 60 }
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(target)
        Mockito.`when`(query.findAllByAccountId(1L)).thenReturn(listOf(target))
        val identityProjection = projection()
        val service = commandExecutor(
            query,
            passThroughGate(),
            remote { _, _ ->
                CharacterCommandObservation.KnockbackApplied(
                    rosterAfter = listOf(CharacterCommandObservedIdentity("hof-99", "다른 이름", "Mage", 10)),
                )
            },
            identityResolver = CharacterIdentityResolver(),
            identityProjection = identityProjection,
        )

        val result = assertIs<CharacterCommandResult.IdentityResolutionRequired>(
            service.execute(1L, CharacterCommand.Knockback(7L, revision, "소셜")),
        )

        assertEquals("hof-99", result.candidates.single().hofCharacterId)
        assertEquals(emptySet(), result.candidates.single().matchingFields)
        Mockito.verify(identityProjection).recordObservedRoster(1L, 7L, "hof-10", setOf("hof-99"))
    }

    @Test
    fun `confirmed knockback does not overwrite another stable record that already owns the replacement id`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val target = character().apply { level = 60 }
        val occupied = CharacterEntity(
            id = 8L,
            account = account,
            hofCharacterId = "hof-11",
            name = "임시 기록",
            job = "Social Knight",
            level = 60,
            updatedAt = revision,
        )
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(target)
        Mockito.`when`(query.findAllByAccountId(1L)).thenReturn(listOf(target))
        Mockito.`when`(query.findByAccountIdAndHofCharacterId(1L, "hof-11")).thenReturn(occupied)
        val identityProjection = projection()
        Mockito.`when`(
            identityProjection.recordConfirmedKnockback(
                1L,
                7L,
                "hof-10",
                "hof-11",
                setOf("hof-10"),
                setOf("hof-11"),
            ),
        ).thenReturn(CharacterCommandIdentityProjectionResult.Occupied)
        val service = commandExecutor(
            query,
            passThroughGate(),
            remote { _, _ ->
                CharacterCommandObservation.KnockbackApplied(
                    listOf(CharacterCommandObservedIdentity("hof-11", "소셜", "Social Knight", 60)),
                )
            },
            identityResolver = CharacterIdentityResolver(),
            identityProjection = identityProjection,
        )

        val result = assertIs<CharacterCommandResult.IdentityResolutionRequired>(
            service.execute(1L, CharacterCommand.Knockback(7L, revision, "소셜")),
        )

        assertEquals("hof-11", result.candidates.single().hofCharacterId)
        assertEquals(setOf("name", "job", "level"), result.candidates.single().matchingFields)
        Mockito.verify(identityProjection).recordConfirmedKnockback(
            1L,
            7L,
            "hof-10",
            "hof-11",
            setOf("hof-10"),
            setOf("hof-11"),
        )
    }

    @Test
    fun `local identity change during remote knockback prevents the result projection from overwriting it`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val target = character().apply { level = 60 }
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(target)
        Mockito.`when`(query.findAllByAccountId(1L)).thenReturn(listOf(target))
        val identityProjection = projection()
        Mockito.`when`(
            identityProjection.recordConfirmedKnockback(
                1L,
                7L,
                "hof-10",
                "hof-11",
                setOf("hof-10"),
                setOf("hof-11"),
            ),
        ).thenReturn(CharacterCommandIdentityProjectionResult.Conflict)
        var refreshCalled = false
        val service = commandExecutor(
            query,
            passThroughGate(),
            remote(refresh = { refreshCalled = true; true }) { _, _ ->
                CharacterCommandObservation.KnockbackApplied(
                    listOf(CharacterCommandObservedIdentity("hof-11", "소셜", "Social Knight", 60)),
                )
            },
            identityResolver = CharacterIdentityResolver(),
            identityProjection = identityProjection,
        )

        val result = service.execute(1L, CharacterCommand.Knockback(7L, revision, "소셜"))

        assertIs<CharacterCommandResult.RefreshRequired>(result)
        assertEquals(false, refreshCalled)
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
        level = 60,
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
        rosterBefore: List<CharacterCommandObservedIdentity> = listOf(
            CharacterCommandObservedIdentity("hof-10", "소셜", "Social Knight", 60),
        ),
        refresh: (String) -> Boolean = { true },
        execute: (CharacterCommandContext, CharacterCommand) -> CharacterCommandObservation,
    ): CharacterCommandRemote = object : CharacterCommandRemote {
        override fun <T> withSession(accountId: Long, operation: (CharacterCommandRemoteSession) -> T): T =
            operation(
                object : CharacterCommandRemoteSession {
                    override fun observeRoster() = rosterBefore
                    override fun execute(context: CharacterCommandContext, command: CharacterCommand) = execute(context, command)
                    override fun refreshSnapshot(hofCharacterId: String) = refresh(hofCharacterId)
                },
            )
    }

    private fun commandExecutor(
        query: CharacterQueryRepository,
        gate: CharacterAutomationGate,
        remote: CharacterCommandRemote,
        identityResolver: CharacterIdentityResolver = Mockito.mock(CharacterIdentityResolver::class.java),
        identityProjection: CharacterCommandIdentityProjection = projection(),
    ) = CharacterCommandExecutor(query, gate, remote, identityResolver, identityProjection)

    private fun projection(): CharacterCommandIdentityProjection =
        Mockito.mock(CharacterCommandIdentityProjection::class.java).also { projection ->
            Mockito.`when`(
                projection.recordKick(
                    Mockito.anyLong(),
                    Mockito.anyLong(),
                    Mockito.anyString(),
                    Mockito.anySet(),
                    anyInstant(),
                ),
            ).thenReturn(CharacterCommandIdentityProjectionResult.Applied)
            Mockito.`when`(
                projection.recordObservedRoster(
                    Mockito.anyLong(),
                    Mockito.anyLong(),
                    Mockito.anyString(),
                    Mockito.anySet(),
                    anyInstant(),
                ),
            ).thenReturn(CharacterCommandIdentityProjectionResult.Applied)
            Mockito.`when`(
                projection.recordConfirmedKnockback(
                    Mockito.anyLong(),
                    Mockito.anyLong(),
                    Mockito.anyString(),
                    Mockito.anyString(),
                    Mockito.anySet(),
                    Mockito.anySet(),
                    anyInstant(),
                ),
            ).thenReturn(CharacterCommandIdentityProjectionResult.Applied)
            Mockito.`when`(
                projection.recordUnconfirmedIdentityAction(
                    Mockito.anyLong(),
                    Mockito.anyLong(),
                    Mockito.anyString(),
                    anyInstant(),
                ),
            ).thenReturn(CharacterCommandIdentityProjectionResult.Applied)
        }

    private fun anyInstant(): Instant = Mockito.any(Instant::class.java) ?: Instant.EPOCH
}
