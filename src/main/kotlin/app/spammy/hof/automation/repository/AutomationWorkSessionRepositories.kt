package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationWorkSessionEntity
import app.spammy.hof.common.persistence.CommandRepository

interface AutomationWorkSessionCommandRepository : CommandRepository<AutomationWorkSessionEntity, Long>

