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

/** HOF가 같은 맵을 서로 다른 이름으로 내려줄 때 정적 맵 식별자로 연결하는 DB 별칭이다. */
@Entity
@Table(
    name = "battle_map_aliases",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_battle_map_aliases_map_normalized",
            columnNames = ["battle_map_id", "normalized_alias"],
        ),
    ],
)
class BattleMapAliasEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "battle_map_id", nullable = false)
    val battleMap: BattleMapEntity,

    @Column(name = "alias", nullable = false, length = 300)
    var alias: String,

    @Column(name = "normalized_alias", nullable = false, length = 300)
    var normalizedAlias: String,
)
