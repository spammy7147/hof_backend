package app.spammy.hof.captcha.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.account.repository.HofCookieRepository
import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofCookieCipher
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.captcha.controller.CaptchaController
import app.spammy.hof.captcha.dto.CaptchaChallengeResponse
import app.spammy.hof.captcha.dto.SubmitCaptchaAnswerRequest
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.captcha.repository.CaptchaChallengeRepository
import app.spammy.hof.captcha.repository.CaptchaQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofBinaryGateway
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofBinaryResponse
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.status.service.HofStatusSnapshotService
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
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.util.AopTestUtils
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
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
    LoginStateParser::class,
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
    private lateinit var cookieQueryRepository: CookieQueryRepository

    @Autowired
    private lateinit var cookieCipher: HofCookieCipher

    @MockitoSpyBean
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
        val challenge = detectAndPrepare(account, "token", "abc")

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

        val answered = service.submitAnswer(account.id, challenge.id, "  7319  ", challenge.preparationVersion)

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
        val challenge = detectAndPrepare(account, "old_token", "old")
        imageStore.clearEvents()
        binaryGateway.body = byteArrayOf(9, 8, 7)
        gateway.response = HofHttpResponse(
            statusCode = 200,
            finalUrl = POLICE_URL,
            body = captchaHtml(tokenName = "new_token", tokenValue = "new"),
            setCookies = mapOf("PHPSESSID" to "updated-session"),
        )

        val refreshed = service.submitAnswer(account.id, challenge.id, "wrong", challenge.preparationVersion)

        assertEquals(challenge.id, refreshed.id)
        assertEquals("READY", refreshed.status)
        assertEquals(2, refreshed.preparationVersion)
        assertEquals(
            listOf("new_token" to "new", "AnswerV" to "", "AnswerOut" to "입니다."),
            queryRepository.findFormFields(challenge.id).map { it.fieldName to it.fieldValue },
        )
        assertEquals(3L, queryRepository.countFormFields(challenge.id))
        assertContentEquals(byteArrayOf(9, 8, 7), assertNotNull(imageStore.read(account.id, challenge.id, 2)).bytes)
        assertEquals(
            listOf("save:${account.id}:${challenge.id}:2", "delete:${account.id}:${challenge.id}:1"),
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
    fun repeatedDetectionReusesChallengeAndClearsPreparedMetadata() {
        val account = savedAccountWithCookie("captcha-service-repeat")
        val first = detectAndPrepare(account, "old_token", "old")
        imageStore.clearEvents()
        val second = assertNotNull(
            service.detectAndRecord(account, captchaHtml("new_token", "new"), POLICE_URL),
        )

        assertEquals(first.id, second.id)
        assertEquals("DETECTED", second.status)
        assertEquals(0, second.preparationVersion)
        assertEquals(1L, queryRepository.countActiveByAccountId(account.id))
        assertEquals(emptyList(), queryRepository.findFormFields(second.id))
        assertEquals(listOf("delete:${account.id}:${second.id}:1"), imageStore.events)

        gateway.response = HofHttpResponse(200, POLICE_URL, captchaHtml("new_token", "new"), emptyMap())
        val prepared = service.prepareCurrent(account.id)
        gateway.response = HofHttpResponse(200, POLICE_URL, "<html><body>통행증이 발급되었습니다.</body></html>", emptyMap())
        service.submitAnswer(account.id, prepared.id, "7319", prepared.preparationVersion)

        assertNull(service.findCurrent(account.id))
        assertEquals(0L, queryRepository.countActiveByAccountId(account.id))
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
        assertEquals(1L, queryRepository.countActiveByAccountId(account.id))
        assertNull(queryRepository.findOwnedByAccountIdAndId(account.id, stale.id))

        gateway.response = HofHttpResponse(200, POLICE_URL, captchaHtml("fresh_token", "fresh"), emptyMap())
        val prepared = service.prepareCurrent(account.id)
        gateway.response = HofHttpResponse(200, POLICE_URL, "<html><body>통행증이 발급되었습니다.</body></html>", emptyMap())
        service.submitAnswer(account.id, prepared.id, "correct", prepared.preparationVersion)

        assertNull(service.findCurrent(account.id))
    }

    @Test
    fun proactiveDueAndReactiveBattleRaceConvergesOnOnePreparationAndOneSubmission() {
        val account = savedAccountWithCookie("captcha-service-concurrent-detect")
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(
                executor.submit<CaptchaChallengeResponse> {
                    start.await()
                    assertNotNull(
                        service.detectAndRecord(
                            account,
                            captchaHtml("token_a", "a"),
                            POLICE_URL,
                            notifyAutomation = false,
                        ),
                    )
                },
                executor.submit<CaptchaChallengeResponse> {
                    start.await()
                    assertNotNull(
                        service.detectAndRecord(
                            account,
                            captchaHtml("token_b", "b"),
                            POLICE_URL,
                            notifyAutomation = true,
                        ),
                    )
                },
            )

            start.countDown()
            val responses = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, responses.map { it.id }.distinct().size)
            assertEquals(1L, queryRepository.countActiveByAccountId(account.id))
            val fields = queryRepository.findFormFields(responses.first().id)
            assertEquals(emptyList(), fields)

            gateway.response = HofHttpResponse(200, POLICE_URL, captchaHtml("fresh_token", "fresh"), emptyMap())
            val prepared = service.prepareCurrent(account.id)
            assertEquals(responses.first().id, prepared.id)
            assertEquals(1, prepared.preparationVersion)

            gateway.response = HofHttpResponse(
                200,
                POLICE_URL,
                "<html><body>통행증이 발급되었습니다.</body></html>",
                emptyMap(),
            )
            val answered = service.submitAnswer(account.id, prepared.id, "correct", prepared.preparationVersion)

            assertEquals("ANSWERED", answered.status)
            assertEquals(1, gateway.requests.count { request -> request.method == HofHttpMethod.POST })
            assertNull(service.findCurrent(account.id))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentSubmissionCallsHofOnceAndSecondSubmitObservesAnsweredStatus() {
        val account = savedAccountWithCookie("captcha-service-concurrent-submit")
        val challenge = detectAndPrepare(account, "token", "submit")
        gateway.response = HofHttpResponse(200, POLICE_URL, "<html><body>통행증이 발급되었습니다.</body></html>", emptyMap())
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
                    runCatching { service.submitAnswer(account.id, challenge.id, "answer-$attempt", challenge.preparationVersion) }
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
    fun requiresNewDetectedChallengeSurvivesOuterRollbackWithoutPreparingFields() {
        val account = savedAccountWithCookie("captcha-service-requires-new")
        var detected: CaptchaChallengeResponse? = null

        TransactionTemplate(transactionManager).executeWithoutResult { transaction ->
            detected = service.detectAndRecord(account, captchaHtml("token", "committed"), POLICE_URL)
            transaction.setRollbackOnly()
        }

        val challenge = assertNotNull(queryRepository.findLatestActiveByAccountId(account.id))
        assertEquals(assertNotNull(detected).id, challenge.id)
        assertEquals("DETECTED", challenge.status)
        assertEquals(0L, queryRepository.countFormFields(challenge.id))
        assertNull(imageStore.read(account.id, challenge.id))
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

        assertNull(queryRepository.findLatestActiveByAccountId(account.id))
        assertNull(imageStore.read(account.id, assertNotNull(rolledBackChallengeId)))
        assertEquals(emptyList(), imageStore.events)
    }

    @Test
    fun rolledBackFailedRefreshPreservesPriorImageAndFields() {
        val account = savedAccountWithCookie("captcha-service-rollback-refresh")
        val challenge = detectAndPrepare(account, "old_token", "old")
        val originalImage = assertNotNull(imageStore.read(account.id, challenge.id, 1)).bytes
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
            target.submitAnswer(account.id, challenge.id, "wrong", challenge.preparationVersion)
            assertContentEquals(originalImage, assertNotNull(imageStore.read(account.id, challenge.id, 1)).bytes)
            transaction.setRollbackOnly()
        }

        assertContentEquals(originalImage, assertNotNull(imageStore.read(account.id, challenge.id, 1)).bytes)
        assertEquals(
            listOf("old_token" to "old", "AnswerV" to "", "AnswerOut" to "입니다."),
            queryRepository.findFormFields(challenge.id).map { it.fieldName to it.fieldValue },
        )
        assertEquals(
            listOf("save:${account.id}:${challenge.id}:2", "delete:${account.id}:${challenge.id}:2"),
            imageStore.events,
        )
    }

    @Test
    fun rolledBackSuccessfulAnswerPreservesPriorImageAndReadyStatus() {
        val account = savedAccountWithCookie("captcha-service-rollback-success")
        val challenge = detectAndPrepare(account, "token", "success-rollback")
        gateway.response = HofHttpResponse(200, POLICE_URL, "<html><body>통행증이 발급되었습니다.</body></html>", emptyMap())
        val originalImage = assertNotNull(imageStore.read(account.id, challenge.id, 1)).bytes
        imageStore.clearEvents()
        val target = AopTestUtils.getTargetObject<CaptchaService>(service)

        TransactionTemplate(transactionManager).executeWithoutResult { transaction ->
            val answered = target.submitAnswer(account.id, challenge.id, "correct", challenge.preparationVersion)
            assertEquals("ANSWERED", answered.status)
            assertContentEquals(originalImage, assertNotNull(imageStore.read(account.id, challenge.id, 1)).bytes)
            transaction.setRollbackOnly()
        }

        assertContentEquals(originalImage, assertNotNull(imageStore.read(account.id, challenge.id, 1)).bytes)
        assertEquals(
            "READY",
            assertNotNull(queryRepository.findOwnedByAccountIdAndId(account.id, challenge.id)).status,
        )
        assertEquals(emptyList(), imageStore.events)
    }

    @Test
    fun controllerInvalidatesCommittedPreparationAfterPoliceFollowUpControlFailure() {
        val account = savedAccountWithCookie("captcha-service-consumed-control")
        val challenge = detectAndPrepare(account, "token", "consumed")
        imageStore.clearEvents()
        val signal = ApiException(ErrorCode.HOF_TEMPORARILY_UNAVAILABLE, "retry later")
        gateway.handler = { request ->
            if (request.method == HofHttpMethod.POST) {
                HofHttpResponse(
                    statusCode = 200,
                    finalUrl = POLICE_URL,
                    body = """
                        <html><body>
                          <font color="red">자경단</font>
                          <p>자경단에서 통행증을 발급받아주세요.</p>
                        </body></html>
                    """.trimIndent(),
                    setCookies = mapOf(
                        "PHPSESSID" to "rotated-session",
                        "NEW_SESSION" to "new-cookie",
                    ),
                )
            } else {
                throw signal
            }
        }
        val controller = CaptchaController(
            service,
            HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java)),
            Mockito.mock(CaptchaAutoSolveCoordinator::class.java),
        )

        val actual = assertFailsWith<ApiException> {
            controller.submitAnswer(
                account.id,
                challenge.id,
                SubmitCaptchaAnswerRequest("7319", challenge.preparationVersion),
            )
        }

        assertSame(signal, actual)
        val invalidated = assertNotNull(queryRepository.findOwnedByAccountIdAndId(account.id, challenge.id))
        assertEquals("DETECTED", invalidated.status)
        assertEquals(0, invalidated.preparationVersion)
        assertNull(invalidated.imageUrl)
        assertNull(invalidated.submitUrl)
        assertEquals(emptyList(), queryRepository.findFormFields(challenge.id))
        assertNull(imageStore.read(account.id, challenge.id, challenge.preparationVersion))
        assertEquals(listOf("delete:${account.id}:${challenge.id}:${challenge.preparationVersion}"), imageStore.events)
        assertEquals(2, gateway.requests.size)
        val persistedCookies = cookieQueryRepository.findByAccountId(account.id)
        assertTrue(persistedCookies.all { cookie -> cookieCipher.isEncrypted(cookie.value) })
        assertEquals(
            mapOf("NEW_SESSION" to "new-cookie", "PHPSESSID" to "rotated-session"),
            persistedCookies
                .associate { cookie -> cookie.name to cookieCipher.decrypt(cookie.value) },
        )
    }

    @Test
    fun consumedRecoveryKeepsNewerPreparationWhilePersistingResponseCookies() {
        val account = savedAccountWithCookie("captcha-service-consumed-version-mismatch")
        val consumed = detectAndPrepare(account, "old_token", "old")
        binaryGateway.body = byteArrayOf(9, 8, 7)
        gateway.response = HofHttpResponse(
            statusCode = 200,
            finalUrl = POLICE_URL,
            body = captchaHtml("new_token", "new"),
            setCookies = emptyMap(),
        )
        val newer = service.submitAnswer(account.id, consumed.id, "wrong", consumed.preparationVersion)
        val newerImageUrl = assertNotNull(
            queryRepository.findOwnedByAccountIdAndId(account.id, consumed.id),
        ).imageUrl
        val fieldsBeforeRecovery = queryRepository.findFormFields(consumed.id)
            .map { field -> Triple(field.fieldOrder, field.fieldName, field.fieldValue) }
        imageStore.clearEvents()
        val currentSession = cookieQueryRepository.findByAccountId(account.id)
            .single { cookie -> cookie.name == "PHPSESSID" }
        currentSession.value = cookieCipher.encrypt("newer-session")
        cookieRepository.save(currentSession)
        cookieRepository.save(
            HofCookieEntity(
                account = account,
                name = "NEW_SESSION",
                value = cookieCipher.encrypt("newer-cookie"),
                domain = "sic.zerosic.com",
                path = "/ZeroHOF",
                updatedAt = NOW.plusSeconds(1),
            ),
        )

        service.recoverConsumedPreparation(
            account.id,
            consumed.id,
            consumed.preparationVersion,
            mapOf("PHPSESSID" to "session"),
            mapOf(
                "PHPSESSID" to "stale-rotation",
                "NEW_SESSION" to "stale-new-cookie",
                "RECOVERABLE" to "recovered-cookie",
            ),
        )

        val stored = assertNotNull(queryRepository.findOwnedByAccountIdAndId(account.id, consumed.id))
        assertEquals("READY", stored.status)
        assertEquals(newer.preparationVersion, stored.preparationVersion)
        assertEquals(newerImageUrl, stored.imageUrl)
        assertEquals(
            fieldsBeforeRecovery,
            queryRepository.findFormFields(consumed.id)
                .map { field -> Triple(field.fieldOrder, field.fieldName, field.fieldValue) },
        )
        assertNotNull(imageStore.read(account.id, consumed.id, newer.preparationVersion))
        assertEquals(emptyList(), imageStore.events)
        assertEquals(
            mapOf(
                "NEW_SESSION" to "newer-cookie",
                "PHPSESSID" to "newer-session",
                "RECOVERABLE" to "recovered-cookie",
            ),
            cookieQueryRepository.findByAccountId(account.id)
                .associate { cookie -> cookie.name to cookieCipher.decrypt(cookie.value) },
        )
    }

    @Test
    fun submitAndConsumedRecoveryUseTheSameAccountThenChallengeLockOrder() {
        val account = savedAccountWithCookie("captcha-service-lock-order")
        val challenge = detectAndPrepare(account, "token", "lock-order")
        imageStore.clearEvents()
        val recoveryReachedChallenge = CountDownLatch(1)
        val allowRecoveryChallengeLock = CountDownLatch(1)
        val submitAttemptedAccountLock = CountDownLatch(1)
        Mockito.doAnswer { invocation ->
            if (Thread.currentThread().name == "captcha-recovery-lock-test") {
                recoveryReachedChallenge.countDown()
                check(allowRecoveryChallengeLock.await(5, TimeUnit.SECONDS))
            }
            invocation.callRealMethod()
        }.`when`(queryRepository).findOwnedByAccountIdAndIdForUpdate(account.id, challenge.id)
        Mockito.doAnswer { invocation ->
            if (Thread.currentThread().name == "captcha-submit-lock-test") {
                submitAttemptedAccountLock.countDown()
            }
            invocation.callRealMethod()
        }.`when`(queryRepository).findAccountByIdForUpdate(account.id)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val recovery = executor.submit<Unit> {
                Thread.currentThread().name = "captcha-recovery-lock-test"
                service.recoverConsumedPreparation(
                    account.id,
                    challenge.id,
                    challenge.preparationVersion,
                    mapOf("PHPSESSID" to "session"),
                    mapOf("PHPSESSID" to "rotated-session"),
                )
            }
            assertTrue(recoveryReachedChallenge.await(5, TimeUnit.SECONDS))
            val submit = executor.submit<Result<CaptchaChallengeResponse>> {
                Thread.currentThread().name = "captcha-submit-lock-test"
                runCatching {
                    service.submitAnswer(account.id, challenge.id, "answer", challenge.preparationVersion)
                }
            }
            assertTrue(submitAttemptedAccountLock.await(5, TimeUnit.SECONDS))
            allowRecoveryChallengeLock.countDown()

            recovery.get(10, TimeUnit.SECONDS)
            val submitFailure = assertNotNull(submit.get(10, TimeUnit.SECONDS).exceptionOrNull())
            assertTrue(submitFailure is ApiException)
            assertEquals(ErrorCode.INVALID_REQUEST, submitFailure.errorCode)
            val stored = assertNotNull(queryRepository.findOwnedByAccountIdAndId(account.id, challenge.id))
            assertEquals("DETECTED", stored.status)
            assertEquals(0, stored.preparationVersion)
            assertEquals(emptyList(), gateway.requests)
            assertEquals(
                "rotated-session",
                cookieCipher.decrypt(
                    cookieQueryRepository.findByAccountId(account.id)
                        .single { cookie -> cookie.name == "PHPSESSID" }
                        .value,
                ),
            )
        } finally {
            allowRecoveryChallengeLock.countDown()
            executor.shutdownNow()
        }
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

    private fun detectAndPrepare(
        account: HofAccountEntity,
        tokenName: String,
        tokenValue: String,
    ): CaptchaChallengeResponse {
        val detected = assertNotNull(
            service.detectAndRecord(account, captchaHtml(tokenName, tokenValue), POLICE_URL),
        )
        gateway.response = HofHttpResponse(
            statusCode = 200,
            finalUrl = POLICE_URL,
            body = captchaHtml(tokenName, tokenValue),
            setCookies = emptyMap(),
        )
        val prepared = service.prepareCurrent(account.id)
        assertEquals(detected.id, prepared.id)
        gateway.requests.clear()
        return prepared
    }

    private fun savedPendingChallenge(
        account: HofAccountEntity,
        createdAt: Instant,
    ): CaptchaChallengeEntity =
        challengeRepository.save(
            CaptchaChallengeEntity(
                account = account,
                status = "READY",
                prompt = "캡차를 입력해주세요.",
                imageUrl = "http://sic.zerosic.com/ZeroHOF/captcha.png",
                sourceUrl = POLICE_URL,
                answer = null,
                createdAt = createdAt,
                answeredAt = null,
                submitUrl = POLICE_URL,
                submitMethod = "POST",
                answerFieldName = "AnswerV",
                preparationVersion = 1,
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
        fun accountGateway(
            gateway: FakeHofGateway,
            timeProvider: TimeProvider,
        ): AccountHofGateway =
            AccountHofGateway(
                gateway,
                org.mockito.Mockito.mock(HofStatusSnapshotService::class.java),
                org.mockito.Mockito.mock(app.spammy.hof.character.service.CharacterRosterObservationService::class.java),
                timeProvider,
            )

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

        override fun execute(
            accountId: Long,
            request: HofRequest,
            cookies: Map<String, String>,
        ): HofHttpResponse {
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

        override fun get(
            accountId: Long,
            origin: HofRequestOrigin,
            url: String,
            cookies: Map<String, String>,
        ): HofBinaryResponse =
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
        private val files = ConcurrentHashMap<Triple<Long, Long, Int>, StoredCaptchaImage>()
        val events = CopyOnWriteArrayList<String>()

        override fun save(
            accountId: Long,
            challengeId: Long,
            preparationVersion: Int,
            contentType: String,
            bytes: ByteArray,
        ) {
            files[Triple(accountId, challengeId, preparationVersion)] = StoredCaptchaImage(contentType, bytes)
            events += event("save", accountId, challengeId, preparationVersion)
        }

        override fun read(accountId: Long, challengeId: Long, preparationVersion: Int): StoredCaptchaImage? =
            files[Triple(accountId, challengeId, preparationVersion)]

        override fun delete(accountId: Long, challengeId: Long, preparationVersion: Int) {
            files.remove(Triple(accountId, challengeId, preparationVersion))
            events += event("delete", accountId, challengeId, preparationVersion)
        }

        fun reset() {
            files.clear()
            events.clear()
        }

        fun clearEvents() {
            events.clear()
        }

        private fun event(action: String, accountId: Long, challengeId: Long, preparationVersion: Int): String =
            if (preparationVersion == 0) {
                "$action:$accountId:$challengeId"
            } else {
                "$action:$accountId:$challengeId:$preparationVersion"
            }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-12T00:00:00Z")
        const val POLICE_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=police"
    }
}
