package app.spammy.hof.town.shop.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.shop.catalog.ShopId
import app.spammy.hof.town.shop.parser.ShopPageParser
import app.spammy.hof.town.shop.repository.ShopQueryRepository
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class ShopCatalogRefreshService(
    private val executor: TownAuthenticatedExecutor,
    private val locationResolver: TownLocationResolver,
    private val parser: ShopPageParser,
    private val persistence: ShopCatalogPersistenceService,
    private val queryRepository: ShopQueryRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val owner = UUID.randomUUID().toString()

    /** 당일 최초 진입 계정만 DB lease를 얻어 상점 하나를 검증한다. 실패는 last-good을 손대지 않는다. */
    fun refreshIfDue(accountId: Long, shopId: ShopId) {
        val now = clock.instant()
        val acquired = persistence.tryAcquire(
            jobKey(shopId), owner, now, now.plus(LEASE_DURATION),
            now.minus(REFRESH_INTERVAL), now.minus(FAILURE_RETRY_INTERVAL),
        )
        if (!acquired) return
        try {
            val url = locationResolver.resolve(feature(shopId)).url
            val items = executor.loadProjected(accountId, url) { _, _, page -> parser.parseCatalog(page) }
            check(items.isNotEmpty()) { "상점 카탈로그에서 상품을 찾지 못했습니다." }
            persistence.replaceAndMarkSuccess(shopId, jobKey(shopId), owner, now, items)
        } catch (failure: Exception) {
            val authenticationFailure = failure is ApiException && failure.errorCode in AUTHENTICATION_ERRORS
            runCatching {
                if (authenticationFailure) persistence.releaseAuthenticationFailure(jobKey(shopId), owner)
                else persistence.releaseFailure(jobKey(shopId), owner)
            }
                .exceptionOrNull()?.let(failure::addSuppressed)
            if (authenticationFailure) throw failure
            // 유효한 last-good이 있으면 사용자 진입은 계속된다. 최초 동기화 실패만 호출자에게 알린다.
            if (queryRepository.findActiveItems(shopId.name).isEmpty()) throw failure
        }
    }

    fun lastSuccessAt(shopId: ShopId) = queryRepository.findLease(jobKey(shopId))?.lastSuccessAt
    private fun jobKey(shopId: ShopId) = "SHOP_${shopId.name}"
    private fun feature(shopId: ShopId) = when (shopId) {
        ShopId.GENERAL -> TownFeatureId.GENERAL_STORE
        ShopId.SUNDRIES -> TownFeatureId.SUNDRIES_STORE
        ShopId.DARK -> TownFeatureId.DARK_STORE
    }

    private companion object {
        val REFRESH_INTERVAL: Duration = Duration.ofDays(1)
        val FAILURE_RETRY_INTERVAL: Duration = Duration.ofDays(1)
        val LEASE_DURATION: Duration = Duration.ofMinutes(5)
        val AUTHENTICATION_ERRORS = setOf(ErrorCode.CAPTCHA_REQUIRED, ErrorCode.HOF_SESSION_EXPIRED)
    }
}
