package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.captcha.repository.CaptchaPassMaintenanceQueryRepository
import app.spammy.hof.captcha.repository.CaptchaChallengeRepository
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.junit.jupiter.api.BeforeEach

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    CaptchaPassMaintenanceQueryRepository::class,
    CaptchaChallengeParser::class,
    CaptchaPassMaintenanceService::class,
    CaptchaPassMaintenanceServiceTest.Config::class,
)
class CaptchaPassMaintenanceServiceTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var service: CaptchaPassMaintenanceService
    @Autowired private lateinit var challenges: CaptchaChallengeRepository
    @Autowired private lateinit var clock: TestClock

    @BeforeEach
    fun resetClock() {
        clock.current = RESPONSE_OBSERVED_AT
    }

    @Test
    fun `active countdown from a complete menu becomes the authoritative account pass state`() {
        val account = account("pass-active")

        assertTrue(
            service.observe(
                account.id,
                "<div id='menu'>Top | 0:26:09 | 전투</div>",
                REQUEST_STARTED_AT,
                RESPONSE_OBSERVED_AT,
            ),
        )

        val actual = service.get(account.id)
        assertEquals("VALID", actual.passState)
        assertEquals(1_569, actual.remainingSeconds)
        assertEquals(RESPONSE_OBSERVED_AT.plusSeconds(1_569), actual.validUntil)
        assertEquals(REQUEST_STARTED_AT, actual.observedAt)
    }

    @Test
    fun `red pass marker records that a pass is required`() {
        val account = account("pass-required")

        assertTrue(
            service.observe(
                account.id,
                "<div id='menu'><font color='red'>통행증</font></div>",
                REQUEST_STARTED_AT,
                RESPONSE_OBSERVED_AT,
            ),
        )

        val actual = service.get(account.id)
        assertEquals("REQUIRED", actual.passState)
        assertNull(actual.remainingSeconds)
        assertNull(actual.validUntil)
    }

    @Test
    fun `partial response and an older complete response preserve the latest state`() {
        val account = account("pass-order")
        service.observe(
            account.id,
            "<div id='menu'>Top | 0:20:00 | 전투</div>",
            REQUEST_STARTED_AT.plusSeconds(2),
            RESPONSE_OBSERVED_AT.plusSeconds(2),
        )

        assertFalse(
            service.observe(
                account.id,
                "<html>captcha response without menu</html>",
                REQUEST_STARTED_AT.plusSeconds(3),
                RESPONSE_OBSERVED_AT.plusSeconds(3),
            ),
        )
        assertFalse(
            service.observe(
                account.id,
                "<div id='menu'>Top | 0:29:00 | 전투</div>",
                REQUEST_STARTED_AT,
                RESPONSE_OBSERVED_AT.plusSeconds(4),
            ),
        )

        val actual = service.get(account.id)
        assertEquals(1_202, actual.remainingSeconds)
        assertEquals(REQUEST_STARTED_AT.plusSeconds(2), actual.observedAt)
    }

    @Test
    fun `an account without an observation is returned as unknown and enabled`() {
        val account = account("pass-unknown")

        val actual = service.get(account.id)

        assertTrue(actual.enabled)
        assertEquals("UNKNOWN", actual.passState)
        assertNull(actual.remainingSeconds)
        assertNull(actual.observedAt)
    }

    @Test
    fun `account setting stops future refreshes and enabling it schedules an immediate check`() {
        val account = account("pass-setting")

        val disabled = service.setEnabled(account.id, false)
        assertFalse(disabled.enabled)
        assertNull(disabled.nextRefreshAt)

        val enabled = service.setEnabled(account.id, true)
        assertTrue(enabled.enabled)
        assertEquals(RESPONSE_OBSERVED_AT, enabled.nextRefreshAt)
    }

    @Test
    fun `a due refresh is leased once and network retries follow one five fifteen minute backoff`() {
        val account = account("pass-lease")
        service.setEnabled(account.id, true)

        val first = service.claimDue(account.id)
        kotlin.test.assertNotNull(first)
        assertNull(service.claimDue(account.id))

        service.scheduleRetry(account.id, first.token, "network")
        assertEquals(RESPONSE_OBSERVED_AT.plusSeconds(60), service.get(account.id).nextRefreshAt)

        clock.current = RESPONSE_OBSERVED_AT.plusSeconds(60)
        val second = kotlin.test.assertNotNull(service.claimDue(account.id))
        service.scheduleRetry(account.id, second.token, "network")
        assertEquals(RESPONSE_OBSERVED_AT.plusSeconds(60 + 300), service.get(account.id).nextRefreshAt)

        clock.current = RESPONSE_OBSERVED_AT.plusSeconds(60 + 300)
        val third = kotlin.test.assertNotNull(service.claimDue(account.id))
        service.scheduleRetry(account.id, third.token, "network")
        assertEquals(RESPONSE_OBSERVED_AT.plusSeconds(60 + 300 + 900), service.get(account.id).nextRefreshAt)
    }

    @Test
    fun `an unrelated required observation does not collapse an active connection backoff`() {
        val account = account("pass-backoff-observation")
        service.setEnabled(account.id, true)
        val claim = kotlin.test.assertNotNull(service.claimDue(account.id))
        service.scheduleRetry(account.id, claim.token, "network")
        val retryAt = service.get(account.id).nextRefreshAt

        service.observe(
            account.id,
            "<div id='menu'><font color='red'>통행증</font></div>",
            REQUEST_STARTED_AT.plusSeconds(10),
            RESPONSE_OBSERVED_AT.plusSeconds(10),
        )

        assertEquals(retryAt, service.get(account.id).nextRefreshAt)
    }

    @Test
    fun `answer confirmation failure closes an active lease and schedules one minute retry`() {
        val account = account("pass-confirmation-retry")
        service.setEnabled(account.id, true)
        val claim = kotlin.test.assertNotNull(service.claimDue(account.id))
        assertTrue(service.beginSubmission(account.id, claim.token))

        service.scheduleConfirmationRetry(account.id, "pass-not-confirmed")

        val actual = service.get(account.id)
        assertEquals(RESPONSE_OBSERVED_AT.plusSeconds(60), actual.nextRefreshAt)
        assertEquals(
            app.spammy.hof.captcha.dto.CaptchaPassMaintenanceLifecycleState.CONNECTION_RETRY_WAIT,
            actual.lifecycleState,
        )
        assertFalse(service.beginReconciliation(account.id, claim.token))
    }

    @Test
    fun `terminal handoff with no next time is not mistaken for an uninitialized bootstrap row`() {
        val account = account("pass-terminal")
        service.setEnabled(account.id, true)
        val claim = kotlin.test.assertNotNull(service.claimDue(account.id))

        service.finishOcrConfigurationRequired(account.id, claim.token)

        assertFalse(service.findDueAccountIds(RESPONSE_OBSERVED_AT.plusSeconds(600)).contains(account.id))
    }

    @Test
    fun `pass required observations do not restart a parked manual handoff`() {
        val account = account("pass-manual-terminal")
        service.setEnabled(account.id, true)
        val claim = kotlin.test.assertNotNull(service.claimDue(account.id))
        val challenge = challenges.save(
            CaptchaChallengeEntity(
                account = account,
                status = "READY",
                prompt = CaptchaChallengeParser.VIGILANTE_PASS_PROMPT,
                challengeKind = CaptchaChallengeEntity.KIND_VIGILANTE_PASS,
                imageUrl = null,
                sourceUrl = "https://hof.example/index.php",
                answer = null,
                createdAt = RESPONSE_OBSERVED_AT,
                answeredAt = null,
            ),
        )
        service.finishManualRequired(account.id, claim.token, challenge.id)

        service.observe(
            account.id,
            "<div id='menu'><font color='red'>통행증</font></div>",
            REQUEST_STARTED_AT.plusSeconds(10),
            RESPONSE_OBSERVED_AT.plusSeconds(10),
        )

        val actual = service.get(account.id)
        assertEquals(app.spammy.hof.captcha.dto.CaptchaPassMaintenanceLastResult.MANUAL_REQUIRED, actual.lastResult)
        assertEquals(challenge.id, actual.manualChallengeId)
        assertNull(actual.nextRefreshAt)
    }

    @Test
    fun `authentication suspension discards a prepared run before remote submission`() {
        val account = account("pass-auth-prepared")
        service.setEnabled(account.id, true)
        val claim = kotlin.test.assertNotNull(service.claimDue(account.id))

        service.suspendForAuthentication(account.id)

        assertFalse(service.beginSubmission(account.id, claim.token))
        assertTrue(service.get(account.id).authSuspended)
        assertNull(service.get(account.id).nextRefreshAt)
    }

    @Test
    fun `OCR has its own lifecycle phase and remains pre-submit for authentication cancellation`() {
        val account = account("pass-auth-recognizing")
        service.setEnabled(account.id, true)
        val claim = kotlin.test.assertNotNull(service.claimDue(account.id))

        assertTrue(service.beginRecognition(account.id, claim.token))
        assertEquals(
            app.spammy.hof.captcha.dto.CaptchaPassMaintenanceLifecycleState.AUTO_RECOGNIZING,
            service.get(account.id).lifecycleState,
        )

        service.suspendForAuthentication(account.id)

        assertFalse(service.authorizeSubmission(account.id, claim.token))
        assertNull(service.get(account.id).nextRefreshAt)
    }

    @Test
    fun `authentication suspension lets an already submitting run reconcile its remote result`() {
        val account = account("pass-auth-submitting")
        service.setEnabled(account.id, true)
        val claim = kotlin.test.assertNotNull(service.claimDue(account.id))
        assertTrue(service.beginSubmission(account.id, claim.token))

        service.suspendForAuthentication(account.id)

        assertTrue(service.beginReconciliation(account.id, claim.token))
        service.finishSuspended(account.id, claim.token)
        assertTrue(service.get(account.id).authSuspended)
    }

    @Test
    fun `a second answer is not authorized after authentication suspension`() {
        val account = account("pass-auth-between-submissions")
        service.setEnabled(account.id, true)
        val claim = kotlin.test.assertNotNull(service.claimDue(account.id))
        assertTrue(service.authorizeSubmission(account.id, claim.token))

        service.suspendForAuthentication(account.id)

        assertFalse(service.authorizeSubmission(account.id, claim.token))
        service.finishCancelled(account.id, claim.token)
        assertTrue(service.get(account.id).authSuspended)
        assertNull(service.get(account.id).nextRefreshAt)
    }

    private fun account(loginId: String): HofAccountEntity = accounts.save(
        HofAccountEntity(loginId = loginId, encryptedPassword = "encrypted", createdAt = REQUEST_STARTED_AT),
    )

    private companion object {
        val REQUEST_STARTED_AT: Instant = Instant.parse("2026-08-26T10:00:00Z")
        val RESPONSE_OBSERVED_AT: Instant = REQUEST_STARTED_AT.plusSeconds(1)
    }

    @TestConfiguration
    class Config {
        @Bean
        fun timeProvider(): TestClock = TestClock()
    }

    class TestClock(var current: Instant = RESPONSE_OBSERVED_AT) : TimeProvider {
        override fun now(): Instant = current
    }
}
