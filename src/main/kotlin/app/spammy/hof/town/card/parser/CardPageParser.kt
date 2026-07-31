package app.spammy.hof.town.card.parser

import app.spammy.hof.town.card.model.*
import app.spammy.hof.town.common.model.ParsedTownCandidate
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
class CardPageParser {
    fun parseIdentify(html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): CardIdentifySnapshot {
        val form = actionForm(page, setOf("Identify", "CardIdentify", "Create")) ?: page.forms.maxByOrNull { it.rows.size }
        return CardIdentifySnapshot(form?.actionId, 1, candidates(form), result)
    }

    fun parseUpgrade(html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): CardUpgradeSnapshot {
        val form = actionForm(page, setOf("Create")) ?: page.forms.maxByOrNull { it.rows.size }
        val base = candidates(form).filter { it.fieldName != ADD_MATERIAL }
        val material = candidates(form).filter { it.fieldName == ADD_MATERIAL }
        val bounds = quantityBounds(html, 1, material.mapNotNull { it.owned }.maxOrNull() ?: 1)
        return CardUpgradeSnapshot(
            form?.actionId,
            slots(base, material), base, material, bounds.first, bounds.second,
            parseHistory(html, Regex("합성|강화|upgrade", RegexOption.IGNORE_CASE)), result,
        )
    }

    fun parseChange(html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): CardChangeSnapshot {
        val form = actionForm(page, setOf("Create")) ?: page.forms.maxByOrNull { it.rows.size }
        val base = candidates(form).filter { it.fieldName != ADD_MATERIAL }
        val material = candidates(form).filter { it.fieldName == ADD_MATERIAL }
        val bounds = quantityBounds(html, 1, 10)
        return CardChangeSnapshot(
            form?.actionId,
            slots(base, material), base, material, bounds.first, minOf(bounds.second, 10),
            parseHistory(html, Regex("변화|변환|업그레이드|change", RegexOption.IGNORE_CASE)), result,
        )
    }

    fun parseSell(html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): CardSellSnapshot {
        val form = actionForm(page, setOf("ItemSell", "Sell")) ?: page.forms.maxByOrNull { it.rows.size }
        val cards = candidates(form).map { candidate ->
            val code = candidate.fieldName?.removePrefix("check_")
            val row = Jsoup.parse(html, finalUrl).selectFirst("input[name=check_$code]")?.closest("tr")
            val cells = row?.select("td").orEmpty()
            val value = cells.getOrNull(1)?.text()?.let { CARD_VALUE.find(clean(it))?.groupValues?.get(1)?.toIntOrNull() }
            candidate.copy(blankCardValue = value, maxQuantity = candidate.maxQuantity ?: owned(candidate.label))
        }
        val blank = BLANK_OWNED.find(clean(Jsoup.parse(html, finalUrl).text()))?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        return CardSellSnapshot(form?.actionId, cards, true, CardRewardKind.BLANK_CARD, blank, result)
    }

    fun parseSoulEcho(html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): SoulEchoSnapshot {
        val document = Jsoup.parse(html, finalUrl)
        val form = actionForm(page, setOf("Create")) ?: page.forms.maxByOrNull { it.rows.size }
        val recipeRows = form?.rows.orEmpty().filter { it.candidate?.inputName != "type" }
        val candidateByLabel = recipeRows.associate { clean(it.label) to it.candidate }
        val recipes = recipeRows.mapIndexed { index, row ->
            val label = clean(row.label)
            val candidate = candidateByLabel[label]
            SoulEchoRecipe(
                id = candidate?.id ?: "display-$index", label = label, selectable = candidate != null,
                category = document.selectFirst("select[name=type] option[selected], select[name=type] option")?.text()?.let(::clean),
                requiredEchoes = ECHO_REQUIREMENT.findAll(label).map { clean(it.value.substringBeforeLast('x')) + " x" + it.groupValues[1] }.toList(),
                cost = money(label), successBonus = BONUS.find(label)?.groupValues?.get(1)?.toIntOrNull(),
            )
        }
        val owned = sectionLines(document, Regex("보유.*소울 에코|Owned.*Soul Echo", RegexOption.IGNORE_CASE))
            .mapNotNull { line ->
                val match = OWNED_ECHO.find(line) ?: return@mapNotNull null
                val name = clean(match.groupValues[1])
                OwnedSoulEcho(name, REGION.find(name)?.groupValues?.get(1)?.trim(), match.groupValues[2].replace(",", "").toInt())
            }
        val history = sectionLines(document, Regex("최근.*(?:이력|결과)|History", RegexOption.IGNORE_CASE))
            .filter { HISTORY_SIGNAL.containsMatchIn(it) }
            .map { SoulEchoHistory(it, !FAILURE.containsMatchIn(it)) }
        val categories = document.select("select[name=type] option:not([disabled])")
            .filter { it.attr("value").isNotBlank() }
            .map { SoulEchoCategory("type:${it.attr("value")}", clean(it.text())) }
        return SoulEchoSnapshot(form?.actionId, categories, recipes, owned, history, result)
    }

    private fun candidates(form: ParsedTownForm?): List<CardCandidate> = form?.rows.orEmpty().mapIndexed { index, row ->
        val candidate = row.candidate
        val label = clean(row.label)
        CardCandidate(
            id = candidate?.id ?: "display-$index", label = label, selectable = candidate != null,
            fieldName = candidate?.inputName, owned = owned(label), rarity = RARITY.find(label)?.value,
            restrictions = RESTRICTION.findAll(label).map { clean(it.value) }.toList(),
            description = label, cost = money(label), maxQuantity = candidate?.maxQuantity,
            sourceKey = candidate?.inputValue,
        )
    }

    private fun slots(base: List<CardCandidate>, material: List<CardCandidate>) = buildList {
        base.firstOrNull()?.fieldName?.let { add(CardSelectionSlot("base", "베이스 카드", it)) }
        material.firstOrNull()?.fieldName?.let { add(CardSelectionSlot("material", "추가 카드", it)) }
    }

    private fun actionForm(page: ParsedTownPage, names: Set<String>): ParsedTownForm? = page.forms.firstOrNull { form ->
        form.submitFields.any { it.name in names }
    }

    private fun quantityBounds(html: String, defaultMin: Int, defaultMax: Int): Pair<Int, Int> {
        val input = Jsoup.parse(html).selectFirst("input[name=amount]") ?: return defaultMin to defaultMax
        return (input.attr("min").toIntOrNull()?.coerceAtLeast(1) ?: defaultMin) to
            (input.attr("max").toIntOrNull() ?: defaultMax)
    }

    private fun parseHistory(html: String, signal: Regex): List<String> = Jsoup.parse(html).select("p,li,div")
        .map { clean(it.text()) }.filter { it.length in 2..500 && signal.containsMatchIn(it) }.distinct().takeLast(30)

    private fun sectionLines(document: org.jsoup.nodes.Document, heading: Regex): List<String> {
        val start = document.select("h1,h2,h3,h4,h5").firstOrNull { heading.containsMatchIn(clean(it.text())) } ?: return emptyList()
        val lines = mutableListOf<String>()
        var node: Element? = start.nextElementSibling()
        while (node != null && node.tagName() !in setOf("h1", "h2", "h3", "h4", "h5")) {
            val selected = node.select("li,p,tr").ifEmpty { listOf(node) }
            lines += selected.map { clean(it.text()) }.filter(String::isNotBlank)
            node = node.nextElementSibling()
        }
        return lines.distinct()
    }

    private fun owned(label: String): Int? = OWNED.find(label)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
    private fun money(label: String): Long? = MONEY.find(label)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
    private fun clean(value: String) = value.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val ADD_MATERIAL = "AddMaterial"
        val MONEY = Regex("[$]\\s*([\\d,]+)")
        val OWNED = Regex("(?:^|\\s)[x×]\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
        val RARITY = Regex("[★☆]+")
        val RESTRICTION = Regex("Base\\s*Only|Drop\\s*Only|Can'?t\\s*Mix|Bind", RegexOption.IGNORE_CASE)
        val CARD_VALUE = Regex("[x×]\\s*(\\d+)")
        val BLANK_OWNED = Regex("Blank\\s*Card\\s*[:：]\\s*([\\d,]+)\\s*장", RegexOption.IGNORE_CASE)
        val ECHO_REQUIREMENT = Regex("Soul\\s+Echo\\s*\\([^)]*\\)[^x×]{0,80}[x×]\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
        val OWNED_ECHO = Regex("(Soul\\s+Echo\\s*\\([^)]*\\)[^x×]*)[x×]\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
        val REGION = Regex("\\(([^)]+)\\)")
        val BONUS = Regex("\\+?(\\d+)%")
        val HISTORY_SIGNAL = Regex("Soul Echo|소울 에코|창조|실패|성공", RegexOption.IGNORE_CASE)
        val FAILURE = Regex("실패|failed", RegexOption.IGNORE_CASE)
    }
}
