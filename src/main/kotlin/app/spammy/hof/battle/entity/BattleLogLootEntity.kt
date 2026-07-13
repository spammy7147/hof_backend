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

/**
 * 전투 결과의 전리품 한 줄을 파싱 값과 원문을 함께 저장하는 엔티티다.
 *
 * [name]과 [quantity]는 통계와 후속 검색에 사용할 구조화 값이고 [rawText]는 HOF가 보여 준 문구를
 * 그대로 앱에 표시하거나 파서 변경을 검증할 때 사용하는 원문 스냅샷이다. [displayOrder]는 파서가
 * 전달한 순서를 보존하며 `(battle_log_id, display_order)` 유일 제약으로 순서 충돌을 방지한다.
 */
@Entity
@Table(
    name = "battle_log_loots",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_battle_log_loots_log_display_order",
            columnNames = ["battle_log_id", "display_order"],
        ),
    ],
)
class BattleLogLootEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "battle_log_id", nullable = false)
    var battleLog: BattleLogEntity,

    @Column(name = "display_order", nullable = false)
    var displayOrder: Int,

    @Column(name = "name", nullable = false, length = 300)
    var name: String,

    @Column(name = "quantity", nullable = false)
    var quantity: Int,

    @Column(name = "raw_text", nullable = false, columnDefinition = "text")
    var rawText: String,
)
