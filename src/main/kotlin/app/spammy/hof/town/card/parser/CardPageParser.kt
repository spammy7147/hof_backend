package app.spammy.hof.town.card.parser

import app.spammy.hof.external.parser.HofHtmlParser

import app.spammy.hof.town.card.model.*
import app.spammy.hof.town.common.model.ParsedTownCandidate
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.common.model.ParsedTownResultItem
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.springframework.stereotype.Component

@Component
class CardPageParser {
    fun parseIdentify(html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): CardIdentifySnapshot {
        val form = actionForm(page, setOf("Identify", "CardIdentify", "Create", "cardshop"), setOf("ItemNo", "item_no"))
        return CardIdentifySnapshot(form?.actionId, 1, candidates(form), identifyResult(html, finalUrl, result))
    }

    fun parseUpgrade(html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): CardUpgradeSnapshot {
        val form = actionForm(page, setOf("Create"), setOf("ItemNo", ADD_MATERIAL))
        val base = candidates(form).filter { it.fieldName != ADD_MATERIAL }
        val material = candidates(form).filter { it.fieldName == ADD_MATERIAL }
        val bounds = quantityBounds(html, 1, material.mapNotNull { it.owned }.maxOrNull() ?: 1)
        return CardUpgradeSnapshot(
            form?.actionId,
            slots(base, material), base, material, bounds.first, bounds.second,
            parseHistory(html, Regex("합성|강화|upgrade", RegexOption.IGNORE_CASE)),
            result = structuredResult(html, result, UPGRADE_RESULT),
        )
    }

    fun parseChange(html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): CardChangeSnapshot {
        val form = actionForm(page, setOf("Create"), setOf("ItemNo", ADD_MATERIAL))
        val base = candidates(form).filter { it.fieldName != ADD_MATERIAL }
        val material = candidates(form).filter { it.fieldName == ADD_MATERIAL }
        val bounds = quantityBounds(html, 1, 10)
        return CardChangeSnapshot(
            form?.actionId,
            slots(base, material), base, material, bounds.first, minOf(bounds.second, 10),
            parseHistory(html, Regex("변화|변환|업그레이드|change", RegexOption.IGNORE_CASE)),
            result = structuredResult(html, result, CHANGE_RESULT),
        )
    }

    fun parseSell(html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): CardSellSnapshot {
        val form = actionForm(page, setOf("ItemSell", "Sell"), setOf("check_"))
        val cards = candidates(form).filterNot { CARD_SELL_HEADER.matches(it.label) }.map { candidate ->
            val code = candidate.fieldName?.removePrefix("check_")
            val row = HofHtmlParser.parse(html, finalUrl).selectFirst("input[name=check_$code]")?.closest("tr")
            val cells = row?.select("td").orEmpty()
            val value = cells.getOrNull(1)?.text()?.let { CARD_VALUE.find(clean(it))?.groupValues?.get(1)?.toIntOrNull() }
            candidate.copy(blankCardValue = value, maxQuantity = candidate.maxQuantity ?: owned(candidate.label))
        }
        val blank = BLANK_OWNED.find(clean(HofHtmlParser.parse(html, finalUrl).text()))?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        return CardSellSnapshot(form?.actionId, cards, true, CardRewardKind.BLANK_CARD, blank, structuredResult(html, result, SELL_RESULT))
    }

    fun parseSoulEcho(html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): SoulEchoSnapshot {
        val document = HofHtmlParser.parse(html, finalUrl)
        val form = actionForm(page, setOf("Create"), setOf("ItemNo", "type"))
        val recipeRows = form?.rows.orEmpty().filter { it.candidate?.inputName != "type" }
        val candidateByLabel = recipeRows.associate { clean(it.label) to it.candidate }
        val selectedOption = document.selectFirst("select[name=type] option[selected]")
            ?: document.selectFirst("select[name=type] option")
        val currentCategoryId = selectedOption?.attr("value")?.takeIf(String::isNotBlank)?.let { "type:$it" }
        val recipes = recipeRows.mapIndexed { index, row ->
            val label = clean(row.label)
            val candidate = candidateByLabel[label]
            SoulEchoRecipe(
                id = candidate?.id ?: "display-$index", label = label, selectable = candidate != null,
                category = currentCategoryId,
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
        return SoulEchoSnapshot(form?.actionId, categories, recipes, currentCategoryId, owned, history, structuredResult(html, result, SOUL_RESULT))
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

    private fun actionForm(page: ParsedTownPage, names: Set<String>, candidateFields: Set<String>): ParsedTownForm? {
        val matches = page.forms.filter { form ->
            form.submitFields.singleOrNull()?.name in names && form.candidates.any { candidate ->
                candidateFields.any { expected ->
                    if (expected.endsWith('_')) candidate.inputName.startsWith(expected) else candidate.inputName == expected
                }
            }
        }
        val equivalentContracts = matches.groupBy { form ->
            listOf<Any?>(
                form.method,
                form.actionUrl,
                form.rows,
                form.hiddenFields,
                form.submitFields,
                form.submitLabel,
                form.submitSource,
                form.hiddenFieldPositions,
                form.editableFields,
            )
        }
        // HOF의 실제 카드 폼은 같은 submit을 목록 위·아래에 반복한다. 공통 폼 파서는 버튼마다
        // 별도 action을 만들므로, 계약이 완전히 같은 경우에만 하나로 접고 마지막 버튼을 사용한다.
        return equivalentContracts.values.singleOrNull()?.lastOrNull()
    }

    private fun quantityBounds(html: String, defaultMin: Int, defaultMax: Int): Pair<Int, Int> {
        val input = HofHtmlParser.parse(html).selectFirst("input[name=amount]") ?: return defaultMin to defaultMax
        return (input.attr("min").toIntOrNull()?.coerceAtLeast(1) ?: defaultMin) to
            (input.attr("max").toIntOrNull() ?: defaultMax)
    }

    private fun parseHistory(html: String, signal: Regex): List<String> = HofHtmlParser.parse(html).select("p,li,div")
        .map { clean(it.ownText()) }.filter { it.length in 2..500 && signal.containsMatchIn(it) }.distinct().takeLast(30)

    /** 감정 성공 페이지가 form 앞에 `<br>` 단위로 삽입하는 장비별 옵션을 구조화한다. */
    private fun identifyResult(html: String, finalUrl: String, result: ParsedTownResult?): ParsedTownResult? {
        val parsed = structuredResult(html, result, IDENTIFY_RESULT) ?: return null
        if (result == null) return parsed
        val liveItems = identifyOptionRows(html, finalUrl)
        if (liveItems.isEmpty()) return parsed
        return parsed.copy(items = (parsed.items + liveItems).distinctBy(ParsedTownResultItem::label).take(100))
    }

    private fun identifyOptionRows(html: String, finalUrl: String): List<ParsedTownResultItem> {
        val document = HofHtmlParser.parse(html, finalUrl)
        val form = document.select("form").firstOrNull { candidate ->
            candidate.select("input[type=radio][name=item_no],input[type=radio][name=ItemNo]").isNotEmpty() &&
                candidate.select("input[type=submit][name=cardshop],button[name=cardshop]").isNotEmpty()
        } ?: return emptyList()
        val siblings = form.parent()?.childNodes() ?: return emptyList()
        val formIndex = siblings.indexOf(form).takeIf { it >= 0 } ?: return emptyList()
        return siblings.subList(0, formIndex).mapIndexedNotNull { index, node ->
            val image = node as? Element ?: return@mapIndexedNotNull null
            if (image.tagName() != "img" || !image.hasClass("vcent")) return@mapIndexedNotNull null
            val line = buildString {
                for (next in siblings.subList(index + 1, formIndex)) {
                    if (next is Element && next.tagName() == "br") break
                    append(nodeText(next)).append(' ')
                }
            }.let(::clean)
            line.takeIf { IDENTIFY_OPTION.containsMatchIn(it) }?.let(::ParsedTownResultItem)
        }.distinctBy(ParsedTownResultItem::label).take(100)
    }

    private fun nodeText(node: Node): String = when (node) {
        is TextNode -> node.text()
        is Element -> node.text()
        else -> ""
    }

    /** 공통 result selector가 없는 HOF의 bare text 결과도 짧은 텍스트만 복구한다. */
    private fun structuredResult(html: String, result: ParsedTownResult?, signal: Regex): ParsedTownResult? {
        if (result == null || result.messages.isNotEmpty() || result.items.isNotEmpty()) return result
        val document = HofHtmlParser.parse(html)
        val form = document.selectFirst("form")
        val explicit = document.select(".result,.message,.msg,.notice,.success,.error,[data-town-result]")
            .map { clean(it.text()) }
        val nearby = if (form == null) {
            emptyList()
        } else {
            val before = generateSequence(form.previousElementSibling()) { it.previousElementSibling() }.take(40)
            val after = generateSequence(form.nextElementSibling()) { it.nextElementSibling() }
                .take(40).takeWhile { it.tagName() != "form" }
            (before + after).flatMap { sibling ->
                (sibling.select("p,li,div,font,b,strong,span") + sibling).asSequence()
            }.map { clean(it.ownText()) }.toList()
        }
        val messages = (explicit + nearby)
            .filter { it.length in 2..500 && signal.containsMatchIn(it) && !RESULT_NOISE.containsMatchIn(it) }
            .distinct().takeLast(20)
        return result.copy(messages = messages)
    }

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
        val CARD_SELL_HEADER = Regex("^(?:(?:가격|수|수량|아이템|카드)(?:\\s+|$))+$", RegexOption.IGNORE_CASE)
        val BLANK_OWNED = Regex("Blank\\s*Card\\s*[:：]\\s*([\\d,]+)\\s*장", RegexOption.IGNORE_CASE)
        val ECHO_REQUIREMENT = Regex("Soul\\s+Echo\\s*\\([^)]*\\)[^x×]{0,80}[x×]\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
        val OWNED_ECHO = Regex("(Soul\\s+Echo\\s*\\([^)]*\\)[^x×]*)[x×]\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
        val REGION = Regex("\\(([^)]+)\\)")
        val BONUS = Regex("\\+?(\\d+)%")
        val HISTORY_SIGNAL = Regex("Soul Echo|소울 에코|창조|실패|성공", RegexOption.IGNORE_CASE)
        val FAILURE = Regex("실패|failed", RegexOption.IGNORE_CASE)
        val IDENTIFY_RESULT = Regex("감정|사용한 금액|부여한 카드 목록|Joker", RegexOption.IGNORE_CASE)
        val IDENTIFY_OPTION = Regex("옵션\\s*:", RegexOption.IGNORE_CASE)
        val UPGRADE_RESULT = Regex("합성 성공|합성 실패|강화 성공|강화 실패|되돌려|소멸|보존|환급", RegexOption.IGNORE_CASE)
        val CHANGE_RESULT = Regex("카드 변화|카드 변환|업그레이드 성공|업그레이드 실패|되돌려|소멸", RegexOption.IGNORE_CASE)
        val SELL_RESULT = Regex("Blank\\s*Card|카드 판매|카드 교환|교환.*(?:성공|완료)|판매.*(?:성공|완료)", RegexOption.IGNORE_CASE)
        val SOUL_RESULT = Regex("소울 에코|Soul Echo|융합|창조|실패|성공", RegexOption.IGNORE_CASE)
        val RESULT_NOISE = Regex("(?:카드를|소울 에코를).*(?:선택|목록)|Create|Reset|전부표시|주의사항", RegexOption.IGNORE_CASE)
    }
}
