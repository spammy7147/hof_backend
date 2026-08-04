package app.spammy.hof.external.parser

import app.spammy.hof.external.model.HofMainStatus
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
/**
 * HOF 홈 HTML에서 상단 상태바 정보를 파싱한다.
 */
class HofMainStatusParser {
    /**
     * 플레이어명, Funds, Time, Work, Auction 값을 읽는다.
     */
    fun parse(html: String): HofMainStatus {
        val statusContainer = Jsoup.parse(html).selectFirst("#menu2")
            ?: return incompleteStatus()
        val ownTexts = statusContainer.allElements
            .map { element -> element.ownText().normalizeSpaces() }
            .filter { text -> text.isNotBlank() }
        val fullText = statusContainer.text().normalizeSpaces()
        val statusOwnerText = findStatusOwnerText(statusContainer).orEmpty()

        return HofMainStatus(
            playerName = findMenuPlayerName(statusContainer) ?: parsePlayerName(statusOwnerText),
            funds = firstMatch(ownTexts, fullText, FUNDS_REGEX) { it.toLongNumberOrNull() },
            timeCurrent = firstMatch(ownTexts, fullText, TIME_REGEX) { it.toIntOrNull() },
            timeMax = firstMatch(ownTexts, fullText, TIME_REGEX, groupIndex = 2) { it.toIntOrNull() },
            work = firstMatch(ownTexts, fullText, WORK_REGEX) { it.trimStatusValue() } ?: UNKNOWN_VALUE,
            auction = firstMatch(ownTexts, fullText, AUCTION_REGEX) { it.trimStatusValue() } ?: UNKNOWN_VALUE,
        )
    }

    private fun incompleteStatus(): HofMainStatus = HofMainStatus(
        playerName = UNKNOWN_VALUE,
        funds = null,
        timeCurrent = null,
        timeMax = null,
        work = UNKNOWN_VALUE,
        auction = UNKNOWN_VALUE,
    )

    /**
     * 인증 상태바인 `#menu2`의 첫 번째 열에서 플레이어 표시명을 읽는다.
     */
    private fun findMenuPlayerName(statusContainer: Element): String? {
        val statusRow = findMinimalStatusCandidate(statusContainer.select("tr"))
            ?: statusContainer.children().firstOrNull(::containsStatusMarkers)
            ?: return null
        val candidate = statusRow.children().firstOrNull()
            ?.text()
            ?.normalizeSpaces()
            .orEmpty()

        return candidate.takeIf { playerName ->
            playerName.isNotBlank() && !STATUS_FIELD_REGEX.containsMatchIn(playerName)
        }
    }

    /**
     * HOF 원문에서 `《타이틀》캐릭터명` 형태의 플레이어 표시명을 찾는다.
     */
    private fun parsePlayerName(fullText: String): String =
        FUNDS_REGEX.find(fullText)
            ?.range
            ?.first
            ?.let { fundsIndex -> PLAYER_REGEX.findAll(fullText.substring(0, fundsIndex)).lastOrNull()?.value }
            ?: UNKNOWN_VALUE

    /**
     * Funds와 Time을 함께 소유한 상태 영역을 찾는다.
     */
    private fun findStatusOwnerText(statusContainer: Element): String? =
        findMinimalStatusCandidate(statusContainer.select("tr"))
            ?.text()
            ?.normalizeSpaces()
            ?: findMinimalStatusCandidate(
                statusContainer.allElements.filter { element ->
                    element !== statusContainer && element.isNamedStatusContainer()
                },
            )
                ?.text()
                ?.normalizeSpaces()
            ?: statusContainer
                .ownText()
                .normalizeSpaces()
                .takeIf(::containsStatusMarkers)
            ?: statusContainer
                .text()
                .normalizeSpaces()
                .takeIf(::containsStatusMarkers)

    private fun findMinimalStatusCandidate(candidates: Iterable<Element>): Element? {
        val matchingCandidates = candidates.filter(::containsStatusMarkers)
        return matchingCandidates.firstOrNull { candidate ->
            candidate.allElements.none { descendant ->
                descendant !== candidate && descendant in matchingCandidates
            }
        }
    }

    private fun containsStatusMarkers(element: Element): Boolean =
        containsStatusMarkers(element.text().normalizeSpaces())

    private fun containsStatusMarkers(text: String): Boolean =
        FUNDS_REGEX.containsMatchIn(text) && TIME_REGEX.containsMatchIn(text)

    private fun Element.isNamedStatusContainer(): Boolean =
        tagName() == "header" ||
            hasAttr("data-status") ||
            id().containsStatusContainerName() ||
            classNames().any { className -> className.containsStatusContainerName() }

    private fun String.containsStatusContainerName(): Boolean =
        contains("header", ignoreCase = true) || contains("status", ignoreCase = true)

    /**
     * element ownText를 먼저 보고, 실패하면 전체 텍스트에서 첫 매치를 찾는다.
     */
    private fun <T> firstMatch(
        ownTexts: List<String>,
        fullText: String,
        regex: Regex,
        groupIndex: Int = 1,
        transform: (String) -> T?,
    ): T? =
        ownTexts.firstNotNullOfOrNull { text ->
            regex.find(text)?.groupValues?.getOrNull(groupIndex)?.let(transform)
        } ?: regex.find(fullText)?.groupValues?.getOrNull(groupIndex)?.let(transform)

    private fun String.normalizeSpaces(): String =
        replace(Regex("""\s+"""), " ").trim()

    private fun String.toLongNumberOrNull(): Long? =
        replace(",", "").toLongOrNull()

    private fun String.trimStatusValue(): String =
        replace(STOP_MARKER_REGEX, "").trim().ifBlank { UNKNOWN_VALUE }

    private companion object {
        const val UNKNOWN_VALUE = "Unknown"
        val PLAYER_REGEX = Regex("""《[^》]+》\S+""")
        val STATUS_FIELD_REGEX = Regex("""\b(?:Funds|Time|Work|Auction)\s*:""")
        val FUNDS_REGEX = Regex("""Funds\s*:\s*\$\s*([0-9,]+)""")
        val TIME_REGEX = Regex("""Time\s*:\s*(\d+)\s*/\s*(\d+)""")
        val WORK_REGEX = Regex("""Work\s*:\s*(.+?)(?=\s+Auction\s*:|$)""")
        val AUCTION_REGEX = Regex("""Auction\s*:\s*(.+?)(?=\s+(?:Show Detail|Hall of Fame|Copy Right|현재 접속자|[^\s]+\s+Lv\.\d+)|$)""")
        val STOP_MARKER_REGEX = Regex("""\s+(?:Show Detail|Hall of Fame|Copy Right|현재 접속자|[^\s]+\s+Lv\.\d+).*$""")
    }
}
