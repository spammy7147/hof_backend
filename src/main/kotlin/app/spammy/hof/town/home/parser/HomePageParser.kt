package app.spammy.hof.town.home.parser

import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.town.home.model.*
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
class HomePageParser {
    fun parse(mode: HomeMode, html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): HomeSnapshot {
        val document = Jsoup.parse(html, finalUrl)
        return when (mode) {
            HomeMode.HOME -> HomeSnapshot(mode, parseQuests(document, finalUrl), emptyList(), result = result)
            HomeMode.REST -> {
                val actions = parseRestActions(document, page)
                HomeSnapshot(mode, emptyList(), actions, parseRestStatus(document, actions), result)
            }
        }
    }

    private fun parseRestStatus(document: org.jsoup.nodes.Document, actions: List<HomeAction>): RestStatus {
        val text = clean(document.text()).take(MAX_PAGE_TEXT)
        val time = TIME_STATUS.find(text)?.destructured
        val baseRecovery = BASE_RECOVERY.find(text)?.groupValues?.get(1)?.number()
        val facilityRecovery = FACILITY_RECOVERY.find(text)?.groupValues?.get(1)?.number()
        val explicitlyUsed = USED_TODAY.containsMatchIn(text)
        val usedToday = when {
            explicitlyUsed -> true
            actions.isNotEmpty() -> false
            else -> null
        }
        return RestStatus(
            currentTime = time?.component1()?.number(),
            maxTime = time?.component2()?.number(),
            baseRecovery = baseRecovery,
            facilityRecovery = facilityRecovery,
            usedToday = usedToday,
            facilities = parseFacilities(document),
        )
    }

    private fun parseFacilities(document: org.jsoup.nodes.Document): List<String> {
        val explicit = document.select("[data-facility], .facility, .facilities li, #facility li, #facilities li")
            .take(MAX_FACILITIES)
            .map { clean(it.text()) }
            .filter { it.isNotBlank() && it.length <= MAX_FACILITY_TEXT && !FOOTER_TEXT.containsMatchIn(it) }
        if (explicit.isNotEmpty()) return explicit.distinct().take(MAX_FACILITIES)

        val heading = document.select("h1,h2,h3,h4,h5,h6,legend,summary,dt,th")
            .firstOrNull { clean(it.text()) == "보유 중인 시설" } ?: return emptyList()
        return generateSequence(heading.nextElementSibling()) { it.nextElementSibling() }
            .takeWhile { it.tagName() !in HEADING_TAGS && !FOOTER_TEXT.containsMatchIn(clean(it.text())) }
            .take(MAX_FACILITY_SIBLINGS)
            .flatMap { sibling ->
                val rows = sibling.select("li,tr")
                (if (rows.isEmpty()) listOf(sibling) else rows).asSequence()
            }
            .map { clean(it.text()) }
            .filter { it.isNotBlank() && it.length <= MAX_FACILITY_TEXT }
            .distinct()
            .take(MAX_FACILITIES)
            .toList()
    }

    private fun parseQuests(document: org.jsoup.nodes.Document, finalUrl: String): List<HomeQuest> = document.select("tr").asSequence()
        .filter { row -> row.select("td").size >= 2 && (row.select("a[href*='action=']").isNotEmpty() || questLike(row.text())) }
        .take(MAX_QUESTS)
        .mapIndexedNotNull { index, row ->
            val cells = row.children().filter { it.tagName() == "td" }
            val text = clean(row.text())
            val link = row.select("a[href*='action=']").singleOrNull()
            val observed = link?.let { parseQuestLink(it, finalUrl) }
            val heading = previousHeading(row)
            val state = when {
                observed?.action == "complete" || Regex("완료|수령|보상").containsMatchIn(link?.text().orEmpty()) -> HomeQuestState.CLAIMABLE
                observed?.action == "get" -> HomeQuestState.AVAILABLE
                Regex("완료한|완료됨").containsMatchIn(heading) -> HomeQuestState.COMPLETED
                Regex("대기|조건\\s*미달").containsMatchIn(heading + " " + text) -> HomeQuestState.WAITING
                else -> HomeQuestState.ACTIVE
            }
            val name = clean(cells.firstOrNull()?.text().orEmpty()).take(MAX_QUEST_NAME).ifBlank { return@mapIndexedNotNull null }
            val details = cells.drop(1).take(MAX_QUEST_DETAIL_CELLS)
                .map { clean(it.text()).take(MAX_QUEST_DETAIL) }.filter(String::isNotBlank)
            val actionId = observed?.let { opaque("${it.action}\u0000${it.no}") }
            HomeQuest(
                id = opaque("$index\u0000$name\u0000${details.joinToString("\u0000")}"),
                name = name,
                state = state,
                mission = details.firstOrNull { !Regex("보상|수락|완료|수령").containsMatchIn(it) },
                reward = details.firstOrNull { Regex("보상|아이템|Funds|Time", RegexOption.IGNORE_CASE).containsMatchIn(it) },
                details = details,
                actionId = actionId,
                action = observed?.action,
                actionNo = observed?.no,
            )
        }.toList()

    private fun parseRestActions(document: org.jsoup.nodes.Document, page: ParsedTownPage): List<HomeAction> {
        return document.select("form").asSequence().take(MAX_FORMS_SCANNED).mapNotNull { domForm ->
            val controls = domForm.select("input, button, select, textarea")
                .filter { it.closest("form") === domForm && !it.hasAttr("disabled") }
            val submit = controls.filter { control ->
                (control.tagName() == "input" && control.attr("type").lowercase() in setOf("submit", "image")) ||
                    (control.tagName() == "button" && control.attr("type").lowercase().let { it.isBlank() || it == "submit" })
            }.singleOrNull() ?: return@mapNotNull null
            val submitName = submit.attr("name").trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val submitValue = submit.attr("value").ifBlank { submit.text() }
            val label = clean(submitValue)
            if (!RESTORE_WORD.containsMatchIn(label)) return@mapNotNull null
            // 휴식은 항목 선택 action이 아니다. 라디오/체크박스/select/text 입력이 섞인 form은
            // 버튼 문구가 같아도 다른 HOF 기능일 수 있으므로 절대 실행 후보로 노출하지 않는다.
            if (controls.any { control ->
                    control.tagName() == "select" || control.tagName() == "textarea" ||
                        (control.tagName() == "input" && control.attr("type").lowercase() !in setOf("hidden", "submit", "image"))
                }
            ) return@mapNotNull null
            if (controls.count { it.tagName() == "input" && it.attr("type").equals("hidden", true) } > MAX_HIDDEN_FIELDS) {
                return@mapNotNull null
            }
            val currentUrl = safeUrl(document.baseUri()) ?: return@mapNotNull null
            val actionUrl = safeUrl(resolveAction(document.baseUri(), domForm.attr("action"))) ?: return@mapNotNull null
            if (actionUrl != currentUrl) return@mapNotNull null
            val current = page.forms.singleOrNull { parsed ->
                parsed.method == HofHttpMethod.POST && safeUrl(parsed.actionUrl) == currentUrl &&
                    parsed.candidates.isEmpty() && parsed.submitFields.singleOrNull()?.let {
                        it.name == submitName && it.value == submitValue
                    } == true
            } ?: return@mapNotNull null
            HomeAction(current.actionId, HomeActionType.RESTORE, label, formActionId = current.actionId)
        }.distinctBy(HomeAction::id).take(MAX_REST_ACTIONS).toList()
    }

    private fun parseQuestLink(link: Element, finalUrl: String): ObservedQuestLink? {
        val base = runCatching { URI(finalUrl) }.getOrNull() ?: return null
        val href = link.attr("href")
        val uri = runCatching {
            if (href.startsWith("?")) URI("${base.scheme}://${base.authority}${base.path}$href").normalize()
            else base.resolve(href).normalize()
        }.getOrNull() ?: return null
        if (uri.scheme != "http" || !uri.host.equals("sic.zerosic.com", true) || uri.port !in setOf(-1, 80) ||
            uri.userInfo != null || uri.fragment != null || uri.path != "/ZeroHOF/index.php"
        ) return null
        val query = decodeQuery(uri.rawQuery) ?: return null
        val pageQuery = decodeQuery(base.rawQuery) ?: return null
        val action = query["action"]?.singleOrNull()?.takeIf { it in setOf("get", "complete") } ?: return null
        val no = query["no"]?.singleOrNull()?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return null
        val currentMenu = pageQuery["menu"]?.singleOrNull() ?: return null
        if (query.keys != setOf("menu", "action", "no") || query["menu"]?.singleOrNull() != currentMenu) return null
        return ObservedQuestLink(action, no)
    }

    private fun previousHeading(row: Element): String {
        var current: Element? = row.closest("table")?.previousElementSibling()
        while (current != null) {
            if (current.tagName() in setOf("h3", "h4")) return clean(current.text())
            current = current.previousElementSibling()
        }
        return ""
    }

    private fun questLike(text: String) = Regex("수락|조건|달성|완료|보상|미션").containsMatchIn(text)
    private fun clean(value: String) = value.replace(Regex("\\s+"), " ").trim()
    private fun String.number(): Int? = replace(",", "").toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
    private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8)
    private fun decodeQuery(rawQuery: String?): Map<String, List<String>>? = runCatching {
        rawQuery.orEmpty().split('&').filter(String::isNotBlank)
            .map { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
            .groupBy({ it.first }, { it.second })
    }.getOrNull()
    private fun resolveAction(pageUrl: String, action: String): String = when {
        action.isBlank() -> pageUrl
        action.startsWith("?") -> "${pageUrl.substringBefore('#').substringBefore('?')}$action"
        else -> URI(pageUrl).resolve(action).toString()
    }
    private fun safeUrl(value: String): String? = runCatching {
        val uri = URI(value).normalize()
        if (uri.scheme != "http" || !uri.host.equals("sic.zerosic.com", true) || uri.port !in setOf(-1, 80) ||
            uri.userInfo != null || uri.fragment != null || uri.path != "/ZeroHOF/index.php"
        ) return@runCatching null
        val query = decodeQuery(uri.rawQuery) ?: return@runCatching null
        if (query.keys != setOf("menu") || query["menu"]?.singleOrNull().isNullOrBlank()) return@runCatching null
        uri.toASCIIString()
    }.getOrNull()
    private fun opaque(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private data class ObservedQuestLink(val action: String, val no: String)
    private companion object {
        const val MAX_QUESTS = 500
        const val MAX_QUEST_NAME = 300
        const val MAX_QUEST_DETAIL = 1_000
        const val MAX_QUEST_DETAIL_CELLS = 20
        const val MAX_REST_ACTIONS = 8
        const val MAX_FORMS_SCANNED = 100
        const val MAX_HIDDEN_FIELDS = 32
        const val MAX_PAGE_TEXT = 200_000
        const val MAX_FACILITIES = 100
        const val MAX_FACILITY_SIBLINGS = 20
        const val MAX_FACILITY_TEXT = 300
        val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        val TIME_STATUS = Regex("\\bTime\\s*[:：]?\\s*([0-9][0-9,]*)\\s*/\\s*([0-9][0-9,]*)", RegexOption.IGNORE_CASE)
        val BASE_RECOVERY = Regex("기본(?:적으로)?\\s*([0-9][0-9,]*)\\s*(?:의\\s*)?Time(?:이|을)?\\s*회복", RegexOption.IGNORE_CASE)
        val FACILITY_RECOVERY = Regex("(?:시설|추가)[^0-9]{0,80}([0-9][0-9,]*)\\s*(?:의\\s*)?Time(?:이|을)?\\s*(?:추가로\\s*)?회복", RegexOption.IGNORE_CASE)
        val USED_TODAY = Regex("오늘[^.。\\n]{0,80}(?:이미\\s*)?(?:휴식|회복)[^.。\\n]{0,40}(?:했습니다|사용했습니다|할 수 없습니다)")
        val RESTORE_WORD = Regex("회복|복구|휴식|Rest|보충", RegexOption.IGNORE_CASE)
        val FOOTER_TEXT = Regex("^(?:[•·\\-]\\s*)?(?:UpDate|Update|Copy\\s*Right)\\b", RegexOption.IGNORE_CASE)
    }
}
