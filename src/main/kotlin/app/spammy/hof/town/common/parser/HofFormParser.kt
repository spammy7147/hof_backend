package app.spammy.hof.town.common.parser

import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofFormField
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
        val actionIdCounts = parsed.groupingBy(ParsedTownForm::actionId).eachCount()
        val unique = parsed.mapIndexed { index, form ->
            if (actionIdCounts[form.actionId] == 1) {
                form
            } else {
                form.copy(actionId = sha256("${form.actionId}|dom-tie:$index"))
            }
        }
        return ParsedTownPage(unique)
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
        val hiddenFieldPositions = ownedControls.mapIndexedNotNull { index, control ->
            index.takeIf {
                control.tagName() == "input" &&
                    control.attr("type").equals("hidden", ignoreCase = true) &&
                    control.attr("name").isNotBlank()
            }
        }
        val submitControls = ownedControls.filter(::isSubmitControl)
        val submitVariants = submitControls.mapNotNull { control ->
            namedValue(control)?.let { field -> listOf(field) to listOf(ownedControls.indexOf(control)) }
        }.ifEmpty { listOf(emptyList<HofFormField>() to emptyList()) }
        val rows = parseRows(form, ownedControls)

        return submitVariants.map { (submitFields, submitFieldPositions) ->
            val fingerprint = buildString {
                append(method.name).append('|').append(actionUrl).append('|')
                append(canonicalFields(submitFields))
                append('|')
                append(ownedControls.map { it.attr("name") }.filter(String::isNotBlank).distinct().sorted().joinToString(","))
                append('|')
                append(canonicalFields(hiddenFields))
                append('|')
                append(canonicalFields(rows.mapNotNull(ParsedTownRow::candidate).map { candidate ->
                    HofFormField(candidate.inputName, candidate.inputValue)
                }))
            }
            ParsedTownForm(
                actionId = sha256(fingerprint),
                method = method,
                actionUrl = actionUrl,
                rows = rows,
                hiddenFields = hiddenFields,
                submitFields = submitFields,
                hiddenFieldPositions = hiddenFieldPositions,
                submitFieldPositions = submitFieldPositions,
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
        controls.filter { it.tagName() == "select" && it.attr("name").isNotBlank() }
            .forEach { select ->
                val selectCount = controls.count { it.tagName() == "select" && it.attr("name").isNotBlank() }
                val quantityControl = controls.singleOrNull { control ->
                    control.tagName() == "input" && control.attr("name").isNotBlank() &&
                        (control.isQuantityControl() || (selectCount >= 4 && control.isPlainScalarInput()))
                }
                select.select("option").filter { it.hasAttr("value") && it.attr("value").isNotBlank() }
                    .forEach { option ->
                        val label = cleanText(option.text()).ifBlank { option.attr("value") }
                        rows += ParsedTownRow(
                            label = label,
                            candidate = ParsedTownCandidate(
                                id = "${select.attr("name")}:${option.attr("value")}",
                                label = label,
                                inputName = select.attr("name"),
                                inputValue = option.attr("value"),
                                quantityFieldName = quantityControl?.attr("name")?.takeIf {
                                    select === controls.firstOrNull { control -> control.tagName() == "select" }
                                },
                                minQuantity = quantityControl?.attr("min")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                                maxQuantity = quantityControl?.attr("max")?.toIntOrNull(),
                                selectionType = TownSelectionType.SELECT,
                                inputPosition = controls.indexOf(select),
                                quantityPosition = quantityControl?.let(controls::indexOf),
                            ),
                        )
                    }
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

        val selectionContainer = selection.quantityContainer()
        val quantityControl = controls.firstOrNull { control ->
            control !== selection && selectionContainer != null && control.quantityContainer() === selectionContainer &&
                control.tagName() == "input" &&
                control.isQuantityControl() &&
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
                inputPosition = controls.indexOf(selection),
                quantityPosition = quantityControl?.let(controls::indexOf),
            ),
        )
    }

    private fun Element.candidateId(valueCounts: Map<String, Int>): String {
        val value = attr("value").trim().ifBlank { "on" }
        return id().trim().ifBlank {
            if (valueCounts[value] == 1) value else "${attr("name")}:$value"
        }
    }

    private fun namedValue(element: Element): HofFormField? {
        val name = element.attr("name").trim()
        if (name.isBlank()) return null
        val value = when {
            element.tagName() == "button" -> element.attr("value").ifBlank { element.text() }
            else -> element.attr("value")
        }
        return HofFormField(name, value)
    }

    private fun Element.quantityContainer(): Element? = closest("tr") ?: closest("li") ?: parent()

    private fun Element.isQuantityControl(): Boolean {
        val type = attr("type").lowercase()
        return type == "number" || (type in TEXT_INPUT_TYPES && QUANTITY_NAME.matches(attr("name")))
    }

    private fun Element.isPlainScalarInput(): Boolean = attr("type").lowercase() in (TEXT_INPUT_TYPES + "number")

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

    private fun canonicalFields(fields: List<HofFormField>): String = fields
        .sortedWith(compareBy(HofFormField::name, HofFormField::value))
        .joinToString("|") { field ->
            "${field.name.length}:${field.name}${field.value.length}:${field.value}"
        }

    private fun cleanText(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val HOF_BASE_URL = "http://sic.zerosic.com/ZeroHOF/index.php"
        val SELECTION_TYPES = setOf("radio", "checkbox")
        val TEXT_INPUT_TYPES = setOf("", "text", "tel")
        val QUANTITY_NAME = Regex("^(qty|quantity|count|amount|num|number|many|suu)(_|\\[|$).*", RegexOption.IGNORE_CASE)
    }
}
