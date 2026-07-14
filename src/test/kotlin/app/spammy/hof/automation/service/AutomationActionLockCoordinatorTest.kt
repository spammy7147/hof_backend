package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationActionRunEntity
import app.spammy.hof.automation.entity.AutomationActionStatus
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.repository.AutomationActionLockTarget
import app.spammy.hof.automation.repository.AutomationActionRunQueryRepository
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.mockito.Mockito

class AutomationActionLockCoordinatorTest {
    private val jobQueryRepository = Mockito.mock(AutomationJobQueryRepository::class.java)
    private val actionQueryRepository = Mockito.mock(AutomationActionRunQueryRepository::class.java)
    private val coordinator = AutomationActionLockCoordinator(jobQueryRepository, actionQueryRepository)

    @Test
    fun `locks job before action after resolving the target without a write lock`() {
        val action = action()
        val target = AutomationActionLockTarget(action.job.id, action.job.account.id)
        Mockito.`when`(actionQueryRepository.findLockTargetById(action.id)).thenReturn(target)
        Mockito.`when`(
            jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(target.accountId, target.jobId),
        ).thenReturn(action.job)
        Mockito.`when`(actionQueryRepository.findByIdForUpdate(action.id)).thenReturn(action)

        val locked = coordinator.lock(action.id)

        assertEquals(action.job, locked?.job)
        assertEquals(action, locked?.action)
        val order = Mockito.inOrder(actionQueryRepository, jobQueryRepository)
        order.verify(actionQueryRepository).findLockTargetById(action.id)
        order.verify(jobQueryRepository).findOwnedByAccountIdAndIdForUpdate(target.accountId, target.jobId)
        order.verify(actionQueryRepository).findByIdForUpdate(action.id)
    }

    @Test
    fun `rejects an action whose ownership changed after the target lookup`() {
        val action = action()
        val target = AutomationActionLockTarget(action.job.id, action.job.account.id)
        val anotherJob = job(id = 12L)
        action.job = anotherJob
        Mockito.`when`(actionQueryRepository.findLockTargetById(action.id)).thenReturn(target)
        Mockito.`when`(
            jobQueryRepository.findOwnedByAccountIdAndIdForUpdate(target.accountId, target.jobId),
        ).thenReturn(job(id = target.jobId))
        Mockito.`when`(actionQueryRepository.findByIdForUpdate(action.id)).thenReturn(action)

        assertNull(coordinator.lock(action.id))
    }

    private fun action() = AutomationActionRunEntity(
        id = 91L,
        job = job(),
        moduleType = AutomationModuleType.TIME_BURN,
        actionType = "RUN_BATTLE",
        actionKey = "map",
        status = AutomationActionStatus.RUNNING,
        requestKey = "job:11:step:0",
        payloadJson = "{}",
        attemptCount = 1,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun job(id: Long = 11L): AutomationJobEntity {
        val account = HofAccountEntity(
            id = 7L,
            loginId = "lock-order",
            encryptedPassword = "encrypted",
            createdAt = NOW,
        )
        val profile = AutomationProfileEntity(
            id = 3L,
            account = account,
            name = "통합 자동화",
            mode = "UNIFIED",
            enabled = true,
            createdAt = NOW,
            updatedAt = NOW,
        )
        return AutomationJobEntity(
            id = id,
            account = account,
            profile = profile,
            status = "RUNNING",
            currentStepIndex = 0,
            message = null,
            createdAt = NOW,
            startedAt = NOW,
            updatedAt = NOW,
            finishedAt = null,
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T06:00:00Z")
    }
}
