package app.spammy.hof.automation.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationActionRunEntity
import app.spammy.hof.automation.entity.AutomationActionStatus
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationModuleMapEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestEntity
import app.spammy.hof.automation.entity.AutomationModuleQuestMapEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.repository.BattleMapRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.repository.PartyPresetRepository
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, UnifiedAutomationQueryRepository::class)
class UnifiedAutomationPersistenceTest {
    @Autowired private lateinit var accountRepository: HofAccountRepository
    @Autowired private lateinit var profileRepository: AutomationProfileRepository
    @Autowired private lateinit var jobRepository: AutomationJobRepository
    @Autowired private lateinit var moduleConfigRepository: AutomationModuleConfigRepository
    @Autowired private lateinit var moduleMapRepository: AutomationModuleMapCommandRepository
    @Autowired private lateinit var moduleQuestRepository: AutomationModuleQuestCommandRepository
    @Autowired private lateinit var moduleQuestMapRepository: AutomationModuleQuestMapCommandRepository
    @Autowired private lateinit var actionRunRepository: AutomationActionRunRepository
    @Autowired private lateinit var battleMapRepository: BattleMapRepository
    @Autowired private lateinit var partyPresetRepository: PartyPresetRepository
    @Autowired private lateinit var queryRepository: UnifiedAutomationQueryRepository
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun persistsEnoughStateToResumeTheSameActionAfterARestart() {
        val now = Instant.parse("2026-07-13T00:00:00Z")
        val account = accountRepository.save(
            HofAccountEntity(loginId = "resume-account", encryptedPassword = "encrypted", createdAt = now),
        )
        val profile = profileRepository.save(
            AutomationProfileEntity(
                account = account,
                name = "통합 자동화",
                mode = "UNIFIED",
                enabled = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
        moduleConfigRepository.save(
            AutomationModuleConfigEntity(
                profile = profile,
                moduleType = AutomationModuleType.KEY_QUEST,
                enabled = true,
                priority = 0,
                displayName = "열쇠 퀘스트",
                thresholdPercent = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val job = jobRepository.save(
            AutomationJobEntity(
                account = account,
                profile = profile,
                status = "RUNNING",
                currentStepIndex = 0,
                message = null,
                createdAt = now,
                startedAt = now,
                updatedAt = now,
                finishedAt = null,
                currentModule = AutomationModuleType.KEY_QUEST.name,
                currentAction = "BATTLE:Noble1021",
                nextRunAt = now.plusSeconds(5),
                lastHeartbeatAt = now,
            ),
        )
        actionRunRepository.save(
            AutomationActionRunEntity(
                job = job,
                moduleType = AutomationModuleType.KEY_QUEST,
                actionType = "BATTLE",
                actionKey = "Noble1021",
                status = AutomationActionStatus.RETRY_WAIT,
                requestKey = "job:${job.id}:step:0",
                payloadJson = "{\"mapCode\":\"Noble1021\"}",
                attemptCount = 1,
                nextAttemptAt = now.plusSeconds(5),
                lastError = "temporary failure",
                createdAt = now,
                updatedAt = now,
            ),
        )
        entityManager.flush()
        entityManager.clear()

        val restoredJob = entityManager.find(AutomationJobEntity::class.java, job.id)
        val restoredConfig = queryRepository.findModules(profile.id).single().config
        val restoredAction = entityManager.createQuery(
            "select a from AutomationActionRunEntity a where a.job.id = :jobId",
            AutomationActionRunEntity::class.java,
        ).setParameter("jobId", job.id).singleResult

        assertNotNull(restoredJob)
        assertEquals("BATTLE:Noble1021", restoredJob.currentAction)
        assertEquals(now.plusSeconds(5), restoredJob.nextRunAt)
        assertEquals(AutomationModuleType.KEY_QUEST, restoredConfig.moduleType)
        assertEquals(AutomationActionStatus.RETRY_WAIT, restoredAction.status)
        assertEquals("job:${job.id}:step:0", restoredAction.requestKey)
        assertEquals(1, restoredAction.attemptCount)
    }

    @Test
    fun findsUnifiedProfileWithAPessimisticWriteLock() {
        val now = Instant.parse("2026-07-13T00:30:00Z")
        val account = newAccount("locked-unified-profile", now)
        val unified = newProfile(account, now)
        newProfile(account, now, mode = "TIME_BURN")
        entityManager.flush()
        entityManager.clear()

        val locked = queryRepository.findProfileForUpdate(account.id)

        assertEquals(unified.id, locked?.id)
        assertEquals("UNIFIED", locked?.mode)
    }

    @Test
    fun persistsMultipleModulesOfTheSameTypeInPriorityOrder() {
        val now = Instant.parse("2026-07-13T01:00:00Z")
        val account = newAccount("duplicate-module-account", now)
        val profile = newProfile(account, now)
        moduleConfigRepository.saveAll(
            listOf(
                newModule(profile, "야간 소모", priority = 8, thresholdPercent = 25, now = now),
                newModule(profile, "주간 소모", priority = 2, thresholdPercent = 75, now = now),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        val modules = queryRepository.findModules(profile.id)

        assertEquals(listOf("주간 소모", "야간 소모"), modules.map { it.config.displayName })
        assertEquals(listOf(2, 8), modules.map { it.config.priority })
        assertEquals(
            listOf(AutomationModuleType.TIME_BURN, AutomationModuleType.TIME_BURN),
            modules.map { it.config.moduleType },
        )
        assertEquals(listOf(emptyList(), emptyList()), modules.map { it.maps })
        assertEquals(listOf(emptyList(), emptyList()), modules.map { it.quests })
    }

    @Test
    fun reconstructsOwnedModuleWithMapChildrenInExecutionOrder() {
        val now = Instant.parse("2026-07-13T02:00:00Z")
        val account = newAccount("module-map-account", now)
        val otherAccount = newAccount("other-module-map-account", now)
        val profile = newProfile(account, now)
        val module = moduleConfigRepository.save(
            newModule(profile, "시간 소모 A", priority = 0, thresholdPercent = 40, now = now),
        )
        val laterMap = battleMapRepository.save(newBattleMap("time-later", "후순위 맵", now))
        val earlierMap = battleMapRepository.save(newBattleMap("time-earlier", "선행 맵", now))
        moduleMapRepository.saveAll(
            listOf(
                AutomationModuleMapEntity(
                    moduleConfig = module,
                    battleMap = laterMap,
                    partyPreset = null,
                    executionOrder = 4,
                ),
                AutomationModuleMapEntity(
                    moduleConfig = module,
                    battleMap = earlierMap,
                    partyPreset = null,
                    executionOrder = 1,
                ),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        val aggregate = assertNotNull(queryRepository.findModule(account.id, module.id))

        assertEquals("시간 소모 A", aggregate.config.displayName)
        assertEquals(40, aggregate.config.thresholdPercent)
        assertEquals(listOf("time-earlier", "time-later"), aggregate.maps.map { it.battleMap.mapCode })
        assertEquals(emptyList(), aggregate.quests)
        assertNull(queryRepository.findModule(otherAccount.id, module.id))
    }

    @Test
    fun findModuleDoesNotReturnAnotherModeForTheSameAccount() {
        val now = Instant.parse("2026-07-13T02:30:00Z")
        val account = newAccount("other-mode-module-account", now)
        val profile = newProfile(account, now, mode = "TIME_BURN")
        val module = moduleConfigRepository.save(
            newModule(profile, "기존 시간 소모", priority = 0, thresholdPercent = 50, now = now),
        )
        entityManager.flush()
        entityManager.clear()

        assertNull(queryRepository.findModule(account.id, module.id))
    }

    @Test
    fun reconstructsQuestMapsInQuestAndMapExecutionOrder() {
        val now = Instant.parse("2026-07-13T03:00:00Z")
        val account = newAccount("module-quest-account", now)
        val profile = newProfile(account, now)
        val module = moduleConfigRepository.save(
            AutomationModuleConfigEntity(
                profile = profile,
                moduleType = AutomationModuleType.KEY_QUEST,
                enabled = true,
                priority = 0,
                displayName = "열쇠 퀘스트",
                thresholdPercent = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val laterQuest = moduleQuestRepository.save(
            AutomationModuleQuestEntity(moduleConfig = module, questCode = "0571", executionOrder = 3),
        )
        val earlierQuest = moduleQuestRepository.save(
            AutomationModuleQuestEntity(moduleConfig = module, questCode = "0563", executionOrder = 1),
        )
        val laterMap = battleMapRepository.save(newBattleMap("quest-later", "퀘스트 후순위 맵", now))
        val earlierMap = battleMapRepository.save(newBattleMap("quest-earlier", "퀘스트 선행 맵", now))
        moduleQuestMapRepository.saveAll(
            listOf(
                AutomationModuleQuestMapEntity(
                    moduleQuest = earlierQuest,
                    battleMap = laterMap,
                    partyPreset = null,
                    executionOrder = 2,
                ),
                AutomationModuleQuestMapEntity(
                    moduleQuest = earlierQuest,
                    battleMap = earlierMap,
                    partyPreset = null,
                    executionOrder = 0,
                ),
            ),
        )
        entityManager.flush()
        entityManager.clear()

        val aggregate = queryRepository.findModules(profile.id).single()

        assertEquals(listOf("0563", "0571"), aggregate.quests.map { it.quest.questCode })
        assertEquals(listOf("quest-earlier", "quest-later"), aggregate.quests.first().maps.map { it.battleMap.mapCode })
        assertEquals(emptyList(), aggregate.quests.last().maps)
        assertEquals(laterQuest.id, aggregate.quests.last().quest.id)
    }

    @Test
    fun deletingPartyPresetClearsModuleAndQuestMapReferences() {
        val now = Instant.parse("2026-07-13T04:00:00Z")
        val account = newAccount("module-preset-delete-account", now)
        val profile = newProfile(account, now)
        val module = moduleConfigRepository.save(
            newModule(profile, "프리셋 삭제 테스트", priority = 0, thresholdPercent = 50, now = now),
        )
        val preset = partyPresetRepository.save(
            PartyPresetEntity(
                account = account,
                name = "삭제할 프리셋",
                createdAt = now,
                updatedAt = now,
            ),
        )
        val moduleMap = battleMapRepository.save(newBattleMap("preset-module-map", "모듈 프리셋 맵", now))
        val questMap = battleMapRepository.save(newBattleMap("preset-quest-map", "퀘스트 프리셋 맵", now))
        moduleMapRepository.save(
            AutomationModuleMapEntity(
                moduleConfig = module,
                battleMap = moduleMap,
                partyPreset = preset,
                executionOrder = 0,
            ),
        )
        val quest = moduleQuestRepository.save(
            AutomationModuleQuestEntity(moduleConfig = module, questCode = "preset-quest", executionOrder = 0),
        )
        moduleQuestMapRepository.save(
            AutomationModuleQuestMapEntity(
                moduleQuest = quest,
                battleMap = questMap,
                partyPreset = preset,
                executionOrder = 0,
            ),
        )
        entityManager.flush()
        entityManager.clear()

        entityManager.createNativeQuery("delete from party_presets where id = :presetId")
            .setParameter("presetId", preset.id)
            .executeUpdate()
        entityManager.clear()

        val aggregate = assertNotNull(queryRepository.findModule(account.id, module.id))
        assertNull(aggregate.maps.single().partyPreset)
        assertNull(aggregate.quests.single().maps.single().partyPreset)
    }

    private fun newAccount(
        loginId: String,
        now: Instant,
    ): HofAccountEntity = accountRepository.save(
        HofAccountEntity(loginId = loginId, encryptedPassword = "encrypted", createdAt = now),
    )

    private fun newProfile(
        account: HofAccountEntity,
        now: Instant,
        mode: String = "UNIFIED",
    ): AutomationProfileEntity = profileRepository.save(
        AutomationProfileEntity(
            account = account,
            name = "통합 자동화",
            mode = mode,
            enabled = true,
            createdAt = now,
            updatedAt = now,
        ),
    )

    private fun newModule(
        profile: AutomationProfileEntity,
        displayName: String,
        priority: Int,
        thresholdPercent: Int?,
        now: Instant,
    ): AutomationModuleConfigEntity = AutomationModuleConfigEntity(
        profile = profile,
        moduleType = AutomationModuleType.TIME_BURN,
        enabled = true,
        priority = priority,
        displayName = displayName,
        thresholdPercent = thresholdPercent,
        createdAt = now,
        updatedAt = now,
    )

    private fun newBattleMap(
        mapCode: String,
        name: String,
        now: Instant,
    ): BattleMapEntity = BattleMapEntity(
        categoryId = "TEST_AUTOMATION",
        mapCode = mapCode,
        name = name,
        normalizedName = name,
        createdAt = now,
        updatedAt = now,
    )
}
