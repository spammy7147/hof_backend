package app.spammy.hof.captcha.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path

data class StoredCaptchaImage(
    val contentType: String,
    val bytes: ByteArray,
)

/**
 * HOF 캡차 이미지를 challenge 생명주기 동안만 보관한다.
 *
 * 원본 simple-php-captcha 이미지는 같은 src를 다시 호출하면 세션 오류나 새 이미지가
 * 반환될 수 있어서, 감지 시점의 바이너리를 백엔드 파일로 고정해 앱에 내려준다.
 */
interface CaptchaImageFileStore {
    fun save(accountId: Long, challengeId: Long, contentType: String, bytes: ByteArray) =
        save(accountId, challengeId, 0, contentType, bytes)

    /**
     * 캡차 이미지 bytes와 content-type을 저장한다.
     */
    fun save(
        accountId: Long,
        challengeId: Long,
        preparationVersion: Int,
        contentType: String,
        bytes: ByteArray,
    )

    /**
     * 저장된 캡차 이미지를 읽는다. 파일이 없으면 null을 반환한다.
     */
    fun read(
        accountId: Long,
        challengeId: Long,
        preparationVersion: Int,
    ): StoredCaptchaImage?

    fun read(accountId: Long, challengeId: Long): StoredCaptchaImage? =
        read(accountId, challengeId, 0)

    /**
     * 인증 완료/실패 후 재사용하지 않을 캡차 이미지 파일을 삭제한다.
     */
    fun delete(
        accountId: Long,
        challengeId: Long,
        preparationVersion: Int,
    )

    fun delete(accountId: Long, challengeId: Long) =
        delete(accountId, challengeId, 0)
}

@Service
/**
 * 로컬 파일 시스템에 캡차 이미지를 저장하는 구현체다.
 */
class LocalCaptchaImageFileStore(
    @Value("\${hof.captcha.image-storage-dir:}")
    configuredStorageDir: String,
) : CaptchaImageFileStore {
    // 운영 설정이 없으면 OS temp가 아니라 사용자 홈 아래 temp를 쓴다.
    private val storageDir: Path = configuredStorageDir
        .trim()
        .ifBlank {
            Path.of(System.getProperty("user.home"), "temp", "hof-captcha-images").toString()
        }
        .let(Path::of)
        .toAbsolutePath()
        .normalize()

    /**
     * 이미지 파일과 content-type 메타 파일을 함께 저장한다.
     */
    override fun save(
        accountId: Long,
        challengeId: Long,
        preparationVersion: Int,
        contentType: String,
        bytes: ByteArray,
    ) {
        Files.createDirectories(storageDir)
        Files.write(imagePath(accountId, challengeId, preparationVersion), bytes)
        Files.writeString(contentTypePath(accountId, challengeId, preparationVersion), contentType)
    }

    /**
     * 이미지 파일을 읽고, content-type 파일이 없으면 기본 binary 타입을 사용한다.
     */
    override fun read(
        accountId: Long,
        challengeId: Long,
        preparationVersion: Int,
    ): StoredCaptchaImage? {
        val imagePath = imagePath(accountId, challengeId, preparationVersion)
        if (!Files.exists(imagePath)) {
            return null
        }

        val contentType = runCatching {
            Files.readString(contentTypePath(accountId, challengeId, preparationVersion)).trim()
        }.getOrNull()?.ifBlank { null } ?: DEFAULT_CONTENT_TYPE

        return StoredCaptchaImage(
            contentType = contentType,
            bytes = Files.readAllBytes(imagePath),
        )
    }

    /**
     * 이미지 파일과 content-type 메타 파일을 모두 삭제한다.
     */
    override fun delete(
        accountId: Long,
        challengeId: Long,
        preparationVersion: Int,
    ) {
        runCatching {
            Files.deleteIfExists(imagePath(accountId, challengeId, preparationVersion))
            Files.deleteIfExists(contentTypePath(accountId, challengeId, preparationVersion))
        }
    }

    /**
     * 계정과 challenge ID로 충돌 없는 이미지 파일 경로를 만든다.
     */
    private fun imagePath(
        accountId: Long,
        challengeId: Long,
        preparationVersion: Int,
    ): Path =
        storageDir.resolve("${basename(accountId, challengeId, preparationVersion)}.captcha").normalize()

    /**
     * 이미지와 짝을 이루는 content-type 메타 파일 경로를 만든다.
     */
    private fun contentTypePath(
        accountId: Long,
        challengeId: Long,
        preparationVersion: Int,
    ): Path =
        storageDir.resolve("${basename(accountId, challengeId, preparationVersion)}.content-type").normalize()

    private fun basename(
        accountId: Long,
        challengeId: Long,
        preparationVersion: Int,
    ): String =
        if (preparationVersion == 0) {
            "account-$accountId-challenge-$challengeId"
        } else {
            "account-$accountId-challenge-$challengeId-version-$preparationVersion"
        }

    private companion object {
        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }
}
