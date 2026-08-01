package app.spammy.hof.town.shop

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.town.shop.catalog.ShopId
import app.spammy.hof.town.shop.config.ShopCatalogCollectorProperties
import app.spammy.hof.town.shop.service.ShopCatalogCollector
import app.spammy.hof.town.shop.service.ShopCatalogRefreshService
import kotlin.test.Test
import org.mockito.Mockito

class ShopCatalogCollectorTest {
    private val refresh = Mockito.mock(ShopCatalogRefreshService::class.java)
    private val recovery = passthroughRecovery()

    @Test
    fun `전용 계정이 설정되면 사용자 진입과 별개로 모든 공용 상점을 검증한다`() {
        ShopCatalogCollector(ShopCatalogCollectorProperties(77L), refresh, recovery).dailyTick()

        ShopId.entries.forEach { shopId ->
            Mockito.verify(refresh).refreshIfDue(77L, shopId, HofRequestOrigin.AUTOMATION)
        }
    }

    @Test
    fun `전용 계정이 없으면 HOF를 요청하지 않는다`() {
        ShopCatalogCollector(ShopCatalogCollectorProperties(0L), refresh, recovery).dailyTick()

        Mockito.verifyNoInteractions(refresh, recovery)
    }

    private fun passthroughRecovery(): HofSessionRecoveryService {
        val service = Mockito.mock(HofSessionRecoveryService::class.java)
        Mockito.doAnswer { invocation -> invocation.getArgument<() -> Any>(2).invoke() }
            .`when`(service).execute<Any>(Mockito.anyLong(), anyOrigin(), anyAction())
        return service
    }

    private fun anyOrigin(): HofRequestOrigin = Mockito.any(HofRequestOrigin::class.java) ?: HofRequestOrigin.AUTOMATION
    private fun anyAction(): () -> Any = Mockito.any<() -> Any>() ?: { Unit }
}
