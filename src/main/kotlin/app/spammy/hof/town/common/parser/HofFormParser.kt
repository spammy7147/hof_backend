package app.spammy.hof.town.common.parser

import app.spammy.hof.external.parser.HofHtmlParser

import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofFormField
import app.spammy.hof.town.common.model.ParsedTownCandidate
import app.spammy.hof.town.common.model.ParsedTownEditableField
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownRow
import app.spammy.hof.town.common.model.TownSelectionType
import app.spammy.hof.town.common.model.TownEditableFieldType
import java.net.URI
import java.security.MessageDigest
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
class HofFormParser {
    fun parse(html: String, pageUrl: String = HOF_BASE_URL): ParsedTownPage {
        val document = HofHtmlParser.parse(html, pageUrl)
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
        val submitVariants = submitControls.map { control ->
            val field = namedValue(control)
            SubmitVariant(
                fields = listOfNotNull(field),
                positions = if (field == null) emptyList() else listOf(ownedControls.indexOf(control)),
                label = submitLabel(control),
                source = field?.name ?: "unnamed-submit",
                controlPosition = ownedControls.indexOf(control),
            )
        }.ifEmpty { listOf(SubmitVariant()) }
        val rows = parseRows(form, ownedControls)
        val editableFields = ownedControls.mapIndexedNotNull { index, control ->
            control.toEditableField(index)
        }

        return submitVariants.map { variant ->
            val submitFields = variant.fields
            val submitFieldPositions = variant.positions
            val fingerprint = buildString {
                append(method.name).append('|').append(actionUrl).append('|')
                append(canonicalFields(submitFields))
                append('|').append(variant.source).append(':').append(variant.label).append('@').append(variant.controlPosition)
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
                submitLabel = variant.label,
                submitSource = variant.source,
                hiddenFieldPositions = hiddenFieldPositions,
                submitFieldPositions = submitFieldPositions,
                editableFields = editableFields,
            )
        }
    }

    private fun Element.toEditableField(position: Int): ParsedTownEditableField? {
        if (tagName() != "input" && tagName() != "textarea") return null
        val type = attr("type").lowercase()
        if (tagName() == "input" && type !in setOf("", "text", "number", "tel")) return null
        val name = attr("name").trim()
        if (name.isBlank()) return null
        val fieldType = if (type in setOf("number", "tel")) TownEditableFieldType.NUMBER else TownEditableFieldType.TEXT
        val label = closest("label")?.text()?.let(::cleanText)?.takeIf { it.isNotBlank() }
            ?: closest("tr")?.selectFirst("th, td")?.text()?.let(::cleanText)?.takeIf { it.isNotBlank() }
            ?: attr("placeholder").trim().takeIf { it.isNotBlank() }
            ?: name
        return ParsedTownEditableField(
            id = sha256("$name|$position"),
            label = label.ifBlank { name },
            inputName = name,
            value = if (tagName() == "textarea") text() else attr("value"),
            inputType = fieldType,
            maxLength = attr("maxlength").toIntOrNull()?.takeIf { it > 0 },
            inputPosition = position,
        )
    }

    private fun parseRows(form: Element, controls: List<Element>): List<ParsedTownRow> {
        val selectable = controls.filter { control ->
            control.tagName() == "input" &&
                control.attr("type").lowercase() in SELECTION_TYPES &&
                control.attr("name").isNotBlank()
        }
        val valueCounts = selectable.groupingBy { it.attr("value").trim().ifBlank { "on" } }.eachCount()
        val rowElements = form.select("tr").filter { it.closest("form") === form }
        val rowControls = rowElements.associateWith { row ->
            selectable.filter { it.closest("tr") === row }.singleOrNull()
        }
        val rows = rowElements.map { row ->
            val control = rowControls[row]
            row.toParsedRow(control, controls, control?.candidateId(valueCounts))
        }.toMutableList()

        // 한 tr 안에 카드형 td가 여러 개인 form은 tr 전체를 한 후보의 label로 오인하지 않는다.
        // 각 radio를 자신이 속한 td/label의 별도 row로 만든다.
        selectable.filter { candidate -> candidate !in rowControls.values }
            .forEach { control ->
                val owner = sequenceOf("td", "li", "label", "div", "p")
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
                val quantityControl = controls.singleOrNull { control ->
                    control.tagName() == "input" && control.attr("name").isNotBlank() &&
                        control.isQuantityControl()
                }
                select.select("option").filter { option ->
                    option.hasAttr("value") && option.attr("value").isNotBlank() && !option.hasAttr("disabled") &&
                        option.parents().none { parent -> parent.tagName() == "optgroup" && parent.hasAttr("disabled") }
                }
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
                                selected = option.hasAttr("selected") ||
                                    (select.selectFirst("option[selected]") == null && option === select.selectFirst("option")),
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
                selected = selection.hasAttr("checked"),
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

    private fun submitLabel(element: Element): String = cleanText(
        element.attr("value").ifBlank { element.text() },
    ).ifBlank { "실행" }

    private fun Element.quantityContainer(): Element? = closest("tr") ?: closest("li") ?: parent()

    private fun Element.isQuantityControl(): Boolean {
        val type = attr("type").lowercase()
        return type == "number" || (type in TEXT_INPUT_TYPES && QUANTITY_NAME.matches(attr("name")))
    }

    private fun isSubmitControl(element: Element): Boolean = when (element.tagName()) {
        "button" -> element.attr("type").lowercase().let { it.isBlank() || it == "submit" }
        "input" -> element.attr("type").lowercase() in setOf("submit", "image")
        else -> false
    }

    private fun resolveAction(pageUrl: String, action: String): String {
        if (action.isBlank()) return pageUrl
        // URI.resolve("?menu=...")는 브라우저와 달리 마지막 path segment를 제거할 수 있다.
        // HOF의 query-only form action은 현재 index.php path를 그대로 보존해야 한다.
        if (action.startsWith("?")) return "${pageUrl.substringBefore('#').substringBefore('?')}$action"
        return URI(pageUrl).resolve(action).toString()
    }

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

    private data class SubmitVariant(
        val fields: List<HofFormField> = emptyList(),
        val positions: List<Int> = emptyList(),
        val label: String = "",
        val source: String = "form-submit",
        val controlPosition: Int = -1,
    )
}
