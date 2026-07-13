package app.spammy.hof.automation.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "automation_consumed_events")
class AutomationConsumedEventEntity(
    @Id @Column(name = "event_id", nullable = false, length = 80) val eventId: String,
    @Column(name = "consumed_at", nullable = false) var consumedAt: Instant,
)
