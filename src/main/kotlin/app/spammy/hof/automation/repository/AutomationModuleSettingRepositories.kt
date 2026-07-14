package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationModuleMapEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestMapEntity
import app.spammy.hof.common.persistence.CommandRepository

interface AutomationModuleMapCommandRepository : CommandRepository<AutomationModuleMapEntity, Long>

interface AutomationModuleQuestCommandRepository : CommandRepository<AutomationModuleQuestEntity, Long>

interface AutomationModuleQuestMapCommandRepository : CommandRepository<AutomationModuleQuestMapEntity, Long>
