package app.spammy.hof.character.command

import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.service.UnifiedAutomationService
import app.spammy.hof.character.service.CharacterOperationAutomation
import app.spammy.hof.town.common.service.AccountHofMutationFence
import java.time.Duration
import org.springframework.stereotype.Service

fun interface CharacterCommandPauseWaiter {
    fun waitFor(duration: Duration)
}

@Service
class ThreadSleepCharacterCommandPauseWaiter : CharacterCommandPauseWaiter {
    override fun waitFor(duration: Duration) {
        try {
            Thread.sleep(duration.toMillis())
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("자동화 일시정지를 기다리던 중 중단되었습니다.", error)
        }
    }
}

/** 실행 중 보호 행동의 완료를 기다려 자동화를 PAUSED로 만든 뒤 명령 후 원래 RUNNING 상태로 복원한다. */
@Service
class TypedAutomationCharacterCommandBridge(
    private val automation: UnifiedAutomationService,
    private val waiter: CharacterCommandPauseWaiter,
    private val jobs: CharacterOperationAutomation,
    private val mutationFence: AccountHofMutationFence,
) : CharacterAutomationGate {
    override fun <T> executeJob(accountId: Long, jobId: Long, unavailable: () -> T, operation: () -> T): T {
        jobs.begin(accountId, jobId)
        try {
            repeat(MAX_POLLS) {
                if (jobs.isReady(accountId)) return operation()
                waiter.waitFor(POLL_INTERVAL)
            }
            return unavailable()
        } finally {
            jobs.finish(accountId, jobId)
        }
    }

    override fun <T> execute(accountId: Long, unavailable: () -> T, operation: () -> T): T {
        jobs.requireAvailable(accountId)
        fun guardedOperation(): T = mutationFence.execute(accountId) {
            jobs.requireAvailable(accountId)
            operation()
        }
        val initial = automation.getTyped(accountId).runtime.lifecycle
        if (initial !in setOf(TypedAutomationLifecycle.RUNNING, TypedAutomationLifecycle.DRAINING)) return guardedOperation()

        val resumeAfterOperation = initial == TypedAutomationLifecycle.RUNNING
        if (resumeAfterOperation) automation.pauseTyped(accountId)
        repeat(MAX_POLLS) {
            when (automation.getTyped(accountId).runtime.lifecycle) {
                TypedAutomationLifecycle.PAUSED -> {
                    return try {
                        guardedOperation()
                    } finally {
                        if (resumeAfterOperation) automation.resumeTyped(accountId)
                    }
                }
                TypedAutomationLifecycle.DRAINING -> waiter.waitFor(POLL_INTERVAL)
                else -> return unavailable()
            }
        }
        return unavailable()
    }

    private companion object {
        const val MAX_POLLS = 300
        val POLL_INTERVAL: Duration = Duration.ofMillis(100)
    }
}
