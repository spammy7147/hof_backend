package app.spammy.hof.town.home.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.town.home.dto.HomeActionRequest
import app.spammy.hof.town.home.model.HomeMode
import app.spammy.hof.town.home.service.HomeService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/town")
class HomeController(private val service: HomeService, private val recovery: HofSessionRecoveryService) {
    @GetMapping("/home") fun home(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.load(accountId, HomeMode.HOME) }
    @PostMapping("/home/quests") fun homeQuest(@CurrentAccountId accountId: Long, @Valid @RequestBody request: HomeActionRequest) = recovery.execute(accountId) { service.runHomeQuest(accountId, request.actionId) }
    @GetMapping("/rest") fun rest(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.load(accountId, HomeMode.REST) }
    @PostMapping("/rest/restore") fun restore(@CurrentAccountId accountId: Long, @Valid @RequestBody request: HomeActionRequest) = recovery.execute(accountId) { service.restore(accountId, request.actionId) }
}
