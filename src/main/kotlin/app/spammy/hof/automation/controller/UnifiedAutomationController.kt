package app.spammy.hof.automation.controller

import app.spammy.hof.automation.dto.UnifiedAutomationSettingsRequest
import app.spammy.hof.automation.dto.UnifiedAutomationStatusResponse
import app.spammy.hof.automation.service.UnifiedAutomationService
import app.spammy.hof.common.security.CurrentAccountId
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/automation/unified")
class UnifiedAutomationController(
    private val service: UnifiedAutomationService,
) {
    @GetMapping
    fun get(@CurrentAccountId accountId: Long): UnifiedAutomationStatusResponse = service.get(accountId)

    @PutMapping
    fun update(
        @CurrentAccountId accountId: Long,
        @Valid @RequestBody request: UnifiedAutomationSettingsRequest,
    ): UnifiedAutomationStatusResponse = service.update(accountId, request)

    @PostMapping("/start")
    fun start(@CurrentAccountId accountId: Long): UnifiedAutomationStatusResponse = service.start(accountId)

    @PostMapping("/pause")
    fun pause(@CurrentAccountId accountId: Long): UnifiedAutomationStatusResponse = service.pause(accountId)

    @PostMapping("/resume")
    fun resume(@CurrentAccountId accountId: Long): UnifiedAutomationStatusResponse = service.resume(accountId)

    @PostMapping("/stop")
    fun stop(@CurrentAccountId accountId: Long): UnifiedAutomationStatusResponse = service.stop(accountId)
}
