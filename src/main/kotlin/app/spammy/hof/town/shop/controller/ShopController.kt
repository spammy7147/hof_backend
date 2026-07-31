package app.spammy.hof.town.shop.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.town.shop.catalog.ShopId
import app.spammy.hof.town.shop.dto.CombineRequest
import app.spammy.hof.town.shop.dto.CombineResponse
import app.spammy.hof.town.shop.dto.PurchaseRequest
import app.spammy.hof.town.shop.dto.SellRequest
import app.spammy.hof.town.shop.dto.SellResponse
import app.spammy.hof.town.shop.dto.ShopResponse
import app.spammy.hof.town.shop.service.ShopService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/town")
class ShopController(
    private val service: ShopService,
    private val recovery: HofSessionRecoveryService,
) {
    @GetMapping("/shops/{shop}")
    fun loadShop(@CurrentAccountId accountId: Long, @PathVariable shop: String): ShopResponse =
        recovery.execute(accountId) { service.loadShop(accountId, ShopId.fromPath(shop)) }

    @PostMapping("/shops/{shop}/purchase")
    fun purchase(
        @CurrentAccountId accountId: Long,
        @PathVariable shop: String,
        @Valid @RequestBody request: PurchaseRequest,
    ): ShopResponse = recovery.execute(accountId) { service.purchase(accountId, ShopId.fromPath(shop), request) }

    @GetMapping("/sell")
    fun loadSell(@CurrentAccountId accountId: Long): SellResponse = recovery.execute(accountId) { service.loadSell(accountId) }

    @PostMapping("/sell")
    fun sell(@CurrentAccountId accountId: Long, @Valid @RequestBody request: SellRequest): SellResponse =
        recovery.execute(accountId) { service.sell(accountId, request) }

    @GetMapping("/combine")
    fun loadCombine(@CurrentAccountId accountId: Long): CombineResponse = recovery.execute(accountId) { service.loadCombine(accountId) }

    @PostMapping("/combine")
    fun combine(@CurrentAccountId accountId: Long, @Valid @RequestBody request: CombineRequest): CombineResponse =
        recovery.execute(accountId) { service.combine(accountId, request) }
}
