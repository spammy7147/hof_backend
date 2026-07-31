package app.spammy.hof.town.reward.parser

import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.reward.model.StashActionCandidate
import app.spammy.hof.town.reward.model.StashBox
import app.spammy.hof.town.reward.model.StashOpenAction
import app.spammy.hof.town.reward.model.StashSnapshot
import org.springframework.stereotype.Component

@Component
class StashPageParser {
    fun parse(
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        result: ParsedTownResult? = null,
    ): StashSnapshot {
        val actionForms = page.forms.mapNotNull { form -> stashAction(form)?.let { it to form } }
        val actions = actionForms
            .groupBy { it.first }
            .mapNotNull { (action, matches) -> matches.singleOrNull()?.second?.let { form ->
                StashActionCandidate(action, clean(form.submitFields.single().value), form.actionId)
            } }
            .sortedBy { ORDER.indexOf(it.action) }
        val rows = actionForms.firstOrNull()?.second?.rows.orEmpty()
        val boxes = rows.filterNot { row -> HEADER.matches(clean(row.label)) }.mapIndexed { index, row ->
            val label = clean(row.label)
            StashBox(
                id = row.candidate?.id ?: "display-$index",
                name = label.replace(LEADING_PRICE, "").trim().ifBlank { "이름 없는 상자" },
                selectable = row.selectable,
                owned = OWNED.find(label)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull(),
                cost = PRICE.find(label)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull(),
                detail = label.takeIf(String::isNotBlank),
            )
        }
        return StashSnapshot(boxes.distinctBy(StashBox::id), actions, result)
    }

    fun action(form: ParsedTownForm): StashOpenAction? = stashAction(form)

    private fun stashAction(form: ParsedTownForm): StashOpenAction? {
        val submit = form.submitFields.singleOrNull() ?: return null
        val label = clean(submit.value)
        if (!OPEN_WORD.containsMatchIn(label)) return null
        if (ALL_WORD.containsMatchIn(label)) return StashOpenAction.ALL
        return DRAW_COUNT.find(label)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()?.let { count ->
            StashOpenAction.entries.singleOrNull { it.drawCount == count }
        }
    }

    private fun clean(value: String) = value.replace(Regex("\\s+"), " ").trim()

    private companion object {
        val ORDER = listOf(StashOpenAction.ONE, StashOpenAction.TWENTY, StashOpenAction.HUNDRED, StashOpenAction.THOUSAND, StashOpenAction.ALL)
        val OPEN_WORD = Regex("열기|개봉|open", RegexOption.IGNORE_CASE)
        val ALL_WORD = Regex("전부|전체|모두|all", RegexOption.IGNORE_CASE)
        val DRAW_COUNT = Regex("([\\d,]+)\\s*개")
        val OWNED = Regex("[x×]\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
        val PRICE = Regex("[$]\\s*([\\d,]+)")
        val LEADING_PRICE = Regex("^[$]\\s*[\\d,]+\\s*")
        val HEADER = Regex("^(개봉가능|가격|Item|아이템)(?:\\s+(개봉가능|가격|Item|아이템))*$", RegexOption.IGNORE_CASE)
    }
}
