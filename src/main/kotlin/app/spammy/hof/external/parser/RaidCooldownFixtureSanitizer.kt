package app.spammy.hof.external.parser

import java.util.Collections
import java.util.IdentityHashMap
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Tag
import org.springframework.stereotype.Component

data class SanitizedRaidCooldownFixture(
    val html: String,
    val mapCount: Int,
    val timerCount: Int,
)

/**
 * 운영 `raid_hunt` 응답에서 레이드 쿨타임의 DOM 결합 관계만 남긴다.
 *
 * 원문, 인증 필드, 사용자 텍스트는 반환하지 않으며 fixture에 필요한 레이드 링크와 타이머 문구만
 * 익명 값으로 다시 만든다. 결과가 없으면 호출자는 어떤 원문도 기록해서는 안 된다.
 */
@Component
class RaidCooldownFixtureSanitizer {
    fun sanitize(rawHtml: String): SanitizedRaidCooldownFixture? {
        val source = HofHtmlParser.parse(rawHtml)
        val raidLinks = source.select("a[href*=raid_common=]").take(MAX_RAID_LINKS)
        val timerPhrasesByElement = source.select("body *")
            .mapNotNull { element ->
                COOLDOWN_PHRASE.find(element.text())
                    ?.value
                    ?.normalizedWhitespace()
                    ?.let { phrase -> element to phrase }
            }
            .filter { (element) ->
                element.children().none { child -> COOLDOWN_PHRASE.containsMatchIn(child.text()) }
            }
            .take(MAX_TIMERS)
            .toMap(IdentityHashMap())
        if (raidLinks.isEmpty() || timerPhrasesByElement.isEmpty()) return null

        val projectedNodes = Collections.newSetFromMap(IdentityHashMap<Element, Boolean>())
        (raidLinks + timerPhrasesByElement.keys).forEach { element ->
            generateSequence(element) { it.parent() }
                .takeWhile { it.tagName() !in DOCUMENT_TAGS }
                .forEach(projectedNodes::add)
        }

        val output = Jsoup.parse("<html><head></head><body></body></html>")
        output.outputSettings().prettyPrint(false)
        val linkIndexes = raidLinks.withIndex().associate { (index, element) -> element to index + 1 }
        source.body().children()
            .filter(projectedNodes::contains)
            .forEach { child -> output.body().appendChild(project(child, projectedNodes, timerPhrasesByElement, linkIndexes)) }

        val fixtureHtml = output.outerHtml()
        if (fixtureHtml.length > MAX_FIXTURE_LENGTH) return null
        return SanitizedRaidCooldownFixture(
            html = fixtureHtml,
            mapCount = raidLinks.size,
            timerCount = timerPhrasesByElement.size,
        )
    }

    private fun project(
        source: Element,
        projectedNodes: Set<Element>,
        timerPhrasesByElement: Map<Element, String>,
        linkIndexes: Map<Element, Int>,
    ): Element {
        val projected = Element(Tag.valueOf(source.tagName()), "")
        safeClasses(source).takeIf(String::isNotEmpty)?.let { projected.attr("class", it) }
        safeId(source.id())?.let { projected.attr("id", it) }

        linkIndexes[source]?.let { index ->
            projected.attr("href", "index.php?raid_common=fixture-map-$index")
            projected.text("Raid - 익명화 대상 $index")
        } ?: timerPhrasesByElement[source]?.let(projected::text)

        source.children()
            .filter(projectedNodes::contains)
            .forEach { child ->
                projected.appendChild(project(child, projectedNodes, timerPhrasesByElement, linkIndexes))
            }
        return projected
    }

    private fun safeClasses(source: Element): String = source.classNames()
        .filter(::isSafeIdentifier)
        .sorted()
        .joinToString(" ")

    private fun safeId(id: String): String? = id.takeIf(::isSafeIdentifier)

    private fun isSafeIdentifier(value: String): Boolean =
        SAFE_IDENTIFIER.matches(value) && !SENSITIVE_IDENTIFIER.containsMatchIn(value)

    private fun String.normalizedWhitespace(): String = trim().replace(Regex("\\s+"), " ")

    private companion object {
        const val MAX_RAID_LINKS = 20
        const val MAX_TIMERS = 20
        const val MAX_FIXTURE_LENGTH = 32_768
        val DOCUMENT_TAGS = setOf("#root", "html", "body")
        val COOLDOWN_PHRASE = Regex("""다음\s*전투까지\s*[\d,]+\s*초\s*남음""")
        val SAFE_IDENTIFIER = Regex("""[A-Za-z][A-Za-z0-9_-]{0,63}""")
        val SENSITIVE_IDENTIFIER = Regex(
            """(?:account|character|login|member|name|password|player|secret|session|token|user)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
