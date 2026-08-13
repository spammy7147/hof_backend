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
            if (selection.quantity != 1 && candidate.quantityFieldName == null) {
                invalid("현재 양식에서는 이 항목의 수량을 지정할 수 없습니다.")
            }
            candidate to selection.quantity
        }
        val duplicateFieldIds = request.values.groupingBy { it.fieldId }.eachCount().filterValues { it > 1 }
        if (duplicateFieldIds.isNotEmpty()) invalid("같은 입력란을 중복 제출할 수 없습니다.")
        val editableValues = request.values.map { submitted ->
            val field = form.editableFields.singleOrNull { it.id == submitted.fieldId }
                ?: invalid("현재 입력할 수 없는 항목입니다. 새로고침 후 다시 시도해 주세요.")
            if (field.maxLength != null && submitted.value.length > field.maxLength) {
                invalid("${field.label}은(는) ${field.maxLength}자 이하로 입력해 주세요.")
            }
            if (field.inputType == app.spammy.hof.town.common.model.TownEditableFieldType.NUMBER &&
                submitted.value.isNotBlank() && submitted.value.toLongOrNull() == null
            ) invalid("${field.label}에는 숫자만 입력할 수 있습니다.")
            field to submitted.value
        }
        candidates.filter { it.first.selectionType == TownSelectionType.RADIO }
            .groupingBy { it.first.inputName }
            .eachCount()
            .filterValues { it > 1 }
            .takeIf(Map<*, *>::isNotEmpty)
            ?.let { invalid("하나만 선택할 수 있는 항목을 여러 개 선택했습니다.") }
        candidates.filter { it.first.selectionType == TownSelectionType.SELECT }
            .groupingBy { it.first.inputName }
            .eachCount()
            .filterValues { it > 1 }
            .takeIf(Map<*, *>::isNotEmpty)
            ?.let { invalid("각 선택 목록에서는 하나의 항목만 선택할 수 있습니다.") }

        val fields = mutableListOf<PositionedField>()
        form.hiddenFields.zip(form.hiddenFieldPositions).forEach { (field, position) ->
            fields += PositionedField(position, field)
        }
        form.submitFields.zip(form.submitFieldPositions).forEach { (field, position) ->
            fields += PositionedField(position, field)
        }
        candidates.forEach { (candidate, quantity) ->
            fields += PositionedField(candidate.inputPosition, HofFormField(candidate.inputName, candidate.inputValue))
            candidate.quantityFieldName?.takeUnless { name -> editableValues.any { it.first.inputName == name } }?.let { name ->
                fields += PositionedField(
                    candidate.quantityPosition ?: candidate.inputPosition + 1,
                    HofFormField(name, quantity.toString()),
                )
            }
        }
        editableValues.forEach { (field, value) ->
            fields += PositionedField(field.inputPosition, HofFormField(field.inputName, value))
        }
        return GuardedTownAction(form, fields.sortedBy(PositionedField::position).map(PositionedField::field))
    }

    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)

    private data class PositionedField(
        val position: Int,
        val field: HofFormField,
    )
}
