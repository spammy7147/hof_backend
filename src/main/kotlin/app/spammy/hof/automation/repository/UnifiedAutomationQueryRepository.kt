package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.entity.QAutomationJobEntity.automationJobEntity
import app.spammy.hof.automation.entity.QAutomationModuleConfigEntity.automationModuleConfigEntity
import app.spammy.hof.automation.entity.QAutomationProfileEntity.automationProfileEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.stereotype.Repository

@Repository
class UnifiedAutomationQueryRepository(
    private val queryFactory: JPAQueryFactory,
) {
    fun findProfile(accountId: Long): AutomationProfileEntity? =
        queryFactory.selectFrom(automationProfileEntity)
            .where(
                automationProfileEntity.account.id.eq(accountId),
                automationProfileEntity.mode.eq(UNIFIED_MODE),
            )
            .orderBy(automationProfileEntity.id.asc())
            .fetchFirst()

    fun findConfigs(profileId: Long): List<AutomationModuleConfigEntity> =
        queryFactory.selectFrom(automationModuleConfigEntity)
            .where(automationModuleConfigEntity.profile.id.eq(profileId))
            .orderBy(automationModuleConfigEntity.priority.asc(), automationModuleConfigEntity.id.asc())
            .fetch()

    fun findCurrentJob(accountId: Long): AutomationJobEntity? =
        queryFactory.selectFrom(automationJobEntity)
            .join(automationJobEntity.profile, automationProfileEntity).fetchJoin()
            .where(
                automationJobEntity.account.id.eq(accountId),
                automationJobEntity.status.`in`(ACTIVE_STATUSES),
            )
            .orderBy(automationJobEntity.updatedAt.desc(), automationJobEntity.id.desc())
            .fetchFirst()

    companion object {
        const val UNIFIED_MODE = "UNIFIED"
        val ACTIVE_STATUSES = setOf(
            "PENDING",
            "RUNNING",
            "WAITING_CAPTCHA",
            "WAITING_CONFIG",
            "WAITING_LOGIN",
            "PAUSED",
        )
    }
}
