package app.spammy.hof.battle.service

import org.jsoup.Jsoup
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.net.URI

/** 전투 표·배치·HOF 이미지를 보존하되 스크립트·폼·임의 외부 리소스는 제거한다. */
internal fun archiveBattleHtml(source: String, detailed: Boolean = false): String {
    val document = Jsoup.parse(source)
    document.select("script, style, iframe, object, embed, #menu2, #menu, #title, #foot").remove()
    document.select("img").forEach { image ->
        val url = battleImageUrl(image.attr("src"))
        if (url == null) image.remove() else image.attr("src", url)
    }
    document.select("[style]").forEach { it.attr("style", battleStyle(it.attr("style"))) }
    document.select("font[color]").forEach {
        if (!Regex("#[a-fA-F0-9]{3,8}|[a-zA-Z]+").matches(it.attr("color"))) it.removeAttr("color")
    }
    val clean = Cleaner(Safelist.relaxed().addTags("div", "span", "font")
        .addAttributes(":all", "class", "style")
        .addAttributes("font", "color").addAttributes("td", "valign")
        .removeAttributes("a", "href")).clean(document)
    clean.head().appendElement("meta").attr("charset", "utf-8")
    clean.head().appendElement("meta").attr("name", "viewport")
        .attr("content", if (detailed) "width=810" else "width=device-width, initial-scale=1")
    clean.head().appendElement("meta").attr("http-equiv", "Content-Security-Policy")
        .attr("content", "default-src 'none'; img-src https://hof.zerosic.com/image/; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'")
    clean.head().appendElement("title").text("패배 전투 로그 보관본")
    clean.head().appendElement("style").text(if (detailed) BATTLE_DETAIL_STYLE else
        "body{color:#111;background:#fff;font-family:sans-serif;line-height:1.6;padding:16px;overflow-wrap:anywhere}table{border-collapse:collapse;max-width:100%}td,th{border:1px solid #ccc;padding:4px}")
    if (detailed) {
        clean.body().html("<div id=\"battle-archive\">${clean.body().html()}</div>")
    } else {
        clean.body().prependElement("p").text("패배 시 받은 전투 응답 요약입니다. 상세 로그 보관본이 아닙니다. 3회 전투는 전체 응답을 포함합니다.")
    }
    return "<!doctype html>\n" + clean.outerHtml()
}

private fun battleImageUrl(value: String): String? = runCatching {
    val uri = URI("https://hof.zerosic.com/").resolve(value.trim().replace(" ", "%20")).normalize()
    if (uri.scheme != "https" || uri.host != "hof.zerosic.com" || uri.port != -1 ||
        uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
        !uri.path.startsWith("/image/") || uri.path.split('/').any { it == ".." } ||
        !Regex("(?i).+\\.(gif|png|jpe?g|webp)").matches(uri.path)) return null
    uri.toASCIIString()
}.getOrNull()

private fun battleStyle(style: String): String = style.split(';').mapNotNull { declaration ->
    val parts = declaration.split(':', limit = 2)
    if (parts.size != 2) return@mapNotNull null
    val property = parts[0].trim().lowercase()
    var value = parts[1].trim()
    if (property == "background-image") {
        val path = Regex("url\\((.*?)\\)", RegexOption.IGNORE_CASE).matchEntire(value)
            ?.groupValues?.get(1)?.trim()?.trim('\'', '"') ?: return@mapNotNull null
        val url = battleImageUrl(path) ?: return@mapNotNull null
        return@mapNotNull "$property:url(\"$url\")"
    }
    if (property !in BATTLE_STYLE_PROPERTIES || !Regex("[a-zA-Z0-9#%.,'\" \\-]+").matches(value)) return@mapNotNull null
    if (property == "display" && value !in setOf("block", "inline", "inline-block")) return@mapNotNull null
    // 원본 HOF의 단위 없는 sprite 크기를 표준 CSS로 바꾼다.
    if (property in setOf("width", "height") && value.all { it.isDigit() }) value += "px"
    "$property:$value"
}.joinToString(";")

private val BATTLE_STYLE_PROPERTIES = setOf(
    "width", "height", "max-width", "min-width", "position", "left", "right", "top", "bottom",
    "margin", "margin-left", "margin-right", "margin-top", "margin-bottom", "padding", "padding-left",
    "padding-right", "padding-top", "padding-bottom", "text-align", "vertical-align", "font-size",
    "font-weight", "font-family", "color", "background-color", "background-repeat", "background-position",
    "letter-spacing", "line-height", "display", "float", "clear",
)

// HOF basis.css/style.css의 전투 표시 규칙을 내장하여 외부 CSS 변경·만료에 의존하지 않는다.
private val BATTLE_DETAIL_STYLE = """
    *{padding:0;margin:0;line-height:140%;font-family:Arial,sans-serif;font-size:14px}
    body{background:#98a0a5;color:#bdc8d7;text-align:center}
    #battle-archive{width:810px;min-height:100vh;background:#10151b;text-align:left;margin:0 auto;padding-bottom:14em}
    table{border-spacing:0}td,th{vertical-align:middle}img{border:0}
    p{margin-bottom:1em}a{color:#8a9cb7;font-weight:bold;text-decoration:none}
    .align-right{text-align:right}.align-center{text-align:center}.bold{font-weight:bold}.u{text-decoration:underline}
    .vcent{vertical-align:middle;margin:0 5px}.c-both,.clear{clear:both}
    .btl_img{background:#070b0e;padding:3px}.teams{width:50%;text-align:center;padding:10px}
    .break{border-bottom:1px solid #070b0e}.break-top{border-top:1px solid #070b0e}
    .hpsp{padding-left:20px}.ttd1{border-left:1px solid #070b0e;padding-left:10px}.ttd2{padding-left:10px}
    .bl{width:50%;float:left;border-right:1px solid #070b0e}.br{width:50%;float:right}
    .real{color:#ff3399}.dmg{color:#cc3300}.recover{color:#3366ff}.support{color:#66cc66}
    .spdmg{color:#993399}.thornsdmg{color:#dddd33}.levelup{font-weight:bold;color:#ffff33}.charge{color:#ffcc33}
""".trimIndent()
