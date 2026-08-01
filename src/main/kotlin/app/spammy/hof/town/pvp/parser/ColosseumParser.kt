package app.spammy.hof.town.pvp.parser

import app.spammy.hof.town.common.model.*
import app.spammy.hof.town.pvp.model.*
import java.net.URI
import java.security.MessageDigest
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
class ColosseumParser {
    fun parseBattle(html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): ColosseumBattleSnapshot {
        val doc = Jsoup.parse(html, finalUrl)
        val semanticTeamForms = page.forms.filter { form ->
            form.submitFields.singleOrNull()?.let { TEAM.matchesSemantic(it.name, it.value) } == true &&
                form.candidates.any { it.selectionType == TownSelectionType.CHECKBOX } &&
                form.candidates.none { it.selectionType == TownSelectionType.RADIO }
        }
        val teamForm = semanticTeamForms.singleOrNull() ?: page.forms.singleOrNull { form ->
            val checkboxes = form.candidates.filter { it.selectionType == TownSelectionType.CHECKBOX }
            checkboxes.isNotEmpty() && form.candidates.none { it.selectionType == TownSelectionType.RADIO } &&
                checkboxes.map { it.inputName }.distinct().singleOrNull()?.let(TEAM_FIELD::matches) == true
        }
        val challengeForms = page.forms.filter { form ->
            form.submitFields.singleOrNull()?.let { CHALLENGE.matchesSemantic(it.name, it.value) } == true &&
                form.candidates.none { it.selectionType == TownSelectionType.CHECKBOX }
        }
        val teamDom = teamForm?.let { findDomForm(doc, it) }
        val selectedValues = teamDom?.select("input[type=checkbox][checked]")?.map { it.attr("name") to it.attr("value").ifBlank { "on" } }?.toSet().orEmpty()
        val fighters = teamForm?.let { observedTeamForm -> observedTeamForm.candidates.filter { it.selectionType == TownSelectionType.CHECKBOX }.take(MAX_FIGHTERS).map { candidate ->
            val row = observedTeamForm.rows.single { it.candidate === candidate }
            val control = teamDom?.select("input[type=checkbox]")?.singleOrNull { it.attr("name") == candidate.inputName && it.attr("value").ifBlank { "on" } == candidate.inputValue }
            val container = control?.closest("td") ?: control?.parent()
            ColosseumFighter(candidate.id, clean(row.label).take(MAX_TEXT), clean(row.label).take(MAX_TEXT), container?.selectFirst("img")?.absUrl("src")?.ifBlank { null }?.take(MAX_URL), candidate.inputName to candidate.inputValue in selectedValues)
        } }.orEmpty()
        val selected = fighters.filter(ColosseumFighter::selected).map(ColosseumFighter::id)
        val max = teamDom?.select("input[type=checkbox]")?.firstOrNull()?.attr("data-max")?.toIntOrNull()
            ?: MAX_TEAM.find(clean(doc.text()))?.groupValues?.get(1)?.toIntOrNull() ?: if (teamForm == null) 0 else COLOSSEUM_TEAM_SIZE
        val stableIds = challengeForms.map { form -> form to stableOpponentId(form) }
        val uniqueIds = stableIds.mapNotNull { it.second }.groupingBy { it }.eachCount()
        val opponents = stableIds.mapIndexedNotNull { index, (form, stableId) ->
            stableId?.takeIf { uniqueIds[it] == 1 } ?: return@mapIndexedNotNull null
            val dom = findDomForm(doc, form)
            val context = clean((dom?.closest("tr") ?: dom)?.text().orEmpty()).take(MAX_TEXT)
            ColosseumOpponent(stableId, context.replace(CHALLENGE, "").trim().ifBlank { "도전자 ${index + 1}" }.take(MAX_TEXT), context)
        }.take(MAX_OPPONENTS)
        val actionIds = opponents.associate { opponent -> opponent.id to stableIds.single { it.second == opponent.id }.first.actionId }
        return ColosseumBattleSnapshot(fighters, selected, if (teamForm == null) 0 else 1, max, opponents, parseBattleResult(doc), result, teamForm?.actionId, actionIds)
    }

    fun parseShop(html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): ColosseumShopSnapshot {
        val doc = Jsoup.parse(html, finalUrl)
        val titleObserved = SHOP_TITLE.containsMatchIn(clean(doc.text()))
        val form = page.forms.singleOrNull { f ->
            val candidate = f.submitFields.singleOrNull()?.let { it.name == "Create" && TRADE.matchesSemantic(it.name, it.value) } == true &&
                f.candidates.any { it.selectionType == TownSelectionType.RADIO }
            candidate && titleObserved && findDomForm(doc, f)?.let(::hasStrictTradeShape) == true
        }
        val dom = form?.let { findDomForm(doc, it) }
        val categoryCandidates = form?.candidates.orEmpty().filter { it.selectionType == TownSelectionType.SELECT }
        val categoryField = categoryCandidates.map { it.inputName }.distinct().singleOrNull()
        val select = categoryField?.let { field -> dom?.select("select[name=${css(field)}]")?.singleOrNull() }
        val categories = categoryCandidates.take(MAX_CATEGORIES).mapNotNull { candidate ->
            val option = select?.select("option")?.singleOrNull { it.attr("value") == candidate.inputValue } ?: return@mapNotNull null
            ColosseumShopCategory(candidate.id, clean(option.text()).take(MAX_TEXT), option.hasAttr("selected") || select.`val`() == option.attr("value"))
        }
        val itemTByCandidate = if (form != null && dom != null) strictItemTByCandidate(form, dom) else emptyMap()
        val amount = dom?.select("input[name=amount]")?.singleOrNull()
        val minQuantity = amount?.attr("min")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val maxQuantity = amount?.attr("max")?.toIntOrNull()?.takeIf { it >= minQuantity } ?: MAX_TRADE_QUANTITY
        val items = form?.rows.orEmpty().mapIndexedNotNull { index, row ->
            val label = clean(row.label)
            if (label.isBlank() || HEADER.matches(label) || row.candidate?.selectionType == TownSelectionType.SELECT) return@mapIndexedNotNull null
            val candidate = row.candidate?.takeIf { it.selectionType == TownSelectionType.RADIO }
            val itemT = candidate?.let(itemTByCandidate::get)
            ColosseumShopRow(candidate?.id ?: "display-$index", label.replace(PRICE_PREFIX, "").trim().take(MAX_TEXT), candidate != null && itemT != null, label.take(MAX_TEXT),
                PRICE.find(label)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull(), OWNED.find(label)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull(),
                minQuantity, candidate?.let { maxQuantity }, itemT)
        }.distinctBy(ColosseumShopRow::id).take(MAX_SHOP_ITEMS)
        val currencies = doc.select("body *").map { clean(it.ownText()) }.filter { OWNED_CURRENCY.containsMatchIn(it) }.mapNotNull { text -> CURRENCY.matchEntire(text)?.let { match -> clean(match.groupValues[1]).take(MAX_TEXT).takeIf { CURRENCY_LABEL.containsMatchIn(it) }?.let { ColosseumCurrency(it, match.groupValues[2].boundedInt()) } } }.distinctBy(ColosseumCurrency::label).take(MAX_CURRENCIES)
        return ColosseumShopSnapshot(categories, categories.firstOrNull(ColosseumShopCategory::current)?.id, items, currencies, result, form?.actionId, categoryField)
    }

    private fun parseBattleResult(doc: org.jsoup.nodes.Document): ColosseumBattleResult? {
        val text = clean(doc.text())
        val victory = WINNER.find(text) ?: return null
        val summary = clean(victory.value)
        val turns = TURNS.find(text)?.groupValues?.get(1)?.boundedInt(MAX_TURNS)
        val hp = HP.findAll(text).map { it.groupValues[1] }.toList()
        val statuses = STATUS.findAll(text).map { clean(it.groupValues[1]) }.toList()
        val ownLines = doc.select("h1,h2,h3,li,tr,p,div").map { clean(it.ownText()) }.filter(String::isNotBlank).distinct()
        val details = ownLines.filter { TURN_NUMBER.containsMatchIn(it) && TURN_ACTION.containsMatchIn(it) }.map { line ->
            val match = TURN_NUMBER.find(line)
            ColosseumTurnLine(match?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)?.boundedInt(MAX_TURNS), line.take(MAX_TEXT))
        }
        val reward = ownLines.firstOrNull { REWARD_LINE.containsMatchIn(it) && !HP.containsMatchIn(it) }?.take(MAX_TEXT)
        val winner = victory.groupValues[1].substringAfterLast('》').replace(RANK, "").trim().take(MAX_TEXT)
        return ColosseumBattleResult(turns, winner, summary.take(MAX_TEXT), hp.getOrNull(0), hp.getOrNull(1), statuses.getOrNull(0)?.take(MAX_TEXT), statuses.getOrNull(1)?.take(MAX_TEXT), DAMAGE.find(text)?.groupValues?.get(1)?.boundedLong(), reward, details.take(MAX_DETAIL))
    }

    private fun findDomForm(doc: org.jsoup.nodes.Document, form: ParsedTownForm): Element? = doc.select("form").singleOrNull { dom ->
        val method = if (dom.attr("method").equals("post", true)) "POST" else "GET"
        val action = if (dom.attr("action").isBlank()) doc.location() else URI(doc.location()).resolve(dom.attr("action")).toString()
        method == form.method.name && (action == form.actionUrl || action.substringAfter('?') == form.actionUrl.substringAfter('?')) &&
            dom.select("input,button").any { el -> form.submitFields.any { it.name == el.attr("name") && it.value == el.attr("value").ifBlank { el.text() } } }
    }
    private fun clean(v: String) = v.replace(Regex("\\s+"), " ").trim()
    private fun css(v: String) = "'${v.replace("'", "\\'")}'"
    private fun Regex.matchesSemantic(name: String, value: String) = matches(clean("$name $value")) || matches(clean(value)) || matches(clean(name))
    private fun hasStrictTradeShape(form: Element): Boolean {
        val radios = form.select("input[type=radio][name]").filter { it.closest("form") === form }
        val selects = form.select("select[name]").filter { it.closest("form") === form }
        return radios.isNotEmpty() && radios.map { it.attr("name") }.distinct().size == 1 && selects.size == 1 &&
            listOf("ItemT", "list_type", "amount").all { name -> form.select("input[name=${css(name)}]").count { it.closest("form") === form && !it.hasAttr("disabled") } == 1 }
    }
    private fun strictItemTByCandidate(form: ParsedTownForm, domForm: Element): Map<ParsedTownCandidate, String> =
        form.candidates.filter { it.selectionType == TownSelectionType.RADIO }.mapNotNull { candidate ->
            val control = domForm.select("input[name=${css(candidate.inputName)}]").filter {
                it.closest("form") === domForm && it.attr("value").trim().ifBlank { "on" } == candidate.inputValue
            }.singleOrNull() ?: return@mapNotNull null
            val values = listOf(control.attr("onclick"), control.closest("tr")?.attr("onclick").orEmpty())
                .flatMap { source -> ITEM_T_ASSIGNMENT.findAll(source).map { it.groupValues[3] }.toList() }.distinct()
            candidate to (values.singleOrNull() ?: return@mapNotNull null)
        }.toMap()
    private fun stableOpponentId(form: ParsedTownForm): String? {
        val fields = form.hiddenFields.filterNot { VOLATILE_FIELD.containsMatchIn(it.name) }
            .map { clean(it.name) to it.value.trim() }.filter { it.first.isNotBlank() && it.second.isNotBlank() }
        if (fields.isEmpty()) return null
        val canonical = fields.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }).joinToString("\u001f") { "${it.first}\u001e${it.second}" }
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        return "opponent-${hash.take(32)}"
    }
    private fun String.boundedInt(max: Int = Int.MAX_VALUE) = replace(",", "").toLongOrNull()?.takeIf { it in 0..max.toLong() }?.toInt()
    private fun String.boundedLong() = replace(",", "").toBigIntegerOrNull()?.takeIf { it.signum() >= 0 && it.bitLength() <= 63 }?.toLong()
    private companion object {
        val TEAM = Regex("(?:set\\s*team|team\\s*setting|팀\\s*(?:설정|저장))", RegexOption.IGNORE_CASE)
        val TEAM_FIELD = Regex("team(?:\\[\\])?", RegexOption.IGNORE_CASE)
        val CHALLENGE = Regex("(?:challenge|도전|전투를?\\s*시작)", RegexOption.IGNORE_CASE)
        val TRADE = Regex("(?:create|trade|교환)", RegexOption.IGNORE_CASE)
        val SHOP_TITLE = Regex("콜로세움\\s*교환소|Colosseum\\s*Shop", RegexOption.IGNORE_CASE)
        val MAX_TEAM = Regex("(?:최대|max)\\s*([0-9]+)", RegexOption.IGNORE_CASE)
        val TURNS = Regex("(?:Show\\s*Detail\\s*\\(|)([0-9]+)\\s*turns?", RegexOption.IGNORE_CASE)
        val WINNER = Regex("([^<>.!?]{1,120}?)(?:은\\(는\\)|은|는|이|가)?\\s*승리(?:했다|했습니다|!)", RegexOption.IGNORE_CASE)
        val RANK = Regex("\\[[^]]{1,30}]")
        val HP = Regex("(?:남은\\s*)?HP\\s*:?\\s*([0-9,]+\\s*/\\s*[0-9,]+)", RegexOption.IGNORE_CASE)
        val STATUS = Regex("(?:상태|생존자)\\s*:?\\s*([^\\s|·]+)")
        val DAMAGE = Regex("총\\s*(?:데미지|대미지)\\s*:?\\s*([0-9,]+)")
        val REWARD_LINE = Regex("보상|획득|얻었다|얻었습니다")
        val TURN_ACTION = Regex("(?:공격|피해|데미지|대미지|damage|attack)", RegexOption.IGNORE_CASE)
        val TURN_NUMBER = Regex("(?:turn|턴)\\s*([0-9]+)|([0-9]+)\\s*(?:turn|턴)", RegexOption.IGNORE_CASE)
        val PRICE = Regex("[$]\\s*([0-9,]+)")
        val PRICE_PREFIX = Regex("^[$]\\s*[0-9,]+\\s*")
        val OWNED = Regex("[x×]\\s*([0-9,]+)", RegexOption.IGNORE_CASE)
        val CURRENCY = Regex("(.+?)\\s*[x×:]\\s*([0-9,]+)(?:개|\\s*보유중)?", RegexOption.IGNORE_CASE)
        val CURRENCY_LABEL = Regex("Order\\s+of\\s+Gladiator|검투사|글래디에이터", RegexOption.IGNORE_CASE)
        val OWNED_CURRENCY = Regex("보유\\s*중|보유량|소지\\s*(?:중|품|재화)")
        val HEADER = Regex("^(제작비|수수료|Item|아이템)(?:\\s+(Item|아이템))?$", RegexOption.IGNORE_CASE)
        val ITEM_T_ASSIGNMENT = Regex("(?:document\\.getElementById\\(\\s*(['\"])ItemT\\1\\s*\\)|(?:document\\.)?ItemT)\\s*\\.value\\s*=\\s*(['\"]?)([A-Za-z0-9_.:-]+)\\2", RegexOption.IGNORE_CASE)
        val VOLATILE_FIELD = Regex("(?:nonce|token|csrf|session|timestamp|time|captcha|submit|challenge)", RegexOption.IGNORE_CASE)
        const val MAX_TURNS = 10_000
        const val MAX_DETAIL = 500
        const val MAX_TEXT = 500
        const val MAX_URL = 2_000
        const val MAX_FIGHTERS = 100
        const val MAX_OPPONENTS = 100
        const val MAX_CATEGORIES = 50
        const val MAX_SHOP_ITEMS = 500
        const val MAX_CURRENCIES = 50
        const val MAX_TRADE_QUANTITY = 999
        const val COLOSSEUM_TEAM_SIZE = 5
    }
}
