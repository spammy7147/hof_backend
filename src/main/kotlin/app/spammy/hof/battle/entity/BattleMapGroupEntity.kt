package app.spammy.hof.battle.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/** 같은 전투 카테고리의 맵을 화면 표시 단위로 묶는 정적 카탈로그 그룹이다. */
@Entity
@Table(
    name = "battle_map_groups",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_battle_map_groups_category_name", columnNames = ["category_id", "name"]),
    ],
)
class BattleMapGroupEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "category_id", nullable = false, length = 50)
    var categoryId: String,

    @Column(name = "name", nullable = false, length = 200)
    var name: String,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    @Column(name = "recommended_level", length = 50)
    var recommendedLevel: String? = null,
)
