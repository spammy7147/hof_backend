package app.spammy.hof.town.reward.parser

import app.spammy.hof.external.parser.HofHtmlParser
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.reward.model.StashActionCandidate
import app.spammy.hof.town.reward.model.StashBox
import app.spammy.hof.town.reward.model.StashOpenAction
import app.spammy.hof.town.reward.model.StashSnapshot
import app.spammy.hof.town.reward.model.StashOpenResult
import app.spammy.hof.town.reward.model.StashReward
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.springframework.stereotype.Component

@Component
class StashPageParser {
    fun parse(
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        afterOpening: Boolean = false,
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
        return StashSnapshot(boxes.distinctBy(StashBox::id), actions, if (afterOpening) parseResult(html, finalUrl) else null)
    }

    fun action(form: ParsedTownForm): StashOpenAction? = stashAction(form)

    private fun parseResult(html: String, finalUrl: String): StashOpenResult {
        val document = HofHtmlParser.parse(html, finalUrl)
        document.select("script,style,noscript,header,nav,footer").remove()
        val heading = document.select("h1,h2,h3,h4,legend").singleOrNull { clean(it.text()) == "상자의 개봉" }
            ?: return StashOpenResult(emptyList())
        val rewards = mutableListOf<StashReward>()
        val failures = mutableListOf<String>()
        val openingMessages = mutableListOf<String>()
        val buffer = StringBuilder()
        var ended = false
        fun flush() {
            val text = clean(buffer.toString())
            buffer.clear()
            if (UNSAFE_RESULT.containsMatchIn(text)) return
            val opening = OPENING.matchEntire(text)?.groupValues?.get(1)
            if (opening != null) {
                openingMessages += "${opening.substringBefore('/').trim()}을 개봉합니다."
                return
            }
            val found = DISCOVERED.matchEntire(text)?.groupValues?.get(1)
            if (found == null) {
                if (FAILURE.matches(text)) failures += text
                return
            }
            val title = found.substringBefore('/').trim()
            val count = TRAILING_QUANTITY.find(title)
            val quantity = if (count == null) 1 else count.groupValues[1].replace(",", "").toIntOrNull()?.takeIf { it > 0 } ?: return
            val name = title.replace(TRAILING_QUANTITY, "").trim().takeIf(String::isNotBlank) ?: return
            rewards += StashReward(name, quantity, found.substringAfter('/', "").trim().takeIf(String::isNotBlank))
        }
        fun visit(node: Node) {
            if (ended) return
            when (node) {
                is TextNode -> {
                    val text = node.wholeText
                    buffer.append(text.substringBefore(LIST_START))
                    if (LIST_START in text) { flush(); ended = true }
                }
                is Element -> when (node.tagName()) {
                    "form", "h1", "h2", "h3", "h4", "h5", "h6", "legend" -> { flush(); ended = true }
                    "img", "br", "hr" -> flush()
                    else -> {
                        node.childNodes().forEach(::visit)
                        if (node.tagName() in setOf("div", "p", "li", "tr")) flush()
                    }
                }
            }
        }
        var node = heading.nextSibling()
        while (node != null && !ended) {
            visit(node)
            node = node.nextSibling()
        }
        flush()
        val combined = rewards.groupBy { it.name to it.detail }.values.flatMap { entries ->
            val quantity = entries.sumOf { it.quantity.toLong() }
            if (quantity <= Int.MAX_VALUE) listOf(entries.first().copy(quantity = quantity.toInt())) else entries
        }
        return StashOpenResult(combined, failures.distinct(), openingMessages.distinct())
    }

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

    private fun clean(value: String) = value.replace(Regex("[\\s\\u00a0]+"), " ").trim()

    private companion object {
        const val LIST_START = "개봉 가능한 물건들의 목록"
        val OPENING = Regex("^(.+?)을\\s*개봉합니다[.!]?$")
        val DISCOVERED = Regex("^(.+\\S)\\s+발견\\s*!+\\s*$")
        val TRAILING_QUANTITY = Regex("\\s+[x×]\\s*([\\d,]+)\\s*$", RegexOption.IGNORE_CASE)
        val FAILURE = Regex("^(?:상자|아이템|물건|개봉|소지금|자금|돈|재료|조건|개수|수량)[^/]*(?:부족합니다|없습니다|불가능합니다|실패(?:했습니다|하였습니다)?)[.!?]*$")
        val UNSAFE_RESULT = Regex("PHPSESSID|Set-Cookie|Authorization|\\bBearer\\s+|password|passwd|비밀번호|</?[a-z][^>]*>", RegexOption.IGNORE_CASE)
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
