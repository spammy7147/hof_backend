package app.spammy.hof.party.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.entity.CharacterPatternSlotEntity
import app.spammy.hof.character.repository.CharacterPatternSlotCommandRepository
import app.spammy.hof.character.repository.CharacterRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, PartyPresetQueryRepository::class)
class PartyPresetQueryRepositoryTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var characterRepository: CharacterRepository

    @Autowired
    private lateinit var patternSlotRepository: CharacterPatternSlotCommandRepository

    @Autowired
    private lateinit var presetRepository: PartyPresetRepository

    @Autowired
    private lateinit var memberRepository: PartyPresetMemberCommandRepository

    @Autowired
    private lateinit var queryRepository: PartyPresetQueryRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun readsOwnedParentsAndBulkMembersInDeterministicOrder() {
        val account = savedAccount("party-query-owner")
        val otherAccount = savedAccount("party-query-other")
        val character = savedCharacter(account, "query-char")
        val pattern = savedPatternSlot(character, 2)
        val oldest = savedPreset(account, "가장 오래됨", UPDATED_EARLIER)
        val firstTie = savedPreset(account, "동률 먼저 생성", UPDATED_LATER)
        val secondTie = savedPreset(account, "동률 나중 생성", UPDATED_LATER)
        val foreign = savedPreset(otherAccount, "다른 계정", UPDATED_LATER)
        saveMembers(oldest)
        saveMembers(firstTie, character, pattern)
        saveMembers(secondTie)
        saveMembers(foreign)

        assertEquals(
            listOf(secondTie.id, firstTie.id, oldest.id),
            queryRepository.findAllByAccountId(account.id).map { it.id },
        )
        assertEquals(firstTie.id, assertNotNull(queryRepository.findOwnedByAccountIdAndId(account.id, firstTie.id)).id)
        assertNull(queryRepository.findOwnedByAccountIdAndId(otherAccount.id, firstTie.id))
        assertNull(queryRepository.findOwnedByAccountIdAndId(account.id, foreign.id))
        assertEquals(
            listOf(oldest.id, firstTie.id),
            queryRepository
                .findOwnedByAccountIdAndIds(account.id, setOf(firstTie.id, oldest.id, foreign.id, Long.MAX_VALUE))
                .map { it.id },
        )
        assertTrue(queryRepository.findOwnedByAccountIdAndIds(account.id, emptyList()).isEmpty())

        val members = queryRepository.findMembersByPresetIds(listOf(secondTie.id, firstTie.id))
        val membersByPreset = members.groupBy { it.preset.id }
        assertEquals(listOf(0, 1, 2, 3, 4), membersByPreset.getValue(firstTie.id).map { it.slotIndex })
        assertEquals(listOf(0, 1, 2, 3, 4), membersByPreset.getValue(secondTie.id).map { it.slotIndex })
        assertEquals("query-char", membersByPreset.getValue(firstTie.id).first().character?.hofCharacterId)
        assertEquals("2", membersByPreset.getValue(firstTie.id).first().patternSlot?.slotCode)
        assertEquals(5L, queryRepository.countMembers(firstTie.id))
        assertTrue(queryRepository.findMembersByPresetIds(emptyList()).isEmpty())
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun databaseEnforcesMemberForeignKeysAndUniqueSlot() {
        val account = savedAccount("party-query-constraints")
        val character = savedCharacter(account, "constraint-char")
        val pattern = savedPatternSlot(character, 0)
        val preset = savedPreset(account, "제약 조건", UPDATED_LATER)

        jdbcTemplate.update(
            """
                insert into party_preset_members(preset_id, slot_index, character_id, pattern_slot_id)
                values (?, ?, ?, ?)
            """.trimIndent(),
            preset.id,
            0,
            character.id,
            pattern.id,
        )

        kotlin.test.assertFailsWith<DataIntegrityViolationException> {
            jdbcTemplate.update(
                """
                    insert into party_preset_members(preset_id, slot_index, character_id, pattern_slot_id)
                    values (?, ?, ?, ?)
                """.trimIndent(),
                preset.id,
                0,
                character.id,
                pattern.id,
            )
        }
        kotlin.test.assertFailsWith<DataIntegrityViolationException> {
            jdbcTemplate.update(
                """
                    insert into party_preset_members(preset_id, slot_index, character_id, pattern_slot_id)
                    values (?, ?, ?, ?)
                """.trimIndent(),
                preset.id,
                1,
                Long.MAX_VALUE,
                null,
            )
        }
        kotlin.test.assertFailsWith<DataIntegrityViolationException> {
            jdbcTemplate.update(
                """
                    insert into party_preset_members(preset_id, slot_index, character_id, pattern_slot_id)
                    values (?, ?, ?, ?)
                """.trimIndent(),
                preset.id,
                5,
                null,
                null,
            )
        }
    }

    private fun savedAccount(loginId: String): HofAccountEntity =
        accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = CREATED_AT,
            ),
        )

    private fun savedCharacter(
        account: HofAccountEntity,
        hofCharacterId: String,
    ): CharacterEntity =
        characterRepository.save(
            CharacterEntity(
                account = account,
                hofCharacterId = hofCharacterId,
                name = hofCharacterId,
                job = "검사",
                updatedAt = CREATED_AT,
            ),
        )

    private fun savedPatternSlot(
        character: CharacterEntity,
        slot: Int,
    ): CharacterPatternSlotEntity =
        patternSlotRepository.save(
            CharacterPatternSlotEntity(
                character = character,
                slotCode = slot.toString(),
                label = "$slot 번 패턴",
                canLoad = true,
            ),
        )

    private fun savedPreset(
        account: HofAccountEntity,
        name: String,
        updatedAt: Instant,
    ): PartyPresetEntity =
        presetRepository.save(
            PartyPresetEntity(
                account = account,
                name = name,
                createdAt = CREATED_AT,
                updatedAt = updatedAt,
            ),
        )

    private fun saveMembers(
        preset: PartyPresetEntity,
        character: CharacterEntity? = null,
        patternSlot: CharacterPatternSlotEntity? = null,
    ) {
        memberRepository.saveAll(
            (4 downTo 0).map { slotIndex ->
                PartyPresetMemberEntity(
                    preset = preset,
                    slotIndex = slotIndex,
                    character = character.takeIf { slotIndex == 0 },
                    patternSlot = patternSlot.takeIf { slotIndex == 0 },
                )
            },
        )
    }

    private companion object {
        val CREATED_AT: Instant = Instant.parse("2026-07-12T00:00:00Z")
        val UPDATED_EARLIER: Instant = Instant.parse("2026-07-12T01:00:00Z")
        val UPDATED_LATER: Instant = Instant.parse("2026-07-12T02:00:00Z")
    }
}
