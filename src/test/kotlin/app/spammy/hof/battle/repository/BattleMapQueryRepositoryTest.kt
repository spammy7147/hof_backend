package app.spammy.hof.battle.repository

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.entity.BattleMapAliasEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.entity.BattleMapGroupEntity
import app.spammy.hof.battle.entity.UnresolvedBattleMapEntity
import app.spammy.hof.battle.model.BattleMapIdentityNormalizer
import app.spammy.hof.common.persistence.QueryDslConfig
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
@Import(QueryDslConfig::class, BattleMapQueryRepository::class)
class BattleMapQueryRepositoryTest {
    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var groupRepository: BattleMapGroupCommandRepository

    @Autowired
    private lateinit var mapRepository: BattleMapRepository

    @Autowired
    private lateinit var aliasRepository: BattleMapAliasCommandRepository

    @Autowired
    private lateinit var stateRepository: AccountBattleMapStateCommandRepository

    @Autowired
    private lateinit var unresolvedRepository: UnresolvedBattleMapCommandRepository

    @Autowired
    private lateinit var queryRepository: BattleMapQueryRepository

    @Test
    fun readsCatalogAliasesAndAccountScopedRowsInDeterministicTreeOrder() {
        val firstAccount = accountRepository.save(account("battle-query-first"))
        val secondAccount = accountRepository.save(account("battle-query-second"))
        val lateGroup = groupRepository.save(
            BattleMapGroupEntity(
                categoryId = CATEGORY,
                name = "둘째 그룹",
                displayOrder = 20,
                recommendedLevel = "40-60",
            ),
        )
        val earlyGroup = groupRepository.save(
            BattleMapGroupEntity(
                categoryId = CATEGORY,
                name = "첫 그룹",
                displayOrder = 10,
                recommendedLevel = "1-20",
            ),
        )
        val lateMap = mapRepository.save(map("query-Noble205", "Noble- 느지막", lateGroup, displayOrder = 5))
        val earlySecondMap = mapRepository.save(map("query-festival03", "Festival- 둘째", earlyGroup, displayOrder = 2))
        val earlyFirstMap = mapRepository.save(map("query-festival01", "Festival- 첫 번째", earlyGroup, displayOrder = 1))
        mapRepository.flush()

        aliasRepository.saveAll(
            listOf(
                alias(lateMap, "공유 별칭"),
                alias(earlySecondMap, "공유 별칭"),
                alias(earlyFirstMap, "첫 번째"),
            ),
        )
        stateRepository.saveAll(
            listOf(
                state(firstAccount, lateMap, rawHref = "index.php?sp_hunt#late"),
                state(firstAccount, earlySecondMap, rawHref = "index.php?sp_hunt#second"),
                state(firstAccount, earlyFirstMap, rawHref = "index.php?sp_hunt#first"),
                state(secondAccount, lateMap, rawHref = "index.php?sp_hunt#other"),
            ),
        )
        unresolvedRepository.saveAll(
            listOf(
                unresolved(firstAccount, "미해결 둘", groupOrder = 10, mapOrder = 4),
                unresolved(firstAccount, "미해결 하나", groupOrder = 10, mapOrder = 3),
                unresolved(secondAccount, "미해결 하나", groupOrder = 10, mapOrder = 3),
            ),
        )
        unresolvedRepository.flush()

        assertEquals(lateMap.id, queryRepository.findMapByCategoryIdAndMapCode(CATEGORY, "query-Noble205")?.id)
        assertNull(queryRepository.findMapByCategoryIdAndMapCode(CATEGORY, "missing"))
        assertEquals(
            listOf(earlyFirstMap.id, earlySecondMap.id, lateMap.id),
            queryRepository.findMapsByCategoryId(CATEGORY).map { it.id },
        )
        assertEquals(listOf(earlyGroup.id, lateGroup.id), queryRepository.findGroupsByCategoryId(CATEGORY).map { it.id })
        assertEquals(
            listOf(earlySecondMap.id, lateMap.id),
            queryRepository
                .findAliasesByCategoryIdAndNormalizedAliases(
                    CATEGORY,
                    setOf(BattleMapIdentityNormalizer.normalize("공유 별칭")),
                )
                .map { it.battleMap.id },
        )
        assertEquals(earlyFirstMap.id, queryRepository.findAliasesByMapId(earlyFirstMap.id).single().battleMap.id)

        assertNotNull(queryRepository.findStateByAccountIdAndMapId(firstAccount.id, earlyFirstMap.id))
        assertNull(queryRepository.findStateByAccountIdAndMapId(secondAccount.id, earlyFirstMap.id))
        assertEquals(
            listOf("query-festival01", "query-festival03", "query-Noble205"),
            queryRepository.findVisibleStatesByAccountIdAndCategoryId(firstAccount.id, CATEGORY).map { it.battleMap.mapCode },
        )
        assertEquals(
            listOf("query-Noble205"),
            queryRepository.findVisibleStatesByAccountIdAndCategoryId(secondAccount.id, CATEGORY).map { it.battleMap.mapCode },
        )
        assertEquals(
            listOf("미해결 하나", "미해결 둘"),
            queryRepository.findVisibleUnresolvedByAccountIdAndCategoryId(firstAccount.id, CATEGORY).map { it.observedName },
        )
        assertEquals(
            listOf("미해결 하나"),
            queryRepository.findVisibleUnresolvedByAccountIdAndCategoryId(secondAccount.id, CATEGORY).map { it.observedName },
        )

        val identity = queryRepository.findUnresolvedByIdentity(
            accountId = firstAccount.id,
            categoryId = CATEGORY,
            groupNormalizedName = BattleMapIdentityNormalizer.normalize("Group"),
            normalizedName = BattleMapIdentityNormalizer.normalize("미해결 하나"),
        )
        assertEquals("미해결 하나", identity?.observedName)
    }

    @Test
    fun bulkMapLookupReturnsOnlyExactRequestedCategoryAndCodePairs() {
        val requestedAdventure = mapRepository.save(catalogMap("adventure_map", "pair-Noble205"))
        mapRepository.save(catalogMap("adventure_map", "pair-snow22"))
        mapRepository.save(catalogMap("battle_map", "pair-Noble205"))
        val requestedBattle = mapRepository.save(catalogMap("battle_map", "pair-snow22"))

        val result = queryRepository.findMapsByCategoryIdAndMapCodePairs(
            setOf(
                "adventure_map" to "pair-Noble205",
                "battle_map" to "pair-snow22",
            ),
        )

        assertEquals(listOf(requestedAdventure.id, requestedBattle.id), result.map { it.id })
        assertEquals(emptyList(), queryRepository.findMapsByCategoryIdAndMapCodePairs(emptyList()))
    }

    private fun account(loginId: String): HofAccountEntity =
        HofAccountEntity(
            loginId = loginId,
            encryptedPassword = "encrypted",
            createdAt = NOW,
        )

    private fun map(
        mapCode: String,
        name: String,
        group: BattleMapGroupEntity,
        displayOrder: Int,
    ): BattleMapEntity =
        BattleMapEntity(
            categoryId = CATEGORY,
            mapCode = mapCode,
            group = group,
            name = name,
            normalizedName = BattleMapIdentityNormalizer.normalize(name),
            displayOrder = displayOrder,
            requiredTime = 100,
            iconUrl = null,
            enabled = true,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun catalogMap(
        categoryId: String,
        mapCode: String,
    ): BattleMapEntity =
        BattleMapEntity(
            categoryId = categoryId,
            mapCode = mapCode,
            name = "$categoryId-$mapCode",
            normalizedName = "$categoryId-$mapCode".lowercase(),
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun alias(
        map: BattleMapEntity,
        value: String,
    ): BattleMapAliasEntity =
        BattleMapAliasEntity(
            battleMap = map,
            alias = value,
            normalizedAlias = BattleMapIdentityNormalizer.normalize(value),
        )

    private fun state(
        account: HofAccountEntity,
        map: BattleMapEntity,
        rawHref: String,
    ): AccountBattleMapStateEntity =
        AccountBattleMapStateEntity(
            account = account,
            battleMap = map,
            keyCount = 2,
            availableCount = 3,
            attemptRemaining = 4,
            winRemaining = 5,
            cooldownUntil = null,
            rawHref = rawHref,
            visible = true,
            lastSeenAt = NOW,
        )

    private fun unresolved(
        account: HofAccountEntity,
        name: String,
        groupOrder: Int,
        mapOrder: Int,
    ): UnresolvedBattleMapEntity =
        UnresolvedBattleMapEntity(
            account = account,
            categoryId = CATEGORY,
            groupName = "Group",
            groupNormalizedName = BattleMapIdentityNormalizer.normalize("Group"),
            groupDisplayOrder = groupOrder,
            mapDisplayOrder = mapOrder,
            observedName = name,
            normalizedName = BattleMapIdentityNormalizer.normalize(name),
            recommendedLevel = "??",
            keyCount = null,
            availableCount = null,
            attemptRemaining = null,
            winRemaining = null,
            cooldownUntil = null,
            requiredTime = 100,
            iconUrl = null,
            rawHref = "index.php?sp_hunt#",
            visible = true,
            lastSeenAt = NOW,
        )

    private companion object {
        const val CATEGORY = "query_test_map"
        val NOW: Instant = Instant.parse("2026-07-08T00:00:00Z")
    }
}
