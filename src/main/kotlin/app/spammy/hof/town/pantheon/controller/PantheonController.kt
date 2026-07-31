package app.spammy.hof.town.pantheon.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.town.pantheon.dto.PantheonActionRequest
import app.spammy.hof.town.pantheon.service.PantheonService
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

@Validated
@RestController
@RequestMapping("/api/town/pantheon")
class PantheonController(
    private val service: PantheonService,
    private val recovery: HofSessionRecoveryService,
) {
    @GetMapping fun street(@CurrentAccountId accountId: Long) = recovery.execute(accountId) { service.street(accountId) }

    @GetMapping("/{shrineId}")
    fun detail(
        @CurrentAccountId accountId: Long,
        @PathVariable @Pattern(regexp = "[0-9a-f]{32}") shrineId: String,
    ) = recovery.execute(accountId) { service.detail(accountId, shrineId) }

    @PostMapping("/{shrineId}/actions")
    fun action(
        @CurrentAccountId accountId: Long,
        @PathVariable @Pattern(regexp = "[0-9a-f]{32}") shrineId: String,
        @Valid @RequestBody request: PantheonActionRequest,
    ) = recovery.execute(accountId) { service.action(accountId, shrineId, request) }
}
