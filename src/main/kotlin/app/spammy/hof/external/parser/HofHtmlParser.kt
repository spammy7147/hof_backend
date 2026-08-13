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
}
