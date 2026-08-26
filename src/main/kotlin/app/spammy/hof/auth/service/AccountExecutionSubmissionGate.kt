package app.spammy.hof.auth.service

import java.sql.Connection
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.sql.DataSource
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * 계정 인증 수명주기와 실제 원격 제출 시작점을 계정별 shared/exclusive lock으로 직렬화한다.
 *
 * 원격 호출은 DB transaction 없이 shared lock 안에서 실행한다. 로그아웃이 exclusive lock을 먼저 얻으면 이후
 * 제출은 폐기된 refresh-token family를 관찰해 거부되고, 제출이 먼저 시작되면 그 호출이 끝난 뒤
 * 로그아웃 transaction이 commit된다. 따라서 로그아웃 응답 이후 새 자동화 요청이 시작될 수 없다.
 */
interface AccountExecutionSubmissionGate {
    fun executeIfAuthorized(accountId: Long, submission: Runnable): Boolean

    fun executeLogout(accountId: Long, logout: Runnable)
}

/** 운영 PostgreSQL에서는 session advisory lock, H2 테스트에서는 같은 의미의 프로세스 lock을 제공한다. */
interface AccountExecutionLock {
    fun executeShared(accountId: Long, action: Runnable)

    fun executeExclusive(accountId: Long, action: Runnable)
}

@Component
class PostgreSqlAdvisoryAccountExecutionLock(
    private val dataSource: DataSource,
) : AccountExecutionLock {
    override fun executeShared(accountId: Long, action: Runnable) {
        execute(accountId, shared = true, action)
    }

    override fun executeExclusive(accountId: Long, action: Runnable) {
        execute(accountId, shared = false, action)
    }

    private fun execute(accountId: Long, shared: Boolean, action: Runnable) {
        dataSource.connection.use { connection ->
            if (!connection.metaData.databaseProductName.equals(POSTGRESQL, ignoreCase = true)) {
                fallback(accountId, shared, action)
                return
            }
            val lockKey = accountId xor LOCK_NAMESPACE
            executeLockFunction(connection, if (shared) LOCK_SHARED_SQL else LOCK_EXCLUSIVE_SQL, lockKey)
            var actionFailure: Throwable? = null
            try {
                action.run()
            } catch (error: Throwable) {
                actionFailure = error
                throw error
            } finally {
                try {
                    val released = executeLockFunction(
                        connection,
                        if (shared) UNLOCK_SHARED_SQL else UNLOCK_EXCLUSIVE_SQL,
                        lockKey,
                        returnsBoolean = true,
                    )
                    check(released) { "PostgreSQL account execution advisory lock was not held." }
                } catch (unlockError: Throwable) {
                    runCatching { connection.abort(DIRECT_EXECUTOR) }
                        .onFailure(unlockError::addSuppressed)
                    if (actionFailure == null) throw unlockError
                    actionFailure.addSuppressed(unlockError)
                }
            }
        }
    }

    private fun executeLockFunction(
        connection: Connection,
        sql: String,
        lockKey: Long,
        returnsBoolean: Boolean = false,
    ): Boolean = connection.prepareStatement(sql).use { statement ->
        statement.setLong(1, lockKey)
        statement.executeQuery().use { result ->
            check(result.next()) { "PostgreSQL advisory lock function returned no row." }
            !returnsBoolean || result.getBoolean(1)
        }
    }

    private fun fallback(accountId: Long, shared: Boolean, action: Runnable) {
        val lock = FALLBACK_LOCKS[Math.floorMod(accountId.hashCode(), FALLBACK_LOCKS.size)]
        if (shared) lock.read { action.run() } else lock.write { action.run() }
    }

    private companion object {
        const val POSTGRESQL = "PostgreSQL"
        const val LOCK_NAMESPACE = 0x484F465F45584543L
        const val LOCK_SHARED_SQL = "select pg_advisory_lock_shared(?)"
        const val LOCK_EXCLUSIVE_SQL = "select pg_advisory_lock(?)"
        const val UNLOCK_SHARED_SQL = "select pg_advisory_unlock_shared(?)"
        const val UNLOCK_EXCLUSIVE_SQL = "select pg_advisory_unlock(?)"
        val FALLBACK_LOCKS = Array(64) { ReentrantReadWriteLock(true) }
        val DIRECT_EXECUTOR = Executor(Runnable::run)
    }
}

@Service
class LockedAccountExecutionSubmissionGate(
    private val authorization: AccountExecutionAuthorizationReader,
    private val executionLock: AccountExecutionLock,
) : AccountExecutionSubmissionGate {
    override fun executeIfAuthorized(accountId: Long, submission: Runnable): Boolean {
        var authorized = false
        executionLock.executeShared(accountId, Runnable {
            if (!authorization.isExecutionAllowed(accountId)) return@Runnable
            submission.run()
            authorized = true
        })
        return authorized
    }

    override fun executeLogout(accountId: Long, logout: Runnable) {
        executionLock.executeExclusive(accountId, logout)
    }
}
