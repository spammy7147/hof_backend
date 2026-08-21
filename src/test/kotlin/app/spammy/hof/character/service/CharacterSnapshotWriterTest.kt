package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.character.entity.*
import app.spammy.hof.character.repository.*
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.SystemTimeProvider
import app.spammy.hof.external.model.HofCharacter
import app.spammy.hof.external.model.HofEquipmentCandidate
import app.spammy.hof.external.model.HofStatusEffect
import app.spammy.hof.external.parser.*
import java.time.Instant
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@DataJpaTest
@Import(
    QueryDslConfig::class,
    CharacterQueryRepository::class,
    CharacterIdentityQueryRepository::class,
    CharacterSnapshotWriter::class,
    CharacterService::class,
    SystemTimeProvider::class,
)
class CharacterSnapshotWriterTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var characters: CharacterRepository
    @Autowired private lateinit var effects: CharacterStatusEffectCommandRepository
    @Autowired private lateinit var candidates: CharacterEquipmentCandidateCommandRepository
    @Autowired private lateinit var query: CharacterQueryRepository
    @Autowired private lateinit var writer: CharacterSnapshotWriter
    @Autowired private lateinit var service: CharacterService

    @Test
    fun `failed section preserves last good data while successful section is replaced`() {
        val account = accounts.save(HofAccountEntity(loginId = "snapshot-writer", encryptedPassword = "x", createdAt = T0))
        val character = characters.save(
            CharacterEntity(account = account, hofCharacterId = "char-1", name = "소셜", job = "Knight", updatedAt = T0),
        )
        effects.save(
            CharacterStatusEffectEntity(
                character = character, effectOrder = 0, effectType = CharacterStatusEffectType.EFFECT,
                name = "이전 효과", valueText = "이전 효과 +1", description = "old",
            ),
        )
        candidates.save(
            CharacterEquipmentCandidateEntity(
                character = character, candidateOrder = 0, sourceValue = "old-item", typeCode = "weapon",
                name = "보존할 장비", iconUrl = "old.gif", description = "old",
            ),
        )

        writer.write(
            character,
            CharacterPageParseResult(
                snapshot = HofCharacter(
                    id = "char-1",
                    statusEffects = listOf(HofStatusEffect("EFFECT", "새 효과", "새 효과 +2", "new")),
                    equipmentCandidates = listOf(HofEquipmentCandidate("new-item", "weapon", "덮어쓰면 안 되는 장비")),
                ),
                sections = mapOf(
                    CharacterPageSection.EFFECTS_FAITH to CharacterSectionParseResult.Success(1),
                    CharacterPageSection.EQUIPMENT_CANDIDATES to CharacterSectionParseResult.Failure(
                        parserVersion = "character-v1",
                        errorCode = "EXPECTED_SCRIPT_NOT_FOUND",
                        expected = "Listtype_equip script",
                        observedCount = 0,
                    ),
                ),
            ),
            T1,
        )

        assertEquals(listOf("새 효과"), query.findStatusEffects(character.id).map { it.name })
        assertEquals(listOf("보존할 장비"), query.findEquipmentCandidates(character.id).map { it.name })
        val effectState = assertNotNull(query.findSectionState(character.id, CharacterSection.EFFECTS_FAITH))
        assertEquals(CharacterSectionSyncStatus.SUCCESS, effectState.status)
        assertEquals(T1, effectState.lastSucceededAt)
        val candidateState = assertNotNull(query.findSectionState(character.id, CharacterSection.EQUIPMENT_CANDIDATES))
        assertEquals(CharacterSectionSyncStatus.FAILED, candidateState.status)
        assertEquals("EXPECTED_SCRIPT_NOT_FOUND", candidateState.errorCode)
        assertEquals(null, candidateState.lastSucceededAt)
    }

    @Test
    fun `transient reset selector replaces only reset candidates`() {
        val account = accounts.save(HofAccountEntity(loginId = "subset-writer", encryptedPassword = "x", createdAt = T0))
        val character = characters.save(
            CharacterEntity(account = account, hofCharacterId = "char-subset", name = "소셜", job = "Knight", updatedAt = T0),
        )
        candidates.saveAll(
            listOf(
                CharacterEquipmentCandidateEntity(
                    character = character, candidateOrder = 0, sourceValue = "sword", typeCode = "weapon",
                    name = "검", iconUrl = "sword.gif", description = "검",
                ),
                CharacterEquipmentCandidateEntity(
                    character = character, candidateOrder = 1, sourceValue = "old-reset", typeCode = "resetitem",
                    name = "옛 리셋", iconUrl = "", description = "옛 리셋",
                ),
            ),
        )

        writer.writeEquipmentCandidateSubset(
            character,
            listOf(HofEquipmentCandidate("new-reset", "resetitem", "새 리셋", quantity = 2)),
            setOf("resetitem"),
            T1,
        )

        val stored = query.findEquipmentCandidates(character.id)
        assertEquals(listOf("sword", "new-reset"), stored.map { it.sourceValue })
        assertEquals(listOf("weapon", "resetitem"), stored.map { it.typeCode })
        assertEquals(2, stored.last().quantity)
        assertEquals(T1, character.updatedAt)
    }

    @Test
    fun `captured HOF page flows through parser and section persistence without losing options or equipment`() {
        val account = accounts.save(HofAccountEntity(loginId = "fixture-pipeline", encryptedPassword = "x", createdAt = T0))
        val character = characters.save(
            CharacterEntity(
                account = account,
                hofCharacterId = "1683198503393759",
                name = "동기화 전",
                job = "Unknown",
                updatedAt = T0,
            ),
        )
        val html = requireNotNull(
            javaClass.getResourceAsStream("/character/Hall of Fame Ver ZeroHOF_skillPoint_not_exist.html"),
        ).use { it.readBytes().toString(Charset.forName("MS949")) }

        val parsed = CharacterDetailParser().parsePage(character.hofCharacterId, html)
        assertEquals(546, parsed.snapshot.equipmentCandidates.size)

        writer.write(character, parsed, T1)

        assertEquals(12, query.findEquipmentByCharacterIds(listOf(character.id)).size)
        assertEquals(parsed.snapshot.equipmentCandidates.size, query.findEquipmentCandidates(character.id).size)
        assertEquals(10, query.findActionPatternsByCharacterIds(listOf(character.id)).size)
        assertEquals(50, query.findStatsByCharacterId(character.id)?.skillPoints)
        assertEquals(
            CharacterSectionSyncStatus.SUCCESS,
            query.findSectionState(character.id, CharacterSection.CURRENT_PATTERN)?.status,
        )
        val apiDetail = service.findDetailById(account.id, character.id)
        assertEquals(12, apiDetail.equipment.size)
        assertEquals(546, apiDetail.equipmentCandidates.size)
        assertEquals(10, apiDetail.actionPatterns.size)
        assertEquals(
            "SUCCESS",
            apiDetail.sectionStates.first { it.section == "CURRENT_PATTERN" }.status,
        )
    }

    @Test
    fun `class change options from a captured page are persisted as typed options`() {
        val account = accounts.save(HofAccountEntity(loginId = "fixture-class", encryptedPassword = "x", createdAt = T0))
        val character = characters.save(
            CharacterEntity(
                account = account,
                hofCharacterId = "1683198503393759",
                name = "동기화 전",
                job = "Unknown",
                updatedAt = T0,
            ),
        )
        val html = requireNotNull(
            javaClass.getResourceAsStream("/character/Hall of Fame Ver ZeroHOF_skillPoint_exist.html"),
        ).use { it.readBytes().toString(Charset.forName("MS949")) }

        writer.write(character, CharacterDetailParser().parsePage(character.hofCharacterId, html), T1)

        assertEquals(
            "Mathematician",
            query.findPatternOptions(character.id).first { it.optionType.name == "CLASS" && it.sourceValue == "523" }.label,
        )
    }

    companion object {
        private val T0 = Instant.parse("2026-08-17T00:00:00Z")
        private val T1 = Instant.parse("2026-08-17T00:01:00Z")
    }
}
