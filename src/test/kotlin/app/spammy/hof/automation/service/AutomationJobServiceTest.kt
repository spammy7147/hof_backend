package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.dto.CreateAutomationJobRequest
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.automation.repository.AutomationJobRepository
import app.spammy.hof.automation.repository.AutomationProfileRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.persistence.QueryDslConfig
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@DataJpaTest
@ActiveProfiles("test")
@Import(
    QueryDslConfig::class,
    AutomationJobQueryRepository::class,
    AutomationJobService::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutomationJobServiceTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var profileRepository: AutomationProfileRepository

    @Autowired
    private lateinit var jobRepository: AutomationJobRepository

    @Autowired
    private lateinit var jobQueryRepository: AutomationJobQueryRepository

    @Autowired
    private lateinit var service: AutomationJobService

    @Test
    fun legacyJobMutationsReturnGoneWithoutCreatingOrChangingRows() {
        val account = savedAccount("automation-job-retired")
        val profile = savedProfile(account)

        assertRetired {
            service.create(account.id, CreateAutomationJobRequest(profile.id))
        }
        assertEquals(0L, jobQueryRepository.countByAccountIdAndStatuses(account.id, ACTIVE_STATUSES))

        val stored = jobRepository.save(
            AutomationJobEntity(
                account = account,
                profile = profile,
                status = "PAUSED",
                currentStepIndex = 2,
                message = "legacy history",
                createdAt = NOW,
                startedAt = NOW,
                updatedAt = NOW,
                finishedAt = null,
            ),
        )

        assertRetired { service.pause(account.id, stored.id) }
        assertRetired { service.resume(account.id, stored.id) }
        assertRetired { service.cancel(account.id, stored.id) }

        val unchanged = requireNotNull(jobQueryRepository.findOwnedByAccountIdAndId(account.id, stored.id))
        assertEquals("PAUSED", unchanged.status)
        assertNull(unchanged.finishedAt)
    }

    @Test
    fun currentLegacyJobRemainsReadOnlyForHistoryCompatibilityAndAccountIsolation() {
        val owner = savedAccount("automation-job-read-owner")
        val other = savedAccount("automation-job-read-other")
        val profile = savedProfile(owner)
        val stored = jobRepository.save(
            AutomationJobEntity(
                account = owner,
                profile = profile,
                status = "RUNNING",
                currentStepIndex = 3,
                message = "legacy read",
                createdAt = NOW.minusSeconds(30),
                startedAt = NOW.minusSeconds(20),
                updatedAt = NOW,
                finishedAt = null,
            ),
        )

        val response = requireNotNull(service.findCurrent(owner.id))
        assertEquals(stored.id, response.id)
        assertEquals(owner.id, response.accountId)
        assertEquals(profile.id, response.profileId)
        assertEquals("RUNNING", response.status)
        assertEquals(3, response.currentStepIndex)
        assertEquals("legacy read", response.message)
        assertNull(service.findCurrent(other.id))
    }

    private fun assertRetired(block: () -> Unit) {
        val error = assertFailsWith<ApiException>(block = block)
        assertEquals(ErrorCode.LEGACY_AUTOMATION_RETIRED, error.errorCode)
        assertEquals(org.springframework.http.HttpStatus.GONE, error.errorCode.status)
    }

    private fun savedAccount(loginId: String): HofAccountEntity =
        accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = NOW,
            ),
        )

    private fun savedProfile(account: HofAccountEntity): AutomationProfileEntity =
        profileRepository.save(
            AutomationProfileEntity(
                account = account,
                name = "보존 프로필",
                mode = "TIME_BURN",
                enabled = true,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-16T00:00:00Z")
        val ACTIVE_STATUSES = setOf("PENDING", "RUNNING", "WAITING_CAPTCHA", "WAITING_CONFIG", "WAITING_LOGIN", "PAUSED")
    }
}
