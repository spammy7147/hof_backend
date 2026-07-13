package app.spammy.hof.battle.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.entity.BattleMapAliasEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.entity.BattleMapGroupEntity
import app.spammy.hof.battle.entity.UnresolvedBattleMapEntity
import app.spammy.hof.battle.model.BattleMapIdentityNormalizer
import app.spammy.hof.external.model.HofBattleMap
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class BattleMapCatalogSyncIndexTest {
    @Test
    fun resolvesWithinObservedGroupAndUpdatesEveryMutableIndex() {
        val account = account()
        val firstGroup = group(1L, "First")
        val secondGroup = group(2L, "Second")
        val firstMap = map(10L, "first01", "First Map", firstGroup)
        val index = BattleMapCatalogSyncIndex(
            maps = listOf(firstMap),
            groups = listOf(firstGroup, secondGroup),
            aliases = listOf(alias(100L, firstMap, "공통")),
            states = emptyList(),
            unresolvedRows = emptyList(),
        )

        assertSame(firstMap, index.resolve(observation(name = "Common- 공통", groupName = "First")))
        assertNull(index.resolve(observation(name = "Common- 공통", groupName = "Second")))
        assertSame(firstMap, index.resolve(observation(name = "Common- 공통", groupName = null)))

        val futureGroup = group(3L, "Future")
        val futureMap = map(11L, "future01", "Future- 미래", futureGroup)
        val futureAliases = BattleMapIdentityNormalizer.aliasValues(futureMap.name).mapIndexed { indexValue, value ->
            alias(200L + indexValue, futureMap, value)
        }
        val state = state(account, futureMap)
        val unresolved = unresolved(account, "Future- 미래")
        index.putGroup(futureGroup)
        index.putMap(futureMap)
        index.putAliases(futureAliases)
        index.putState(state)
        index.putUnresolved(unresolved)

        assertSame(futureGroup, index.findGroup(BattleMapIdentityNormalizer.normalize("Future")))
        assertSame(futureMap, index.resolve(observation(mapCode = "future01", name = "renamed", groupName = "Other")))
        assertSame(futureMap, index.resolve(observation(name = "Future- 미래", groupName = "Future")))
        assertEquals(futureAliases.map { it.normalizedAlias }.toSet(), index.aliasesForMap(futureMap.id).map { it.normalizedAlias }.toSet())
        assertSame(state, index.stateFor(futureMap.id))
        assertSame(unresolved, index.unresolvedFor(UnresolvedBattleMapIdentity.from(unresolved)))
        assertSame(unresolved, index.removeUnresolved(UnresolvedBattleMapIdentity.from(unresolved)))
        assertNull(index.unresolvedFor(UnresolvedBattleMapIdentity.from(unresolved)))
    }

    private fun observation(
        mapCode: String? = null,
        name: String,
        groupName: String?,
    ): HofBattleMap =
        HofBattleMap(
            categoryId = CATEGORY,
            mapCode = mapCode,
            name = name,
            groupName = groupName,
            rawHref = "index.php?sp_hunt#",
        )

    private fun account(): HofAccountEntity =
        HofAccountEntity(
            id = 1L,
            loginId = "sync-index",
            encryptedPassword = "encrypted",
            createdAt = NOW,
        )

    private fun group(
        id: Long,
        name: String,
    ): BattleMapGroupEntity =
        BattleMapGroupEntity(
            id = id,
            categoryId = CATEGORY,
            name = name,
            displayOrder = id.toInt(),
        )

    private fun map(
        id: Long,
        code: String,
        name: String,
        group: BattleMapGroupEntity,
    ): BattleMapEntity =
        BattleMapEntity(
            id = id,
            categoryId = CATEGORY,
            mapCode = code,
            group = group,
            name = name,
            normalizedName = BattleMapIdentityNormalizer.normalize(name),
            enabled = true,
            createdAt = NOW,
            updatedAt = NOW,
        )

    private fun alias(
        id: Long,
        map: BattleMapEntity,
        value: String,
    ): BattleMapAliasEntity =
        BattleMapAliasEntity(
            id = id,
            battleMap = map,
            alias = value,
            normalizedAlias = BattleMapIdentityNormalizer.normalize(value),
        )

    private fun state(
        account: HofAccountEntity,
        map: BattleMapEntity,
    ): AccountBattleMapStateEntity =
        AccountBattleMapStateEntity(
            id = 300L,
            account = account,
            battleMap = map,
            rawHref = "index.php?sp_common=${map.mapCode}",
            lastSeenAt = NOW,
        )

    private fun unresolved(
        account: HofAccountEntity,
        name: String,
    ): UnresolvedBattleMapEntity =
        UnresolvedBattleMapEntity(
            id = 400L,
            account = account,
            categoryId = CATEGORY,
            groupName = "Future",
            groupNormalizedName = BattleMapIdentityNormalizer.normalize("Future"),
            observedName = name,
            normalizedName = BattleMapIdentityNormalizer.normalize(name),
            rawHref = "index.php?sp_hunt#",
            lastSeenAt = NOW,
        )

    private companion object {
        const val CATEGORY = "adventure_map"
        val NOW: Instant = Instant.parse("2026-07-08T00:00:00Z")
    }
}
