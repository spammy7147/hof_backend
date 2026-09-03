package app.spammy.hof.external.parser

import app.spammy.hof.external.model.HofCharacter
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
/**
 * HOF 홈 HTML에서 캐릭터 ID 목록을 파싱한다.
 */
class CharacterRosterParser {
    /**
     * href/action/onclick/data-href와 전체 HTML에서 `char=` 링크를 찾아 캐릭터 목록을 만든다.
     */
    fun parse(html: String): List<HofCharacter> {
        val document = HofHtmlParser.parse(html, HOF_BASE_URL)
        val byId = linkedMapOf<String, HofCharacter>()

        document.select("""[href*=char=], [action*=char=], [onclick*=char=], [data-href*=char=]""").forEach { element ->
            val source = listOf("href", "action", "onclick", "data-href")
                .joinToString(" ") { attr -> element.attr(attr) }
            val card = element.rosterCardEvidence()
            val name = element.text().toCandidateName().ifBlank { card?.name.orEmpty() }

            CHAR_ID_REGEX.findAll(source).forEach { match ->
                val id = match.groupValues[1]
                val observed = HofCharacter(
                    id = id,
                    name = name,
                    level = card?.level,
                    job = card?.job.orEmpty(),
                )
                byId[id] = byId[id]?.mergeObserved(observed) ?: observed
            }
        }

        CHAR_ID_REGEX.findAll(html).forEach { match ->
            val id = match.groupValues[1]
            byId.putIfAbsent(id, HofCharacter(id = id))
        }

        return byId.values.mapIndexed { index, character ->
            character.copy(rosterOrder = index)
        }
    }

    /**
     * 링크 텍스트가 캐릭터명 후보로 쓸 수 있는지 정리한다.
     */
    private fun String.toCandidateName(): String =
        trim()
            .removeSuffix("*")
            .trimEnd()
            .takeIf { name -> name.isNotBlank() && !name.all { it.isDigit() } && name.length <= 20 }
            .orEmpty()

    /** 이미지 링크 밖에 이름·레벨·직업을 쓰는 HOF 홈 캐릭터 카드도 함께 읽는다. */
    private fun Element.rosterCardEvidence(): RosterCardEvidence? =
        generateSequence(this) { it.parent() }
            .take(4)
            .mapNotNull { element -> element.text().toRosterCardEvidence() }
            .firstOrNull()

    private fun String.toRosterCardEvidence(): RosterCardEvidence? {
        val normalized = replace('\u00a0', ' ').replace(WHITESPACE_REGEX, " ").trim()
        if (normalized.length > 100 || LEVEL_TOKEN_REGEX.findAll(normalized).count() != 1) return null
        val match = ROSTER_CARD_REGEX.matchEntire(normalized) ?: return null
        val name = match.groupValues[1].toCandidateName().takeIf(String::isNotBlank) ?: return null
        val level = match.groupValues[2].toIntOrNull() ?: return null
        val job = match.groupValues[3].trim().takeIf { it.isNotBlank() && it.length <= 50 } ?: return null
        return RosterCardEvidence(name, level, job)
    }

    private fun HofCharacter.mergeObserved(other: HofCharacter): HofCharacter = copy(
        name = name.ifBlank { other.name },
        level = level ?: other.level,
        job = job.ifBlank { other.job },
    )

    private data class RosterCardEvidence(
        val name: String,
        val level: Int,
        val job: String,
    )

    private companion object {
        val CHAR_ID_REGEX = Regex("""[?&]char=(\d+)""")
        val WHITESPACE_REGEX = Regex("""\s+""")
        val LEVEL_TOKEN_REGEX = Regex("""\bLv\.?\s*\d+""", RegexOption.IGNORE_CASE)
        val ROSTER_CARD_REGEX = Regex("""^(.+?)\s+Lv\.?\s*(\d+)\s+(.+)$""", RegexOption.IGNORE_CASE)
        const val HOF_BASE_URL = "https://hof.zerosic.com/index.php"
    }
}
