package app.spammy.hof.battle.service

import org.jsoup.Jsoup
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/** 전투 응답 전체의 텍스트와 표를 보존하고 스크립트·폼·외부 리소스 의존성을 제거한다. */
internal fun archiveBattleHtml(source: String): String {
    val document = Jsoup.parse(source)
    document.select("script, style, iframe, object, embed, #menu2").remove()
    val clean = Cleaner(Safelist.relaxed().addTags("div", "span").removeTags("img")
        .removeAttributes("a", "href")).clean(document)
    clean.head().appendElement("meta").attr("charset", "utf-8")
    clean.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
    clean.head().appendElement("title").text("패배 전투 로그 보관본")
    clean.head().appendElement("style").text("body{font-family:sans-serif;line-height:1.6;padding:16px;overflow-wrap:anywhere}table{border-collapse:collapse;max-width:100%}td,th{border:1px solid #ccc;padding:4px}")
    clean.body().prependElement("p").text("패배 시 받은 전투 응답 보관본입니다. 3회 전투는 전체 응답을 포함합니다.")
    return "<!doctype html>\n" + clean.outerHtml()
}
