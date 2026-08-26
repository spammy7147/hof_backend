package app.spammy.hof.auth.service

import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.mockito.Mockito

class PostgreSqlAdvisoryAccountExecutionLockTest {
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

    private fun postgresFixture(): Fixture {
        val dataSource = Mockito.mock(DataSource::class.java)
        val connection = Mockito.mock(Connection::class.java)
        val metadata = Mockito.mock(DatabaseMetaData::class.java)
        val events = mutableListOf<String>()
        Mockito.`when`(dataSource.connection).thenReturn(connection)
        Mockito.`when`(connection.metaData).thenReturn(metadata)
        Mockito.`when`(metadata.databaseProductName).thenReturn("PostgreSQL")
        Mockito.`when`(connection.prepareStatement(Mockito.anyString())).thenAnswer { invocation ->
            val sql = invocation.getArgument<String>(0)
            preparedStatement(sql, events)
        }
        return Fixture(dataSource, events)
    }

    private fun preparedStatement(sql: String, events: MutableList<String>): PreparedStatement {
        val statement = Mockito.mock(PreparedStatement::class.java)
        val result = Mockito.mock(ResultSet::class.java)
        Mockito.`when`(statement.executeQuery()).thenAnswer {
            events += sql
            result
        }
        Mockito.`when`(result.next()).thenReturn(true)
        Mockito.`when`(result.getBoolean(1)).thenReturn(true)
        return statement
    }

    private data class Fixture(
        val dataSource: DataSource,
        val events: MutableList<String>,
    )
}
