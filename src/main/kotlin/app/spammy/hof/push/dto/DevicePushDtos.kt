package app.spammy.hof.push.dto

import jakarta.validation.constraints.NotBlank

data class RegisterAndroidPushTargetRequest(
    @field:NotBlank val installationId: String,
    @field:NotBlank val nativeToken: String,
)

data class DevicePushTargetResponse(
    val id: Long,
    val platform: String,
    val installationId: String,
    val active: Boolean,
    val lastSeenAt: String,
)
