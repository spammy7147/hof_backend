package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationActionRunEntity
import app.spammy.hof.common.persistence.CommandRepository

interface AutomationActionRunRepository : CommandRepository<AutomationActionRunEntity, Long>
