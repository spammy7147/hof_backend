package app.spammy.hof.battle.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.entity.BattleMapAliasEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.entity.BattleMapGroupEntity
import app.spammy.hof.battle.model.BattleMapIdentityNormalizer
import app.spammy.hof.battle.repository.AccountBattleMapStateCommandRepository
import app.spammy.hof.battle.repository.BattleMapAliasCommandRepository
import app.spammy.hof.battle.repository.BattleMapGroupCommandRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.repository.BattleMapRepository
import app.spammy.hof.battle.repository.UnresolvedBattleMapCommandRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofBattleMap
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.Mockito

class BattleMapCatalogServiceBulkTest {
    private val queryRepository = Mockito.mock(BattleMapQueryRepository::class.java)
    private val mapRepository = Mockito.mock(BattleMapRepository::class.java)
    private val groupRepository = Mockito.mock(BattleMapGroupCommandRepository::class.java)
    private val aliasRepository = Mockito.mock(BattleMapAliasCommandRepository::class.java)
    private val stateRepository = Mockito.mock(AccountBattleMapStateCommandRepository::class.java)
    private val unresolvedRepository = Mockito.mock(UnresolvedBattleMapCommandRepository::class.java)
    private val group = BattleMapGroupEntity(
        id = 1L,
        categoryId = CATEGORY,
        name = "Group",
        displayOrder = 1,
    )
    private val firstMap = map(10L, "first01", "One- 하나")
    private val secondMap = map(11L, "second01", "Two- 둘")
    private val aliases = listOf(firstMap, secondMap).flatMap { map ->
        BattleMapIdentityNormalizer.aliasValues(map.name).mapIndexed { index, value ->
            BattleMapAliasEntity(
                id = map.id * 10 + index,
                battleMap = map,
                alias = value,
                normalizedAlias = BattleMapIdentityNormalizer.normalize(value),
            )
        }
    }
    private val service = BattleMapCatalogTransactionService(
        queryRepository = queryRepository,
        mapRepository = mapRepository,
        groupRepository = groupRepository,
        aliasRepository = aliasRepository,
        stateRepository = stateRepository,
        unresolvedRepository = unresolvedRepository,
        timeProvider = TimeProvider { NOW },
    )

    @Test
    fun preloadsEachReadModelOnceRegardlessOfObservationCount() {
        Mockito.`when`(queryRepository.findMapsByCategoryId(CATEGORY)).thenReturn(listOf(firstMap, secondMap))
        Mockito.`when`(queryRepository.findGroupsByCategoryId(CATEGORY)).thenReturn(listOf(group))
        Mockito.`when`(queryRepository.findAliasesByCategoryId(CATEGORY)).thenReturn(aliases)
        Mockito.`when`(queryRepository.findStatesByAccountIdAndCategoryId(ACCOUNT.id, CATEGORY)).thenReturn(emptyList())
        Mockito.`when`(queryRepository.findUnresolvedByAccountIdAndCategoryId(ACCOUNT.id, CATEGORY)).thenReturn(emptyList())
        Mockito.`when`(groupRepository.save(anyGroup())).thenAnswer { it.arguments[0] }
        Mockito.`when`(mapRepository.save(anyMap())).thenAnswer { it.arguments[0] }
        Mockito.`when`(stateRepository.save(anyState())).thenAnswer { it.arguments[0] }
        val observations = List(40) { index ->
            val map = if (index % 2 == 0) firstMap else secondMap
            HofBattleMap(
                categoryId = CATEGORY,
                mapCode = null,
                name = map.name,
                groupName = group.name,
                groupOrder = group.displayOrder,
                mapOrder = index,
                rawHref = "index.php?sp_hunt#",
            )
        }

        val result = service.synchronizeCategory(ACCOUNT, CATEGORY, observations)

        assertEquals(setOf("first01", "second01"), result.mapNotNull { it.mapCode }.toSet())
        assertTrue(result.all { it.resolved })
        Mockito.verify(queryRepository).findMapsByCategoryId(CATEGORY)
        Mockito.verify(queryRepository).findGroupsByCategoryId(CATEGORY)
        Mockito.verify(queryRepository).findAliasesByCategoryId(CATEGORY)
        Mockito.verify(queryRepository).findStatesByAccountIdAndCategoryId(ACCOUNT.id, CATEGORY)
        Mockito.verify(queryRepository).findUnresolvedByAccountIdAndCategoryId(ACCOUNT.id, CATEGORY)
        Mockito.verifyNoMoreInteractions(queryRepository)
    }

    @Test
    fun `catalog refresh does not erase active projected cooldown for learned member`() {
        firstMap.sharesMinuteCooldown = true
        val state = existingState(firstMap, NOW.plusSeconds(40))
        prepareExistingState(state)

        service.synchronizeCategory(ACCOUNT, CATEGORY, listOf(observation(firstMap)))

        assertEquals(NOW.plusSeconds(40), state.cooldownUntil)
    }

    @Test
    fun `catalog refresh still clears stale cooldown for ordinary map`() {
        val state = existingState(firstMap, NOW.plusSeconds(40))
        prepareExistingState(state)

        service.synchronizeCategory(ACCOUNT, CATEGORY, listOf(observation(firstMap)))

        assertEquals(null, state.cooldownUntil)
    }

    private fun prepareExistingState(state: AccountBattleMapStateEntity) {
        Mockito.`when`(queryRepository.findMapsByCategoryId(CATEGORY)).thenReturn(listOf(firstMap))
        Mockito.`when`(queryRepository.findGroupsByCategoryId(CATEGORY)).thenReturn(listOf(group))
        Mockito.`when`(queryRepository.findAliasesByCategoryId(CATEGORY))
            .thenReturn(aliases.filter { it.battleMap.id == firstMap.id })
        Mockito.`when`(queryRepository.findStatesByAccountIdAndCategoryId(ACCOUNT.id, CATEGORY))
            .thenReturn(listOf(state))
        Mockito.`when`(queryRepository.findUnresolvedByAccountIdAndCategoryId(ACCOUNT.id, CATEGORY))
            .thenReturn(emptyList())
        Mockito.`when`(groupRepository.save(anyGroup())).thenAnswer { it.arguments[0] }
        Mockito.`when`(mapRepository.save(anyMap())).thenAnswer { it.arguments[0] }
        Mockito.`when`(stateRepository.save(anyState())).thenAnswer { it.arguments[0] }
    }

    private fun existingState(map: BattleMapEntity, cooldownUntil: Instant) = AccountBattleMapStateEntity(
        account = ACCOUNT,
        battleMap = map,
        cooldownUntil = cooldownUntil,
        rawHref = "index.php?sp_hunt=${map.mapCode}",
        lastSeenAt = NOW.minusSeconds(1),
    )

    private fun observation(map: BattleMapEntity) = HofBattleMap(
        categoryId = CATEGORY,
        mapCode = map.mapCode,
        name = map.name,
        groupName = group.name,
        groupOrder = group.displayOrder,
        mapOrder = map.displayOrder,
        cooldownRemainingSeconds = null,
        rawHref = "index.php?sp_hunt=${map.mapCode}",
    )

    private fun map(
        id: Long,
        code: String,
        name: String,
    ): BattleMapEntity =
        BattleMapEntity(
            id = id,
            categoryId = CATEGORY,
            mapCode = code,
            group = group,
            name = name,
            normalizedName = BattleMapIdentityNormalizer.normalize(name),
            displayOrder = id.toInt(),
            enabled = true,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun anyGroup(): BattleMapGroupEntity {
        Mockito.any(BattleMapGroupEntity::class.java)
        return group
    }

    private fun anyMap(): BattleMapEntity {
        Mockito.any(BattleMapEntity::class.java)
        return firstMap
    }

    private fun anyState(): AccountBattleMapStateEntity {
        Mockito.any(AccountBattleMapStateEntity::class.java)
        return AccountBattleMapStateEntity(
            account = ACCOUNT,
            battleMap = firstMap,
            rawHref = "index.php?sp_hunt#",
            lastSeenAt = NOW,
        )
    }

    private companion object {
        const val CATEGORY = "adventure_map"
        val NOW: Instant = Instant.parse("2026-07-08T00:00:00Z")
        val ACCOUNT = HofAccountEntity(
            id = 1L,
            loginId = "bulk-sync",
            encryptedPassword = "encrypted",
            createdAt = NOW,
        )
    }
}
