package app.spammy.hof.town.reward.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.town.reward.dto.OrbExchangeRequest
import app.spammy.hof.town.reward.dto.StashOpenRequest
import app.spammy.hof.town.reward.service.RewardService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/town/rewards")
class RewardController(
    private val service: RewardService,
    private val recovery: HofSessionRecoveryService,
) {
    @GetMapping("/stash") fun stash(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.loadStash(accountId) }
    @PostMapping("/stash/open") fun openStash(@CurrentAccountId accountId: Long, @Valid @RequestBody request: StashOpenRequest) = recovery.execute(accountId) { service.openStash(accountId, request) }
    @GetMapping("/orbs") fun orbs(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.loadOrbs(accountId) }
    @PostMapping("/orbs/exchange") fun exchangeOrbs(@CurrentAccountId accountId: Long, @Valid @RequestBody request: OrbExchangeRequest) = recovery.execute(accountId) { service.exchangeOrbs(accountId, request) }
}
