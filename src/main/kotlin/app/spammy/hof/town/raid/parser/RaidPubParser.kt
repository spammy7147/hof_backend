package app.spammy.hof.town.raid.parser

import app.spammy.hof.external.parser.HofHtmlParser

import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.raid.model.*
import app.spammy.hof.external.model.HofHttpMethod
import java.math.BigInteger
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.springframework.stereotype.Component

/** APK parseRaidPub와 같은 h4/direct-child 경계를 사용하되 action은 strict allowlist로만 노출한다. */
@Component
class RaidPubParser {
    fun parse(
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        result: ParsedTownResult? = null,
        availableRaidCodes: Set<String> = emptySet(),
    ): RaidPubSnapshot {
        val pageTerminatorComplete = hasCompletePageTerminator(Jsoup.parse(html, finalUrl))
        val doc = HofHtmlParser.parse(html, finalUrl)
        val normalizedResult = result?.let { parsed ->
            if (RESET_SUCCEEDED.containsMatchIn(clean(doc.text())) && RESET_SUCCESS_MESSAGE !in parsed.messages) {
                parsed.copy(messages = (parsed.messages + RESET_SUCCESS_MESSAGE).distinct())
            } else {
                parsed
            }
        }
        if (!safeRaidPubPageUrl(finalUrl)) return empty(normalizedResult)
        val contents = doc.selectFirst("#contents") ?: return empty(normalizedResult)
        val forms = contents.select("form").filter { form ->
            form.attr("method").equals("post", true) &&
                safeRaidPubActionUrl(resolve(finalUrl, form.attr("action")))
        }
        if (forms.size != 1) return empty(normalizedResult)
        val form = forms.single()
        if (
            form.select("a[href*=raidlog]").isEmpty() ||
            !pageTerminatorComplete
        ) return empty(normalizedResult)
        val submitControlCounts = form.children()
            .filter { it.tagName() in setOf("input", "button") && isSubmit(it) }
            .map { control ->
                control.attr("name").trim() to control.attr("value").ifBlank { control.text() }.trim()
            }
            .groupingBy { it }
            .eachCount()
        val actionIds = page.forms.mapNotNull { parsed ->
            val submit = parsed.submitFields.singleOrNull() ?: return@mapNotNull null
            if (parsed.method != HofHttpMethod.POST || !safeRaidPubActionUrl(parsed.actionUrl) ||
                parsed.hiddenFields.groupingBy { it.name }.eachCount().any { it.value > 1 }
            ) return@mapNotNull null
            if (submitControlCounts[submit.name to submit.value] != 1) return@mapNotNull null
            (submit.name to submit.value) to parsed.actionId
        }.groupBy({ it.first }, { it.second }).mapValues { (_, ids) -> ids.distinct().singleOrNull() }

        val global = linkedMapOf<RaidAction, String>()
        val sections = mutableListOf<MutableRaid>()
        var current: MutableRaid? = null
        fun flush() { current?.let(sections::add); current = null }
        form.childNodes().forEach { node ->
            val element = node as? Element
            if (element?.tagName() == "h4") {
                val anchor = element.selectFirst("a[name^=Raid]")
                flush()
                if (anchor != null) current = MutableRaid(
                    code = anchor.attr("name").trim(),
                    heading = clean(element.text()),
                )
                return@forEach
            }
            if (element?.selectFirst("a[href*=raidlog]") != null) { flush(); return@forEach }
            if (element != null && element.tagName() in setOf("input", "button") && isSubmit(element)) {
                val name = element.attr("name").trim()
                val value = element.attr("value").ifBlank { element.text() }.trim()
                val action = classify(value) ?: return@forEach
                val actionId = actionIds[name to value] ?: return@forEach
                if (current == null) {
                    if (action in GLOBAL_ACTIONS) global.putIfAbsent(action, actionId)
                } else if (action in RAID_ACTIONS) {
                    current?.actionIds?.putIfAbsent(action, actionId)
                }
            }
            current?.appendText(nodeText(node))
        }
        flush()

        val declaredCodes = form.select("h4 a[name^=Raid]")
            .filter { anchor -> anchor.closest("form") === form }
            .map { anchor -> anchor.attr("name").trim() }
        if (
            declaredCodes.size > MAX_RAIDS ||
            declaredCodes.any { code -> !RAID_CODE.matches(code) } ||
            declaredCodes.distinct().size != declaredCodes.size ||
            sections.map(MutableRaid::code) != declaredCodes
        ) return empty(normalizedResult)

        val pageText = clean(doc.text()).take(MAX_PAGE_TEXT)
        val playerName = PLAYER_NAME.find(pageText)?.groupValues
            ?.drop(1)
            ?.firstOrNull(String::isNotBlank)
            ?.trim()
            .orEmpty()
        val boundedSections = sections.take(MAX_RAIDS)
        val codeCounts = boundedSections.map(MutableRaid::code).filter(RAID_CODE::matches).groupingBy { it }.eachCount()
        val raids = boundedSections.mapNotNull { section ->
            val code = section.code.takeIf { RAID_CODE.matches(it) } ?: return@mapNotNull null
            if (codeCounts[code] != 1) return@mapNotNull null
            val text = clean(section.text.toString())
            val statusText = STATUS.find(text)?.groupValues?.get(1)?.trim()?.take(MAX_TEXT)
            val wait = statusText?.let { status ->
                DEPART.find(status)?.groupValues?.get(1)?.boundedInt(MAX_WAIT_SECONDS)
                    ?: REWARD_WAIT.find(status)?.let(::boundedDurationSeconds)
            }
            val rewardMatch = statusText?.let(REWARD_WAIT::find)
            val rewardWait = rewardMatch?.let(::boundedDurationSeconds)
            val applicantsText = text.substringAfter("신청자", "")
            val applicants = APPLICANT.findAll(applicantsText).map { clean(it.groupValues[1]).take(MAX_TEXT) }
                .filter(String::isNotBlank).distinct().take(MAX_APPLICANTS).toList()
            val playable = !UNPLAYABLE.containsMatchIn(section.heading)
            val joined = playerName.isNotBlank() && applicants.any { applicant ->
                val plain = applicant.substringAfterLast('》').trim()
                plain == playerName || applicant.contains(playerName)
            }
            val status = classifyStatus(statusText, playable)
            val available = code in availableRaidCodes
            RaidPubRaid(
                id = code,
                name = (HEADING_NAME.find(section.heading)?.groupValues?.get(1) ?: section.heading)
                    .replace(Regex("\\[[^]]*]"), "").trim().take(MAX_TEXT),
                playable = playable,
                difficulty = DIFFICULTY.find(text)?.groupValues?.get(1)?.trim()?.take(MAX_TEXT),
                maxPartySize = PARTY_SIZE.find(text)?.groupValues?.get(1)?.boundedInt(MAX_PARTY_SIZE),
                rewardDamage = REWARD_DAMAGE.find(text)?.groupValues?.get(1)?.replace(Regex("\\s+"), "")?.take(MAX_TEXT),
                status = status,
                statusText = statusText,
                waitSeconds = wait,
                applicants = applicants,
                joined = joined,
                actions = section.actionIds.keys.toSet(),
                battleTarget = if (playable && joined && available) RaidBattleTarget(mapCode = code) else null,
                actionIds = section.actionIds.toMap(),
                rewardWindowStatus = when {
                    status != RaidStatus.COMPLETED -> RaidRewardWindowStatus.ABSENT
                    rewardMatch != null && rewardWait == null -> RaidRewardWindowStatus.INCOMPLETE
                    rewardWait?.let { it > 0 } == true -> RaidRewardWindowStatus.WAIT
                    rewardWait == 0 && RaidAction.REWARD in global -> RaidRewardWindowStatus.AVAILABLE
                    REWARD_CONFIRMATION.containsMatchIn(statusText.orEmpty()) && RaidAction.REWARD in global ->
                        RaidRewardWindowStatus.AVAILABLE
                    REWARD_CONFIRMATION.containsMatchIn(statusText.orEmpty()) -> RaidRewardWindowStatus.INCOMPLETE
                    else -> RaidRewardWindowStatus.ABSENT
                },
                rewardWaitSeconds = rewardWait,
            )
        }
        if (raids.size != sections.size) return empty(normalizedResult)
        val applyWaiting = APPLY_WAIT_STATE.containsMatchIn(pageText)
        val applyWait = APPLY_WAIT.find(pageText)?.let(::boundedDurationSeconds)
        return RaidPubSnapshot(
            raids = raids,
            applied = APPLIED.containsMatchIn(pageText),
            applyWait = applyWaiting,
            applyWaitSeconds = applyWait,
            myStatus = MY_STATUS.find(pageText)?.value?.take(MAX_TEXT),
            globalActions = global.keys,
            result = normalizedResult,
            globalActionIds = global,
            pageComplete = true,
        )
    }

    private fun empty(result: ParsedTownResult?) = RaidPubSnapshot(emptyList(), false, false, null, null, emptySet(), result, emptyMap(), false)
    private fun hasCompletePageTerminator(document: org.jsoup.nodes.Document): Boolean =
        document.select("h5").any { clean(it.text()).contains("copy right", ignoreCase = true) } &&
            document.select("h6").any { clean(it.text()).contains("h.o.f korean ver", ignoreCase = true) } &&
            document.select("img[src]").any { image ->
                image.attr("src").substringBefore('?').substringAfterLast('/').equals("zerohof.gif", true)
            }
    private fun nodeText(node: Node): String = when (node) { is TextNode -> node.text(); is Element -> node.text(); else -> "" }
    private fun isSubmit(element: Element): Boolean = when (element.tagName()) {
        "button" -> element.attr("type").lowercase().let { it.isBlank() || it == "submit" }
        "input" -> element.attr("type").lowercase() in setOf("submit", "image")
        else -> false
    }
    private fun classify(value: String): RaidAction? = when {
        REGISTER.matches(value) -> RaidAction.REGISTER
        LEAVE.matches(value) -> RaidAction.LEAVE
        START.matches(value) -> RaidAction.START
        RESET.matches(value) -> RaidAction.RESET
        REWARD.matches(value) -> RaidAction.REWARD
        WAIT_RESET.matches(value) -> RaidAction.WAIT_RESET
        REFRESH.matches(value) -> RaidAction.REFRESH
        else -> null
    }
    private fun classifyStatus(text: String?, playable: Boolean): RaidStatus = when {
        !playable -> RaidStatus.TESTING
        text == null -> RaidStatus.UNKNOWN
        isRaidResetRequiredStatus(text) -> RaidStatus.COMPLETED
        REWARD_CONFIRMATION.containsMatchIn(text) -> RaidStatus.COMPLETED
        COMPLETED.containsMatchIn(text) -> RaidStatus.COMPLETED
        IN_BATTLE.containsMatchIn(text) -> RaidStatus.IN_BATTLE
        DEPART.containsMatchIn(text) -> RaidStatus.WAITING
        READY.containsMatchIn(text) -> RaidStatus.READY
        RECRUITING.containsMatchIn(text) -> RaidStatus.RECRUITING
        CLOSED.containsMatchIn(text) -> RaidStatus.CLOSED
        else -> RaidStatus.UNKNOWN
    }
    private fun resolve(pageUrl: String, action: String): String = when {
        action.isBlank() -> pageUrl
        action.startsWith("?") -> pageUrl.substringBefore('#').substringBefore('?') + action
        else -> URI(pageUrl).resolve(action).toString()
    }
    private fun safeRaidPubPageUrl(value: String): Boolean = safeHofIndexUrl(value) { query ->
        query.split('&').any { it.equals("menu=raidpub", true) }
    }
    private fun safeRaidPubActionUrl(value: String): Boolean = safeHofIndexUrl(value) { query ->
        query.isBlank() || query.split('&').any { it.equals("menu=raidpub", true) }
    }
    private fun safeHofIndexUrl(value: String, acceptsQuery: (String) -> Boolean): Boolean = runCatching {
        val uri = URI(value).normalize()
        uri.scheme == "http" && uri.host.equals("sic.zerosic.com", true) && uri.port in setOf(-1, 80) &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.path == "/ZeroHOF/index.php" &&
            acceptsQuery(uri.rawQuery.orEmpty())
    }.getOrDefault(false)
    private fun clean(value: String) = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
    private fun String.boundedInt(max: Int) = replace(",", "").toLongOrNull()?.takeIf { it in 0..max.toLong() }?.toInt()
    private fun boundedDurationSeconds(match: MatchResult): Int? {
        val values = match.groupValues.drop(1).map { value ->
            if (value.isBlank()) BigInteger.ZERO else value.toBigIntegerOrNull() ?: return null
        }
        if (match.groupValues.drop(1).all(String::isBlank)) return null
        val total = values[0] * BigInteger.valueOf(3600) + values[1] * BigInteger.valueOf(60) + values[2]
        return total.takeIf { it >= BigInteger.ZERO && it <= BigInteger.valueOf(MAX_WAIT_SECONDS.toLong()) }?.toInt()
    }

    private data class MutableRaid(val code: String, val heading: String, val text: StringBuilder = StringBuilder(), val actionIds: LinkedHashMap<RaidAction, String> = linkedMapOf()) {
        fun appendText(value: String) {
            if (value.isBlank() || text.length >= MAX_SECTION_TEXT) return
            text.append(' ').append(value.take(MAX_SECTION_TEXT - text.length))
        }
    }

    private companion object {
        const val RESET_SUCCESS_MESSAGE = "전투가 신청 가능 상태로 바뀌었습니다."
        val RESET_SUCCEEDED = Regex("전투가\\s*신청\\s*가능\\s*상태로\\s*바뀌었습니다\\.?")
        val RAID_ACTIONS = setOf(RaidAction.REGISTER, RaidAction.LEAVE, RaidAction.START, RaidAction.RESET)
        val GLOBAL_ACTIONS = setOf(RaidAction.REWARD, RaidAction.WAIT_RESET, RaidAction.REFRESH)
        val REGISTER = Regex("^(?:(?:파티에\\s*)?등록(?:한다)?|신청(?:한다)?|register)$", RegexOption.IGNORE_CASE)
        val LEAVE = Regex("^(?:파티에서\\s*)?(?:나온다|나오기|탈퇴(?:한다)?|leave)$", RegexOption.IGNORE_CASE)
        val START = Regex("^(?:전투를?\\s*)?시작(?:한다)?$|^start$", RegexOption.IGNORE_CASE)
        val RESET = Regex("^(?:(?:파티|전투)를?\\s*)?리셋(?:한다)?$|^reset$", RegexOption.IGNORE_CASE)
        val REWARD = Regex("^(?:보상(?:을)?\\s*(?:확인|받기|받는다)|reward)$", RegexOption.IGNORE_CASE)
        val WAIT_RESET = Regex("^(?:신청\\s*)?대기(?:시간)?\\s*(?:초기화|리셋)|^wait\\s*reset$", RegexOption.IGNORE_CASE)
        val REFRESH = Regex("^(?:상태\\s*)?(?:갱신|새로고침)|^refresh$", RegexOption.IGNORE_CASE)
        val PLAYER_NAME = Regex("》\\s*([^\\s》]+)\\s+Funds\\b|([^\\s》]+)\\s+Funds\\s*:", RegexOption.IGNORE_CASE)
        val HEADING_NAME = Regex("집단\\s*전투\\s*\\d+\\s*-\\s*(.+)$")
        val DIFFICULTY = Regex("난이도\\s*:\\s*(.+?)\\s*도전\\s*인원수")
        val PARTY_SIZE = Regex("도전\\s*인원수\\s*:\\s*(\\d+)")
        val REWARD_DAMAGE = Regex("특별\\s*보상\\s*데미지\\s*:\\s*([0-9,]+\\s*\\+?)")
        val STATUS = Regex("현재\\s*상태\\s*:\\s*(.+?)(?=\\s*(?:◎|신청자|$))")
        val DEPART = Regex("(\\d+)\\s*초\\s*후\\s*출발")
        val REWARD_WAIT = Regex("남은\\s*시간\\s*앞으로\\s*(?:(\\d+)\\s*시간)?\\s*(?:(\\d+)\\s*분)?\\s*(?:(\\d+)\\s*초)?")
        val APPLICANT = Regex("-\\s*\\[([^]]+)]")
        val APPLY_WAIT = Regex("신청\\s*가능\\s*까지\\s*(?:(\\d+)\\s*시간)?\\s*(?:(\\d+)\\s*분)?\\s*(?:(\\d+)\\s*초)?")
        val APPLY_WAIT_STATE = Regex("신청\\s*대기|신청\\s*가능\\s*까지")
        val MY_STATUS = Regex("현재\\s*(?:상태는|전투)[^)]*\\)")
        val APPLIED = Regex("신청한\\s*상태|신청\\s*완료")
        val UNPLAYABLE = Regex("플레이\\s*불가|시험\\s*중")
        val COMPLETED = Regex("토벌\\s*완료|완료")
        val REWARD_CONFIRMATION = Regex("보상\\s*확인\\s*시간")
        val IN_BATTLE = Regex("전투\\s*중")
        val READY = Regex("출발\\s*가능")
        val RECRUITING = Regex("모집\\s*중")
        val CLOSED = Regex("신청\\s*불가|마감|종료")
        val RAID_CODE = Regex("Raid[A-Za-z0-9_.:-]{1,180}")
        const val MAX_RAIDS = 100
        const val MAX_APPLICANTS = 100
        const val MAX_PARTY_SIZE = 100
        const val MAX_WAIT_SECONDS = 604_800
        const val MAX_TEXT = 500
        const val MAX_SECTION_TEXT = 20_000
        const val MAX_PAGE_TEXT = 200_000
    }
}
