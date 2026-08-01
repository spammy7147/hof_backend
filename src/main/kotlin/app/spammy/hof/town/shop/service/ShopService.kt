package app.spammy.hof.town.shop.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.model.TownSelectionType
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.fishing.dto.TownActionResultResponse
import app.spammy.hof.town.shop.catalog.ShopId
import app.spammy.hof.town.shop.dto.CombineRequest
import app.spammy.hof.town.shop.dto.CombineResponse
import app.spammy.hof.town.shop.dto.PurchaseRequest
import app.spammy.hof.town.shop.dto.SellRequest
import app.spammy.hof.town.shop.dto.SellResponse
import app.spammy.hof.town.shop.dto.ShopItemResponse
import app.spammy.hof.town.shop.dto.ShopResponse
import app.spammy.hof.town.shop.parser.ShopPageParser
import app.spammy.hof.town.shop.repository.ShopQueryRepository
import java.time.Clock
import java.time.Duration
import org.springframework.stereotype.Service

@Service
class ShopService(
    private val executor: TownAuthenticatedExecutor,
    private val locationResolver: TownLocationResolver,
    private val parser: ShopPageParser,
    private val catalogRefresh: ShopCatalogRefreshService,
    private val queryRepository: ShopQueryRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** 로그인 사용자의 HOF 세션을 확인한 뒤 공용 snapshot을 읽는다. 상품 GET이나 CAPTCHA는 발생시키지 않는다. */
    fun loadShop(accountId: Long, shopId: ShopId): ShopResponse {
        executor.requireSession(accountId)
        return catalog(shopId)
    }

    fun purchase(accountId: Long, shopId: ShopId, request: PurchaseRequest): ShopResponse {
        ensureUnique(request.items.map { it.itemId })
        request.items.forEach { line ->
            queryRepository.findActiveItem(shopId.name, line.itemId)
                ?: invalid("현재 상점에서 구매할 수 없는 품목입니다.")
        }
        val url = locationResolver.resolve(feature(shopId)).url
        val result = executor.executeProjected(
            accountId = accountId,
            pageUrl = url,
            resolveAction = { _, _, page ->
                val form = parser.purchaseForm(page)
                    ?.takeIf { current -> request.items.all { line -> current.candidates.any { it.id == line.itemId } } }
                    ?: invalid("상점 구매 양식이 변경되었습니다. 목록을 새로 확인해 주세요.")
                TownActionRequest(form.actionId, request.items.map { TownActionSelection(it.itemId, it.quantity) })
            },
        ) { _, _, actionResult, _ -> TownActionResultResponse.from(actionResult) }
        return catalog(shopId).copy(result = result)
    }

    fun loadSell(accountId: Long): SellResponse = executor.loadProjected(
        accountId, resolveLocation(accountId, TownFeatureId.SELL),
    ) { _, _, page -> parser.parseSell(page) }

    fun sell(accountId: Long, request: SellRequest): SellResponse {
        ensureUnique(request.items.map { it.candidateId })
        val url = resolveLocation(accountId, TownFeatureId.SELL)
        return executor.executeProjected(
            accountId = accountId,
            pageUrl = url,
            resolveAction = { _, _, page ->
                val ids = request.items.map { it.candidateId }.toSet()
                val form = parser.sellForm(page, ids) ?: invalid("현재 판매할 수 없는 품목이 포함되어 있습니다.")
                TownActionRequest(form.actionId, request.items.map { TownActionSelection(it.candidateId, it.quantity) })
            },
        ) { _, _, result, page -> parser.parseSell(page).copy(result = TownActionResultResponse.from(result)) }
    }

    fun loadCombine(accountId: Long): CombineResponse = executor.loadProjected(
        accountId, resolveLocation(accountId, TownFeatureId.COMBINE),
    ) { _, _, page -> parser.parseCombine(page) }

    fun combine(accountId: Long, request: CombineRequest): CombineResponse {
        val allIds = listOf(request.primaryCandidateId) + request.secondaryCandidateIds
        val url = resolveLocation(accountId, TownFeatureId.COMBINE)
        return executor.executeProjected(
            accountId = accountId,
            pageUrl = url,
            resolveAction = { _, _, page ->
                val form = parser.combineForm(page) ?: invalid("조합 양식을 찾지 못했습니다.")
                val groups = form.candidates.filter { it.selectionType == TownSelectionType.SELECT }
                    .groupBy { it.inputName }.values.toList()
                if (groups.size < 4 || groups[0].none { it.id == request.primaryCandidateId } ||
                    request.secondaryCandidateIds.indices.any { index -> groups[index + 1].none { it.id == request.secondaryCandidateIds[index] } }
                ) invalid("현재 조합 소재에서 선택할 수 없는 항목입니다.")
                TownActionRequest(
                    form.actionId,
                    allIds.mapIndexed { index, id -> TownActionSelection(id, if (index == 0) request.quantity else 1) },
                )
            },
        ) { _, _, result, page -> parser.parseCombine(page).copy(result = TownActionResultResponse.from(result)) }
    }

    private fun catalog(shopId: ShopId): ShopResponse {
        val lastVerified = catalogRefresh.lastSuccessAt(shopId)
        return ShopResponse(
            shopId = shopId.pathValue,
            items = queryRepository.findActiveItems(shopId.name).map { item ->
                ShopItemResponse(item.id.itemKey, item.name, true, item.description, price = item.price, type = item.itemType)
            },
            stale = lastVerified == null || lastVerified.isBefore(clock.instant().minus(Duration.ofDays(1))),
            lastVerifiedAt = lastVerified,
        )
    }

    private fun feature(shopId: ShopId) = when (shopId) {
        ShopId.GENERAL -> TownFeatureId.GENERAL_STORE
        ShopId.SUNDRIES -> TownFeatureId.SUNDRIES_STORE
        ShopId.DARK -> TownFeatureId.DARK_STORE
    }
    /** 미확인 메뉴는 인증된 마을 entry의 공개 menu 링크만 발견·캐시한다. */
    private fun resolveLocation(accountId: Long, featureId: TownFeatureId): String = try {
        locationResolver.resolve(featureId).url
    } catch (error: ApiException) {
        if (error.errorCode != ErrorCode.RESOURCE_NOT_FOUND) throw error
        executor.loadProjected(accountId, TOWN_ENTRY_URL) { html, _, _ ->
            locationResolver.resolve(featureId, html).url
        }
    }
    private fun ensureUnique(ids: List<String>) {
        if (ids.distinct().size != ids.size) invalid("같은 품목을 중복 제출할 수 없습니다.")
    }
    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)

    private companion object {
        const val TOWN_ENTRY_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=town"
    }
}
