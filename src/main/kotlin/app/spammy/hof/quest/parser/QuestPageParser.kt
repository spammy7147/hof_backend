package app.spammy.hof.quest.parser

import app.spammy.hof.external.parser.HofHtmlParser

import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestIdentityFactory
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.springframework.stereotype.Component

data class QuestPageObservation(
    val quests: List<QuestSnapshot>,
    val complete: Boolean,
)

@Component
class QuestPageParser {
    fun parse(html: String): List<QuestSnapshot> = parseDocument(HofHtmlParser.parse(html))

    fun parseObservation(
        html: String,
        finalUrl: String,
        statusCode: Int = 200,
    ): QuestPageObservation {
        val document = HofHtmlParser.parse(html, finalUrl)
        val contents = document.selectFirst("#contents")
        val normalizedText = contents?.text()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val completeSections = contents?.let { scope ->
            REQUIRED_SECTION_HEADINGS.mapNotNull { heading -> completeSection(scope, heading) }
        }.orEmpty()
        val hasCompleteSections = completeSections.size == REQUIRED_SECTION_HEADINGS.size &&
            completeSections.all(::hasOnlyParsableRows)
        val complete = statusCode in 200..299 &&
            QUEST_URL_PATTERN.containsMatchIn(finalUrl) &&
            contents != null &&
            normalizedText.contains("퀘스트 목록") &&
            hasCompleteSections &&
            HofHtmlParser.hasCompletePageTerminator(html, finalUrl)
        return QuestPageObservation(parseDocument(document), complete)
    }

    private fun completeSection(contents: Element, headingPattern: Regex): CompleteQuestSection? {
        val heading = contents.select("h1, h2, h3, h4, h5, h6").firstOrNull { candidate ->
            headingPattern.matches(normalize(candidate.text()))
        } ?: return null
        val sectionTable = generateSequence(heading.nextElementSibling()) { it.nextElementSibling() }
            .takeWhile { sibling -> sibling.tagName() !in HEADING_TAGS }
            .mapNotNull { sibling ->
                if (sibling.tagName() == "table") sibling else sibling.selectFirst("table")
            }
            .firstOrNull()
            ?: return null
        val hasRequiredHeader = sectionTable.select("tr").any { row ->
            row.children()
                .filter { it.tagName() == "th" || it.tagName() == "td" }
                .map { normalize(it.text()) } == REQUIRED_HEADERS
        }
        if (!hasRequiredHeader) return null
        return CompleteQuestSection(sectionTable, parseSection(heading.text()))
    }

    private fun hasOnlyParsableRows(section: CompleteQuestSection): Boolean {
        val rows = section.table.select("tr")
            .filter { row -> row.closest("table") === section.table }
            .filterNot { row ->
            row.children()
                .filter { it.tagName() == "th" || it.tagName() == "td" }
                .map { normalize(it.text()) } == REQUIRED_HEADERS
            }
            .filter { row -> directCells(row).any { cell -> normalize(cell.text()).isNotEmpty() } }
        var index = 0
        while (index < rows.size) {
            val start = rows[index]
            if (!isQuestStart(start)) return false
            val span = directCells(start)
                .mapNotNull { cell -> cell.attr("rowspan").toIntOrNull() }
                .maxOrNull()
                ?: 1
            if (span !in 1..MAX_COMPLETE_SECTION_ROWSPAN || index + span > rows.size) return false
            val nodes = rows.subList(index, index + span)
            if (nodes.drop(1).any(::isQuestStart)) return false
            if (parseBlock(QuestBlock(start, nodes), section.section) == null) return false
            index += span
        }
        return true
    }

    private data class CompleteQuestSection(
        val table: Element,
        val section: QuestSection?,
    )

    private fun parseDocument(document: org.jsoup.nodes.Document): List<QuestSnapshot> {
        val scope = document.selectFirst("#contents") ?: document
        var sourceOrder = 0
        val occurrences = questBlocks(scope)
            .mapNotNull { block ->
                parseBlock(block, sectionOf(block.start))
                    ?.copy(sourceOrder = sourceOrder++)
            }

        return occurrences
            .groupBy(QuestSnapshot::questKey)
            .map { (_, duplicates) -> duplicates.maxBy(::occurrencePriority) }
            .sortedBy(QuestSnapshot::sourceOrder)
    }

    private data class QuestBlock(
        val start: Element,
        val nodes: List<Element>,
    )

    private fun questBlocks(scope: Element): List<QuestBlock> = scope.select("tr, [data-quest-id]")
        .filter(::isTopLevelQuestNode)
        .filter(::isQuestStart)
        .map { start ->
            if (!start.tagName().equals("tr", ignoreCase = true)) {
                QuestBlock(start = start, nodes = listOf(start))
            } else {
                val continuationRows = generateSequence(start.nextElementSibling()) { it.nextElementSibling() }
                    .filter { it.tagName().equals("tr", ignoreCase = true) }
                    .takeWhile { !isQuestStart(it) }
                    .toList()
                QuestBlock(start = start, nodes = listOf(start) + continuationRows)
            }
        }

    private fun isQuestStart(element: Element): Boolean = element.hasAttr("data-quest-id") ||
        (
            element.tagName().equals("tr", ignoreCase = true) &&
                directCells(element).any(::containsOwnedDisplayCode)
            )

    private fun containsOwnedDisplayCode(cell: Element): Boolean {
        return QUEST_ID.containsMatchIn(ownedCellText(cell))
    }

    private fun ownedCellText(cell: Element): String {
        val ownedContent = cell.clone()
        ownedContent.select("table, [data-quest-id]").forEach { it.remove() }
        return ownedContent.text()
    }

    private fun occurrencePriority(snapshot: QuestSnapshot): Int = when {
        snapshot.state == QuestState.CLAIMABLE -> 5
        snapshot.section == QuestSection.ACTIVE -> 4
        snapshot.section == QuestSection.AVAILABLE -> 3
        snapshot.section == QuestSection.WAITING -> 2
        snapshot.section == QuestSection.COMPLETED -> 1
        else -> 0
    }

    private fun isTopLevelQuestNode(element: Element): Boolean {
        return element.parents().none { parent ->
            parent.hasAttr("data-quest-id") ||
                parent.tagName().equals("tr", ignoreCase = true)
        }
    }

    private fun parseBlock(
        block: QuestBlock,
        section: QuestSection?,
    ): QuestSnapshot? {
        val start = block.start
        val startCells = directCells(start)
        val nameCell = startCells.firstOrNull { it.hasClass("td7s") }
            ?: startCells.firstOrNull(::containsOwnedDisplayCode)
        val nameText = nameCell?.let(::ownedCellText)
            ?: start.selectFirst("h1, h2, h3, h4, [data-quest-name]")?.text()
            ?: start.text()
        val displayCode = start.attr("data-quest-id").ifBlank {
            QUEST_ID.find(nameText)?.groupValues?.get(1).orEmpty()
        }
        if (displayCode.isBlank()) return null

        val normalizedName = normalize(nameText.replace(QUEST_ID, ""))
        val identity = QuestIdentityFactory.create(displayCode, normalizedName)
        val actionHref = block.nodes.firstNotNullOfOrNull { node ->
            selectOwnedFirst(node, "a[href*='action=']")?.attr("href")
        }.orEmpty()
        val formAction = block.nodes.firstNotNullOfOrNull { node ->
            selectOwnedFirst(node, "form[action*='action=']")?.attr("action")
        }.orEmpty()
        val actionControl = block.nodes.firstNotNullOfOrNull { node ->
            selectOwnedFirst(node, "[name=complete], [name=get]")
        }
        val action = ACTION.find(actionHref)?.groupValues?.get(1)
            ?: ACTION.find(formAction)?.groupValues?.get(1)
            ?: block.nodes.firstNotNullOfOrNull { selectOwnedFirst(it, "[name=complete]") }?.let { "complete" }
            ?: block.nodes.firstNotNullOfOrNull { selectOwnedFirst(it, "[name=get]") }?.let { "get" }
        val actionNo = actionNo(
            nodes = block.nodes,
            actionControl = actionControl,
            actionHref = actionHref,
            formAction = formAction,
        )
        val resolvedSection = section ?: sectionFromAction(action)
        val missionTexts = block.nodes.flatMap(::missionTexts)
        val missions = missionTexts.map { text ->
            parseMission(
                questKey = identity.questKey,
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
            questKey = identity.questKey,
            name = normalizedName,
            state = state,
            section = resolvedSection,
            sourceOrder = 0,
            missions = missions,
            actionNo = actionNo,
            rewards = rewardTexts(block),
            displayCode = identity.displayCode,
        )
    }

    private fun actionNo(
        nodes: List<Element>,
        actionControl: Element?,
        actionHref: String,
        formAction: String,
    ): String? {
        val encodedUrlValue = NO_PARAMETER.find(actionHref)?.groupValues?.get(1)
            ?: NO_PARAMETER.find(formAction)?.groupValues?.get(1)
        if (encodedUrlValue != null) return decodeUrlParameter(encodedUrlValue)

        val actionForm = actionControl?.closest("form")
        return actionForm?.let { form ->
            selectOwnedFirst(form, "input[name=no]")?.attr("value")?.ifBlank { null }
        }
            ?: nodes.firstNotNullOfOrNull { node ->
                selectOwnedFirst(node, "input[name=no]")?.attr("value")?.ifBlank { null }
            }
    }

    private fun selectOwnedFirst(owner: Element, selector: String): Element? = owner.select(selector)
        .firstOrNull { selected ->
            selected.parents()
                .takeWhile { it !== owner }
                .none {
                    it.hasAttr("data-quest-id") ||
                        it.normalName() == "table" ||
                        it.normalName() == "tr"
                }
        }

    private fun missionTexts(element: Element): List<String> {
        val rewardCell = rewardCell(element)
        val missionCells = directCells(element)
            .filterNot {
                it.hasClass("td7s") ||
                    it.hasClass("td8s") ||
                    it.isWithin(rewardCell)
            }
            .flatMap { splitMissionSegments(it) }
        if (missionCells.isNotEmpty()) return missionCells

        return splitMissionSegments(element, rewardCell)
    }

    private fun rewardTexts(block: QuestBlock): List<String> = rewardTexts(block.start) +
        block.nodes.drop(1).flatMap(::explicitRewardTexts)

    private fun rewardTexts(element: Element): List<String> {
        val rewardCell = rewardCell(element) ?: return emptyList()

        return rewardLines(rewardCell)
    }

    private fun explicitRewardTexts(element: Element): List<String> {
        val cells = directCells(element)
        val localLabel = cells.indexOfFirst { normalize(it.text()) == "보상" }
        val rewardCell = when {
            localLabel >= 0 && localLabel + 1 <= cells.lastIndex -> cells[localLabel + 1]
            else -> cells.firstOrNull { LABELED_REWARD_PREFIX.containsMatchIn(it.text()) }
        } ?: return emptyList()

        return rewardLines(rewardCell)
    }

    private fun rewardLines(rewardCell: Element): List<String> =
        splitDisplayLines(rewardCell)
            .map { normalize(it.replaceFirst(REWARD_PREFIX, "")) }
            .filter { it.isNotBlank() && it != "-" }

    private fun rewardCell(element: Element): Element? {
        val cells = directCells(element)
        if (cells.isEmpty()) return null

        val localLabel = cells.indexOfFirst { normalize(it.text()) == "보상" }
        if (localLabel >= 0 && localLabel + 1 <= cells.lastIndex) return cells[localLabel + 1]

        cells.firstOrNull { LABELED_REWARD_PREFIX.containsMatchIn(it.text()) }?.let { return it }

        val table = element.closest("table")
        val rewardColumn = table?.let(::rewardColumnIndex)
        if (rewardColumn != null) return cells.getOrNull(rewardColumn)

        val actionCell = cells.lastOrNull() ?: return null
        val hasLegacyShape = element.tagName().equals("tr", ignoreCase = true) &&
            cells.size >= 4 &&
            cells.first().hasClass("td7s") &&
            (
                actionCell.hasClass("td8s") ||
                    actionCell.select("a[href*='action='], [name=complete], [name=get]").isNotEmpty()
                )
        return cells.getOrNull(cells.lastIndex - 1).takeIf { hasLegacyShape }
    }

    private fun directCells(element: Element): List<Element> = element.children()
        .filter { it.tagName().equals("td", ignoreCase = true) }

    private fun rewardColumnIndex(table: Element): Int? = table.select("tr")
        .filter { it.closest("table") === table }
        .filterNot(::isQuestDataRow)
        .firstNotNullOfOrNull { row ->
            val headerCells = row.children()
                .filter {
                    it.tagName().equals("th", ignoreCase = true) ||
                        it.tagName().equals("td", ignoreCase = true)
                }

            if (headerCells.none { normalize(it.text()) == "퀘스트명" }) return@firstNotNullOfOrNull null

            headerCells.indexOfFirst { normalize(it.text()) == "보상" }
                .takeIf { it >= 0 }
        }

    private fun isQuestDataRow(row: Element): Boolean {
        val cells = directCells(row)
        return row.hasAttr("data-quest-id") ||
            cells.any { it.hasClass("td7s") } ||
            QUEST_ID.containsMatchIn(cells.firstOrNull()?.text().orEmpty())
    }

    private fun Element.isWithin(ancestor: Element?): Boolean = ancestor != null &&
        (this === ancestor || parents().any { it === ancestor })

    private fun splitDisplayLines(element: Element): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            lines += current.toString()
            current.clear()
        }

        fun visit(node: Node) {
            when (node) {
                is TextNode -> current.append(node.wholeText)
                is Element -> when {
                    node.tagName().equals("br", ignoreCase = true) -> flush()
                    node.isBlock -> {
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
        return lines
    }

    private fun splitMissionSegments(
        element: Element,
        excluded: Element? = null,
    ): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            val text = normalize(current.toString())
            if (MISSION_PREFIX.containsMatchIn(text)) segments += text
            current.clear()
        }

        fun visit(node: Node) {
            if (node === excluded) return
            when (node) {
                is TextNode -> current.append(node.wholeText)
                is Element -> when {
                    node.hasAttr("data-quest-id") -> Unit
                    node.tagName().equals("table", ignoreCase = true) -> Unit
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
        questKey: String,
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
        val stableDescriptor = normalize(
            PROGRESS.replace(description) { match ->
                "[required=${match.groupValues[2]}]"
            },
        )

        return QuestMission(
            key = semanticMissionKey(questKey, type, stableDescriptor),
            type = type,
            target = target,
            progress = progress,
            completable = completable,
        )
    }

    private fun semanticMissionKey(
        questKey: String,
        type: QuestMissionType,
        stableDescriptor: String,
    ): String {
        // Identical descriptors intentionally share a key: no source qualifier exists to distinguish them stably.
        val normalizedIdentity = stableDescriptor.lowercase(Locale.ROOT)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedIdentity.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(16)
        return "$questKey:${type.name.lowercase(Locale.ROOT)}:$digest"
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

    private fun decodeUrlParameter(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        value
    }

    private companion object {
        val QUEST_URL_PATTERN = Regex("(?:[?&])menu=quest(?:&|$)", RegexOption.IGNORE_CASE)
        val REQUIRED_SECTION_HEADINGS = listOf(
            Regex("진행중인\\s*퀘스트\\s*목록"),
            Regex("수락\\s*가능한\\s*퀘스트\\s*목록"),
            Regex("대기중인\\s*퀘스트\\s*목록"),
        )
        val REQUIRED_HEADERS = listOf("퀘스트명", "타입", "제한", "보상", "행동")
        val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        val QUEST_ID = Regex("\\[([A-Za-z0-9_-]{4,})]")
        val PROGRESS = Regex("\\[\\s*(\\d+)\\s*/\\s*(\\d+)\\s*]")
        val ACTION = Regex("[?&]action=(get|complete)(?:[&#]|$)")
        val NO_PARAMETER = Regex("[?&]no=([^&\"'#\\s]+)")
        val MISSION_PREFIX = Regex("^미션\\s*[:：]\\s*")
        val REWARD_PREFIX = Regex("^\\s*보상\\s*[:：]?\\s*")
        val LABELED_REWARD_PREFIX = Regex("^\\s*보상\\s*[:：]\\s*")
        val WHITESPACE = Regex("\\s+")
        val MISSION_BLOCK_TAGS = setOf("div", "li", "p")
        const val MAX_COMPLETE_SECTION_ROWSPAN = 20
    }
}
