package app.spammy.hof.character.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.character.dto.CharacterActionPatternResponse
import app.spammy.hof.character.dto.CharacterEquipmentResponse
import app.spammy.hof.character.dto.CharacterPatternSlotResponse
import app.spammy.hof.character.dto.CharacterPositionChoiceResponse
import app.spammy.hof.character.dto.CharacterPositionGuardResponse
import app.spammy.hof.character.dto.CharacterSkillResponse
import app.spammy.hof.character.dto.CharacterStatsResponse
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterSyncFailureEntity
import app.spammy.hof.character.entity.CharacterSyncJobEntity
import app.spammy.hof.character.entity.CharacterSyncJobStatus
import app.spammy.hof.character.service.CharacterService
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofCharacter
import app.spammy.hof.external.model.HofCharacterStats
import app.spammy.hof.external.model.HofPatternSlot
import app.spammy.hof.external.model.HofPositionChoice
import app.spammy.hof.external.model.HofPositionGuard
import app.spammy.hof.external.parser.CharacterDetailParser
import java.time.Instant
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@ActiveProfiles("test")
@DataJpaTest
@Import(
    QueryDslConfig::class,
    CharacterQueryRepository::class,
    CharacterSyncJobQueryRepository::class,
    CharacterService::class,
    CharacterQueryRepositoryTest.ClockConfig::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CharacterQueryRepositoryTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var characterQueryRepository: CharacterQueryRepository

    @Autowired
    private lateinit var characterService: CharacterService

    @Autowired
    private lateinit var syncJobRepository: CharacterSyncJobRepository

    @Autowired
    private lateinit var syncFailureRepository: CharacterSyncFailureCommandRepository

    @Autowired
    private lateinit var syncJobQueryRepository: CharacterSyncJobQueryRepository

    @Test
    fun storesParsedSnapshotInNormalizedRowsAndRoundTripsInSourceOrder() {
        val account = accountRepository.save(account("normalized-round-trip"))
        val parsed = parsedSnapshot(CHARACTER_ID)

        assertEquals(2, parsed.statusLines.size)
        assertEquals(2, parsed.patternSlots.size)
        assertEquals(1, parsed.actionPatterns.size)
        assertEquals(2, parsed.positionGuard.positions.size)
        assertEquals(1, parsed.equipment.size)
        assertEquals(1, parsed.learnedSkills.size)
        assertEquals(1, parsed.learnableSkills.size)

        characterService.upsertCharacterSnapshot(
            account = account,
            rosterCharacter = HofCharacter(id = CHARACTER_ID, name = "목록 이름"),
            detail = parsed,
        )

        val character = assertNotNull(
            characterQueryRepository.findByAccountIdAndHofCharacterId(account.id, CHARACTER_ID),
        )
        val characterIds = listOf(character.id)

        assertEquals(1L, characterQueryRepository.countByAccountId(account.id))
        assertEquals(parsed.statusLines, characterQueryRepository.findStatusLinesByCharacterIds(characterIds).map { it.content })
        assertEquals(listOf("0", "1"), characterQueryRepository.findPatternSlotsByCharacterIds(characterIds).map { it.slotCode })
        assertEquals(1, characterQueryRepository.findStatsByCharacterIds(characterIds).size)
        assertEquals(116, assertNotNull(characterQueryRepository.findStatsByCharacterId(character.id)).atk)
        assertEquals(listOf(0), characterQueryRepository.findActionPatternsByCharacterIds(characterIds).map { it.rowIndex })
        assertEquals(1, characterQueryRepository.findGuardSettingsByCharacterIds(characterIds).size)
        assertEquals("front", assertNotNull(characterQueryRepository.findGuardSettingByCharacterId(character.id)).selectedPosition)
        assertEquals(listOf("front", "back"), characterQueryRepository.findPositionChoicesByCharacterIds(characterIds).map { it.value })
        assertEquals(listOf("weapon"), characterQueryRepository.findEquipmentByCharacterIds(characterIds).map { it.slot })
        assertEquals(
            listOf("LEARNABLE:0", "LEARNED:0"),
            characterQueryRepository.findSkillsByCharacterIds(characterIds).map { "${it.skillType.name}:${it.skillOrder}" },
        )

        val detail = characterService.findDetail(account.id, CHARACTER_ID)

        assertEquals(parsed.name, detail.name)
        assertEquals(parsed.job, detail.job)
        assertEquals(parsed.level, detail.level)
        assertEquals(parsed.imageUrl, detail.imageUrl)
        assertEquals(parsed.statusLines, detail.statusLines)
        assertEquals(
            listOf(
                CharacterPatternSlotResponse(slot = "0", label = "범용", canLoad = true),
                CharacterPatternSlotResponse(slot = "1", label = "대회랑", canLoad = false),
            ),
            detail.patternSlots,
        )
        assertEquals(
            CharacterStatsResponse(
                atk = 116,
                matk = 5,
                defBase = 141,
                defBonus = 1649,
                mdefBase = 111,
                mdefBonus = 1089,
                handleUsed = 25,
                handleMax = 25,
                costUsed = 0,
                costMax = 10,
            ),
            detail.stats,
        )
        assertEquals(
            listOf(
                CharacterActionPatternResponse(
                    index = 0,
                    judge = "1701",
                    judgeText = "자신이 후방",
                    quantity = "0",
                    quantityText = "0",
                    skill = "4003",
                    skillText = "Stance Restore(Self) - (SP:0)",
                ),
            ),
            detail.actionPatterns,
        )
        assertEquals(
            CharacterPositionGuardResponse(
                positions = listOf(
                    CharacterPositionChoiceResponse(value = "front", checked = true),
                    CharacterPositionChoiceResponse(value = "back", checked = false),
                ),
                selectedPosition = "front",
                guardValue = "always",
                guardText = "반드시 지킨다",
            ),
            detail.positionGuard,
        )
        assertEquals(
            listOf(
                CharacterEquipmentResponse(
                    slot = "weapon",
                    part = "Weapon",
                    name = "Soulcollector's Sword Breaker",
                    iconUrl = "http://sic.zerosic.com/ZeroHOF/item/swordb.gif",
                    description = "Atk:96",
                    checked = true,
                ),
            ),
            detail.equipment,
        )
        assertEquals(
            listOf(
                CharacterSkillResponse(
                    name = "Attack / enemy - individual",
                    iconUrl = "http://sic.zerosic.com/ZeroHOF/skill/skill_042.png",
                    category = "사용 가능 스킬(Mastered)",
                ),
            ),
            detail.learnedSkills,
        )
        assertEquals(
            listOf(
                CharacterSkillResponse(
                    value = "1014",
                    name = "Double Quick Slash / 8pt",
                    iconUrl = "http://sic.zerosic.com/ZeroHOF/skill/skill_074z.png",
                ),
            ),
            detail.learnableSkills,
        )
    }

    @Test
    fun replacesOrderedChildrenAndPreservesIdsForUnchangedPatternSlotCodes() {
        val account = accountRepository.save(account("normalized-replacement"))
        val initial = parsedSnapshot(CHARACTER_ID)
        characterService.upsertCharacterSnapshot(account, HofCharacter(id = CHARACTER_ID, name = "목록 이름"), initial)

        val character = assertNotNull(characterQueryRepository.findByAccountIdAndHofCharacterId(account.id, CHARACTER_ID))
        val oldSlots = characterQueryRepository.findPatternSlotsByCharacterIds(listOf(character.id)).associateBy { it.slotCode }

        val updated = initial.copy(
            imageUrl = "http://sic.zerosic.com/ZeroHOF/image/updated.gif",
            statusLines = listOf("교체 상태"),
            patternSlots = listOf(
                HofPatternSlot(slot = "1", label = "갱신 슬롯", canLoad = true),
                HofPatternSlot(slot = "2", label = "신규 슬롯", canLoad = false),
            ),
            stats = HofCharacterStats(atk = 999),
            actionPatterns = emptyList(),
            positionGuard = HofPositionGuard(
                positions = listOf(HofPositionChoice(value = "back", checked = true)),
                selectedPosition = "back",
                guardValue = "never",
                guardText = "지키지 않는다",
            ),
            equipment = emptyList(),
            learnedSkills = emptyList(),
            learnableSkills = emptyList(),
        )

        characterService.upsertCharacterSnapshot(account, HofCharacter(id = CHARACTER_ID, name = "목록 이름"), updated)

        val slots = characterQueryRepository.findPatternSlotsByCharacterIds(listOf(character.id))
        assertEquals(listOf("1", "2"), slots.map { it.slotCode })
        assertEquals(oldSlots.getValue("1").id, slots.first { it.slotCode == "1" }.id)
        assertNotEquals(oldSlots.getValue("0").id, slots.first { it.slotCode == "2" }.id)
        assertNull(slots.find { it.slotCode == "0" })
        assertEquals(listOf("교체 상태"), characterQueryRepository.findStatusLinesByCharacterIds(listOf(character.id)).map { it.content })
        assertEquals(1, characterQueryRepository.findStatsByCharacterIds(listOf(character.id)).size)
        assertEquals(999, characterQueryRepository.findStatsByCharacterId(character.id)?.atk)
        assertTrue(characterQueryRepository.findActionPatternsByCharacterIds(listOf(character.id)).isEmpty())
        assertEquals(listOf("back"), characterQueryRepository.findPositionChoicesByCharacterIds(listOf(character.id)).map { it.value })
        assertTrue(characterQueryRepository.findEquipmentByCharacterIds(listOf(character.id)).isEmpty())
        assertTrue(characterQueryRepository.findSkillsByCharacterIds(listOf(character.id)).isEmpty())

        val detail = characterService.findDetail(account.id, CHARACTER_ID)
        assertEquals(2, detail.patternSlotCount)
        assertEquals(listOf("갱신 슬롯", "신규 슬롯"), detail.patternSlots.map { it.label })
        assertEquals(emptyList(), detail.actionPatterns)
        assertEquals(emptyList(), detail.equipment)
        assertEquals(emptyList(), detail.learnedSkills)
        assertEquals(emptyList(), detail.learnableSkills)
    }

    @Test
    fun idOnlyFallbackKeepsExistingDetailCoreChildrenAndChildIds() {
        val account = accountRepository.save(account("normalized-empty-detail"))
        val initial = parsedSnapshot(CHARACTER_ID)
        characterService.upsertCharacterSnapshot(account, HofCharacter(id = CHARACTER_ID, name = "목록 이름"), initial)
        val character = assertNotNull(characterQueryRepository.findByAccountIdAndHofCharacterId(account.id, CHARACTER_ID))
        val beforeFallback = characterService.findDetail(account.id, CHARACTER_ID)
        val childIdsBeforeFallback = normalizedChildIds(character.id)

        characterService.upsertCharacterSnapshot(
            account = account,
            rosterCharacter = HofCharacter(id = CHARACTER_ID, name = "목록 갱신 이름", job = "Roster Job", level = 50),
            detail = HofCharacter(id = CHARACTER_ID),
        )

        val detail = characterService.findDetail(account.id, CHARACTER_ID)
        assertEquals("목록 갱신 이름", detail.name)
        assertEquals(initial.job, detail.job)
        assertEquals(initial.level, detail.level)
        assertEquals(initial.patternSlots.size, detail.patternSlotCount)
        assertEquals(initial.imageUrl, detail.imageUrl)
        assertEquals(beforeFallback.copy(name = "목록 갱신 이름"), detail)
        assertEquals(childIdsBeforeFallback, normalizedChildIds(character.id))
    }

    @Test
    fun meaningfulCoreOnlyDetailReplacesPreviouslyStoredChildSnapshot() {
        val account = accountRepository.save(account("normalized-core-only-detail"))
        val initial = parsedSnapshot(CHARACTER_ID)
        characterService.upsertCharacterSnapshot(account, HofCharacter(id = CHARACTER_ID, name = "목록 이름"), initial)
        val character = assertNotNull(characterQueryRepository.findByAccountIdAndHofCharacterId(account.id, CHARACTER_ID))
        val childIdsBeforeUpdate = normalizedChildIds(character.id)

        characterService.upsertCharacterSnapshot(
            account = account,
            rosterCharacter = HofCharacter(id = CHARACTER_ID, name = "목록 이름"),
            detail = HofCharacter(
                id = CHARACTER_ID,
                name = "핵심 상세 이름",
                job = "Core Detail Job",
                level = 61,
            ),
        )

        val detail = characterService.findDetail(account.id, CHARACTER_ID)
        val childIdsAfterUpdate = normalizedChildIds(character.id)
        assertEquals("핵심 상세 이름", detail.name)
        assertEquals("Core Detail Job", detail.job)
        assertEquals(61, detail.level)
        assertEquals(0, detail.patternSlotCount)
        assertNull(detail.imageUrl)
        assertEquals(emptyList(), detail.statusLines)
        assertEquals(emptyList(), detail.patternSlots)
        assertEquals(CharacterStatsResponse(), detail.stats)
        assertEquals(emptyList(), detail.actionPatterns)
        assertEquals(CharacterPositionGuardResponse(), detail.positionGuard)
        assertEquals(emptyList(), detail.equipment)
        assertEquals(emptyList(), detail.learnedSkills)
        assertEquals(emptyList(), detail.learnableSkills)
        assertEquals(childIdsBeforeUpdate.getValue("stats"), childIdsAfterUpdate.getValue("stats"))
        assertEquals(childIdsBeforeUpdate.getValue("guard"), childIdsAfterUpdate.getValue("guard"))
        assertTrue(childIdsAfterUpdate.filterKeys { it !in setOf("stats", "guard") }.values.all { it.isEmpty() })
    }

    @Test
    fun newCharacterWithIdOnlyFallbackUsesAvailableRosterCoreFields() {
        val account = accountRepository.save(account("normalized-new-fallback"))
        val roster = HofCharacter(
            id = CHARACTER_ID,
            name = "신규 roster 이름",
            job = "Roster Job",
            level = 44,
            patternSlots = listOf(HofPatternSlot(slot = "0", label = "roster 슬롯", canLoad = true)),
            imageUrl = "http://sic.zerosic.com/ZeroHOF/image/roster.gif",
        )

        characterService.upsertCharacterSnapshot(
            account = account,
            rosterCharacter = roster,
            detail = HofCharacter(id = CHARACTER_ID),
        )

        val character = assertNotNull(characterQueryRepository.findByAccountIdAndHofCharacterId(account.id, CHARACTER_ID))
        val detail = characterService.findDetail(account.id, CHARACTER_ID)
        assertEquals(roster.name, detail.name)
        assertEquals(roster.job, detail.job)
        assertEquals(roster.level, detail.level)
        assertEquals(roster.patternSlots.size, detail.patternSlotCount)
        assertEquals(roster.imageUrl, detail.imageUrl)
        assertEquals(emptyList(), detail.patternSlots)
        assertTrue(normalizedChildIds(character.id).values.all { it.isEmpty() })
    }

    @Test
    fun characterEntitiesExposeNoJsonPersistenceProperties() {
        val characterProperties = CharacterEntity::class.memberProperties.map { it.name }
        val syncJobProperties = CharacterSyncJobEntity::class.memberProperties.map { it.name }

        assertFalse(characterProperties.any { it.endsWith("Json") })
        assertFalse(syncJobProperties.any { it.endsWith("Json") })
    }

    @Test
    fun ordersSyncFailuresAndEnforcesBothJobScopedUniquenessRules() {
        val account = accountRepository.save(account("normalized-sync-failures"))
        val job = syncJobRepository.save(
            CharacterSyncJobEntity(
                account = account,
                status = CharacterSyncJobStatus.RUNNING,
                rosterCount = 2,
                startedAt = NOW,
            ),
        )
        syncFailureRepository.save(
            CharacterSyncFailureEntity(
                syncJob = job,
                failureOrder = 1,
                hofCharacterId = "222",
            ),
        )
        syncFailureRepository.save(
            CharacterSyncFailureEntity(
                syncJob = job,
                failureOrder = 0,
                hofCharacterId = "111",
            ),
        )

        assertEquals(job.id, assertNotNull(syncJobQueryRepository.findById(job.id)).id)
        assertEquals(job.id, assertNotNull(syncJobQueryRepository.findByAccountIdAndId(account.id, job.id)).id)
        assertEquals(
            listOf("111", "222"),
            syncJobQueryRepository.findFailuresByJobId(job.id).map { it.hofCharacterId },
        )
        assertEquals(
            "111",
            assertNotNull(syncJobQueryRepository.findFailureByJobIdAndHofCharacterId(job.id, "111")).hofCharacterId,
        )

        assertFailsWith<DataIntegrityViolationException> {
            syncFailureRepository.save(
                CharacterSyncFailureEntity(
                    syncJob = job,
                    failureOrder = 2,
                    hofCharacterId = "111",
                ),
            )
        }
        assertFailsWith<DataIntegrityViolationException> {
            syncFailureRepository.save(
                CharacterSyncFailureEntity(
                    syncJob = job,
                    failureOrder = 0,
                    hofCharacterId = "333",
                ),
            )
        }
        assertEquals(2, syncJobQueryRepository.findFailuresByJobId(job.id).size)
    }

    private fun account(loginId: String): HofAccountEntity =
        HofAccountEntity(
            loginId = loginId,
            encryptedPassword = "encrypted-password",
            createdAt = NOW,
        )

    private fun normalizedChildIds(characterId: Long): Map<String, List<Long>> =
        mapOf(
            "status" to characterQueryRepository.findStatusLinesByCharacterIds(listOf(characterId)).map { it.id },
            "pattern" to characterQueryRepository.findPatternSlotsByCharacterIds(listOf(characterId)).map { it.id },
            "stats" to listOfNotNull(characterQueryRepository.findStatsByCharacterId(characterId)?.characterId),
            "action" to characterQueryRepository.findActionPatternsByCharacterIds(listOf(characterId)).map { it.id },
            "guard" to listOfNotNull(characterQueryRepository.findGuardSettingByCharacterId(characterId)?.characterId),
            "position" to characterQueryRepository.findPositionChoicesByCharacterIds(listOf(characterId)).map { it.id },
            "equipment" to characterQueryRepository.findEquipmentByCharacterIds(listOf(characterId)).map { it.id },
            "skill" to characterQueryRepository.findSkillsByCharacterIds(listOf(characterId)).map { it.id },
        )

    private fun parsedSnapshot(characterId: String): HofCharacter =
        CharacterDetailParser().parse(
            characterId = characterId,
            html = """
                <div class="carpet_frame">
                  <img src="/ZeroHOF/image/char/sknight02.gif">
                  소셜 Lv.60 Social Knight
                </div>

                <h3>Character Status</h3>
                <div>첫째 상태 Atk : 116 Matk : 5 Def : 141 + 1649 Mdef : 111 + 1089</div>
                <div>둘째 상태 handle : 25 / 25 cost : 0 / 10</div>
                <h3>Pattern</h3>

                <form>
                  <input name="patternno" value="0">
                  <input type="button" value="범용">
                  <input name="loadpattern" value="LOAD">
                </form>
                <form>
                  <input name="patternno" value="1">
                  <input type="button" value="대회랑">
                </form>

                <form>
                  <select name="judge0"><option value="1701" selected>자신이 후방</option></select>
                  <select name="quantity0"><option value="0" selected>0</option></select>
                  <select name="skill0"><option value="4003" selected>Stance Restore(Self) - (SP:0)</option></select>
                </form>

                <form>
                  <input name="position" value="front" checked> Front
                  <input name="position" value="back"> Back
                  <select name="guard"><option value="always" selected>반드시 지킨다</option></select>
                </form>

                <h3>Current Equip's</h3>
                <table>
                  <tr>
                    <td class="align-right">Weapon :</td>
                    <td>
                      <input name="spot" value="weapon" checked>
                      <img src="/ZeroHOF/item/swordb.gif">
                      Soulcollector's Sword Breaker
                      <span class="dmg">Atk:96</span>
                      <span style="font-size:10px">Atk:96</span>
                    </td>
                  </tr>
                </table>

                <h3>Current Skill</h3>
                <div>Attack / enemy - individual</div>
                <h3>Skill</h3>
                <form>
                  <table>
                    <tr>
                      <td><input name="newskill" value="1014"></td>
                      <td><img src="/ZeroHOF/skill/skill_074z.png"> Double Quick Slash / 8pt</td>
                    </tr>
                  </table>
                </form>
            """.trimIndent(),
        ).copy(
            learnedSkills = listOf(
                app.spammy.hof.external.model.HofSkill(
                    name = "Attack / enemy - individual",
                    iconUrl = "http://sic.zerosic.com/ZeroHOF/skill/skill_042.png",
                    category = "사용 가능 스킬(Mastered)",
                ),
            ),
        )

    @TestConfiguration
    class ClockConfig {
        @Bean
        fun timeProvider(): TimeProvider = TimeProvider { NOW }
    }

    private companion object {
        const val CHARACTER_ID = "1683198503393759"
        val NOW: Instant = Instant.parse("2026-07-13T00:00:00Z")
    }
}
