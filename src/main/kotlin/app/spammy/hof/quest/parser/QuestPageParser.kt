package app.spammy.hof.quest.parser

import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.springframework.stereotype.Component

@Component
class QuestPageParser {
    fun parse(html: String): List<QuestSnapshot> {
        val document = Jsoup.parse(html)
        var sourceOrder = 0
        val occurrences = document.select("tr, [data-quest-id]")
            .filter(::isTopLevelQuestNode)
            .mapNotNull { element ->
                parseElement(element, sectionOf(element))
                    ?.copy(sourceOrder = sourceOrder++)
            }

        return occurrences
            .groupBy(QuestSnapshot::questId)
            .map { (_, duplicates) -> duplicates.maxBy { STATE_PRIORITY.getValue(it.state) } }
            .sortedBy(QuestSnapshot::sourceOrder)
    }

    private fun isTopLevelQuestNode(element: Element): Boolean {
        val isRow = element.tagName().equals("tr", ignoreCase = true)
        return element.parents().none { parent ->
            parent.hasAttr("data-quest-id") ||
                (!isRow && parent.tagName().equals("tr", ignoreCase = true))
        }
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
        val formAction = element.selectFirst("form[action*='action=']")?.attr("action").orEmpty()
        val actionControl = element.selectFirst("[name=complete], [name=get]")
        val action = ACTION.find(actionHref)?.groupValues?.get(1)
            ?: ACTION.find(formAction)?.groupValues?.get(1)
            ?: element.selectFirst("[name=complete]")?.let { "complete" }
            ?: element.selectFirst("[name=get]")?.let { "get" }
        val resolvedSection = section ?: sectionFromAction(action)
        val missionTexts = missionTexts(element)
        val duplicateCounts = mutableMapOf<String, Int>()
        val missions = missionTexts.map { text ->
            parseMission(
                questId = questId,
                text = text,
                hasCompleteAction = action == "complete",
            )
        }.map { mission ->
            val duplicateNumber = duplicateCounts.merge(mission.key, 1, Int::plus)!!
            if (duplicateNumber == 1) mission else mission.copy(key = "${mission.key}#$duplicateNumber")
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
            actionNo = (
                NO_PARAMETER.find(actionHref)?.groupValues?.get(1)
                    ?: NO_PARAMETER.find(formAction)?.groupValues?.get(1)
                    ?: actionControl?.closest("form")?.selectFirst("input[name=no]")?.attr("value")?.ifBlank { null }
                    ?: element.selectFirst("input[name=no]")?.attr("value")?.ifBlank { null }
                )?.let(::decode),
        )
    }

    private fun missionTexts(element: Element): List<String> {
        val cells = element.select("td")
            .filterNot { it.hasClass("td7s") || it.hasClass("td8s") }
            .flatMap(::splitMissionSegments)
        if (cells.isNotEmpty()) return cells

        return splitMissionSegments(element)
    }

    private fun splitMissionSegments(element: Element): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val text = normalize(current.toString())
            if (text.contains("미션")) segments += text
            current.clear()
        }

        fun visit(node: Node) {
            when (node) {
                is TextNode -> current.append(node.wholeText)
                is Element -> when {
                    node.tagName().equals("br", ignoreCase = true) -> flush()
                    node.normalName() in MISSION_BLOCK_TAGS -> {
                        if (current.isNotBlank()) flush()
                        node.childNodes().forEach(::visit)
                        flush()
                    }
                    else -> node.childNodes().forEach(::visit)
                }
            }
        }

        element.childNodes().forEach(::visit)
        flush()
        return segments
    }

    private fun parseMission(
        questId: String,
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
        val semanticIdentity = target
            ?.let(::normalize)
            ?: normalize(description.replace(PROGRESS, ""))

        return QuestMission(
            key = semanticMissionKey(questId, type, semanticIdentity),
            type = type,
            target = target,
            progress = progress,
            completable = completable,
        )
    }

    private fun semanticMissionKey(
        questId: String,
        type: QuestMissionType,
        semanticIdentity: String,
    ): String {
        val normalizedIdentity = semanticIdentity.lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedIdentity.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(16)
        return "$questId:${type.name.lowercase(Locale.ROOT)}:$digest"
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
        val MISSION_BLOCK_TAGS = setOf("div", "li", "p")
        val STATE_PRIORITY = mapOf(
            QuestState.UNAVAILABLE to 0,
            QuestState.COMPLETED to 1,
            QuestState.AVAILABLE to 2,
            QuestState.ACTIVE to 3,
            QuestState.CLAIMABLE to 4,
        )
    }
}
