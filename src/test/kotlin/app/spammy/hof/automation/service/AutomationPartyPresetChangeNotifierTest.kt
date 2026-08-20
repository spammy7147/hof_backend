package app.spammy.hof.automation.service

import app.spammy.hof.automation.outbox.AutomationOutboxService
import kotlin.test.Test
import org.mockito.Mockito

class AutomationPartyPresetChangeNotifierTest {
    private val lifecycle = Mockito.mock(AutomationWorkLifecycle::class.java)
    private val outbox = Mockito.mock(AutomationOutboxService::class.java)
    private val notifier = AutomationPartyPresetChangeNotifier(lifecycle, outbox)

    @Test
    fun `preset change makes a parked raid due and enqueues an immediate wakeup`() {
        notifier.changed(7)

        Mockito.verify(lifecycle).triggerRaidConfigurationCheck(7)
        Mockito.verify(outbox).enqueue(7, "PARTY_PRESET_UPDATED")
    }
}
