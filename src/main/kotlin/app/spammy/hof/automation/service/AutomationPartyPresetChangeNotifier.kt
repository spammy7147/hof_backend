package app.spammy.hof.automation.service

import app.spammy.hof.automation.outbox.AutomationOutboxService
import app.spammy.hof.party.service.PartyPresetChangeNotifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AutomationPartyPresetChangeNotifier(
    private val lifecycle: AutomationWorkLifecycle,
    private val outbox: AutomationOutboxService,
) : PartyPresetChangeNotifier {
    @Transactional
    override fun changed(accountId: Long) {
        lifecycle.triggerRaidConfigurationCheck(accountId)
        outbox.enqueue(accountId, PARTY_PRESET_UPDATED)
    }

    private companion object {
        const val PARTY_PRESET_UPDATED = "PARTY_PRESET_UPDATED"
    }
}
