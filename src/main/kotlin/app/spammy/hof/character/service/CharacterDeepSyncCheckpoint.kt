package app.spammy.hof.character.service

import app.spammy.hof.character.entity.CharacterRecoveryStatus
import app.spammy.hof.external.model.HofActionPatternRow
import app.spammy.hof.external.model.HofEquipment
import app.spammy.hof.external.parser.CharacterPageParseResult
import app.spammy.hof.external.parser.CharacterPageSection
import app.spammy.hof.external.parser.CharacterSectionParseResult
import java.nio.ByteBuffer
import java.security.MessageDigest

/** 복원에 필요한 의미 값만 보존한다. 폼·선택지·쿠키·HTML은 포함하지 않는다. */
data class CharacterRestoreState(
    val hofCharacterId: String,
    val patterns: List<HofActionPatternRow>,
    val equipment: List<HofEquipment>,
    val position: String,
    val guard: String,
) {
    companion object {
        fun capture(page: CharacterPageParseResult): CharacterRestoreState {
            val required = setOf(
                CharacterPageSection.PROFILE, CharacterPageSection.CURRENT_PATTERN,
                CharacterPageSection.POSITION_GUARD, CharacterPageSection.EQUIPMENT,
            )
            check(required.all { page.sections[it] is CharacterSectionParseResult.Success }) {
                "복원 원본의 장비·행동 패턴·위치/호위를 완전히 확인하지 못했습니다."
            }
            val snapshot = page.snapshot
            check(snapshot.id.isNotBlank() && snapshot.actionPatterns.isNotEmpty()) { "복원 원본의 캐릭터 또는 행동 패턴이 없습니다." }
            check(snapshot.positionGuard.selectedPosition.isNotBlank() && snapshot.positionGuard.guardValue.isNotBlank()) {
                "복원 원본의 위치·호위 값을 확인하지 못했습니다."
            }
            check(snapshot.actionPatterns.map { it.index }.distinct().size == snapshot.actionPatterns.size) {
                "복원 원본의 행동 패턴 순서가 중복됩니다."
            }
            check(snapshot.actionPatterns.all { it.judge.isNotBlank() && it.skill.isNotBlank() }) {
                "복원 원본의 행동 조건·스킬 값을 확인하지 못했습니다."
            }
            return CharacterRestoreState(
                snapshot.id,
                snapshot.actionPatterns.sortedBy { it.index }.map {
                    HofActionPatternRow(it.index, judge = it.judge, quantity = it.quantity, skill = it.skill)
                },
                snapshot.equipment.filter { it.name.isNotBlank() }.map { it.copy(checked = false) },
                snapshot.positionGuard.selectedPosition,
                snapshot.positionGuard.guardValue,
            )
        }
    }
}

data class CharacterDeepSyncCheckpoint(
    val original: CharacterRestoreState,
    val status: CharacterRecoveryStatus = CharacterRecoveryStatus.REQUIRED,
    val collectionComplete: Boolean = false,
    val collectionError: String? = null,
    val restoreAttempts: Int = 0,
    val observed: CharacterSyncObservation = CharacterSyncObservation.from(original),
    val pendingChange: CharacterSyncChange? = null,
    val restoreAttemptLimit: Int = 3,
) {
    /** 응답 유실 시에도 제출한 명령이 바꿀 수 없는 영역은 마지막 관측과 같아야 한다. */
    fun requireExpected(current: CharacterRestoreState) {
        check(current.hofCharacterId == original.hofCharacterId) { "복원 원본과 현재 HOF 캐릭터가 다릅니다." }
        val latest = CharacterSyncObservation.from(current)
        if (latest == observed || current == original) return
        val unchangedConditions = latest.conditions == observed.conditions &&
            latest.position == observed.position && latest.guard == observed.guard
        val expected = when (pendingChange) {
            CharacterSyncChange.LOAD_PATTERN -> latest.equipment == observed.equipment
            CharacterSyncChange.LOAD_EQUIPMENT -> unchangedConditions
            CharacterSyncChange.RESTORE_EQUIPMENT -> unchangedConditions &&
                current.equipment.groupingBy { it }.eachCount().all { (item, count) ->
                    count <= original.equipment.count { it == item }
                }
            CharacterSyncChange.RESTORE_PATTERN -> latest.equipment == observed.equipment &&
                current.patterns == original.patterns && latest.position == observed.position && latest.guard == observed.guard
            CharacterSyncChange.RESTORE_POSITION -> latest.equipment == observed.equipment &&
                latest.patterns == observed.patterns && current.position == original.position && current.guard == original.guard
            null -> false
        }
        check(expected) { "원본 서버의 설정이 예상한 작업 상태와 다릅니다. 외부 변경 가능성이 있어 자동 복원을 중단했습니다." }
    }
}

enum class CharacterSyncChange { LOAD_PATTERN, LOAD_EQUIPMENT, RESTORE_EQUIPMENT, RESTORE_PATTERN, RESTORE_POSITION }

typealias CharacterSyncBeforeChange = (CharacterRestoreState, CharacterSyncChange) -> Unit

/** 원본은 관계형 값으로 보존하고, 변경 감지는 비밀이 없는 의미 값의 지문만 사용한다. */
data class CharacterSyncObservation(
    val equipment: String,
    val patterns: String,
    val conditions: String,
    val position: String,
    val guard: String,
) {
    companion object {
        fun fingerprint(state: CharacterRestoreState): String = from(state).let {
            fingerprint(listOf(state.hofCharacterId, it.equipment, it.patterns, it.position, it.guard))
        }

        fun from(state: CharacterRestoreState) = CharacterSyncObservation(
            fingerprint(state.equipment.flatMap { listOf(it.slot, it.part, it.name, it.iconUrl, it.description) }),
            fingerprint(state.patterns.flatMap { listOf(it.index.toString(), it.judge, it.quantity, it.skill) }),
            fingerprint(state.patterns.flatMap { listOf(it.index.toString(), it.judge, it.quantity) }),
            state.position, state.guard,
        )

        private fun fingerprint(fields: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fields.forEach { field ->
                val bytes = field.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
