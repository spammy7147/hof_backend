package app.spammy.hof.town.auction.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.town.auction.entity.AuctionObservationEntity
import app.spammy.hof.town.auction.parser.AuctionPageParser
import app.spammy.hof.town.auction.repository.AuctionObservationRepository
import app.spammy.hof.town.auction.repository.AuctionQueryRepository
import app.spammy.hof.town.auction.repository.AuctionAtomicUpsertRepository
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.model.ParsedTownPage
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import java.security.MessageDigest
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

enum class AuctionAction { BROWSE, BID, EXHIBIT, CLAIM }
enum class ObservationKind { CURRENT, SOLD }

data class AuctionListing(
    val rowKey: String,
    val candidateId: String?,
    val actionId: String,
    val listingId: String?,
    val name: String,
    val type: String?,
    val quantity: Int,
    val totalPrice: Long,
    val unitPrice: Long,
    val action: AuctionAction,
    val kind: ObservationKind,
)
data class AuctionCapabilities(
    val bidActionId: String? = null,
    val exhibitEntryActionId: String? = null,
    val claimItemActionId: String? = null,
    val claimFundsActionId: String? = null,
)
data class AuctionPage(
    val listings: List<AuctionListing>,
    val actions: List<AuctionAction>,
    val capabilities: AuctionCapabilities = AuctionCapabilities(),
    val result: TownActionResultResponse? = null,
)
data class AuctionExhibitPage(
    val items: List<AuctionListing>,
    val durations: List<AuctionDuration>,
    val actionId: String?,
    val entryActionId: String? = null,
    val result: TownActionResultResponse? = null,
)
data class AuctionDuration(val value: String, val label: String)
data class AuctionSnapshot(
    val listingId: String?, val name: String, val type: String?, val quantity: Int,
    val totalPrice: Long, val kind: ObservationKind,
)
data class AuctionBidCommand(val actionId: String, val listingId: String, val bidPrice: Long)
data class AuctionExhibitCommand(
    val entryActionId: String, val actionId: String, val candidateId: String, val amount: Int, val exhibitTime: String,
    val startPrice: Long, val comment: String,
)
data class MarketPoint(val totalPrice: Long, val unitPrice: Long, val quantity: Int, val observedAt: Instant, val kind: ObservationKind)
data class MarketItem(
    val itemKey: String, val name: String, val type: String?, val latestUnitPrice: Long,
    val averageUnitPrice: Long, val minimumUnitPrice: Long, val maximumUnitPrice: Long,
    val tradeCount: Int, val volume: Long, val points: List<MarketPoint>,
)
data class AuctionMarket(val items: List<MarketItem>, val generatedAt: Instant)

@Service
class AuctionObservationService(
    private val repository: AuctionObservationRepository,
    private val queryRepository: AuctionQueryRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val atomicUpsert: AuctionAtomicUpsertRepository? = null,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun observe(snapshots: List<AuctionSnapshot>, observedAt: Instant = clock.instant()) {
        snapshots.forEach { snapshot ->
            if (snapshot.quantity <= 0 || snapshot.totalPrice < 0) return@forEach
            val itemKey = hash("${snapshot.name.lowercase()}|${snapshot.type.orEmpty().lowercase()}")
            val listingId = snapshot.listingId?.trim()?.takeIf(String::isNotEmpty)
            val key = hash(
                listingId?.let { "auction:${it.trimStart('0').ifBlank { "0" }}" }
                    ?: "anonymous|${snapshot.kind}|$itemKey|${snapshot.quantity}|${snapshot.totalPrice}",
            )
            val existing = queryRepository.findByKey(key)
            val entity = existing ?: AuctionObservationEntity(observationKey = key)
            entity.listingId = snapshot.listingId
            entity.observationKind = snapshot.kind.name
            entity.itemKey = itemKey
            entity.itemName = snapshot.name.take(300)
            entity.itemType = snapshot.type?.take(100)
            entity.quantity = snapshot.quantity
            entity.totalPrice = snapshot.totalPrice
            entity.unitPrice = snapshot.totalPrice / snapshot.quantity
            if (existing == null) entity.observedAt = observedAt
            entity.lastSeenAt = observedAt
            atomicUpsert?.upsert(entity) ?: repository.save(entity)
        }
    }

    @Transactional(readOnly = true)
    fun market(query: String?): AuctionMarket {
        val now = clock.instant()
        val observations = queryRepository.findRecent(query, now.minus(Duration.ofDays(30)))
        val items = observations.groupBy { it.itemKey }.mapNotNull { (itemKey, rows) ->
            val sold = rows.filter { it.observationKind == ObservationKind.SOLD.name }
            if (sold.isEmpty()) return@mapNotNull null
            val ordered = sold.sortedBy { it.observedAt }
            MarketItem(
                itemKey, ordered.last().itemName, ordered.last().itemType, ordered.last().unitPrice,
                checkedAverage(sold.map { it.unitPrice }), sold.minOf { it.unitPrice }, sold.maxOf { it.unitPrice },
                sold.size, sold.sumOf { it.quantity.toLong() }, ordered.takeLast(100).map {
                    MarketPoint(it.totalPrice, it.unitPrice, it.quantity, it.observedAt, ObservationKind.valueOf(it.observationKind))
                },
            )
        }.sortedBy { it.name }
        return AuctionMarket(items, now)
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun checkedAverage(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        return values.fold(BigInteger.ZERO) { total, value -> total + BigInteger.valueOf(value) }
            .divide(BigInteger.valueOf(values.size.toLong())).longValueExact()
    }
}

@Service
class AuctionService(
    private val executor: TownAuthenticatedExecutor,
    private val locationResolver: TownLocationResolver,
    private val parser: AuctionPageParser,
    private val observations: AuctionObservationService,
) {
    fun browse(accountId: Long, query: String?): AuctionPage {
        val url = resolveLocation(accountId)
        return executor.loadProjected(accountId, url) { html, _, page ->
            observeBestEffort(html, page)
            parser.parse(html, page, query)
        }
    }

    fun bid(accountId: Long, command: AuctionBidCommand): AuctionPage {
        if (!command.listingId.matches(Regex("\\d{1,20}")) || command.bidPrice < 0) invalid("입찰 번호 또는 입찰가가 올바르지 않습니다.")
        val url = resolveLocation(accountId)
        parser.requireFormActionId(command.actionId)
        return executor.executeProjectedWithScalars(
            accountId, url, TownActionRequest(command.actionId),
            mapOf("ArticleNo" to command.listingId, "BidPrice" to command.bidPrice.toString()),
            BID_SCALARS,
            null,
        ) { html, _, result, page ->
            observeBestEffort(html, page)
            parser.parse(html, page).copy(result = anonymousResult(result))
        }
    }

    fun openExhibit(accountId: Long, actionId: String): AuctionExhibitPage {
        val url = resolveLocation(accountId)
        return executor.executeProjected(accountId, url, resolveAction = { _, _, page ->
            parser.requireExactForm(page, actionId, "ExhibitItemForm")
            TownActionRequest(actionId)
        }) { html, _, result, page -> parser.parseExhibit(html, page).copy(entryActionId = actionId, result = anonymousResult(result)) }
    }

    fun exhibit(accountId: Long, command: AuctionExhibitCommand): AuctionExhibitPage {
        if (command.amount !in 1..100_000 || command.startPrice <= 0 || command.exhibitTime.length !in 1..30 || command.comment.length > 300) {
            invalid("출품 값이 올바르지 않습니다.")
        }
        val url = resolveLocation(accountId)
        return executor.executeTwoStepProjectedWithScalars(
            accountId, url,
            entryAction = { page ->
                parser.requireExactForm(page, command.entryActionId, "ExhibitItemForm")
                TownActionRequest(command.entryActionId)
            },
            finalAction = { page ->
                parser.requireExactForm(page, command.actionId, "PutAuction")
                TownActionRequest(command.actionId, listOf(TownActionSelection(command.candidateId, 1)))
            },
            scalarValues = mapOf(
                "Amount" to command.amount.toString(), "ExhibitTime" to command.exhibitTime,
                "StartPrice" to command.startPrice.toString(), "Comment" to command.comment,
            ),
            requiredScalarFields = EXHIBIT_SCALARS,
            requiredFinalSubmitField = "PutAuction",
        ) { html, _, result, page -> parser.parseExhibit(html, page).copy(entryActionId = command.entryActionId, result = anonymousResult(result)) }
    }

    fun claim(accountId: Long, actionId: String, submitName: String): AuctionPage {
        val url = resolveLocation(accountId)
        return executor.executeProjected(accountId, url, resolveAction = { _, _, page ->
            parser.requireExactForm(page, actionId, submitName)
            TownActionRequest(actionId)
        }) { html, _, result, page ->
            observeBestEffort(html, page)
            parser.parse(html, page).copy(result = anonymousResult(result))
        }
    }

    /** 시세 관측은 부가 기능이므로 파싱 또는 DB 장애가 사용자의 옥션 작업을 막지 않는다. */
    internal fun observeBestEffort(html: String, page: ParsedTownPage) {
        try {
            observations.observe(parser.snapshots(html, page))
        } catch (failure: Exception) {
            logger.warn("Auction observation failed; serving the live auction response: {}", failure.javaClass.simpleName)
        }
    }

    private fun resolveLocation(accountId: Long, origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE): String = try {
        locationResolver.resolve(TownFeatureId.AUCTION).url
    } catch (error: ApiException) {
        if (error.errorCode != ErrorCode.RESOURCE_NOT_FOUND) throw error
        executor.loadProjected(accountId, TOWN_ENTRY_URL, origin) { html, _, _ ->
            locationResolver.resolve(TownFeatureId.AUCTION, html).url
        }
    }
    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
    private fun anonymousResult(result: app.spammy.hof.town.common.model.ParsedTownResult): TownActionResultResponse {
        fun scrub(value: String): String = redactAuctionParticipantLine(value)
        return TownActionResultResponse.from(result.copy(
            messages = result.messages.map(::scrub),
            items = result.items.map { it.copy(label = scrub(it.label)) },
        ))
    }
    private companion object {
        val logger = LoggerFactory.getLogger(AuctionService::class.java)
        const val TOWN_ENTRY_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=town"
        val BID_SCALARS = setOf("ArticleNo", "BidPrice")
        val EXHIBIT_SCALARS = setOf("Amount", "ExhibitTime", "StartPrice", "Comment")
    }
}

private val AUCTION_PARTICIPANT_MARKER = Regex("판매자|입찰자|\\bseller\\b|\\bbidder\\b", RegexOption.IGNORE_CASE)

/** 참여자 표기가 있는 한 결과 줄만 폐기하고 다른 독립 결과 줄은 그대로 보존한다. */
internal fun redactAuctionParticipantLine(value: String): String =
    if (AUCTION_PARTICIPANT_MARKER.containsMatchIn(value)) {
        "옥션 결과(참여자 정보 비공개)"
    } else {
        value.replace(Regex("[A-Za-z0-9가-힣_-]{2,}\\s*님"), "사용자").take(500)
    }
