package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.account.repository.HofCookieRepository
import app.spammy.hof.account.service.HofCookieCipher
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.captcha.repository.CaptchaChallengeRepository
import app.spammy.hof.captcha.repository.CaptchaQueryRepository
import app.spammy.hof.captcha.dto.CaptchaChallengeResponse
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofBinaryGateway
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofBinaryResponse
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.util.AopTestUtils
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(
    QueryDslConfig::class,
    CookieQueryRepository::class,
    CaptchaQueryRepository::class,
    CaptchaChallengeParser::class,
    CaptchaImageManager::class,
    CaptchaService::class,
    CaptchaServicePersistenceTest.BoundaryConfig::class,
)
class CaptchaServicePersistenceTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var cookieRepository: HofCookieRepository

    @Autowired
    private lateinit var queryRepository: CaptchaQueryRepository

    @Autowired
    private lateinit var challengeRepository: CaptchaChallengeRepository

    @Autowired
    private lateinit var service: CaptchaService

    @Autowired
    private lateinit var gateway: FakeHofGateway

    @Autowired
    private lateinit var imageStore: FakeCaptchaImageFileStore

    @Autowired
    private lateinit var binaryGateway: FakeHofBinaryGateway

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @BeforeTest
    fun resetBoundaries() {
        gateway.reset()
        binaryGateway.reset()
        imageStore.reset()
    }

    @Test
    fun detectPersistsOrderedFieldsAndSubmitReconstructsMapWithAnswerOverwritten() {
        val account = savedAccountWithCookie("captcha-service-fields")
        val challenge = assertNotNull(
            service.detectAndRecord(
                account = account,
                html = captchaHtml(tokenName = "token", tokenValue = "abc"),
                sourceUrl = POLICE_URL,
            ),
        )

        assertEquals(
            listOf(
                Triple(0, "token", "abc"),
                Triple(1, "AnswerV", ""),
                Triple(2, "AnswerOut", "입니다."),
            ),
            queryRepository.findFormFields(challenge.id)
                .map { Triple(it.fieldOrder, it.fieldName, it.fieldValue) },
        )
        assertEquals(3L, queryRepository.countFormFields(challenge.id))

        gateway.response = HofHttpResponse(
            statusCode = 200,
            finalUrl = POLICE_URL,
            body = "<html><body>통행증이 발급되었습니다.</body></html>",
            setCookies = emptyMap(),
        )

        val answered = service.submitAnswer(account.id, challenge.id, "  7319  ")

        assertEquals("ANSWERED", answered.status)
        assertEquals("캡차 인증이 완료되었습니다.", answered.prompt)
        assertEquals(
            listOf(
                "token" to "abc",
                "AnswerV" to "7319",
                "AnswerOut" to "입니다.",
            ),
            gateway.requests.single().formFields.entries.map { it.key to it.value },
        )
    }

    @Test
    fun failedSubmissionReplacesOldFieldsInTheSameChallenge() {
        val account = savedAccountWithCookie("captcha-service-replace")
        val challenge = assertNotNull(
            service.detectAndRecord(
                account = account,
                html = captchaHtml(tokenName = "old_token", tokenValue = "old"),
                sourceUrl = POLICE_URL,
            ),
        )
        imageStore.clearEvents()
        binaryGateway.body = byteArrayOf(9, 8, 7)
        gateway.response = HofHttpResponse(
            statusCode = 200,
            finalUrl = POLICE_URL,
            body = captchaHtml(tokenName = "new_token", tokenValue = "new"),
            setCookies = mapOf("PHPSESSID" to "updated-session"),
        )

        val refreshed = service.submitAnswer(account.id, challenge.id, "wrong")

        assertEquals(challenge.id, refreshed.id)
        assertEquals("PENDING", refreshed.status)
        assertEquals(
            listOf("new_token" to "new", "AnswerV" to "", "AnswerOut" to "입니다."),
            queryRepository.findFormFields(challenge.id).map { it.fieldName to it.fieldValue },
        )
        assertEquals(3L, queryRepository.countFormFields(challenge.id))
        assertContentEquals(byteArrayOf(9, 8, 7), assertNotNull(imageStore.read(account.id, challenge.id)).bytes)
        assertEquals(
            listOf("delete:${account.id}:${challenge.id}", "save:${account.id}:${challenge.id}"),
            imageStore.events,
        )
    }

    @Test
    fun currentAndImageLookupEnforceOwnershipAndPendingStatusThroughQueryDsl() {
        val owner = savedAccountWithCookie("captcha-service-owner")
        val other = savedAccountWithCookie("captcha-service-other")
        val challenge = assertNotNull(
            service.detectAndRecord(
                account = owner,
                html = captchaHtml(tokenName = "token", tokenValue = "owner"),
                sourceUrl = POLICE_URL,
            ),
        )

        assertEquals(challenge.id, assertNotNull(service.findCurrent(owner.id)).id)
        assertNull(service.findCurrent(other.id))
        val exception = assertFailsWith<ApiException> {
            service.loadImage(other.id, challenge.id)
        }
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun repeatedDetectionReusesPendingChallengeAndAnsweredChallengeDoesNotResurface() {
        val account = savedAccountWithCookie("captcha-service-repeat")
        val first = assertNotNull(
            service.detectAndRecord(account, captchaHtml("old_token", "old"), POLICE_URL),
        )
        val second = assertNotNull(
            service.detectAndRecord(account, captchaHtml("new_token", "new"), POLICE_URL),
        )

        assertEquals(first.id, second.id)
        assertEquals(1L, queryRepository.countPendingByAccountId(account.id))
        assertEquals(
            listOf("new_token" to "new", "AnswerV" to "", "AnswerOut" to "입니다."),
            queryRepository.findFormFields(second.id).map { it.fieldName to it.fieldValue },
        )

        service.submitAnswer(account.id, second.id, "7319")

        assertNull(service.findCurrent(account.id))
        assertEquals(0L, queryRepository.countPendingByAccountId(account.id))
        assertNull(imageStore.read(account.id, second.id))
    }

    @Test
    fun detectionRemovesLegacyDuplicatePendingRowsSoOlderChallengeCannotResurface() {
        val account = savedAccountWithCookie("captcha-service-stale-pending")
        val stale = savedPendingChallenge(account, NOW.minusSeconds(60))
        val newest = savedPendingChallenge(account, NOW.minusSeconds(30))

        val detected = assertNotNull(
            service.detectAndRecord(account, captchaHtml("fresh_token", "fresh"), POLICE_URL),
        )

        assertEquals(newest.id, detected.id)
        assertEquals(1L, queryRepository.countPendingByAccountId(account.id))
        assertNull(queryRepository.findOwnedByAccountIdAndId(account.id, stale.id))

        service.submitAnswer(account.id, detected.id, "correct")

        assertNull(service.findCurrent(account.id))
    }

    @Test
    fun concurrentDetectionSerializesOnAccountAndKeepsOnePendingChallenge() {
        val account = savedAccountWithCookie("captcha-service-concurrent-detect")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(
                executor.submit<CaptchaChallengeResponse> {
                    start.await()
                    assertNotNull(service.detectAndRecord(account, captchaHtml("token_a", "a"), POLICE_URL))
                },
                executor.submit<CaptchaChallengeResponse> {
                    start.await()
                    assertNotNull(service.detectAndRecord(account, captchaHtml("token_b", "b"), POLICE_URL))
                },
            )

            start.countDown()
            val responses = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, responses.map { it.id }.distinct().size)
            assertEquals(1L, queryRepository.countPendingByAccountId(account.id))
            val fields = queryRepository.findFormFields(responses.first().id)
            assertTrue(fields.first().fieldName in setOf("token_a", "token_b"))
            assertEquals(listOf("AnswerV", "AnswerOut"), fields.drop(1).map { it.fieldName })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentSubmissionCallsHofOnceAndSecondSubmitObservesAnsweredStatus() {
        val account = savedAccountWithCookie("captcha-service-concurrent-submit")
        val challenge = assertNotNull(
            service.detectAndRecord(account, captchaHtml("token", "submit"), POLICE_URL),
        )
        val gatewayEntered = CountDownLatch(1)
        val releaseGateway = CountDownLatch(1)
        val attemptsStarted = CountDownLatch(2)
        gateway.handler = {
            gatewayEntered.countDown()
            check(releaseGateway.await(5, TimeUnit.SECONDS))
            gateway.response
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { attempt ->
                executor.submit<Result<CaptchaChallengeResponse>> {
                    attemptsStarted.countDown()
                    runCatching { service.submitAnswer(account.id, challenge.id, "answer-$attempt") }
                }
            }

            assertTrue(attemptsStarted.await(5, TimeUnit.SECONDS))
            assertTrue(gatewayEntered.await(5, TimeUnit.SECONDS))
            releaseGateway.countDown()
            val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, outcomes.count { it.isSuccess })
            val rejected = outcomes.single { it.isFailure }.exceptionOrNull()
            assertTrue(rejected is ApiException)
            assertEquals(ErrorCode.INVALID_REQUEST, rejected.errorCode)
            assertEquals(1, gateway.requests.size)
            assertEquals("ANSWERED", assertNotNull(queryRepository.findOwnedByAccountIdAndId(account.id, challenge.id)).status)
        } finally {
            releaseGateway.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun requiresNewChallengeAndFieldsSurviveOuterRollback() {
        val account = savedAccountWithCookie("captcha-service-requires-new")
        var detected: CaptchaChallengeResponse? = null

        TransactionTemplate(transactionManager).executeWithoutResult { transaction ->
            detected = service.detectAndRecord(account, captchaHtml("token", "committed"), POLICE_URL)
            transaction.setRollbackOnly()
        }

        val challenge = assertNotNull(queryRepository.findLatestPendingByAccountId(account.id))
        assertEquals(assertNotNull(detected).id, challenge.id)
        assertEquals(3L, queryRepository.countFormFields(challenge.id))
        assertNotNull(imageStore.read(account.id, challenge.id))
    }

    @Test
    fun rolledBackDetectionDoesNotApplyDeferredImageSave() {
        val account = savedAccountWithCookie("captcha-service-rollback-detect")
        val target = AopTestUtils.getTargetObject<CaptchaService>(service)
        var rolledBackChallengeId: Long? = null

        TransactionTemplate(transactionManager).executeWithoutResult { transaction ->
            rolledBackChallengeId = assertNotNull(
                target.detectAndRecord(account, captchaHtml("token", "rolled-back"), POLICE_URL),
            ).id
            assertNull(imageStore.read(account.id, assertNotNull(rolledBackChallengeId)))
            transaction.setRollbackOnly()
        }

        assertNull(queryRepository.findLatestPendingByAccountId(account.id))
        assertNull(imageStore.read(account.id, assertNotNull(rolledBackChallengeId)))
        assertEquals(emptyList(), imageStore.events)
    }

    @Test
    fun rolledBackFailedRefreshPreservesPriorImageAndFields() {
        val account = savedAccountWithCookie("captcha-service-rollback-refresh")
        val challenge = assertNotNull(
            service.detectAndRecord(account, captchaHtml("old_token", "old"), POLICE_URL),
        )
        val originalImage = assertNotNull(imageStore.read(account.id, challenge.id)).bytes
        imageStore.clearEvents()
        binaryGateway.body = byteArrayOf(9, 8, 7)
        gateway.response = HofHttpResponse(
            statusCode = 200,
            finalUrl = POLICE_URL,
            body = captchaHtml("new_token", "new"),
            setCookies = emptyMap(),
        )
        val target = AopTestUtils.getTargetObject<CaptchaService>(service)

        TransactionTemplate(transactionManager).executeWithoutResult { transaction ->
            target.submitAnswer(account.id, challenge.id, "wrong")
            assertContentEquals(originalImage, assertNotNull(imageStore.read(account.id, challenge.id)).bytes)
            transaction.setRollbackOnly()
        }

        assertContentEquals(originalImage, assertNotNull(imageStore.read(account.id, challenge.id)).bytes)
        assertEquals(
            listOf("old_token" to "old", "AnswerV" to "", "AnswerOut" to "입니다."),
            queryRepository.findFormFields(challenge.id).map { it.fieldName to it.fieldValue },
        )
        assertEquals(emptyList(), imageStore.events)
    }

    @Test
    fun rolledBackSuccessfulAnswerPreservesPriorImageAndPendingStatus() {
        val account = savedAccountWithCookie("captcha-service-rollback-success")
        val challenge = assertNotNull(
            service.detectAndRecord(account, captchaHtml("token", "success-rollback"), POLICE_URL),
        )
        val originalImage = assertNotNull(imageStore.read(account.id, challenge.id)).bytes
        imageStore.clearEvents()
        val target = AopTestUtils.getTargetObject<CaptchaService>(service)

        TransactionTemplate(transactionManager).executeWithoutResult { transaction ->
            val answered = target.submitAnswer(account.id, challenge.id, "correct")
            assertEquals("ANSWERED", answered.status)
            assertContentEquals(originalImage, assertNotNull(imageStore.read(account.id, challenge.id)).bytes)
            transaction.setRollbackOnly()
        }

        assertContentEquals(originalImage, assertNotNull(imageStore.read(account.id, challenge.id)).bytes)
        assertEquals(
            "PENDING",
            assertNotNull(queryRepository.findOwnedByAccountIdAndId(account.id, challenge.id)).status,
        )
        assertEquals(emptyList(), imageStore.events)
    }

    private fun savedAccountWithCookie(loginId: String): HofAccountEntity {
        val account = accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = NOW,
            ),
        )
        cookieRepository.save(
            HofCookieEntity(
                account = account,
                name = "PHPSESSID",
                value = "session",
                domain = "sic.zerosic.com",
                path = "/ZeroHOF",
                updatedAt = NOW,
            ),
        )
        return account
    }

    private fun savedPendingChallenge(
        account: HofAccountEntity,
        createdAt: Instant,
    ): CaptchaChallengeEntity =
        challengeRepository.save(
            CaptchaChallengeEntity(
                account = account,
                status = "PENDING",
                prompt = "캡차를 입력해주세요.",
                imageUrl = "http://sic.zerosic.com/ZeroHOF/captcha.png",
                sourceUrl = POLICE_URL,
                answer = null,
                createdAt = createdAt,
                answeredAt = null,
                submitUrl = POLICE_URL,
                submitMethod = "POST",
                answerFieldName = "AnswerV",
            ),
        )

    private fun captchaHtml(
        tokenName: String,
        tokenValue: String,
    ): String =
        """
            <html><body>
              <p>캡차를 입력해주세요.</p>
              <form action="$POLICE_URL" method="post">
                <input type="hidden" name="$tokenName" value="$tokenValue">
                <input type="text" name="AnswerV" value="ignored-prefill">
                <input type="submit" name="AnswerOut" value="입니다.">
                <img src="http://sic.zerosic.com/ZeroHOF/captcha.png" alt="captcha">
              </form>
            </body></html>
        """.trimIndent()

    @TestConfiguration
    class BoundaryConfig {
        @Bean
        fun gateway(): FakeHofGateway = FakeHofGateway()

        @Bean
        fun binaryGateway(): FakeHofBinaryGateway = FakeHofBinaryGateway()

        @Bean
        fun imageStore(): FakeCaptchaImageFileStore = FakeCaptchaImageFileStore()

        @Bean
        fun timeProvider(): TimeProvider = TimeProvider { NOW }

        @Bean
        fun hofCookieCipher(): HofCookieCipher =
            HofCookieCipher(
                Base64.getEncoder().encodeToString(ByteArray(32) { index -> (index + 41).toByte() }),
            )
    }

    class FakeHofGateway : HofGateway {
        val requests = CopyOnWriteArrayList<HofRequest>()
        @Volatile
        var response: HofHttpResponse = successResponse()
        @Volatile
        var handler: ((HofRequest) -> HofHttpResponse)? = null

        override fun execute(request: HofRequest, cookies: Map<String, String>): HofHttpResponse {
            requests += request
            return handler?.invoke(request) ?: response
        }

        fun reset() {
            requests.clear()
            response = successResponse()
            handler = null
        }

        private fun successResponse(): HofHttpResponse =
            HofHttpResponse(
                statusCode = 200,
                finalUrl = POLICE_URL,
                body = "<html><body>통행증이 발급되었습니다.</body></html>",
                setCookies = emptyMap(),
            )
    }

    class FakeHofBinaryGateway : HofBinaryGateway {
        @Volatile
        var body: ByteArray = byteArrayOf(1, 2, 3)

        override fun get(url: String, cookies: Map<String, String>): HofBinaryResponse =
            HofBinaryResponse(
                statusCode = 200,
                finalUrl = url,
                contentType = "image/png",
                body = body,
            )

        fun reset() {
            body = byteArrayOf(1, 2, 3)
        }
    }

    class FakeCaptchaImageFileStore : CaptchaImageFileStore {
        private val files = ConcurrentHashMap<Pair<Long, Long>, StoredCaptchaImage>()
        val events = CopyOnWriteArrayList<String>()

        override fun save(
            accountId: Long,
            challengeId: Long,
            contentType: String,
            bytes: ByteArray,
        ) {
            files[accountId to challengeId] = StoredCaptchaImage(contentType, bytes)
            events += "save:$accountId:$challengeId"
        }

        override fun read(accountId: Long, challengeId: Long): StoredCaptchaImage? =
            files[accountId to challengeId]

        override fun delete(accountId: Long, challengeId: Long) {
            files.remove(accountId to challengeId)
            events += "delete:$accountId:$challengeId"
        }

        fun reset() {
            files.clear()
            events.clear()
        }

        fun clearEvents() {
            events.clear()
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-12T00:00:00Z")
        const val POLICE_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=police"
    }
}
