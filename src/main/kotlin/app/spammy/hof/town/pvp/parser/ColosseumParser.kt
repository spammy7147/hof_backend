package app.spammy.hof.town.pvp.parser

import app.spammy.hof.external.parser.HofHtmlParser

import app.spammy.hof.town.common.model.*
import app.spammy.hof.town.pvp.model.*
import java.net.URI
import org.jsoup.nodes.FormElement
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
class ColosseumParser {
    fun parseBattle(html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): ColosseumBattleSnapshot {
        val doc = HofHtmlParser.parse(html, finalUrl)
        val teamForm = page.forms.singleOrNull { form ->
            isRankForm(form) && (TEAM.matches(form.submitLabel) || form.hiddenFields.any { it.name == "SetRankTeam" }) && form.candidates.isNotEmpty() &&
                form.candidates.all { it.selectionType == TownSelectionType.CHECKBOX && CHARACTER_FIELD.matches(it.inputName) }
        }
        val teamDom = teamForm?.let { findDomForm(doc, it) }
        val fighters = teamForm?.candidates.orEmpty().sortedBy { it.inputPosition }.take(MAX_FIGHTERS).map { candidate ->
            val control = teamDom?.let(::formControls)?.singleOrNull { it.attr("name") == candidate.inputName }
            val container = control?.closest("td") ?: control?.parent()
            ColosseumFighter("fighter-${candidate.inputName.removePrefix("char_")}", clean(candidate.label).take(MAX_TEXT), clean(candidate.label).take(MAX_TEXT),
                container?.selectFirst("img")?.absUrl("src")?.ifBlank { null }?.take(MAX_URL), candidate.selected,
                candidate.inputName.removePrefix("char_"))
        }
        val challengeForm = page.forms.singleOrNull { form ->
            isRankForm(form) &&
                form.submitFields.singleOrNull()?.let { it.name == "ChallengeRank" && it.value == "challenge!" } == true &&
                form.candidates.isEmpty()
        }
        val top = parseRankings(doc, "TOP 5")
        val near = parseRankings(doc, "NEAR 5")
        val notice = doc.select("#contents").firstOrNull()?.let { NEXT_CHALLENGE.find(clean(it.text()))?.value }
            ?: NEXT_CHALLENGE.find(clean(doc.text()))?.value
        return ColosseumBattleSnapshot(fighters, fighters.filter(ColosseumFighter::selected).map(ColosseumFighter::id),
            COLOSSEUM_TEAM_SIZE, COLOSSEUM_TEAM_SIZE, top, near, challengeForm != null, notice,
            parseBattleResult(doc), result, teamForm?.takeIf { TEAM.matches(it.submitLabel) }?.actionId,
            teamForm?.candidates.orEmpty().associate { "fighter-${it.inputName.removePrefix("char_")}" to it.id }, challengeForm?.actionId)
    }

    private fun parseRankings(doc: org.jsoup.nodes.Document, heading: String): List<ColosseumRanking> {
        val group = doc.select("div").singleOrNull { clean(it.ownText()) == heading }?.parent() ?: return emptyList()
        return group.selectFirst("table")?.select("tr")?.mapNotNull { row ->
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.size != 2 || clean(cells[0].text()) == "순위") return@mapNotNull null
            val trophy = cells[0].selectFirst("img")?.attr("src")?.let { TROPHY.find(it)?.groupValues?.get(1) }
            val rank = clean(cells[0].text()).ifBlank { trophy?.let { "${it}위" }.orEmpty() }
            val teams = cells[1].wholeText().lines().map(::clean).filter(String::isNotBlank).map { it.take(MAX_TEXT) }
            rank.takeIf(String::isNotBlank)?.let { ColosseumRanking(it, teams.take(MAX_OPPONENTS)) }
        }?.take(5).orEmpty()
    }

    fun parseShop(html: String, finalUrl: String, page: ParsedTownPage, result: ParsedTownResult? = null): ColosseumShopSnapshot {
        val doc = HofHtmlParser.parse(html, finalUrl)
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
        val outcome = doc.select("span,h1,h2,td,div,p").lastOrNull { element ->
            WINNER.containsMatchIn(clean(element.ownText())) || DRAW.matches(clean(element.ownText()))
        } ?: return null
        val summary = clean(outcome.ownText())
        val victory = WINNER.find(summary)
        val text = clean(doc.text())
        val resultCells = doc.select("td.teams").filter { HP.containsMatchIn(it.text()) && STATUS.containsMatchIn(it.text()) }
        val labels = doc.select("td").filter { it.text().contains("레벨 합계") && it.text().contains("평균 레벨") }
            .map { clean(it.wholeText().lineSequence().firstOrNull { line -> line.isNotBlank() }.orEmpty()).take(MAX_TEXT) }
        val teams = resultCells.takeLast(2).mapIndexed { index, cell ->
            val value = clean(cell.text())
            ColosseumBattleTeamResult(labels.getOrNull(index), HP.find(value)?.groupValues?.get(1),
                STATUS.find(value)?.groupValues?.get(1), DAMAGE.find(value)?.groupValues?.get(1)?.boundedLong())
        }
        val turns = TURNS.find(text)?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)?.boundedInt(MAX_TURNS)
        val reward = doc.select("body *").map { clean(it.ownText()) }
            .firstOrNull { REWARD_LINE.containsMatchIn(it) && !HP.containsMatchIn(it) }?.take(MAX_TEXT)
        val detailUrl = doc.select("a[href]").mapNotNull { link ->
            runCatching { URI(link.absUrl("href")) }.getOrNull()?.takeIf { uri ->
                uri.scheme == "https" && uri.host == "hof.zerosic.com" && uri.userInfo == null &&
                    uri.port == -1 && uri.path in setOf("", "/", "/index.php") && DETAIL_QUERY.matches(uri.rawQuery.orEmpty())
            }?.toString()
        }.distinct().singleOrNull()
        return ColosseumBattleResult(turns, victory?.groupValues?.get(1)?.substringAfterLast('》')?.replace(RANK, "")?.trim(),
            summary.take(MAX_TEXT), teams, reward, detailUrl)
    }

    private fun formControls(form: Element): List<Element> =
        if (form is FormElement) form.elements().toList() else form.select("input,button,select,textarea").toList()

    private fun isRankForm(form: ParsedTownForm): Boolean =
        form.method == app.spammy.hof.external.model.HofHttpMethod.POST &&
            URI(form.actionUrl).rawQuery == "menu=rank"

    private fun findDomForm(doc: org.jsoup.nodes.Document, form: ParsedTownForm): Element? = doc.select("form").singleOrNull { dom ->
        val method = if (dom.attr("method").equals("post", true)) "POST" else "GET"
        val action = if (dom.attr("action").isBlank()) doc.location() else URI(doc.location()).resolve(dom.attr("action")).toString()
        method == form.method.name && (action == form.actionUrl || action.substringAfter('?') == form.actionUrl.substringAfter('?')) &&
            (form.submitLabel.isBlank() && formControls(dom).any { it.attr("name") == "SetRankTeam" } ||
            formControls(dom).any { el ->
                val type = el.attr("type").lowercase()
                (type == "submit" || el.tagName() == "button") &&
                    clean(el.attr("value").ifBlank { el.text() }) == form.submitLabel &&
                    (form.submitFields.isEmpty() && el.attr("name").isBlank() || form.submitFields.any { it.name == el.attr("name") && it.value == el.attr("value").ifBlank { el.text() } })
            })
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
    private fun String.boundedInt(max: Int = Int.MAX_VALUE) = replace(",", "").toLongOrNull()?.takeIf { it in 0..max.toLong() }?.toInt()
    private fun String.boundedLong() = replace(",", "").toBigIntegerOrNull()?.takeIf { it.signum() >= 0 && it.bitLength() <= 63 }?.toLong()
    private companion object {
        val TEAM = Regex("(?:set\\s*team|team\\s*setting|팀\\s*(?:설정|저장))", RegexOption.IGNORE_CASE)
        val TRADE = Regex("(?:create|trade|교환)", RegexOption.IGNORE_CASE)
        val SHOP_TITLE = Regex("콜로세움\\s*교환소|Colosseum\\s*Shop", RegexOption.IGNORE_CASE)
        val CHARACTER_FIELD = Regex("char_[0-9]+")
        val TROPHY = Regex("Thro([1-3])[.]gif", RegexOption.IGNORE_CASE)
        val NEXT_CHALLENGE = Regex("Time left to Next\\s*:\\s*[0-9]+:[0-9]{2}:[0-9]{2}", RegexOption.IGNORE_CASE)
        val TURNS = Regex("([0-9]+)\\s*turns?|턴\\s*:\\s*([0-9]+)\\s*/[0-9]+", RegexOption.IGNORE_CASE)
        val DRAW = Regex(".*(?:무승부|비겼다|draw).*", RegexOption.IGNORE_CASE)
        val DETAIL_QUERY = Regex("rlog=[0-9]{1,30}")
        val WINNER = Regex("([^<>.!?]{1,120}?)(?:은\\(는\\)|은|는|이|가)?\\s*승리(?:했다|했습니다|!)", RegexOption.IGNORE_CASE)
        val RANK = Regex("\\[[^]]{1,30}]")
        val HP = Regex("(?:남은\\s*)?HP\\s*:?\\s*([0-9,]+\\s*/\\s*[0-9,]+)", RegexOption.IGNORE_CASE)
        val STATUS = Regex("(?:상태|생존자)\\s*:?\\s*([^\\s|·]+)")
        val DAMAGE = Regex("총\\s*(?:데미지|대미지)\\s*:?\\s*([0-9,]+)")
        val REWARD_LINE = Regex("보상|획득|얻었다|얻었습니다")
        val PRICE = Regex("[$]\\s*([0-9,]+)")
        val PRICE_PREFIX = Regex("^[$]\\s*[0-9,]+\\s*")
        val OWNED = Regex("[x×]\\s*([0-9,]+)", RegexOption.IGNORE_CASE)
        val CURRENCY = Regex("(.+?)\\s*[x×:]\\s*([0-9,]+)(?:개|\\s*보유중)?", RegexOption.IGNORE_CASE)
        val CURRENCY_LABEL = Regex("Order\\s+of\\s+Gladiator|검투사|글래디에이터", RegexOption.IGNORE_CASE)
        val OWNED_CURRENCY = Regex("보유\\s*중|보유량|소지\\s*(?:중|품|재화)")
        val HEADER = Regex("^(제작비|수수료|Item|아이템)(?:\\s+(Item|아이템))?$", RegexOption.IGNORE_CASE)
        val ITEM_T_ASSIGNMENT = Regex("(?:document\\.getElementById\\(\\s*(['\"])ItemT\\1\\s*\\)|(?:document\\.)?ItemT)\\s*\\.value\\s*=\\s*(['\"]?)([A-Za-z0-9_.:-]+)\\2", RegexOption.IGNORE_CASE)
        const val MAX_TURNS = 10_000
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
