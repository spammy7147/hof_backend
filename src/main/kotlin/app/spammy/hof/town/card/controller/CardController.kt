package app.spammy.hof.town.card.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.town.card.dto.*
import app.spammy.hof.town.card.service.CardService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/town/cards")
class CardController(private val service: CardService, private val recovery: HofSessionRecoveryService) {
    @GetMapping("/identify") fun identifyPage(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.loadIdentify(accountId) }
    @PostMapping("/identify") fun identify(@CurrentAccountId accountId: Long, @Valid @RequestBody request: CardIdentifyRequest) = recovery.execute(accountId) { service.identify(accountId, request) }
    @GetMapping("/upgrade") fun upgradePage(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.loadUpgrade(accountId) }
    @PostMapping("/upgrade") fun upgrade(@CurrentAccountId accountId: Long, @Valid @RequestBody request: CardUpgradeRequest) = recovery.execute(accountId) { service.upgrade(accountId, request) }
    @GetMapping("/change") fun changePage(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.loadChange(accountId) }
    @PostMapping("/change") fun change(@CurrentAccountId accountId: Long, @Valid @RequestBody request: CardChangeRequest) = recovery.execute(accountId) { service.change(accountId, request) }
    @GetMapping("/sell") fun sellPage(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.loadSell(accountId) }
    @PostMapping("/sell") fun sell(@CurrentAccountId accountId: Long, @Valid @RequestBody request: CardSellRequest) = recovery.execute(accountId) { service.sell(accountId, request) }
    @GetMapping("/soul-echo") fun soulEchoPage(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.loadSoulEcho(accountId) }
    @PostMapping("/soul-echo") fun soulEcho(@CurrentAccountId accountId: Long, @Valid @RequestBody request: SoulEchoFuseRequest) = recovery.execute(accountId) { service.fuseSoulEcho(accountId, request) }
}
