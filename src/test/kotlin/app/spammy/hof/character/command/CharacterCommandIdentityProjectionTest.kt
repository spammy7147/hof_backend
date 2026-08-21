package app.spammy.hof.character.command

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterHofIdHistoryEntity
import app.spammy.hof.character.entity.CharacterHofIdLinkReason
import app.spammy.hof.character.entity.CharacterLifecycle
import app.spammy.hof.character.identity.CharacterLifecycleService
import app.spammy.hof.character.identity.CharacterLifecycleTransaction
import app.spammy.hof.character.repository.CharacterHofIdHistoryRepository
import app.spammy.hof.character.repository.CharacterIdentityQueryRepository
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.repository.CharacterRepository
import app.spammy.hof.character.service.CharacterService
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.status.repository.HofStatusSnapshotQueryRepository
import app.spammy.hof.status.repository.HofStatusSnapshotCommandRepository
import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import app.spammy.hof.town.common.service.AccountHofMutationFence
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@ActiveProfiles("test")
@DataJpaTest
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    CharacterQueryRepository::class,
    CharacterIdentityQueryRepository::class,
    HofStatusSnapshotQueryRepository::class,
    CharacterService::class,
    CharacterLifecycleService::class,
    CharacterLifecycleTransaction::class,
    AccountHofMutationFence::class,
    CharacterCommandIdentityProjection::class,
    CharacterCommandIdentityProjectionTest.ClockConfig::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CharacterCommandIdentityProjectionTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var characterRepository: CharacterRepository
    @Autowired private lateinit var histories: CharacterHofIdHistoryRepository
    @Autowired private lateinit var characters: CharacterQueryRepository
    @Autowired private lateinit var identity: CharacterIdentityQueryRepository
    @Autowired private lateinit var projection: CharacterCommandIdentityProjection
    @Autowired private lateinit var statusCommands: HofStatusSnapshotCommandRepository

    @Test
    fun `confirmed knockback merges the provisional post-observation row into the stable character`() {
        val account = accounts.save(account("knockback-provisional"))
        val target = saveCharacter(account, "old-id", "소셜")
        val provisional = saveCharacter(account, "new-id", "소셜")

        val result = projection.recordConfirmedKnockback(
            account.id,
            target.id,
            "old-id",
            "new-id",
            localHofIdsBefore = setOf("old-id"),
            rosterIds = setOf("new-id"),
            rosterObservedAt = NOW.plusSeconds(1),
        )

        assertEquals(CharacterCommandIdentityProjectionResult.Applied, result)
        val linked = assertNotNull(characters.findByAccountIdAndHofCharacterId(account.id, "new-id"))
        assertEquals(target.id, linked.id)
        assertEquals(1L, characters.countByAccountId(account.id))
        assertEquals(listOf("old-id", "new-id"), identity.findHistory(target.id).map { it.hofCharacterId })
        assertEquals(false, identity.findOpenHistory(target.id)?.userConfirmed)
        assertEquals(null, characters.findByAccountIdAndId(account.id, provisional.id))
    }

    @Test
    fun `confirmed knockback preserves a record that already owned the replacement before submission`() {
        val account = accounts.save(account("knockback-collision"))
        val target = saveCharacter(account, "old-id", "소셜")
        val occupied = saveCharacter(account, "new-id", "다른 기록", CharacterHofIdLinkReason.MANUAL_LINK)

        val result = projection.recordConfirmedKnockback(
            account.id,
            target.id,
            "old-id",
            "new-id",
            localHofIdsBefore = setOf("old-id"),
            rosterIds = setOf("new-id"),
            rosterObservedAt = NOW.plusSeconds(1),
        )

        assertEquals(CharacterCommandIdentityProjectionResult.Occupied, result)
        val missingTarget = assertNotNull(characters.findByAccountIdAndId(account.id, target.id))
        assertEquals("old-id", missingTarget.hofCharacterId)
        assertEquals(CharacterLifecycle.MISSING, missingTarget.lifecycle)
        assertEquals(occupied.id, characters.findByAccountIdAndHofCharacterId(account.id, "new-id")?.id)
        assertEquals(2L, characters.countByAccountId(account.id))
    }

    @Test
    fun `unconfirmed applied identity command locks the old stable id as missing`() {
        val account = accounts.save(account("unconfirmed-identity"))
        val target = saveCharacter(account, "old-id", "소셜")

        val result = projection.recordUnconfirmedIdentityAction(
            account.id,
            target.id,
            "old-id",
            NOW.plusSeconds(1),
        )

        assertEquals(CharacterCommandIdentityProjectionResult.Applied, result)
        assertEquals(CharacterLifecycle.MISSING, characters.findByAccountIdAndId(account.id, target.id)?.lifecycle)
    }

    @Test
    fun `older command roster cannot overwrite a newer roster watermark`() {
        val account = accounts.save(account("newer-watermark"))
        val target = saveCharacter(account, "old-id", "소셜")
        statusCommands.save(
            HofStatusSnapshotEntity(
                account = account,
                playerName = "player",
                funds = 0,
                timeCurrent = 0,
                timeMax = 0,
                work = "",
                auction = "",
                observedAt = NOW,
                characterRosterObservedAt = NOW.plusSeconds(2),
            ),
        )

        val result = projection.recordUnconfirmedIdentityAction(
            account.id,
            target.id,
            "old-id",
            NOW.plusSeconds(1),
        )

        assertEquals(CharacterCommandIdentityProjectionResult.Conflict, result)
        assertEquals(CharacterLifecycle.ACTIVE, characters.findByAccountIdAndId(account.id, target.id)?.lifecycle)
    }

    private fun saveCharacter(
        account: HofAccountEntity,
        hofId: String,
        name: String,
        linkReason: CharacterHofIdLinkReason = CharacterHofIdLinkReason.INITIAL_SYNC,
    ): CharacterEntity {
        val saved = characterRepository.save(
            CharacterEntity(
                account = account,
                hofCharacterId = hofId,
                name = name,
                job = "Social Knight",
                level = 60,
                updatedAt = NOW,
            ),
        )
        histories.save(
            CharacterHofIdHistoryEntity(
                character = saved,
                account = account,
                hofCharacterId = hofId,
                validFrom = NOW,
                linkReason = linkReason,
                userConfirmed = true,
            ),
        )
        return saved
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ClockConfig {
        @Bean fun timeProvider(): TimeProvider = TimeProvider { NOW.plusSeconds(1) }
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-08-21T00:00:00Z")
        fun account(loginId: String) = HofAccountEntity(loginId = loginId, encryptedPassword = "encrypted", createdAt = NOW)
    }
}

@ActiveProfiles("test")
@DataJpaTest
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    CharacterQueryRepository::class,
    CharacterIdentityQueryRepository::class,
    HofStatusSnapshotQueryRepository::class,
    CharacterService::class,
    CharacterCommandIdentityProjection::class,
    CharacterCommandIdentityProjectionTest.ClockConfig::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CharacterCommandIdentityProjectionRollbackTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var characterRepository: CharacterRepository
    @Autowired private lateinit var characters: CharacterQueryRepository
    @Autowired private lateinit var projection: CharacterCommandIdentityProjection

    @MockitoBean private lateinit var lifecycle: CharacterLifecycleService

    @Test
    fun `kick roster reconciliation rolls back when archive cannot be committed`() {
        val account = accounts.save(CharacterCommandIdentityProjectionTest.account("kick-rollback"))
        val target = characterRepository.save(
            CharacterEntity(
                account = account,
                hofCharacterId = "old-id",
                name = "소셜",
                job = "Social Knight",
                updatedAt = CharacterCommandIdentityProjectionTest.NOW,
            ),
        )
        Mockito.doThrow(IllegalStateException("archive failed")).`when`(lifecycle).archive(account.id, target.id)

        assertFailsWith<IllegalStateException> {
            projection.recordKick(account.id, target.id, "old-id", setOf("another-id"))
        }

        val unchanged = assertNotNull(characters.findByAccountIdAndId(account.id, target.id))
        assertEquals(CharacterLifecycle.ACTIVE, unchanged.lifecycle)
        assertEquals(null, unchanged.missingSince)
    }
}
