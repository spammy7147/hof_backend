package app.spammy.hof.quest.parser

import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSection
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
        val elements = document.select("#contents table tr").ifEmpty { document.select("table tr") }.toMutableList()
        elements += document.select("[data-quest-id]").filter { it.closest("tr") == null }

        return elements
            .mapNotNull { element -> parseElement(element, sectionOf(element)) }
            .distinctBy(QuestSnapshot::questId)
            .mapIndexed { sourceOrder, snapshot -> snapshot.copy(sourceOrder = sourceOrder) }
    }

    private fun parseElement(
        element: Element,
        section: QuestSection?,
    ): QuestSnapshot? {
        val nameText = element.selectFirst("td.td7s")?.text()
            ?: element.selectFirst("h1, h2, h3, h4, [data-quest-name]")?.text()
            ?: element.text()
        val questId = element.attr("data-quest-id").ifBlank {
            QUEST_ID.find(nameText)?.groupValues?.get(1).orEmpty()
        }
        if (questId.isBlank()) return null

        val normalizedName = normalize(nameText.replace(QUEST_ID, ""))
        val actionHref = element.selectFirst("a[href*='action=']")?.attr("href").orEmpty()
        val action = ACTION.find(actionHref)?.groupValues?.get(1)
            ?: element.selectFirst("[name=complete]")?.let { "complete" }
            ?: element.selectFirst("[name=get]")?.let { "get" }
        val resolvedSection = section ?: sectionFromAction(action)
        val missionTexts = missionTexts(element)
        val missions = missionTexts.mapIndexed { index, text ->
            parseMission(
                questId = questId,
                missionIndex = index,
                text = text,
                hasCompleteAction = action == "complete",
            )
        }
        val state = when {
            resolvedSection == QuestSection.COMPLETED -> QuestState.COMPLETED
            resolvedSection == QuestSection.AVAILABLE -> QuestState.AVAILABLE
            resolvedSection == QuestSection.WAITING -> QuestState.UNAVAILABLE
            action == "complete" -> QuestState.CLAIMABLE
            else -> QuestState.ACTIVE
        }

        return QuestSnapshot(
            questId = questId,
            name = normalizedName,
            state = state,
            section = resolvedSection,
            sourceOrder = 0,
            missions = missions,
            actionNo = NO_PARAMETER.find(actionHref)?.groupValues?.get(1)?.let(::decode),
        )
    }

    private fun missionTexts(element: Element): List<String> {
        val cells = element.select("td")
            .filterNot { it.hasClass("td7s") || it.hasClass("td8s") }
            .map { normalize(it.text()) }
            .filter { it.contains("미션") }
        if (cells.isNotEmpty()) return cells

        return listOf(normalize(element.text())).filter { it.contains("미션") }
    }

    private fun parseMission(
        questId: String,
        missionIndex: Int,
        text: String,
        hasCompleteAction: Boolean,
    ): QuestMission {
        val description = normalize(text.replaceFirst(MISSION_PREFIX, ""))
        val type = when {
            description.contains("즉시 완료") -> QuestMissionType.IMMEDIATE
            description.contains("아이템 전달") || description.contains("아이템 반납") -> QuestMissionType.ITEM_TURN_IN
            description.contains("몬스터 처치") -> QuestMissionType.MONSTER_KILL
            description.contains("지역 클리어") || description.contains("맵 클리어") -> QuestMissionType.MAP_CLEAR
            else -> QuestMissionType.OTHER
        }
        val progress = PROGRESS.find(description)?.let { match ->
            QuestProgress(
                current = match.groupValues[1].toInt(),
                required = match.groupValues[2].toInt(),
            )
        }
        val target = extractTarget(description)
        val completable = type == QuestMissionType.IMMEDIATE ||
            hasCompleteAction ||
            progress?.let { it.current >= it.required } == true

        return QuestMission(
            key = "$questId:$missionIndex",
            type = type,
            target = target,
            progress = progress,
            completable = completable,
        )
    }

    private fun extractTarget(description: String): String? {
        val start = description.indexOf('(')
        val end = description.lastIndexOf(')')
        if (start < 0 || end <= start) return null
        return normalize(description.substring(start + 1, end)).ifBlank { null }
    }

    private fun sectionOf(element: Element): QuestSection? {
        val table = element.closest("table") ?: return null
        var cursor = table.previousElementSibling()
        while (cursor != null) {
            if (cursor.tagName().equals("h4", ignoreCase = true)) return parseSection(cursor.text())
            if (cursor.tagName().equals("table", ignoreCase = true)) return null
            cursor = cursor.previousElementSibling()
        }
        return null
    }

    private fun parseSection(heading: String): QuestSection? = when {
        heading.contains("진행") || heading.contains("ACTIVE", ignoreCase = true) -> QuestSection.ACTIVE
        heading.contains("수락 가능") || heading.contains("AVAILABLE", ignoreCase = true) -> QuestSection.AVAILABLE
        heading.contains("대기") || heading.contains("WAITING", ignoreCase = true) -> QuestSection.WAITING
        heading.contains("완료") || heading.contains("COMPLETED", ignoreCase = true) -> QuestSection.COMPLETED
        else -> null
    }

    private fun sectionFromAction(action: String?): QuestSection = when (action) {
        "get" -> QuestSection.AVAILABLE
        else -> QuestSection.ACTIVE
    }

    private fun normalize(value: String): String = value.replace(WHITESPACE, " ").trim()

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

    private companion object {
        val QUEST_ID = Regex("\\[([A-Za-z0-9_-]{4,})]")
        val PROGRESS = Regex("\\[\\s*(\\d+)\\s*/\\s*(\\d+)\\s*]")
        val ACTION = Regex("[?&]action=(get|complete)(?:[&#]|$)")
        val NO_PARAMETER = Regex("[?&]no=([^&\"'#\\s]+)")
        val MISSION_PREFIX = Regex("^\\s*미션\\s*:\\s*")
        val WHITESPACE = Regex("\\s+")
    }
}
