package app.spammy.hof.push.entity

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
@Table(name = "device_push_targets")
class DevicePushTargetEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "account_id", nullable = false) var account: HofAccountEntity,
    @Column(name = "platform", nullable = false, length = 20) var platform: String = "ANDROID",
    @Column(name = "target_type", nullable = false, length = 20) var targetType: String = "TOKEN",
    @Column(name = "installation_id", nullable = false, length = 160) var installationId: String,
    @Column(name = "target_value", nullable = false, columnDefinition = "text") var targetValue: String,
    @Column(name = "active", nullable = false) var active: Boolean,
    @Column(name = "last_seen_at", nullable = false) var lastSeenAt: Instant,
    @Column(name = "created_at", nullable = false) var createdAt: Instant,
)
