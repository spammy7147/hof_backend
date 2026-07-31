package app.spammy.hof.town.auction.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.town.auction.service.AuctionAction
import app.spammy.hof.town.auction.service.AuctionActionCommand
import app.spammy.hof.town.auction.service.AuctionMarket
import app.spammy.hof.town.auction.service.AuctionObservationService
import app.spammy.hof.town.auction.service.AuctionPage
import app.spammy.hof.town.auction.service.AuctionService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class AuctionActionRequest(
    @field:NotBlank val actionId: String,
    val candidateId: String? = null,
    @field:Min(1) @field:Max(100000) val quantity: Int = 1,
)

@RestController
@RequestMapping("/api/town")
class AuctionController(
    private val service: AuctionService,
    private val observations: AuctionObservationService,
    private val recovery: HofSessionRecoveryService,
) {
    @GetMapping("/auction")
    fun browse(@CurrentAccountId accountId: Long, @RequestParam(required = false) query: String?): AuctionPage =
        recovery.execute(accountId) { service.browse(accountId, query) }

    @PostMapping("/auction/{action}")
    fun action(
        @CurrentAccountId accountId: Long,
        @PathVariable action: String,
        @Valid @RequestBody request: AuctionActionRequest,
    ): AuctionPage = recovery.execute(accountId) {
        val type = runCatching { AuctionAction.valueOf(action.uppercase()) }.getOrElse {
            throw app.spammy.hof.common.error.ApiException(app.spammy.hof.common.error.ErrorCode.INVALID_REQUEST, "지원하지 않는 옥션 작업입니다.")
        }
        service.execute(accountId, type, AuctionActionCommand(request.actionId, request.candidateId, request.quantity))
    }

    /** 저장된 익명 관측치만 읽는다. 이 endpoint에서는 HOF 호출이나 세션 복구를 수행하지 않는다. */
    @GetMapping("/auction-market")
    fun market(@RequestParam(required = false) query: String?): AuctionMarket = observations.market(query)
}
