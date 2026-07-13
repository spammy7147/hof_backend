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
import java.time.Instant

/**
 * 전투 한 회차의 단일 값 결과와 실행 당시 맵 식별자 스냅샷을 보존하는 부모 엔티티다.
 *
 * [battleMap]은 현재 정적 맵 카탈로그를 가리키는 선택적 참조다. 카탈로그가 아직 동기화되지 않았거나
 * 이후 맵이 정리되어도 과거 기록을 설명할 수 있도록 카테고리, 맵 코드, 맵 이름은 별도 스냅샷으로
 * 항상 저장한다. 참가자와 전리품은 이 엔티티에 컬렉션으로 매핑하지 않고 각각의 정규화 테이블에서
 * 조회하여, 최근 기록 pagination 시 collection fetch join으로 행이 중복되는 문제를 피한다.
 */
@Entity
@Table(name = "battle_logs")
class BattleLogEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    var account: HofAccountEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "battle_map_id")
    var battleMap: BattleMapEntity?,

    @Column(name = "category_id", nullable = false, length = 50)
    var categoryIdSnapshot: String,

    @Column(name = "map_code", nullable = false, length = 100)
    var mapCodeSnapshot: String,

    @Column(name = "map_name_snapshot", nullable = false, length = 300)
    var mapNameSnapshot: String,

    @Column(name = "outcome", nullable = false)
    var outcome: String,

    @Column(name = "title", nullable = false, columnDefinition = "text")
    var title: String,

    @Column(name = "turns")
    var turns: Int?,

    @Column(name = "funds")
    var funds: Int?,

    @Column(name = "experience")
    var experience: Int?,

    @Column(name = "quest", columnDefinition = "text")
    var quest: String?,

    @Column(name = "enemy_hp_current")
    var enemyHpCurrent: Int?,

    @Column(name = "enemy_hp_max")
    var enemyHpMax: Int?,

    @Column(name = "enemy_survivors_alive")
    var enemySurvivorsAlive: Int?,

    @Column(name = "enemy_survivors_max")
    var enemySurvivorsMax: Int?,

    @Column(name = "enemy_total_damage")
    var enemyTotalDamage: Int?,

    @Column(name = "enemy_turn_current")
    var enemyTurnCurrent: Int?,

    @Column(name = "enemy_turn_max")
    var enemyTurnMax: Int?,

    @Column(name = "ally_hp_current")
    var allyHpCurrent: Int?,

    @Column(name = "ally_hp_max")
    var allyHpMax: Int?,

    @Column(name = "ally_survivors_alive")
    var allySurvivorsAlive: Int?,

    @Column(name = "ally_survivors_max")
    var allySurvivorsMax: Int?,

    @Column(name = "ally_total_damage")
    var allyTotalDamage: Int?,

    @Column(name = "ally_turn_current")
    var allyTurnCurrent: Int?,

    @Column(name = "ally_turn_max")
    var allyTurnMax: Int?,

    @Column(name = "raw_log_url", columnDefinition = "text")
    var rawLogUrl: String?,

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
)
