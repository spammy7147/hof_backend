package app.spammy.hof.config

import app.spammy.hof.automation.adapter.LocalAutomationWakeupAdapter
import app.spammy.hof.automation.adapter.UnifiedAutomationLocalWakeExecutor
import app.spammy.hof.automation.config.AutomationKafkaConfig
import app.spammy.hof.automation.kafka.AutomationWakeupConsumer
import app.spammy.hof.automation.kafka.KafkaAutomationWakeupAdapter
import app.spammy.hof.automation.outbox.KafkaAutomationOutboxTransport
import app.spammy.hof.automation.outbox.LocalAutomationOutboxTransport
import app.spammy.hof.automation.recovery.AutomationRecoveryScheduler
import app.spammy.hof.automation.recovery.AutomationSessionReconciliationScheduler
import app.spammy.hof.captcha.service.NoOpCaptchaNotificationGateway
import app.spammy.hof.push.config.FirebaseConfig
import app.spammy.hof.push.kafka.PushRequestConsumer
import app.spammy.hof.push.service.AndroidPushService
import app.spammy.hof.push.service.OutboxCaptchaNotificationGateway
import kotlin.test.Test
import kotlin.test.assertContentEquals
import org.springframework.context.annotation.Profile

class RuntimeProfileContractTest {
    @Test
    fun `shared kafka automation components run in dev and prod`() {
        listOf(
            AutomationKafkaConfig::class.java,
            AutomationWakeupConsumer::class.java,
            KafkaAutomationWakeupAdapter::class.java,
            KafkaAutomationOutboxTransport::class.java,
            AutomationRecoveryScheduler::class.java,
            AutomationSessionReconciliationScheduler::class.java,
        ).forEach { assertProfiles(it, "dev | prod") }
    }

    @Test
    fun `local automation components are test only`() {
        listOf(
            UnifiedAutomationLocalWakeExecutor::class.java,
            LocalAutomationWakeupAdapter::class.java,
            LocalAutomationOutboxTransport::class.java,
        ).forEach { assertProfiles(it, "test") }
    }

    @Test
    fun `captcha notification gateway follows runtime environment`() {
        assertProfiles(NoOpCaptchaNotificationGateway::class.java, "dev | test")
        assertProfiles(OutboxCaptchaNotificationGateway::class.java, "prod")
    }

    @Test
    fun `firebase delivery components are production only`() {
        listOf(
            FirebaseConfig::class.java,
            PushRequestConsumer::class.java,
            AndroidPushService::class.java,
        ).forEach { assertProfiles(it, "prod") }
    }

    private fun assertProfiles(type: Class<*>, vararg expected: String) {
        assertContentEquals(expected.toList(), type.getAnnotation(Profile::class.java).value.toList())
    }
}
