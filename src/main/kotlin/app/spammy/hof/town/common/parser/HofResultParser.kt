package app.spammy.hof.town.common.parser

import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.common.model.ParsedTownResultItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
class HofResultParser {
    fun parse(html: String): ParsedTownResult {
        val document = Jsoup.parse(html)
        document.select("script, style, noscript, header, nav, footer, form").remove()
        document.select("body *")
            .filter { element -> EXCLUDED_TEXT.containsMatchIn(element.ownText()) }
            .forEach(Element::remove)

        val roots = preferredRoots(document.select(RESULT_SELECTORS))
            .ifEmpty { headingScopedRoots(document.select("body *")) }
        val items = roots.flatMap { root ->
            root.select(ITEM_SELECTORS).mapNotNull { item -> cleanText(item.text()).takeIf(String::isNotBlank) }
        }.distinct().take(MAX_ITEMS).map(::ParsedTownResultItem)
        val messages = roots.mapNotNull { root ->
            val copy = root.clone()
            copy.select(ITEM_SELECTORS).remove()
            cleanText(copy.text()).takeIf(String::isNotBlank)
        }.distinct().take(MAX_MESSAGES)

        return ParsedTownResult(messages = messages, items = items)
    }

    private fun preferredRoots(elements: List<Element>): List<Element> {
        val matched = elements.toSet()
        return elements.filter { candidate -> candidate.parents().none(matched::contains) }
    }

    private fun headingScopedRoots(elements: List<Element>): List<Element> {
        val heading = elements.firstOrNull { element ->
            element.tagName() in HEADING_TAGS && RESULT_HEADING.matches(cleanText(element.text()))
        } ?: return emptyList()
        return heading.siblingElements().takeWhile { sibling ->
            sibling.tagName() !in HEADING_TAGS && !EXCLUDED_TEXT.containsMatchIn(sibling.text())
        }
    }

    private fun cleanText(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_MESSAGE_LENGTH)

    private companion object {
        const val RESULT_SELECTORS = "#result, [data-town-result], .result, .message, .notice, .success, .error, .warning"
        const val ITEM_SELECTORS = "[data-result-item], .result-item, .item"
        val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "legend")
        val RESULT_HEADING = Regex("^(결과|Result|처리 결과)$", RegexOption.IGNORE_CASE)
        val EXCLUDED_TEXT = Regex("Copy\\s*Right|UpDate\\s+Manual|GameData\\s+Top", RegexOption.IGNORE_CASE)
        const val MAX_MESSAGE_LENGTH = 1_000
        const val MAX_MESSAGES = 20
        const val MAX_ITEMS = 100
    }
}
