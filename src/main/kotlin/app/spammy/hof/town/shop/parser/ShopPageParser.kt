package app.spammy.hof.town.shop.parser

import app.spammy.hof.town.common.model.ParsedTownCandidate
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.TownSelectionType
import app.spammy.hof.town.shop.catalog.ParsedShopItem
import app.spammy.hof.town.shop.dto.CombineOptionResponse
import app.spammy.hof.town.shop.dto.CombineResponse
import app.spammy.hof.town.shop.dto.SellItemResponse
import app.spammy.hof.town.shop.dto.SellResponse
import org.springframework.stereotype.Component

@Component
class ShopPageParser {
    fun parseCatalog(page: ParsedTownPage): List<ParsedShopItem> = purchaseForm(page)?.rows.orEmpty()
        .mapNotNull { row ->
            val candidate = row.candidate ?: return@mapNotNull null
            val price = price(row.label) ?: return@mapNotNull null
            val name = itemName(row.label)
            ParsedShopItem(candidate.id, name, type(row.label), row.label.takeUnless { it == name }, price)
        }
        .distinctBy(ParsedShopItem::itemKey)

    fun purchaseForm(page: ParsedTownPage): ParsedTownForm? = page.forms
        .filter { it.candidates.isNotEmpty() }
        .maxByOrNull { form -> form.rows.count { price(it.label) != null } }

    fun parseSell(page: ParsedTownPage): SellResponse {
        val form = page.forms.filter { it.candidates.isNotEmpty() }
            .maxByOrNull { candidateForm -> candidateForm.candidates.count { it.selectionType == TownSelectionType.CHECKBOX } }
        return SellResponse(form?.rows.orEmpty().mapNotNull { row ->
            val candidate = row.candidate ?: return@mapNotNull null
            val unitPrice = price(row.label) ?: return@mapNotNull null
            SellItemResponse(
                id = candidate.id,
                label = itemName(row.label),
                selectable = true,
                detail = row.label,
                price = unitPrice,
                quantity = quantity(row.label),
                type = type(row.label),
            )
        })
    }

    fun sellForm(page: ParsedTownPage, candidateIds: Set<String>): ParsedTownForm? = page.forms.singleOrNull { form ->
        candidateIds.isNotEmpty() && candidateIds.all { id -> form.candidates.any { it.id == id } }
    }

    fun parseCombine(page: ParsedTownPage): CombineResponse {
        val form = combineForm(page) ?: return CombineResponse(emptyList(), List(3) { emptyList() })
        val groups = form.candidates.filter { it.selectionType == TownSelectionType.SELECT }.groupBy(ParsedTownCandidate::inputName)
            .values.toList()
        return CombineResponse(
            primary = groups.firstOrNull().orEmpty().map(::option),
            secondarySlots = (0 until 3).map { index -> groups.getOrNull(index + 1).orEmpty().map(::option) },
        )
    }

    fun combineForm(page: ParsedTownPage): ParsedTownForm? = page.forms.firstOrNull { form ->
        form.candidates.filter { it.selectionType == TownSelectionType.SELECT }.map(ParsedTownCandidate::inputName).distinct().size >= 4
    }

    private fun option(candidate: ParsedTownCandidate) = CombineOptionResponse(candidate.id, candidate.label, quantity(candidate.label))
    private fun price(text: String): Long? = PRICE.find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
    private fun quantity(text: String): Int? = QUANTITY.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
    private fun type(text: String): String? = TYPE.find(text)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank)
    private fun itemName(text: String): String = text
        .replace(PRICE, "")
        .substringBefore(" /").substringBefore("(").trim().ifBlank { text.take(120) }

    private companion object {
        val PRICE = Regex("[$￦]\\s*([0-9][0-9,]*)")
        val QUANTITY = Regex("(?:x|×|보유\\s*)\\s*([0-9][0-9,]*)", RegexOption.IGNORE_CASE)
        val TYPE = Regex("\\((weapon|armor|cloak|shoes|item|accessory|jobitem|head|avatar|skillseal|char|useitem|addmaterial|housing[^)]*|other)\\)", RegexOption.IGNORE_CASE)
    }
}
