package app.spammy.hof.automation.lease

import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class AccountAutomationLeaseService(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun tryAcquire(
        accountId: Long,
        ownerId: String,
        now: Instant,
        duration: Duration,
    ): Boolean {
        val until = now.plus(duration)
        val updated = jdbcTemplate.update(
            """
            update account_automation_leases
               set owner_id = ?, lease_until = ?, updated_at = ?
             where account_id = ? and lease_until <= ?
            """.trimIndent(),
            ownerId,
            Timestamp.from(until),
            Timestamp.from(now),
            accountId,
            Timestamp.from(now),
        )
        if (updated == 1) return true
        return try {
            jdbcTemplate.update(
                """
                insert into account_automation_leases(account_id, owner_id, lease_until, updated_at)
                values (?, ?, ?, ?)
                """.trimIndent(),
                accountId,
                ownerId,
                Timestamp.from(until),
                Timestamp.from(now),
            ) == 1
        } catch (_: DuplicateKeyException) {
            false
        }
    }

    fun release(accountId: Long, ownerId: String) {
        jdbcTemplate.update(
            "delete from account_automation_leases where account_id = ? and owner_id = ?",
            accountId,
            ownerId,
        )
    }
}
