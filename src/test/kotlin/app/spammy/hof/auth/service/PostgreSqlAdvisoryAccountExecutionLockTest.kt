package app.spammy.hof.auth.service

import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito

class PostgreSqlAdvisoryAccountExecutionLockTest {
    @Test
    fun `rejects a pool too small for advisory outer and after-commit transactions`() {
        val dataSource = Mockito.mock(HikariDataSource::class.java)
        Mockito.`when`(dataSource.maximumPoolSize).thenReturn(2)

        assertFailsWith<IllegalArgumentException> {
            PostgreSqlAdvisoryAccountExecutionLock(dataSource)
        }
    }

    @Test
    fun `uses PostgreSQL shared and exclusive session locks across the complete action`() {
        val fixture = postgresFixture()
        val lock = PostgreSqlAdvisoryAccountExecutionLock(fixture.dataSource)

        lock.executeShared(7L, Runnable { fixture.events += "shared-action" })
        lock.executeExclusive(7L, Runnable { fixture.events += "exclusive-action" })

        assertEquals(
            listOf(
                "select pg_advisory_lock_shared(?)",
                "shared-action",
                "select pg_advisory_unlock_shared(?)",
                "select pg_advisory_lock(?)",
                "exclusive-action",
                "select pg_advisory_unlock(?)",
            ),
            fixture.events,
        )
    }

    @Test
    fun `always releases the PostgreSQL session lock when the action fails`() {
        val fixture = postgresFixture()
        val lock = PostgreSqlAdvisoryAccountExecutionLock(fixture.dataSource)

        assertFailsWith<IllegalStateException> {
            lock.executeShared(7L, Runnable { error("submission failed") })
        }

        assertEquals(
            listOf("select pg_advisory_lock_shared(?)", "select pg_advisory_unlock_shared(?)"),
            fixture.events,
        )
    }

    @Test
    fun `bounds held advisory connections so an action connection remains available`() {
        val fixture = postgresFixture()
        val lock = PostgreSqlAdvisoryAccountExecutionLock(fixture.dataSource)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                lock.executeShared(7L, Runnable {
                    firstEntered.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                })
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = executor.submit {
                lock.executeShared(8L, Runnable { secondEntered.countDown() })
            }

            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `aborts a connection when lock acquisition outcome is uncertain`() {
        val fixture = postgresFixture(failLockAcquisition = true)
        val lock = PostgreSqlAdvisoryAccountExecutionLock(fixture.dataSource)

        assertFailsWith<SQLException> {
            lock.executeShared(7L, Runnable { error("must not run") })
        }

        Mockito.verify(fixture.connection).abort(anyExecutor())
        assertEquals(listOf("select pg_advisory_lock_shared(?)"), fixture.events)
    }

    private fun postgresFixture(failLockAcquisition: Boolean = false): Fixture {
        val dataSource = Mockito.mock(DataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val metadata = Mockito.mock(DatabaseMetaData::class.java)
        val events = CopyOnWriteArrayList<String>()
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.metaData).thenReturn(metadata)
        Mockito.`when`(metadata.databaseProductName).thenReturn("PostgreSQL")
        Mockito.`when`(connection.prepareStatement(Mockito.anyString())).thenAnswer { invocation ->
            val sql = invocation.getArgument<String>(0)
            preparedStatement(sql, events, failLockAcquisition)
        }
        return Fixture(dataSource, connection, events)
    }

    private fun preparedStatement(
        sql: String,
        events: MutableList<String>,
        failLockAcquisition: Boolean,
    ): PreparedStatement {
        val statement = Mockito.mock(PreparedStatement::class.java)
        val result = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(statement.executeQuery()).thenAnswer {
            events += sql
            if (failLockAcquisition && sql == "select pg_advisory_lock_shared(?)") {
                throw SQLException("connection lost after advisory lock request")
            }
            result
        }
        Mockito.`when`(result.next()).thenReturn(true)
        Mockito.`when`(result.getBoolean(1)).thenReturn(true)
        return statement
    }

    private fun anyExecutor(): Executor = Mockito.any(Executor::class.java) ?: Executor(Runnable::run)

    private data class Fixture(
        val dataSource: DataSource,
        val connection: Connection,
        val events: CopyOnWriteArrayList<String>,
    )
}
