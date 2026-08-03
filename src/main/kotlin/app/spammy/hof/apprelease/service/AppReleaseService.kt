package app.spammy.hof.apprelease.service

import app.spammy.hof.apprelease.config.AppReleaseProperties
import app.spammy.hof.apprelease.dto.AndroidReleaseResponse
import app.spammy.hof.apprelease.dto.LatestAndroidReleaseResponse
import app.spammy.hof.apprelease.dto.PublishAndroidReleaseRequest
import app.spammy.hof.apprelease.entity.AppReleaseEntity
import app.spammy.hof.apprelease.repository.AppReleaseQueryRepository
import app.spammy.hof.apprelease.repository.AppReleaseRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import org.springframework.core.io.FileSystemResource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class AppReleaseDownload(
    val release: AppReleaseEntity,
    val resource: FileSystemResource,
)

@Service
class AppReleaseService(
    private val repository: AppReleaseRepository,
    private val queries: AppReleaseQueryRepository,
    private val properties: AppReleaseProperties,
    private val timeProvider: TimeProvider,
) {
    private val storageRoot: Path = Path.of(properties.storageRoot).toAbsolutePath().normalize()

    @Transactional
    fun publishAndroid(
        request: PublishAndroidReleaseRequest,
        providedToken: String?,
    ): AndroidReleaseResponse {
        verifyPublishToken(providedToken)
        val file = requireReleaseFile(request.fileName)
        val actualSize = Files.size(file)
        val requestedSha256 = request.sha256.lowercase()
        if (actualSize != request.fileSize || sha256(file) != requestedSha256) {
            throw ApiException(ErrorCode.RELEASE_CONFLICT, "APK 파일의 크기 또는 SHA-256이 등록 정보와 일치하지 않습니다.")
        }

        queries.findByPlatformAndVersionCode(ANDROID, request.versionCode)?.let { existing ->
            if (existing.matches(request, requestedSha256)) return existing.toResponse()
            throw ApiException(ErrorCode.RELEASE_CONFLICT, "이미 다른 APK가 등록된 versionCode입니다.")
        }
        queries.findByFileName(request.fileName)?.let {
            throw ApiException(ErrorCode.RELEASE_CONFLICT, "이미 다른 버전에 등록된 APK 파일명입니다.")
        }
        queries.findLatest(ANDROID)?.let { latest ->
            if (request.versionCode <= latest.versionCode) {
                throw ApiException(ErrorCode.RELEASE_CONFLICT, "versionCode는 현재 최신 버전보다 커야 합니다.")
            }
        }

        return repository.save(
            AppReleaseEntity(
                platform = ANDROID,
                versionCode = request.versionCode,
                versionName = request.versionName,
                fileName = request.fileName,
                fileSize = request.fileSize,
                sha256 = requestedSha256,
                gitRevision = request.gitRevision.lowercase(),
                jenkinsBuild = request.jenkinsBuild,
                publishedAt = timeProvider.now(),
            ),
        ).toResponse()
    }

    @Transactional(readOnly = true)
    fun latestAndroid(currentVersionCode: Long): LatestAndroidReleaseResponse {
        if (currentVersionCode < 0) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "currentVersionCode는 0 이상이어야 합니다.")
        }
        val latest = queries.findLatest(ANDROID)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "등록된 Android APK가 없습니다.")
        return LatestAndroidReleaseResponse(
            updateAvailable = latest.versionCode > currentVersionCode,
            release = latest.toResponse(),
        )
    }

    @Transactional(readOnly = true)
    fun downloadAndroid(versionCode: Long): AppReleaseDownload {
        val release = queries.findByPlatformAndVersionCode(ANDROID, versionCode)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "요청한 Android APK 버전을 찾을 수 없습니다.")
        return release.toDownload()
    }

    @Transactional(readOnly = true)
    fun downloadLatestAndroid(): AppReleaseDownload {
        val release = queries.findLatest(ANDROID)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "등록된 Android APK가 없습니다.")
        return release.toDownload()
    }

    private fun AppReleaseEntity.toDownload(): AppReleaseDownload {
        val file = requireReleaseFile(fileName)
        if (Files.size(file) != fileSize) {
            throw ApiException(ErrorCode.RELEASE_CONFLICT, "저장된 APK 파일 크기가 등록 정보와 일치하지 않습니다.")
        }
        return AppReleaseDownload(this, FileSystemResource(file))
    }

    private fun verifyPublishToken(providedToken: String?) {
        val expected = properties.publishToken
        val matches = expected.isNotBlank() && providedToken != null && MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            providedToken.toByteArray(Charsets.UTF_8),
        )
        if (!matches) {
            throw ApiException(ErrorCode.RELEASE_PUBLISH_UNAUTHORIZED, "유효한 앱 릴리스 등록 토큰이 필요합니다.")
        }
    }

    private fun requireReleaseFile(fileName: String): Path {
        if (!FILE_NAME.matches(fileName)) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "유효하지 않은 APK 파일명입니다.")
        }
        val file = storageRoot.resolve(fileName).normalize()
        if (file.parent != storageRoot || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "APK 파일을 저장소에서 찾을 수 없습니다.")
        }
        return file
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun AppReleaseEntity.matches(
        request: PublishAndroidReleaseRequest,
        normalizedSha256: String,
    ): Boolean =
        versionName == request.versionName &&
            fileName == request.fileName &&
            fileSize == request.fileSize &&
            sha256 == normalizedSha256 &&
            gitRevision == request.gitRevision.lowercase() &&
            jenkinsBuild == request.jenkinsBuild

    private fun AppReleaseEntity.toResponse(): AndroidReleaseResponse =
        AndroidReleaseResponse(
            versionCode = versionCode,
            versionName = versionName,
            fileSize = fileSize,
            sha256 = sha256,
            gitRevision = gitRevision,
            jenkinsBuild = jenkinsBuild,
            publishedAt = publishedAt,
            downloadUrl = "/api/app-releases/android/$versionCode/download",
        )

    companion object {
        private const val ANDROID = "ANDROID"
        private val FILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,250}\\.apk$")
    }
}
