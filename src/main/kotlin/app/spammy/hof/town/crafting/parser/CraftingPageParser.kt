package app.spammy.hof.town.crafting.parser

import app.spammy.hof.town.common.model.*
import app.spammy.hof.town.crafting.model.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
class CraftingPageParser {
    fun parse(
        mode: CraftingMode,
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        result: ParsedTownResult? = null,
        warningCode: String? = null,
    ): CraftingSnapshot {
        val document = Jsoup.parse(html, finalUrl)
        val contract = contract(mode)
        val actionForms = page.forms.filter { form ->
            form.submitFields.singleOrNull()?.name == contract.submit &&
                form.candidates.any { it.inputName == contract.categoryField }
        }
        val actionForm = actionForms.singleOrNull()
        val completionForm = page.forms.singleOrNull { it.submitFields.singleOrNull()?.name == "WSend" }
        val domForm = actionForm?.let { findDomForm(document, it, contract) }
        val itemTByCandidate = if (mode in ITEM_T_MODES && actionForm != null && domForm != null) {
            strictItemTByCandidate(actionForm, domForm, contract.itemField)
        } else emptyMap()
        val categories = parseCategories(actionForm, domForm, contract.categoryField)
        val categoryCandidateId = categories.firstOrNull(CraftingCategory::current)?.id
        val itemRows = actionForm?.rows.orEmpty().filter { row ->
            row.candidate == null || row.candidate.inputName == contract.itemField
        }.mapIndexedNotNull { index, row ->
            val label = clean(row.label)
            if (label.isBlank() || HEADER.matches(label)) return@mapIndexedNotNull null
            val candidate = row.candidate
            val itemT = candidate?.let(itemTByCandidate::get)
            val selectable = candidate != null && (mode !in ITEM_T_MODES || itemT != null)
            CraftingRow(
                id = candidate?.id ?: "display-$index",
                label = label.replace(LEADING_PRICE, "").trim(),
                selectable = selectable,
                detail = label,
                cost = PRICE.find(label)?.groupValues?.get(1)?.number(),
                owned = OWNED.find(label)?.groupValues?.get(1)?.intNumber(),
                workSeconds = WORK_SECONDS.find(label)?.groupValues?.get(1)?.intNumber(),
                itemT = itemT,
            )
        }.distinctBy(CraftingRow::id)
        val quantity = parseQuantity(domForm, mode)
        val refineOptions = parseRefineOptions(actionForm, domForm)
        val additional = parseAdditionalMaterials(actionForm, domForm, mode)
        val remaining = REMAINING_SECONDS.find(clean(document.text()))?.groupValues?.get(1)?.intNumber()
        val activeText = ACTIVE_JOB.find(clean(document.text()))?.value?.let(::clean)
        val activeJob = if (mode == CraftingMode.WORKBASE && (remaining != null || activeText != null || completionForm != null)) {
            ActiveCraftingJob(activeText ?: if (remaining == null) "제작 결과 확인 가능" else "현재 장비를 제작 중입니다.", remaining, completionForm != null)
        } else null
        return CraftingSnapshot(
            mode = mode,
            categories = categories,
            currentCategoryId = categoryCandidateId,
            rows = itemRows,
            minQuantity = quantity.first,
            maxQuantity = quantity.second,
            activeJob = activeJob,
            allowedRefineCounts = refineOptions.second.keys.sorted(),
            additionalMaterials = additional,
            additionalMaterialsOptional = mode == CraftingMode.CREATE,
            warningCode = warningCode,
            history = parseHistory(document),
            result = result,
            actionId = actionForm?.actionId,
            completionActionId = completionForm?.actionId,
            categoryCandidateId = categoryCandidateId,
            refineCountCandidateIds = refineOptions.second,
            timesADefaultCandidateId = refineOptions.first,
        )
    }

    private fun strictItemTByCandidate(form: ParsedTownForm, domForm: Element, itemField: String): Map<ParsedTownCandidate, String> =
        form.candidates.filter { it.inputName == itemField }.mapNotNull { candidate ->
            val controls = domForm.select("input[name=${cssValue(candidate.inputName)}]").filter {
                it.closest("form") === domForm &&
                it.attr("value").trim().ifBlank { "on" } == candidate.inputValue
            }
            val control = controls.singleOrNull() ?: return@mapNotNull null
            val sources = listOf(control.attr("onclick"), control.closest("tr")?.attr("onclick").orEmpty())
            val assignments = sources.flatMap { source ->
                ITEM_T_ASSIGNMENT.findAll(source).map { it.groupValues[3] }.toList()
            }.distinct()
            candidate to (assignments.singleOrNull() ?: return@mapNotNull null)
        }.toMap()

    private fun parseCategories(form: ParsedTownForm?, domForm: Element?, field: String): List<CraftingCategory> {
        if (form == null || domForm == null) return emptyList()
        val select = domForm.select("select[name=${cssValue(field)}]").filter { it.closest("form") === domForm }.singleOrNull()
            ?: return emptyList()
        return select.select("option[value]").filterNot { it.hasAttr("disabled") }.mapNotNull { option ->
            val candidate = form.candidates.singleOrNull { it.inputName == field && it.inputValue == option.attr("value") }
                ?: return@mapNotNull null
            CraftingCategory(candidate.id, clean(option.text()), option.hasAttr("selected") || select.`val`() == option.attr("value"))
        }
    }

    /** timesA는 최신 GET의 selected option만 보존하고, 사용자 선택 목록은 timesB에서만 만든다. */
    private fun parseRefineOptions(form: ParsedTownForm?, domForm: Element?): Pair<String?, Map<Int, String>> {
        if (form == null || domForm == null) return null to emptyMap()
        fun candidate(field: String, value: String) = form.candidates.singleOrNull { it.inputName == field && it.inputValue == value }?.id
        val timesA = domForm.select("select[name=timesA]").filter { it.closest("form") === domForm }.singleOrNull()?.let { select ->
            val selected = select.select("option[selected]").singleOrNull() ?: select.select("option[value=${cssValue(select.`val`())}]").singleOrNull()
            selected?.attr("value")?.let { candidate("timesA", it) }
        }
        val timesBSelect = domForm.select("select[name=timesB]").filter { it.closest("form") === domForm }.singleOrNull()
            ?: return timesA to emptyMap()
        val counts = timesBSelect.select("option[value]").filterNot { it.hasAttr("disabled") }.mapNotNull { option ->
            val count = option.attr("value").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val id = candidate("timesB", option.attr("value")) ?: return@mapNotNull null
            count to id
        }
        return timesA to counts.toMap().takeIf { it.size == counts.size }.orEmpty()
    }

    private fun parseAdditionalMaterials(form: ParsedTownForm?, domForm: Element?, mode: CraftingMode): List<AdditionalMaterial> {
        if (mode != CraftingMode.CREATE || form == null || domForm == null) return emptyList()
        return form.candidates.filter { it.inputName == "AddMaterial" }.mapNotNull { candidate ->
            val input = domForm.select("input[name=AddMaterial]").filter {
                it.closest("form") === domForm && it.attr("value").trim().ifBlank { "on" } == candidate.inputValue
            }.singleOrNull() ?: return@mapNotNull null
            val fragments = mutableListOf<String>()
            var sibling = input.nextSibling()
            while (sibling != null) {
                if (sibling is Element && sibling.tagName() in setOf("br", "input")) break
                fragments += sibling.outerHtml()
                sibling = sibling.nextSibling()
            }
            val label = clean(Jsoup.parseBodyFragment(fragments.joinToString("")).text())
            if (label.isBlank()) return@mapNotNull null
            AdditionalMaterial(
                id = candidate.id,
                label = label,
                selectable = true,
                owned = OWNED.find(label)?.groupValues?.get(1)?.intNumber(),
                detail = label,
            )
        }.distinctBy(AdditionalMaterial::id)
    }

    private fun parseQuantity(form: Element?, mode: CraftingMode): Pair<Int, Int> {
        val input = form?.select("input[name=amount]")?.filter { it.closest("form") === form }?.singleOrNull()
        val min = input?.attr("min")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val fallback = when (mode) { CraftingMode.WORKBASE -> 10; CraftingMode.CREATE -> 100; else -> Int.MAX_VALUE }
        val max = input?.attr("max")?.toIntOrNull()
            ?: form?.text()?.let { MAX_QUANTITY.find(it)?.groupValues?.get(1)?.intNumber() }
            ?: fallback
        return min to max.coerceAtLeast(min)
    }

    private fun parseHistory(document: org.jsoup.nodes.Document): List<String> {
        val elements = document.select("body *")
        val markerIndex = elements.indexOfFirst { HALL_OF_PAIN.containsMatchIn(clean(it.ownText())) }
        if (markerIndex < 0) return emptyList()
        return elements.drop(markerIndex + 1)
            .takeWhile { !FOOTER_WORD.containsMatchIn(clean(it.ownText())) }
            .map { clean(it.ownText()) }
            .filter { it.isNotBlank() && HISTORY_WORD.containsMatchIn(it) }
            .distinct().takeLast(50)
    }

    private fun findDomForm(document: org.jsoup.nodes.Document, form: ParsedTownForm, contract: Contract): Element? = document.select("form").filter { dom ->
        val categoryControls = dom.select("select[name=${cssValue(contract.categoryField)}]").filter { it.closest("form") === dom }
        val submitControls = dom.select("input[name=${cssValue(contract.submit)}],button[name=${cssValue(contract.submit)}]")
            .filter { it.closest("form") === dom }
        categoryControls.size == 1 && submitControls.size == 1 &&
            dom.attr("action").let { action -> action.isBlank() || form.actionUrl.endsWith(action.substringAfterLast('/')) || form.actionUrl.contains(action) }
    }.singleOrNull()

    private fun contract(mode: CraftingMode) = when (mode) {
        CraftingMode.WORKBASE -> Contract("ItemNo", "type_create", "Create")
        CraftingMode.CLARIS -> Contract("ItemNo", "type_create", "Create")
        CraftingMode.REFINE -> Contract("item_no", "type", "refine")
        CraftingMode.CREATE -> Contract("ItemNo", "type_create", "Create")
        CraftingMode.VETERAN -> Contract("item_no", "type", "refine")
    }

    private fun clean(value: String) = value.replace(Regex("\\s+"), " ").trim()
    private fun String.number() = replace(",", "").toLongOrNull()
    private fun String.intNumber() = number()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
    private fun cssValue(value: String) = "'${value.replace("'", "\\'")}'"
    private data class Contract(val itemField: String, val categoryField: String, val submit: String)

    private companion object {
        val ITEM_T_MODES = setOf(CraftingMode.WORKBASE, CraftingMode.CLARIS, CraftingMode.CREATE)
        val ITEM_T_ASSIGNMENT = Regex("(?:document\\.getElementById\\(\\s*(['\"])ItemT\\1\\s*\\)|(?:document\\.)?ItemT)\\s*\\.value\\s*=\\s*(['\"]?)([A-Za-z0-9_.:-]+)\\2", RegexOption.IGNORE_CASE)
        val PRICE = Regex("[$]\\s*([\\d,]+)")
        val LEADING_PRICE = Regex("^[$]\\s*[\\d,]+\\s*")
        val OWNED = Regex("[x×]\\s*([\\d,]+)", RegexOption.IGNORE_CASE)
        val WORK_SECONDS = Regex("제작\\s*시간\\s*:\\s*([\\d,]+)")
        val REMAINING_SECONDS = Regex("결과는\\s*([\\d,]+)초\\s*후")
        val ACTIVE_JOB = Regex("현재\\s+장비를\\s+제작\\s+중입니다[^.]*\\.")
        val MAX_QUANTITY = Regex("최대\\s*([\\d,]+)개")
        val HISTORY_WORD = Regex("제련|성공|실패|파괴")
        val HALL_OF_PAIN = Regex("Hall\\s+of\\s+Pain", RegexOption.IGNORE_CASE)
        val FOOTER_WORD = Regex("Copy\\s*Right|UpDate\\s+Manual|GameData\\s+Top", RegexOption.IGNORE_CASE)
        val HEADER = Regex("^(제작비|제작비 Item|Item|아이템|수수료)(?:\\s+(Item|아이템))?$", RegexOption.IGNORE_CASE)
    }
}
