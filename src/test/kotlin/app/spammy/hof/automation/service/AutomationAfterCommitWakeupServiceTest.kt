package app.spammy.hof.automation.service

import app.spammy.hof.automation.port.AutomationWakeupPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.mockito.Mockito
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

class AutomationAfterCommitWakeupServiceTest {
    private val wakeupPort = Mockito.mock(AutomationWakeupPort::class.java)
    private val service = AutomationAfterCommitWakeupService(wakeupPort)

    @Test
    fun wakeDelegatesToTheConfiguredAdapter() {
        service.wake(17L, "MODULES_UPDATED")

        Mockito.verify(wakeupPort).wake(17L, "MODULES_UPDATED")
    }

    @Test
    fun wakeAlwaysStartsANewTransaction() {
        val method = AutomationAfterCommitWakeupService::class.java.getMethod(
            "wake",
            Long::class.javaPrimitiveType,
            String::class.java,
        )
        val transactional = assertNotNull(method.getAnnotation(Transactional::class.java))

        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation)
    }
}
