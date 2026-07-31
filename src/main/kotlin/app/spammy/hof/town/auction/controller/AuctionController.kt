package app.spammy.hof.town.auction.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.town.auction.service.AuctionBidCommand
import app.spammy.hof.town.auction.service.AuctionExhibitCommand
import app.spammy.hof.town.auction.service.AuctionExhibitPage
import app.spammy.hof.town.auction.service.AuctionMarket
import app.spammy.hof.town.auction.service.AuctionObservationService
import app.spammy.hof.town.auction.service.AuctionPage
import app.spammy.hof.town.auction.service.AuctionService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class AuctionBidRequest(
    @field:NotBlank val actionId: String,
    @field:NotBlank val listingId: String,
    @field:PositiveOrZero val bidPrice: Long,
)
data class AuctionExhibitOpenRequest(@field:NotBlank val actionId: String)
data class AuctionExhibitRequest(
    @field:NotBlank val entryActionId: String,
    @field:NotBlank val actionId: String,
    @field:NotBlank val candidateId: String,
    @field:Min(1) @field:Max(100000) val amount: Int,
    @field:NotBlank @field:Size(max = 30) val exhibitTime: String,
    @field:PositiveOrZero val startPrice: Long,
    @field:Size(max = 300) val comment: String = "",
)
data class AuctionClaimRequest(@field:NotBlank val actionId: String)

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

    @PostMapping("/auction/bid")
    fun bid(
        @CurrentAccountId accountId: Long,
        @Valid @RequestBody request: AuctionBidRequest,
    ): AuctionPage = recovery.execute(accountId) { service.bid(accountId, AuctionBidCommand(request.actionId, request.listingId, request.bidPrice)) }

    @PostMapping("/auction/exhibit/open")
    fun openExhibit(@CurrentAccountId accountId: Long, @Valid @RequestBody request: AuctionExhibitOpenRequest): AuctionExhibitPage =
        recovery.execute(accountId) { service.openExhibit(accountId, request.actionId) }

    @PostMapping("/auction/exhibit")
    fun exhibit(@CurrentAccountId accountId: Long, @Valid @RequestBody request: AuctionExhibitRequest): AuctionExhibitPage =
        recovery.execute(accountId) {
            service.exhibit(accountId, AuctionExhibitCommand(
                request.entryActionId, request.actionId, request.candidateId, request.amount, request.exhibitTime, request.startPrice, request.comment,
            ))
        }

    @PostMapping("/auction/claim-item")
    fun claimItem(@CurrentAccountId accountId: Long, @Valid @RequestBody request: AuctionClaimRequest): AuctionPage =
        recovery.execute(accountId) { service.claim(accountId, request.actionId, "GetAutuonItem") }

    @PostMapping("/auction/claim-funds")
    fun claimFunds(@CurrentAccountId accountId: Long, @Valid @RequestBody request: AuctionClaimRequest): AuctionPage =
        recovery.execute(accountId) { service.claim(accountId, request.actionId, "GetAutuonMoney") }

    /** 저장된 익명 관측치만 읽는다. 이 endpoint에서는 HOF 호출이나 세션 복구를 수행하지 않는다. */
    @GetMapping("/auction-market")
    fun market(@RequestParam(required = false) query: String?): AuctionMarket = observations.market(query)
}
