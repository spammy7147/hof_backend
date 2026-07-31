package app.spammy.hof.town.auction.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.town.auction.entity.AuctionObservationEntity
import app.spammy.hof.town.auction.parser.AuctionPageParser
import app.spammy.hof.town.auction.repository.AuctionObservationRepository
import app.spammy.hof.town.auction.repository.AuctionQueryRepository
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

enum class AuctionAction { BROWSE, BID, EXHIBIT, CLAIM }
enum class ObservationKind { CURRENT, SOLD }

data class AuctionListing(
    val candidateId: String,
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
data class AuctionPage(val listings: List<AuctionListing>, val actions: List<AuctionAction>, val result: TownActionResultResponse? = null)
data class AuctionSnapshot(
    val listingId: String?, val name: String, val type: String?, val quantity: Int,
    val totalPrice: Long, val kind: ObservationKind,
)
data class AuctionActionCommand(
    val actionId: String, val candidateId: String? = null, val quantity: Int = 1,
    val listingId: String? = null, val price: Long? = null,
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
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun observe(snapshots: List<AuctionSnapshot>, observedAt: Instant = clock.instant()) {
        snapshots.forEach { snapshot ->
            if (snapshot.quantity <= 0 || snapshot.totalPrice < 0) return@forEach
            val itemKey = hash("${snapshot.name.lowercase()}|${snapshot.type.orEmpty().lowercase()}")
            val bucket = observedAt.epochSecond / 3600
            val key = hash(snapshot.listingId?.let { "id:$it|${snapshot.kind}" }
                ?: "$itemKey|${snapshot.quantity}|${snapshot.totalPrice}|$bucket|${snapshot.kind}")
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
            repository.save(entity)
        }
    }

    @Transactional(readOnly = true)
    fun market(query: String?): AuctionMarket {
        val now = clock.instant()
        val observations = queryRepository.findRecent(query, now.minus(Duration.ofDays(30)))
        val items = observations.groupBy { it.itemKey }.map { (itemKey, rows) ->
            val ordered = rows.sortedBy { it.observedAt }
            MarketItem(
                itemKey, ordered.last().itemName, ordered.last().itemType, ordered.last().unitPrice,
                rows.map { it.unitPrice }.average().toLong(), rows.minOf { it.unitPrice }, rows.maxOf { it.unitPrice },
                rows.size, rows.sumOf { it.quantity.toLong() }, ordered.takeLast(100).map {
                    MarketPoint(it.totalPrice, it.unitPrice, it.quantity, it.observedAt, ObservationKind.valueOf(it.observationKind))
                },
            )
        }.sortedBy { it.name }
        return AuctionMarket(items, now)
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
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
            observations.observe(parser.snapshots(html, page))
            parser.parse(html, page, query)
        }
    }

    fun execute(accountId: Long, action: AuctionAction, command: AuctionActionCommand): AuctionPage {
        if (action == AuctionAction.BROWSE) invalid("조회는 비용 작업이 아닙니다.")
        if (command.quantity !in 1..100_000) invalid("수량이 올바르지 않습니다.")
        val url = resolveLocation(accountId)
        return executor.executeProjected(
            accountId = accountId, pageUrl = url,
            resolveAction = { _, _, page ->
                val form = parser.form(page, action, command.candidateId)
                    ?.takeIf { it.actionId == command.actionId }
                    ?: invalid("옥션 양식이 변경되었습니다. 새로고침해 주세요.")
                TownActionRequest(
                    form.actionId,
                    command.candidateId?.let { listOf(TownActionSelection(it, command.quantity)) }.orEmpty(),
                )
            },
        ) { html, _, result, page ->
            observations.observe(parser.snapshots(html, page))
            parser.parse(html, page).copy(result = TownActionResultResponse.from(result))
        }
    }

    fun collectorPage(accountId: Long): List<AuctionSnapshot> {
        val url = resolveLocation(accountId, HofRequestOrigin.AUTOMATION)
        return executor.loadProjected(accountId, url, HofRequestOrigin.AUTOMATION) { html, _, page ->
            parser.snapshots(html, page)
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
    private companion object { const val TOWN_ENTRY_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=town" }
}
