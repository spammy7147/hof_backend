package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.account.repository.HofCookieRepository
import app.spammy.hof.account.service.HofCookieCipher
import app.spammy.hof.captcha.dto.CaptchaChallengeResponse
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.captcha.entity.CaptchaFormFieldEntity
import app.spammy.hof.captcha.repository.CaptchaChallengeRepository
import app.spammy.hof.captcha.repository.CaptchaFormFieldCommandRepository
import app.spammy.hof.captcha.repository.CaptchaQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.isHofControlSignal
import app.spammy.hof.external.client.rethrowIfHofControlSignal
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.external.parser.LoginStateParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager

data class CaptchaImageResponse(
    val contentType: String,
    val bytes: ByteArray,
)

@Service
/**
 * 캡차/자경단 통행증 감지, 이미지 저장, 답안 제출을 담당한다.
 */
class CaptchaService(
    private val captchaChallengeRepository: CaptchaChallengeRepository,
    private val captchaFormFieldRepository: CaptchaFormFieldCommandRepository,
    private val captchaQueryRepository: CaptchaQueryRepository,
    private val cookieRepository: HofCookieRepository,
    private val cookieQueryRepository: CookieQueryRepository,
    private val cookieCipher: HofCookieCipher,
    private val gateway: AccountHofGateway,
    private val challengeParser: CaptchaChallengeParser,
    private val loginStateParser: LoginStateParser,
    private val imageManager: CaptchaImageManager,
    private val timeProvider: TimeProvider,
    private val automationHook: CaptchaAutomationHook? = null,
) {
    /**
     * HOF 응답 HTML에서 캡차/자경단 통행증 신호만 찾아 detected challenge로 저장한다.
     *
     * 감지 시점에는 경찰서 페이지나 이미지에 접근하지 않는다. 신호 확인 뒤 계정 row를
     * `PESSIMISTIC_WRITE`로 잠그고 commit까지 유지하므로 같은 계정의 동시 감지는 직렬화된다.
     * 기존 active challenge는 같은 ID로 초기화하고 과거 중복 active challenge는 삭제해 답변 완료 뒤
     * 오래된 challenge가 다시 current로 나타나지 않게 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun detectAndRecord(
        account: HofAccountEntity,
        html: String,
        sourceUrl: String,
    ): CaptchaChallengeResponse? {
        val document = Jsoup.parse(html, sourceUrl)
        val pageText = document.text().trim()
        if (!challengeParser.hasCaptchaSignal(document, pageText)) {
            return null
        }

        val lockedAccount = captchaQueryRepository.findAccountByIdForUpdate(account.id)
            ?: account.takeUnless { isTransactionActive() }
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캡차를 저장할 계정을 찾지 못했습니다.")
        val activeChallenges = captchaQueryRepository.findActiveByAccountId(lockedAccount.id)
        val existingChallenge = activeChallenges.firstOrNull()
        val prompt = if (pageText.contains(VIGILANTE_PASS_PROMPT)) {
            VIGILANTE_PASS_PROMPT
        } else {
            DEFAULT_PROMPT
        }
        val detectedAt = timeProvider.now()

        val savedChallenge = if (existingChallenge == null) {
            captchaChallengeRepository.save(
                CaptchaChallengeEntity(
                    account = lockedAccount,
                    status = STATUS_DETECTED,
                    prompt = prompt,
                    imageUrl = null,
                    sourceUrl = sourceUrl,
                    answer = null,
                    createdAt = detectedAt,
                    answeredAt = null,
                    submitUrl = null,
                    submitMethod = "POST",
                    answerFieldName = CaptchaChallengeParser.DEFAULT_ANSWER_FIELD,
                    preparationVersion = 0,
                ),
            )
        } else {
            val previousVersion = existingChallenge.preparationVersion
            existingChallenge.status = STATUS_DETECTED
            existingChallenge.prompt = prompt
            existingChallenge.imageUrl = null
            existingChallenge.sourceUrl = sourceUrl
            existingChallenge.answer = null
            existingChallenge.answeredAt = null
            existingChallenge.createdAt = detectedAt
            existingChallenge.submitUrl = null
            existingChallenge.submitMethod = "POST"
            existingChallenge.answerFieldName = CaptchaChallengeParser.DEFAULT_ANSWER_FIELD
            existingChallenge.preparationVersion = 0
            replaceFormFields(existingChallenge, emptyList())
            imageManager.deleteAfterCommit(existingChallenge.account.id, existingChallenge.id, previousVersion)
            existingChallenge
        }

        val staleChallenges = activeChallenges.drop(1)
        if (staleChallenges.isNotEmpty()) {
            captchaChallengeRepository.deleteAll(staleChallenges)
            staleChallenges.forEach { stale ->
                imageManager.deleteAfterCommit(stale.account.id, stale.id, stale.preparationVersion)
            }
        }

        automationHook?.detected(savedChallenge)

        return savedChallenge.toResponse()
    }

    @Transactional
    fun prepareCurrent(accountId: Long): CaptchaChallengeResponse {
        val account = captchaQueryRepository.findAccountByIdForUpdate(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val challenge = captchaQueryRepository.findLatestActiveByAccountId(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "대기 중인 캡차가 없습니다.")
        val storedCookies = cookieQueryRepository.findByAccountId(accountId)
        if (storedCookies.isEmpty()) {
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "저장된 HOF 로그인 쿠키가 없습니다.")
        }
        val cookies = storedCookies.associate { cookie -> cookie.name to cookieCipher.decrypt(cookie.value) }
        val policeUrl = challengeParser.buildPoliceUrl(challenge.sourceUrl)
        val response = gateway.execute(account.id, HofRequest(HofHttpMethod.GET, policeUrl), cookies)
        val login = loginStateParser.parse(response.body)
        if (login.hasLoginForm && !login.isLoggedIn) {
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "HOF 로그인 세션이 만료되었습니다.")
        }
        if (response.statusCode !in 200..299) {
            throw ApiException(ErrorCode.HOF_REQUEST_FAILED, "HOF 캡차 화면을 불러오지 못했습니다.")
        }

        val activeCookies = mergeResponseCookies(account, storedCookies, response.setCookies)
        val responseUrl = response.finalUrl.ifBlank { policeUrl }
        val document = Jsoup.parse(response.body, responseUrl)
        val pageText = document.text().trim()
        if (challengeParser.isCaptchaSuccessPage(pageText)) {
            challenge.status = STATUS_ANSWERED
            challenge.answer = null
            challenge.answeredAt = timeProvider.now()
            imageManager.deleteAfterCommit(accountId, challenge.id, challenge.preparationVersion)
            automationHook?.answered(challenge)
            return challenge.toResponse()
        }
        val hasSimpleCaptcha = response.body.contains(
            CaptchaChallengeParser.SIMPLE_CAPTCHA_SCRIPT,
            ignoreCase = true,
        )
        val metadata = challengeParser.extractDocumentMetadata(
            document = document,
            pageText = pageText,
            sourceUrl = responseUrl,
            defaultAnswerField = if (hasSimpleCaptcha) {
                CaptchaChallengeParser.SIMPLE_CAPTCHA_ANSWER_FIELD
            } else {
                CaptchaChallengeParser.DEFAULT_ANSWER_FIELD
            },
            fallbackImageUrl = if (hasSimpleCaptcha) {
                challengeParser.buildSimpleCaptchaImageUrl(responseUrl)
            } else {
                null
            },
            fallbackSubmitField = if (hasSimpleCaptcha) {
                CaptchaChallengeParser.SIMPLE_CAPTCHA_SUBMIT_FIELD to CaptchaChallengeParser.SIMPLE_CAPTCHA_SUBMIT_VALUE
            } else {
                null
            },
        )
        val imageUrl = metadata.imageUrl
            ?: throw ApiException(ErrorCode.CAPTCHA_PREPARATION_FAILED, "최신 캡차를 준비하지 못했습니다.")
        val previousVersion = challenge.preparationVersion
        val nextVersion = previousVersion + 1
        imageManager.storePrepared(
            accountId = accountId,
            origin = HofRequestOrigin.INTERACTIVE,
            challengeId = challenge.id,
            preparationVersion = nextVersion,
            imageUrl = imageUrl,
            cookies = activeCookies,
        )
        deletePreparedVersionAfterRollback(accountId, challenge.id, nextVersion)

        challenge.applyChallengeMetadata(metadata.copy(prompt = challenge.prompt))
        replaceFormFields(challenge, metadata.formFields)
        challenge.status = STATUS_READY
        challenge.preparationVersion = nextVersion
        imageManager.deleteAfterCommit(accountId, challenge.id, previousVersion)
        return challenge.toResponse()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun invalidateCurrentPreparation(accountId: Long) {
        captchaQueryRepository.findAccountByIdForUpdate(accountId) ?: return
        val challenge = captchaQueryRepository.findLatestActiveByAccountId(accountId) ?: return
        if (challenge.status != STATUS_READY) return

        val previousVersion = challenge.preparationVersion
        resetPreparation(challenge)
        imageManager.deleteAfterCommit(accountId, challenge.id, previousVersion)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recoverConsumedPreparation(
        accountId: Long,
        challengeId: Long,
        consumedPreparationVersion: Int,
        responseSetCookies: Map<String, String>,
    ) {
        val account = captchaQueryRepository.findAccountByIdForUpdate(accountId) ?: return
        val storedCookies = cookieQueryRepository.findByAccountId(accountId)
        mergeResponseCookies(account, storedCookies, responseSetCookies)

        val challenge = captchaQueryRepository.findOwnedByAccountIdAndIdForUpdate(accountId, challengeId) ?: return
        if (challenge.status != STATUS_READY || challenge.preparationVersion != consumedPreparationVersion) return

        resetPreparation(challenge)
        imageManager.deleteAfterCommit(accountId, challenge.id, consumedPreparationVersion)
    }

    /**
     * 현재 사용자에게 보여줄 active challenge를 외부 호출 없이 반환한다.
     */
    @Transactional
    fun findCurrent(accountId: Long): CaptchaChallengeResponse? {
        val challenge = captchaQueryRepository
            .findLatestActiveByAccountId(accountId)
            ?: return null

        return challenge.toResponse()
    }

    /**
     * 앱 `<Image>`가 읽을 캡차 바이너리를 반환한다.
     *
     * 준비 요청에서 저장한 정확한 버전의 로컬 파일만 반환한다.
     */
    @Transactional
    fun loadImage(
        accountId: Long,
        challengeId: Long,
        preparationVersion: Int = 0,
    ): CaptchaImageResponse {
        val challenge = captchaQueryRepository
            .findOwnedByAccountIdAndIdAndStatus(accountId, challengeId, STATUS_READY)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캡차 이미지를 찾지 못했습니다.")

        if (challenge.preparationVersion != preparationVersion) {
            throw ApiException(ErrorCode.CAPTCHA_STALE, "캡차가 갱신되었습니다. 최신 이미지를 다시 확인해 주세요.")
        }
        return imageManager.readStored(accountId, challengeId, preparationVersion)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캡차 이미지를 찾지 못했습니다.")
    }

    /**
     * 사용자가 입력한 답안을 HOF 원본 form에 맞춰 제출한다.
     *
     * challenge row를 비관적으로 잠근 뒤 READY 상태와 준비 버전을 검사하므로 동시 제출 중 하나만 HOF를 호출한다.
     * 첫 제출의 제어 신호는 준비 상태를 보존하고 일반 transport 실패만 이미지를 즉시 삭제한다. 제출 성공 뒤
     * 경찰 후속 조회의 제어 신호는 marker로 transaction을 먼저 unwind한 뒤 controller가 준비 상태를 무효화한다.
     */
    @Transactional
    fun submitAnswer(
        accountId: Long,
        challengeId: Long,
        answer: String,
        preparationVersion: Int,
    ): CaptchaChallengeResponse {
        val normalizedAnswer = answer.trim()
        if (normalizedAnswer.isBlank()) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "캡차 답안을 입력해야 합니다.")
        }

        val lockedChallenge = captchaQueryRepository.findOwnedByAccountIdAndIdForUpdate(accountId, challengeId)
        val nonTransactionalFallback = if (!isTransactionActive()) {
            captchaQueryRepository.findOwnedByAccountIdAndId(accountId, challengeId)
        } else {
            null
        }
        val challenge = lockedChallenge ?: nonTransactionalFallback
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "캡차 대기 항목을 찾지 못했습니다.")
        if (challenge.status != STATUS_READY) {
            throw ApiException(ErrorCode.INVALID_REQUEST, "입력할 준비가 된 캡차만 제출할 수 있습니다.")
        }
        if (challenge.preparationVersion != preparationVersion) {
            throw ApiException(ErrorCode.CAPTCHA_STALE, "캡차가 갱신되었습니다. 최신 이미지를 다시 확인해 주세요.")
        }

        val storedCookies = cookieQueryRepository.findByAccountId(accountId)
        val cookies = storedCookies.associate { cookie -> cookie.name to cookieCipher.decrypt(cookie.value) }
        if (cookies.isEmpty()) {
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "저장된 HOF 로그인 쿠키가 없습니다.")
        }

        val request = HofRequest(
            method = if (challenge.submitMethod.equals("GET", ignoreCase = true)) {
                HofHttpMethod.GET
            } else {
                HofHttpMethod.POST
            },
            url = challenge.submitUrl?.trim()?.ifBlank { null } ?: challenge.sourceUrl,
            formFields = buildSubmittedFormFields(
                challenge = challenge,
                storedFields = captchaQueryRepository.findFormFields(challenge.id),
                answer = normalizedAnswer,
            ),
        )
        val response = runCatching {
            gateway.execute(challenge.account.id, request, cookies)
        }.getOrElse { error ->
            error.rethrowIfHofControlSignal()
            imageManager.deleteImmediately(challenge.account.id, challenge.id, challenge.preparationVersion)
            throw ApiException(ErrorCode.HOF_REQUEST_FAILED, "HOF 캡차 답안 제출에 실패했습니다.", error)
        }
        val activeCookies = mergeResponseCookies(
            account = challenge.account,
            storedCookies = storedCookies,
            setCookies = response.setCookies,
        )

        val responseUrl = response.finalUrl.ifBlank { request.url }
        val login = loginStateParser.parse(response.body)
        if (login.hasLoginForm && !login.isLoggedIn) {
            throw ApiException(ErrorCode.HOF_SESSION_EXPIRED, "HOF 로그인 세션이 만료되었습니다.")
        }
        val document = Jsoup.parse(response.body, responseUrl)
        val pageText = document.text().trim()
        if (!challengeParser.isCaptchaSuccessPage(pageText) && challengeParser.hasCaptchaSignal(document, pageText)) {
            val metadata = try {
                extractChallengeMetadata(challenge.account, document, pageText, responseUrl)
            } catch (error: Throwable) {
                if (error.isHofControlSignal()) {
                    throw CaptchaPreparationConsumedException.from(error, response.setCookies)
                }
                throw error
            }
            val imageUrl = metadata.imageUrl
                ?: throw ApiException(ErrorCode.CAPTCHA_PREPARATION_FAILED, "새 캡차를 준비하지 못했습니다.")
            val previousVersion = challenge.preparationVersion
            val nextVersion = previousVersion + 1
            imageManager.storePrepared(
                accountId = challenge.account.id,
                origin = HofRequestOrigin.INTERACTIVE,
                challengeId = challenge.id,
                preparationVersion = nextVersion,
                imageUrl = imageUrl,
                cookies = metadata.imageCookies ?: activeCookies,
            )
            deletePreparedVersionAfterRollback(challenge.account.id, challenge.id, nextVersion)
            challenge.status = STATUS_READY
            challenge.answer = null
            challenge.answeredAt = null
            challenge.applyChallengeMetadata(metadata)
            replaceFormFields(challenge, metadata.formFields)
            challenge.preparationVersion = nextVersion
            imageManager.deleteAfterCommit(challenge.account.id, challenge.id, previousVersion)
            return challenge.toResponse()
        }

        challenge.status = STATUS_ANSWERED
        challenge.answer = normalizedAnswer
        challenge.answeredAt = timeProvider.now()
        imageManager.deleteAfterCommit(challenge.account.id, challenge.id, challenge.preparationVersion)
        automationHook?.answered(challenge)

        return challenge.toResponse()
    }

    /**
     * 캡차 metadata를 추출한다.
     *
     * 자경단 통행증 게이트는 전투 응답 본문이 아니라 `menu=police` 화면의 form과
     * `simple-php-captcha` 이미지를 기준으로 처리한다.
     */
    private fun extractChallengeMetadata(
        account: HofAccountEntity,
        document: Element,
        pageText: String,
        sourceUrl: String,
    ): CaptchaChallengeMetadata {
        val metadata = challengeParser.extractDocumentMetadata(
            document = document,
            pageText = pageText,
            sourceUrl = sourceUrl,
        )
        if (!challengeParser.isVigilantePassGate(document, pageText)) {
            return metadata
        }

        return fetchPoliceChallengeMetadata(
            account = account,
            sourceUrl = sourceUrl,
            prompt = metadata.prompt,
        ) ?: metadata
    }

    /**
     * 현재 HTML 문서 안에서 캡차 form, 이미지, 답안 input, 제출 URL 정보를 추출한다.
     */
    /**
     * `자경단에서 통행증...` 문구만 있는 화면에서 실제 캡차가 있는 경찰서 페이지를 호출한다.
     */
    private fun fetchPoliceChallengeMetadata(
        account: HofAccountEntity,
        sourceUrl: String,
        prompt: String,
    ): CaptchaChallengeMetadata? {
        val storedCookies = cookieQueryRepository.findByAccountId(account.id)
        val cookies = storedCookies.associate { cookie -> cookie.name to cookieCipher.decrypt(cookie.value) }
        if (cookies.isEmpty()) {
            return null
        }

        val policeUrl = challengeParser.buildPoliceUrl(sourceUrl)
        val response = runCatching {
            gateway.execute(
                account.id,
                HofRequest(
                    method = HofHttpMethod.GET,
                    url = policeUrl,
                ),
                cookies,
            )
        }.getOrElse { error ->
            error.rethrowIfHofControlSignal()
            return null
        }
        if (response.statusCode !in 200..299) {
            return null
        }
        val activeCookies = mergeResponseCookies(
            account = account,
            storedCookies = storedCookies,
            setCookies = response.setCookies,
        )

        val responseUrl = response.finalUrl.ifBlank { policeUrl }
        val policeDocument = Jsoup.parse(response.body, responseUrl)
        val policePageText = policeDocument.text().trim()
        val hasSimpleCaptcha = response.body.contains(
            CaptchaChallengeParser.SIMPLE_CAPTCHA_SCRIPT,
            ignoreCase = true,
        )
        val metadata = challengeParser.extractDocumentMetadata(
            document = policeDocument,
            pageText = policePageText,
            sourceUrl = responseUrl,
            defaultAnswerField = if (hasSimpleCaptcha) {
                CaptchaChallengeParser.SIMPLE_CAPTCHA_ANSWER_FIELD
            } else {
                CaptchaChallengeParser.DEFAULT_ANSWER_FIELD
            },
            fallbackImageUrl = if (hasSimpleCaptcha) {
                challengeParser.buildSimpleCaptchaImageUrl(responseUrl)
            } else {
                null
            },
            fallbackSubmitField = if (hasSimpleCaptcha) {
                CaptchaChallengeParser.SIMPLE_CAPTCHA_SUBMIT_FIELD to
                    CaptchaChallengeParser.SIMPLE_CAPTCHA_SUBMIT_VALUE
            } else {
                null
            },
        )

        return metadata.imageUrl?.let {
            metadata.copy(
                prompt = prompt,
                imageCookies = activeCookies,
            )
        }
    }

    /**
     * HOF 응답 Set-Cookie를 DB 쿠키와 합쳐 이후 요청에 사용할 최신 쿠키 Map을 만든다.
     */
    private fun mergeResponseCookies(
        account: HofAccountEntity,
        storedCookies: List<HofCookieEntity>,
        setCookies: Map<String, String>,
    ): Map<String, String> {
        val currentCookies = storedCookies.associate { cookie -> cookie.name to cookieCipher.decrypt(cookie.value) }
        if (setCookies.isEmpty()) {
            return currentCookies
        }

        val now = timeProvider.now()
        val cookiesByName = storedCookies.associateBy { cookie -> cookie.name }
        setCookies.forEach { (name, value) ->
            val existingCookie = cookiesByName[name]
            if (existingCookie != null) {
                existingCookie.value = cookieCipher.encrypt(value)
                existingCookie.updatedAt = now
            } else {
                cookieRepository.save(
                    HofCookieEntity(
                        account = account,
                        name = name,
                        value = cookieCipher.encrypt(value),
                        domain = "sic.zerosic.com",
                        path = "/ZeroHOF",
                        updatedAt = now,
                    ),
                )
            }
        }

        return currentCookies + setCookies
    }

    /**
     * 새로 찾은 form/image metadata를 기존 challenge row에 반영한다.
     */
    private fun CaptchaChallengeEntity.applyChallengeMetadata(metadata: CaptchaChallengeMetadata) {
        prompt = metadata.prompt
        imageUrl = metadata.imageUrl
        sourceUrl = metadata.sourceUrl
        submitUrl = metadata.submitUrl
        submitMethod = metadata.submitMethod
        answerFieldName = metadata.answerFieldName
    }

    private fun resetPreparation(challenge: CaptchaChallengeEntity) {
        challenge.status = STATUS_DETECTED
        challenge.imageUrl = null
        challenge.submitUrl = null
        challenge.submitMethod = "POST"
        challenge.answerFieldName = CaptchaChallengeParser.DEFAULT_ANSWER_FIELD
        challenge.preparationVersion = 0
        replaceFormFields(challenge, emptyList())
    }

    /**
     * 새 challenge를 먼저 저장해 생성된 FK를 확보한 뒤, 같은 트랜잭션에서 form field row를 저장한다.
     *
     * [CaptchaChallengeMetadata.formFields]의 순서는 HTML input 순서이며 [CaptchaFormFieldEntity.fieldOrder]로
     * 그대로 복사된다. 답안 field도 빈 값 row로 포함되어 submit 시 다른 hidden/submit field와 동일한
     * 경로로 읽히고, 실제 사용자 답안은 원본 row를 변경하지 않은 채 요청 Map에서만 덮어쓴다.
     */
    private fun saveFormFields(
        challenge: CaptchaChallengeEntity,
        fields: List<CaptchaFormField>,
    ) {
        if (fields.isEmpty()) return

        captchaFormFieldRepository.saveAll(
            fields.map { field ->
                CaptchaFormFieldEntity(
                    challenge = challenge,
                    fieldOrder = field.fieldOrder,
                    fieldName = field.fieldName,
                    fieldValue = field.fieldValue,
                )
            },
        )
    }

    /**
     * 실패 응답이 같은 challenge에 새 form을 내려주면 기존 field를 전부 새 snapshot으로 교체한다.
     *
     * 기존 row를 QueryDSL로 읽어 command repository에서 삭제하고 즉시 flush한 다음 새 row를 넣는다.
     * 삭제와 삽입을 분리해 `(challenge_id, field_name)` unique 제약이 Hibernate SQL 실행 순서 때문에
     * 충돌하지 않게 하며, challenge metadata·이미지 경로 갱신까지 submit 트랜잭션 하나로 묶는다.
     */
    private fun replaceFormFields(
        challenge: CaptchaChallengeEntity,
        fields: List<CaptchaFormField>,
    ) {
        val existingFields = captchaQueryRepository.findFormFields(challenge.id)
        if (existingFields.isNotEmpty()) {
            captchaFormFieldRepository.deleteAll(existingFields)
            captchaFormFieldRepository.flush()
        }
        saveFormFields(challenge, fields)
    }

    private fun isTransactionActive(): Boolean =
        TransactionSynchronizationManager.isActualTransactionActive()

    private fun deletePreparedVersionAfterRollback(accountId: Long, challengeId: Long, preparationVersion: Int) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() ||
            !TransactionSynchronizationManager.isSynchronizationActive()
        ) {
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : org.springframework.transaction.support.TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status != org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED) {
                        imageManager.deleteImmediately(accountId, challengeId, preparationVersion)
                    }
                }
            },
        )
    }

    /**
     * 저장해 둔 원본 form field에 사용자의 답안 값을 채워 제출용 field Map을 만든다.
     */
    private fun buildSubmittedFormFields(
        challenge: CaptchaChallengeEntity,
        storedFields: List<CaptchaFormFieldEntity>,
        answer: String,
    ): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        storedFields.forEach { field ->
            fields[field.fieldName] = field.fieldValue
        }
        fields[challenge.answerFieldName] = answer
        return fields
    }

    /**
     * Entity를 앱 응답 DTO로 변환한다.
     *
     * 이미지 URL은 원본 URL이 아니라 백엔드의 이미지 proxy endpoint로 내려준다.
     */
    private fun CaptchaChallengeEntity.toResponse(): CaptchaChallengeResponse =
        CaptchaChallengeResponse(
            id = id,
            accountId = account.id,
            status = status,
            prompt = if (status == STATUS_ANSWERED) CAPTCHA_SUCCESS_MESSAGE else prompt,
            imageUrl = imageUrl
                ?.takeIf { status == STATUS_READY }
                ?.let { "/api/captcha/$id/image?version=$preparationVersion" },
            sourceUrl = sourceUrl,
            preparationVersion = preparationVersion,
            createdAt = createdAt.toString(),
            answeredAt = answeredAt?.toString(),
        )

    private companion object {
        const val STATUS_DETECTED = "DETECTED"
        const val STATUS_READY = "READY"
        const val STATUS_ANSWERED = "ANSWERED"
        const val VIGILANTE_PASS_PROMPT = "자경단에서 통행증을 발급받아주세요."
        const val DEFAULT_PROMPT = "캡차 인증이 필요합니다."
        const val CAPTCHA_SUCCESS_MESSAGE = "캡차 인증이 완료되었습니다."
    }
}
