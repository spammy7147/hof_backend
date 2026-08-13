package app.spammy.hof.town.exchange.parser

import app.spammy.hof.external.parser.HofHtmlParser

import app.spammy.hof.town.common.model.*
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.exchange.model.*
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
        categoryCandidateId: String? = null,
    ): ExchangeSnapshot {
        val materialized = if (mode in STATIC_CATALOG_MODES && LIST_FUNCTION_MARKER in html) {
            materializeStaticCatalog(html, finalUrl, categoryCandidateId)
        } else null
        val effectiveHtml = materialized?.html ?: html
        val effectivePage = materialized?.let { formParser.parse(effectiveHtml, finalUrl) } ?: page
        val document = HofHtmlParser.parse(effectiveHtml, finalUrl)
        val domForms = mapDomForms(document, effectivePage)
        val forms = effectivePage.forms.filter { it.submitFields.size == 1 }
        val gradeForms = if (mode == ExchangeMode.LEGACY) forms.filter(::isLegacyGradeForm) else emptyList()
        val annForms = if (mode == ExchangeMode.ANN) forms.mapNotNull { form ->
            annType(form, annSection(document, domForms[form.actionId]))?.let { type ->
                AnnActionGroup(type, annLabel(type), rows(form), form.actionId)
            }
        } else emptyList()
        val tradeForm = if (mode == ExchangeMode.ANN) null else forms
            .filterNot { it in gradeForms }
            .filter { form -> form.candidates.any { it.selectionType != TownSelectionType.SELECT } }
            .singleOrNull { isTradeForm(it) }
        val domTradeForm = tradeForm?.let { domForms[it.actionId] }
        val categories = materialized?.categories?.map { category ->
            ExchangeCategory(category.id, category.label, category.id == materialized.currentCategoryId)
        } ?: tradeForm?.let { parseCategories(it, document) }.orEmpty()
        val current = categories.singleOrNull { it.current }?.id
        val itemTByCandidate = if (tradeForm != null && domTradeForm != null && hasScalar(domTradeForm, "ItemT")) {
            strictItemTByCandidate(tradeForm, domTradeForm)
        } else emptyMap()
        return ExchangeSnapshot(
            mode = mode,
            categories = categories,
            currentCategoryId = current,
            rows = tradeForm?.let { rows(it, itemTByCandidate, domTradeForm?.let { form -> hasScalar(form, "ItemT") } == true) }.orEmpty(),
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

    fun materializeStaticCatalogHtml(html: String, finalUrl: String, categoryCandidateId: String): String =
        materializeStaticCatalog(html, finalUrl, categoryCandidateId).html

    private fun materializeStaticCatalog(
        html: String,
        finalUrl: String,
        categoryCandidateId: String?,
    ): MaterializedCatalog {
        val document = HofHtmlParser.parse(html, finalUrl)
        val select = document.select("form select[name=${css(CATEGORY_FIELD)}]").filter { candidate ->
            candidate.closest("form")?.select("input[type=submit],button[type=submit],button:not([type])").orEmpty().isEmpty()
        }.singleOrNull() ?: throw ExchangeContractException("교환 분류 선택란을 하나로 확인하지 못했습니다.")
        val categories = select.select("option[value]").filterNot { it.hasAttr("disabled") }.map { option ->
            val value = option.attr("value").trim()
            if (!SAFE_CATEGORY_TOKEN.matches(value)) throw ExchangeContractException("안전하지 않은 교환 분류 값입니다.")
            CategoryContract("$CATEGORY_FIELD:$value", value, clean(option.text()).ifBlank { value })
        }
        if (categories.isEmpty() || categories.map(CategoryContract::id).distinct().size != categories.size) {
            throw ExchangeContractException("교환 분류 계약이 비어 있거나 중복되었습니다.")
        }
        val current = if (categoryCandidateId == null) {
            val selectedValue = select.`val`().trim()
            categories.singleOrNull { it.value == selectedValue } ?: categories.first()
        } else {
            categories.singleOrNull { it.id == categoryCandidateId }
                ?: throw ExchangeCategoryException(categoryCandidateId)
        }
        val catalog = parseStaticCatalog(document)
        val expected = categories.map(CategoryContract::value).filterNot { it == ALL_CATEGORY }.toSet()
        if (catalog.keys != expected) throw ExchangeContractException("교환 분류와 정적 품목 목록이 일치하지 않습니다.")
        val fragment = if (current.value == ALL_CATEGORY) {
            categories.asSequence().map(CategoryContract::value).filterNot { it == ALL_CATEGORY }
                .joinToString("") { catalog.getValue(it) }
        } else catalog.getValue(current.value)
        val actionForm = document.select("form").filter(::isRawStaticActionForm).singleOrNull()
            ?: throw ExchangeContractException("교환 제출 양식을 하나로 확인하지 못했습니다.")
        val list = actionForm.select("#list").filter { it.closest("form") === actionForm }.singleOrNull()
            ?: throw ExchangeContractException("교환 품목 목록 위치를 하나로 확인하지 못했습니다.")
        list.html("<table>$fragment</table><input type=\"hidden\" name=\"$LIST_FIELD\" value=\"${current.value}\">")
        return MaterializedCatalog(document.outerHtml(), categories, current.id)
    }

    private fun parseStaticCatalog(document: org.jsoup.nodes.Document): Map<String, String> {
        val script = document.select("script").map(Element::data).filter { LIST_FUNCTION_MARKER in it }.singleOrNull()
            ?: throw ExchangeContractException("정적 교환 목록 스크립트를 하나로 확인하지 못했습니다.")
        val body = LIST_FUNCTION.find(script)?.groupValues?.get(1)
            ?: throw ExchangeContractException("정적 교환 목록 함수를 확인하지 못했습니다.")
        val labels = LIST_CASE_LABEL.findAll(body).map { it.groupValues[1] }.toList()
        val assignments = LIST_CASE_ASSIGNMENT.findAll(body).map { match ->
            match.groupValues[1] to decodeStaticJavascriptExpression(match.groupValues[2])
        }.toList()
        if (labels.isEmpty() || labels.size != assignments.size || labels != assignments.map { it.first } || labels.distinct().size != labels.size) {
            throw ExchangeContractException("정적 교환 목록 case 계약이 모호합니다.")
        }
        return assignments.toMap()
    }

    private fun decodeStaticJavascriptExpression(value: String): String {
        val decoded = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            while (index < value.length && value[index].isWhitespace()) index++
            if (index >= value.length || value[index++] != '\'') {
                throw ExchangeContractException("정적 교환 목록에 문자열 이외의 표현식이 있습니다.")
            }
            var closed = false
            while (index < value.length) {
                val character = value[index++]
                if (character == '\'') { closed = true; break }
                if (character != '\\') { decoded.append(character); continue }
                if (index >= value.length) throw ExchangeContractException("끝나지 않은 JavaScript 문자열입니다.")
                decoded.append(when (val escaped = value[index++]) {
                    '\\' -> '\\'; '\'' -> '\''; '"' -> '"'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    else -> throw ExchangeContractException("지원하지 않는 JavaScript escape: $escaped")
                })
            }
            if (!closed) throw ExchangeContractException("끝나지 않은 JavaScript 문자열입니다.")
            while (index < value.length && value[index].isWhitespace()) index++
            if (index < value.length) {
                if (value[index++] != '+' || value.substring(index).isBlank()) {
                    throw ExchangeContractException("정적 교환 목록에 허용되지 않은 문자열 결합이 있습니다.")
                }
            }
        }
        return decoded.toString()
    }

    private fun isRawStaticActionForm(form: Element): Boolean {
        val controls = form.select("input,button,select,textarea").filter { it.closest("form") === form && !it.hasAttr("disabled") }
        val submits = controls.filter { control ->
            (control.tagName() == "input" && control.attr("type").equals("submit", true)) ||
                (control.tagName() == "button" && control.attr("type").let { it.isBlank() || it.equals("submit", true) })
        }
        return form.attr("method").equals("post", true) &&
            submits.singleOrNull()?.attr("name").equals("Create", true) &&
            controls.count { it.attr("name") == "ItemT" } == 1 &&
            controls.count { it.attr("name") == "amount" } == 1
    }

    private fun rows(
        form: ParsedTownForm,
        itemTByCandidate: Map<ParsedTownCandidate, String> = emptyMap(),
        requiresItemT: Boolean = false,
    ): List<ExchangeRow> = form.rows.mapIndexedNotNull { index, row ->
        val label = clean(row.label)
        if (label.isBlank() || HEADER.matches(label)) return@mapIndexedNotNull null
        val display = label.replace(LEADING_PRICE, "").trim()
        val detailSeparator = DETAIL_SEPARATOR.find(display)
        val itemLabel = detailSeparator?.let { display.substring(0, it.range.first).trim() }.orEmpty().ifBlank { display }
        val detail = detailSeparator?.let { display.substring(it.range.last + 1).trim().takeIf(String::isNotBlank) }
        val candidate = row.candidate?.takeIf { it.selectionType != TownSelectionType.SELECT }
        val itemT = candidate?.let(itemTByCandidate::get)
        ExchangeRow(
            id = candidate?.id ?: "display-$index",
            label = itemLabel,
            selectable = candidate != null && (!requiresItemT || itemT != null),
            detail = detail,
            cost = PRICE.find(label)?.groupValues?.get(1)?.number(),
            owned = OWNED.find(label)?.groupValues?.get(1)?.intNumber(),
            minQuantity = candidate?.minQuantity ?: 1,
            maxQuantity = candidate?.let { it.maxQuantity ?: MAX_TRADE_QUANTITY },
            itemT = itemT,
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

    /** HofFormParser의 DOM 순서를 보존해 동일한 Create form끼리도 dom-tie actionId와 정확히 연결한다. */
    private fun mapDomForms(document: org.jsoup.nodes.Document, page: ParsedTownPage): Map<String, Element> {
        var offset = 0
        val result = mutableMapOf<String, Element>()
        document.select("form").forEach { dom ->
            val variantCount = formParser.parse(dom.outerHtml(), document.baseUri()).forms.size
            page.forms.drop(offset).take(variantCount).forEach { result[it.actionId] = dom }
            offset += variantCount
        }
        return result.takeIf { offset == page.forms.size }.orEmpty()
    }

    /** HOF create 계열은 radio의 제한된 ItemT 직접 대입으로 recipe variant를 고른다. */
    private fun strictItemTByCandidate(form: ParsedTownForm, domForm: Element): Map<ParsedTownCandidate, String> =
        form.candidates.filter { it.selectionType != TownSelectionType.SELECT }.mapNotNull { candidate ->
            val controls = domForm.select("input[name=${css(candidate.inputName)}]").filter {
                it.closest("form") === domForm && it.attr("value").trim().ifBlank { "on" } == candidate.inputValue
            }
            val control = controls.singleOrNull() ?: return@mapNotNull null
            val sources = listOf(control.attr("onclick"), control.closest("tr")?.attr("onclick").orEmpty())
            val values = sources.flatMap { source -> ITEM_T_ASSIGNMENT.findAll(source).map { it.groupValues[3] }.toList() }.distinct()
            candidate to (values.singleOrNull() ?: return@mapNotNull null)
        }.toMap()

    private fun hasScalar(form: Element, name: String): Boolean = form.select("input[name=${css(name)}]")
        .count { it.closest("form") === form && !it.hasAttr("disabled") } == 1

    private fun annType(form: ParsedTownForm, section: String?): AnnAction? {
        val submit = form.submitFields.single()
        val semantic = "${section.orEmpty()} ${submit.name} ${submit.value}"
        return when {
            GIFT_WORD.containsMatchIn(semantic) -> AnnAction.GIVE_GIFT
            MODIFY_WORD.containsMatchIn(semantic) && form.candidates.any { it.selectionType != TownSelectionType.SELECT } -> AnnAction.MODIFY_ITEM
            else -> null
        }
    }

    private fun annSection(document: org.jsoup.nodes.Document, dom: Element?): String? {
        dom ?: return null
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
        // 실제 버튼 문구는 `Junk 등급 장비!`처럼 "교환"을 포함하지 않는다.
        val LEGACY_GRADE = Regex("(?:Junk|Old|Common|Uncommon|Rare|Historical).*(?:등급|grade)", RegexOption.IGNORE_CASE)
        val GIFT_WORD = Regex("Gift|선물", RegexOption.IGNORE_CASE)
        val MODIFY_WORD = Regex("Modify|Create|맡기|마제즈", RegexOption.IGNORE_CASE)
        val ANN_SECTION = Regex("앤에게.*(맡기|선물)|아이템을 맡긴다", RegexOption.IGNORE_CASE)
        val PRICE = Regex("[$]\\s*([\\d,]+)")
        val LEADING_PRICE = Regex("^[$]\\s*[\\d,]+\\s*")
        val DETAIL_SEPARATOR = Regex("\\s+/\\s+")
        val OWNED = Regex("[x×]\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
        val CURRENCY_WORD = Regex("보유|Coin|Statue|Shard|Ticket|Memo|Certificate|조각|주화|증표", RegexOption.IGNORE_CASE)
        val CURRENCY_QUANTITY = Regex("(?::|：|x|×)\\s*([\\d,]+)\\s*(?:개|보유중|보유 중)?", RegexOption.IGNORE_CASE)
        val HISTORY_WORD = Regex("교환|마제즈|결과|성공|실패", RegexOption.IGNORE_CASE)
        val HISTORY_MARKER = Regex("마제즈.*이력|최근.*결과|history", RegexOption.IGNORE_CASE)
        val HEADER = Regex("^(제작비|수수료|Item|아이템|제작비 Item|수수료 Item)$", RegexOption.IGNORE_CASE)
        val ITEM_T_ASSIGNMENT = Regex("(?:document\\.getElementById\\(\\s*(['\"])ItemT\\1\\s*\\)|(?:document\\.)?ItemT)\\s*\\.value\\s*=\\s*(['\"]?)([A-Za-z0-9_.:-]+)\\2", RegexOption.IGNORE_CASE)
        val STATIC_CATALOG_MODES = setOf(ExchangeMode.EMBLEM, ExchangeMode.EVENT)
        const val LIST_FUNCTION_MARKER = "function Listtype_create"
        val LIST_FUNCTION = Regex("function\\s+Listtype_create\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\n\\}\\s*function\\s+ChangeTypecreate\\b")
        val LIST_CASE_LABEL = Regex("case\\s+[\"']([A-Za-z0-9_-]{1,80})[\"']\\s*:")
        val LIST_CASE_ASSIGNMENT = Regex("case\\s+[\"']([A-Za-z0-9_-]{1,80})[\"']\\s*:\\s*html\\s*=\\s*([\\s\\S]*?)\\s*;\\s*break\\s*;")
        val SAFE_CATEGORY_TOKEN = Regex("[A-Za-z0-9_-]{1,80}")
        const val CATEGORY_FIELD = "type_create"
        const val LIST_FIELD = "list_type"
        const val ALL_CATEGORY = "all"
        const val MAX_TRADE_QUANTITY = 999
    }

    private data class CategoryContract(val id: String, val value: String, val label: String)
    private data class MaterializedCatalog(
        val html: String,
        val categories: List<CategoryContract>,
        val currentCategoryId: String,
    )
}

class ExchangeContractException(message: String) : IllegalStateException(message)
class ExchangeCategoryException(val candidateId: String) : IllegalArgumentException(candidateId)
