package app.spammy.hof.town.common.parser

import app.spammy.hof.external.parser.HofHtmlParser

import app.spammy.hof.town.common.model.TownFeatureId
import java.net.URI
import java.text.Normalizer
import org.springframework.stereotype.Component

data class DiscoveredTownLocation(
    val href: String,
)

/** 마을 진입 페이지의 표시 링크에서 계정과 무관한 기능 위치만 보수적으로 발견한다. */
@Component
class TownEntryPageParser {
    fun parse(
        html: String,
        sourceUrl: String = HOF_ENTRY_URL,
    ): Map<TownFeatureId, DiscoveredTownLocation> {
        val source = runCatching { URI(sourceUrl) }.getOrNull() ?: return emptyMap()
        val candidates = mutableMapOf<TownFeatureId, MutableSet<String>>()

        HofHtmlParser.parse(html, sourceUrl).select("a[href]").forEach { anchor ->
            val href = publicMenuHref(source, anchor.attr("href")) ?: return@forEach
            val labels = labelCandidates(anchor.text())
            val matches = TownFeatureId.entries.filter { feature ->
                feature.menuCode == null && feature.allAliases.any { alias -> normalize(alias) in labels }
            }
            if (matches.size == 1) candidates.getOrPut(matches.single(), ::linkedSetOf).add(href)
        }

        return candidates.mapNotNull { (feature, hrefs) ->
            hrefs.singleOrNull()?.let { feature to DiscoveredTownLocation(it) }
        }.toMap()
    }

    private fun publicMenuHref(source: URI, rawHref: String): String? {
        val resolved = runCatching {
            if (rawHref.startsWith('?')) {
                URI(source.scheme, source.rawAuthority, source.path, rawHref.removePrefix("?"), null)
            } else {
                source.resolve(rawHref)
            }.normalize()
        }.getOrNull() ?: return null
        if (!resolved.scheme.equals(HOF_SCHEME, ignoreCase = true) ||
            !resolved.host.equals(HOF_HOST, ignoreCase = true) ||
            resolved.port !in setOf(-1, 80) ||
            resolved.rawUserInfo != null ||
            resolved.rawFragment != null ||
            resolved.path != HOF_PATH
        ) return null

        val queryParts = resolved.rawQuery?.split('&').orEmpty()
        if (queryParts.size != 1) return null
        val pair = queryParts.single().split('=', limit = 2)
        if (pair.size != 2 || !pair[0].equals("menu", ignoreCase = true)) return null
        val menuCode = pair[1]
        if (!menuCode.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return null
        return "?menu=$menuCode"
    }

    private fun labelCandidates(label: String): Set<String> = buildSet {
        add(normalize(label))
        add(normalize(label.replace(PARENTHETICAL, "")))
        PARENTHETICAL.findAll(label).forEach { match -> add(normalize(match.groupValues[1])) }
    }.filterTo(linkedSetOf()) { it.isNotEmpty() }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace(NON_LETTER_OR_DIGIT, "")

    private companion object {
        const val HOF_ENTRY_URL = "http://sic.zerosic.com/ZeroHOF/index.php"
        const val HOF_SCHEME = "http"
        const val HOF_HOST = "sic.zerosic.com"
        const val HOF_PATH = "/ZeroHOF/index.php"
        val PARENTHETICAL = Regex("[（(]([^）)]+)[）)]")
        val NON_LETTER_OR_DIGIT = Regex("[^\\p{L}\\p{N}]+")
    }
}
