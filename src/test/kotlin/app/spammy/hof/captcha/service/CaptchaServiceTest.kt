package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.account.repository.HofCookieRepository
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.captcha.entity.CaptchaFormFieldEntity
import app.spammy.hof.captcha.repository.CaptchaChallengeRepository
import app.spammy.hof.captcha.repository.CaptchaFormFieldCommandRepository
import app.spammy.hof.captcha.repository.CaptchaQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofBinaryGateway
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofBinaryResponse
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CaptchaServiceTest {
    private val now = Instant.parse("2026-07-08T00:00:00Z")
    private val account = HofAccountEntity(
        id = 1L,
        loginId = "abcd12",
        encryptedPassword = "qwer12",
        createdAt = now,
    )
    private val repository = RecordingCaptchaChallengeRepository()
    private val formFieldRepository = RecordingCaptchaFormFieldRepository()
    private val queryRepository = Mockito.mock(CaptchaQueryRepository::class.java)
    private val cookieRepository = Mockito.mock(HofCookieRepository::class.java)
    private val cookieQueryRepository = Mockito.mock(CookieQueryRepository::class.java)
    private val gateway = FakeHofGateway()
    private val binaryGateway = FakeHofBinaryGateway()
    private val captchaImageFileStore = FakeCaptchaImageFileStore()
    private val service = CaptchaService(
        captchaChallengeRepository = repository,
        captchaFormFieldRepository = formFieldRepository,
        captchaQueryRepository = queryRepository,
        cookieRepository = cookieRepository,
        cookieQueryRepository = cookieQueryRepository,
        gateway = gateway,
        challengeParser = CaptchaChallengeParser(),
        imageManager = CaptchaImageManager(binaryGateway, captchaImageFileStore),
        timeProvider = TimeProvider { now },
    )

    @Test
    fun detectAndRecordReturnsNullWhenCaptchaIsAbsent() {
        val response = service.detectAndRecord(
            account = account,
            html = """
                <html><body>
                  <form action="/ZeroHOF/battle.php" method="post">
                    <button name="common" value="snow22">전투</button>
                  </form>
                  <div>전투에서 승리했습니다.</div>
                </body></html>
            """.trimIndent(),
            sourceUrl = "http://sic.zerosic.com/ZeroHOF/index.php",
        )

        assertNull(response)
        assertEquals(emptyList(), repository.savedEntities)
    }

    @Test
    fun detectAndRecordUsesNewWriteTransaction() {
        val annotation = CaptchaService::class.java
            .getMethod("detectAndRecord", HofAccountEntity::class.java, String::class.java, String::class.java)
            .getAnnotation(Transactional::class.java)

        assertNotNull(annotation)
        assertEquals(Propagation.REQUIRES_NEW, annotation.propagation)
    }

    @Test
    fun detectAndRecordReturnsNullWhenBattleFormOnlyHasHiddenCodeField() {
        val response = service.detectAndRecord(
            account = account,
            html = """
                <html><body>
                  <form action="/ZeroHOF/battle.php" method="post">
                    <input type="hidden" name="code" value="snow22">
                    <input type="hidden" name="mode" value="battle">
                    <button type="submit">전투</button>
                  </form>
                  <div>전투에서 승리했습니다.</div>
                </body></html>
            """.trimIndent(),
            sourceUrl = "http://sic.zerosic.com/ZeroHOF/index.php",
        )

        assertNull(response)
        assertEquals(emptyList(), repository.savedEntities)
    }

    @Test
    fun detectAndRecordAlwaysFetchesPolicePageForVigilantePassGate() {
        val imageBytes = byteArrayOf(7, 7, 7)
        repository.nextId = 7L
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(listOf(cookie()))
        gateway.response = HofHttpResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/index.php?menu=police",
            body = """
                <html><body>
                  <form action="/ZeroHOF/index.php?menu=police" method="post">
                    <img src="simple-php-captcha.php?_CAPTCHA=1">
                    <input type="text" name="AnswerV">
                    <input type="submit" name="AnswerOut" value="입니다.">
                  </form>
                </body></html>
            """.trimIndent(),
            setCookies = emptyMap(),
        )
        binaryGateway.response = HofBinaryResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1",
            contentType = "image/png",
            body = imageBytes,
        )

        val response = assertNotNull(
            service.detectAndRecord(
                account = account,
                html = """
                    <html><body>
                      <font color="red">자경단</font>
                      <p>자경단에서 통행증을 발급받아주세요.</p>
                      <form action="/ZeroHOF/pass_check.php" method="get">
                        <input type="hidden" name="mode" value="battle">
                        <input type="hidden" name="map" value="snow22">
                        <img src="pass_image.php?code=abc">
                        <input type="text" name="pass_code" value="prefilled">
                      </form>
                    </body></html>
                """.trimIndent(),
                sourceUrl = "http://sic.zerosic.com/ZeroHOF/index.php?common=snow22",
            ),
        )

        assertEquals("PENDING", response.status)
        assertEquals("자경단에서 통행증을 발급받아주세요.", response.prompt)
        assertEquals("/api/captcha/7/image", response.imageUrl)

        val saved = repository.savedEntities.single()
        assertEquals("http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1", saved.imageUrl)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=police", saved.submitUrl)
        assertEquals("POST", saved.submitMethod)
        assertEquals("AnswerV", saved.answerFieldName)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=police", gateway.requests.single().url)
        assertEquals(listOf("http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1"), binaryGateway.urls)
        assertContentEquals(imageBytes, captchaImageFileStore.files["1:7"]?.bytes)

        assertLatestSavedFields("AnswerV" to "", "AnswerOut" to "입니다.")
    }

    @Test
    fun detectAndRecordFetchesPoliceCaptchaWhenVigilanteGateHasNoImage() {
        val imageBytes = byteArrayOf(4, 3, 2, 1)
        repository.nextId = 17L
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(listOf(cookie()))
        gateway.response = HofHttpResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/index.php?menu=police",
            body = """
                <html><body>
                  <form action="/ZeroHOF/index.php?menu=police" method="post">
                    <img src="simple-php-captcha.php?_CAPTCHA=1">
                    <input type="text" name="AnswerV">
                    <input type="submit" name="AnswerOut" value="입니다.">
                  </form>
                </body></html>
            """.trimIndent(),
            setCookies = emptyMap(),
        )
        binaryGateway.response = HofBinaryResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1",
            contentType = "image/png; charset=UTF-8",
            body = imageBytes,
        )

        val response = assertNotNull(
            service.detectAndRecord(
                account = account,
                html = """
                    <html><body>
                      <font color="red">자경단</font>
                      <p>자경단에서 통행증을 발급받아주세요.</p>
                    </body></html>
                """.trimIndent(),
                sourceUrl = "http://sic.zerosic.com/ZeroHOF/index.php?common=snow22",
            ),
        )

        assertEquals("/api/captcha/17/image", response.imageUrl)
        assertEquals(1, gateway.requests.size)
        assertEquals(HofHttpMethod.GET, gateway.requests.single().method)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=police", gateway.requests.single().url)
        assertEquals(mapOf("PHPSESSID" to "abc"), gateway.cookies.single())

        val saved = repository.savedEntities.single()
        assertEquals("자경단에서 통행증을 발급받아주세요.", saved.prompt)
        assertEquals("http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1", saved.imageUrl)
        assertEquals("image/png", captchaImageFileStore.files["1:17"]?.contentType)
        assertContentEquals(imageBytes, captchaImageFileStore.files["1:17"]?.bytes)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=police", saved.sourceUrl)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=police", saved.submitUrl)
        assertEquals("POST", saved.submitMethod)
        assertEquals("AnswerV", saved.answerFieldName)
        assertLatestSavedFields("AnswerV" to "", "AnswerOut" to "입니다.")
        assertEquals(listOf("http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1"), binaryGateway.urls)
    }

    @Test
    fun detectAndRecordUsesPoliceSetCookiesWhenDownloadingCaptchaImage() {
        val imageBytes = byteArrayOf(9, 9, 1, 1)
        val sessionCookie = cookie(value = "old-session")
        repository.nextId = 21L
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(listOf(sessionCookie))
        gateway.response = HofHttpResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/index.php?menu=police",
            body = """
                <html><body>
                  <form action="/ZeroHOF/index.php?menu=police" method="post">
                    <img src="simple-php-captcha.php?_CAPTCHA=1">
                    <input type="text" name="AnswerV">
                    <input type="submit" name="AnswerOut" value="입니다.">
                  </form>
                </body></html>
            """.trimIndent(),
            setCookies = mapOf(
                "PHPSESSID" to "fresh-session",
                "_CAPTCHA" to "fresh-captcha",
            ),
        )
        binaryGateway.response = HofBinaryResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1",
            contentType = "image/png",
            body = imageBytes,
        )

        service.detectAndRecord(
            account = account,
            html = """
                <html><body>
                  <font color="red">자경단</font>
                  <p>자경단에서 통행증을 발급받아주세요.</p>
                </body></html>
            """.trimIndent(),
            sourceUrl = "http://sic.zerosic.com/ZeroHOF/index.php?common=snow22",
        )

        assertEquals(mapOf("PHPSESSID" to "old-session"), gateway.cookies.single())
        assertEquals(
            mapOf("PHPSESSID" to "fresh-session", "_CAPTCHA" to "fresh-captcha"),
            binaryGateway.cookies.single(),
        )
        assertEquals("fresh-session", sessionCookie.value)
        val cookieCaptor = ArgumentCaptor.forClass(HofCookieEntity::class.java)
        Mockito.verify(cookieRepository).save(capture(cookieCaptor, cookie()))
        assertEquals("_CAPTCHA", cookieCaptor.value.name)
        assertEquals("fresh-captcha", cookieCaptor.value.value)
        assertContentEquals(imageBytes, captchaImageFileStore.files["1:21"]?.bytes)
    }

    @Test
    fun detectAndRecordStoresPendingChallengeWhenCaptchaAppears() {
        repository.nextId = 4L

        val response = service.detectAndRecord(
            account = account,
            html = """
                <html><body>
                  <p>통행증을 입력하세요</p>
                  <img src="/ZeroHOF/captcha.php?id=1">
                  <input name="captcha">
                </body></html>
            """.trimIndent(),
            sourceUrl = "http://sic.zerosic.com/ZeroHOF/index.php?common=snow22",
        )

        assertNotNull(response)
        assertEquals("PENDING", response.status)
        assertEquals("통행증을 입력하세요", response.prompt)
        assertEquals("/api/captcha/4/image", response.imageUrl)
    }

    @Test
    fun findCurrentBackfillsPoliceCaptchaWhenPendingVigilanteChallengeHasNoImage() {
        val imageBytes = byteArrayOf(8, 7, 6, 5)
        val challenge = pendingChallenge(
            id = 18L,
            prompt = "자경단에서 통행증을 발급받아주세요.",
            imageUrl = null,
        )
        Mockito.`when`(queryRepository.findLatestPendingByAccountId(1L))
            .thenReturn(challenge)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(listOf(cookie()))
        gateway.response = HofHttpResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/index.php?menu=police",
            body = """
                <html><body>
                  <form action="/ZeroHOF/index.php?menu=police" method="post">
                    <img src="simple-php-captcha.php?_CAPTCHA=1">
                    <input type="text" name="AnswerV">
                    <input type="submit" name="AnswerOut" value="입니다.">
                  </form>
                </body></html>
            """.trimIndent(),
            setCookies = emptyMap(),
        )
        binaryGateway.response = HofBinaryResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1",
            contentType = "image/gif",
            body = imageBytes,
        )

        val response = assertNotNull(service.findCurrent(1L))

        assertEquals("/api/captcha/18/image", response.imageUrl)
        assertEquals("http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1", challenge.imageUrl)
        assertEquals("image/gif", captchaImageFileStore.files["1:18"]?.contentType)
        assertContentEquals(imageBytes, captchaImageFileStore.files["1:18"]?.bytes)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=police", challenge.sourceUrl)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=police", challenge.submitUrl)
        assertEquals("AnswerV", challenge.answerFieldName)
        assertLatestSavedFields("AnswerV" to "", "AnswerOut" to "입니다.")
        assertEquals(1, gateway.requests.size)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=police", gateway.requests.single().url)
        assertEquals(listOf("http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1"), binaryGateway.urls)
    }

    @Test
    fun submitAnswerSendsStoredFormWithCookiesAndMarksAnsweredWhenCaptchaIsGone() {
        val challenge = pendingChallenge(
            id = 3L,
            submitUrl = "http://sic.zerosic.com/ZeroHOF/pass_check.php",
            submitMethod = "GET",
            answerFieldName = "pass_code",
            formFields = linkedMapOf("mode" to "battle", "map" to "snow22", "pass_code" to ""),
        )
        captchaImageFileStore.files["1:3"] = StoredFile(
            contentType = "image/png",
            bytes = byteArrayOf(1, 1, 1),
        )
        stubOwnedChallenge(3L, challenge)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(
            listOf(
                cookie(name = "PHPSESSID", value = "abc"),
                cookie(name = "NO", value = "1"),
            ),
        )
        gateway.response = HofHttpResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/battle.php",
            body = "<html><body><div>전투에서 승리했습니다.</div></body></html>",
            setCookies = emptyMap(),
        )

        val response = service.submitAnswer(accountId = 1L, challengeId = 3L, answer = "  1234  ")

        assertEquals("ANSWERED", response.status)
        assertEquals("1234", challenge.answer)
        assertEquals(now, challenge.answeredAt)
        assertEquals(listOf("1:3"), captchaImageFileStore.deletedKeys)
        assertEquals(1, gateway.requests.size)
        assertEquals(HofHttpMethod.GET, gateway.requests.single().method)
        assertEquals("http://sic.zerosic.com/ZeroHOF/pass_check.php", gateway.requests.single().url)
        assertEquals(
            mapOf(
                "mode" to "battle",
                "map" to "snow22",
                "pass_code" to "1234",
            ),
            gateway.requests.single().formFields,
        )
        assertEquals(mapOf("PHPSESSID" to "abc", "NO" to "1"), gateway.cookies.single())
    }

    @Test
    fun submitAnswerMarksAnsweredWhenPoliceSuccessPageStillContainsCaptchaWords() {
        val challenge = pendingChallenge(
            id = 22L,
            prompt = "자경단에서 통행증을 발급받아주세요.",
            imageUrl = "http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1",
            submitUrl = "http://sic.zerosic.com/ZeroHOF/index.php?menu=police",
            submitMethod = "POST",
            answerFieldName = "AnswerV",
            formFields = linkedMapOf("AnswerV" to "", "AnswerOut" to "입니다."),
        )
        captchaImageFileStore.files["1:22"] = StoredFile(
            contentType = "image/png",
            bytes = byteArrayOf(3, 2, 1),
        )
        stubOwnedChallenge(22L, challenge)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(listOf(cookie()))
        gateway.response = HofHttpResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/index.php?menu=police",
            body = """
                <html><body>
                  <div>자경단 검문소(Checkpoint)</div>
                  <p>매크로 방지 캡션 시스템입니다.</p>
                  <p>uEjs5...(확인 : uEjs5) 흠, 정답입니다.</p>
                  <p>통행증이 발급되었습니다.(30분 지속) Time이 200 회복되었습니다.</p>
                  <div>최근 검문 이력 전체표시 - [《얼어붙은 손길》 공민이] 검문 통과에 성공했다 (대답:uEjs5 정답:uEjs5)</div>
                </body></html>
            """.trimIndent(),
            setCookies = emptyMap(),
        )

        val response = service.submitAnswer(accountId = 1L, challengeId = 22L, answer = "uEjs5")

        assertEquals("ANSWERED", response.status)
        assertEquals("캡차 인증이 완료되었습니다.", response.prompt)
        assertNull(response.imageUrl)
        assertEquals("ANSWERED", challenge.status)
        assertEquals("uEjs5", challenge.answer)
        assertEquals(now, challenge.answeredAt)
        assertEquals(listOf("1:22"), captchaImageFileStore.deletedKeys)
        assertEquals(emptyList(), binaryGateway.urls)
    }

    @Test
    fun loadImageFetchesPendingChallengeImageWithCookiesAndStripsContentTypeParameters() {
        val bytes = byteArrayOf(9, 8, 7)
        val imageUrl = "http://sic.zerosic.com/ZeroHOF/pass_image.php?code=abc"
        val challenge = pendingChallenge(id = 8L, imageUrl = imageUrl)
        stubPendingImageChallenge(8L, challenge)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(
            listOf(
                cookie(name = "PHPSESSID", value = "abc"),
                cookie(name = "NO", value = "1"),
            ),
        )
        binaryGateway.response = HofBinaryResponse(
            statusCode = 200,
            finalUrl = imageUrl,
            contentType = "image/png; charset=UTF-8",
            body = bytes,
        )

        val response = service.loadImage(accountId = 1L, challengeId = 8L)

        assertEquals("image/png", response.contentType)
        assertContentEquals(bytes, response.bytes)
        assertEquals(listOf(imageUrl), binaryGateway.urls)
        assertEquals(mapOf("PHPSESSID" to "abc", "NO" to "1"), binaryGateway.cookies.single())
    }

    @Test
    fun loadImageReturnsStoredImageBytesWithoutRecallingHofImageUrl() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val challenge = pendingChallenge(
            id = 19L,
            imageUrl = "http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1",
        )
        captchaImageFileStore.files["1:19"] = StoredFile(
            contentType = "image/png",
            bytes = bytes,
        )
        stubPendingImageChallenge(19L, challenge)

        val response = service.loadImage(accountId = 1L, challengeId = 19L)

        assertEquals("image/png", response.contentType)
        assertContentEquals(bytes, response.bytes)
        Mockito.verifyNoInteractions(cookieRepository)
        assertEquals(emptyList(), binaryGateway.urls)
    }

    @Test
    fun loadImageDefaultsContentTypeWhenHeaderIsMissing() {
        val bytes = byteArrayOf(5, 4, 3)
        val imageUrl = "http://sic.zerosic.com/ZeroHOF/pass_image.php?code=abc"
        val challenge = pendingChallenge(id = 9L, imageUrl = imageUrl)
        stubPendingImageChallenge(9L, challenge)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(listOf(cookie()))
        binaryGateway.response = HofBinaryResponse(
            statusCode = 200,
            finalUrl = imageUrl,
            contentType = null,
            body = bytes,
        )

        val response = service.loadImage(accountId = 1L, challengeId = 9L)

        assertEquals("application/octet-stream", response.contentType)
        assertContentEquals(bytes, response.bytes)
    }

    @Test
    fun loadImageDefaultsContentTypeWhenHeaderIsInvalid() {
        val bytes = byteArrayOf(6, 7, 8)
        val imageUrl = "http://sic.zerosic.com/ZeroHOF/pass_image.php?code=abc"
        val challenge = pendingChallenge(id = 15L, imageUrl = imageUrl)
        stubPendingImageChallenge(15L, challenge)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(listOf(cookie()))
        binaryGateway.response = HofBinaryResponse(
            statusCode = 200,
            finalUrl = imageUrl,
            contentType = "not a media type",
            body = bytes,
        )

        val response = service.loadImage(accountId = 1L, challengeId = 15L)

        assertEquals("application/octet-stream", response.contentType)
        assertContentEquals(bytes, response.bytes)
    }

    @Test
    fun loadImageRejectsMissingChallenge() {
        Mockito.`when`(queryRepository.findOwnedByAccountIdAndIdAndStatus(1L, 88L, "PENDING"))
            .thenReturn(null)

        val exception = assertFailsWith<ApiException> {
            service.loadImage(accountId = 1L, challengeId = 88L)
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
        Mockito.verifyNoInteractions(cookieRepository)
        assertEquals(emptyList(), binaryGateway.urls)
    }

    @Test
    fun loadImageRejectsChallengeOwnedByAnotherAccount() {
        val challenge = pendingChallenge(
            id = 10L,
            owner = HofAccountEntity(
                id = 2L,
                loginId = "zzzz99",
                encryptedPassword = "qwer12",
                createdAt = now,
            ),
            imageUrl = "http://sic.zerosic.com/ZeroHOF/pass_image.php?code=abc",
        )
        Mockito.`when`(queryRepository.findOwnedByAccountIdAndIdAndStatus(1L, 10L, "PENDING"))
            .thenReturn(null)

        val exception = assertFailsWith<ApiException> {
            service.loadImage(accountId = 1L, challengeId = 10L)
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
        Mockito.verifyNoInteractions(cookieRepository)
        assertEquals(emptyList(), binaryGateway.urls)
    }

    @Test
    fun loadImageRejectsChallengeThatIsNotPending() {
        val challenge = pendingChallenge(
            id = 11L,
            status = "ANSWERED",
            imageUrl = "http://sic.zerosic.com/ZeroHOF/pass_image.php?code=abc",
        )
        Mockito.`when`(queryRepository.findOwnedByAccountIdAndIdAndStatus(1L, 11L, "PENDING"))
            .thenReturn(null)

        val exception = assertFailsWith<ApiException> {
            service.loadImage(accountId = 1L, challengeId = 11L)
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
        Mockito.verifyNoInteractions(cookieRepository)
        assertEquals(emptyList(), binaryGateway.urls)
    }

    @Test
    fun loadImageRejectsChallengeWithoutImageUrl() {
        val challenge = pendingChallenge(id = 12L, imageUrl = "   ")
        stubPendingImageChallenge(12L, challenge)

        val exception = assertFailsWith<ApiException> {
            service.loadImage(accountId = 1L, challengeId = 12L)
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
        Mockito.verifyNoInteractions(cookieRepository)
        assertEquals(emptyList(), binaryGateway.urls)
    }

    @Test
    fun loadImageRejectsWhenStoredCookiesAreMissing() {
        val challenge = pendingChallenge(
            id = 13L,
            imageUrl = "http://sic.zerosic.com/ZeroHOF/pass_image.php?code=abc",
        )
        stubPendingImageChallenge(13L, challenge)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(emptyList())

        val exception = assertFailsWith<ApiException> {
            service.loadImage(accountId = 1L, challengeId = 13L)
        }

        assertEquals(ErrorCode.HOF_SESSION_EXPIRED, exception.errorCode)
        assertEquals(emptyList(), binaryGateway.urls)
    }

    @Test
    fun loadImageWrapsBinaryGatewayFailure() {
        val cause = IllegalStateException("network down")
        val challenge = pendingChallenge(
            id = 14L,
            imageUrl = "http://sic.zerosic.com/ZeroHOF/pass_image.php?code=abc",
        )
        stubPendingImageChallenge(14L, challenge)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(listOf(cookie()))
        binaryGateway.failure = cause

        val exception = assertFailsWith<ApiException> {
            service.loadImage(accountId = 1L, challengeId = 14L)
        }

        assertEquals(ErrorCode.HOF_REQUEST_FAILED, exception.errorCode)
        assertEquals(cause, exception.cause)
    }

    @Test
    fun loadImageRejectsNonSuccessfulBinaryGatewayResponse() {
        val imageUrl = "http://sic.zerosic.com/ZeroHOF/pass_image.php?code=abc"
        val challenge = pendingChallenge(id = 16L, imageUrl = imageUrl)
        stubPendingImageChallenge(16L, challenge)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(listOf(cookie()))
        binaryGateway.response = HofBinaryResponse(
            statusCode = 403,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/login.php",
            contentType = "text/html",
            body = "<html>denied</html>".toByteArray(),
        )

        val exception = assertFailsWith<ApiException> {
            service.loadImage(accountId = 1L, challengeId = 16L)
        }

        assertEquals(ErrorCode.HOF_REQUEST_FAILED, exception.errorCode)
        assertEquals(listOf(imageUrl), binaryGateway.urls)
    }

    @Test
    fun loadImageRejectsSuccessfulHtmlResponseFromHofImageUrl() {
        val imageUrl = "http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1"
        val challenge = pendingChallenge(id = 20L, imageUrl = imageUrl)
        stubPendingImageChallenge(20L, challenge)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(listOf(cookie()))
        binaryGateway.response = HofBinaryResponse(
            statusCode = 200,
            finalUrl = imageUrl,
            contentType = "text/html; charset=UTF-8",
            body = "<br />Undefined index: _CAPTCHA".toByteArray(),
        )

        val exception = assertFailsWith<ApiException> {
            service.loadImage(accountId = 1L, challengeId = 20L)
        }

        assertEquals(ErrorCode.HOF_REQUEST_FAILED, exception.errorCode)
        assertEquals(listOf(imageUrl), binaryGateway.urls)
    }

    @Test
    fun submitAnswerFallsBackToSourceUrlWhenSubmitUrlIsMissing() {
        val challenge = pendingChallenge(
            id = 4L,
            submitUrl = null,
            submitMethod = "POST",
            answerFieldName = "captcha",
            formFields = linkedMapOf("captcha" to ""),
        )
        stubOwnedChallenge(4L, challenge)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(listOf(cookie()))

        service.submitAnswer(accountId = 1L, challengeId = 4L, answer = "1234")

        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?common=snow22", gateway.requests.single().url)
    }

    @Test
    fun submitAnswerRefreshesPendingChallengeWhenHofReturnsAnotherCaptcha() {
        val refreshedImageBytes = byteArrayOf(6, 6, 6)
        val challenge = pendingChallenge(
            id = 5L,
            submitUrl = "http://sic.zerosic.com/ZeroHOF/pass_check.php",
            submitMethod = "POST",
            answerFieldName = "pass_code",
            formFields = linkedMapOf("old_token" to "old", "pass_code" to ""),
        )
        captchaImageFileStore.files["1:5"] = StoredFile(
            contentType = "image/png",
            bytes = byteArrayOf(1, 1, 1),
        )
        stubOwnedChallenge(5L, challenge)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(listOf(cookie()))
        gateway.responses += listOf(
            HofHttpResponse(
                statusCode = 200,
                finalUrl = "http://sic.zerosic.com/ZeroHOF/retry.php?common=snow22",
                body = """
                    <html><body>
                      <p>자경단에서 통행증을 발급받아주세요.</p>
                    </body></html>
                """.trimIndent(),
                setCookies = emptyMap(),
            ),
            HofHttpResponse(
                statusCode = 200,
                finalUrl = "http://sic.zerosic.com/ZeroHOF/index.php?menu=police",
                body = """
                    <html><body>
                      <form action="/ZeroHOF/index.php?menu=police" method="post">
                        <input type="hidden" name="token" value="new-token">
                        <img src="simple-php-captcha.php?_CAPTCHA=1">
                        <input type="text" name="AnswerV">
                        <input type="submit" name="AnswerOut" value="입니다.">
                      </form>
                    </body></html>
                """.trimIndent(),
                setCookies = emptyMap(),
            ),
        )
        binaryGateway.response = HofBinaryResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1",
            contentType = "image/png",
            body = refreshedImageBytes,
        )

        val response = service.submitAnswer(accountId = 1L, challengeId = 5L, answer = "wrong")

        assertEquals("PENDING", response.status)
        assertEquals(HofHttpMethod.POST, gateway.requests[0].method)
        assertEquals(
            mapOf(
                "old_token" to "old",
                "pass_code" to "wrong",
            ),
            gateway.requests[0].formFields,
        )
        assertEquals(HofHttpMethod.GET, gateway.requests[1].method)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=police", gateway.requests[1].url)
        assertEquals("/api/captcha/5/image", response.imageUrl)
        assertEquals("PENDING", challenge.status)
        assertNull(challenge.answer)
        assertNull(challenge.answeredAt)
        assertEquals("자경단에서 통행증을 발급받아주세요.", challenge.prompt)
        assertEquals("http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1", challenge.imageUrl)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=police", challenge.sourceUrl)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=police", challenge.submitUrl)
        assertEquals("POST", challenge.submitMethod)
        assertEquals("AnswerV", challenge.answerFieldName)
        assertEquals(listOf("1:5"), captchaImageFileStore.deletedKeys)
        assertEquals("image/png", captchaImageFileStore.files["1:5"]?.contentType)
        assertContentEquals(refreshedImageBytes, captchaImageFileStore.files["1:5"]?.bytes)
        assertEquals(listOf("old_token", "pass_code"), formFieldRepository.deletedBatches.single().map { it.fieldName })
        assertEquals(1, formFieldRepository.flushCount)
        assertLatestSavedFields(
            "token" to "new-token",
            "AnswerV" to "",
            "AnswerOut" to "입니다.",
        )
    }

    @Test
    fun submitAnswerRejectsBlankAnswer() {
        val exception = assertFailsWith<ApiException> {
            service.submitAnswer(accountId = 1L, challengeId = 3L, answer = "   ")
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
        assertEquals(emptyList(), repository.savedEntities)
        Mockito.verifyNoInteractions(cookieRepository)
        assertEquals(emptyList(), gateway.requests)
    }

    @Test
    fun submitAnswerRejectsChallengeOwnedByAnotherAccount() {
        val challenge = pendingChallenge(
            id = 3L,
            owner = HofAccountEntity(
                id = 2L,
                loginId = "zzzz99",
                encryptedPassword = "qwer12",
                createdAt = now,
            ),
        )
        Mockito.`when`(queryRepository.findOwnedByAccountIdAndId(1L, 3L)).thenReturn(null)

        val exception = assertFailsWith<ApiException> {
            service.submitAnswer(accountId = 1L, challengeId = 3L, answer = "1234")
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
        Mockito.verifyNoInteractions(cookieRepository)
        assertEquals(emptyList(), gateway.requests)
    }

    @Test
    fun submitAnswerRejectsChallengeThatIsNotPending() {
        val challenge = pendingChallenge(id = 3L, status = "ANSWERED")
        stubOwnedChallenge(3L, challenge)

        val exception = assertFailsWith<ApiException> {
            service.submitAnswer(accountId = 1L, challengeId = 3L, answer = "1234")
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
        Mockito.verifyNoInteractions(cookieRepository)
        assertEquals(emptyList(), gateway.requests)
    }

    @Test
    fun submitAnswerRejectsWhenStoredCookiesAreMissing() {
        val challenge = pendingChallenge(id = 3L)
        stubOwnedChallenge(3L, challenge)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(emptyList())

        val exception = assertFailsWith<ApiException> {
            service.submitAnswer(accountId = 1L, challengeId = 3L, answer = "1234")
        }

        assertEquals(ErrorCode.HOF_SESSION_EXPIRED, exception.errorCode)
        assertNull(challenge.answer)
        assertEquals(emptyList(), gateway.requests)
    }

    @Test
    fun submitAnswerWrapsGatewayFailure() {
        val challenge = pendingChallenge(id = 3L)
        captchaImageFileStore.files["1:3"] = StoredFile(
            contentType = "image/png",
            bytes = byteArrayOf(1, 1, 1),
        )
        val cause = IllegalStateException("network down")
        stubOwnedChallenge(3L, challenge)
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(listOf(cookie()))
        gateway.failure = cause

        val exception = assertFailsWith<ApiException> {
            service.submitAnswer(accountId = 1L, challengeId = 3L, answer = "1234")
        }

        assertEquals(ErrorCode.HOF_REQUEST_FAILED, exception.errorCode)
        assertEquals(cause, exception.cause)
        assertEquals("PENDING", challenge.status)
        assertNull(challenge.answer)
        assertEquals(listOf("1:3"), captchaImageFileStore.deletedKeys)
    }

    private fun pendingChallenge(
        id: Long,
        owner: HofAccountEntity = account,
        status: String = "PENDING",
        prompt: String = "통행증을 입력하세요",
        imageUrl: String? = null,
        submitUrl: String? = "http://sic.zerosic.com/ZeroHOF/pass_check.php",
        submitMethod: String = "POST",
        answerFieldName: String = "captcha",
        formFields: Map<String, String> = linkedMapOf("captcha" to ""),
    ): CaptchaChallengeEntity {
        val challenge = CaptchaChallengeEntity(
            id = id,
            account = owner,
            status = status,
            prompt = prompt,
            imageUrl = imageUrl,
            sourceUrl = "http://sic.zerosic.com/ZeroHOF/index.php?common=snow22",
            answer = null,
            createdAt = now,
            answeredAt = null,
            submitUrl = submitUrl,
            submitMethod = submitMethod,
            answerFieldName = answerFieldName,
        )
        Mockito.`when`(queryRepository.findFormFields(id)).thenReturn(
            formFields.entries.mapIndexed { fieldOrder, (fieldName, fieldValue) ->
                CaptchaFormFieldEntity(
                    id = fieldOrder.toLong() + 1,
                    challenge = challenge,
                    fieldOrder = fieldOrder,
                    fieldName = fieldName,
                    fieldValue = fieldValue,
                )
            },
        )
        return challenge
    }

    private fun cookie(
        name: String = "PHPSESSID",
        value: String = "abc",
    ): HofCookieEntity =
        HofCookieEntity(
            account = account,
            name = name,
            value = value,
            updatedAt = now,
        )

    private fun <T : Any> capture(
        captor: ArgumentCaptor<T>,
        fallback: T,
    ): T = captor.capture() ?: fallback

    private fun stubOwnedChallenge(
        challengeId: Long,
        challenge: CaptchaChallengeEntity,
    ) {
        Mockito.`when`(queryRepository.findOwnedByAccountIdAndId(1L, challengeId)).thenReturn(challenge)
    }

    private fun stubPendingImageChallenge(
        challengeId: Long,
        challenge: CaptchaChallengeEntity,
    ) {
        Mockito.`when`(
            queryRepository.findOwnedByAccountIdAndIdAndStatus(1L, challengeId, "PENDING"),
        ).thenReturn(challenge)
    }

    private fun assertLatestSavedFields(vararg expected: Pair<String, String>) {
        assertEquals(
            expected.toList(),
            formFieldRepository.savedBatches.last()
                .sortedBy { it.fieldOrder }
                .map { it.fieldName to it.fieldValue },
        )
    }

    private class RecordingCaptchaChallengeRepository : CaptchaChallengeRepository {
        val savedEntities = mutableListOf<CaptchaChallengeEntity>()
        var nextId: Long? = null

        @Suppress("UNCHECKED_CAST")
        override fun <S : CaptchaChallengeEntity> save(entity: S): S {
            val saved = CaptchaChallengeEntity(
                id = nextId ?: entity.id,
                account = entity.account,
                status = entity.status,
                prompt = entity.prompt,
                imageUrl = entity.imageUrl,
                sourceUrl = entity.sourceUrl,
                answer = entity.answer,
                createdAt = entity.createdAt,
                answeredAt = entity.answeredAt,
                submitUrl = entity.submitUrl,
                submitMethod = entity.submitMethod,
                answerFieldName = entity.answerFieldName,
            )
            savedEntities += saved
            return saved as S
        }

        override fun <S : CaptchaChallengeEntity> saveAll(entities: Iterable<S>): List<S> =
            entities.map(::save)

        override fun delete(entity: CaptchaChallengeEntity) = Unit

        override fun deleteAll(entities: Iterable<CaptchaChallengeEntity>) = Unit

        override fun flush() = Unit
    }

    private class RecordingCaptchaFormFieldRepository : CaptchaFormFieldCommandRepository {
        val savedBatches = mutableListOf<List<CaptchaFormFieldEntity>>()
        val deletedBatches = mutableListOf<List<CaptchaFormFieldEntity>>()
        var flushCount: Int = 0

        override fun <S : CaptchaFormFieldEntity> save(entity: S): S = entity

        override fun <S : CaptchaFormFieldEntity> saveAll(entities: Iterable<S>): List<S> =
            entities.toList().also { savedBatches += it }

        override fun delete(entity: CaptchaFormFieldEntity) {
            deletedBatches += listOf(entity)
        }

        override fun deleteAll(entities: Iterable<CaptchaFormFieldEntity>) {
            deletedBatches += entities.toList()
        }

        override fun flush() {
            flushCount += 1
        }
    }

    private class FakeHofGateway : HofGateway {
        val requests = mutableListOf<HofRequest>()
        val cookies = mutableListOf<Map<String, String>>()
        var response = HofHttpResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/index.php",
            body = "<html><body>OK</body></html>",
            setCookies = emptyMap(),
        )
        val responses = ArrayDeque<HofHttpResponse>()
        var failure: RuntimeException? = null

        override fun execute(request: HofRequest, cookies: Map<String, String>): HofHttpResponse {
            requests += request
            this.cookies += cookies
            failure?.let { throw it }
            return responses.removeFirstOrNull() ?: response
        }
    }

    private class FakeHofBinaryGateway : HofBinaryGateway {
        val urls = mutableListOf<String>()
        val cookies = mutableListOf<Map<String, String>>()
        var response = HofBinaryResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/pass_image.php",
            contentType = "application/octet-stream",
            body = byteArrayOf(),
        )
        var failure: RuntimeException? = null

        override fun get(url: String, cookies: Map<String, String>): HofBinaryResponse {
            urls += url
            this.cookies += cookies
            failure?.let { throw it }
            return response
        }
    }

    private data class StoredFile(
        val contentType: String,
        val bytes: ByteArray,
    )

    private class FakeCaptchaImageFileStore : CaptchaImageFileStore {
        val files = linkedMapOf<String, StoredFile>()
        val deletedKeys = mutableListOf<String>()

        override fun save(
            accountId: Long,
            challengeId: Long,
            contentType: String,
            bytes: ByteArray,
        ) {
            files[key(accountId, challengeId)] = StoredFile(
                contentType = contentType,
                bytes = bytes,
            )
        }

        override fun read(
            accountId: Long,
            challengeId: Long,
        ): StoredCaptchaImage? =
            files[key(accountId, challengeId)]?.let { file ->
                StoredCaptchaImage(
                    contentType = file.contentType,
                    bytes = file.bytes,
                )
            }

        override fun delete(
            accountId: Long,
            challengeId: Long,
        ) {
            val key = key(accountId, challengeId)
            deletedKeys += key
            files.remove(key)
        }

        private fun key(
            accountId: Long,
            challengeId: Long,
        ): String = "$accountId:$challengeId"
    }
}
