package app.spammy.hof.automation.entity

import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.party.entity.PartyPresetEntity
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
 * 자동전투 프로필에서 실행할 정적 전투 맵과 선택된 파티 프리셋을 연결하는 순서 행이다.
 *
 * `(profile_id, battle_map_id)` 유일 제약은 같은 맵의 중복 실행을 DB에서도 차단한다. 파티 프리셋은
 * 편집 중 아직 선택하지 않을 수 있어 nullable이며, 값이 있으면 서비스가 프로필 계정 소유인지 먼저
 * 검증한다. [executionOrder] 중복은 저장 전에 요청 전체를 확인해 INVALID_REQUEST로 반환한다.
 */
@Entity
@Table(
    name = "automation_profile_maps",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_automation_profile_maps_profile_map",
            columnNames = ["profile_id", "battle_map_id"],
        ),
    ],
)
class AutomationProfileMapEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", nullable = false)
    var profile: AutomationProfileEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "battle_map_id", nullable = false)
    var battleMap: BattleMapEntity,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "party_preset_id")
    var partyPreset: PartyPresetEntity?,

    @Column(name = "execution_order", nullable = false)
    var executionOrder: Int,

    @Column(name = "module_type", nullable = false, length = 50)
    var moduleType: String = AutomationModuleType.NORMAL_MAP.name,

    @Column(name = "purpose", nullable = false, length = 50)
    var purpose: String = "PRIMARY",
)
