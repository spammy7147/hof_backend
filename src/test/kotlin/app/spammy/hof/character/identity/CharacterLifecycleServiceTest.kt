package app.spammy.hof.character.identity

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterHofIdHistoryEntity
import app.spammy.hof.character.entity.CharacterHofIdLinkReason
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.repository.CharacterHofIdHistoryRepository
import app.spammy.hof.character.repository.CharacterIdentityQueryRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.repository.CharacterRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.AccountHofMutationFence
import app.spammy.hof.town.common.service.TownActionGuard
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito

class CharacterLifecycleServiceTest {
    private val now = Instant.parse("2026-08-17T00:00:00Z")
    private val account = HofAccountEntity(1L, "account", "encrypted", now)

    @Test
    fun `multiple knockbacks append history while stable character id stays unchanged`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val accounts = Mockito.mock(AccountQueryRepository::class.java)
        val identity = Mockito.mock(CharacterIdentityQueryRepository::class.java)
        val characters = Mockito.mock(CharacterRepository::class.java)
        val histories = Mockito.mock(CharacterHofIdHistoryRepository::class.java)
        val character = character()
        val initial = CharacterHofIdHistoryEntity(
            id = 1L,
            character = character,
            account = account,
            hofCharacterId = "old",
            validFrom = now.minusSeconds(10),
            linkReason = CharacterHofIdLinkReason.INITIAL_SYNC,
            userConfirmed = true,
        )
        val saved = mutableListOf<CharacterHofIdHistoryEntity>()
        Mockito.`when`(query.findByAccountIdAndIdForUpdate(1L, 7L)).thenReturn(character)
        Mockito.`when`(accounts.findByIdForUpdate(1L)).thenReturn(account)
        Mockito.`when`(query.findByAccountIdAndHofCharacterId(Mockito.eq(1L), Mockito.anyString())).thenReturn(null)
        Mockito.`when`(identity.findOpenHistory(7L)).thenAnswer { saved.lastOrNull() ?: initial }
        Mockito.`when`(histories.save(anyHistory())).thenAnswer { invocation ->
            (invocation.arguments[0] as CharacterHofIdHistoryEntity).also(saved::add)
        }
        val service = CharacterLifecycleService(
            AccountHofMutationFence(),
            CharacterLifecycleTransaction(accounts, query, identity, characters, histories, TimeProvider { now }, Mockito.mock(app.spammy.hof.character.repository.CharacterOperationJobQueryRepository::class.java)),
        )

        service.link(1L, 7L, "new-1", CharacterHofIdLinkReason.KNOCKBACK, false)
        service.link(1L, 7L, "new-2", CharacterHofIdLinkReason.KNOCKBACK, false)

        assertEquals(7L, character.id)
        assertEquals("new-2", character.hofCharacterId)
        assertEquals(listOf("new-1", "new-2"), saved.map { it.hofCharacterId })
        assertEquals(now, initial.validTo)
        assertEquals(now, saved.first().validTo)
        assertNull(saved.last().validTo)
    }

    @Test
    fun `kick archive restore and permanent delete preserve account ownership rules`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val accounts = Mockito.mock(AccountQueryRepository::class.java)
        val identity = Mockito.mock(CharacterIdentityQueryRepository::class.java)
        val characters = Mockito.mock(CharacterRepository::class.java)
        val histories = Mockito.mock(CharacterHofIdHistoryRepository::class.java)
        val character = character()
        Mockito.`when`(query.findByAccountIdAndIdForUpdate(1L, 7L)).thenReturn(character)
        Mockito.`when`(accounts.findByIdForUpdate(1L)).thenReturn(account)
        val service = CharacterLifecycleService(
            AccountHofMutationFence(),
            CharacterLifecycleTransaction(accounts, query, identity, characters, histories, TimeProvider { now }, Mockito.mock(app.spammy.hof.character.repository.CharacterOperationJobQueryRepository::class.java)),
        )

        service.archive(1L, 7L)
        assertEquals(CharacterLifecycle.ARCHIVED, character.lifecycle)
        service.restore(1L, 7L)
        assertEquals(CharacterLifecycle.MISSING, character.lifecycle)
        service.archive(1L, 7L)
        service.deletePermanently(1L, 7L)
        Mockito.verify(characters).delete(character)

        assertFailsWith<ApiException> { service.archive(2L, 7L) }
    }

    @Test
    fun `manual lifecycle change waits until the account hof command sequence finishes`() {
        val accounts = Mockito.mock(AccountQueryRepository::class.java)
        val query = Mockito.mock(CharacterQueryRepository::class.java)
        val identity = Mockito.mock(CharacterIdentityQueryRepository::class.java)
        val characters = Mockito.mock(CharacterRepository::class.java)
        val histories = Mockito.mock(CharacterHofIdHistoryRepository::class.java)
        val character = character()
        val mutationFence = AccountHofMutationFence()
        val lifecycleEntered = CountDownLatch(1)
        Mockito.`when`(accounts.findByIdForUpdate(1L)).thenAnswer {
            lifecycleEntered.countDown()
            account
        }
        Mockito.`when`(query.findByAccountIdAndIdForUpdate(1L, 7L)).thenReturn(character)
        val lifecycle = CharacterLifecycleService(
            mutationFence,
            CharacterLifecycleTransaction(accounts, query, identity, characters, histories, TimeProvider { now }, Mockito.mock(app.spammy.hof.character.repository.CharacterOperationJobQueryRepository::class.java)),
        )
        val town = TownAuthenticatedExecutor(
            accounts,
            Mockito.mock(CookieQueryRepository::class.java),
            HofRequestFactory(),
            Mockito.mock(AccountHofGateway::class.java),
            LoginStateParser(),
            HofFormParser(),
            HofResultParser(),
            TownActionGuard(),
            mutationFence,
        )
        val commandStarted = CountDownLatch(1)
        val releaseCommand = CountDownLatch(1)
        val archiveAttempted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)

        try {
            val command = pool.submit {
                town.executeAccountSequence(1L) {
                    commandStarted.countDown()
                    assertTrue(releaseCommand.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(commandStarted.await(1, TimeUnit.SECONDS))
            val archive = pool.submit {
                archiveAttempted.countDown()
                lifecycle.archive(1L, 7L)
            }

            assertTrue(archiveAttempted.await(1, TimeUnit.SECONDS))
            assertFalse(lifecycleEntered.await(200, TimeUnit.MILLISECONDS))
            releaseCommand.countDown()

            command.get(1, TimeUnit.SECONDS)
            archive.get(1, TimeUnit.SECONDS)
            assertEquals(CharacterLifecycle.ARCHIVED, character.lifecycle)
        } finally {
            releaseCommand.countDown()
            pool.shutdownNow()
        }
    }

    private fun character() = CharacterEntity(
        id = 7L,
        account = account,
        hofCharacterId = "old",
        name = "소셜",
        job = "Social Knight",
        updatedAt = now,
    )

    private fun anyHistory(): CharacterHofIdHistoryEntity =
        Mockito.any(CharacterHofIdHistoryEntity::class.java) ?: CharacterHofIdHistoryEntity(
            character = character(),
            account = account,
            hofCharacterId = "matcher",
            validFrom = now,
            linkReason = CharacterHofIdLinkReason.MANUAL_LINK,
            userConfirmed = true,
        )
}
