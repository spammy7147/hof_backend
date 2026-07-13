package app.spammy.hof.account.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.common.persistence.QueryDslConfig
import jakarta.persistence.EntityManager
import java.time.Instant
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ActiveProfiles("test")
@DataJpaTest
@Import(QueryDslConfig::class, AccountQueryRepository::class, CookieQueryRepository::class)
class AccountQueryRepositoryTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var cookieRepository: HofCookieRepository

    @Autowired
    private lateinit var accountQueryRepository: AccountQueryRepository

    @Autowired
    private lateinit var cookieQueryRepository: CookieQueryRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun readsAccountByIdAndLoginId() {
        val saved = accountRepository.save(account(loginId = "query-user"))
        accountRepository.flush()
        entityManager.clear()

        val foundById = assertNotNull(accountQueryRepository.findById(saved.id))
        val foundByLoginId = assertNotNull(accountQueryRepository.findByLoginId("query-user"))

        assertEquals(saved.id, foundById.id)
        assertEquals("query-user", foundById.loginId)
        assertEquals(saved.id, foundByLoginId.id)
        assertNull(accountQueryRepository.findById(Long.MAX_VALUE))
        assertNull(accountQueryRepository.findByLoginId("missing-user"))
    }

    @Test
    fun replacesCookiesAfterDeleteAllAndFlushWithoutLeavingOldRows() {
        val savedAccount = accountRepository.save(account(loginId = "cookie-user"))
        val oldCookies = cookieRepository.saveAll(
            listOf(
                cookie(savedAccount, name = "session", value = "old-session"),
                cookie(savedAccount, name = "auth", value = "old-auth"),
                cookie(savedAccount, name = "legacy", value = "old-legacy"),
            ),
        )
        cookieRepository.flush()
        entityManager.clear()

        val existingCookies = cookieQueryRepository.findByAccountId(savedAccount.id)
        val managedAccount = assertNotNull(accountQueryRepository.findById(savedAccount.id))
        val oldCookieIds = oldCookies.map(HofCookieEntity::id).toSet()

        assertEquals(listOf("auth", "legacy", "session"), existingCookies.map(HofCookieEntity::name))
        assertEquals(listOf("old-auth", "old-legacy", "old-session"), existingCookies.map(HofCookieEntity::value))

        cookieRepository.deleteAll(existingCookies)
        cookieRepository.flush()

        val replacementCookies = cookieRepository.saveAll(
            listOf(
                cookie(managedAccount, name = "session", value = "new-session"),
                cookie(managedAccount, name = "auth", value = "new-auth"),
                cookie(managedAccount, name = "csrf", value = "new-csrf"),
            ),
        )
        cookieRepository.flush()
        entityManager.clear()

        val found = cookieQueryRepository.findByAccountId(savedAccount.id)

        assertEquals(3, found.size)
        assertEquals(listOf("auth", "csrf", "session"), found.map(HofCookieEntity::name))
        assertEquals(listOf("new-auth", "new-csrf", "new-session"), found.map(HofCookieEntity::value))
        assertEquals(replacementCookies.map(HofCookieEntity::id).toSet(), found.map(HofCookieEntity::id).toSet())
        assertTrue(found.none { cookie -> cookie.id in oldCookieIds })
    }

    private fun account(loginId: String): HofAccountEntity =
        HofAccountEntity(
            loginId = loginId,
            encryptedPassword = "encrypted-password",
            createdAt = NOW,
        )

    private fun cookie(
        account: HofAccountEntity,
        name: String,
        value: String,
    ): HofCookieEntity =
        HofCookieEntity(
            account = account,
            name = name,
            value = value,
            domain = "sic.zerosic.com",
            path = "/ZeroHOF",
            updatedAt = NOW,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-12T00:00:00Z")
    }
}
