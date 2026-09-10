package app.spammy.hof.automation.outbox

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.auth.service.AccountExecutionAuthorizationReader
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.entity.TypedAutomationRuntimeStateEntity
import app.spammy.hof.automation.kafka.AutomationWakeupConsumer
import app.spammy.hof.automation.lease.AccountAutomationLeaseService
import app.spammy.hof.automation.service.UnifiedAutomationRunner
import app.spammy.hof.automation.service.UnifiedAutomationService
import app.spammy.hof.captcha.dto.CaptchaPassMaintenanceLastResult
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.captcha.repository.CaptchaQueryRepository
import app.spammy.hof.captcha.service.CaptchaNotificationGateway
import app.spammy.hof.captcha.service.CaptchaPassMaintenanceService
import app.spammy.hof.captcha.service.CaptchaPassTerminalService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.push.dto.RegisterAndroidPushTargetRequest
import app.spammy.hof.push.kafka.PushRequestConsumer
import app.spammy.hof.push.repository.DevicePushTargetQueryRepository
import app.spammy.hof.push.service.AndroidPushService
import app.spammy.hof.push.service.DevicePushTargetService
import app.spammy.hof.push.service.FirebaseAndroidMessageSender
import app.spammy.hof.push.service.OutboxCaptchaNotificationGateway
import app.spammy.hof.push.service.PushOutboxService
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.support.Acknowledgment
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

/** 브로커와 FCM 전송만 대체하고 정지·통행증 terminal·outbox·consumer 조립은 유지한다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(AutomationStopNotificationIntegrationTest.Config::class)
class AutomationStopNotificationIntegrationTest {
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var transactions: PlatformTransactionManager
    @Autowired private lateinit var automation: UnifiedAutomationService
    @Autowired private lateinit var maintenance: CaptchaPassMaintenanceService
    @Autowired private lateinit var terminal: CaptchaPassTerminalService
    @Autowired private lateinit var targets: DevicePushTargetService
    @Autowired private lateinit var pushes: PushOutboxService
    @Autowired private lateinit var wakes: AutomationOutboxService
    @Autowired private lateinit var query: AutomationOutboxQueryRepository
    @Autowired private lateinit var publisher: AutomationOutboxPublisher
    @Autowired private lateinit var marker: AutomationOutboxPublishMarker
    @Autowired private lateinit var clock: StopClock
    @MockitoBean private lateinit var hof: HofGateway
    @MockitoBean private lateinit var sender: FirebaseAndroidMessageSender
    @MockitoBean private lateinit var authorization: AccountExecutionAuthorizationReader

    @Test
    fun `정지는 계정의 깨우기만 취소하고 통행증 수동 입력과 로그인 알림을 끝까지 전달한다`() {
        Mockito.`when`(authorization.isExecutionAllowed(Mockito.anyLong())).thenReturn(true)
        val account = account(TypedAutomationLifecycle.RUNNING)
        val peer = account(TypedAutomationLifecycle.STOPPED)
        val challengeId = TransactionTemplate(transactions).execute {
            val challenge = CaptchaChallengeEntity(account = account, status = "READY", prompt = "통행증",
                challengeKind = CaptchaChallengeEntity.KIND_VIGILANTE_PASS, imageUrl = null,
                sourceUrl = "https://example.test/index.php", answer = null, createdAt = clock.now(), answeredAt = null)
            entityManager.persist(challenge)
            challenge.id
        }
        targets.register(account.id, RegisterAndroidPushTargetRequest("stop-notification", "fixture-fcm-token"))
        maintenance.setEnabled(account.id, true)
        val claim = assertNotNull(maintenance.claimDue(account.id))
        terminal.finishManualRequired(account.id, claim.token, challengeId)
        pushes.enqueueLoginRequired(account)
        val due = wakes.enqueue(account.id, "DUE")
        val future = wakes.enqueue(account.id, "FUTURE", clock.now().plusSeconds(60))
        val peerWake = wakes.enqueue(peer.id, "PEER")
        val published = wakes.enqueue(account.id, "ALREADY_PUBLISHED")
        marker.markPublished(published.id)
        val notifications = query.findUnpublished(clock.now()).filter { it.account.id == account.id && it.topic == PushOutboxService.PUSH_TOPIC }
        assertEquals(2, notifications.size)

        assertEquals(TypedAutomationLifecycle.RUNNING, automation.getTyped(account.id).runtime.lifecycle)
        assertEquals(TypedAutomationLifecycle.STOPPED, automation.stopTyped(account.id).runtime.lifecycle)

        assertNull(query.findById(due.id))
        assertNull(query.findById(future.id))
        assertNotNull(query.findById(peerWake.id))
        assertNotNull(query.findById(published.id)?.publishedAt)
        assertEquals(notifications.map { it.eventId }.toSet(), query.findUnpublished(clock.now())
            .filter { it.account.id == account.id }.map { it.eventId }.toSet())
        assertEquals(CaptchaPassMaintenanceLastResult.MANUAL_REQUIRED, maintenance.get(account.id).lastResult)
        assertEquals(challengeId, maintenance.get(account.id).manualChallengeId)

        publisher.publishBatch()

        notifications.forEach { event ->
            assertNotNull(query.findById(event.id)?.publishedAt)
            assertTrue(query.consumed(event.eventId))
        }
        val eventIds = notifications.associate { mapper.readTree(it.payload).path("type").asText() to it.eventId }
        Mockito.verify(sender).send(Mockito.eq("fixture-fcm-token") ?: "", Mockito.anyString(), Mockito.anyString(),
            Mockito.eq(mapOf("type" to "CAPTCHA_REQUIRED", "challengeId" to challengeId.toString(),
                "eventId" to eventIds.getValue("CAPTCHA_REQUIRED"))) ?: emptyMap())
        Mockito.verify(sender).send(Mockito.eq("fixture-fcm-token") ?: "", Mockito.anyString(), Mockito.anyString(),
            Mockito.eq(mapOf("type" to "LOGIN_REQUIRED", "eventId" to eventIds.getValue("LOGIN_REQUIRED"))) ?: emptyMap())

        // 이미 읽힌 깨우기가 늦게 도착해도 정지된 runtime은 새 HOF 행동을 제출하지 않는다.
        clock.current = clock.now().plusSeconds(120)
        val stale = wakes.enqueue(account.id, "LATE_DELIVERY_AFTER_STOP")
        publisher.publishBatch()
        assertTrue(query.consumed(stale.eventId))
        assertEquals(TypedAutomationLifecycle.STOPPED, automation.getTyped(account.id).runtime.lifecycle)
        Mockito.verifyNoInteractions(hof)
        Mockito.verifyNoMoreInteractions(sender)
    }

    private fun account(state: TypedAutomationLifecycle): HofAccountEntity = TransactionTemplate(transactions).execute {
        val account = HofAccountEntity(loginId = "stop-${UUID.randomUUID()}", encryptedPassword = "fixture", createdAt = clock.now())
        entityManager.persist(account)
        entityManager.persist(TypedAutomationRuntimeStateEntity(accountId = account.id, account = account,
            lifecycleStatus = state, stopReason = "MANUAL_STOP".takeIf { state == TypedAutomationLifecycle.STOPPED },
            createdAt = clock.now(), updatedAt = clock.now()))
        account
    }

    class StopClock(var current: Instant = Instant.parse("2026-09-09T00:00:00Z")) : TimeProvider {
        override fun now() = current
    }

    @TestConfiguration
    class Config {
        @Bean @Primary fun stopClock() = StopClock()
        @Bean @Primary fun notifications(pushes: PushOutboxService): CaptchaNotificationGateway = OutboxCaptchaNotificationGateway(pushes)
        @Bean @Primary
        fun replayTransport(mapper: ObjectMapper, consumed: AutomationConsumedEventService,
            lease: AccountAutomationLeaseService, runner: UnifiedAutomationRunner, clock: TimeProvider,
            sender: FirebaseAndroidMessageSender, targets: DevicePushTargetQueryRepository,
            targetService: DevicePushTargetService, challenges: CaptchaQueryRepository): AutomationOutboxTransport {
            val wakeConsumer = AutomationWakeupConsumer(mapper, consumed, lease, runner, clock)
            val pushConsumer = PushRequestConsumer(mapper, consumed, AndroidPushService(sender, targets, targetService, consumed, challenges))
            return object : AutomationOutboxTransport {
                override val supportedTopics: Set<String>? = null
                override fun publish(row: AutomationOutboxEntity) {
                    var acknowledged = false
                    val acknowledgment = Acknowledgment { acknowledged = true }
                    when (row.topic) {
                        AutomationOutboxService.WAKEUP_TOPIC -> wakeConsumer.consume(row.payload, acknowledgment)
                        PushOutboxService.PUSH_TOPIC -> pushConsumer.consume(row.payload, acknowledgment)
                        else -> error("알 수 없는 outbox topic: ${row.topic}")
                    }
                    check(acknowledged)
                }
            }
        }
    }
}
