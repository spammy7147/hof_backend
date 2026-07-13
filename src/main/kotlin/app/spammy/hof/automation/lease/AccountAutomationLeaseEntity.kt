package app.spammy.hof.automation.lease

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "account_automation_leases")
class AccountAutomationLeaseEntity(
    @Id @Column(name = "account_id", nullable = false) val accountId: Long,
    @Column(name = "owner_id", nullable = false, length = 120) var ownerId: String,
    @Column(name = "lease_until", nullable = false) var leaseUntil: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
)
