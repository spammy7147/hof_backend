package app.spammy.hof.push.controller

import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.push.dto.DevicePushTargetResponse
import app.spammy.hof.push.dto.RegisterAndroidPushTargetRequest
import app.spammy.hof.push.service.DevicePushTargetService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/push/android/targets")
class DevicePushController(
    private val service: DevicePushTargetService,
) {
    @PostMapping
    fun register(
        @CurrentAccountId accountId: Long,
        @Valid @RequestBody request: RegisterAndroidPushTargetRequest,
    ): DevicePushTargetResponse = service.register(accountId, request)

    @GetMapping
    fun findAll(@CurrentAccountId accountId: Long): List<DevicePushTargetResponse> = service.findAll(accountId)

    @DeleteMapping("/{targetId}")
    fun deactivate(
        @CurrentAccountId accountId: Long,
        @PathVariable targetId: Long,
    ): ResponseEntity<Void> {
        service.deactivate(accountId, targetId)
        return ResponseEntity.noContent().build()
    }
}
