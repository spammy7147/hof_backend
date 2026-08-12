package app.spammy.hof.town.fishing.parser

import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingActionCandidate
import app.spammy.hof.town.fishing.model.FishingBattleTarget
import app.spammy.hof.town.fishing.model.FishingCatchItem
import app.spammy.hof.town.fishing.model.FishingExchangeItem
import app.spammy.hof.town.fishing.model.FishingExchangeCategory
import app.spammy.hof.town.fishing.model.FishingExchangeSnapshot
import app.spammy.hof.town.fishing.model.FishingOutcome
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.fishing.model.FishingSnapshot
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.jsoup.Jsoup
import org.jsoup.nodes.Node
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import org.springframework.stereotype.Component

@Component
class FishingPageParser(
    private val formParser: HofFormParser = HofFormParser(),
) {
    fun parse(
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        result: ParsedTownResult? = null,
    ): FishingSnapshot {
        val document = Jsoup.parse(html, finalUrl)
        document.select("script, style, noscript, header, nav, .nav, .menu, #menu").remove()
        val contentRoot = document.selectFirst("#fishing, main, #content, .content") ?: document.body()
        val text = clean(contentRoot.text())
        val actions = page.forms.mapNotNull(::fishingAction)
        val battleMessagePresent = contentRoot.getAllElements().asSequence()
            .filter(::isRedWarning)
            .map { clean(it.ownText()) }
            .filter(String::isNotBlank)
            .any(BATTLE_BLOCKED::containsMatchIn)
        val detectedBattleTarget = contentRoot.select("a[href]")
            .asSequence()
            .filter { clean(it.text()).matches(Regex("^(전투|Battle)$", RegexOption.IGNORE_CASE)) }
            .mapNotNull { parseBattleTarget(it, finalUrl) }
            .firstOrNull()
        val fishingResultLines = parseFishingResultLines(contentRoot)
        val resultText = ((result?.messages.orEmpty() + result?.items.orEmpty().map { item -> item.label }) + fishingResultLines)
            .joinToString(" ")
        val outcome = if (result != null) when {
            ESCAPED.containsMatchIn(resultText) -> FishingOutcome.ESCAPED
            CAUGHT.containsMatchIn(resultText) -> FishingOutcome.CAUGHT
            result.messages.isNotEmpty() || result.items.isNotEmpty() -> FishingOutcome.INFORMATIONAL
            else -> FishingOutcome.INFORMATIONAL
        } else if (STARTED.containsMatchIn(text)) FishingOutcome.STARTED else null
        // HOF는 실제 낚시 전투 차단 문구를 빨간 글씨로 표시한다. 낚시 action 직후에는
        // 같은 문구가 본문 경고가 아니라 결과 영역으로만 반환될 수 있으므로 구조화된
        // action 결과도 함께 본다. 일반 안내문은 result가 아니므로 차단으로 오인하지 않는다.
        val blocked = battleMessagePresent || BATTLE_BLOCKED.containsMatchIn(resultText)
        val battleTarget = detectedBattleTarget.takeIf { blocked }
        val available = if (blocked) emptyList() else actions
        val catches = if (result != null) parseCaughtItems(document, fishingResultLines) else emptyList()
        return FishingSnapshot(
            notice = DATE_NOTICE.find(text)?.value,
            remainingCasts = REMAINING.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            waterStatus = fishingResultLines.takeIf(List<String>::isNotEmpty)?.joinToString("\n") ?: parseWaterStatus(text),
            baitCount = BAIT.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            shiningBaitCount = SHINING_BAIT.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            escapeSeconds = ESCAPE_SECONDS.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            combo = COMBO.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            locationName = LOCATION.find(text)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank) ?: "일반 낚시터",
            primaryAction = when {
                blocked -> FishingPrimaryAction.NONE
                available.any { it.action == FishingAction.CATCH } -> FishingPrimaryAction.CATCH
                available.any { it.action == FishingAction.START } -> FishingPrimaryAction.START
                else -> FishingPrimaryAction.NONE
            },
            availableActions = available,
            lastOutcome = outcome,
            blockedByBattle = blocked,
            battleTarget = battleTarget,
            catches = catches,
            result = result,
        )
    }

    fun parseExchange(
        html: String,
        finalUrl: String,
        @Suppress("UNUSED_PARAMETER") page: ParsedTownPage,
        result: ParsedTownResult? = null,
        categoryCandidateId: String? = null,
    ): FishingExchangeSnapshot {
        val materialized = materializeExchange(html, finalUrl, categoryCandidateId)
        val materializedPage = formParser.parse(materialized.html, finalUrl)
        val form = materializedPage.forms.singleOrNull { candidate ->
            candidate.submitFields.singleOrNull()?.name.equals(EXCHANGE_SUBMIT_FIELD, ignoreCase = true) &&
                candidate.candidates.all { it.inputName == EXCHANGE_ITEM_FIELD }
        } ?: throw FishingExchangeContractException("교환 제출 양식을 하나로 확인하지 못했습니다.")
        val document = Jsoup.parse(materialized.html, finalUrl)
        val domForm = findMaterializedExchangeForm(document, form)
            ?: throw FishingExchangeContractException("교환 제출 DOM을 하나로 확인하지 못했습니다.")
        val categories = materialized.categories.map { category ->
            FishingExchangeCategory(category.id, category.label, category.id == materialized.currentCategoryId)
        }
        val itemTByCandidate = strictExchangeItemT(form, domForm)
        return FishingExchangeSnapshot(
            actionId = form.actionId,
            categories = categories,
            currentCategoryId = materialized.currentCategoryId,
            items = form.rows.filter { row ->
                row.candidate == null || row.candidate.inputName == EXCHANGE_ITEM_FIELD
            }.mapIndexedNotNull { index, row ->
                val label = clean(row.label)
                if (label.isBlank() || EXCHANGE_HEADER.matches(label)) return@mapIndexedNotNull null
                val candidate = row.candidate
                val itemT = candidate?.let(itemTByCandidate::get)
                FishingExchangeItem(
                    id = candidate?.id ?: "display-$index",
                    name = label.replace(EXCHANGE_LEADING_PRICE, "").trim(),
                    selectable = candidate != null && itemT != null,
                    detail = null,
                    price = PRICE.find(label)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull(),
                    materials = MATERIAL.findAll(label).map { it.groupValues[1].trim() }.distinct().toList(),
                    itemT = itemT,
                )
            }.distinctBy(FishingExchangeItem::id),
            result = result,
        )
    }

    fun materializeExchangeHtml(html: String, finalUrl: String, categoryCandidateId: String): String =
        materializeExchange(html, finalUrl, categoryCandidateId).html

    private fun materializeExchange(
        html: String,
        finalUrl: String,
        categoryCandidateId: String?,
    ): MaterializedExchange {
        val document = Jsoup.parse(html, finalUrl)
        val categorySelects = document.select("form select[name=${cssValue(EXCHANGE_CATEGORY_FIELD)}]").filter { select ->
            select.closest("form")?.select("input[type=submit],button[type=submit],button:not([type])").orEmpty().isEmpty()
        }
        val select = categorySelects.singleOrNull()
            ?: throw FishingExchangeContractException("교환 분류 선택란을 하나로 확인하지 못했습니다.")
        val options = select.select("option[value]").filterNot { it.hasAttr("disabled") }.map { option ->
            val value = option.attr("value").trim()
            if (!SAFE_EXCHANGE_TOKEN.matches(value)) {
                throw FishingExchangeContractException("안전하지 않은 교환 분류 값입니다.")
            }
            ExchangeCategoryContract(
                id = "$EXCHANGE_CATEGORY_FIELD:$value",
                value = value,
                label = clean(option.text()).ifBlank { value },
            )
        }
        if (options.isEmpty() || options.map(ExchangeCategoryContract::id).distinct().size != options.size) {
            throw FishingExchangeContractException("교환 분류 계약이 비어 있거나 중복되었습니다.")
        }
        val current = if (categoryCandidateId == null) {
            val selectedValue = select.`val`().trim()
            options.singleOrNull { it.value == selectedValue } ?: options.first()
        } else {
            options.singleOrNull { it.id == categoryCandidateId }
                ?: throw FishingExchangeCategoryException(categoryCandidateId)
        }
        val catalog = parseStaticExchangeCatalog(document)
        val expectedCases = options.map(ExchangeCategoryContract::value).filterNot { it == EXCHANGE_ALL_CATEGORY }.toSet()
        if (catalog.keys != expectedCases) {
            throw FishingExchangeContractException("교환 분류와 정적 품목 목록이 일치하지 않습니다.")
        }
        val fragment = if (current.value == EXCHANGE_ALL_CATEGORY) {
            options.asSequence().map(ExchangeCategoryContract::value).filterNot { it == EXCHANGE_ALL_CATEGORY }
                .joinToString(separator = "") { catalog.getValue(it) }
        } else {
            catalog.getValue(current.value)
        }
        val actionForms = document.select("form").filter(::isRawExchangeActionForm)
        val actionForm = actionForms.singleOrNull()
            ?: throw FishingExchangeContractException("교환 제출 양식을 하나로 확인하지 못했습니다.")
        val list = actionForm.select("#list").filter { it.closest("form") === actionForm }.singleOrNull()
            ?: throw FishingExchangeContractException("교환 품목 목록 위치를 하나로 확인하지 못했습니다.")
        list.html("<table>$fragment</table><input type=\"hidden\" name=\"$EXCHANGE_LIST_FIELD\" value=\"${current.value}\">")
        return MaterializedExchange(document.outerHtml(), options, current.id)
    }

    private fun parseStaticExchangeCatalog(document: org.jsoup.nodes.Document): Map<String, String> {
        val scripts = document.select("script").map(Element::data).filter { LIST_FUNCTION_MARKER in it }
        val script = scripts.singleOrNull()
            ?: throw FishingExchangeContractException("정적 교환 목록 스크립트를 하나로 확인하지 못했습니다.")
        val functionBody = LIST_FUNCTION.find(script)?.groupValues?.get(1)
            ?: throw FishingExchangeContractException("정적 교환 목록 함수를 확인하지 못했습니다.")
        val labels = LIST_CASE_LABEL.findAll(functionBody).map { it.groupValues[1] }.toList()
        val assignments = LIST_CASE_ASSIGNMENT.findAll(functionBody).map { match ->
            match.groupValues[1] to decodeStaticJavascriptExpression(match.groupValues[2])
        }.toList()
        if (labels.isEmpty() || labels.size != assignments.size || labels != assignments.map(Pair<String, String>::first) ||
            labels.distinct().size != labels.size
        ) throw FishingExchangeContractException("정적 교환 목록 case 계약이 모호합니다.")
        return assignments.toMap()
    }

    private fun decodeStaticJavascriptExpression(value: String): String {
        val decoded = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            while (index < value.length && value[index].isWhitespace()) index++
            if (index >= value.length || value[index++] != '\'') {
                throw FishingExchangeContractException("정적 교환 목록에 문자열 이외의 표현식이 있습니다.")
            }
            var closed = false
            while (index < value.length) {
                val character = value[index++]
                if (character == '\'') {
                    closed = true
                    break
                }
                if (character != '\\') {
                    decoded.append(character)
                    continue
                }
                if (index >= value.length) throw FishingExchangeContractException("끝나지 않은 JavaScript 문자열입니다.")
                decoded.append(when (val escaped = value[index++]) {
                    '\\' -> '\\'
                    '\'' -> '\''
                    '"' -> '"'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    else -> throw FishingExchangeContractException("지원하지 않는 JavaScript escape: $escaped")
                })
            }
            if (!closed) throw FishingExchangeContractException("끝나지 않은 JavaScript 문자열입니다.")
            while (index < value.length && value[index].isWhitespace()) index++
            if (index < value.length) {
                if (value[index++] != '+') {
                    throw FishingExchangeContractException("정적 교환 목록에 허용되지 않은 연산이 있습니다.")
                }
                if (value.substring(index).isBlank()) {
                    throw FishingExchangeContractException("정적 교환 목록 문자열 결합이 끝나지 않았습니다.")
                }
            }
        }
        return decoded.toString()
    }

    private fun isRawExchangeActionForm(form: Element): Boolean {
        val owned = form.select("input,button,select,textarea").filter { it.closest("form") === form && !it.hasAttr("disabled") }
        val submits = owned.filter { control ->
            (control.tagName() == "input" && control.attr("type").equals("submit", true)) ||
                (control.tagName() == "button" && control.attr("type").let { it.isBlank() || it.equals("submit", true) })
        }
        return form.attr("method").equals("post", true) &&
            submits.singleOrNull()?.attr("name").equals(EXCHANGE_SUBMIT_FIELD, true) &&
            owned.count { it.attr("name") == EXCHANGE_ITEM_T_FIELD } == 1 &&
            owned.count { it.attr("name") == EXCHANGE_AMOUNT_FIELD } == 1
    }

    private fun strictExchangeItemT(form: ParsedTownForm, domForm: Element): Map<app.spammy.hof.town.common.model.ParsedTownCandidate, String> =
        form.candidates.filter { it.inputName == EXCHANGE_ITEM_FIELD }.mapNotNull { candidate ->
            val control = domForm.select("input[name=${cssValue(EXCHANGE_ITEM_FIELD)}]").filter {
                it.closest("form") === domForm && it.attr("value").trim().ifBlank { "on" } == candidate.inputValue
            }.singleOrNull() ?: return@mapNotNull null
            val assignments = listOf(control.attr("onclick"), control.closest("tr")?.attr("onclick").orEmpty())
                .flatMap { source -> ITEM_T_ASSIGNMENT.findAll(source).map { it.groupValues[3] }.toList() }
                .distinct()
            candidate to (assignments.singleOrNull() ?: return@mapNotNull null)
        }.toMap()

    private fun findMaterializedExchangeForm(document: org.jsoup.nodes.Document, form: ParsedTownForm): Element? {
        val forms = document.select("form").filter { dom ->
            val items = dom.select("input[name=${cssValue(EXCHANGE_ITEM_FIELD)}]").filter { it.closest("form") === dom }
            isRawExchangeActionForm(dom) &&
                (items.isNotEmpty() || form.candidates.none { it.inputName == EXCHANGE_ITEM_FIELD }) && equivalentActionUrl(form, dom)
        }
        return forms.singleOrNull()
    }

    private fun equivalentActionUrl(form: ParsedTownForm, dom: Element): Boolean = dom.attr("action").let { action ->
        action.isBlank() || form.actionUrl.endsWith(action.substringAfterLast('/')) || form.actionUrl.contains(action)
    }

    private fun cssValue(value: String) = "'${value.replace("'", "\\'")}'"

    fun actionFor(form: ParsedTownForm): FishingAction? = fishingAction(form)?.action

    private fun fishingAction(form: ParsedTownForm): FishingActionCandidate? {
        val label = clean((form.submitFields.map { it.value } + form.rows.map { it.label }).joinToString(" "))
        val action = when {
            START.matches(label) -> FishingAction.START
            CATCH.matches(label) -> FishingAction.CATCH
            STATUS.matches(label) -> FishingAction.STATUS
            FILTER.matches(label) -> FishingAction.FILTER
            else -> null
        }
        return action?.let { FishingActionCandidate(it, form.actionId) }
    }

    private fun parseBattleTarget(anchor: Element, finalUrl: String): FishingBattleTarget? = runCatching {
        val uri = URI(finalUrl).resolve(anchor.attr("href"))
        val base = URI(finalUrl)
        if (uri.scheme != base.scheme || uri.host != base.host) return@runCatching null
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            pieces.firstOrNull()?.takeIf(String::isNotBlank)?.let { key ->
                URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(pieces.getOrElse(1) { "" }, StandardCharsets.UTF_8)
            }
        }.toMap()
        val (category, code) = when {
            !query["sp_common"].isNullOrBlank() -> "adventure_map" to query.getValue("sp_common")
            !query["union"].isNullOrBlank() -> "union" to query.getValue("union")
            !query["raid_common"].isNullOrBlank() -> "raid" to query.getValue("raid_common")
            !query["common"].isNullOrBlank() -> "battle_map" to query.getValue("common")
            else -> return@runCatching null
        }
        FishingBattleTarget(category, code.take(120))
    }.getOrNull()

    private fun parseCaughtItems(document: org.jsoup.nodes.Document, fishingResultLines: List<String>): List<FishingCatchItem> {
        val roots = document.select("#result, [data-town-result], .result, .message, .success")
        val lines = roots.flatMap { root ->
            root.select("li, tr, p, [data-result-item], .result-item, .item").ifEmpty { listOf(root) }.map { clean(it.text()) }
        } + fishingResultLines
        return lines.mapNotNull { line ->
                if (!CAUGHT.containsMatchIn(line)) return@mapNotNull null
                val name = line.substringBefore('(').substringBefore(" x").trim()
                FishingCatchItem(
                    name = name,
                    quantity = QUANTITY.findAll(line).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 1,
                    remainingUses = USES.find(line)?.groupValues?.get(1)?.toIntOrNull(),
                    effect = EFFECT.find(line)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank),
                ).takeIf { it.name.isNotBlank() }
            }.distinctBy { listOf(it.name, it.quantity, it.remainingUses, it.effect) }
    }

    /** HOF 낚시 결과는 별도 result wrapper 없이 '낚시 교환소'와 '물의 상태' 사이에 출력된다. */
    private fun parseFishingResultLines(contentRoot: Element): List<String> {
        val start = contentRoot.select("a").firstOrNull { clean(it.text()) == "낚시 교환소" } ?: return emptyList()
        val end = contentRoot.getAllElements().firstOrNull { clean(it.ownText()) == "물의 상태" } ?: return emptyList()
        val buffer = StringBuilder()
        var collecting = false
        contentRoot.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node === end) collecting = false
                if (!collecting) return
                when {
                    node is TextNode -> buffer.append(node.text())
                    node is Element && (node.tagName() == "br" || node.tagName() in RESULT_BLOCK_TAGS) -> buffer.append('\n')
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node === start) collecting = true
                if (collecting && node is Element && node.tagName() in RESULT_BLOCK_TAGS) buffer.append('\n')
            }
        })
        return buffer.lineSequence()
            .map(::clean)
            .filter(String::isNotBlank)
            .filterNot { FISHING_RESULT_NOISE.matches(it) }
            .distinct()
            .take(MAX_FISHING_RESULT_LINES)
            .toList()
    }

    private fun parseWaterStatus(text: String): String? {
        val remaining = REMAINING.find(text) ?: return null
        val suffix = text.substring(remaining.range.last + 1).trimStart(' ', ')', '）', ':', '：')
        val withoutFollowingSections = WATER_STATUS_ENDINGS.fold(suffix) { current, marker -> current.substringBefore(marker) }
        val sentenceEnd = WATER_STATUS_SENTENCE_END.find(withoutFollowingSections)?.range?.first
        val status = withoutFollowingSections.substring(0, sentenceEnd ?: withoutFollowingSections.length)
            .replace(REMAINING_STATUS_PREFIX, "")
            .trim()
        return status.takeIf { candidate ->
            candidate.isNotBlank() &&
                !FISHING_ACTION_LABEL.matches(candidate) &&
                candidate.length <= MAX_WATER_STATUS_LENGTH
        }
    }

    private fun isRedWarning(element: Element): Boolean {
        val color = element.attr("color").trim().lowercase()
        if (color in RED_COLOR_VALUES) return true
        val styleColor = CSS_COLOR.find(element.attr("style"))?.groupValues?.get(1)?.lowercase()
        return styleColor in RED_COLOR_VALUES
    }

    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private companion object {
        val DATE_NOTICE = Regex("날짜가 갱신되었습니다[.!]?")
        val REMAINING = Regex("오늘의 남은 낚시 횟수\\s*[:：]?\\s*(\\d+)회")
        val REMAINING_STATUS_PREFIX = Regex("^[（(]?\\s*오늘의 남은 낚시 횟수\\s*[:：]?\\s*\\d+회\\s*[）)]?\\s*")
        val BAIT = Regex("(?<!빛나는 )미끼 경단\\s*[:：]?\\s*(\\d+)개")
        val SHINING_BAIT = Regex("빛나는 미끼\\s*[:：]?\\s*(\\d+)개")
        val ESCAPE_SECONDS = Regex("(?:도망|도망까지)[^0-9]{0,12}(\\d+)초")
        val COMBO = Regex("현재\\s*(\\d+)\\s*콤보")
        val LOCATION = Regex("낚시 장소\\s*[:：]?\\s*([^|]+)")
        val START = Regex(".*(?:낚시를 시작한다|낚시 시작|Start Fishing).*", RegexOption.IGNORE_CASE)
        val CATCH = Regex(".*(?:낚는다|Catch).*", RegexOption.IGNORE_CASE)
        val STATUS = Regex(".*(?:상태를 본다|상태 보기|Status).*", RegexOption.IGNORE_CASE)
        val FILTER = Regex(".*(?:거른다|거르기|Filter).*", RegexOption.IGNORE_CASE)
        val ESCAPED = Regex("도망(?:쳤|갔|가 버렸|쳐)|놓쳤|escaped", RegexOption.IGNORE_CASE)
        val CAUGHT = Regex("낚았다|획득했다|낚는데!|caught", RegexOption.IGNORE_CASE)
        val STARTED = Regex("지금부터 낚시를 시작|물고기 그림자|낚시를 시작합니다")
        val BATTLE_BLOCKED = Regex("(?:전투몹|몬스터)[^。.!?]*(?:출몰|등장|낚시(?:가|를)?\\s*(?:할 수 없|불가능))", RegexOption.IGNORE_CASE)
        val CSS_COLOR = Regex("(?:^|;)\\s*color\\s*:\\s*([^;\\s]+)", RegexOption.IGNORE_CASE)
        val RED_COLOR_VALUES = setOf("red", "#f00", "#ff0000", "rgb(255,0,0)", "rgb(255, 0, 0)")
        val FISHING_ACTION_LABEL = Regex("^(?:낚시를 시작한다|낚시 시작|낚는다|상태를 본다|거른다)$")
        val FISHING_RESULT_NOISE = Regex("^(?:낚시터|낚시 교환소)$")
        val RESULT_BLOCK_TAGS = setOf("p", "div", "li", "tr", "section")
        const val MAX_FISHING_RESULT_LINES = 8
        val WATER_STATUS_ENDINGS = listOf(
            "미끼 경단",
            "빛나는 미끼",
            "낚시 장소",
            "낚시를 시작한다",
            "낚는다",
            "상태를 본다",
            "거른다",
            "낚시의 방법",
            "UpDate",
        )
        val WATER_STATUS_SENTENCE_END = Regex("[。.!?]")
        const val MAX_WATER_STATUS_LENGTH = 160
        val PRICE = Regex("[$]\\s*([\\d,]+)")
        val MATERIAL = Regex("([A-Za-z가-힣][A-Za-z가-힣 '\\-]{1,60})\\s*[x×]\\s*\\d+")
        val EXCHANGE_HEADER = Regex("^(제작비|제작비 Item|Item|아이템|수수료)(?:\\s+(Item|아이템))?$", RegexOption.IGNORE_CASE)
        val EXCHANGE_LEADING_PRICE = Regex("^[$]\\s*[\\d,]+\\s*")
        val ITEM_T_ASSIGNMENT = Regex("(?:document\\.getElementById\\(\\s*(['\"])ItemT\\1\\s*\\)|(?:document\\.)?ItemT)\\s*\\.value\\s*=\\s*(['\"]?)([A-Za-z0-9_.:-]+)\\2", RegexOption.IGNORE_CASE)
        const val LIST_FUNCTION_MARKER = "function Listtype_create"
        val LIST_FUNCTION = Regex("function\\s+Listtype_create\\s*\\([^)]*\\)\\s*\\{([\\s\\S]*?)\\n\\}\\s*function\\s+ChangeTypecreate\\b")
        val LIST_CASE_LABEL = Regex("case\\s+[\"']([A-Za-z0-9_-]{1,80})[\"']\\s*:")
        val LIST_CASE_ASSIGNMENT = Regex("case\\s+[\"']([A-Za-z0-9_-]{1,80})[\"']\\s*:\\s*html\\s*=\\s*([\\s\\S]*?)\\s*;\\s*break\\s*;")
        val SAFE_EXCHANGE_TOKEN = Regex("[A-Za-z0-9_-]{1,80}")
        const val EXCHANGE_CATEGORY_FIELD = "type_create"
        const val EXCHANGE_ITEM_FIELD = "ItemNo"
        const val EXCHANGE_ITEM_T_FIELD = "ItemT"
        const val EXCHANGE_AMOUNT_FIELD = "amount"
        const val EXCHANGE_LIST_FIELD = "list_type"
        const val EXCHANGE_SUBMIT_FIELD = "Create"
        const val EXCHANGE_ALL_CATEGORY = "all"
        val USES = Regex("\\((\\d+)회 사용가능\\)")
        val QUANTITY = Regex("[x×]\\s*(\\d+)")
        val EFFECT = Regex("사용 효과\\s*[:：]\\s*([^)]+)")
    }

    private data class ExchangeCategoryContract(val id: String, val value: String, val label: String)
    private data class MaterializedExchange(
        val html: String,
        val categories: List<ExchangeCategoryContract>,
        val currentCategoryId: String,
    )
}

class FishingExchangeContractException(message: String) : IllegalStateException(message)
class FishingExchangeCategoryException(val candidateId: String) : IllegalArgumentException(candidateId)
