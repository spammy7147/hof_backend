package app.spammy.hof.external.parser

import app.spammy.hof.external.model.HofCharacter
import org.jsoup.Jsoup
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
        val document = Jsoup.parse(html, HOF_BASE_URL)
        val byId = linkedMapOf<String, HofCharacter>()

        document.select("""[href*=char=], [action*=char=], [onclick*=char=], [data-href*=char=]""").forEach { element ->
            val source = listOf("href", "action", "onclick", "data-href")
                .joinToString(" ") { attr -> element.attr(attr) }
            val name = element.text().toCandidateName()

            CHAR_ID_REGEX.findAll(source).forEach { match ->
                val id = match.groupValues[1]
                byId.putIfAbsent(
                    id,
                    HofCharacter(
                        id = id,
                        name = name,
                    ),
                )
            }
        }

        CHAR_ID_REGEX.findAll(html).forEach { match ->
            val id = match.groupValues[1]
            byId.putIfAbsent(id, HofCharacter(id = id))
        }

        return byId.values.toList()
    }

    /**
     * 링크 텍스트가 캐릭터명 후보로 쓸 수 있는지 정리한다.
     */
    private fun String.toCandidateName(): String =
        trim()
            .takeIf { name -> name.isNotBlank() && !name.all { it.isDigit() } && name.length <= 20 }
            .orEmpty()

    private companion object {
        val CHAR_ID_REGEX = Regex("""[?&]char=(\d+)""")
        const val HOF_BASE_URL = "http://sic.zerosic.com/ZeroHOF/index.php"
    }
}
