package app.spammy.hof.character.identity

import app.spammy.hof.account.entity.HofAccountEntity
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
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.mockito.Mockito

class CharacterLifecycleServiceTest {
    private val now = Instant.parse("2026-08-17T00:00:00Z")
    private val account = HofAccountEntity(1L, "account", "encrypted", now)

    @Test
    fun `multiple knockbacks append history while stable character id stays unchanged`() {
        val query = Mockito.mock(CharacterQueryRepository::class.java)
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
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character)
        Mockito.`when`(query.findByAccountIdAndHofCharacterId(Mockito.eq(1L), Mockito.anyString())).thenReturn(null)
        Mockito.`when`(identity.findOpenHistory(7L)).thenAnswer { saved.lastOrNull() ?: initial }
        Mockito.`when`(histories.save(anyHistory())).thenAnswer { invocation ->
            (invocation.arguments[0] as CharacterHofIdHistoryEntity).also(saved::add)
        }
        val service = CharacterLifecycleService(query, identity, characters, histories, TimeProvider { now })

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
        val identity = Mockito.mock(CharacterIdentityQueryRepository::class.java)
        val characters = Mockito.mock(CharacterRepository::class.java)
        val histories = Mockito.mock(CharacterHofIdHistoryRepository::class.java)
        val character = character()
        Mockito.`when`(query.findByAccountIdAndId(1L, 7L)).thenReturn(character)
        val service = CharacterLifecycleService(query, identity, characters, histories, TimeProvider { now })

        service.archive(1L, 7L)
        assertEquals(CharacterLifecycle.ARCHIVED, character.lifecycle)
        service.restore(1L, 7L)
        assertEquals(CharacterLifecycle.MISSING, character.lifecycle)
        service.archive(1L, 7L)
        service.deletePermanently(1L, 7L)
        Mockito.verify(characters).delete(character)

        assertFailsWith<ApiException> { service.archive(2L, 7L) }
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
