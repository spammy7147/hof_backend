package app.spammy.hof.town.exchange.parser

import app.spammy.hof.town.common.model.*
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.exchange.model.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
class ExchangePageParser(private val formParser: HofFormParser = HofFormParser()) {
    fun parse(
        mode: ExchangeMode,
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        result: ParsedTownResult? = null,
    ): ExchangeSnapshot {
        val document = Jsoup.parse(html, finalUrl)
        val forms = page.forms.filter { it.submitFields.size == 1 }
        val gradeForms = if (mode == ExchangeMode.LEGACY) forms.filter(::isLegacyGradeForm) else emptyList()
        val annForms = if (mode == ExchangeMode.ANN) forms.mapNotNull { form ->
            annType(form, annSection(document, finalUrl, form))?.let { type -> AnnActionGroup(type, annLabel(type), rows(form), form.actionId) }
        } else emptyList()
        val tradeForm = if (mode == ExchangeMode.ANN) null else forms
            .filterNot { it in gradeForms }
            .filter { form -> form.candidates.any { it.selectionType != TownSelectionType.SELECT } }
            .singleOrNull { isTradeForm(it) }
        val categories = tradeForm?.let { parseCategories(it, document) }.orEmpty()
        val current = categories.singleOrNull { it.current }?.id
        return ExchangeSnapshot(
            mode = mode,
            categories = categories,
            currentCategoryId = current,
            rows = tradeForm?.let(::rows).orEmpty(),
            ownedCurrencies = parseCurrencies(document),
            gradeActions = gradeForms.map { form ->
                LegacyGradeAction(form.actionId, clean(form.submitFields.single().value).ifBlank { clean(form.submitFields.single().name) })
            },
            annActions = annForms,
            warning = if (mode == ExchangeMode.LEGACY && gradeForms.isNotEmpty()) LEGACY_AUTOMATIC_TARGET_WARNING else null,
            history = parseHistory(document),
            result = result,
            tradeActionId = tradeForm?.actionId,
            categoryField = categories.firstOrNull()?.id?.let { categoryId ->
                tradeForm?.candidates?.singleOrNull { it.id == categoryId }?.inputName
            },
        )
    }

    private fun rows(form: ParsedTownForm): List<ExchangeRow> = form.rows.mapIndexedNotNull { index, row ->
        val label = clean(row.label)
        if (label.isBlank() || HEADER.matches(label)) return@mapIndexedNotNull null
        val candidate = row.candidate?.takeIf { it.selectionType != TownSelectionType.SELECT }
        ExchangeRow(
            id = candidate?.id ?: "display-$index",
            label = label.replace(LEADING_PRICE, "").trim(),
            selectable = candidate != null,
            detail = label,
            cost = PRICE.find(label)?.groupValues?.get(1)?.number(),
            owned = OWNED.find(label)?.groupValues?.get(1)?.intNumber(),
            minQuantity = candidate?.minQuantity ?: 1,
            maxQuantity = candidate?.maxQuantity,
        )
    }.distinctBy(ExchangeRow::id)

    private fun parseCategories(form: ParsedTownForm, document: org.jsoup.nodes.Document): List<ExchangeCategory> {
        val selectGroups = form.candidates.filter { it.selectionType == TownSelectionType.SELECT }.groupBy { it.inputName }
        val group = selectGroups.entries.singleOrNull { (_, values) -> values.size >= 1 } ?: return emptyList()
        val domSelect = document.select("select[name=${css(group.key)}]").filter { it.closest("form") != null }.singleOrNull()
            ?: return emptyList()
        return domSelect.select("option[value]").filterNot { it.hasAttr("disabled") }.mapNotNull { option ->
            val candidate = group.value.singleOrNull { it.inputValue == option.attr("value") } ?: return@mapNotNull null
            ExchangeCategory(candidate.id, clean(option.text()), option.hasAttr("selected") || domSelect.`val`() == option.attr("value"))
        }
    }

    private fun isTradeForm(form: ParsedTownForm): Boolean {
        val submit = form.submitFields.single()
        return TRADE_WORD.containsMatchIn("${submit.name} ${submit.value}")
    }

    private fun isLegacyGradeForm(form: ParsedTownForm): Boolean {
        if (form.candidates.any { it.selectionType != TownSelectionType.SELECT }) return false
        val submit = form.submitFields.single()
        return LEGACY_GRADE.containsMatchIn("${submit.name} ${submit.value}")
    }

    private fun annType(form: ParsedTownForm, section: String?): AnnAction? {
        val submit = form.submitFields.single()
        val semantic = "${section.orEmpty()} ${submit.name} ${submit.value}"
        return when {
            GIFT_WORD.containsMatchIn(semantic) -> AnnAction.GIVE_GIFT
            MODIFY_WORD.containsMatchIn(semantic) && form.candidates.any { it.selectionType != TownSelectionType.SELECT } -> AnnAction.MODIFY_ITEM
            else -> null
        }
    }

    private fun annSection(document: org.jsoup.nodes.Document, finalUrl: String, form: ParsedTownForm): String? {
        val dom = document.select("form").singleOrNull { element ->
            formParser.parse(element.outerHtml(), finalUrl).forms.any { it.actionId == form.actionId }
        } ?: return null
        val elements = document.select("body *")
        val index = elements.indexOf(dom)
        if (index < 0) return null
        return elements.take(index).asReversed().map { clean(it.ownText()) }
            .firstOrNull { ANN_SECTION.containsMatchIn(it) }
    }

    private fun annLabel(type: AnnAction) = when (type) {
        AnnAction.MODIFY_ITEM -> "앤에게 아이템을 맡긴다"
        AnnAction.GIVE_GIFT -> "앤에게 선물"
    }

    private fun parseCurrencies(document: org.jsoup.nodes.Document): List<OwnedExchangeCurrency> = document.select("body *")
        .map { clean(it.ownText()) }
        .filter { text -> CURRENCY_WORD.containsMatchIn(text) && CURRENCY_QUANTITY.containsMatchIn(text) }
        .mapNotNull { text ->
            val match = CURRENCY_QUANTITY.find(text) ?: return@mapNotNull null
            val label = clean(text.substring(0, match.range.first)).trim(':', '：', ' ')
            if (label.isBlank()) null else OwnedExchangeCurrency(label, match.groupValues[1].number())
        }.distinctBy { it.label }.take(30)

    private fun parseHistory(document: org.jsoup.nodes.Document): List<String> {
        val elements = document.select("body *")
        val marker = elements.indexOfFirst { HISTORY_MARKER.containsMatchIn(clean(it.ownText())) }
        if (marker < 0) return emptyList()
        return elements.drop(marker + 1).map { clean(it.ownText()) }
            .filter { it.isNotBlank() && HISTORY_WORD.containsMatchIn(it) }
            .distinct().takeLast(50)
    }

    private fun clean(value: String) = value.replace(Regex("\\s+"), " ").trim()
    private fun String.number() = replace(",", "").toLongOrNull()
    private fun String.intNumber() = number()?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    private fun css(value: String) = "'${value.replace("'", "\\'")}'"

    private companion object {
        val TRADE_WORD = Regex("Create|Trade|교환|제작", RegexOption.IGNORE_CASE)
        val LEGACY_GRADE = Regex("(Junk|Old|Common|Uncommon|Rare|Historical).*(교환|exchange)", RegexOption.IGNORE_CASE)
        val GIFT_WORD = Regex("Gift|선물", RegexOption.IGNORE_CASE)
        val MODIFY_WORD = Regex("Modify|Create|맡기|마제즈", RegexOption.IGNORE_CASE)
        val ANN_SECTION = Regex("앤에게.*(맡기|선물)|아이템을 맡긴다", RegexOption.IGNORE_CASE)
        val PRICE = Regex("[$]\\s*([\\d,]+)")
        val LEADING_PRICE = Regex("^[$]\\s*[\\d,]+\\s*")
        val OWNED = Regex("[x×]\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
        val CURRENCY_WORD = Regex("보유|Coin|Statue|Shard|Ticket|Memo|Certificate|조각|주화|증표", RegexOption.IGNORE_CASE)
        val CURRENCY_QUANTITY = Regex("(?::|：|x|×)\\s*([\\d,]+)\\s*(?:개|보유중|보유 중)?", RegexOption.IGNORE_CASE)
        val HISTORY_WORD = Regex("교환|마제즈|결과|성공|실패", RegexOption.IGNORE_CASE)
        val HISTORY_MARKER = Regex("마제즈.*이력|최근.*결과|history", RegexOption.IGNORE_CASE)
        val HEADER = Regex("^(제작비|수수료|Item|아이템|제작비 Item|수수료 Item)$", RegexOption.IGNORE_CASE)
    }
}
