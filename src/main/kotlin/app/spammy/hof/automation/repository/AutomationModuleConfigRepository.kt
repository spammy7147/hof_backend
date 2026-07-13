package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.common.persistence.CommandRepository

interface AutomationModuleConfigRepository : CommandRepository<AutomationModuleConfigEntity, Long>
