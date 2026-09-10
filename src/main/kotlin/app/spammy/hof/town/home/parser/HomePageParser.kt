package app.spammy.hof.town.home.parser

import app.spammy.hof.external.parser.HofHtmlParser

import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.town.home.model.*
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
class HomePageParser {
    fun parse(mode: HomeMode, html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): HomeSnapshot {
        val document = HofHtmlParser.parse(html, finalUrl)
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
                val listItems = sibling.select("li")
                if (listItems.isNotEmpty()) return@flatMap listItems.asSequence()

                val cells = sibling.select("tr").flatMap { row ->
                    row.children().filter { it.tagName() == "td" }
                }
                (if (cells.isEmpty()) listOf(sibling) else cells).asSequence()
            }
            .map { clean(it.text()) }
            .filter { it.isNotBlank() && it.length <= MAX_FACILITY_TEXT }
            .distinct()
            .take(MAX_FACILITIES)
            .toList()
    }

    /** HOF 자택 작업은 한 작업을 rowspan으로 묶은 3개 tr에 나눠 표시한다. */
    private fun parseQuests(document: org.jsoup.nodes.Document, finalUrl: String): List<HomeQuest> = buildList {
        document.select("table").take(MAX_QUEST_TABLES).forEach { table ->
            if (size >= MAX_QUESTS) return@forEach
            val heading = previousHeading(table)
            val rows = table.select("tr").filter { it.closest("table") === table }
            var rowIndex = 0
            while (rowIndex < rows.size && size < MAX_QUESTS) {
                val row = rows[rowIndex]
                val cells = row.children().filter { it.tagName() == "td" }
                val name = clean(cells.firstOrNull()?.text().orEmpty()).take(MAX_QUEST_NAME)
                if (cells.size < 4 || name.isBlank() || name == "작업명") {
                    rowIndex++
                    continue
                }

                val rowSpan = cells.first().attr("rowspan").toIntOrNull()?.coerceIn(1, MAX_QUEST_ROW_SPAN) ?: 1
                val groupedRows = rows.subList(rowIndex, minOf(rows.size, rowIndex + rowSpan))
                val groupedText = clean(groupedRows.joinToString(" ") { it.text() })
                val links = groupedRows.flatMap { it.select("a[href*='action=']") }
                val link = links.singleOrNull()
                val observed = link?.let { parseQuestLink(it, finalUrl) }
                if (observed == null && !questLike(groupedText) && !HOME_SECTION.containsMatchIn(heading)) {
                    rowIndex += rowSpan
                    continue
                }

                // 보상 열은 reward로 별도 전달하므로 타입/제한만 일반 상세에 포함한다.
                val mainDetails = cells.drop(1).take(2).map { clean(it.text()) }
                val continuationDetails = groupedRows.drop(1).flatMap { continuation ->
                    continuation.children().filter { it.tagName() == "td" }.map { clean(it.text()) }
                }
                val details = (mainDetails + continuationDetails).asSequence()
                    .map { it.take(MAX_QUEST_DETAIL) }
                    .filter { it.isNotBlank() && it != "-" && !ACTION_ONLY.matches(it) }
                    .distinct()
                    .take(MAX_QUEST_DETAIL_CELLS)
                    .toList()
                val state = when {
                    observed?.action == "complete" || Regex("완료|수령|보상").containsMatchIn(link?.text().orEmpty()) -> HomeQuestState.CLAIMABLE
                    Regex("대기|조건\\s*미달").containsMatchIn(heading) -> HomeQuestState.WAITING
                    observed?.action == "get" -> HomeQuestState.AVAILABLE
                    Regex("완료한|완료됨").containsMatchIn(heading) -> HomeQuestState.COMPLETED
                    else -> HomeQuestState.ACTIVE
                }
                val actionId = observed?.let { opaque("${it.action}\u0000${it.no}") }
                // 표시용 ACTIVE 기본값과 실제로 확인한 상태를 구분한다.
                val completeInactiveAction = cells.size >= 5 && links.isEmpty() && clean(cells.last().text()) == "-"
                val stateObserved = groupedRows.size == rowSpan && when (state) {
                    HomeQuestState.AVAILABLE -> observed?.action == "get"
                    HomeQuestState.CLAIMABLE -> observed?.action == "complete"
                    HomeQuestState.ACTIVE -> Regex("진행\\s*중").containsMatchIn(heading) && completeInactiveAction
                    HomeQuestState.WAITING, HomeQuestState.COMPLETED -> observed != null || completeInactiveAction
                }
                add(
                    HomeQuest(
                        // 상태가 바뀌면 작업이 다른 섹션으로 이동한다. 작업명(HQ 코드 포함)을
                        // 식별자로 사용해 수락 전후에도 저장된 자동화 설정을 유지한다.
                        id = opaque(name),
                        name = name,
                        state = state,
                        mission = details.firstOrNull { MISSION.containsMatchIn(it) },
                        reward = clean(cells.getOrNull(3)?.text().orEmpty()).take(MAX_QUEST_DETAIL).takeIf { it.isNotBlank() && it != "-" },
                        details = details,
                        actionId = actionId,
                        action = observed?.action,
                        actionNo = observed?.no,
                        stateObserved = stateObserved,
                    ),
                )
                rowIndex += rowSpan
            }
        }
    }

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
        if (uri.scheme != "https" || !uri.host.equals("hof.zerosic.com", true) || uri.port !in setOf(-1, 443) ||
            uri.userInfo != null || uri.fragment != null || uri.path != "/index.php"
        ) return null
        val query = decodeQuery(uri.rawQuery) ?: return null
        val pageQuery = decodeQuery(base.rawQuery) ?: return null
        val action = query["action"]?.singleOrNull()?.takeIf { it in setOf("get", "complete") } ?: return null
        val no = query["no"]?.singleOrNull()?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return null
        val currentMenu = pageQuery["menu"]?.singleOrNull() ?: return null
        if (query.keys != setOf("menu", "action", "no") || query["menu"]?.singleOrNull() != currentMenu) return null
        return ObservedQuestLink(action, no)
    }

    private fun previousHeading(element: Element): String {
        var current: Element? = (if (element.tagName() == "table") element else element.closest("table"))?.previousElementSibling()
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
        if (uri.scheme != "https" || !uri.host.equals("hof.zerosic.com", true) || uri.port !in setOf(-1, 443) ||
            uri.userInfo != null || uri.fragment != null || uri.path != "/index.php"
        ) return@runCatching null
        val query = decodeQuery(uri.rawQuery) ?: return@runCatching null
        if (query.keys != setOf("menu") || query["menu"]?.singleOrNull().isNullOrBlank()) return@runCatching null
        uri.toASCIIString()
    }.getOrNull()
    private fun opaque(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private data class ObservedQuestLink(val action: String, val no: String)
    private companion object {
        const val MAX_QUESTS = 500
        const val MAX_QUEST_TABLES = 100
        const val MAX_QUEST_ROW_SPAN = 10
        const val MAX_QUEST_NAME = 300
        const val MAX_QUEST_DETAIL = 1_000
        const val MAX_QUEST_DETAIL_CELLS = 20
        const val MAX_REST_ACTIONS = 8
        const val MAX_FORMS_SCANNED = 100
        const val MAX_HIDDEN_FIELDS = 32
        const val MAX_PAGE_TEXT = 200_000
        const val MAX_FACILITIES = 500
        const val MAX_FACILITY_SIBLINGS = 20
        const val MAX_FACILITY_TEXT = 300
        val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        val TIME_STATUS = Regex("\\bTime\\s*[:：]?\\s*([0-9][0-9,]*)\\s*/\\s*([0-9][0-9,]*)", RegexOption.IGNORE_CASE)
        val BASE_RECOVERY = Regex("기본(?:적으로)?\\s*([0-9][0-9,]*)\\s*(?:의\\s*)?Time(?:이|을)?\\s*회복", RegexOption.IGNORE_CASE)
        val FACILITY_RECOVERY = Regex("(?:시설|추가)[^0-9]{0,80}([0-9][0-9,]*)\\s*(?:의\\s*)?Time(?:이|을)?\\s*(?:추가로\\s*)?회복", RegexOption.IGNORE_CASE)
        val USED_TODAY = Regex("오늘[^.。\\n]{0,80}(?:이미\\s*)?(?:휴식|회복)[^.。\\n]{0,40}(?:했습니다|사용했습니다|할 수 없습니다)")
        val RESTORE_WORD = Regex("회복|복구|휴식|Rest|보충", RegexOption.IGNORE_CASE)
        val HOME_SECTION = Regex("(?:진행중인|수락 가능한|대기중인|완료한) 작업 목록")
        val MISSION = Regex("^미션\\s*[:：]")
        val ACTION_ONLY = Regex("^(?:수락|완료|수령|-)$")
        val FOOTER_TEXT = Regex("^(?:[•·\\-]\\s*)?(?:UpDate|Update|Copy\\s*Right)\\b", RegexOption.IGNORE_CASE)
    }
}
