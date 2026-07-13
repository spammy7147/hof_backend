package app.spammy.hof.battle.entity

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
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/** 키, 횟수, 쿨타임처럼 같은 맵에서도 HOF 계정별로 다른 관측 상태다. */
@Entity
@Table(
    name = "account_battle_map_states",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_account_battle_map_states_account_map",
            columnNames = ["account_id", "battle_map_id"],
        ),
    ],
)
class AccountBattleMapStateEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    val account: HofAccountEntity,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "battle_map_id", nullable = false)
    val battleMap: BattleMapEntity,

    @Column(name = "key_count")
    var keyCount: Int? = null,

    @Column(name = "available_count")
    var availableCount: Int? = null,

    @Column(name = "attempt_remaining")
    var attemptRemaining: Int? = null,

    @Column(name = "win_remaining")
    var winRemaining: Int? = null,

    @Column(name = "cooldown_until")
    var cooldownUntil: Instant? = null,

    @Column(name = "raw_href", nullable = false, columnDefinition = "text")
    var rawHref: String,

    @Column(name = "visible", nullable = false)
    var visible: Boolean = true,

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant,
)
