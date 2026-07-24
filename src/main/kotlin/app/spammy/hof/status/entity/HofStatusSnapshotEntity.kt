package app.spammy.hof.status.entity

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

/** 인증된 HOF 응답에서 마지막으로 관측한 계정 상단 상태다. */
@Entity
@Table(name = "latest_hof_status")
class HofStatusSnapshotEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, unique = true)
    val account: HofAccountEntity,

    @Column(name = "player_name", nullable = false)
    var playerName: String,

    @Column(name = "funds", nullable = false)
    var funds: Long,

    @Column(name = "time_current", nullable = false)
    var timeCurrent: Int,

    @Column(name = "time_max", nullable = false)
    var timeMax: Int,

    @Column(name = "work", nullable = false)
    var work: String,

    @Column(name = "auction", nullable = false)
    var auction: String,

    @Column(name = "observed_at", nullable = false)
    var observedAt: Instant,
)
