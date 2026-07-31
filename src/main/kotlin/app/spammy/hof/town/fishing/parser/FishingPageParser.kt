package app.spammy.hof.town.fishing.parser

import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingActionCandidate
import app.spammy.hof.town.fishing.model.FishingExchangeItem
import app.spammy.hof.town.fishing.model.FishingExchangeSnapshot
import app.spammy.hof.town.fishing.model.FishingOutcome
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.fishing.model.FishingSnapshot
import java.net.URI
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
        document.select("script, style, noscript").remove()
        val text = clean(document.text())
        val actions = page.forms.mapNotNull(::fishingAction)
        val battleAnchor = document.select("a[href]").firstOrNull { anchor ->
            clean(anchor.text()).contains("전투") || clean(anchor.text()).contains("Battle", ignoreCase = true)
        }
        val battleLink = battleAnchor?.let { safeBattleLink(it, finalUrl) }
        val outcomeText = (result?.messages.orEmpty() + result?.items.orEmpty().map { it.label } + text).joinToString(" ")
        val outcome = when {
            ESCAPED.containsMatchIn(outcomeText) -> FishingOutcome.ESCAPED
            CAUGHT.containsMatchIn(outcomeText) -> FishingOutcome.CAUGHT
            STARTED.containsMatchIn(outcomeText) -> FishingOutcome.STARTED
            result != null && (result.messages.isNotEmpty() || result.items.isNotEmpty()) -> FishingOutcome.INFORMATIONAL
            else -> null
        }
        val blocked = battleLink != null || BATTLE_BLOCKED.containsMatchIn(text)
        val available = if (blocked) emptyList() else actions
        return FishingSnapshot(
            notice = DATE_NOTICE.find(text)?.value,
            remainingCasts = REMAINING.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            waterStatus = WATER.find(text)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank),
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
            battleLink = battleLink,
            result = result,
        )
    }

    fun parseExchange(page: ParsedTownPage, result: ParsedTownResult? = null): FishingExchangeSnapshot {
        val form = page.forms.maxByOrNull { it.rows.size }
        return FishingExchangeSnapshot(
            actionId = form?.actionId,
            items = form?.rows.orEmpty().mapIndexed { index, row ->
                FishingExchangeItem(
                    id = row.candidate?.id ?: "display-$index",
                    name = row.label,
                    selectable = row.selectable,
                    detail = null,
                )
            },
            result = result,
        )
    }

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

    private fun safeBattleLink(anchor: Element, finalUrl: String): String? = runCatching {
        val uri = URI(finalUrl).resolve(anchor.attr("href"))
        val base = URI(finalUrl)
        uri.toString().takeIf { uri.scheme == base.scheme && uri.host == base.host }
    }.getOrNull()

    private fun clean(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private companion object {
        val DATE_NOTICE = Regex("날짜가 갱신되었습니다[.!]?")
        val REMAINING = Regex("오늘의 남은 낚시 횟수\\s*[:：]?\\s*(\\d+)회")
        val BAIT = Regex("(?<!빛나는 )미끼 경단\\s*[:：]?\\s*(\\d+)개")
        val SHINING_BAIT = Regex("빛나는 미끼\\s*[:：]?\\s*(\\d+)개")
        val ESCAPE_SECONDS = Regex("(?:도망|도망까지)[^0-9]{0,12}(\\d+)초")
        val COMBO = Regex("현재\\s*(\\d+)\\s*콤보")
        val WATER = Regex("(?:물의 상태|남은 낚시 횟수[^)]*\\))\\s*[:：]?\\s*([^。.!]+)")
        val LOCATION = Regex("낚시 장소\\s*[:：]?\\s*([^|]+)")
        val START = Regex(".*(?:낚시를 시작한다|낚시 시작|Start Fishing).*", RegexOption.IGNORE_CASE)
        val CATCH = Regex(".*(?:낚는다|Catch).*", RegexOption.IGNORE_CASE)
        val STATUS = Regex(".*(?:상태를 본다|상태 보기|Status).*", RegexOption.IGNORE_CASE)
        val FILTER = Regex(".*(?:거른다|거르기|Filter).*", RegexOption.IGNORE_CASE)
        val ESCAPED = Regex("도망(?:쳤|갔|가 버렸|쳐)|놓쳤|escaped", RegexOption.IGNORE_CASE)
        val CAUGHT = Regex("낚았다|획득했다|낚는(?:다|데)!|caught", RegexOption.IGNORE_CASE)
        val STARTED = Regex("지금부터 낚시를 시작|물고기 그림자|낚시를 시작합니다")
        val BATTLE_BLOCKED = Regex("(?:몬스터|전투몹).*(?:출몰|등장)|전투.*(?:완료|종료).*(?:낚시)")
    }
}
