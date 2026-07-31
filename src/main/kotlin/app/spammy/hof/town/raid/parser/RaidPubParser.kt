package app.spammy.hof.town.raid.parser

import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.raid.model.*
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
        val doc = Jsoup.parse(html, finalUrl)
        val forms = doc.select("form").filter { form ->
            val resolved = resolve(finalUrl, form.attr("action"))
            resolved.substringAfter('?', "").split('&').any { it.equals("menu=raidpub", true) }
        }
        if (forms.size != 1) return empty(result)
        val form = forms.single()
        val submitControlCounts = form.children()
            .filter { it.tagName() in setOf("input", "button") && isSubmit(it) }
            .map { control ->
                control.attr("name").trim() to control.attr("value").ifBlank { control.text() }.trim()
            }
            .groupingBy { it }
            .eachCount()
        val actionIds = page.forms.mapNotNull { parsed ->
            val submit = parsed.submitFields.singleOrNull() ?: return@mapNotNull null
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
            current?.text?.append(' ')?.append(nodeText(node))
        }
        flush()

        val pageText = clean(doc.text())
        val playerName = PLAYER_NAME.find(pageText)?.groupValues
            ?.drop(1)
            ?.firstOrNull(String::isNotBlank)
            ?.trim()
            .orEmpty()
        val raids = sections.take(MAX_RAIDS).mapNotNull { section ->
            val code = section.code.takeIf { RAID_CODE.matches(it) } ?: return@mapNotNull null
            val text = clean(section.text.toString())
            val statusText = STATUS.find(text)?.groupValues?.get(1)?.trim()?.take(MAX_TEXT)
            val wait = statusText?.let { DEPART.find(it)?.groupValues?.get(1)?.boundedInt(MAX_WAIT_SECONDS) }
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
            )
        }
        val applyWait = APPLY_WAIT.find(pageText)?.let { match ->
            val seconds = match.groupValues[1].toLongOrNull().orZero() * 3600L +
                match.groupValues[2].toLongOrNull().orZero() * 60L + match.groupValues[3].toLongOrNull().orZero()
            seconds.takeIf { it in 0..MAX_WAIT_SECONDS.toLong() }?.toInt()
        }
        return RaidPubSnapshot(
            raids = raids,
            applied = APPLIED.containsMatchIn(pageText),
            applyWaitSeconds = applyWait,
            myStatus = MY_STATUS.find(pageText)?.value?.take(MAX_TEXT),
            globalActions = global.keys + RaidAction.REFRESH,
            result = result,
            globalActionIds = global,
            observedRaidPubForm = true,
        )
    }

    private fun empty(result: ParsedTownResult?) = RaidPubSnapshot(emptyList(), false, null, null, setOf(RaidAction.REFRESH), result, emptyMap(), false)
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
        COMPLETED.containsMatchIn(text) -> RaidStatus.COMPLETED
        IN_BATTLE.containsMatchIn(text) -> RaidStatus.IN_BATTLE
        READY.containsMatchIn(text) -> RaidStatus.READY
        DEPART.containsMatchIn(text) -> RaidStatus.WAITING
        RECRUITING.containsMatchIn(text) -> RaidStatus.RECRUITING
        CLOSED.containsMatchIn(text) -> RaidStatus.CLOSED
        else -> RaidStatus.UNKNOWN
    }
    private fun resolve(pageUrl: String, action: String): String = when {
        action.isBlank() -> pageUrl
        action.startsWith("?") -> pageUrl.substringBefore('#').substringBefore('?') + action
        else -> URI(pageUrl).resolve(action).toString()
    }
    private fun clean(value: String) = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
    private fun String.boundedInt(max: Int) = replace(",", "").toLongOrNull()?.takeIf { it in 0..max.toLong() }?.toInt()
    private fun Long?.orZero() = this ?: 0L

    private data class MutableRaid(val code: String, val heading: String, val text: StringBuilder = StringBuilder(), val actionIds: LinkedHashMap<RaidAction, String> = linkedMapOf())

    private companion object {
        val RAID_ACTIONS = setOf(RaidAction.REGISTER, RaidAction.LEAVE, RaidAction.START, RaidAction.RESET)
        val GLOBAL_ACTIONS = setOf(RaidAction.REWARD, RaidAction.WAIT_RESET, RaidAction.REFRESH)
        val REGISTER = Regex("^(?:등록(?:한다)?|신청(?:한다)?|register)$", RegexOption.IGNORE_CASE)
        val LEAVE = Regex("^(?:파티에서\\s*)?(?:나온다|나오기|탈퇴(?:한다)?|leave)$", RegexOption.IGNORE_CASE)
        val START = Regex("^(?:전투를?\\s*)?시작(?:한다)?$|^start$", RegexOption.IGNORE_CASE)
        val RESET = Regex("^(?:파티를?\\s*)?리셋(?:한다)?$|^reset$", RegexOption.IGNORE_CASE)
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
        val APPLICANT = Regex("-\\s*\\[([^]]+)]")
        val APPLY_WAIT = Regex("신청\\s*가능\\s*까지\\s*(?:(\\d+)\\s*시간)?\\s*(?:(\\d+)\\s*분)?\\s*(?:(\\d+)\\s*초)?")
        val MY_STATUS = Regex("현재\\s*(?:상태는|전투)[^)]*\\)")
        val APPLIED = Regex("신청한\\s*상태|신청\\s*완료")
        val UNPLAYABLE = Regex("플레이\\s*불가|시험\\s*중")
        val COMPLETED = Regex("토벌\\s*완료|완료")
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
    }
}
