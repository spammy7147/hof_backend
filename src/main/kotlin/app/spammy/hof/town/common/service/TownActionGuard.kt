package app.spammy.hof.town.common.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.model.HofFormField
import app.spammy.hof.town.common.model.GuardedTownAction
import app.spammy.hof.town.common.model.ParsedTownCandidate
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownSelectionType
import org.springframework.stereotype.Component

@Component
class TownActionGuard {
    fun guard(page: ParsedTownPage, request: TownActionRequest): GuardedTownAction {
        val form = page.forms.singleOrNull { it.actionId == request.actionId }
            ?: invalid("현재 페이지에서 실행할 수 없는 작업입니다. 새로고침 후 다시 시도해 주세요.")
        val duplicateIds = request.selections.groupingBy { it.candidateId }.eachCount().filterValues { it > 1 }
        if (duplicateIds.isNotEmpty()) invalid("같은 항목을 중복 선택할 수 없습니다.")

        val candidates = request.selections.map { selection ->
            val candidate = form.candidates.singleOrNull { it.id == selection.candidateId }
                ?: invalid("현재 선택할 수 없는 항목입니다. 새로고침 후 다시 시도해 주세요.")
            if (selection.quantity < candidate.minQuantity) {
                invalid("수량은 ${candidate.minQuantity} 이상이어야 합니다.")
            }
            if (candidate.maxQuantity != null && selection.quantity > candidate.maxQuantity) {
                invalid("허용된 최대 수량을 초과했습니다.")
            }
            candidate to selection.quantity
        }
        candidates.filter { it.first.selectionType == TownSelectionType.RADIO }
            .groupingBy { it.first.inputName }
            .eachCount()
            .filterValues { it > 1 }
            .takeIf(Map<*, *>::isNotEmpty)
            ?.let { invalid("하나만 선택할 수 있는 항목을 여러 개 선택했습니다.") }

        val fields = mutableListOf<PositionedField>()
        form.hiddenFields.zip(form.hiddenFieldPositions).forEach { (field, position) ->
            fields += PositionedField(position, field)
        }
        form.submitFields.zip(form.submitFieldPositions).forEach { (field, position) ->
            fields += PositionedField(position, field)
        }
        candidates.forEach { (candidate, quantity) ->
            fields += PositionedField(candidate.inputPosition, HofFormField(candidate.inputName, candidate.inputValue))
            candidate.quantityFieldName?.let { name ->
                fields += PositionedField(
                    candidate.quantityPosition ?: candidate.inputPosition + 1,
                    HofFormField(name, quantity.toString()),
                )
            }
        }
        return GuardedTownAction(form, fields.sortedBy(PositionedField::position).map(PositionedField::field))
    }

    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)

    private data class PositionedField(
        val position: Int,
        val field: HofFormField,
    )
}
