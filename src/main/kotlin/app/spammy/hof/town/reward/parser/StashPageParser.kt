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
        val action = ACTION_FIELDS[submit.name] ?: return null
        val label = clean(submit.value)
        if (!OPEN_WORD.containsMatchIn(label)) return null
        if (action == StashOpenAction.ALL) return action.takeIf { ALL_WORD.containsMatchIn(label) }
        val count = DRAW_COUNT.find(label)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        return action.takeIf { count == it.drawCount }
    }

    private fun clean(value: String) = value.replace(Regex("\\s+"), " ").trim()

    private companion object {
        val ORDER = listOf(StashOpenAction.ONE, StashOpenAction.TWENTY, StashOpenAction.HUNDRED, StashOpenAction.THOUSAND, StashOpenAction.ALL)
        // APK가 관측한 고정 submit 계약만 허용한다. 표시 문구가 우연히 같은 임의 submit을
        // action으로 승격하면 서버가 추가한 다른 기능을 상자 개봉으로 오인할 수 있다.
        val ACTION_FIELDS = mapOf(
            "Open" to StashOpenAction.ONE,
            "Open20" to StashOpenAction.TWENTY,
            "Open100" to StashOpenAction.HUNDRED,
            "Open1000" to StashOpenAction.THOUSAND,
            "AllOpen" to StashOpenAction.ALL,
        )
        val OPEN_WORD = Regex("열기|개봉|open", RegexOption.IGNORE_CASE)
        val ALL_WORD = Regex("전부|전체|모두|all", RegexOption.IGNORE_CASE)
        val DRAW_COUNT = Regex("([\\d,]+)\\s*개")
        val OWNED = Regex("[x×]\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
        val PRICE = Regex("[$]\\s*([\\d,]+)")
        val LEADING_PRICE = Regex("^[$]\\s*[\\d,]+\\s*")
        val HEADER = Regex("^(개봉가능|가격|Item|아이템)(?:\\s+(개봉가능|가격|Item|아이템))*$", RegexOption.IGNORE_CASE)
    }
}
