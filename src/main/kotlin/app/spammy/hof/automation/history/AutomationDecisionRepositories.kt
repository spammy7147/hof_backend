package app.spammy.hof.automation.history

import app.spammy.hof.common.persistence.CommandRepository

interface AutomationDecisionCycleCommandRepository : CommandRepository<AutomationDecisionCycleEntity, Long>

interface AutomationDecisionEventCommandRepository : CommandRepository<AutomationDecisionEventEntity, Long>
