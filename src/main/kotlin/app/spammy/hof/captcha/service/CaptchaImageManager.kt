package app.spammy.hof.captcha.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.client.HofBinaryGateway
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 캡차 원본 이미지 다운로드와 challenge별 임시 파일 생명주기를 관리한다.
 *
 * HOF의 이미지 URL은 PHP session 안의 `_CAPTCHA` 값과 결합돼 있어 나중에 같은 URL을 다시
 * 호출해도 동일한 이미지라는 보장이 없다. 따라서 경찰서 form을 읽은 시점의 쿠키로 즉시 내려받고,
 * DB transaction이 commit된 뒤에만 파일을 생성·교체·삭제해 DB와 파일 상태가 어긋나지 않게 한다.
 */
@Service
class CaptchaImageManager(
    private val binaryGateway: HofBinaryGateway,
    private val fileStore: CaptchaImageFileStore,
) {
    /** 저장된 이미지를 읽으며 파일 I/O 오류는 사용자에게 전달할 API 오류로 변환한다. */
    fun readStored(
        accountId: Long,
        challengeId: Long,
    ): CaptchaImageResponse? =
        runCatching { fileStore.read(accountId, challengeId) }
            .getOrElse { error ->
                throw ApiException(ErrorCode.HOF_REQUEST_FAILED, "저장된 캡차 이미지를 읽지 못했습니다.", error)
            }
            ?.let { stored -> CaptchaImageResponse(normalizeContentType(stored.contentType), stored.bytes) }

    /** 저장 파일 존재 여부를 확인한다. 읽기 실패는 숨기지 않아 손상된 파일을 정상 상태로 오인하지 않는다. */
    fun exists(
        accountId: Long,
        challengeId: Long,
    ): Boolean = fileStore.read(accountId, challengeId) != null

    /**
     * 저장 파일이 없을 때만 사용하는 원본 fallback이다.
     *
     * 쿠키가 없거나 HOF가 HTML Notice를 반환하면 이미지로 전달하지 않고 명확한 API 오류를 발생시킨다.
     */
    fun downloadRequired(
        imageUrl: String,
        cookies: Map<String, String>,
    ): CaptchaImageResponse {
        if (cookies.isEmpty()) {
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "저장된 HOF 로그인 쿠키가 없습니다.")
        }

        val response = runCatching { binaryGateway.get(imageUrl, cookies) }
            .getOrElse { error ->
                throw ApiException(ErrorCode.HOF_REQUEST_FAILED, "HOF 캡차 이미지를 불러오지 못했습니다.", error)
            }
        if (response.statusCode !in 200..299) {
            throw ApiException(
                ErrorCode.HOF_REQUEST_FAILED,
                "HOF 캡차 이미지를 불러오지 못했습니다. status=${response.statusCode}",
            )
        }
        if (isHtmlContentType(response.contentType)) {
            throw ApiException(ErrorCode.HOF_REQUEST_FAILED, "HOF 캡차 이미지가 HTML로 응답했습니다.")
        }
        return CaptchaImageResponse(normalizeContentType(response.contentType), response.body)
    }

    /** 신규 challenge 파일은 DB row가 commit된 뒤에만 저장한다. */
    fun saveAfterCommit(
        accountId: Long,
        challengeId: Long,
        imageUrl: String?,
        cookies: Map<String, String>,
    ) {
        afterCommit { storeIfPossible(accountId, challengeId, imageUrl, cookies) }
    }

    /** 기존 이미지는 metadata 교체가 commit된 뒤 삭제하고 새 이미지를 저장한다. */
    fun replaceAfterCommit(
        accountId: Long,
        challengeId: Long,
        imageUrl: String?,
        cookies: Map<String, String>,
    ) {
        afterCommit {
            deleteImmediately(accountId, challengeId)
            storeIfPossible(accountId, challengeId, imageUrl, cookies)
        }
    }

    /** 성공하거나 중복 정리된 challenge 파일을 DB commit 이후 삭제한다. */
    fun deleteAfterCommit(
        accountId: Long,
        challengeId: Long,
    ) {
        afterCommit { deleteImmediately(accountId, challengeId) }
    }

    /** 외부 요청 예외처럼 transaction 결과와 무관하게 폐기해야 하는 이미지를 즉시 삭제한다. */
    fun deleteImmediately(
        accountId: Long,
        challengeId: Long,
    ) {
        fileStore.delete(accountId, challengeId)
    }

    private fun storeIfPossible(
        accountId: Long,
        challengeId: Long,
        imageUrl: String?,
        cookies: Map<String, String>,
    ) {
        val normalizedImageUrl = imageUrl?.trim()?.ifBlank { null } ?: return
        if (cookies.isEmpty()) return

        val response = runCatching { binaryGateway.get(normalizedImageUrl, cookies) }.getOrNull() ?: return
        if (response.statusCode !in 200..299 || isHtmlContentType(response.contentType)) return

        runCatching {
            fileStore.save(
                accountId = accountId,
                challengeId = challengeId,
                contentType = normalizeContentType(response.contentType),
                bytes = response.body,
            )
        }
    }

    private fun afterCommit(action: () -> Unit) {
        if (
            !TransactionSynchronizationManager.isActualTransactionActive() ||
            !TransactionSynchronizationManager.isSynchronizationActive()
        ) {
            action()
            return
        }

        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    runCatching(action)
                }
            },
        )
    }

    private fun isHtmlContentType(contentType: String?): Boolean =
        contentType
            ?.substringBefore(";")
            ?.trim()
            ?.equals("text/html", ignoreCase = true) == true

    private fun normalizeContentType(contentType: String?): String =
        runCatching {
            contentType
                ?.substringBefore(";")
                ?.trim()
                ?.ifBlank { null }
                ?.let(MediaType::parseMediaType)
                ?.takeIf { mediaType -> mediaType.type.equals("image", ignoreCase = true) }
                ?.toString()
        }.getOrNull()?.substringBefore(";") ?: DEFAULT_BINARY_CONTENT_TYPE

    private companion object {
        const val DEFAULT_BINARY_CONTENT_TYPE = "application/octet-stream"
    }
}
