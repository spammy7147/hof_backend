package app.spammy.hof.automation.controller

import app.spammy.hof.automation.dto.SaveAutomationPresetRequest
import app.spammy.hof.automation.dto.ApplyAutomationPresetRequest
import app.spammy.hof.automation.service.AutomationPresetService
import app.spammy.hof.common.security.CurrentAccountId
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/automation/unified/presets")
class AutomationPresetController(private val service: AutomationPresetService) {
    @GetMapping
    fun list(@CurrentAccountId accountId: Long) = service.list(accountId)

    @GetMapping("/{presetId}")
    fun get(@CurrentAccountId accountId: Long, @PathVariable presetId: Long) = service.get(accountId, presetId)

    @GetMapping("/applied")
    fun applied(@CurrentAccountId accountId: Long) = service.getApplied(accountId)

    @PostMapping
    fun create(@CurrentAccountId accountId: Long, @Valid @RequestBody request: SaveAutomationPresetRequest) =
        service.save(accountId, request)

    @PutMapping("/{presetId}")
    fun overwrite(@CurrentAccountId accountId: Long, @PathVariable presetId: Long,
        @Valid @RequestBody request: SaveAutomationPresetRequest) = service.save(accountId, request, presetId)

    @DeleteMapping("/{presetId}")
    fun delete(@CurrentAccountId accountId: Long, @PathVariable presetId: Long) = service.delete(accountId, presetId)

    @PostMapping("/{presetId}/apply")
    fun apply(@CurrentAccountId accountId: Long, @PathVariable presetId: Long,
        @Valid @RequestBody request: ApplyAutomationPresetRequest) = service.applyWithDetail(accountId, presetId, request.revision)
}
