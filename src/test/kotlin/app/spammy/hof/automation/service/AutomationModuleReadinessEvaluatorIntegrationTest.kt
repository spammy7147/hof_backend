package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.AutomationModuleConfigEntity
import app.spammy.hof.automation.entity.AutomationModuleMapEntity
import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.repository.UnifiedAutomationQueryRepository
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import jakarta.persistence.EntityManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@Import(
    QueryDslConfig::class,
    PartyPresetQueryRepository::class,
    UnifiedAutomationQueryRepository::class,
    AutomationModuleReadinessEvaluator::class,
)
class AutomationModuleReadinessEvaluatorIntegrationTest {
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var queryRepository: UnifiedAutomationQueryRepository
    @Autowired private lateinit var evaluator: AutomationModuleReadinessEvaluator

    @Test
    fun queryDslLoadedPresetWithAnUnloadablePatternIsNotReady() {
        val account = HofAccountEntity(loginId = "unloadable-pattern", encryptedPassword = "encrypted", createdAt = NOW)
        entityManager.persist(account)
        val character = CharacterEntity(
            account = account,
            hofCharacterId = "character-1",
            name = "캐릭터",
            job = "직업",
            updatedAt = NOW,
        )
        entityManager.persist(character)
        val pattern = CharacterPatternSlotEntity(
            character = character,
            slotCode = "0",
            label = "로드 불가",
            canLoad = false,
        )
        entityManager.persist(pattern)
        val preset = PartyPresetEntity(account = account, name = "로드 불가 프리셋", createdAt = NOW, updatedAt = NOW)
        entityManager.persist(preset)
        entityManager.persist(PartyPresetMemberEntity(preset, 0, character, pattern))
        val profile = AutomationProfileEntity(
            account = account,
            name = "통합 자동화",
            mode = UnifiedAutomationQueryRepository.UNIFIED_MODE,
            enabled = true,
            createdAt = NOW,
            updatedAt = NOW,
        )
        entityManager.persist(profile)
        val config = AutomationModuleConfigEntity(
            profile = profile,
            moduleType = AutomationModuleType.TIME_BURN,
            enabled = true,
            priority = 0,
            displayName = "Time 자동 소모",
            thresholdPercent = 90,
            createdAt = NOW,
            updatedAt = NOW,
        )
        entityManager.persist(config)
        val battleMap = BattleMapEntity(
            categoryId = "battle_map",
            mapCode = "unloadable-pattern-map",
            name = "로드 불가 패턴 맵",
            normalizedName = "로드 불가 패턴 맵",
            createdAt = NOW,
            updatedAt = NOW,
        )
        entityManager.persist(battleMap)
        entityManager.persist(
            AutomationModuleMapEntity(
                moduleConfig = config,
                battleMap = battleMap,
                partyPreset = preset,
                executionOrder = 0,
            ),
        )
        entityManager.flush()
        entityManager.clear()

        val module = queryRepository.findModules(profile.id).single()
        val readiness = evaluator.evaluate(listOf(module))

        assertFalse(readiness.isReady(module))
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T05:00:00Z")
    }
}
