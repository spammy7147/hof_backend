package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.dto.AutomationProfileMapRequest
import app.spammy.hof.automation.dto.CreateAutomationProfileRequest
import app.spammy.hof.automation.dto.UpdateAutomationProfileRequest
import app.spammy.hof.automation.entity.AutomationJobEntity
import app.spammy.hof.automation.repository.AutomationJobQueryRepository
import app.spammy.hof.automation.repository.AutomationJobRepository
import app.spammy.hof.automation.repository.AutomationProfileQueryRepository
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.repository.BattleMapRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.party.repository.PartyPresetRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.hibernate.SessionFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    BattleMapQueryRepository::class,
    PartyPresetQueryRepository::class,
    AutomationProfileQueryRepository::class,
    AutomationJobQueryRepository::class,
    AutomationProfileService::class,
    AutomationProfileServiceTest.ClockConfig::class,
)
class AutomationProfileServiceTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var battleMapRepository: BattleMapRepository

    @Autowired
    private lateinit var partyPresetRepository: PartyPresetRepository

    @Autowired
    private lateinit var profileQueryRepository: AutomationProfileQueryRepository

    @Autowired
    private lateinit var jobRepository: AutomationJobRepository

    @Autowired
    private lateinit var service: AutomationProfileService

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @Test
    fun createPersistsStructuredMapsAndRoundTripsInExecutionOrder() {
        val account = savedAccount("automation-round-trip")
        val firstMap = savedMap("adventure_map", "test-Noble205")
        val secondMap = savedMap("battle_map", "test-snow22")
        val preset = savedPreset(account, "범용 파티")

        val response = service.create(
            account.id,
            CreateAutomationProfileRequest(
                name = "  일일 모험  ",
                mode = "basic_adventure",
                maps = listOf(
                    mapRequest(secondMap, preset, executionOrder = 1),
                    mapRequest(firstMap, preset, executionOrder = 0),
                ),
            ),
        )

        assertEquals("일일 모험", response.name)
        assertEquals("BASIC_ADVENTURE", response.mode)
        assertEquals(true, response.enabled)
        assertEquals(listOf("test-Noble205", "test-snow22"), response.maps.map { it.mapCode })
        assertEquals(listOf(0, 1), response.maps.map { it.executionOrder })
        assertEquals(listOf(preset.id, preset.id), response.maps.map { it.partyPresetId })
        assertEquals(2L, profileQueryRepository.countMaps(response.id))
        assertEquals(response, service.findAll(account.id).single())
    }

    @Test
    fun createAllowsAnEmptyMapListForTheInitialEditorState() {
        val account = savedAccount("automation-empty")

        val response = service.create(
            account.id,
            CreateAutomationProfileRequest(
                name = "새 자동전투",
                mode = "TIME_BURN",
                maps = emptyList(),
            ),
        )

        assertEquals(emptyList(), response.maps)
        assertEquals(0L, profileQueryRepository.countMaps(response.id))
    }

    @Test
    fun listUsesOneParentQueryAndOneBulkMapQuery() {
        val account = savedAccount("automation-list")
        val battleMap = savedMap("adventure_map", "test-Noble205")
        val preset = savedPreset(account, "목록 파티")
        val first = service.create(account.id, request("첫 번째", battleMap, preset))
        val second = service.create(account.id, request("두 번째", battleMap, preset))
        entityManager.flush()
        entityManager.clear()
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.isStatisticsEnabled = true
        statistics.clear()

        val responses = service.findAll(account.id)

        assertEquals(listOf(second.id, first.id), responses.map { it.id })
        assertEquals(2L, statistics.prepareStatementCount)
    }

    @Test
    fun updateDeletesOldRowsBeforeInsertingReplacements() {
        val account = savedAccount("automation-replacement")
        val oldMap = savedMap("adventure_map", "test-Noble205")
        val firstReplacement = savedMap("battle_map", "test-snow22")
        val secondReplacement = savedMap("battle_map", "test-snow23")
        val preset = savedPreset(account, "교체 파티")
        val created = service.create(account.id, request("변경 전", oldMap, preset))

        val updated = service.update(
            account.id,
            created.id,
            UpdateAutomationProfileRequest(
                name = "변경 후",
                mode = "LIMITED_DUNGEON",
                maps = listOf(
                    mapRequest(secondReplacement, preset, executionOrder = 1),
                    mapRequest(firstReplacement, preset, executionOrder = 0),
                ),
                enabled = false,
            ),
        )

        assertEquals("변경 후", updated.name)
        assertEquals(false, updated.enabled)
        assertEquals(listOf("test-snow22", "test-snow23"), updated.maps.map { it.mapCode })
        assertEquals(2L, profileQueryRepository.countMaps(created.id))
        assertEquals(
            listOf("test-snow22", "test-snow23"),
            profileQueryRepository.findMapsByProfileIds(listOf(created.id)).map { it.battleMap.mapCode },
        )
    }

    @Test
    fun rejectsDuplicateMapsAsInvalidRequest() {
        val account = savedAccount("automation-duplicate-map")
        val battleMap = savedMap("battle_map", "test-snow22")
        val preset = savedPreset(account, "중복 맵 파티")

        val exception = assertFailsWith<ApiException> {
            service.create(
                account.id,
                CreateAutomationProfileRequest(
                    name = "중복 맵",
                    mode = "TIME_BURN",
                    maps = listOf(
                        mapRequest(battleMap, preset, executionOrder = 0),
                        mapRequest(battleMap, preset, executionOrder = 1),
                    ),
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
    }

    @Test
    fun rejectsDuplicateExecutionOrdersAsInvalidRequest() {
        val account = savedAccount("automation-duplicate-order")
        val firstMap = savedMap("battle_map", "test-snow22")
        val secondMap = savedMap("battle_map", "test-snow23")
        val preset = savedPreset(account, "중복 순서 파티")

        val exception = assertFailsWith<ApiException> {
            service.create(
                account.id,
                CreateAutomationProfileRequest(
                    name = "중복 순서",
                    mode = "TIME_BURN",
                    maps = listOf(
                        mapRequest(firstMap, preset, executionOrder = 0),
                        mapRequest(secondMap, preset, executionOrder = 0),
                    ),
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
    }

    @Test
    fun rejectsUnknownMapAsResourceNotFound() {
        val account = savedAccount("automation-map-validation")
        val preset = savedPreset(account, "맵 검증 파티")

        val exception = assertFailsWith<ApiException> {
            service.create(
                account.id,
                CreateAutomationProfileRequest(
                    name = "없는 맵",
                    mode = "TIME_BURN",
                    maps = listOf(
                        AutomationProfileMapRequest(
                            categoryId = "adventure_map",
                            mapCode = "missing",
                            partyPresetId = preset.id,
                            executionOrder = 0,
                        ),
                    ),
                ),
            )
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun rejectsPartyPresetOwnedByAnotherAccountAsResourceNotFound() {
        val owner = savedAccount("automation-preset-owner")
        val requester = savedAccount("automation-preset-requester")
        val battleMap = savedMap("adventure_map", "test-Noble205")
        val foreignPreset = savedPreset(owner, "다른 계정 파티")

        val exception = assertFailsWith<ApiException> {
            service.create(requester.id, request("다른 계정 프리셋", battleMap, foreignPreset))
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun rejectsMoreThanOneHundredMapsAsInvalidRequest() {
        val account = savedAccount("automation-map-cap")

        val exception = assertFailsWith<ApiException> {
            service.create(
                account.id,
                CreateAutomationProfileRequest(
                    name = "과도한 맵",
                    mode = "TIME_BURN",
                    maps = (0..100).map { index ->
                        AutomationProfileMapRequest(
                            categoryId = "battle_map",
                            mapCode = "payload-map-$index",
                            partyPresetId = null,
                            executionOrder = index,
                        )
                    },
                ),
            )
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
    }

    @Test
    fun allowsAMapWithoutAPartyPresetWhileTheProfileIsBeingEdited() {
        val account = savedAccount("automation-null-preset")
        val battleMap = savedMap("adventure_map", "test-Noble205")

        val response = service.create(
            account.id,
            CreateAutomationProfileRequest(
                name = "프리셋 선택 전",
                mode = "TIME_BURN",
                maps = listOf(
                    AutomationProfileMapRequest(
                        categoryId = battleMap.categoryId,
                        mapCode = battleMap.mapCode,
                        partyPresetId = null,
                        executionOrder = 0,
                    ),
                ),
            ),
        )

        assertNull(response.maps.single().partyPresetId)
    }

    @Test
    fun updateTreatsAnotherAccountsProfileAsNotFound() {
        val owner = savedAccount("automation-profile-owner")
        val requester = savedAccount("automation-profile-requester")
        val created = service.create(owner.id, CreateAutomationProfileRequest("소유 프로필", "TIME_BURN", emptyList()))

        val exception = assertFailsWith<ApiException> {
            service.update(
                requester.id,
                created.id,
                UpdateAutomationProfileRequest("탈취 시도", "TIME_BURN", emptyList(), enabled = true),
            )
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, exception.errorCode)
    }

    @Test
    fun deleteRejectsProfileReferencedByAJobAndKeepsItsRows() {
        val account = savedAccount("automation-profile-delete-job")
        val battleMap = savedMap("battle_map", "delete-guard-map")
        val preset = savedPreset(account, "삭제 보호 파티")
        val created = service.create(account.id, request("삭제 보호 프로필", battleMap, preset))
        val profile = assertNotNull(profileQueryRepository.findOwnedByAccountIdAndId(account.id, created.id))
        jobRepository.save(
            AutomationJobEntity(
                account = account,
                profile = profile,
                status = "CANCELLED",
                currentStepIndex = 0,
                message = null,
                createdAt = NOW,
                startedAt = NOW,
                updatedAt = NOW,
                finishedAt = NOW,
            ),
        )

        val exception = assertFailsWith<ApiException> {
            service.delete(account.id, created.id)
        }

        assertEquals(ErrorCode.INVALID_REQUEST, exception.errorCode)
        assertNotNull(profileQueryRepository.findOwnedByAccountIdAndId(account.id, created.id))
        assertEquals(1L, profileQueryRepository.countMaps(created.id))
    }

    private fun savedAccount(loginId: String): HofAccountEntity =
        accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = NOW,
            ),
        )

    private fun savedMap(
        categoryId: String,
        mapCode: String,
    ): BattleMapEntity =
        battleMapRepository.save(
            BattleMapEntity(
                categoryId = categoryId,
                mapCode = mapCode,
                name = mapCode,
                normalizedName = mapCode.lowercase(),
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

    private fun savedPreset(
        account: HofAccountEntity,
        name: String,
    ): PartyPresetEntity =
        partyPresetRepository.save(
            PartyPresetEntity(
                account = account,
                name = name,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

    private fun request(
        name: String,
        battleMap: BattleMapEntity,
        preset: PartyPresetEntity,
    ): CreateAutomationProfileRequest =
        CreateAutomationProfileRequest(
            name = name,
            mode = "TIME_BURN",
            maps = listOf(mapRequest(battleMap, preset, executionOrder = 0)),
        )

    private fun mapRequest(
        battleMap: BattleMapEntity,
        preset: PartyPresetEntity,
        executionOrder: Int,
    ): AutomationProfileMapRequest =
        AutomationProfileMapRequest(
            categoryId = battleMap.categoryId,
            mapCode = battleMap.mapCode,
            partyPresetId = preset.id,
            executionOrder = executionOrder,
        )

    @TestConfiguration
    class ClockConfig {
        @Bean
        fun timeProvider(): TimeProvider = TimeProvider { NOW }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-12T00:00:00Z")
    }
}
