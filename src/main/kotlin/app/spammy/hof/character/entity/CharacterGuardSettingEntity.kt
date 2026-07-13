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
 * 캐릭터의 선택 위치와 가드 설정을 캐릭터당 한 행으로 저장한다.
 */
@Entity
@Table(name = "character_guard_settings")
class CharacterGuardSettingEntity(
    @Id
    @Column(name = "character_id")
    var characterId: Long = 0,

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_id", nullable = false)
    var character: CharacterEntity,

    @Column(name = "selected_position", nullable = false, columnDefinition = "text")
    var selectedPosition: String,

    @Column(name = "guard_value", nullable = false, columnDefinition = "text")
    var guardValue: String,

    @Column(name = "guard_text", nullable = false, columnDefinition = "text")
    var guardText: String,
)
