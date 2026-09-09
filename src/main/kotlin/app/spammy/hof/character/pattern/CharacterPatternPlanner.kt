package app.spammy.hof.character.pattern

import java.time.Instant

data class CharacterPatternRowValue(
    val judge: String,
    val quantity: String,
    val skill: String,
)

data class CharacterPatternSetting(
    val rows: List<CharacterPatternRowValue>,
    val position: String,
    val guard: String,
)

data class CharacterPatternDraft(
    val baseRevision: Instant,
    val rows: List<CharacterPatternRowValue>,
    val position: String,
    val guard: String,
)

data class CharacterPatternRowDiff(
    val rowNumber: Int,
    val before: CharacterPatternRowValue?,
    val current: CharacterPatternRowValue?,
)

sealed interface CharacterPatternPlanResult {
    data class Ready(val plan: CharacterPatternApplyPlan) : CharacterPatternPlanResult
    data class Conflict(val currentRevision: Instant, val rowDiffs: List<CharacterPatternRowDiff>) : CharacterPatternPlanResult
    data class Rejected(val code: String, val message: String) : CharacterPatternPlanResult
}

data class CharacterPatternApplyPlan(
    val rows: List<CharacterPatternRowValue>,
    val position: String,
    val guard: String,
)

class CharacterPatternPlanner {
    fun insertAbove(
        draft: CharacterPatternDraft,
        selectedIndex: Int,
        capacity: Int,
        defaultRow: CharacterPatternRowValue,
    ): CharacterPatternDraft {
        require(selectedIndex in draft.rows.indices) { "선택한 패턴 행을 찾지 못했습니다." }
        require(draft.rows.size <= capacity) { "추가 대기 행이 이미 있습니다." }
        val rows = draft.rows.toMutableList().apply { add(selectedIndex, defaultRow) }
        check(rows.size <= capacity + 1) { "패턴은 서버 행 수보다 한 행까지만 더 편집할 수 있습니다." }
        return draft.copy(rows = rows)
    }

    fun delete(
        draft: CharacterPatternDraft,
        selectedIndex: Int,
        capacity: Int,
        defaultRow: CharacterPatternRowValue,
    ): CharacterPatternDraft {
        require(selectedIndex in draft.rows.indices) { "선택한 패턴 행을 찾지 못했습니다." }
        val rows = draft.rows.toMutableList().apply { removeAt(selectedIndex) }
        while (rows.size < capacity) rows += defaultRow
        return draft.copy(rows = rows)
    }

    fun move(draft: CharacterPatternDraft, fromIndex: Int, toIndex: Int): CharacterPatternDraft {
        require(fromIndex in draft.rows.indices && toIndex in draft.rows.indices) { "옮길 패턴 행을 찾지 못했습니다." }
        val rows = draft.rows.toMutableList()
        val row = rows.removeAt(fromIndex)
        rows.add(toIndex, row)
        return draft.copy(rows = rows)
    }

    fun plan(
        base: CharacterPatternSetting,
        baseRevision: Instant,
        current: CharacterPatternSetting,
        currentRevision: Instant,
        draft: CharacterPatternDraft,
        capacity: Int,
        allowedJudges: Set<String>,
        allowedSkills: Set<String>,
        allowedPositions: Set<String>,
        allowedGuards: Set<String>,
        force: Boolean = false,
    ): CharacterPatternPlanResult {
        // revision은 원격 버전이 아닌 관측 시각이다. 재조회 후의 전체 설정을 초안의 기준과 비교한다.
        if (!force && (draft.baseRevision != baseRevision || current != base)) {
            return CharacterPatternPlanResult.Conflict(currentRevision, diff(base.rows, current.rows))
        }
        if (draft.rows.size != capacity) {
            return CharacterPatternPlanResult.Rejected(
                if (draft.rows.size > capacity) "DELETE_REQUIRED" else "ROW_COUNT_MISMATCH",
                if (draft.rows.size > capacity) "추가한 행을 적용하려면 원하는 행 하나를 삭제해 주세요."
                else "패턴 행 수가 현재 캐릭터의 허용 수와 다릅니다.",
            )
        }
        if (draft.rows.any { it.judge !in allowedJudges }) {
            return CharacterPatternPlanResult.Rejected("JUDGE_NOT_ALLOWED", "현재 선택할 수 없는 조건이 포함되어 있습니다.")
        }
        if (draft.rows.any { it.skill !in allowedSkills }) {
            return CharacterPatternPlanResult.Rejected("SKILL_NOT_ALLOWED", "현재 사용할 수 없는 스킬이 포함되어 있습니다.")
        }
        if (draft.rows.any { it.quantity.toIntOrNull() == null }) {
            return CharacterPatternPlanResult.Rejected("INVALID_QUANTITY", "패턴 기준값은 숫자여야 합니다.")
        }
        if (draft.position !in allowedPositions || draft.guard !in allowedGuards) {
            return CharacterPatternPlanResult.Rejected("POSITION_GUARD_NOT_ALLOWED", "현재 선택할 수 없는 위치 또는 호위 설정입니다.")
        }
        return CharacterPatternPlanResult.Ready(CharacterPatternApplyPlan(draft.rows, draft.position, draft.guard))
    }

    private fun diff(
        before: List<CharacterPatternRowValue>,
        current: List<CharacterPatternRowValue>,
    ): List<CharacterPatternRowDiff> = (0 until maxOf(before.size, current.size)).mapNotNull { index ->
        val old = before.getOrNull(index)
        val now = current.getOrNull(index)
        CharacterPatternRowDiff(index + 1, old, now).takeIf { old != now }
    }
}
