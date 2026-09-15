package app.spammy.hof.character.pattern

import java.time.Instant
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

data class CharacterPatternRemoteSlot(val slotCode: String, val label: String, val canLoad: Boolean)

data class CharacterPatternRemoteState(
    val revision: Instant,
    val setting: CharacterPatternSetting,
    val capacity: Int,
    val judgeValues: Set<String>,
    val skillValues: Set<String>,
    val positionValues: Set<String>,
    val guardValues: Set<String>,
    val savedSlots: List<CharacterPatternRemoteSlot>,
)

enum class CharacterPatternMutationReceipt { RESPONSE_RECEIVED, RESPONSE_LOST }

interface CharacterPatternRemote {
    /** 요청 캐릭터의 전체 현재 패턴·위치·호위를 확인할 수 없으면 관측을 실패시킨다. */
    fun observe(): CharacterPatternRemoteState
    fun changeAllRows(rows: List<CharacterPatternRowValue>): CharacterPatternMutationReceipt
    fun changePositionGuard(position: String, guard: String): CharacterPatternMutationReceipt
    fun saveSlot(slotCode: String, name: String): CharacterPatternMutationReceipt
    fun deleteSlot(slotCode: String): CharacterPatternMutationReceipt
    fun loadSlot(slotCode: String): CharacterPatternMutationReceipt
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(CharacterPatternOperationResult.Completed::class, name = "Completed"),
    JsonSubTypes.Type(CharacterPatternOperationResult.Conflict::class, name = "Conflict"),
    JsonSubTypes.Type(CharacterPatternOperationResult.Rejected::class, name = "Rejected"),
    JsonSubTypes.Type(CharacterPatternOperationResult.PartiallyApplied::class, name = "PartiallyApplied"),
    JsonSubTypes.Type(CharacterPatternOperationResult.RefreshRequired::class, name = "RefreshRequired"),
)
sealed interface CharacterPatternOperationResult {
    data class Completed(val revision: Instant, val messages: List<String> = emptyList()) : CharacterPatternOperationResult
    data class Conflict(val currentRevision: Instant, val rowDiffs: List<CharacterPatternRowDiff>) : CharacterPatternOperationResult
    data class PartiallyApplied(val completedSteps: Int, val nextStep: String, val message: String) : CharacterPatternOperationResult
    data class RefreshRequired(val message: String) : CharacterPatternOperationResult
    data class Rejected(val code: String, val message: String) : CharacterPatternOperationResult
}

sealed interface PatternSlotAfterApply {
    data object None : PatternSlotAfterApply
    data class SaveEmpty(val slotCode: String, val name: String) : PatternSlotAfterApply
    data class Replace(val slotCode: String, val name: String) : PatternSlotAfterApply
}

class CharacterPatternOrchestrator(
    private val planner: CharacterPatternPlanner = CharacterPatternPlanner(),
) {
    fun apply(
        remote: CharacterPatternRemote,
        base: CharacterPatternSetting,
        baseRevision: Instant,
        draft: CharacterPatternDraft,
        slotAfterApply: PatternSlotAfterApply = PatternSlotAfterApply.None,
        force: Boolean = false,
    ): CharacterPatternOperationResult {
        val current = remote.observe()
        val planned = planner.plan(
            base = base,
            baseRevision = baseRevision,
            current = current.setting,
            currentRevision = current.revision,
            draft = draft,
            capacity = current.capacity,
            allowedJudges = current.judgeValues,
            allowedSkills = current.skillValues,
            allowedPositions = current.positionValues,
            allowedGuards = current.guardValues,
            force = force,
        )
        val plan = when (planned) {
            is CharacterPatternPlanResult.Conflict -> return CharacterPatternOperationResult.Conflict(
                planned.currentRevision,
                planned.rowDiffs,
            )
            is CharacterPatternPlanResult.Rejected -> return CharacterPatternOperationResult.Rejected(planned.code, planned.message)
            is CharacterPatternPlanResult.Ready -> planned.plan
        }

        validateSlot(current, slotAfterApply)?.let { return it }

        remote.changeAllRows(plan.rows)
        val rowsObserved = remote.observe()
        if (rowsObserved.setting.rows != plan.rows) {
            return CharacterPatternOperationResult.RefreshRequired(
                "패턴 저장 응답을 확정할 수 없습니다. 현재 서버 상태를 새로고침해 확인해 주세요.",
            )
        }

        remote.changePositionGuard(plan.position, plan.guard)
        val applied = remote.observe()
        if (applied.setting != CharacterPatternSetting(plan.rows, plan.position, plan.guard)) {
            return CharacterPatternOperationResult.PartiallyApplied(
                completedSteps = 1,
                nextStep = "POSITION_GUARD",
                message = "Action Pattern은 저장됐지만 위치·호위 적용을 확인하지 못했습니다.",
            )
        }

        return when (slotAfterApply) {
            PatternSlotAfterApply.None -> CharacterPatternOperationResult.Completed(applied.revision)
            is PatternSlotAfterApply.SaveEmpty -> saveEmpty(remote, applied, slotAfterApply)
            is PatternSlotAfterApply.Replace -> replace(remote, applied, slotAfterApply)
        }
    }

    fun load(remote: CharacterPatternRemote, slotCode: String): CharacterPatternOperationResult {
        val before = remote.observe()
        val slot = before.savedSlots.singleOrNull { it.slotCode == slotCode && it.canLoad }
            ?: return CharacterPatternOperationResult.Rejected("SLOT_NOT_LOADABLE", "불러올 수 있는 패턴 슬롯이 아닙니다.")
        remote.loadSlot(slot.slotCode)
        val after = remote.observe()
        return if (after.revision != before.revision || after.setting != before.setting) {
            CharacterPatternOperationResult.Completed(after.revision)
        } else {
            CharacterPatternOperationResult.RefreshRequired("패턴 불러오기 결과를 확인하지 못했습니다.")
        }
    }

    fun delete(remote: CharacterPatternRemote, slotCode: String): CharacterPatternOperationResult {
        val before = remote.observe()
        if (before.savedSlots.none { it.slotCode == slotCode && it.canLoad }) {
            return CharacterPatternOperationResult.Rejected("SLOT_NOT_FOUND", "삭제할 저장 패턴을 찾지 못했습니다.")
        }
        remote.deleteSlot(slotCode)
        val after = remote.observe()
        return if (after.savedSlots.none { it.slotCode == slotCode && it.canLoad }) {
            CharacterPatternOperationResult.Completed(after.revision)
        } else {
            CharacterPatternOperationResult.RefreshRequired("저장 패턴 삭제 결과를 확인하지 못했습니다.")
        }
    }

    private fun saveEmpty(
        remote: CharacterPatternRemote,
        current: CharacterPatternRemoteState,
        request: PatternSlotAfterApply.SaveEmpty,
    ): CharacterPatternOperationResult {
        val slot = current.savedSlots.singleOrNull { it.slotCode == request.slotCode && !it.canLoad }
            ?: return CharacterPatternOperationResult.PartiallyApplied(2, "SAVE_SLOT", "현재 설정은 저장됐지만 선택한 패턴 슬롯이 비어 있지 않습니다.")
        remote.saveSlot(slot.slotCode, request.name)
        val after = remote.observe()
        return if (after.savedSlots.any { it.slotCode == slot.slotCode && it.canLoad }) {
            CharacterPatternOperationResult.Completed(after.revision, listOf("현재 설정과 저장 패턴을 저장했습니다."))
        } else {
            CharacterPatternOperationResult.PartiallyApplied(2, "SAVE_SLOT", "현재 설정은 저장됐지만 패턴 슬롯 보관을 확인하지 못했습니다.")
        }
    }

    private fun replace(
        remote: CharacterPatternRemote,
        current: CharacterPatternRemoteState,
        request: PatternSlotAfterApply.Replace,
    ): CharacterPatternOperationResult {
        val slot = current.savedSlots.singleOrNull { it.slotCode == request.slotCode && it.canLoad }
            ?: return CharacterPatternOperationResult.PartiallyApplied(2, "SAVE_SLOT", "현재 설정은 저장됐지만 교체할 저장 패턴을 찾지 못했습니다.")
        remote.deleteSlot(slot.slotCode)
        val deleted = remote.observe()
        if (deleted.savedSlots.any { it.slotCode == slot.slotCode && it.canLoad }) {
            return CharacterPatternOperationResult.RefreshRequired("기존 저장 패턴 삭제 결과를 확인하지 못해 교체를 중단했습니다.")
        }
        remote.saveSlot(slot.slotCode, request.name)
        val saved = remote.observe()
        return if (saved.savedSlots.any { it.slotCode == slot.slotCode && it.canLoad }) {
            CharacterPatternOperationResult.Completed(saved.revision, listOf("저장 패턴을 교체했습니다."))
        } else {
            CharacterPatternOperationResult.PartiallyApplied(
                completedSteps = 3,
                nextStep = "SAVE_SLOT",
                message = "기존 슬롯은 삭제됐지만 현재 설정을 다시 보관하지 못했습니다.",
            )
        }
    }

    private fun validateSlot(
        current: CharacterPatternRemoteState,
        request: PatternSlotAfterApply,
    ): CharacterPatternOperationResult.Rejected? {
        val (slotCode, name) = when (request) {
            PatternSlotAfterApply.None -> return null
            is PatternSlotAfterApply.SaveEmpty -> request.slotCode to request.name
            is PatternSlotAfterApply.Replace -> request.slotCode to request.name
        }
        if (name.isBlank() || name.length > 6) {
            return CharacterPatternOperationResult.Rejected("INVALID_SLOT_NAME", "저장 이름은 1~6자로 입력해 주세요.")
        }
        val shouldBeOccupied = request is PatternSlotAfterApply.Replace
        if (current.savedSlots.none { it.slotCode == slotCode && it.canLoad == shouldBeOccupied }) {
            return if (shouldBeOccupied) CharacterPatternOperationResult.Rejected("SLOT_NOT_FOUND", "교체할 저장 패턴을 찾지 못했습니다.")
            else CharacterPatternOperationResult.Rejected("SLOT_NOT_EMPTY", "선택한 패턴 슬롯이 비어 있지 않습니다.")
        }
        return null
    }
}
