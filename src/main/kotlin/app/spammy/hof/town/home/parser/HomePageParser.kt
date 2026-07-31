package app.spammy.hof.town.home.parser

import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
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
            HomeMode.HOME -> HomeSnapshot(mode, parseQuests(document, finalUrl), emptyList(), result)
            HomeMode.REST -> HomeSnapshot(mode, emptyList(), parseRestActions(document, page), result)
        }
    }

    private fun parseQuests(document: org.jsoup.nodes.Document, finalUrl: String): List<HomeQuest> = document.select("tr")
        .filter { row -> row.select("td").size >= 2 && (row.select("a[href*='action=']").isNotEmpty() || questLike(row.text())) }
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
            val name = clean(cells.firstOrNull()?.text().orEmpty()).ifBlank { return@mapIndexedNotNull null }
            val details = cells.drop(1).map { clean(it.text()) }.filter(String::isNotBlank)
            val actionId = observed?.let { opaque("${it.action}\u0000${it.no}") }
            HomeQuest(
                id = opaque("$index\u0000$name\u0000${details.joinToString("\u0000")}"),
                name = name,
                state = state,
                mission = details.firstOrNull { !Regex("보상|수락|완료|수령").containsMatchIn(it) },
                reward = details.firstOrNull { Regex("보상|아이템|Funds|Time", RegexOption.IGNORE_CASE).containsMatchIn(it) },
                actionId = actionId,
                action = observed?.action,
                actionNo = observed?.no,
            )
        }

    private fun parseRestActions(document: org.jsoup.nodes.Document, page: ParsedTownPage): List<HomeAction> {
        val formsById = page.forms.associateBy { it.actionId }
        return document.select("form").mapNotNull { domForm ->
            val label = domForm.select("input[type=submit], button[type=submit], button:not([type])")
                .filter { it.closest("form") === domForm && !it.hasAttr("disabled") }
                .map { clean(it.attr("value").ifBlank { it.text() }) }.singleOrNull()
                ?: return@mapNotNull null
            if (!RESTORE_WORD.containsMatchIn(label)) return@mapNotNull null
            val parsed = app.spammy.hof.town.common.parser.HofFormParser().parse(domForm.outerHtml(), document.baseUri()).forms.singleOrNull()
                ?: return@mapNotNull null
            val current = formsById[parsed.actionId] ?: return@mapNotNull null
            HomeAction(current.actionId, HomeActionType.RESTORE, label, formActionId = current.actionId)
        }.distinctBy(HomeAction::id)
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
        val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank)
            .map { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
            .groupBy({ it.first }, { it.second })
        val pageQuery = base.rawQuery.orEmpty().split('&').filter(String::isNotBlank)
            .map { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
            .groupBy({ it.first }, { it.second })
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
    private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8)
    private fun opaque(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private data class ObservedQuestLink(val action: String, val no: String)
    private companion object { val RESTORE_WORD = Regex("회복|복구|휴식|Rest|보충", RegexOption.IGNORE_CASE) }
}
