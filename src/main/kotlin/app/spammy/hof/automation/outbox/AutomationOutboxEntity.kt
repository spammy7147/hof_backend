package app.spammy.hof.automation.outbox

import app.spammy.hof.account.entity.HofAccountEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "automation_outbox")
class AutomationOutboxEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @Column(name = "event_id", nullable = false, unique = true, length = 80) var eventId: String,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id", nullable = false) var account: HofAccountEntity,
    @Column(name = "topic", nullable = false, length = 120) var topic: String,
    @Column(name = "event_key", nullable = false, length = 80) var eventKey: String,
    @Column(name = "payload", nullable = false, columnDefinition = "text") var payload: String,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
    @Column(name = "available_at", nullable = false) var availableAt: Instant,
    @Column(name = "published_at") var publishedAt: Instant? = null,
)
