package app.spammy.hof.battle.seed

import app.spammy.hof.external.model.HofBattleMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** fixture/APK 병합의 코드 해석과 메타데이터 우선순위를 작은 합성 입력으로 고정한다. */
class BattleMapSeedMergerTest {
    private val merger = BattleMapSeedMerger()

    @Test
    fun uniqueNormalizedAliasResolvesNullFixtureCode() {
        val result = merger.merge(
            fixtureObservations = listOf(fixture(name = "Region - 유일 별칭")),
            apkSeedMaps = listOf(apk(code = "unique01", label = "유일 별칭")),
        )

        assertEquals("unique01", result.single().mapCode)
        assertTrue(result.single().fixtureMetadata)
    }

    @Test
    fun ambiguousNormalizedAliasDoesNotGuessFixtureCode() {
        val result = merger.merge(
            fixtureObservations = listOf(fixture(name = "공유 별칭")),
            apkSeedMaps = listOf(
                apk(code = "ambiguous01", label = "공유 별칭"),
                apk(code = "ambiguous02", label = "공유 별칭"),
            ),
        )

        assertEquals(setOf("ambiguous01", "ambiguous02"), result.map(SeedMap::mapCode).toSet())
        assertTrue(result.none(SeedMap::fixtureMetadata))
    }

    @Test
    fun fixtureGroupOrderAndStaticMetadataWinAfterAliasResolution() {
        val result = merger.merge(
            fixtureObservations = listOf(
                fixture(
                    name = "Fixture Region - 우선 맵",
                    groupName = "Fixture Group",
                    groupOrder = 7,
                    mapOrder = 4,
                    iconUrl = "http://localhost/Hall of Fame Ver ZeroHOF_files/map.gif",
                ),
            ),
            apkSeedMaps = listOf(
                apk(
                    code = "fixture-wins",
                    label = "우선 맵",
                    mapName = "APK Region - 우선 맵",
                    group = "APK Group",
                ),
            ),
        )

        val merged = result.single()
        assertEquals("Fixture Region - 우선 맵", merged.name)
        assertEquals("Fixture Group", merged.groupName)
        assertEquals(7, merged.groupOrder)
        assertEquals(4, merged.mapOrder)
        assertEquals("40-60", merged.recommendedLevel)
        assertEquals(75, merged.requiredTime)
        assertNull(merged.iconUrl)
        assertTrue(merged.fixtureMetadata)
        assertTrue(merged.aliases.contains("APK Region - 우선 맵"))
    }

    @Test
    fun retainsApkOnlyRowsWithoutFixtureObservations() {
        val result = merger.merge(
            fixtureObservations = emptyList(),
            apkSeedMaps = listOf(apk(code = "apk-only", label = "APK Only", group = "APK Group")),
        )

        val merged = result.single()
        assertEquals("apk-only", merged.mapCode)
        assertEquals("APK Only", merged.name)
        assertEquals("APK Group", merged.groupName)
        assertFalse(merged.fixtureMetadata)
    }

    private fun fixture(
        name: String,
        groupName: String = "Fixture Group",
        groupOrder: Int = 3,
        mapOrder: Int = 2,
        iconUrl: String? = null,
    ): HofBattleMap =
        HofBattleMap(
            categoryId = ADVENTURE_CATEGORY,
            mapCode = null,
            name = name,
            groupName = groupName,
            groupOrder = groupOrder,
            mapOrder = mapOrder,
            recommendedLevel = "40-60",
            requiredTime = 75,
            iconUrl = iconUrl,
            rawHref = "index.php?sp_hunt#",
        )

    private fun apk(
        code: String,
        label: String,
        mapName: String = label,
        group: String = "APK Group",
    ): ApkSeedMap =
        ApkSeedMap(
            label = label,
            map = code,
            mapName = mapName,
            param = "sp_common",
            group = group,
        )

    private companion object {
        const val ADVENTURE_CATEGORY = "adventure_map"
    }
}
