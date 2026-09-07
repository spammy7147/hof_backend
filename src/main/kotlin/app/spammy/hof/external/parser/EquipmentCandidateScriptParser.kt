package app.spammy.hof.external.parser

import app.spammy.hof.external.model.HofEquipmentCandidate
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** JavaScript가 DOM에 삽입하는 장비 후보 HTML을 실행하지 않고 안전하게 추출한다. */
object EquipmentCandidateScriptParser {
    private val caseRegex = Regex("""case\s+["']([^"']+)["']\s*:""")
    private val quantityRegex = Regex("""(?:^|\s)x\s*(\d+)(?=\s|/|$)""", RegexOption.IGNORE_CASE)

    /** `equip_item` form에 동적으로 삽입되는 `Listtype_equip` 장비 카탈로그만 반환한다. */
    fun parseEquipmentCatalog(document: Document): List<HofEquipmentCandidate> = buildList {
        document.select("script").filter { it.data().contains("Listtype_equip") }.forEach { script ->
            var typeCode = ""
            script.data().lineSequence().forEach { line ->
                caseRegex.find(line)?.groupValues?.get(1)?.let { typeCode = it }
                val start = line.indexOf("<input type=\"radio\" name=\"item_no\"")
                if (start < 0) return@forEach
                val end = line.indexOf("<br />", start).takeIf { it >= 0 } ?: line.length
                val fragment = line.substring(start, end)
                val body = Jsoup.parseBodyFragment(fragment, document.baseUri()).body()
                val input = body.selectFirst("input[name=item_no]") ?: return@forEach
                val text = body.text().trim()
                add(
                    HofEquipmentCandidate(
                        value = input.attr("value").trim(),
                        typeCode = typeCode,
                        name = readName(body),
                        iconUrl = body.selectFirst("img")?.absUrl("src").orEmpty(),
                        description = text,
                        quantity = quantityRegex.find(text)?.groupValues?.get(1)?.toIntOrNull(),
                    ),
                )
            }
        }
    }.distinctBy { it.typeCode to it.value }

    fun parse(document: Document): List<HofEquipmentCandidate> = buildList {
        addAll(parseEquipmentCatalog(document))
        // 사용 가능 아이템은 JavaScript stock 목록이 아니라 실제 form DOM에만 있는 페이지가 있다.
        document.select("form").filter { form ->
            form.select("input[type=submit][name=use_char_item]").isNotEmpty()
        }.forEach { form ->
            form.select("input[name=item_no]").forEach { input ->
                val parent = input.parent() ?: return@forEach
                val nodes = parent.childNodes()
                val start = nodes.indexOf(input)
                if (start < 0) return@forEach
                val fragment = buildString {
                    for (index in start until nodes.size) {
                        val node = nodes[index]
                        if (index > start && node.nodeName().equals("br", true)) break
                        append(node.outerHtml())
                    }
                }
                parseFragment(document, fragment, "characteritem")?.let(::add)
            }
        }
        document.select("form:has(input[type=submit][name=resetVarious]) select[name=itemUse] option")
            .forEach { option ->
                val text = option.text().trim()
                add(
                    HofEquipmentCandidate(
                        value = option.attr("value").trim(),
                        typeCode = "resetitem",
                        name = text.replace(quantityRegex, "").trim(),
                        description = text,
                        quantity = quantityRegex.find(text)?.groupValues?.get(1)?.toIntOrNull(),
                    ),
                )
            }
    }.distinctBy { it.typeCode to it.value }

    private fun parseFragment(document: Document, fragment: String, typeCode: String): HofEquipmentCandidate? {
        val body = Jsoup.parseBodyFragment(fragment, document.baseUri()).body()
        val input = body.selectFirst("input[name=item_no]") ?: return null
        val text = body.text().trim()
        return HofEquipmentCandidate(
            value = input.attr("value").trim(),
            typeCode = typeCode,
            name = readName(body),
            iconUrl = body.selectFirst("img")?.absUrl("src").orEmpty(),
            description = text,
            quantity = quantityRegex.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    private fun readName(body: Element): String {
        // 괄호는 카드 이름에도 쓰이므로 실제 장비 분류 표시 앞까지만 이름으로 읽는다.
        val classification = body.selectFirst("span.light")
        if (classification != null) {
            return generateSequence(classification.previousSibling()) { it.previousSibling() }
                .toList().asReversed().joinToString("") { node ->
                    when (node) {
                        is TextNode -> node.text()
                        is Element -> node.text()
                        else -> ""
                    }
                }.trim()
        }
        return body.text().substringBefore(" (").substringBefore(" x").trim()
    }
}
