package app.spammy.hof.automation.lease

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(AccountAutomationLeaseService::class)
class AccountAutomationLeaseServiceTest {
    @Autowired private lateinit var accountRepository: HofAccountRepository
    @Autowired private lateinit var service: AccountAutomationLeaseService

    @Test
    fun liveLeaseRejectsAnotherWorkerAndExpiredLeaseCanBeTakenOver() {
        val now = Instant.parse("2026-07-13T00:00:00Z")
        val account = accountRepository.save(
            HofAccountEntity(loginId = "lease-account", encryptedPassword = "encrypted", createdAt = now),
        )
        accountRepository.flush()

        assertTrue(service.tryAcquire(account.id, "worker-a", now, Duration.ofSeconds(30)))
        assertFalse(service.tryAcquire(account.id, "worker-b", now.plusSeconds(1), Duration.ofSeconds(30)))
        assertTrue(service.tryAcquire(account.id, "worker-b", now.plusSeconds(31), Duration.ofSeconds(30)))
        service.release(account.id, "worker-b")
        assertTrue(service.tryAcquire(account.id, "worker-c", now.plusSeconds(32), Duration.ofSeconds(30)))
    }
}
