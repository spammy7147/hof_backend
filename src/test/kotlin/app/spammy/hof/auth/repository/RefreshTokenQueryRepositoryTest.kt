package app.spammy.hof.auth.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.auth.entity.RefreshTokenEntity
import app.spammy.hof.common.persistence.QueryDslConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, RefreshTokenQueryRepository::class)
class RefreshTokenQueryRepositoryTest {
    @Autowired private lateinit var accountRepository: HofAccountRepository
    @Autowired private lateinit var tokenRepository: RefreshTokenRepository
    @Autowired private lateinit var queryRepository: RefreshTokenQueryRepository
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun findsTokenByHashWithLockAndReadsOnlyItsFamily() {
        val owner = savedAccount("refresh-owner")
        val other = savedAccount("refresh-other")
        val first = savedToken(owner, "a".repeat(64), FAMILY_ID, CREATED_AT)
        val second = savedToken(owner, "b".repeat(64), FAMILY_ID, CREATED_AT.plusSeconds(1))
        savedToken(other, "c".repeat(64), OTHER_FAMILY_ID, CREATED_AT.plusSeconds(2))

        assertEquals(first.id, assertNotNull(queryRepository.findByTokenHashForUpdate(first.tokenHash)).id)
        assertNull(queryRepository.findByTokenHashForUpdate("f".repeat(64)))
        assertEquals(
            listOf(first.id, second.id),
            queryRepository.findByFamilyId(FAMILY_ID).map { it.id },
        )
        assertEquals(2L, queryRepository.countByFamilyId(FAMILY_ID))
        assertEquals(2L, queryRepository.countActiveByAccountId(owner.id, CREATED_AT.plusSeconds(10)))

        second.rotatedAt = CREATED_AT.plusSeconds(5)
        assertEquals(1L, queryRepository.countActiveByAccountId(owner.id, CREATED_AT.plusSeconds(10)))
    }

    @Test
    fun databaseRejectsDuplicateTokenHash() {
        val account = savedAccount("refresh-constraints")
        savedToken(account, "d".repeat(64), FAMILY_ID, CREATED_AT)
        tokenRepository.flush()

        assertFailsWith<DataIntegrityViolationException> {
            savedToken(account, "d".repeat(64), OTHER_FAMILY_ID, CREATED_AT.plusSeconds(1))
            tokenRepository.flush()
        }
    }

    @Test
    fun deletingAccountCascadesToRefreshTokens() {
        val account = savedAccount("refresh-cascade")
        savedToken(account, "e".repeat(64), FAMILY_ID, CREATED_AT)
        tokenRepository.flush()

        jdbcTemplate.update("delete from hof_accounts where id = ?", account.id)

        assertEquals(0L, queryRepository.countByFamilyId(FAMILY_ID))
    }

    private fun savedAccount(loginId: String): HofAccountEntity =
        accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = CREATED_AT,
            ),
        )

    private fun savedToken(
        account: HofAccountEntity,
        tokenHash: String,
        familyId: String,
        createdAt: Instant,
    ): RefreshTokenEntity =
        tokenRepository.save(
            RefreshTokenEntity(
                account = account,
                tokenHash = tokenHash,
                familyId = familyId,
                clientType = "NATIVE",
                createdAt = createdAt,
                expiresAt = createdAt.plusSeconds(3_600),
            ),
        )

    private companion object {
        val CREATED_AT: Instant = Instant.parse("2026-07-13T00:00:00Z")
        const val FAMILY_ID = "11111111-1111-1111-1111-111111111111"
        const val OTHER_FAMILY_ID = "22222222-2222-2222-2222-222222222222"
    }
}
