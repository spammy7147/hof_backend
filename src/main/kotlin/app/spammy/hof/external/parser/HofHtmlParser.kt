package app.spammy.hof.external.parser

import org.jsoup.Jsoup

import org.jsoup.nodes.Document

/**
 * HOF 전체 문서에 공통으로 적용할 DOM 정리 규칙을 소유한다.
 *
 * `#foot`는 본문 기능과 무관한 사이트 푸터이므로 텍스트, 링크, 폼 후보를 읽기 전에 제거한다.
 */
object HofHtmlParser {
    fun parse(html: String, baseUri: String = ""): Document =
        Jsoup.parse(html, baseUri).also { document -> document.select("#foot").remove() }

    /** 기능 파서가 제거하는 공통 footer를 raw 문서에서 먼저 확인한다. */
    fun hasCompletePageTerminator(html: String, baseUri: String = ""): Boolean {
        val document = Jsoup.parse(html, baseUri)
        return document.select("h5").any {
            normalize(it.text()).contains("copy right", ignoreCase = true)
        } && document.select("h6").any {
            normalize(it.text()).contains("h.o.f korean ver", ignoreCase = true)
        } && document.select("img[src]").any { image ->
            image.attr("src").substringBefore('?').substringAfterLast('/').equals("zerohof.gif", true)
        }
    }

    private fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}
