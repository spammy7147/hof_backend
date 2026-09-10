package app.spammy.hof.push.kafka

import app.spammy.hof.automation.outbox.AutomationConsumedEventService
import app.spammy.hof.push.service.AndroidPushService
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.mockito.Mockito
import org.springframework.kafka.support.Acknowledgment
import tools.jackson.module.kotlin.jacksonObjectMapper

class PushRequestConsumerTest {
    private val consumed = Mockito.mock(AutomationConsumedEventService::class.java)
    private val pushes = Mockito.mock(AndroidPushService::class.java)
    private val acknowledgment = Mockito.mock(Acknowledgment::class.java)
    private val objectMapper = jacksonObjectMapper()
    private val consumer = PushRequestConsumer(objectMapper, consumed, pushes)

    @Test
    fun `captcha event is sent recorded and acknowledged`() {
        consumer.consume(payload(), acknowledgment)

        Mockito.verify(pushes).sendCaptchaRequired(7L, 91L, "event-1")
        Mockito.verify(consumed).record("event-1")
        Mockito.verify(acknowledgment).acknowledge()
    }

    @Test
    fun `already consumed event is acknowledged without another push`() {
        Mockito.`when`(consumed.wasConsumed("event-1")).thenReturn(true)

        consumer.consume(payload(), acknowledgment)

        Mockito.verifyNoInteractions(pushes)
        Mockito.verify(consumed, Mockito.never()).record(Mockito.anyString())
        Mockito.verify(acknowledgment).acknowledge()
    }

    @Test
    fun `failed push is neither recorded nor acknowledged`() {
        Mockito.doThrow(IllegalStateException("temporary"))
            .`when`(pushes).sendCaptchaRequired(7L, 91L, "event-1")

        assertFailsWith<IllegalStateException> { consumer.consume(payload(), acknowledgment) }

        Mockito.verify(consumed, Mockito.never()).record(Mockito.anyString())
        Mockito.verifyNoInteractions(acknowledgment)
    }

    private fun payload(): String = objectMapper.writeValueAsString(
        PushRequestEvent("event-1", 7L, "CAPTCHA_REQUIRED", 91L),
    )
}
