package app.spammy.hof.town.common.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import app.spammy.hof.external.client.timeHofStage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** 같은 계정의 HOF 변경과 로컬 identity 변경이 서로의 검증·반영 사이에 끼어들지 않게 한다. */
@Component
class AccountHofMutationFence {
    private val log = LoggerFactory.getLogger(javaClass)
    private val states = ConcurrentHashMap<Long, AccountFenceState>()

    /** DB 잠금 해제 뒤 실제 세션 변경에 앞선 form·쿠키 관측을 무효화한다. rollback도 동일하다. */
    fun invalidateAfterCompletion(accountId: Long) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) { execute(accountId) {} }
                },
            )
        } else {
            execute(accountId) {}
        }
    }

    fun <T> execute(accountId: Long, action: () -> T): T {
        val state = state(accountId)
        return withTimedLock(accountId, state) {
            try {
                action()
            } finally {
                state.revision += 1
            }
        }
    }

    /** 읽기와 revision 취득을 직렬화해 이후 제출 전 끼어든 mutation을 검출한다. */
    fun <T> observe(accountId: Long, observation: () -> T): AccountHofObservation<T> {
        val state = state(accountId)
        return withTimedLock(accountId, state) {
            AccountHofObservation(observation(), state.revision)
        }
    }

    /** 관측 뒤 다른 계정 mutation이 없을 때만 그 관측에 결합된 행동을 실행한다. */
    fun <T> executeObserved(accountId: Long, revision: Long, action: () -> T): T {
        val state = state(accountId)
        return withTimedLock(accountId, state) {
            if (state.revision != revision) throw AccountHofObservationInvalidatedException()
            try {
                action()
            } finally {
                state.revision += 1
            }
        }
    }

    private fun <T> withTimedLock(accountId: Long, state: AccountFenceState, action: () -> T): T {
        timeHofStage(log, accountId, "fence.wait") { state.lock.lock() }
        return try { timeHofStage(log, accountId, "fence.held", action) } finally { state.lock.unlock() }
    }

    private fun state(accountId: Long): AccountFenceState =
        states.computeIfAbsent(accountId) { AccountFenceState(ReentrantLock(true)) }

    private class AccountFenceState(
        val lock: ReentrantLock,
        var revision: Long = 0,
    )
}

data class AccountHofObservation<T>(val value: T, val revision: Long)

class AccountHofObservationInvalidatedException :
    RuntimeException("관측 뒤 같은 계정의 HOF 상태 변경이 발생했습니다.")
