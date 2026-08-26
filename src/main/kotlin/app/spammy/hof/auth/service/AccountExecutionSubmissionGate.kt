package app.spammy.hof.auth.service

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.springframework.stereotype.Service

/**
 * 계정 인증 수명주기와 실제 원격 제출 시작점을 프로세스 내 계정별 게이트로 직렬화한다.
 *
 * 원격 호출은 DB transaction 없이 read lock 안에서 실행한다. 로그아웃이 write lock을 먼저 얻으면 이후
 * 제출은 폐기된 refresh-token family를 관찰해 거부되고, 제출이 먼저 시작되면 그 호출이 끝난 뒤
 * 로그아웃 transaction이 commit된다. 따라서 로그아웃 응답 이후 새 자동화 요청이 시작될 수 없다.
 */
interface AccountExecutionSubmissionGate {
    fun executeIfAuthorized(accountId: Long, submission: Runnable): Boolean

    fun executeLogout(accountId: Long, logout: Runnable)
}

@Service
class LockedAccountExecutionSubmissionGate(
    private val authorization: AccountExecutionAuthorizationReader,
) : AccountExecutionSubmissionGate {
    private val locks = Array(LOCK_STRIPES) { ReentrantReadWriteLock(true) }

    override fun executeIfAuthorized(accountId: Long, submission: Runnable): Boolean {
        return lock(accountId).read {
            if (!authorization.isExecutionAllowed(accountId)) return@read false
            submission.run()
            true
        }
    }

    override fun executeLogout(accountId: Long, logout: Runnable) {
        lock(accountId).write { logout.run() }
    }

    private fun lock(accountId: Long): ReentrantReadWriteLock =
        locks[Math.floorMod(accountId.hashCode(), locks.size)]

    private companion object {
        const val LOCK_STRIPES = 64
    }
}
