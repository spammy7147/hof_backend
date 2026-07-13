package app.spammy.hof.quest.parser

import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
class QuestPageParser {
    fun parse(html: String): List<QuestSnapshot> {
        val document = Jsoup.parse(html)
        val snapshots = mutableListOf<QuestSnapshot>()

        document.select("#contents table tr, table tr").forEach { row ->
            parseElement(row, categoryOf(row.closest("table")))?.let(snapshots::add)
        }
        document.select("[data-quest-id]").forEach { block ->
            if (block.closest("tr") == null) {
                parseElement(block, categoryOf(block))?.let(snapshots::add)
            }
        }

        return snapshots
            .groupBy(QuestSnapshot::questId)
            .map { (_, duplicated) -> duplicated.maxBy { STATE_PRIORITY.getValue(it.state) } }
    }

    private fun parseElement(
        element: Element,
        category: String,
    ): QuestSnapshot? {
        val nameText = element.selectFirst("td.td7s")?.text()
            ?: element.selectFirst("h1, h2, h3, h4, [data-quest-name]")?.text()
            ?: element.text()
        val questId = element.attr("data-quest-id").ifBlank {
            QUEST_ID.find(nameText)?.groupValues?.get(1).orEmpty()
        }
        if (questId.isBlank()) return null

        val fullText = element.text().replace(WHITESPACE, " ").trim()
        val actionLink = element.selectFirst("a[href*='action=']")
        val actionHref = actionLink?.attr("href").orEmpty()
        val action = ACTION.find(actionHref)?.groupValues?.get(1)
            ?: element.selectFirst("[name=complete]")?.let { "complete" }
            ?: element.selectFirst("[name=get]")?.let { "get" }
        val progress = PROGRESS.find(fullText)?.let { match ->
            QuestProgress(
                current = match.groupValues[1].toInt(),
                target = match.groupValues[2].toInt(),
            )
        }
        val state = when {
            action == "complete" -> QuestState.CLAIMABLE
            action == "get" -> QuestState.AVAILABLE
            category.contains("완료") || fullText.contains("완료한 퀘스트") -> QuestState.COMPLETED
            category.contains("진행") || progress != null -> QuestState.ACTIVE
            else -> QuestState.UNAVAILABLE
        }

        return QuestSnapshot(
            questId = questId,
            name = nameText.replace(QUEST_ID, "").trim(),
            state = state,
            progress = progress,
            actionNo = NO_PARAMETER.find(actionHref)?.groupValues?.get(1)?.let(::decode),
        )
    }

    private fun categoryOf(element: Element?): String {
        var cursor = element?.previousElementSibling()
        while (cursor != null) {
            if (cursor.tagName().equals("h4", ignoreCase = true)) return cursor.text()
            if (cursor.tagName().equals("table", ignoreCase = true)) return ""
            cursor = cursor.previousElementSibling()
        }
        return ""
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)

    private companion object {
        val QUEST_ID = Regex("\\[([A-Za-z0-9_-]{4,})]")
        val PROGRESS = Regex("\\[\\s*(\\d+)\\s*/\\s*(\\d+)\\s*]")
        val ACTION = Regex("[?&]action=(get|complete)(?:[&#]|$)")
        val NO_PARAMETER = Regex("[?&]no=([^&\"'#\\s]+)")
        val WHITESPACE = Regex("\\s+")
        val STATE_PRIORITY = mapOf(
            QuestState.UNAVAILABLE to 0,
            QuestState.COMPLETED to 1,
            QuestState.AVAILABLE to 2,
            QuestState.ACTIVE to 3,
            QuestState.CLAIMABLE to 4,
        )
    }
}
