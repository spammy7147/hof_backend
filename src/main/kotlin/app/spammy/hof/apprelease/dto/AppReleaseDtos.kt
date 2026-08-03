package app.spammy.hof.apprelease.dto

import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant

data class PublishAndroidReleaseRequest(
    @field:Positive
    val versionCode: Long,
    @field:Size(min = 1, max = 100)
    val versionName: String,
    @field:Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,250}\\.apk$")
    val fileName: String,
    @field:Positive
    val fileSize: Long,
    @field:Pattern(regexp = "^[0-9a-fA-F]{64}$")
    val sha256: String,
    @field:Pattern(regexp = "^[0-9a-fA-F]{40}$")
    val gitRevision: String,
    @field:Positive
    val jenkinsBuild: Long,
)

data class AndroidReleaseResponse(
    val versionCode: Long,
    val versionName: String,
    val fileSize: Long,
    val sha256: String,
    val gitRevision: String,
    val jenkinsBuild: Long,
    val publishedAt: Instant,
    val downloadUrl: String,
)

data class LatestAndroidReleaseResponse(
    val updateAvailable: Boolean,
    val release: AndroidReleaseResponse,
)
