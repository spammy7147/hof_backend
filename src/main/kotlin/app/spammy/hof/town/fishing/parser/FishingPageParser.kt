package app.spammy.hof.town.fishing.parser

import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
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
class FishingPageParser {
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
        page: ParsedTownPage,
        result: ParsedTownResult? = null,
    ): FishingExchangeSnapshot {
        val document = Jsoup.parse(html, finalUrl)
        val matchingForms = page.forms.filter { candidate ->
            candidate.submitFields.singleOrNull()?.name.equals("Create", ignoreCase = true) &&
                candidate.candidates.any { it.inputName == EXCHANGE_CATEGORY_FIELD } &&
                candidate.candidates.any { it.inputName == EXCHANGE_ITEM_FIELD }
        }.ifEmpty {
            page.forms.filter { candidate ->
                candidate.submitFields.singleOrNull()?.name.equals("Create", ignoreCase = true) &&
                    candidate.candidates.any { it.inputName == EXCHANGE_CATEGORY_FIELD }
            }
        }
        // HOF의 긴 교환 목록은 같은 form 안에 Create 버튼을 위·아래로 반복할 수 있다.
        // HofFormParser는 submit마다 action을 하나씩 만들므로, 완전히 같은 계약이면 첫 action을 사용한다.
        val form = matchingForms.firstOrNull()?.takeIf { first ->
            matchingForms.all { candidate -> sameExchangeContract(first, candidate) }
        }
        val domForm = form?.let { parsed -> findExchangeDomForm(document, parsed) }
        val categories = parseExchangeCategories(form, domForm)
        val currentCategoryId = categories.firstOrNull(FishingExchangeCategory::current)?.id
        val itemTByCandidate = if (form != null && domForm != null) strictExchangeItemT(form, domForm) else emptyMap()
        return FishingExchangeSnapshot(
            actionId = form?.actionId,
            categories = categories,
            currentCategoryId = currentCategoryId,
            items = form?.rows.orEmpty().filter { row ->
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

    private fun parseExchangeCategories(form: ParsedTownForm?, domForm: Element?): List<FishingExchangeCategory> {
        if (form == null || domForm == null) return emptyList()
        val select = equivalentSelect(domForm, "select[name=${cssValue(EXCHANGE_CATEGORY_FIELD)}]")
            ?: return emptyList()
        return select.select("option[value]").filterNot { it.hasAttr("disabled") }.mapNotNull { option ->
            val candidate = uniqueCandidate(form, EXCHANGE_CATEGORY_FIELD, option.attr("value"))
                ?: return@mapNotNull null
            FishingExchangeCategory(
                candidate.id,
                clean(option.text()).ifBlank { option.attr("value") },
                option.hasAttr("selected") || select.`val`() == option.attr("value"),
            )
        }.distinctBy(FishingExchangeCategory::id)
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

    private fun findExchangeDomForm(document: org.jsoup.nodes.Document, form: ParsedTownForm): Element? {
        val forms = document.select("form").filter { dom ->
            val category = equivalentSelect(dom, "select[name=${cssValue(EXCHANGE_CATEGORY_FIELD)}]")
            val items = dom.select("input[name=${cssValue(EXCHANGE_ITEM_FIELD)}]").filter { it.closest("form") === dom }
            val submits = dom.select("input[name=Create],button[name=Create]").filter { it.closest("form") === dom }
            category != null && (items.isNotEmpty() || form.candidates.none { it.inputName == EXCHANGE_ITEM_FIELD }) &&
                submits.isNotEmpty() && equivalentActionUrl(form, dom)
        }
        val first = forms.firstOrNull() ?: return null
        return first.takeIf { forms.all { candidate -> exchangeDomSignature(candidate) == exchangeDomSignature(first) } }
    }

    private fun sameExchangeContract(left: ParsedTownForm, right: ParsedTownForm): Boolean =
        left.method == right.method &&
            left.actionUrl == right.actionUrl &&
            left.hiddenFields == right.hiddenFields &&
            left.submitFields.map { it.name } == right.submitFields.map { it.name } &&
            left.candidates.map { it.inputName to it.inputValue } == right.candidates.map { it.inputName to it.inputValue }

    private fun equivalentActionUrl(form: ParsedTownForm, dom: Element): Boolean = dom.attr("action").let { action ->
        action.isBlank() || form.actionUrl.endsWith(action.substringAfterLast('/')) || form.actionUrl.contains(action)
    }

    private fun exchangeDomSignature(form: Element): List<Any> = listOf(
        form.attr("method").lowercase(),
        form.attr("action"),
        selectSignature(equivalentSelect(form, "select[name=${cssValue(EXCHANGE_CATEGORY_FIELD)}]")!!),
        form.select("input[name=${cssValue(EXCHANGE_ITEM_FIELD)}]").filter { it.closest("form") === form }
            .map { it.attr("value").trim().ifBlank { "on" } },
    )

    private fun equivalentSelect(form: Element, selector: String): Element? {
        val selects = form.select(selector).filter { it.closest("form") === form }
        val first = selects.firstOrNull() ?: return null
        val signature = selectSignature(first)
        return first.takeIf { selects.all { selectSignature(it) == signature } }
    }

    private fun selectSignature(select: Element): List<Triple<String, String, Boolean>> = select.select("option").map { option ->
        Triple(option.attr("value"), clean(option.text()), option.hasAttr("selected") || select.`val`() == option.attr("value"))
    }

    private fun uniqueCandidate(form: ParsedTownForm, field: String, value: String) = form.candidates
        .filter { it.inputName == field && it.inputValue == value }
        .distinctBy { listOf(it.id, it.inputName, it.inputValue, it.selectionType.name) }
        .singleOrNull()

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
        const val EXCHANGE_CATEGORY_FIELD = "type_create"
        const val EXCHANGE_ITEM_FIELD = "ItemNo"
        val USES = Regex("\\((\\d+)회 사용가능\\)")
        val QUANTITY = Regex("[x×]\\s*(\\d+)")
        val EFFECT = Regex("사용 효과\\s*[:：]\\s*([^)]+)")
    }
}
