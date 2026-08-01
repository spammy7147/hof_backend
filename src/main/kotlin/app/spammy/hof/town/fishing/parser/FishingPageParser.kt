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
import org.jsoup.nodes.Element
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
            .map { clean(it.ownText()) }
            .filter(String::isNotBlank)
            .any(BATTLE_BLOCKED::containsMatchIn)
        val detectedBattleTarget = contentRoot.select("a[href]")
            .asSequence()
            .filter { clean(it.text()).matches(Regex("^(전투|Battle)$", RegexOption.IGNORE_CASE)) }
            .mapNotNull { parseBattleTarget(it, finalUrl) }
            .firstOrNull()
        val resultText = result?.let { (it.messages + it.items.map { item -> item.label }).joinToString(" ") }
        val outcome = if (result != null) when {
            ESCAPED.containsMatchIn(resultText.orEmpty()) -> FishingOutcome.ESCAPED
            CAUGHT.containsMatchIn(resultText.orEmpty()) -> FishingOutcome.CAUGHT
            result.messages.isNotEmpty() || result.items.isNotEmpty() -> FishingOutcome.INFORMATIONAL
            else -> FishingOutcome.INFORMATIONAL
        } else if (STARTED.containsMatchIn(text)) FishingOutcome.STARTED else null
        // 안내문에 포함된 일반적인 "전투/낚시" 문구만으로는 전투 상태가 아니다.
        // 실제 차단 문구와 낚시 전투 target이 함께 있을 때만 낚시를 막는다.
        val blocked = battleMessagePresent && detectedBattleTarget != null
        val battleTarget = detectedBattleTarget.takeIf { blocked }
        val available = if (blocked) emptyList() else actions
        val catches = if (result != null) parseCaughtItems(document) else emptyList()
        return FishingSnapshot(
            notice = DATE_NOTICE.find(text)?.value,
            remainingCasts = REMAINING.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            waterStatus = parseWaterStatus(text),
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
        val form = page.forms.singleOrNull { candidate ->
            candidate.submitFields.singleOrNull()?.name.equals("Create", ignoreCase = true) &&
                candidate.candidates.any { it.inputName == EXCHANGE_CATEGORY_FIELD }
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
        val select = domForm.select("select[name=${cssValue(EXCHANGE_CATEGORY_FIELD)}]")
            .filter { it.closest("form") === domForm }.singleOrNull() ?: return emptyList()
        return select.select("option[value]").filterNot { it.hasAttr("disabled") }.mapNotNull { option ->
            val candidate = form.candidates.singleOrNull {
                it.inputName == EXCHANGE_CATEGORY_FIELD && it.inputValue == option.attr("value")
            } ?: return@mapNotNull null
            FishingExchangeCategory(
                candidate.id,
                clean(option.text()).ifBlank { option.attr("value") },
                option.hasAttr("selected") || select.`val`() == option.attr("value"),
            )
        }
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

    private fun findExchangeDomForm(document: org.jsoup.nodes.Document, form: ParsedTownForm): Element? =
        document.select("form").filter { dom ->
            val categories = dom.select("select[name=${cssValue(EXCHANGE_CATEGORY_FIELD)}]").filter { it.closest("form") === dom }
            val submits = dom.select("input[name=Create],button[name=Create]").filter { it.closest("form") === dom }
            categories.size == 1 && submits.size == 1 && form.actionUrl.isNotBlank()
        }.singleOrNull()

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

    private fun parseCaughtItems(document: org.jsoup.nodes.Document): List<FishingCatchItem> {
        val roots = document.select("#result, [data-town-result], .result, .message, .success")
        return roots.flatMap { root -> root.select("li, tr, p, [data-result-item], .result-item, .item").ifEmpty { listOf(root) } }
            .mapNotNull { element ->
                val line = clean(element.text())
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

    private fun parseWaterStatus(text: String): String? {
        val remaining = REMAINING.find(text) ?: return null
        val suffix = text.substring(remaining.range.last + 1).trimStart(' ', ')', '）', ':', '：')
        val withoutFollowingSections = WATER_STATUS_ENDINGS.fold(suffix) { current, marker -> current.substringBefore(marker) }
        val sentenceEnd = WATER_STATUS_SENTENCE_END.find(withoutFollowingSections)?.range?.first
        val status = withoutFollowingSections.substring(0, sentenceEnd ?: withoutFollowingSections.length).trim()
        return status.takeIf { candidate ->
            candidate.isNotBlank() &&
                !FISHING_ACTION_LABEL.matches(candidate) &&
                candidate.length <= MAX_WATER_STATUS_LENGTH
        }
    }

    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private companion object {
        val DATE_NOTICE = Regex("날짜가 갱신되었습니다[.!]?")
        val REMAINING = Regex("오늘의 남은 낚시 횟수\\s*[:：]?\\s*(\\d+)회")
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
        val BATTLE_BLOCKED = Regex("(?:전투몹|몬스터)[^。.!?]*(?:출몰했습니다|등장했습니다)")
        val FISHING_ACTION_LABEL = Regex("^(?:낚시를 시작한다|낚시 시작|낚는다|상태를 본다|거른다)$")
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
