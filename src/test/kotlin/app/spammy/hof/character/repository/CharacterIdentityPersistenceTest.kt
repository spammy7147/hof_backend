package app.spammy.hof.character.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterHofIdHistoryEntity
import app.spammy.hof.character.entity.CharacterHofIdLinkReason
import app.spammy.hof.common.persistence.QueryDslConfig
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
@Import(QueryDslConfig::class, CharacterIdentityQueryRepository::class)
class CharacterIdentityPersistenceTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var characterRepository: CharacterRepository

    @Autowired
    private lateinit var historyRepository: CharacterHofIdHistoryRepository

    @Autowired
    private lateinit var identityRepository: CharacterIdentityQueryRepository

    @Test
    fun `multiple hof ids resolve to one stable character`() {
        val account = accountRepository.save(
            HofAccountEntity(
                loginId = "stable-character",
                encryptedPassword = "encrypted",
                createdAt = T0,
            ),
        )
        val character = characterRepository.save(
            CharacterEntity(
                account = account,
                hofCharacterId = "old-id",
                name = "소셜",
                job = "Social Knight",
                level = 60,
                updatedAt = T0,
            ),
        )
        val oldHistory = historyRepository.save(
            CharacterHofIdHistoryEntity(
                character = character,
                account = account,
                hofCharacterId = "old-id",
                validFrom = T0,
                linkReason = CharacterHofIdLinkReason.INITIAL_SYNC,
                userConfirmed = true,
            ),
        )

        oldHistory.close(T1)
        character.hofCharacterId = "new-id"
        historyRepository.flush()
        historyRepository.save(
            CharacterHofIdHistoryEntity(
                character = character,
                account = account,
                hofCharacterId = "new-id",
                validFrom = T1,
                linkReason = CharacterHofIdLinkReason.KNOCKBACK,
                userConfirmed = false,
            ),
        )
        historyRepository.flush()

        assertEquals(character.id, identityRepository.findByAccountIdAndAnyHofCharacterId(account.id, "old-id")?.id)
        assertEquals(character.id, identityRepository.findByAccountIdAndAnyHofCharacterId(account.id, "new-id")?.id)
        assertEquals("new-id", identityRepository.findOpenHistory(character.id)?.hofCharacterId)
        assertEquals(listOf("old-id", "new-id"), identityRepository.findHistory(character.id).map { it.hofCharacterId })
        assertNull(identityRepository.findByAccountIdAndAnyHofCharacterId(account.id, "unknown"))
    }

    companion object {
        private val T0 = Instant.parse("2026-08-17T00:00:00Z")
        private val T1 = Instant.parse("2026-08-17T00:01:00Z")
    }
}
