package app.spammy.hof.town.common.parser

import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.town.common.model.ParsedTownCandidate
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownRow
import app.spammy.hof.town.common.model.TownSelectionType
import java.net.URI
import java.security.MessageDigest
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
class HofFormParser {
    fun parse(html: String, pageUrl: String = HOF_BASE_URL): ParsedTownPage {
        val document = Jsoup.parse(html, pageUrl)
        val parsed = document.select("form").flatMap { form -> parseForm(form, pageUrl) }
        return ParsedTownPage(parsed)
    }

    private fun parseForm(form: Element, pageUrl: String): List<ParsedTownForm> {
        val method = if (form.attr("method").equals("post", ignoreCase = true)) {
            HofHttpMethod.POST
        } else {
            HofHttpMethod.GET
        }
        val actionUrl = resolveAction(pageUrl, form.attr("action"))
        val ownedControls = form.select("input, button, select, textarea")
            .filter { it.closest("form") === form && !it.hasAttr("disabled") }
        val hiddenFields = ownedControls
            .filter { it.tagName() == "input" && it.attr("type").equals("hidden", ignoreCase = true) }
            .mapNotNull(::namedValue)
            .toMap()
        val submitControls = ownedControls.filter(::isSubmitControl)
        val submitVariants = submitControls.mapNotNull(::namedValue).map(::mapOf).ifEmpty { listOf(emptyMap()) }
        val rows = parseRows(form, ownedControls)

        return submitVariants.map { submitFields ->
            val fingerprint = buildString {
                append(method.name).append('|').append(actionUrl).append('|')
                append(submitFields.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" })
                append('|')
                append(ownedControls.map { it.attr("name") }.filter(String::isNotBlank).distinct().sorted().joinToString(","))
            }
            ParsedTownForm(
                actionId = sha256(fingerprint),
                method = method,
                actionUrl = actionUrl,
                rows = rows,
                hiddenFields = hiddenFields,
                submitFields = submitFields,
            )
        }
    }

    private fun parseRows(form: Element, controls: List<Element>): List<ParsedTownRow> {
        val selectable = controls.filter { control ->
            control.tagName() == "input" &&
                control.attr("type").lowercase() in SELECTION_TYPES &&
                control.attr("name").isNotBlank()
        }
        val valueCounts = selectable.groupingBy { it.attr("value").trim().ifBlank { "on" } }.eachCount()
        val rowElements = form.select("tr").filter { it.closest("form") === form }
        val rows = rowElements.map { row ->
            val control = selectable.firstOrNull { it.closest("tr") === row }
            row.toParsedRow(control, controls, control?.candidateId(valueCounts))
        }.toMutableList()

        selectable.filter { candidate -> rowElements.none { candidate.closest("tr") === it } }
            .forEach { control ->
                val owner = sequenceOf("li", "label", "div", "p")
                    .mapNotNull(control::closest)
                    .firstOrNull { it.closest("form") === form }
                rows += (owner ?: control.parent() ?: control).toParsedRow(
                    control,
                    controls,
                    control.candidateId(valueCounts),
                )
            }
        return rows
    }

    private fun Element.toParsedRow(
        selection: Element?,
        controls: List<Element>,
        candidateId: String?,
    ): ParsedTownRow {
        val label = cleanText(text()).ifBlank {
            selection?.attr("value")?.trim().orEmpty().ifBlank { "이름 없는 항목" }
        }
        if (selection == null) return ParsedTownRow(label)

        val quantityControl = controls.firstOrNull { control ->
            control !== selection && control.closest("tr") === selection.closest("tr") &&
                control.tagName() == "input" &&
                control.attr("type").lowercase() in QUANTITY_TYPES &&
                control.attr("name").isNotBlank()
        }
        val value = selection.attr("value").trim().ifBlank { "on" }
        return ParsedTownRow(
            label = label,
            candidate = ParsedTownCandidate(
                id = requireNotNull(candidateId),
                label = label,
                inputName = selection.attr("name"),
                inputValue = value,
                quantityFieldName = quantityControl?.attr("name"),
                minQuantity = quantityControl?.attr("min")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                maxQuantity = quantityControl?.attr("max")?.toIntOrNull(),
                selectionType = if (selection.attr("type").equals("checkbox", true)) {
                    TownSelectionType.CHECKBOX
                } else {
                    TownSelectionType.RADIO
                },
            ),
        )
    }

    private fun Element.candidateId(valueCounts: Map<String, Int>): String {
        val value = attr("value").trim().ifBlank { "on" }
        return id().trim().ifBlank {
            if (valueCounts[value] == 1) value else "${attr("name")}:$value"
        }
    }

    private fun namedValue(element: Element): Pair<String, String>? {
        val name = element.attr("name").trim()
        if (name.isBlank()) return null
        val value = when {
            element.tagName() == "button" -> element.attr("value").ifBlank { element.text() }
            else -> element.attr("value")
        }
        return name to value
    }

    private fun isSubmitControl(element: Element): Boolean = when (element.tagName()) {
        "button" -> element.attr("type").lowercase().let { it.isBlank() || it == "submit" }
        "input" -> element.attr("type").lowercase() in setOf("submit", "image")
        else -> false
    }

    private fun resolveAction(pageUrl: String, action: String): String =
        URI(pageUrl).resolve(action.ifBlank { pageUrl }).toString()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun cleanText(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val HOF_BASE_URL = "http://sic.zerosic.com/ZeroHOF/index.php"
        val SELECTION_TYPES = setOf("radio", "checkbox")
        val QUANTITY_TYPES = setOf("number", "text")
    }
}
