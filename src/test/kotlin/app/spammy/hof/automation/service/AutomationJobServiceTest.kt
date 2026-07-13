package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.dto.AutomationJobResponse
import app.spammy.hof.automation.dto.CreateAutomationJobRequest
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.entity.AutomationProfileMapEntity
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.automation.repository.AutomationJobRepository
import app.spammy.hof.automation.repository.AutomationProfileMapCommandRepository
import app.spammy.hof.automation.repository.AutomationProfileQueryRepository
import app.spammy.hof.automation.repository.AutomationProfileRepository
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.repository.BattleMapRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.repository.PartyPresetRepository
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@DataJpaTest
@ActiveProfiles("test")
@Import(
    QueryDslConfig::class,
    AutomationProfileQueryRepository::class,
    AutomationJobQueryRepository::class,
    AutomationJobService::class,
    AutomationJobServiceTest.ClockConfig::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutomationJobServiceTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var battleMapRepository: BattleMapRepository

    @Autowired
    private lateinit var presetRepository: PartyPresetRepository

    @Autowired
    private lateinit var profileRepository: AutomationProfileRepository

    @Autowired
    private lateinit var profileMapRepository: AutomationProfileMapCommandRepository

    @Autowired
    private lateinit var jobRepository: AutomationJobRepository

    @Autowired
    private lateinit var jobQueryRepository: AutomationJobQueryRepository

    @Autowired
    private lateinit var service: AutomationJobService

    @Autowired
    private lateinit var coordinatedTimeProvider: CoordinatedTimeProvider

    @Test
    fun createLinksAnExecutableOwnedProfileAndInitializesStructuredState() {
        val account = savedAccount("automation-job-create")
        val profile = savedExecutableProfile(account, "실행 프로필", "job-create-map")

        val response = service.create(account.id, CreateAutomationJobRequest(profile.id))

        assertEquals(account.id, response.accountId)
        assertEquals(profile.id, response.profileId)
        assertEquals("PENDING", response.status)
        assertEquals(0, response.currentStepIndex)
        assertEquals("생성됨. 실행 루프는 다음 단계에서 연결됩니다.", response.message)
        assertEquals(NOW.toString(), response.createdAt)
        assertNull(response.startedAt)
        assertEquals(NOW.toString(), response.updatedAt)
        assertNull(response.finishedAt)
        assertEquals(response, service.findCurrent(account.id))
    }

    @Test
    fun createRejectsAProfileOwnedByAnotherAccount() {
        val owner = savedAccount("automation-job-profile-owner")
        val requester = savedAccount("automation-job-profile-requester")
        val profile = savedExecutableProfile(owner, "다른 계정 프로필", "foreign-profile-map")

        val exception = assertFailsWith<ApiException> {
            service.create(requester.id, CreateAutomationJobRequest(profile.id))
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun createRejectsDisabledProfileAsInvalidRequest() {
        val account = savedAccount("automation-job-disabled")
        val profile = savedExecutableProfile(account, "비활성 프로필", "disabled-profile-map", enabled = false)

        val exception = assertFailsWith<ApiException> {
            service.create(account.id, CreateAutomationJobRequest(profile.id))
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
    }

    @Test
    fun createRejectsProfileWithoutMapsAsInvalidRequest() {
        val account = savedAccount("automation-job-empty")
        val profile = savedProfile(account, "빈 프로필")

        val exception = assertFailsWith<ApiException> {
            service.create(account.id, CreateAutomationJobRequest(profile.id))
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
    }

    @Test
    fun createRejectsProfileWithUnassignedPresetAsInvalidRequest() {
        val account = savedAccount("automation-job-null-preset")
        val profile = savedProfile(account, "프리셋 미지정")
        val battleMap = savedMap("null-preset-map")
        profileMapRepository.save(
            AutomationProfileMapEntity(
                profile = profile,
                battleMap = battleMap,
                partyPreset = null,
                executionOrder = 0,
            ),
        )

        val exception = assertFailsWith<ApiException> {
            service.create(account.id, CreateAutomationJobRequest(profile.id))
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
    }

    @Test
    fun createRejectsProfileWithDisabledStaticBattleMapAsInvalidRequest() {
        val account = savedAccount("automation-job-disabled-map")
        val profile = savedExecutableProfile(
            account = account,
            name = "비활성 맵 프로필",
            mapCode = "disabled-static-map",
            mapEnabled = false,
        )

        val exception = assertFailsWith<ApiException> {
            service.create(account.id, CreateAutomationJobRequest(profile.id))
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
    }

    @Test
    fun runningJobCanPauseResumeAndCancelWithStructuredTimestamps() {
        val account = savedAccount("automation-job-transitions")
        val profile = savedExecutableProfile(account, "상태 프로필", "transition-map")
        val created = service.create(account.id, CreateAutomationJobRequest(profile.id))
        setStatus(account.id, created.id, "RUNNING")

        val paused = service.pause(account.id, created.id)
        assertEquals("PAUSED", paused.status)
        assertEquals(NOW.toString(), paused.startedAt)
        assertNull(paused.finishedAt)

        val resumed = service.resume(account.id, created.id)
        assertEquals("RUNNING", resumed.status)
        assertEquals(NOW.toString(), resumed.startedAt)
        assertNull(resumed.finishedAt)

        val cancelled = service.cancel(account.id, created.id)
        assertEquals("CANCELLED", cancelled.status)
        assertEquals(NOW.toString(), cancelled.finishedAt)
        assertNull(service.findCurrent(account.id))
    }

    @Test
    fun waitingCaptchaJobCanPause() {
        val account = savedAccount("automation-job-waiting-pause")
        val profile = savedExecutableProfile(account, "캡차 프로필", "waiting-pause-map")
        val job = service.create(account.id, CreateAutomationJobRequest(profile.id))
        setStatus(account.id, job.id, "WAITING_CAPTCHA")

        assertEquals("PAUSED", service.pause(account.id, job.id).status)
    }

    @Test
    fun pauseRejectsPendingAndPausedJobs() {
        val account = savedAccount("automation-job-invalid-pause")
        val profile = savedExecutableProfile(account, "잘못된 정지", "invalid-pause-map")
        val job = service.create(account.id, CreateAutomationJobRequest(profile.id))

        assertInvalidRequest { service.pause(account.id, job.id) }
        setStatus(account.id, job.id, "PAUSED")
        assertInvalidRequest { service.pause(account.id, job.id) }
    }

    @Test
    fun resumeRejectsPendingAndTerminalJobs() {
        val account = savedAccount("automation-job-invalid-resume")
        val profile = savedExecutableProfile(account, "잘못된 재개", "invalid-resume-map")
        val job = service.create(account.id, CreateAutomationJobRequest(profile.id))

        assertInvalidRequest { service.resume(account.id, job.id) }
        service.cancel(account.id, job.id)
        assertInvalidRequest { service.resume(account.id, job.id) }
    }

    @Test
    fun cancelAcceptsEveryActiveStatusAndRejectsTerminalStatus() {
        ACTIVE_STATUSES.forEachIndexed { index, status ->
            val account = savedAccount("automation-job-cancel-active-$index")
            val profile = savedExecutableProfile(account, "취소 가능 $status", "cancel-active-map-$index")
            val job = service.create(account.id, CreateAutomationJobRequest(profile.id))
            setStatus(account.id, job.id, status)

            assertEquals("CANCELLED", service.cancel(account.id, job.id).status)
            assertInvalidRequest { service.cancel(account.id, job.id) }
        }
    }

    @Test
    fun createRejectsASecondActiveJobForTheSameAccount() {
        val account = savedAccount("automation-job-single-active")
        val firstProfile = savedExecutableProfile(account, "첫 번째 프로필", "single-active-map-1")
        val secondProfile = savedExecutableProfile(account, "두 번째 프로필", "single-active-map-2")
        service.create(account.id, CreateAutomationJobRequest(firstProfile.id))

        val exception = assertFailsWith<ApiException> {
            service.create(account.id, CreateAutomationJobRequest(secondProfile.id))
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
    }

    @Test
    fun concurrentCreatesAreSerializedAndOnlyOneActiveJobIsStored() {
        val account = savedAccount("automation-job-concurrent")
        val firstProfile = savedExecutableProfile(account, "동시 첫 번째", "concurrent-map-1")
        val secondProfile = savedExecutableProfile(account, "동시 두 번째", "concurrent-map-2")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = listOf(firstProfile, secondProfile).map { profile ->
                executor.submit<Result<AutomationJobResponse>> {
                    ready.countDown()
                    start.await(10, TimeUnit.SECONDS)
                    runCatching { service.create(account.id, CreateAutomationJobRequest(profile.id)) }
                }
            }
            ready.await(10, TimeUnit.SECONDS)
            start.countDown()
            val outcomes = futures.map { future -> future.get(10, TimeUnit.SECONDS) }

            assertEquals(1, outcomes.count { outcome -> outcome.isSuccess })
            val failure = outcomes.single { outcome -> outcome.isFailure }.exceptionOrNull() as ApiException
            assertEquals(ErrorCode.INVALID_REQUEST, failure.errorCode)
            assertEquals(1L, jobQueryRepository.countByAccountIdAndStatuses(account.id, ACTIVE_STATUSES))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentResumeAndCancelCannotResurrectATerminalJob() {
        val account = savedAccount("automation-job-concurrent-transition")
        val profile = savedExecutableProfile(account, "동시 상태 변경", "concurrent-transition-map")
        val job = service.create(account.id, CreateAutomationJobRequest(profile.id))
        setStatus(account.id, job.id, "PAUSED")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        coordinatedTimeProvider.armTransitionRace()

        try {
            val resume = executor.submit<Result<AutomationJobResponse>> {
                Thread.currentThread().name = "resume-request"
                start.await(10, TimeUnit.SECONDS)
                runCatching { service.resume(account.id, job.id) }
            }
            val cancel = executor.submit<Result<AutomationJobResponse>> {
                Thread.currentThread().name = "cancel-request"
                start.await(10, TimeUnit.SECONDS)
                runCatching { service.cancel(account.id, job.id) }
            }
            start.countDown()
            val outcomes = listOf(resume, cancel).map { future -> future.get(10, TimeUnit.SECONDS) }

            assertEquals(true, outcomes[1].isSuccess)
            outcomes.filter { outcome -> outcome.isFailure }.forEach { outcome ->
                assertEquals(ErrorCode.INVALID_REQUEST, (outcome.exceptionOrNull() as ApiException).errorCode)
            }
            val stored = requireNotNull(jobQueryRepository.findOwnedByAccountIdAndId(account.id, job.id))
            assertEquals("CANCELLED", stored.status)
            assertEquals(NOW, stored.finishedAt)
        } finally {
            coordinatedTimeProvider.disarm()
            executor.shutdownNow()
        }
    }

    @Test
    fun statusChangeTreatsAnotherAccountsJobAsNotFound() {
        val owner = savedAccount("automation-job-owner")
        val requester = savedAccount("automation-job-requester")
        val profile = savedExecutableProfile(owner, "소유 프로필", "owned-job-map")
        val job = service.create(owner.id, CreateAutomationJobRequest(profile.id))

        val exception = assertFailsWith<ApiException> {
            service.pause(requester.id, job.id)
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
    }

    private fun setStatus(
        accountId: Long,
        jobId: Long,
        status: String,
    ) {
        val job = requireNotNull(jobQueryRepository.findOwnedByAccountIdAndId(accountId, jobId))
        job.status = status
        if (status == "RUNNING" || status == "WAITING_CAPTCHA") job.startedAt = NOW
        jobRepository.save(job)
    }

    private fun assertInvalidRequest(block: () -> Unit) {
        val exception = assertFailsWith<ApiException>(block = block)
        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
    }

    private fun savedAccount(loginId: String): HofAccountEntity =
        accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = NOW,
            ),
        )

    private fun savedExecutableProfile(
        account: HofAccountEntity,
        name: String,
        mapCode: String,
        enabled: Boolean = true,
        mapEnabled: Boolean = true,
    ): AutomationProfileEntity {
        val profile = savedProfile(account, name, enabled)
        val battleMap = savedMap(mapCode, enabled = mapEnabled)
        val preset = savedPreset(account, "$name 파티")
        profileMapRepository.save(
            AutomationProfileMapEntity(
                profile = profile,
                battleMap = battleMap,
                partyPreset = preset,
                executionOrder = 0,
            ),
        )
        return profile
    }

    private fun savedProfile(
        account: HofAccountEntity,
        name: String,
        enabled: Boolean = true,
    ): AutomationProfileEntity =
        profileRepository.save(
            AutomationProfileEntity(
                account = account,
                name = name,
                mode = "TIME_BURN",
                enabled = enabled,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

    private fun savedMap(
        mapCode: String,
        enabled: Boolean = true,
    ): BattleMapEntity =
        battleMapRepository.save(
            BattleMapEntity(
                categoryId = "battle_map",
                mapCode = mapCode,
                name = mapCode,
                normalizedName = mapCode,
                enabled = enabled,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

    private fun savedPreset(
        account: HofAccountEntity,
        name: String,
    ): PartyPresetEntity =
        presetRepository.save(
            PartyPresetEntity(
                account = account,
                name = name,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

    @TestConfiguration
    class ClockConfig {
        @Bean
        fun timeProvider(): CoordinatedTimeProvider = CoordinatedTimeProvider()
    }

    class CoordinatedTimeProvider : TimeProvider {
        @Volatile
        private var race: TransitionRace? = null

        override fun now(): Instant {
            val activeRace = race ?: return NOW
            when (Thread.currentThread().name) {
                "resume-request" -> {
                    activeRace.resumeRead.countDown()
                    activeRace.allowResume.await(1, TimeUnit.SECONDS)
                    Thread.sleep(200)
                }

                "cancel-request" -> {
                    activeRace.resumeRead.await(1, TimeUnit.SECONDS)
                    activeRace.allowResume.countDown()
                }
            }
            return NOW
        }

        fun armTransitionRace() {
            race = TransitionRace()
        }

        fun disarm() {
            race = null
        }
    }

    private class TransitionRace(
        val resumeRead: CountDownLatch = CountDownLatch(1),
        val allowResume: CountDownLatch = CountDownLatch(1),
    )

    private companion object {
        val ACTIVE_STATUSES = listOf("PENDING", "RUNNING", "WAITING_CAPTCHA", "PAUSED")
        val NOW: Instant = Instant.parse("2026-07-12T00:00:00Z")
    }
}
