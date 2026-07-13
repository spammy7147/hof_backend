package app.spammy.hof.battle.entity

import app.spammy.hof.character.entity.CharacterEntity
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
 * 전투에 참여한 캐릭터 한 명을 실행 당시 슬롯 순서와 함께 보존하는 엔티티다.
 *
 * [character]는 현재 캐릭터 행이 남아 있을 때만 연결되는 선택적 FK다. 캐릭터가 삭제되거나 동기화
 * 대상에서 사라져도 과거 전투 기록의 의미가 훼손되지 않도록 HOF 캐릭터 ID와 표시 이름은 항상
 * 스냅샷 컬럼에 저장한다. `(battle_log_id, slot_index)` 유일 제약으로 한 전투의 슬롯 중복을 막는다.
 */
@Entity
@Table(
    name = "battle_log_participants",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_battle_log_participants_log_slot",
            columnNames = ["battle_log_id", "slot_index"],
        ),
    ],
)
class BattleLogParticipantEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "battle_log_id", nullable = false)
    var battleLog: BattleLogEntity,

    @Column(name = "slot_index", nullable = false)
    var slotIndex: Int,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id")
    var character: CharacterEntity?,

    @Column(name = "hof_character_id_snapshot", nullable = false, length = 50)
    var hofCharacterIdSnapshot: String,

    @Column(name = "character_name_snapshot", nullable = false, length = 100)
    var characterNameSnapshot: String,
)
