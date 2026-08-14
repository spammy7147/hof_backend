package app.spammy.hof.town.auction.parser

import app.spammy.hof.external.parser.HofHtmlParser

import app.spammy.hof.town.auction.service.AuctionAction
import app.spammy.hof.town.auction.service.AuctionCapabilities
import app.spammy.hof.town.auction.service.AuctionDuration
import app.spammy.hof.town.auction.service.AuctionExhibitPage
import app.spammy.hof.town.auction.service.AuctionListing
import app.spammy.hof.town.auction.service.AuctionPage
import app.spammy.hof.town.auction.service.AuctionSnapshot
import app.spammy.hof.town.auction.service.ObservationKind
import app.spammy.hof.town.common.model.ParsedTownForm
import app.spammy.hof.town.common.model.ParsedTownPage
import java.security.MessageDigest
import org.springframework.stereotype.Component

@Component
class AuctionPageParser {
    fun parse(html: String, page: ParsedTownPage, query: String? = null): AuctionPage {
        val document = HofHtmlParser.parse(html)
        val bidActionId = actionId(page, "Bid", "BidPrice")
        val listings = document.select("table tr").mapNotNull { tr ->
            val cells = tr.children().filter { it.tagName() in setOf("td", "th") }
            if (cells.size < 6 || bidActionId == null) return@mapNotNull null
            val no = Regex("\\d+").find(cells[0].text())?.value ?: return@mapNotNull null
            val total = money(cells[2].text()) ?: return@mapNotNull null
            val itemText = cells[3].text()
            val item = item(itemText)
            AuctionListing(
                rowKey = "lot:$no", candidateId = null, actionId = bidActionId, listingId = no, name = item.first,
                type = item.second, quantity = item.third, totalPrice = total, unitPrice = total / item.third,
                action = AuctionAction.BID, kind = ObservationKind.CURRENT,
            )
        }.filter { query.isNullOrBlank() || it.name.contains(query, true) }
        val actions = mutableListOf<AuctionAction>()
        if (bidActionId != null) actions += AuctionAction.BID
        val capabilities = AuctionCapabilities(
            bidActionId = bidActionId,
            exhibitEntryActionId = actionId(page, "ExhibitItemForm"),
            claimItemActionId = actionId(page, "GetAutuonItem"),
            claimFundsActionId = actionId(page, "GetAutuonMoney"),
        )
        if (capabilities.exhibitEntryActionId != null) actions += AuctionAction.EXHIBIT
        if (capabilities.claimItemActionId != null || capabilities.claimFundsActionId != null) actions += AuctionAction.CLAIM
        return AuctionPage(listings.distinctBy { it.listingId }, actions.distinct(), capabilities)
    }

    fun parse(page: ParsedTownPage, query: String? = null): AuctionPage {
        val forms = page.forms.mapNotNull { form ->
            val action = action(form) ?: return@mapNotNull null
            action to form
        }
        val listings = forms.flatMap { (action, form) ->
            form.rows.mapNotNull { row ->
                val candidate = row.candidate ?: return@mapNotNull null
                parseListing(candidate.id, row.label, action, form.actionId)
            }
        }.distinctBy { "${it.action}:${it.rowKey}" }
            .filter { query.isNullOrBlank() || it.name.contains(query, ignoreCase = true) }
        return AuctionPage(listings, forms.map { it.first }.distinct())
    }

    fun parseExhibit(html: String, page: ParsedTownPage): AuctionExhibitPage {
        val document = HofHtmlParser.parse(html)
        val form = page.forms.singleOrNull { parsed ->
            parsed.submitFields.any { it.name == "PutAuction" } &&
                parsed.candidates.any { it.inputName == "item_no" }
        }
        val items = form?.candidates?.filter { it.inputName == "item_no" }?.map { candidate ->
            val parsed = item(candidate.label)
            AuctionListing("item:${candidate.id}", candidate.id, form.actionId, null, parsed.first, parsed.second, parsed.third, 0, 0, AuctionAction.EXHIBIT, ObservationKind.CURRENT)
        }.orEmpty()
        val durations = document.select("select[name=ExhibitTime] option[value]")
            .filter { it.attr("value").isNotBlank() && !it.hasAttr("disabled") }
            .map { AuctionDuration(it.attr("value"), it.text().trim().ifBlank { it.attr("value") }) }
        return AuctionExhibitPage(items, durations, form?.actionId)
    }

    fun requireExactForm(page: ParsedTownPage, actionId: String, submitName: String): ParsedTownForm =
        page.forms.singleOrNull { form ->
            form.actionId == actionId && form.submitFields.singleOrNull()?.name == submitName
        } ?: throw app.spammy.hof.common.error.ApiException(
            app.spammy.hof.common.error.ErrorCode.INVALID_REQUEST,
            "옥션 양식이 변경되었습니다. 새로고침해 주세요.",
        )

    fun requireFormActionId(actionId: String) {
        if (actionId.isBlank() || actionId.length > 128) throw app.spammy.hof.common.error.ApiException(
            app.spammy.hof.common.error.ErrorCode.INVALID_REQUEST, "옥션 양식 식별자가 올바르지 않습니다.",
        )
    }

    fun snapshots(page: ParsedTownPage): List<AuctionSnapshot> = parse(page).listings.map { row ->
        AuctionSnapshot(row.listingId, row.name, row.type, row.quantity, row.totalPrice, row.kind)
    }

    fun snapshots(html: String, page: ParsedTownPage): List<AuctionSnapshot> {
        val current = parse(html, page).listings.filter { it.action == AuctionAction.BID }.map { row ->
            AuctionSnapshot(row.listingId, row.name, row.type, row.quantity, row.totalPrice, ObservationKind.CURRENT)
        }
        val text = HofHtmlParser.parse(html).body().text()
        val sold = text.split(Regex("(?=No\\.\\s*\\d+)", RegexOption.IGNORE_CASE)).mapNotNull { body ->
            if (!SOLD_INCLUDE.containsMatchIn(body) || SOLD_EXCLUDE.containsMatchIn(body)) return@mapNotNull null
            val no = Regex("No\\.\\s*(\\d+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1) ?: return@mapNotNull null
            val price = money(body) ?: return@mapNotNull null
            val soldItem = Regex("출품한\\s+(.+?)\\s*(?:을|를)\\s+.+?(?:[$￦]\\s*[\\d,]+|[\\d,]+\\s*Funds)", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)
            val englishItem = Regex("No\\.\\s*\\d+\\s+(.+?)\\s+(?:sold|won)(?:\\s+for)?\\s+[$￦]", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)
            val source = (soldItem ?: englishItem ?: body.substringBefore("\$").replace(Regex("^No\\.\\s*\\d+"), ""))
                .replace(Regex("^(?:seller|bidder|판매자|입찰자)\\s*[:：][^|/]+[|/]\\s*", RegexOption.IGNORE_CASE), "")
            val parsed = item(source)
            parsed.first.takeIf { it.isNotBlank() }?.let {
                AuctionSnapshot(no, it, parsed.second, parsed.third, price, ObservationKind.SOLD)
            }
        }.toList()
        return (current + sold).distinctBy { "${it.listingId}|${it.kind}|${it.name}|${it.totalPrice}" }
    }

    fun form(page: ParsedTownPage, action: AuctionAction, candidateId: String?): ParsedTownForm? = page.forms.firstOrNull { form ->
        action(form) == action && (candidateId == null || form.candidates.any { it.id == candidateId })
    }

    fun actionForm(page: ParsedTownPage, action: AuctionAction): ParsedTownForm? = when (action) {
        AuctionAction.BID -> page.forms.firstOrNull { it.submitFields.any { field -> field.name.equals("Bid", true) } || it.hiddenFields.any { field -> field.name == "ArticleNo" } }
        AuctionAction.EXHIBIT -> page.forms.firstOrNull { it.submitFields.any { field -> field.name in setOf("ExhibitItemForm", "PutAuction") } }
        AuctionAction.CLAIM -> page.forms.firstOrNull { it.submitFields.any { field -> field.name in setOf("GetAutuonItem", "GetAutuonMoney") } }
        AuctionAction.BROWSE -> null
    }

    private fun action(form: ParsedTownForm): AuctionAction? {
        val text = (form.rows.joinToString(" ") { it.label } + " " +
            form.submitFields.joinToString(" ") { "${it.name} ${it.value}" }).lowercase()
        return when {
            Regex("낙찰|수령|claim").containsMatchIn(text) -> AuctionAction.CLAIM
            Regex("출품|등록|exhibit|sell").containsMatchIn(text) -> AuctionAction.EXHIBIT
            Regex("입찰|구매|bid|buy").containsMatchIn(text) -> AuctionAction.BID
            form.candidates.isNotEmpty() -> AuctionAction.BROWSE
            else -> null
        }
    }

    private fun parseListing(candidateId: String, raw: String, action: AuctionAction, actionId: String): AuctionListing? {
        val total = PRICE.findAll(raw).mapNotNull { it.groupValues[1].replace(",", "").toLongOrNull() }.lastOrNull() ?: return null
        val quantity = QUANTITY.find(raw)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val type = TYPE.find(raw)?.groupValues?.get(1)?.trim()
        val privacySafe = raw.replace(Regex("(?:판매자|입찰자|seller|bidder)\\s*[:：][^/|]+", RegexOption.IGNORE_CASE), "")
        val name = privacySafe.replace(PRICE, "").substringBefore(" /").substringBefore("(").trim().take(300)
            .ifBlank { "Auction item" }
        val listingId = LISTING_ID.find(raw)?.groupValues?.get(1)
        return AuctionListing(
            rowKey = "candidate:$candidateId",
            candidateId = candidateId,
            actionId = actionId,
            listingId = listingId,
            name = name,
            type = type,
            quantity = quantity,
            totalPrice = total,
            unitPrice = total / quantity,
            action = action,
            kind = if (action == AuctionAction.CLAIM) ObservationKind.SOLD else ObservationKind.CURRENT,
        )
    }

    companion object {
        private val PRICE = Regex("[$￦]\\s*([0-9][0-9,]*)")
        private val QUANTITY = Regex("(?:x|×|수량\\s*[:：]?)\\s*([0-9][0-9,]*)\\s*(?:개)?", RegexOption.IGNORE_CASE)
        private val TYPE = Regex("\\((weapon|armor|cloak|shoes|hat|item|accessory|jobitem|head|avatar|skillseal|char|useitem|addmaterial|housing[^)]*|other)\\)", RegexOption.IGNORE_CASE)
        private val LISTING_ID = Regex("(?:No\\.?|#|번호\\s*[:：]?)\\s*([A-Za-z0-9_-]{1,100})", RegexOption.IGNORE_CASE)
        private val SOLD_INCLUDE = Regex("낙찰하였습니다|낙찰되었습니다|\\bsold\\b|\\bwon\\b", RegexOption.IGNORE_CASE)
        private val SOLD_EXCLUDE = Regex("입찰하였습니다|출품되었습니다|취소되었습니다|입찰자가\\s*없어|Exhibit|Put Auction", RegexOption.IGNORE_CASE)
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun actionId(page: ParsedTownPage, vararg names: String): String? = page.forms.firstOrNull { form ->
        form.submitFields.any { it.name in names } ||
            form.hiddenFields.any { it.name in names } ||
            form.editableFields.any { it.inputName in names }
    }?.actionId
    private fun money(text: String): Long? = PRICE.find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
    private fun item(text: String): Triple<String, String?, Int> {
        val qty = QUANTITY.find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val type = TYPE.find(text)?.groupValues?.get(1)?.trim()
        val name = text.substringBefore(" /").replace(PRICE, "").replace(QUANTITY, "")
            .replace(Regex("\\([^)]*\\)"), "").replace(Regex("(?:판매자|입찰자|seller|bidder)\\s*[:：].*", RegexOption.IGNORE_CASE), "")
            .trim().take(300).ifBlank { "Auction item" }
        return Triple(name, type, qty)
    }
}
