package app.spammy.hof.automation.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.common.persistence.QueryDslConfig
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, AutomationJobQueryRepository::class)
class AutomationJobQueryRepositoryTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var profileRepository: AutomationProfileRepository

    @Autowired
    private lateinit var jobRepository: AutomationJobRepository

    @Autowired
    private lateinit var queryRepository: AutomationJobQueryRepository

    @Test
    fun readsOwnedJobAndSelectsTheNewestActiveJobWithAnIdTieBreaker() {
        val account = savedAccount("automation-job-query")
        val otherAccount = savedAccount("automation-job-query-other")
        val profile = savedProfile(account, "실행 프로필")
        val unusedProfile = savedProfile(account, "미실행 프로필")
        val foreignProfile = savedProfile(otherAccount, "다른 계정 프로필")
        savedJob(account, profile, "RUNNING", EARLIER)
        val firstTie = savedJob(account, profile, "PAUSED", LATER)
        val secondTie = savedJob(account, profile, "PENDING", LATER)
        savedJob(account, profile, "CANCELLED", FINISHED)
        val foreign = savedJob(otherAccount, foreignProfile, "RUNNING", FINISHED)

        assertEquals(
            secondTie.id,
            assertNotNull(queryRepository.findCurrentByAccountIdAndStatuses(account.id, ACTIVE_STATUSES)).id,
        )
        assertEquals(firstTie.id, assertNotNull(queryRepository.findOwnedByAccountIdAndId(account.id, firstTie.id)).id)
        assertNull(queryRepository.findOwnedByAccountIdAndId(otherAccount.id, firstTie.id))
        assertNull(queryRepository.findOwnedByAccountIdAndId(account.id, foreign.id))
        assertNull(queryRepository.findCurrentByAccountIdAndStatuses(account.id, emptyList()))
        assertTrue(queryRepository.existsByProfileId(profile.id))
        assertFalse(queryRepository.existsByProfileId(unusedProfile.id))
        assertEquals(3L, queryRepository.countByAccountIdAndStatuses(account.id, ACTIVE_STATUSES))
        assertEquals(0L, queryRepository.countByAccountIdAndStatuses(account.id, emptyList()))
    }

    private fun savedAccount(loginId: String): HofAccountEntity =
        accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = CREATED_AT,
            ),
        )

    private fun savedProfile(
        account: HofAccountEntity,
        name: String,
    ): AutomationProfileEntity =
        profileRepository.save(
            AutomationProfileEntity(
                account = account,
                name = name,
                mode = "TIME_BURN",
                enabled = true,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )

    private fun savedJob(
        account: HofAccountEntity,
        profile: AutomationProfileEntity,
        status: String,
        updatedAt: Instant,
    ): AutomationJobEntity =
        jobRepository.save(
            AutomationJobEntity(
                account = account,
                profile = profile,
                status = status,
                currentStepIndex = 0,
                message = null,
                createdAt = CREATED_AT,
                startedAt = null,
                updatedAt = updatedAt,
                finishedAt = null,
            ),
        )

    private companion object {
        val ACTIVE_STATUSES = listOf("PENDING", "RUNNING", "WAITING_CAPTCHA", "PAUSED")
        val CREATED_AT: Instant = Instant.parse("2026-07-12T00:00:00Z")
        val EARLIER: Instant = Instant.parse("2026-07-12T01:00:00Z")
        val LATER: Instant = Instant.parse("2026-07-12T02:00:00Z")
        val FINISHED: Instant = Instant.parse("2026-07-12T03:00:00Z")
    }
}
