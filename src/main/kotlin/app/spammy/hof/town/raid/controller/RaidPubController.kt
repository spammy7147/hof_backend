package app.spammy.hof.town.raid.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.town.raid.dto.RaidPubActionRequest
import app.spammy.hof.town.raid.service.RaidPubService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/town/raid")
class RaidPubController(
    private val service: RaidPubService,
    private val recovery: HofSessionRecoveryService,
) {
    @GetMapping fun load(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.load(accountId) }
    @PostMapping("/actions") fun action(@CurrentAccountId accountId: Long, @Valid @RequestBody request: RaidPubActionRequest) =
        recovery.execute(accountId) { service.action(accountId, request) }
}
