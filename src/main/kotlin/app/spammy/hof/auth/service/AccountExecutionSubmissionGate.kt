package app.spammy.hof.auth.service

import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
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
    private val connectionPermits = Semaphore(resolveConnectionPermitCount(dataSource), true)
    private val postgres by lazy {
        dataSource.connection.use { connection ->
            connection.metaData.databaseProductName.equals(POSTGRESQL, ignoreCase = true)
        }
    }

    override fun executeShared(accountId: Long, action: Runnable) {
        execute(accountId, shared = true, action)
    }

    override fun executeExclusive(accountId: Long, action: Runnable) {
        execute(accountId, shared = false, action)
    }

    private fun execute(accountId: Long, shared: Boolean, action: Runnable) {
        if (!postgres) {
            fallback(accountId, shared, action)
            return
        }
        acquireConnectionPermit()
        try {
            dataSource.connection.use { connection ->
                val lockKey = accountId xor LOCK_NAMESPACE
                try {
                    executeLockFunction(connection, if (shared) LOCK_SHARED_SQL else LOCK_EXCLUSIVE_SQL, lockKey)
                } catch (lockError: Throwable) {
                    abortConnection(connection, lockError)
                    throw lockError
                }
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
                        abortConnection(connection, unlockError)
                        if (actionFailure == null) throw unlockError
                        actionFailure.addSuppressed(unlockError)
                    }
                }
            }
        } finally {
            connectionPermits.release()
        }
    }

    private fun acquireConnectionPermit() {
        try {
            connectionPermits.acquire()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for an account execution lock connection.", error)
        }
    }

    private fun abortConnection(connection: Connection, failure: Throwable) {
        runCatching { connection.abort(DIRECT_EXECUTOR) }
            .onFailure(failure::addSuppressed)
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

        fun resolveConnectionPermitCount(dataSource: DataSource): Int {
            val configuredPoolSize = runCatching {
                when {
                    dataSource is HikariDataSource -> dataSource.maximumPoolSize
                    dataSource.isWrapperFor(HikariDataSource::class.java) ->
                        dataSource.unwrap(HikariDataSource::class.java).maximumPoolSize
                    else -> UNKNOWN_POOL_SAFE_SIZE
                }
            }.getOrDefault(UNKNOWN_POOL_SAFE_SIZE)
            val maximumPoolSize = configuredPoolSize.takeIf { it > 0 } ?: HIKARI_DEFAULT_POOL_SIZE
            require(maximumPoolSize >= MINIMUM_POOL_SIZE) {
                "Account execution advisory locks require a database pool of at least $MINIMUM_POOL_SIZE connections."
            }
            return ((maximumPoolSize - RESERVED_CONNECTIONS) / CONNECTIONS_PER_GATE)
                .coerceAtLeast(1)
        }

        const val MINIMUM_POOL_SIZE = 2
        const val HIKARI_DEFAULT_POOL_SIZE = 10
        const val UNKNOWN_POOL_SAFE_SIZE = 2
        const val RESERVED_CONNECTIONS = 1
        const val CONNECTIONS_PER_GATE = 2
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
