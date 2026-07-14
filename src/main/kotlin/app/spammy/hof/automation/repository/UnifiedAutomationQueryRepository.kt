package app.spammy.hof.automation.repository

import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationModuleMapEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestMapEntity
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.entity.QAutomationJobEntity.automationJobEntity
import app.spammy.hof.automation.entity.QAutomationModuleConfigEntity.automationModuleConfigEntity
import app.spammy.hof.automation.entity.QAutomationModuleMapEntity.automationModuleMapEntity
import app.spammy.hof.automation.entity.QAutomationModuleQuestEntity.automationModuleQuestEntity
import app.spammy.hof.automation.entity.QAutomationModuleQuestMapEntity.automationModuleQuestMapEntity
import app.spammy.hof.automation.entity.QAutomationProfileEntity.automationProfileEntity
import app.spammy.hof.battle.entity.QBattleMapEntity.battleMapEntity
import app.spammy.hof.party.entity.QPartyPresetEntity.partyPresetEntity
import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Repository

data class AutomationModuleAggregate(
    val config: AutomationModuleConfigEntity,
    val maps: List<AutomationModuleMapEntity>,
    val quests: List<AutomationModuleQuestAggregate>,
)

data class AutomationModuleQuestAggregate(
    val quest: AutomationModuleQuestEntity,
    val maps: List<AutomationModuleQuestMapEntity>,
)

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

    /**
     * 모듈 순서가 바뀌는 트랜잭션 동안 계정의 통합 프로필 행을 쓰기 잠금으로 점유한다.
     *
     * 우선순위 계산과 전체 순서 교체가 여러 앱 인스턴스에서 동시에 실행되어도 같은 프로필의 변경은
     * 직렬화된다. 잠금은 서비스의 create/update/delete/reorder 진입점에서 가장 먼저 획득한다.
     */
    fun findProfileForUpdate(accountId: Long): AutomationProfileEntity? =
        queryFactory.selectFrom(automationProfileEntity)
            .where(
                automationProfileEntity.account.id.eq(accountId),
                automationProfileEntity.mode.eq(UNIFIED_MODE),
            )
            .orderBy(automationProfileEntity.id.asc())
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetchFirst()

    fun findConfigs(profileId: Long): List<AutomationModuleConfigEntity> =
        queryFactory.selectFrom(automationModuleConfigEntity)
            .where(automationModuleConfigEntity.profile.id.eq(profileId))
            .orderBy(automationModuleConfigEntity.priority.asc(), automationModuleConfigEntity.id.asc())
            .fetch()

    fun findModules(profileId: Long): List<AutomationModuleAggregate> =
        assembleModules(findConfigs(profileId))

    fun findModule(
        accountId: Long,
        moduleId: Long,
    ): AutomationModuleAggregate? {
        val config = queryFactory.selectFrom(automationModuleConfigEntity)
            .where(
                automationModuleConfigEntity.id.eq(moduleId),
                automationModuleConfigEntity.profile.account.id.eq(accountId),
                automationModuleConfigEntity.profile.mode.eq(UNIFIED_MODE),
            )
            .fetchOne()
            ?: return null

        return assembleModules(listOf(config)).single()
    }

    fun findCurrentJob(accountId: Long): AutomationJobEntity? =
        queryFactory.selectFrom(automationJobEntity)
            .join(automationJobEntity.profile, automationProfileEntity).fetchJoin()
            .where(
                automationJobEntity.account.id.eq(accountId),
                automationJobEntity.status.`in`(ACTIVE_STATUSES),
            )
            .orderBy(automationJobEntity.updatedAt.desc(), automationJobEntity.id.desc())
            .fetchFirst()

    private fun assembleModules(configs: List<AutomationModuleConfigEntity>): List<AutomationModuleAggregate> {
        if (configs.isEmpty()) return emptyList()

        val configIds = configs.map(AutomationModuleConfigEntity::id)
        val mapsByConfigId = findMaps(configIds).groupBy { it.moduleConfig.id }
        val quests = findQuests(configIds)
        val questMapsByQuestId = findQuestMaps(quests.map(AutomationModuleQuestEntity::id))
            .groupBy { it.moduleQuest.id }
        val questsByConfigId = quests.groupBy { it.moduleConfig.id }

        return configs.map { config ->
            AutomationModuleAggregate(
                config = config,
                maps = mapsByConfigId[config.id].orEmpty(),
                quests = questsByConfigId[config.id].orEmpty().map { quest ->
                    AutomationModuleQuestAggregate(
                        quest = quest,
                        maps = questMapsByQuestId[quest.id].orEmpty(),
                    )
                },
            )
        }
    }

    private fun findMaps(configIds: Collection<Long>): List<AutomationModuleMapEntity> =
        queryFactory.selectFrom(automationModuleMapEntity)
            .join(automationModuleMapEntity.battleMap, battleMapEntity).fetchJoin()
            .leftJoin(automationModuleMapEntity.partyPreset, partyPresetEntity).fetchJoin()
            .where(automationModuleMapEntity.moduleConfig.id.`in`(configIds))
            .orderBy(
                automationModuleMapEntity.moduleConfig.id.asc(),
                automationModuleMapEntity.executionOrder.asc(),
                automationModuleMapEntity.id.asc(),
            )
            .fetch()

    private fun findQuests(configIds: Collection<Long>): List<AutomationModuleQuestEntity> =
        queryFactory.selectFrom(automationModuleQuestEntity)
            .where(automationModuleQuestEntity.moduleConfig.id.`in`(configIds))
            .orderBy(
                automationModuleQuestEntity.moduleConfig.id.asc(),
                automationModuleQuestEntity.executionOrder.asc(),
                automationModuleQuestEntity.id.asc(),
            )
            .fetch()

    private fun findQuestMaps(questIds: Collection<Long>): List<AutomationModuleQuestMapEntity> {
        if (questIds.isEmpty()) return emptyList()

        return queryFactory.selectFrom(automationModuleQuestMapEntity)
            .join(automationModuleQuestMapEntity.battleMap, battleMapEntity).fetchJoin()
            .leftJoin(automationModuleQuestMapEntity.partyPreset, partyPresetEntity).fetchJoin()
            .where(automationModuleQuestMapEntity.moduleQuest.id.`in`(questIds))
            .orderBy(
                automationModuleQuestMapEntity.moduleQuest.id.asc(),
                automationModuleQuestMapEntity.executionOrder.asc(),
                automationModuleQuestMapEntity.id.asc(),
            )
            .fetch()
    }

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
