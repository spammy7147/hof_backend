package app.spammy.hof.automation.outbox

import app.spammy.hof.common.persistence.CommandRepository

interface AutomationOutboxRepository : CommandRepository<AutomationOutboxEntity, Long>
interface AutomationConsumedEventRepository : CommandRepository<AutomationConsumedEventEntity, String>
