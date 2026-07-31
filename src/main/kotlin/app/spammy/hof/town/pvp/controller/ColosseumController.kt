package app.spammy.hof.town.pvp.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.town.pvp.dto.*
import app.spammy.hof.town.pvp.service.ColosseumService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/town/pvp")
class ColosseumController(private val service: ColosseumService, private val recovery: HofSessionRecoveryService) {
    @GetMapping("/colosseum") fun load(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.loadBattle(accountId) }
    @PostMapping("/colosseum/team") fun saveTeam(@CurrentAccountId accountId: Long, @Valid @RequestBody request: SaveColosseumTeamRequest) = recovery.execute(accountId) { service.saveTeam(accountId, request) }
    @PostMapping("/colosseum/challenge") fun challenge(@CurrentAccountId accountId: Long, @Valid @RequestBody request: ChallengeColosseumRequest) = recovery.execute(accountId) { service.challenge(accountId, request) }
    @GetMapping("/colosseum-shop") fun shop(@CurrentAccountId accountId: Long, @RequestParam(required = false) categoryCandidateId: String?) = recovery.execute(accountId) { if (categoryCandidateId == null) service.loadShop(accountId) else service.loadShopCategory(accountId, categoryCandidateId) }
    @PostMapping("/colosseum-shop/trade") fun trade(@CurrentAccountId accountId: Long, @Valid @RequestBody request: ColosseumTradeRequest) = recovery.execute(accountId) { service.trade(accountId, request) }
}
