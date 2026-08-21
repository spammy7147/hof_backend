package app.spammy.hof.town.common.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.springframework.stereotype.Component

/** 같은 계정의 HOF 변경과 로컬 identity 변경이 서로의 검증·반영 사이에 끼어들지 않게 한다. */
@Component
class AccountHofMutationFence {
    private val locks = ConcurrentHashMap<Long, ReentrantLock>()

    fun <T> execute(accountId: Long, action: () -> T): T =
        locks.computeIfAbsent(accountId) { ReentrantLock(true) }.withLock(action)
}
