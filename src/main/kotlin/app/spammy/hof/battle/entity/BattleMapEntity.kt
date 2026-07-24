package app.spammy.hof.battle.entity

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

/** 계정과 무관하게 공유하는 HOF 전투 맵 카탈로그 행이다. */
@Entity
@Table(
    name = "battle_maps",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_battle_maps_category_map_code", columnNames = ["category_id", "map_code"]),
    ],
)
class BattleMapEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "category_id", nullable = false, length = 50)
    var categoryId: String,

    @Column(name = "map_code", nullable = false, length = 100)
    var mapCode: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    var group: BattleMapGroupEntity? = null,

    @Column(name = "name", nullable = false, length = 300)
    var name: String,

    @Column(name = "normalized_name", nullable = false, length = 300)
    var normalizedName: String,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    @Column(name = "required_time")
    var requiredTime: Int? = null,

    @Column(name = "icon_url", columnDefinition = "text")
    var iconUrl: String? = null,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true,

    @Column(name = "shares_minute_cooldown", nullable = false)
    var sharesMinuteCooldown: Boolean = false,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)
