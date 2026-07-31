package app.spammy.hof.town.exchange.controller

import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.town.exchange.dto.*
import app.spammy.hof.town.exchange.model.ExchangeMode
import app.spammy.hof.town.exchange.service.ExchangeService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/town/exchanges")
class ExchangeController(private val service: ExchangeService, private val recovery: HofSessionRecoveryService) {
    @GetMapping("/{mode}")
    fun load(@CurrentAccountId accountId: Long, @PathVariable mode: String, @RequestParam(required = false) categoryCandidateId: String?) =
        recovery.execute(accountId) {
            val parsed = mode(mode)
            if (categoryCandidateId == null) service.load(accountId, parsed) else service.loadCategory(accountId, parsed, categoryCandidateId)
        }

    @PostMapping("/{mode}/trade")
    fun trade(@CurrentAccountId accountId: Long, @PathVariable mode: String, @Valid @RequestBody request: ExchangeTradeRequest) =
        recovery.execute(accountId) { service.trade(accountId, mode(mode), request) }

    @PostMapping("/legacy/grade")
    fun legacyGrade(@CurrentAccountId accountId: Long, @Valid @RequestBody request: LegacyGradeExchangeRequest) =
        recovery.execute(accountId) { service.exchangeLegacyGrade(accountId, request) }

    @PostMapping("/ann/action")
    fun ann(@CurrentAccountId accountId: Long, @Valid @RequestBody request: AnnActionRequest) =
        recovery.execute(accountId) { service.annAction(accountId, request) }

    private fun mode(value: String) = when (value) {
        "emblem" -> ExchangeMode.EMBLEM
        "event" -> ExchangeMode.EVENT
        "legacy" -> ExchangeMode.LEGACY
        "ann" -> ExchangeMode.ANN
        else -> throw app.spammy.hof.common.error.ApiException(app.spammy.hof.common.error.ErrorCode.INVALID_REQUEST, "지원하지 않는 교환 시설입니다.")
    }
}
