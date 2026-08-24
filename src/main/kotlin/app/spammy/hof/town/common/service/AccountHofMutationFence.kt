package app.spammy.hof.town.common.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.springframework.stereotype.Component

/** 같은 계정의 HOF 변경과 로컬 identity 변경이 서로의 검증·반영 사이에 끼어들지 않게 한다. */
@Component
class AccountHofMutationFence {
    private val states = ConcurrentHashMap<Long, AccountFenceState>()

    fun <T> execute(accountId: Long, action: () -> T): T {
        val state = state(accountId)
        return state.lock.withLock {
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
        return state.lock.withLock {
            AccountHofObservation(observation(), state.revision)
        }
    }

    /** 관측 뒤 다른 계정 mutation이 없을 때만 그 관측에 결합된 행동을 실행한다. */
    fun <T> executeObserved(accountId: Long, revision: Long, action: () -> T): T {
        val state = state(accountId)
        return state.lock.withLock {
            if (state.revision != revision) throw AccountHofObservationInvalidatedException()
            try {
                action()
            } finally {
                state.revision += 1
            }
        }
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
