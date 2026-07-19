package app.spammy.hof.battle.entity

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.battle.model.BattleMapKeyMode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/** 안전하게 카탈로그 맵으로 결정할 수 없는 계정별 HOF 맵 관측을 원본 트리 형태로 보존한다. */
@Entity
@Table(
    name = "unresolved_battle_maps",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_unresolved_battle_maps_identity",
            columnNames = ["account_id", "category_id", "group_normalized_name", "normalized_name"],
        ),
    ],
)
class UnresolvedBattleMapEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    val account: HofAccountEntity,

    @Column(name = "category_id", nullable = false, length = 50)
    val categoryId: String,

    @Column(name = "group_name", length = 200)
    var groupName: String? = null,

    @Column(name = "group_normalized_name", nullable = false, length = 200)
    val groupNormalizedName: String,

    @Column(name = "group_display_order", nullable = false)
    var groupDisplayOrder: Int = 0,

    @Column(name = "map_display_order", nullable = false)
    var mapDisplayOrder: Int = 0,

    @Column(name = "observed_name", nullable = false, length = 300)
    var observedName: String,

    @Column(name = "normalized_name", nullable = false, length = 300)
    val normalizedName: String,

    @Column(name = "recommended_level", length = 50)
    var recommendedLevel: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "key_mode", nullable = false, length = 20)
    var keyMode: BattleMapKeyMode = BattleMapKeyMode.UNKNOWN,

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

    @Column(name = "required_time")
    var requiredTime: Int? = null,

    @Column(name = "icon_url", columnDefinition = "text")
    var iconUrl: String? = null,

    @Column(name = "raw_href", nullable = false, columnDefinition = "text")
    var rawHref: String,

    @Column(name = "visible", nullable = false)
    var visible: Boolean = true,

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: Instant,
)
