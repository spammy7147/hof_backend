package app.spammy.hof.apprelease.controller

import app.spammy.hof.apprelease.dto.AndroidReleaseResponse
import app.spammy.hof.apprelease.dto.LatestAndroidReleaseResponse
import app.spammy.hof.apprelease.dto.PublishAndroidReleaseRequest
import app.spammy.hof.apprelease.service.AppReleaseDownload
import app.spammy.hof.apprelease.service.AppReleaseService
import jakarta.validation.Valid
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/app-releases/android")
class PublicAppReleaseController(
    private val service: AppReleaseService,
) {
    @GetMapping("/latest")
    fun latest(
        @RequestParam(defaultValue = "0") currentVersionCode: Long,
    ): LatestAndroidReleaseResponse = service.latestAndroid(currentVersionCode)

    @GetMapping("/latest/download")
    fun downloadLatest(): ResponseEntity<Resource> =
        downloadResponse(service.downloadLatestAndroid(), "no-store")

    @GetMapping("/{versionCode}/download")
    fun download(
        @PathVariable versionCode: Long,
    ): ResponseEntity<Resource> =
        downloadResponse(service.downloadAndroid(versionCode), "public, max-age=31536000, immutable")

    private fun downloadResponse(
        download: AppReleaseDownload,
        cacheControl: String,
    ): ResponseEntity<Resource> =
        ResponseEntity.ok()
            .contentType(APK_MEDIA_TYPE)
            .contentLength(download.release.fileSize)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${download.release.fileName}\"")
            .header(HttpHeaders.ETAG, "\"${download.release.sha256}\"")
            .header(HttpHeaders.CACHE_CONTROL, cacheControl)
            .body(download.resource)

    companion object {
        private val APK_MEDIA_TYPE = MediaType.parseMediaType("application/vnd.android.package-archive")
    }
}

@RestController
@RequestMapping("/internal/app-releases/android")
class InternalAppReleaseController(
    private val service: AppReleaseService,
) {
    @PostMapping
    fun publish(
        @RequestHeader(name = RELEASE_TOKEN_HEADER, required = false) releaseToken: String?,
        @Valid @RequestBody request: PublishAndroidReleaseRequest,
    ): AndroidReleaseResponse = service.publishAndroid(request, releaseToken)

    companion object {
        const val RELEASE_TOKEN_HEADER = "X-HOF-Release-Token"
    }
}
