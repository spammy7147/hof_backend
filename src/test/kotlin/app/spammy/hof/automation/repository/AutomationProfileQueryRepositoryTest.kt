package app.spammy.hof.automation.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationProfileEntity
import app.spammy.hof.automation.entity.AutomationProfileMapEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.repository.BattleMapRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.repository.PartyPresetRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(QueryDslConfig::class, AutomationProfileQueryRepository::class)
class AutomationProfileQueryRepositoryTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var battleMapRepository: BattleMapRepository

    @Autowired
    private lateinit var presetRepository: PartyPresetRepository

    @Autowired
    private lateinit var profileRepository: AutomationProfileRepository

    @Autowired
    private lateinit var profileMapRepository: AutomationProfileMapCommandRepository

    @Autowired
    private lateinit var queryRepository: AutomationProfileQueryRepository

    @Test
    fun readsOwnedProfilesAndBulkMapRowsInDeterministicOrder() {
        val account = savedAccount("automation-profile-query")
        val otherAccount = savedAccount("automation-profile-query-other")
        val firstMap = savedMap("adventure_map", "query-Noble205")
        val secondMap = savedMap("battle_map", "query-snow22")
        val preset = savedPreset(account, "조회 파티")
        val oldest = savedProfile(account, "오래된 프로필", EARLIER)
        val firstTie = savedProfile(account, "동률 먼저 생성", LATER)
        val secondTie = savedProfile(account, "동률 나중 생성", LATER)
        val foreign = savedProfile(otherAccount, "다른 계정", LATER)
        profileMapRepository.saveAll(
            listOf(
                profileMap(firstTie, secondMap, preset, executionOrder = 1),
                profileMap(firstTie, firstMap, preset, executionOrder = 0),
                profileMap(secondTie, secondMap, preset, executionOrder = 0),
            ),
        )

        assertEquals(
            listOf(secondTie.id, firstTie.id, oldest.id),
            queryRepository.findAllByAccountId(account.id).map { it.id },
        )
        assertEquals(firstTie.id, assertNotNull(queryRepository.findOwnedByAccountIdAndId(account.id, firstTie.id)).id)
        assertNull(queryRepository.findOwnedByAccountIdAndId(otherAccount.id, firstTie.id))
        assertNull(queryRepository.findOwnedByAccountIdAndId(account.id, foreign.id))

        val rows = queryRepository.findMapsByProfileIds(listOf(secondTie.id, firstTie.id))
        assertEquals(listOf(firstTie.id, firstTie.id, secondTie.id), rows.map { it.profile.id })
        assertEquals(listOf("query-Noble205", "query-snow22", "query-snow22"), rows.map { it.battleMap.mapCode })
        assertEquals(listOf(0, 1, 0), rows.map { it.executionOrder })
        assertEquals(preset.id, rows.first().partyPreset?.id)
        assertEquals(2L, queryRepository.countMaps(firstTie.id))
        assertTrue(queryRepository.findMapsByProfileIds(emptyList()).isEmpty())
    }

    private fun savedAccount(loginId: String): HofAccountEntity =
        accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = CREATED_AT,
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
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )

    private fun savedPreset(
        account: HofAccountEntity,
        name: String,
    ): PartyPresetEntity =
        presetRepository.save(
            PartyPresetEntity(
                account = account,
                name = name,
                createdAt = CREATED_AT,
                updatedAt = CREATED_AT,
            ),
        )

    private fun savedProfile(
        account: HofAccountEntity,
        name: String,
        updatedAt: Instant,
    ): AutomationProfileEntity =
        profileRepository.save(
            AutomationProfileEntity(
                account = account,
                name = name,
                mode = "TIME_BURN",
                enabled = true,
                createdAt = CREATED_AT,
                updatedAt = updatedAt,
            ),
        )

    private fun profileMap(
        profile: AutomationProfileEntity,
        battleMap: BattleMapEntity,
        preset: PartyPresetEntity,
        executionOrder: Int,
    ): AutomationProfileMapEntity =
        AutomationProfileMapEntity(
            profile = profile,
            battleMap = battleMap,
            partyPreset = preset,
            executionOrder = executionOrder,
        )

    private companion object {
        val CREATED_AT: Instant = Instant.parse("2026-07-12T00:00:00Z")
        val EARLIER: Instant = Instant.parse("2026-07-12T01:00:00Z")
        val LATER: Instant = Instant.parse("2026-07-12T02:00:00Z")
    }
}
