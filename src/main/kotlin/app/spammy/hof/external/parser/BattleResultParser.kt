package app.spammy.hof.external.parser

import app.spammy.hof.external.model.HofBattleLoot
import app.spammy.hof.external.model.HofBattleOutcome
import app.spammy.hof.external.model.HofBattleResult
import app.spammy.hof.external.model.HofBattleSide
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.springframework.stereotype.Component

@Component
/**
 * HOF 전투 결과 HTML을 앱에서 표시하기 쉬운 구조로 파싱한다.
 */
class BattleResultParser {
    /**
     * HOF 전투 결과 HTML을 앱 표시용 구조로 변환한다.
     *
     * 승리 문구는 "로그 주소 복사" 링크 뒤 텍스트가 가장 신뢰도가 높아서 먼저 사용하고,
     * 적군/아군 row는 HOF 원문 출력 순서대로 첫 번째 블록을 적군, 두 번째 블록을 아군으로 본다.
     */
    fun parse(
        html: String,
        playerName: String?,
    ): HofBattleResult =
        parseAll(html = html, playerName = playerName).first()

    /**
     * 3회 전투처럼 한 HTML에 여러 결과 블록이 있는 경우 모든 라운드를 파싱한다.
     */
    fun parseAll(
        html: String,
        playerName: String?,
    ): List<HofBattleResult> {
        val document = Jsoup.parse(html)
        val documentLines = DomLineCollector().collect(document)

        return splitRoundLines(documentLines).map { roundLines ->
            parseRoundLines(lines = roundLines, playerName = playerName)
        }
    }

    /**
     * DOM 순서와 줄 경계를 보존한 라운드에서 제목, URL, 보상, 아군/적군 상태를 읽는다.
     *
     * 기존 scalar 정규식에는 이 라운드의 텍스트만 평탄화해 전달한다. 전리품은 평탄화하지 않은 줄 목록을
     * 사용하고 raw-log URL도 같은 라운드 범위의 링크에서만 선택하므로 인접 라운드의 값이 섞이지 않는다.
     */
    private fun parseRoundLines(
        lines: List<DomLine>,
        playerName: String?,
    ): HofBattleResult {
        val text = lines.joinToString(" ") { line -> line.text }.normalized()
        val title = findTitle(text)
        val statBlocks = parseSideBlocks(text)
        val enemySide = statBlocks.getOrNull(0) ?: HofBattleSide.EMPTY
        val allySide = statBlocks.getOrNull(1) ?: HofBattleSide.EMPTY

        return HofBattleResult(
            outcome = parseOutcome(
                title = title,
                playerName = playerName,
            ),
            title = title,
            turns = TURN_PATTERN.find(text)?.groupValues?.getOrNull(1)?.toNumberOrNull(),
            funds = parseFunds(text),
            experience = parseExperience(text),
            loots = parseLoots(lines.map(DomLine::text)),
            quest = parseQuest(text),
            enemySide = enemySide,
            allySide = allySide,
            rawLogUrl = findRawLogUrl(lines),
        )
    }

    /**
     * 가장 신뢰할 수 있는 전투 결과 제목을 찾는다.
     */
    private fun findTitle(text: String): String {
        extractLogCopyTitle(text)?.let { title -> return title }
        return extractTitle(text).orEmpty()
    }

    private fun extractLogCopyTitle(text: String): String? =
        LOG_COPY_TITLE_PATTERN.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.normalized()
            ?.cleanTitlePrefix()
            ?.ifBlank { null }

    private fun extractTitle(text: String): String? =
        TITLE_PATTERN.find(text)
            ?.value
            ?.normalized()
            ?.cleanTitlePrefix()
            ?.ifBlank { null }

    /** 같은 라운드 범위에 속한 링크 중 명시적인 로그 주소 링크를 우선해 원본 URL을 선택한다. */
    private fun findRawLogUrl(lines: List<DomLine>): String? {
        val links = lines.flatMap(DomLine::links)
        return (
            links.firstOrNull { link -> link.text.contains("로그 주소") }
                ?: links.firstOrNull { link -> link.url.contains("log", ignoreCase = true) }
            )
            ?.url
            ?.ifBlank { null }
    }

    /**
     * 승리/패배/무승부를 전투 결과 제목과 권위 있는 플레이어 이름으로 판단한다.
     */
    private fun parseOutcome(
        title: String,
        playerName: String?,
    ): HofBattleOutcome {
        if (title.contains("무승부")) return HofBattleOutcome.DRAW

        val normalizedPlayerName = playerName
            ?.normalized()
            ?.takeUnless { name -> name.isBlank() || name.equals("Unknown", ignoreCase = true) }
            ?: return HofBattleOutcome.UNKNOWN

        return when {
            title.contains("승리했다") && title.contains(normalizedPlayerName) -> HofBattleOutcome.VICTORY
            title.contains("승리했다") -> HofBattleOutcome.DEFEAT
            else -> HofBattleOutcome.UNKNOWN
        }
    }

    private fun parseFunds(text: String): Int? =
        FUNDS_PATTERN.find(text)?.groupValues?.getOrNull(1)?.toNumberOrNull()
            ?: REWARD_FUNDS_PATTERN.find(text)?.groupValues?.getOrNull(1)?.toNumberOrNull()

    private fun parseExperience(text: String): Int? =
        EXPERIENCE_PATTERN.find(text)?.groupValues?.getOrNull(1)?.toNumberOrNull()
            ?: REWARD_EXPERIENCE_PATTERN.find(text)?.groupValues?.getOrNull(1)?.toNumberOrNull()

    /**
     * DOM에서 보존한 전리품 표시 줄을 순서대로 구조화한다.
     *
     * `전리품` 머리말 뒤의 각 줄은 독립적인 전리품 원문이다. trailing `x 숫자`가 있으면 이름과 수량을
     * 분리하고, 없으면 원문 전체를 이름으로 두고 quantity 1을 사용한다. 따라서 한 섹션에 두 형식이
     * 섞여 있어도 정규식 전체 매칭 과정에서 어느 줄도 유실되지 않는다.
     */
    private fun parseLoots(lines: List<String>): List<HofBattleLoot> {
        val lootLines = mutableListOf<String>()
        var collecting = false

        for (sourceLine in lines) {
            val line = sourceLine.normalized()
            if (line.isBlank()) continue

            if (!collecting) {
                val headerIndex = line.indexOf(LOOT_HEADER)
                if (headerIndex < 0) continue

                collecting = true
                val inlineLoot = line.substring(headerIndex + LOOT_HEADER.length).normalized()
                if (inlineLoot.isNotBlank()) lootLines += inlineLoot
                continue
            }

            if (isLootSectionTerminator(line)) break
            lootLines += line
        }

        return lootLines.map(::parseLoot)
    }

    private fun isLootSectionTerminator(line: String): Boolean =
        line.contains("신앙심") ||
            line.contains("변동") ||
            line.contains("남은 HP") ||
            line.contains("퀘스트") ||
            line.startsWith("Update", ignoreCase = true) ||
            line.contains("Copy Right", ignoreCase = true)

    /** 전리품 원문의 trailing 수량 접미사를 제거하고 구조화 모델을 만든다. */
    private fun parseLoot(rawText: String): HofBattleLoot {
        val suffix = LOOT_QUANTITY_SUFFIX_PATTERN.find(rawText)
        val quantity = suffix?.groupValues?.getOrNull(1)?.toNumberOrNull() ?: 1
        val name = suffix
            ?.let { match -> rawText.substring(0, match.range.first).trim() }
            ?.ifBlank { rawText }
            ?: rawText
        return HofBattleLoot(name = name, quantity = quantity, rawText = rawText)
    }

    private fun parseQuest(text: String): String? =
        QUEST_PATTERN.find(text)?.value?.normalized()

    /**
     * 적군/아군 상태 블록을 순서대로 읽는다. 첫 번째는 적군, 두 번째는 아군이다.
     */
    private fun parseSideBlocks(text: String): List<HofBattleSide> =
        SIDE_PATTERN.findAll(text)
            .map { match ->
                HofBattleSide(
                    hpCurrent = match.groupValues.getOrNull(1)?.toNumberOrNull(),
                    hpMax = match.groupValues.getOrNull(2)?.toNumberOrNull(),
                    survivorsAlive = match.groupValues.getOrNull(3)?.toNumberOrNull(),
                    survivorsMax = match.groupValues.getOrNull(4)?.toNumberOrNull(),
                    totalDamage = match.groupValues.getOrNull(5)?.toNumberOrNull(),
                    turnCurrent = match.groupValues.getOrNull(6)?.toNumberOrNull(),
                    turnMax = match.groupValues.getOrNull(7)?.toNumberOrNull(),
                )
            }
            .toList()

    /**
     * DOM 줄 목록을 `Show Detail` 시작 줄로 나눈다.
     *
     * 링크와 줄 경계를 포함한 구조를 먼저 나눈 뒤 각 라운드만 평탄화하므로 서로 다른 로그 URL과 전리품
     * 표시 순서가 전체 문서 `text()` 호출로 사라지지 않는다. 단일 라운드는 기존처럼 앞쪽 문구도 보존한다.
     */
    private fun splitRoundLines(lines: List<DomLine>): List<List<DomLine>> {
        val starts = lines.indices.filter { index -> ROUND_START_PATTERN.containsMatchIn(lines[index].text) }
        if (starts.size <= 1) return listOf(lines)

        return starts.mapIndexed { index, start ->
            val end = starts.getOrNull(index + 1) ?: lines.size
            lines.subList(start, end)
        }
    }

    private data class DomLink(
        val text: String,
        val url: String,
    )

    private data class DomLine(
        val text: String,
        val links: List<DomLink>,
    )

    /**
     * Jsoup DOM을 순회하며 실제 `<br>`, block element, source 줄바꿈을 전투 표시 줄로 변환한다.
     *
     * anchor 정보는 해당 줄에 함께 붙여 round 분리 후에도 URL 소유 범위를 알 수 있게 한다. 이미지 같은
     * 비텍스트 inline element는 순서를 바꾸지 않고 건너뛰며, 인접 inline text는 한 줄로 합친다.
     */
    private class DomLineCollector {
        private val lines = mutableListOf<DomLine>()
        private val text = StringBuilder()
        private val links = mutableListOf<DomLink>()

        fun collect(document: Document): List<DomLine> {
            document.body().childNodes().forEach(::visit)
            flush()
            return lines
        }

        private fun visit(node: Node) {
            when (node) {
                is TextNode -> appendText(node.wholeText)
                is Element -> visitElement(node)
            }
        }

        private fun visitElement(element: Element) {
            if (element.tagName().equals("br", ignoreCase = true)) {
                flush()
                return
            }

            val block = element.tag().isBlock
            if (block) flush()
            if (element.tagName().equals("a", ignoreCase = true) && element.hasAttr("href")) {
                links += DomLink(
                    text = normalize(element.text()),
                    url = element.absUrl("href").ifBlank { element.attr("href") },
                )
            }
            element.childNodes().forEach(::visit)
            if (block) flush()
        }

        private fun appendText(rawText: String) {
            val fragments = rawText.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            fragments.forEachIndexed { index, fragment ->
                val normalized = normalize(fragment)
                if (normalized.isNotBlank()) {
                    if (text.isNotEmpty()) text.append(' ')
                    text.append(normalized)
                }
                if (index < fragments.lastIndex) flush()
            }
        }

        private fun flush() {
            val normalizedText = normalize(text.toString())
            if (normalizedText.isNotBlank() || links.isNotEmpty()) {
                lines += DomLine(text = normalizedText, links = links.toList())
            }
            text.clear()
            links.clear()
        }

        private fun normalize(value: String): String = value.replace(WHITESPACE_PATTERN, " ").trim()
    }

    private fun String.toNumberOrNull(): Int? =
        replace(",", "").replace("$", "").trim().toIntOrNull()

    private fun String.normalized(): String =
        replace(WHITESPACE_PATTERN, " ").trim()

    private fun String.cleanTitlePrefix(): String =
        replace(Regex("""^[)\]\s-]+"""), "").trim()

    private companion object {
        val TITLE_PATTERN = Regex("""(?:무승부!|[^.!?]{1,120}?승리했다!)""")
        val LOG_COPY_TITLE_PATTERN = Regex("""로그\s*주소\s*복사\s*(무승부!|[^.!?]{1,120}?승리했다!)""")
        val ROUND_START_PATTERN = Regex("""Show\s*Detail\(""", RegexOption.IGNORE_CASE)
        val TURN_PATTERN = Regex("""Show\s*Detail\(\s*([\d,]+)\s*turns?\.?\s*\)""", RegexOption.IGNORE_CASE)
        val FUNDS_PATTERN = Regex("""획득\s*Funds\s*:\s*\$?\s*([\d,]+)""", RegexOption.IGNORE_CASE)
        val REWARD_FUNDS_PATTERN = Regex("""보상\s*:\s*([\d,]+)\s*Funds""", RegexOption.IGNORE_CASE)
        val EXPERIENCE_PATTERN = Regex("""획득\s*경험치\s*:\s*([\d,]+)""")
        val REWARD_EXPERIENCE_PATTERN = Regex("""경험치\s*([\d,]+)""")
        val LOOT_QUANTITY_SUFFIX_PATTERN = Regex("""\s+x\s*([\d,]+)$""", RegexOption.IGNORE_CASE)
        val QUEST_PATTERN = Regex("""\[\s*퀘스트\s*정보\s*갱신\s*]\s*.+?\(\s*\d+\s*/\s*\d+\s*\)""")
        val WHITESPACE_PATTERN = Regex("""[\s\p{Z}]+""")
        val SIDE_PATTERN = Regex(
            """남은\s*HP\s*:\s*([\d,]+)\s*/\s*([\d,]+)\s+생존자\s*:\s*([\d,]+)\s*/\s*([\d,]+)\s+총\s*데미지\s*:\s*([\d,]+)(?:\s+턴\s*:\s*([\d,]+)\s*/\s*([\d,]+))?""",
        )
        const val LOOT_HEADER = "전리품"
    }
}
