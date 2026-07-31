package app.spammy.hof.town.reward.parser

import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.reward.model.*
import java.security.MessageDigest
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.springframework.stereotype.Component

@Component
class OrbExchangeParser {
    fun parse(
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        result: ParsedTownResult? = null,
    ): OrbExchangeSnapshot {
        val text = clean(Jsoup.parse(html, finalUrl).body().text())
        return OrbExchangeSnapshot(
            displayedOrbs = parseCounts(text),
            orbCountsEstimated = false,
            remainingRewards = REMAINING_TOTAL.find(text)?.groupValues?.get(1)?.number(),
            rewardMonth = MONTH.find(text)?.let { "${it.groupValues[1]}년 ${it.groupValues[2]}월" },
            rewards = parseRewards(html, finalUrl),
            actions = page.forms.mapNotNull(::actionCandidate)
                .groupBy(OrbActionCandidate::action)
                .mapNotNull { (_, matches) -> matches.singleOrNull() }
                .sortedBy { it.action.repetitions },
            result = result,
        )
    }

    fun parseExchange(
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        result: ParsedTownResult,
        before: OrbExchangeSnapshot,
        requestedDraws: Int,
    ): OrbExchangeSnapshot {
        require(requestedDraws in setOf(1, 5))
        val parsed = parse(html, finalUrl, page, result)
        val resultSection = resultSectionNodes(html, finalUrl)
        val explicitOutcomes = parseExplicitOutcomes(resultSection)
        val failureLines = explicitOutcomes.filterNot(OrbExchangeOutcome::success).map(OrbExchangeOutcome::text)
            .ifEmpty { parseFailures(resultSection, result) }
        val failed = failureLines.size.coerceAtMost(requestedDraws)
        val successful = (requestedDraws - failed).coerceAtLeast(0)
        val outcomes = if (explicitOutcomes.any(OrbExchangeOutcome::success)) {
            explicitOutcomes
        } else {
            inferOutcomes(before.rewards, parsed.rewards, successful) +
                failureLines.map { OrbExchangeOutcome(it, 1, success = false, inferred = false) }
        }
        val safeResult = ParsedTownResult(
            messages = result.messages.filterNot { ORB_SHORTAGE.containsMatchIn(it) }.distinct().take(20),
            items = emptyList(),
        )
        return parsed.copy(
            displayedOrbs = before.displayedOrbs.deduct(successful),
            orbCountsEstimated = successful > 0 && before.displayedOrbs.run { red != null || blue != null || green != null },
            actions = parsed.actions.ifEmpty { before.actions },
            outcomes = outcomes,
            lastAction = OrbExchangeAction.entries.single { it.repetitions == requestedDraws },
            result = safeResult,
        )
    }

    fun action(form: ParsedTownForm): OrbExchangeAction? = when (form.submitFields.singleOrNull()?.name) {
        "TestBTN2" -> OrbExchangeAction.ONE
        "TestBTN3" -> OrbExchangeAction.FIVE
        else -> null
    }

    private fun actionCandidate(form: ParsedTownForm): OrbActionCandidate? = action(form)?.let { action ->
        OrbActionCandidate(action, clean(form.submitFields.single().value), form.actionId)
    }

    private fun parseCounts(text: String) = OrbCounts(orb(text, "Red"), orb(text, "Blue"), orb(text, "Green"))
    private fun orb(text: String, color: String) = Regex("$color\\s*Orb\\s*[:：]\\s*([\\d,]+)\\s*개", RegexOption.IGNORE_CASE)
        .find(text)?.groupValues?.get(1)?.number()

    private fun parseRewards(html: String, finalUrl: String): List<OrbLimitedReward> {
        val document = Jsoup.parse(html, finalUrl)
        val candidates = document.select("body *").filter { element ->
            REMAINING_REWARD.containsMatchIn(clean(element.text())) && element.children().none { child ->
                REMAINING_REWARD.containsMatchIn(clean(child.text()))
            }
        }
        return candidates.mapNotNull { smallest ->
            val element = generateSequence(smallest as Element?) { current ->
                current.parent()?.takeIf { it.tagName() !in setOf("body", "html") }
            }.take(4).firstOrNull { candidate -> rewardName(clean(candidate.text())).isNotBlank() } ?: smallest
            val text = clean(element.text())
            val match = REMAINING_REWARD.find(text) ?: return@mapNotNull null
            val remainingRaw = match.groupValues[1]
            val name = rewardName(text).take(200)
            name.takeIf(String::isNotBlank)?.let {
                OrbLimitedReward(key = sha256(normalizeRewardName(it)), name = it, remaining = remainingRaw.number())
            }
        }.distinctBy(OrbLimitedReward::key)
    }

    private fun inferOutcomes(before: List<OrbLimitedReward>, after: List<OrbLimitedReward>, successful: Int): List<OrbExchangeOutcome> {
        // Result 응답에 List가 생략되거나 부분 렌더링된 경우 감소가 없었다고 볼 수 없다.
        // 교환 전 카탈로그를 모두 다시 관측했을 때만 남은 수량 차이와 무제한 꽝을 추론한다.
        if (before.isEmpty() || before.any { old -> after.none { it.key == old.key } }) return emptyList()
        var accounted = 0
        val finite = before.mapNotNull { old ->
            val capacity = (successful - accounted).coerceAtLeast(0)
            if (capacity == 0) return@mapNotNull null
            val previous = old.remaining ?: return@mapNotNull null
            val current = after.singleOrNull { it.key == old.key }?.remaining ?: return@mapNotNull null
            (previous - current).coerceIn(0, capacity).takeIf { it > 0 }?.let { quantity ->
                accounted += quantity
                OrbExchangeOutcome(old.name, quantity, success = true, inferred = true)
            }
        }
        val fundsBag = (successful - accounted).coerceAtLeast(0).takeIf { it > 0 }?.let {
            OrbExchangeOutcome(UNLIMITED_FUNDS_BAG, it, success = true, inferred = true)
        }
        return finite + listOfNotNull(fundsBag)
    }

    private fun resultSectionNodes(html: String, finalUrl: String): List<Node> {
        val document = Jsoup.parse(html, finalUrl)
        val anchorHeading = document.selectFirst("a[name=OrbboxShopinfo2]")?.closest("h1,h2,h3,h4,legend")
        val heading = anchorHeading ?: document.select("h1,h2,h3,h4,legend").firstOrNull {
            clean(it.text()).matches(Regex("^(Result|결과|처리 결과)$", RegexOption.IGNORE_CASE))
        } ?: return emptyList()
        val nodes = mutableListOf<Node>()
        var node = heading.nextSibling()
        while (node != null) {
            if (node is Element && node.tagName() in HEADING_TAGS) break
            nodes += node
            node = node.nextSibling()
        }
        return nodes
    }

    private fun parseExplicitOutcomes(nodes: List<Node>): List<OrbExchangeOutcome> {
        val outcomes = mutableListOf<OrbExchangeOutcome>()
        var buffer = StringBuilder()
        fun flush() {
            val text = clean(buffer.toString())
            val events = (ACQUIRED.findAll(text).map { it.range.first to true } + ORB_SHORTAGE.findAll(text).map { it.range.first to false }).sortedBy { it.first }.toList()
            events.forEachIndexed { index, (start, success) ->
                if (success) {
                    val prefixStart = if (index == 0) 0 else events[index - 1].first
                    val prefix = text.substring(prefixStart, start).substringAfterLast("획득했다! ").substringAfterLast("부족합니다. ").trim()
                    prefix.replace(ACQUIRED_SUFFIX, "").replace(TRAILING_BIND, "").trim().takeIf(String::isNotBlank)?.let {
                        outcomes += OrbExchangeOutcome(it, 1, success = true, inferred = false)
                    }
                } else {
                    outcomes += OrbExchangeOutcome(ORB_SHORTAGE.find(text, start)?.value ?: "필요한 오브의 개수가 부족합니다.", 1, success = false, inferred = false)
                }
            }
            buffer = StringBuilder()
        }
        fun visit(node: Node) {
            when (node) {
                is TextNode -> buffer.append(' ').append(node.text())
                is Element -> when (node.tagName()) {
                    "img" -> flush()
                    "br", "hr" -> flush()
                    else -> {
                        node.childNodes().forEach(::visit)
                        if (node.tagName() in RESULT_BLOCK_TAGS) flush()
                    }
                }
            }
        }
        nodes.forEach(::visit)
        flush()
        return outcomes.take(100)
    }

    private fun parseFailures(nodes: List<Node>, fallback: ParsedTownResult): List<String> {
        val sectionText = clean(nodes.joinToString(" ") { it.toString() })
        val matches = ORB_SHORTAGE.findAll(sectionText).map { clean(Jsoup.parse(it.value).text()) }.filter(String::isNotBlank).toList()
        if (matches.isNotEmpty()) return matches.take(5)
        return fallback.messages.flatMap { message -> ORB_SHORTAGE.findAll(message).map { clean(it.value) }.toList() }.take(5)
    }

    private fun normalizeRewardName(value: String) = clean(value).lowercase().replace(Regex("\\s+"), " ")
    private fun rewardName(text: String): String {
        val match = REMAINING_REWARD.find(text) ?: return ""
        val before = text.substring(0, match.range.first).replace(ORDINAL, "").trim(' ', ':', '-')
        return before.substringAfter("상품", before).trim(' ', ':', '-')
    }
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun String.number(): Int? = replace(",", "").toIntOrNull()
    private fun clean(value: String) = value.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    private companion object {
        val REMAINING_TOTAL = Regex("뽑을 수 있는 상품이\\s*([\\d,]+)\\s*개 남아")
        val MONTH = Regex("(\\d{4})\\s*년\\s*(\\d{1,2})\\s*월의 보상 리스트")
        val REMAINING_REWARD = Regex("현재 남은 수량\\s*[:：]\\s*(무제한|[\\d,]+)\\s*개?")
        val ORDINAL = Regex("^\\d+번째\\s*상품")
        val ACQUIRED = Regex("을\\(를\\)\\s*획득했다[^!。.]*(?:[!。.]|$)")
        val ACQUIRED_SUFFIX = Regex("\\s*을\\(를\\)\\s*$")
        val TRAILING_BIND = Regex("\\s*/\\s*Bind\\s*/?\\s*$", RegexOption.IGNORE_CASE)
        val ORB_SHORTAGE = Regex("필요한 오브의 개수가 부족합니다[.!]?")
        val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "legend")
        val RESULT_BLOCK_TAGS = setOf("div", "p", "li", "tr")
        const val UNLIMITED_FUNDS_BAG = "Funds Bag($ 1,000)"
    }
}
