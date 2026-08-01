package app.spammy.hof.town.fishing.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.town.fishing.dto.FishingExchangeRequest
import app.spammy.hof.town.fishing.dto.FishingExchangeResponse
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.service.FishingService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/town")
class FishingController(
    private val service: FishingService,
    private val recovery: HofSessionRecoveryService,
) {
    @GetMapping("/fishing")
    fun load(@CurrentAccountId accountId: Long): FishingResponse = recovery.execute(accountId) {
        service.load(accountId)
    }

    @PostMapping("/fishing/actions/{action}")
    fun act(
        @CurrentAccountId accountId: Long,
        @PathVariable action: FishingAction,
    ): FishingResponse = recovery.execute(accountId) { service.act(accountId, action) }

    @GetMapping("/fishing-exchange")
    fun loadExchange(@CurrentAccountId accountId: Long): FishingExchangeResponse = recovery.execute(accountId) {
        service.loadExchange(accountId)
    }

    @GetMapping("/fishing-exchange", params = ["categoryCandidateId"])
    fun loadExchangeCategory(
        @CurrentAccountId accountId: Long,
        @RequestParam categoryCandidateId: String,
    ): FishingExchangeResponse = recovery.execute(accountId) {
        service.loadExchangeCategory(accountId, categoryCandidateId)
    }

    @PostMapping("/fishing-exchange")
    fun exchange(
        @CurrentAccountId accountId: Long,
        @Valid @RequestBody request: FishingExchangeRequest,
    ): FishingExchangeResponse = recovery.execute(accountId) { service.exchange(accountId, request) }
}
