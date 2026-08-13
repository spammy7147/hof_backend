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
            val display = label.replace(LEADING_PRICE, "").trim()
            val name = display.substringBefore(DETAIL_SEPARATOR).replace(TRAILING_OWNED, "").trim()
            val detail = display.substringAfter(DETAIL_SEPARATOR, "").trim().takeIf(String::isNotBlank)
            StashBox(
                id = row.candidate?.id ?: "display-$index",
                name = name.ifBlank { "이름 없는 상자" },
                selectable = row.selectable,
                owned = OWNED.find(label)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull(),
                cost = PRICE.find(label)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull(),
                detail = detail,
            )
        }
        return StashSnapshot(boxes.distinctBy(StashBox::id), actions, result)
    }

    fun action(form: ParsedTownForm): StashOpenAction? = stashAction(form)

    private fun stashAction(form: ParsedTownForm): StashOpenAction? {
        val submit = form.submitFields.singleOrNull() ?: return null
        val allowedActions = ACTION_FIELDS[submit.name] ?: return null
        val label = clean(submit.value)
        if (!OPEN_WORD.containsMatchIn(label)) return null
        val count = DRAW_COUNT.find(label)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        val semanticActions = buildSet {
            if (ALL_WORD.containsMatchIn(label)) add(StashOpenAction.ALL)
            ORDER.filter { it.drawCount == count }.forEach(::add)
        }
        return allowedActions.intersect(semanticActions).singleOrNull()
    }

    private fun clean(value: String) = value.replace(Regex("\\s+"), " ").trim()

    private companion object {
        val ORDER = listOf(StashOpenAction.ONE, StashOpenAction.TWENTY, StashOpenAction.HUNDRED, StashOpenAction.THOUSAND, StashOpenAction.ALL)
        // APK가 관측한 고정 submit 계약만 허용한다. 표시 문구가 우연히 같은 임의 submit을
        // action으로 승격하면 서버가 추가한 다른 기능을 상자 개봉으로 오인할 수 있다.
        val ACTION_FIELDS = mapOf(
            "Open" to setOf(StashOpenAction.ONE),
            "Open20" to setOf(StashOpenAction.TWENTY),
            "Open100" to setOf(StashOpenAction.HUNDRED),
            "Open1000" to setOf(StashOpenAction.THOUSAND),
            // HOF 실서버는 같은 필드를 과거의 "전부"와 현재의 "1000개" 의미로 재사용한다.
            // field 이름과 표시 문구의 의미가 정확히 하나로 교차할 때만 action으로 인정한다.
            "AllOpen" to setOf(StashOpenAction.THOUSAND, StashOpenAction.ALL),
        )
        val OPEN_WORD = Regex("열기|개봉|open", RegexOption.IGNORE_CASE)
        val ALL_WORD = Regex("전부|전체|모두|all", RegexOption.IGNORE_CASE)
        val DRAW_COUNT = Regex("([\\d,]+)\\s*개")
        val OWNED = Regex("[x×]\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
        val TRAILING_OWNED = Regex("\\s*[x×]\\s*[\\d,]+\\s*$", RegexOption.IGNORE_CASE)
        val PRICE = Regex("[$]\\s*([\\d,]+)")
        val LEADING_PRICE = Regex("^[$]\\s*[\\d,]+\\s*")
        const val DETAIL_SEPARATOR = "/"
        val HEADER = Regex("^(개봉가능|가격|Item|아이템)(?:\\s+(개봉가능|가격|Item|아이템))*$", RegexOption.IGNORE_CASE)
    }
}
