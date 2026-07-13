package app.spammy.hof.character.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapsId
import jakarta.persistence.OneToOne
import jakarta.persistence.Table

/**
 * 캐릭터 상세 페이지에서 파싱한 주요 전투 스탯을 캐릭터당 한 행으로 저장한다.
 */
@Entity
@Table(name = "character_stats")
class CharacterStatsEntity(
    @Id
    @Column(name = "character_id")
    var characterId: Long = 0,

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,

    @Column(name = "atk")
    var atk: Int? = null,

    @Column(name = "matk")
    var matk: Int? = null,

    @Column(name = "def_base")
    var defBase: Int? = null,

    @Column(name = "def_bonus")
    var defBonus: Int? = null,

    @Column(name = "mdef_base")
    var mdefBase: Int? = null,

    @Column(name = "mdef_bonus")
    var mdefBonus: Int? = null,

    @Column(name = "handle_used")
    var handleUsed: Int? = null,

    @Column(name = "handle_max")
    var handleMax: Int? = null,

    @Column(name = "cost_used")
    var costUsed: Int? = null,

    @Column(name = "cost_max")
    var costMax: Int? = null,
)
