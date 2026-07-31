package app.spammy.hof.town.pantheon.parser

import app.spammy.hof.external.model.HofFormField
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.model.ParsedTownResult
import app.spammy.hof.town.pantheon.model.*
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component

@Component
class PantheonParser {
    fun parseStreet(html: String, finalUrl: String): PantheonStreetSnapshot {
        val document = Jsoup.parse(html, finalUrl)
        val baseQuery = safeQuery(finalUrl) ?: return PantheonStreetSnapshot(emptyList())
        val observed = document.select("a[href]").asSequence().take(MAX_LINKS).mapNotNull { link ->
            val target = safeObservedTarget(finalUrl, link.attr("href"), baseQuery) ?: return@mapNotNull null
            val delta = queryDelta(baseQuery, safeQuery(target) ?: return@mapNotNull null)
                ?.takeIf { it.size == 1 } ?: return@mapNotNull null
            val owner = link.closest("li")
            val label = sequenceOf(clean(link.text()), clean(owner?.text().orEmpty()))
                .firstOrNull { SHRINE_WORD.containsMatchIn(it) }?.take(MAX_NAME) ?: return@mapNotNull null
            if (label.isBlank() || !SHRINE_WORD.containsMatchIn(label)) return@mapNotNull null
            val (name, alias) = splitName(label)
            val color = parseColor(link) ?: owner?.let(::parseColor)
            val stableIdentity = clean(alias ?: name).lowercase().takeIf(String::isNotBlank) ?: return@mapNotNull null
            stableIdentity to PantheonShrine(
                id = opaque("shrine\u0000$stableIdentity\u0000${canonical(delta)}"),
                name = name,
                alias = alias,
                color = color,
                imageUrl = owner?.selectFirst("img[src]")?.absUrl("src")?.takeIf(::safeImageUrl),
                detailUrl = target,
            )
        }.take(MAX_SHRINES).toList()
        val identityCounts = observed.groupingBy { it.first }.eachCount()
        val idCounts = observed.groupingBy { it.second.id }.eachCount()
        return PantheonStreetSnapshot(observed.filter {
            identityCounts[it.first] == 1 && idCounts[it.second.id] == 1
        }.map { it.second })
    }

    fun parseDetail(
        shrineId: String,
        html: String,
        finalUrl: String,
        page: ParsedTownPage,
        result: ParsedTownResult? = null,
    ): PantheonDetailSnapshot {
        val document = Jsoup.parse(html, finalUrl)
        val heading = document.select("h1,h2,h3,h4").firstOrNull { SHRINE_DETAIL_WORD.containsMatchIn(clean(it.text())) }
        val title = clean(heading?.text().orEmpty()).ifBlank { "신전" }.take(MAX_NAME)
        val (name, alias) = splitName(title)
        val text = clean(document.text()).take(MAX_PAGE_TEXT)
        val actions = parseActions(document, finalUrl, page)
        val description = heading?.let(::descriptionAfter)?.take(MAX_DESCRIPTION)
        return PantheonDetailSnapshot(
            shrineId = shrineId,
            name = name,
            alias = alias,
            description = description,
            imageUrl = heading?.nextElementSibling()?.let { sibling ->
                (if (sibling.tagName() == "img") sibling else sibling.selectFirst("img[src]"))?.absUrl("src")?.takeIf(::safeImageUrl)
            } ?: document.selectFirst("h1 + img[src], h2 + img[src], h3 + img[src], h4 + img[src]")?.absUrl("src")?.takeIf(::safeImageUrl),
            deity = DEITY.find(text)?.groupValues?.get(1)?.trim()?.take(MAX_FIELD),
            alignment = ALIGNMENT.find(text)?.groupValues?.get(1)?.trim()?.take(MAX_FIELD),
            domains = DOMAINS.find(text)?.groupValues?.get(1)?.split('/', ',', '·')?.map(::clean)?.filter(String::isNotBlank)?.distinct()?.take(MAX_DOMAINS).orEmpty(),
            relation = RELATION.find(text)?.groupValues?.get(1)?.trim()?.take(MAX_FIELD),
            currentJob = CURRENT_JOB.find(text)?.groupValues?.get(1)?.trim()?.take(MAX_FIELD),
            actions = actions,
            result = result,
        )
    }

    private fun parseActions(document: Document, finalUrl: String, page: ParsedTownPage): List<PantheonAction> {
        val result = mutableListOf<PantheonAction>()
        val base = safeQuery(finalUrl)?.takeIf { query -> query.values.all { it.size == 1 } } ?: return emptyList()
        document.select("form").take(MAX_FORMS).forEach { domForm ->
            val controls = domForm.select("input,button,select,textarea")
                .filter { it.closest("form") === domForm && !it.hasAttr("disabled") }
            if (controls.size > MAX_CONTROLS) return@forEach
            val submits = controls.filter(::isSubmit)
            if (submits.size != 1 || controls.any { control ->
                    control !== submits.single() && !(control.tagName() == "input" && control.attr("type").equals("hidden", true))
                }
            ) return@forEach
            val hiddenNames = controls.filter { it.tagName() == "input" && it.attr("type").equals("hidden", true) }.map { it.attr("name").trim() }
            val hiddenControls = controls.filter { it.tagName() == "input" && it.attr("type").equals("hidden", true) }
            if (hiddenNames.any { it.isBlank() || it.length > MAX_FIELD_NAME } ||
                hiddenControls.any { it.attr("value").length > MAX_FIELD_VALUE } ||
                hiddenNames.size != hiddenNames.distinct().size
            ) return@forEach
            submits.forEach { submit ->
                val submitName = submit.attr("name").trim()
                val submitValue = submit.attr("value").ifBlank { submit.text() }
                if (submitName.isBlank() || submitName.length > MAX_FIELD_NAME || submitValue.length > MAX_FIELD_VALUE ||
                    submitName in hiddenNames
                ) return@forEach
                val label = clean(submitValue).take(MAX_LABEL)
                val type = classify(label) ?: return@forEach
                val safeActionUrl = safeFormTarget(finalUrl, domForm.attr("action"), base) ?: return@forEach
                val current = page.forms.singleOrNull { parsed ->
                    parsed.method in setOf(HofHttpMethod.GET, HofHttpMethod.POST) && parsed.submitFields.singleOrNull()?.let {
                        it.name == submitName && it.value == submitValue
                    } == true && parsed.actionUrl == safeActionUrl
                } ?: return@forEach
                if (current.candidates.isNotEmpty() || current.hiddenFields.size > MAX_HIDDEN ||
                    current.hiddenFields.map(HofFormField::name) != hiddenNames
                ) return@forEach
                val actionQuery = queryDelta(base, safeQuery(safeActionUrl) ?: return@forEach) ?: return@forEach
                val contract = listOf(
                    current.method.name,
                    canonical(actionQuery),
                    fieldContract(submitName, submitValue),
                    hiddenNames.sorted().joinToString("\u0001"),
                ).joinToString("\u0000")
                result += action(label, type, contract, formActionId = current.actionId)
            }
        }
        document.select("a[href]").take(MAX_LINKS).forEach { link ->
            val label = clean(link.text()).take(MAX_LABEL)
            val type = classify(label) ?: return@forEach
            val target = safeObservedTarget(finalUrl, link.attr("href"), base) ?: return@forEach
            val targetQuery = safeQuery(target) ?: return@forEach
            val query = queryDelta(base, targetQuery) ?: return@forEach
            if (query.isEmpty() || query.size > MAX_QUERY_FIELDS) return@forEach
            result += action(label, type, canonical(query), query = query)
        }
        val bounded = result.take(MAX_ACTIONS)
        val counts = bounded.groupingBy(PantheonAction::id).eachCount()
        return bounded.filter { counts[it.id] == 1 }
    }

    private fun action(
        label: String,
        type: ShrineAction,
        identityContract: String,
        formActionId: String? = null,
        query: List<HofFormField>? = null,
    ): PantheonAction {
        val funds = FUNDS.find(label)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()?.takeIf { it in 1..MAX_FUNDS }
        val percent = PERCENT.find(label)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..100 }
        val item = ITEM_DONATION.find(label)?.destructured
        return PantheonAction(
            id = opaque("${type.name}\u0000${clean(label).lowercase()}\u0000$identityContract"), type = type, label = label,
            costFunds = funds, fundsPercent = percent,
            itemName = item?.component1()?.trim()?.take(MAX_FIELD),
            itemQuantity = item?.component2()?.toIntOrNull()?.takeIf { it in 1..MAX_ITEM_QUANTITY },
            formActionId = formActionId, query = query,
        )
    }

    private fun classify(label: String): ShrineAction? {
        val semantic = label.trimEnd(' ', '.', '!', '。')
        return when {
        CHECK.matches(semantic) -> ShrineAction.CHECK_DOCTRINE
        BUY.matches(semantic) -> ShrineAction.BUY_PRIEST_ITEM
        DONATE_ITEM.matches(semantic) -> ShrineAction.DONATE_ITEM
        DONATE_PERCENT.matches(semantic) && PERCENT.find(semantic)?.groupValues?.get(1) == "1" -> ShrineAction.DONATE_PERCENT
        DONATE_FIXED.matches(semantic) && !PERCENT.containsMatchIn(semantic) && FUNDS.containsMatchIn(semantic) -> ShrineAction.DONATE_FIXED
        else -> null
        }
    }

    private fun descriptionAfter(heading: Element): String? {
        val chunks = mutableListOf<String>()
        var current = heading.nextElementSibling()
        while (current != null && current.tagName() !in HEADING_TAGS && chunks.size < MAX_DESCRIPTION_BLOCKS) {
            if (current.tagName() !in setOf("form", "script", "style") && !current.hasClass("actions")) {
                val value = clean(current.text())
                if (value.isNotBlank() && !FIELD_PREFIX.containsMatchIn(value)) chunks += value
            }
            current = current.nextElementSibling()
        }
        return chunks.joinToString(" ").take(MAX_DESCRIPTION).ifBlank { null }
    }

    private fun splitName(value: String): Pair<String, String?> {
        val match = NAME_ALIAS.matchEntire(value)
        return if (match == null) value to null else clean(match.groupValues[1]) to clean(match.groupValues[2]).ifBlank { null }
    }

    private fun safeObservedTarget(pageUrl: String, href: String, baseQuery: Map<String, List<String>>): String? {
        val target = safeFormTarget(pageUrl, href, baseQuery) ?: return null
        if (safeQuery(target) == baseQuery) return null
        return target
    }

    private fun safeFormTarget(pageUrl: String, href: String, baseQuery: Map<String, List<String>>): String? {
        val resolved = runCatching { URI(resolve(pageUrl, href)).normalize() }.getOrNull() ?: return null
        if (resolved.scheme != "http" || !resolved.host.equals("sic.zerosic.com", true) || resolved.port !in setOf(-1, 80) ||
            resolved.rawUserInfo != null || resolved.rawFragment != null || resolved.path != "/ZeroHOF/index.php"
        ) return null
        val query = safeQuery(resolved.toASCIIString()) ?: return null
        if (query.size !in 1..MAX_QUERY_FIELDS || query.values.any { it.size != 1 } || baseQuery.values.any { it.size != 1 }) return null
        val baseMenu = baseQuery["menu"]?.singleOrNull() ?: return null
        if (query["menu"]?.singleOrNull() != baseMenu) return null
        if (baseQuery.any { (key, values) -> query[key] != values }) return null
        return resolved.toASCIIString()
    }

    private fun queryDelta(base: Map<String, List<String>>, target: Map<String, List<String>>): List<HofFormField>? {
        if (base.values.any { it.size != 1 } || target.values.any { it.size != 1 } ||
            base.any { (name, values) -> target[name] != values }
        ) return null
        return target.entries.asSequence().filter { (name, _) -> name !in base }.flatMap { (name, values) ->
            values.asSequence().map { HofFormField(name, it) }
        }.toList()
    }

    private fun canonical(fields: List<HofFormField>): String = fields.sortedWith(compareBy(HofFormField::name, HofFormField::value))
        .joinToString("\u0001") { fieldContract(it.name, it.value) }

    private fun fieldContract(name: String, value: String) = "${name.length}:$name:${value.length}:$value"

    private fun safeQuery(url: String): Map<String, List<String>>? = runCatching {
        val uri = URI(url)
        uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).map { part ->
            decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
        }.also { entries -> if (entries.any { it.first.isBlank() || it.first.length > 80 || it.second.isBlank() || it.second.length > 500 }) error("unsafe") }
            .groupBy({ it.first }, { it.second })
    }.getOrNull()

    private fun resolve(pageUrl: String, href: String): String = when {
        href.startsWith("?") -> pageUrl.substringBefore('#').substringBefore('?') + href
        else -> URI(pageUrl).resolve(href).toString()
    }
    private fun parseColor(element: Element): String? {
        val value = element.attr("style").let { COLOR.find(it)?.groupValues?.get(1) }
            ?: element.attr("color").takeIf(String::isNotBlank)
        return value?.trim()?.take(32)
    }
    private fun safeImageUrl(value: String): Boolean = runCatching {
        val uri = URI(value).normalize()
        uri.scheme in setOf("http", "https") && uri.host.equals("sic.zerosic.com", true) &&
            ((uri.scheme == "http" && uri.port in setOf(-1, 80)) || (uri.scheme == "https" && uri.port in setOf(-1, 443))) &&
            uri.userInfo == null && uri.fragment == null && uri.path.startsWith("/ZeroHOF/")
    }.getOrDefault(false)
    private fun isSubmit(element: Element) = when (element.tagName()) {
        "input" -> element.attr("type").lowercase() in setOf("submit", "image")
        "button" -> element.attr("type").lowercase().let { it.isBlank() || it == "submit" }
        else -> false
    }
    private fun clean(value: String) = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
    private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8)
    private fun opaque(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    private companion object {
        val SHRINE_WORD = Regex("신전|전당|창고|도서관|밀실|제단|성당|정원|둥지|나선탑|구덩이", RegexOption.IGNORE_CASE)
        val SHRINE_DETAIL_WORD = Regex("신전|전당|창고|도서관|밀실|제단|성당|정원|둥지|나선탑|구덩이|Hall|Storage|Library|Altar|Church|Garden|Nest|Tower|Pit", RegexOption.IGNORE_CASE)
        val NAME_ALIAS = Regex("^(.+?)\\s*\\(([^()]*)\\)\\s*$")
        val DEITY = Regex("(?:섬기는|설치되는)\\s*신\\s*[:：]?\\s*([^\\n]+?)(?=\\s*(?:성향|주관하는|F[■□]|공민|사제와|$))")
        val ALIGNMENT = Regex("성향\\s*[:：]?\\s*(Law|Neutral|Evil|Good|Chaos|중립|질서|악|선)", RegexOption.IGNORE_CASE)
        val DOMAINS = Regex("주관하는\\s*영역\\s*[:：]?\\s*(.+?)(?=\\s*(?:관계\\s*[:：]|F[■□]|공민|사제와|$))")
        val RELATION = Regex("(?:관계|신앙)\\s*[:：]?\\s*(.+?)(?=\\s*(?:공민|사제와|$))")
        val CURRENT_JOB = Regex("(?:현재\\s*)?(?:Work|작업)\\s*[:：]\\s*(.+?)(?=\\s*(?:Auction|F[■□]|$))", RegexOption.IGNORE_CASE)
        val CHECK = Regex("^(?:교리|가르침|교단)\\s*(?:을|를)?\\s*(?:확인한다|확인|본다|보기)$")
        val BUY = Regex("^사제\\s*아이템(?:을)?\\s*(?:구입한다|구입|산다)(?:\\s*\\(.+\\))?$")
        val DONATE_ITEM = Regex("^(?:아이템|.+?\\s+x?\\s*\\d+)\\s*(?:을)?\\s*(?:교단에\\s*)?(?:기부한다|기부)$")
        val DONATE_PERCENT = Regex("^(?:교단에\\s*)?기부한다.*(?:%|퍼센트).*$")
        val DONATE_FIXED = Regex("^(?:교단에\\s*)?기부한다(?:\\s*\\(.+Funds.*\\))?$")
        val FUNDS = Regex("([0-9][0-9,]*)\\s*Funds", RegexOption.IGNORE_CASE)
        val PERCENT = Regex("([0-9]{1,3})\\s*%")
        val ITEM_DONATION = Regex("^(.+?)\\s*x?\\s*(\\d+)\\s*(?:을)?\\s*(?:교단에\\s*)?기부")
        val COLOR = Regex("color\\s*:\\s*([^;]+)", RegexOption.IGNORE_CASE)
        val FIELD_PREFIX = Regex("섬기는\\s*신|성향\\s*:|주관하는\\s*영역|F[■□]|공민|사제와")
        val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
        const val MAX_LINKS = 500
        const val MAX_SHRINES = 50
        const val MAX_FORMS = 100
        const val MAX_CONTROLS = 40
        const val MAX_HIDDEN = 32
        const val MAX_FIELD_NAME = 80
        const val MAX_FIELD_VALUE = 500
        const val MAX_QUERY_FIELDS = 8
        const val MAX_ACTIONS = 20
        const val MAX_NAME = 300
        const val MAX_LABEL = 500
        const val MAX_FIELD = 500
        const val MAX_DOMAINS = 30
        const val MAX_DESCRIPTION = 4_000
        const val MAX_DESCRIPTION_BLOCKS = 20
        const val MAX_PAGE_TEXT = 200_000
        const val MAX_FUNDS = 10_000_000_000L
        const val MAX_ITEM_QUANTITY = 1_000_000_000
    }
}
