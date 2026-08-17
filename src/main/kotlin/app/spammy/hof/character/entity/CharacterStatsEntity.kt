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

    @Column(name = "status_points")
    var statusPoints: Int? = null,

    @Column(name = "skill_points")
    var skillPoints: Int? = null,

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

    @Column(name = "exp_current") var expCurrent: Long? = null,
    @Column(name = "exp_max") var expMax: Long? = null,
    @Column(name = "exp_maxed") var expMaxed: Boolean? = null,
    @Column(name = "hp_base") var hpBase: Int? = null,
    @Column(name = "hp_bonus") var hpBonus: Int? = null,
    @Column(name = "sp_base") var spBase: Int? = null,
    @Column(name = "sp_bonus") var spBonus: Int? = null,
    @Column(name = "str_real") var strReal: Int? = null,
    @Column(name = "str_bonus") var strBonus: Int? = null,
    @Column(name = "int_real") var intReal: Int? = null,
    @Column(name = "int_bonus") var intBonus: Int? = null,
    @Column(name = "dex_real") var dexReal: Int? = null,
    @Column(name = "dex_bonus") var dexBonus: Int? = null,
    @Column(name = "spd_real") var spdReal: Int? = null,
    @Column(name = "spd_bonus") var spdBonus: Int? = null,
    @Column(name = "luk_real") var lukReal: Int? = null,
    @Column(name = "luk_bonus") var lukBonus: Int? = null,
    @Column(name = "exp_description", columnDefinition = "text") var expDescription: String? = null,
    @Column(name = "hp_description", columnDefinition = "text") var hpDescription: String? = null,
    @Column(name = "sp_description", columnDefinition = "text") var spDescription: String? = null,
    @Column(name = "str_description", columnDefinition = "text") var strDescription: String? = null,
    @Column(name = "int_description", columnDefinition = "text") var intDescription: String? = null,
    @Column(name = "dex_description", columnDefinition = "text") var dexDescription: String? = null,
    @Column(name = "spd_description", columnDefinition = "text") var spdDescription: String? = null,
    @Column(name = "luk_description", columnDefinition = "text") var lukDescription: String? = null,
)
