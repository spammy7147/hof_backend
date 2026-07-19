package app.spammy.hof.battle.seed

import app.spammy.hof.battle.model.BattleMapIdentityNormalizer
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.external.model.HofBattleMap
import app.spammy.hof.external.parser.BattleMapParser
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/** 저장된 HOF HTML과 APK 분석 자료를 읽어 재현 가능한 V2 정적 맵 시드를 생성한다. */
object BattleMapSeedGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val layout = SeedProjectLayout.locate()
        Files.writeString(
            layout.seedMigration,
            generateSql(layout),
            StandardCharsets.UTF_8,
        )
    }

    /** 현재 저장소의 원본 자료만 사용해 매 실행마다 동일한 SQL 문자열을 만든다. */
    fun generateSql(): String = generateSql(SeedProjectLayout.locate())

    private fun generateSql(layout: SeedProjectLayout): String {
        val fixtureReader = BattleMapFixtureReader(BattleMapParser())
        val fixtureObservations = listOf(
            FixtureSource(BATTLE_CATEGORY, "common", listOf("example", "호프정보", "전투맵.html")),
            FixtureSource(ADVENTURE_CATEGORY, "sp_common", listOf("example", "호프정보", "모험맵.html")),
        ).flatMap { source -> fixtureReader.read(layout.repositoryRoot, source) }

        val apkSeedMaps = BattleMapApkSeedReader().read(
            SeedFixturePathResolver.resolve(
                layout.repositoryRoot,
                listOf(
                    "example",
                    "mobile_analysis",
                    "analysis",
                    "jadx-src",
                    "resources",
                    "assets",
                    "battles-seed.json",
                ),
            ),
        ).filter { seed -> seed.param == "common" || seed.param == "sp_common" }

        return BattleMapSeedSqlWriter().write(
            BattleMapSeedMerger().merge(fixtureObservations, apkSeedMaps),
        )
    }

    private const val BATTLE_CATEGORY = "battle_map"
    private const val ADVENTURE_CATEGORY = "adventure_map"
}

/** MS949 HTML fixture의 NFD/NFC 파일명 차이를 흡수하고 기존 파서 관측값을 반환한다. */
internal class BattleMapFixtureReader(
    private val parser: BattleMapParser,
) {
    fun read(
        repositoryRoot: Path,
        source: FixtureSource,
    ): List<HofBattleMap> {
        val path = SeedFixturePathResolver.resolve(repositoryRoot, source.relativePath)
        val html = Files.readString(path, Charset.forName("MS949"))
        return parser.parse(source.categoryId, source.queryName, html)
            .map(HofBattleMap::toLegacyV2FixtureObservation)
    }
}

/** V2 checksum을 보존하기 위해 실행 중 파서의 permanent-key 정규화만 기존 seed 형식으로 돌린다. */
private fun HofBattleMap.toLegacyV2FixtureObservation(): HofBattleMap =
    if (keyMode == BattleMapKeyMode.UNLIMITED) {
        copy(name = "$name( x )")
    } else {
        this
    }

/** APK JSON에서 맵 코드와 원본 label/mapName/group만 읽고 실행 설정은 버린다. */
internal class BattleMapApkSeedReader {
    private val objectMapper = jacksonObjectMapper()

    fun read(path: Path): List<ApkSeedMap> =
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            objectMapper.readValue<ApkSeedDocument>(reader).battles
        }
}

/** fixture 우선 규칙과 유일 별칭 해석 규칙으로 카테고리/코드별 정적 맵을 합친다. */
internal class BattleMapSeedMerger {
    fun merge(
        fixtureObservations: List<HofBattleMap>,
        apkSeedMaps: List<ApkSeedMap>,
    ): List<SeedMap> {
        val apkCatalogMaps = apkSeedMaps.mapNotNull(ApkSeedMap::toCatalogMap)
        val apkByKey = apkCatalogMaps.groupBy(ApkCatalogMap::key)
        val resolvedFixtures = fixtureObservations.mapNotNull { fixture ->
            val code = fixture.mapCode ?: resolveUniqueCode(fixture, apkCatalogMaps) ?: return@mapNotNull null
            fixture.toSeedMap(code)
        }

        val mergedByKey = linkedMapOf<SeedMapKey, SeedMap>()
        resolvedFixtures.forEach { fixture ->
            val existing = mergedByKey[fixture.key]
            mergedByKey[fixture.key] = if (existing == null) {
                fixture
            } else {
                existing.copy(aliases = mergeAliasValues(existing.aliases, fixture.aliases))
            }
        }

        apkByKey.toSortedMap(SEED_KEY_ORDER).forEach { (key, apkRows) ->
            val apkAliases = apkRows.flatMap(ApkCatalogMap::aliasValues)
            val existing = mergedByKey[key]
            if (existing != null) {
                mergedByKey[key] = existing.copy(
                    aliases = mergeAliasValues(existing.aliases, apkAliases),
                )
            } else {
                val source = apkRows.first()
                mergedByKey[key] = SeedMap(
                    categoryId = source.categoryId,
                    mapCode = source.mapCode,
                    name = source.preferredName,
                    groupName = source.groupName,
                    groupOrder = 0,
                    mapOrder = 0,
                    recommendedLevel = null,
                    requiredTime = null,
                    iconUrl = null,
                    aliases = mergeAliasValues(apkAliases, listOf(source.preferredName)),
                    fixtureMetadata = false,
                )
            }
        }

        return assignApkOnlyOrders(mergedByKey.values.toList())
            .sortedWith(SEED_MAP_ORDER)
    }

    private fun resolveUniqueCode(
        fixture: HofBattleMap,
        apkMaps: List<ApkCatalogMap>,
    ): String? {
        val fixtureAliases = BattleMapIdentityNormalizer.aliases(fixture.name)
        if (fixtureAliases.isEmpty()) return null

        return apkMaps.asSequence()
            .filter { apk -> apk.categoryId == fixture.categoryId }
            .filter { apk -> apk.normalizedAliases.any(fixtureAliases::contains) }
            .map(ApkCatalogMap::mapCode)
            .distinct()
            .toList()
            .singleOrNull()
    }

    private fun assignApkOnlyOrders(sourceMaps: List<SeedMap>): List<SeedMap> =
        sourceMaps.groupBy(SeedMap::categoryId)
            .toSortedMap()
            .flatMap { (_, categoryMaps) -> assignCategoryOrders(categoryMaps) }

    private fun assignCategoryOrders(categoryMaps: List<SeedMap>): List<SeedMap> {
        val fixtureMaps = categoryMaps.filter(SeedMap::fixtureMetadata)
        val fixtureGroupOrders = fixtureMaps
            .filter { map -> map.groupName != null }
            .groupBy { map -> requireNotNull(map.groupName) }
            .mapValues { (groupName, maps) ->
                val orders = maps.map(SeedMap::groupOrder).distinct()
                check(orders.size == 1) { "fixture group order conflict: $groupName=$orders" }
                orders.single()
            }
        var nextGroupOrder = (fixtureMaps.maxOfOrNull(SeedMap::groupOrder) ?: -1) + 1
        val apkOnlyGroupOrders = categoryMaps.asSequence()
            .filterNot(SeedMap::fixtureMetadata)
            .mapNotNull(SeedMap::groupName)
            .filterNot(fixtureGroupOrders::containsKey)
            .distinct()
            .sortedWith(compareBy<String> { BattleMapIdentityNormalizer.normalize(it) }.thenBy { it })
            .associateWith { nextGroupOrder++ }

        val fixtureLastMapOrder = fixtureMaps.groupBy { map -> map.groupName }
            .mapValues { (_, maps) -> maps.maxOf(SeedMap::mapOrder) }
        val nextMapOrder = mutableMapOf<String?, Int>()

        return categoryMaps.sortedWith(
            compareBy<SeedMap> { it.groupName?.let(BattleMapIdentityNormalizer::normalize).orEmpty() }
                .thenBy(SeedMap::mapCode),
        ).map { map ->
            if (map.fixtureMetadata) return@map map

            val groupOrder = map.groupName?.let { groupName ->
                fixtureGroupOrders[groupName] ?: requireNotNull(apkOnlyGroupOrders[groupName])
            } ?: nextGroupOrder
            val mapOrder = nextMapOrder.getOrPut(map.groupName) {
                (fixtureLastMapOrder[map.groupName] ?: -1) + 1
            }
            nextMapOrder[map.groupName] = mapOrder + 1
            map.copy(groupOrder = groupOrder, mapOrder = mapOrder)
        }
    }
}

/** 병합된 정적 그룹, 맵, 별칭을 순서가 고정된 일반 INSERT 문으로 직렬화한다. */
internal class BattleMapSeedSqlWriter {
    fun write(sourceMaps: List<SeedMap>): String {
        val maps = sourceMaps.sortedWith(SEED_MAP_ORDER)
        val groups = maps.mapNotNull { map ->
            map.groupName?.let { name ->
                SeedGroup(map.categoryId, name, map.groupOrder, map.recommendedLevel)
            }
        }.groupBy(SeedGroup::key)
            .map { (key, candidates) ->
                val displayOrders = candidates.map(SeedGroup::displayOrder).distinct()
                check(displayOrders.size == 1) { "seed group order conflict: $key=$displayOrders" }
                candidates.first().copy(
                    recommendedLevel = candidates.mapNotNull(SeedGroup::recommendedLevel).firstOrNull(),
                )
            }.sortedWith(SEED_GROUP_ORDER)

        return buildString {
            groups.forEach { group ->
                appendLine(
                    "insert into battle_map_groups " +
                        "(category_id, name, display_order, recommended_level) values " +
                        "(${group.categoryId.sqlLiteral()}, ${group.name.sqlLiteral()}, " +
                        "${group.displayOrder}, ${group.recommendedLevel.sqlNullable()});",
                )
            }
            maps.forEach { map ->
                val groupId = map.groupName?.let { groupName ->
                    "(select id from battle_map_groups where category_id = " +
                        "${map.categoryId.sqlLiteral()} and name = ${groupName.sqlLiteral()})"
                } ?: "null"
                appendLine(
                    "insert into battle_maps " +
                        "(category_id, map_code, group_id, name, normalized_name, display_order, " +
                        "required_time, icon_url, enabled, created_at, updated_at) values " +
                        "(${map.categoryId.sqlLiteral()}, ${map.mapCode.sqlLiteral()}, $groupId, " +
                        "${map.name.sqlLiteral()}, " +
                        "${BattleMapIdentityNormalizer.normalize(map.name).sqlLiteral()}, ${map.mapOrder}, " +
                        "${map.requiredTime.sqlNullable()}, ${map.iconUrl.sqlNullable()}, true, " +
                        "current_timestamp, current_timestamp);",
                )
            }
            maps.forEach { map ->
                aliasRows(map).forEach { alias ->
                    appendLine(
                        "insert into battle_map_aliases (battle_map_id, alias, normalized_alias) values " +
                            "((select id from battle_maps where category_id = ${map.categoryId.sqlLiteral()} " +
                            "and map_code = ${map.mapCode.sqlLiteral()}), ${alias.value.sqlLiteral()}, " +
                            "${alias.normalizedValue.sqlLiteral()});",
                    )
                }
            }
        }
    }

    private fun aliasRows(map: SeedMap): List<SeedAlias> {
        val valuesByNormalizedAlias = linkedMapOf<String, String>()
        (map.aliases + map.name).forEach { sourceValue ->
            BattleMapIdentityNormalizer.aliasValues(sourceValue).forEach { aliasValue ->
                val normalized = BattleMapIdentityNormalizer.normalize(aliasValue)
                if (normalized.isNotBlank()) valuesByNormalizedAlias.putIfAbsent(normalized, aliasValue)
            }
        }
        return valuesByNormalizedAlias.map { (normalized, value) -> SeedAlias(value, normalized) }
            .sortedWith(compareBy(SeedAlias::normalizedValue).thenBy(SeedAlias::value))
    }

    private fun String?.sqlNullable(): String = this?.sqlLiteral() ?: "null"

    private fun Int?.sqlNullable(): String = this?.toString() ?: "null"

    private fun String.sqlLiteral(): String = "'${replace("'", "''")}'"
}

internal data class FixtureSource(
    val categoryId: String,
    val queryName: String,
    val relativePath: List<String>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApkSeedDocument(
    val battles: List<ApkSeedMap> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class ApkSeedMap(
    val label: String = "",
    val map: String = "",
    val mapName: String = "",
    val param: String = "",
    val group: String = "",
) {
    fun toCatalogMap(): ApkCatalogMap? {
        val categoryId = when (param.trim()) {
            "common" -> "battle_map"
            "sp_common" -> "adventure_map"
            else -> return null
        }
        val code = map.trim().takeIf(String::isNotBlank) ?: return null
        val cleanLabel = label.collapseWhitespace()
        val cleanMapName = mapName.collapseWhitespace()
        val aliases = listOf(cleanLabel, cleanMapName).filter(String::isNotBlank)
        val preferredName = cleanMapName.ifBlank { cleanLabel }.ifBlank { code }
        return ApkCatalogMap(
            categoryId = categoryId,
            mapCode = code,
            preferredName = preferredName,
            groupName = group.collapseWhitespace().ifBlank { null },
            aliasValues = aliases,
        )
    }
}

internal data class ApkCatalogMap(
    val categoryId: String,
    val mapCode: String,
    val preferredName: String,
    val groupName: String?,
    val aliasValues: List<String>,
) {
    val key: SeedMapKey = SeedMapKey(categoryId, mapCode)
    val normalizedAliases: Set<String> = aliasValues
        .flatMap(BattleMapIdentityNormalizer::aliases)
        .toSet()
}

internal data class SeedMap(
    val categoryId: String,
    val mapCode: String,
    val name: String,
    val groupName: String?,
    val groupOrder: Int,
    val mapOrder: Int,
    val recommendedLevel: String?,
    val requiredTime: Int?,
    val iconUrl: String?,
    val aliases: List<String>,
    val fixtureMetadata: Boolean,
) {
    val key: SeedMapKey = SeedMapKey(categoryId, mapCode)
}

internal data class SeedMapKey(
    val categoryId: String,
    val mapCode: String,
)

private data class SeedGroup(
    val categoryId: String,
    val name: String,
    val displayOrder: Int,
    val recommendedLevel: String?,
) {
    val key: Pair<String, String> = categoryId to name
}

private data class SeedAlias(
    val value: String,
    val normalizedValue: String,
)

private data class SeedProjectLayout(
    val repositoryRoot: Path,
) {
    val seedMigration: Path = repositoryRoot
        .resolve("hof_backend/src/main/resources/db/migration/V2__seed_battle_map_catalog.sql")

    companion object {
        fun locate(): SeedProjectLayout {
            val current = Path.of("").toAbsolutePath().normalize()
            val repositoryRoot = generateSequence(current) { path -> path.parent }
                .firstOrNull { candidate ->
                    Files.isRegularFile(candidate.resolve("hof_backend/build.gradle.kts")) &&
                        Files.isDirectory(candidate.resolve("example"))
                } ?: error("HOF repository root not found from $current")
            return SeedProjectLayout(repositoryRoot)
        }
    }
}

/** macOS NFD와 일반 NFC 이름을 동일하게 비교해 저장 fixture의 실제 경로를 찾는다. */
private object SeedFixturePathResolver {
    fun resolve(
        root: Path,
        relativeSegments: List<String>,
    ): Path = relativeSegments.fold(root) { directory, expectedName ->
        check(Files.isDirectory(directory)) { "fixture directory not found: $directory" }
        val normalizedExpectedName = expectedName.normalizedFileName()
        val matches = Files.list(directory).use { children ->
            children.filter { child -> child.fileName.toString().normalizedFileName() == normalizedExpectedName }
                .sorted()
                .toList()
        }
        check(matches.size == 1) {
            "fixture path segment must resolve exactly once: directory=$directory name=$expectedName matches=$matches"
        }
        matches.single()
    }

    private fun String.normalizedFileName(): String =
        Normalizer.normalize(this, Normalizer.Form.NFC).lowercase(Locale.ROOT)
}

private fun HofBattleMap.toSeedMap(mapCode: String): SeedMap =
    SeedMap(
        categoryId = categoryId,
        mapCode = mapCode,
        name = name,
        groupName = groupName,
        groupOrder = groupOrder,
        mapOrder = mapOrder,
        recommendedLevel = recommendedLevel,
        requiredTime = requiredTime,
        iconUrl = iconUrl.sanitizedFixtureIconUrl(),
        aliases = listOf(name),
        fixtureMetadata = true,
    )

private fun mergeAliasValues(
    first: Iterable<String>,
    second: Iterable<String>,
): List<String> {
    val valuesByNormalizedAlias = linkedMapOf<String, String>()
    (first + second).forEach { value ->
        val cleanValue = value.collapseWhitespace()
        val normalized = BattleMapIdentityNormalizer.normalize(cleanValue)
        if (normalized.isNotBlank()) valuesByNormalizedAlias.putIfAbsent(normalized, cleanValue)
    }
    return valuesByNormalizedAlias.values.toList()
}

private fun String.collapseWhitespace(): String = replace(Regex("""\s+"""), " ").trim()

/** 브라우저의 페이지 저장 폴더를 가리키는 로컬 capture URL은 정적 HOF asset으로 시드하지 않는다. */
private fun String?.sanitizedFixtureIconUrl(): String? =
    this?.takeUnless { value -> value.contains("_files", ignoreCase = true) }

private val SEED_KEY_ORDER = compareBy<SeedMapKey>(SeedMapKey::categoryId).thenBy(SeedMapKey::mapCode)

private val SEED_MAP_ORDER = compareBy<SeedMap>(SeedMap::categoryId)
    .thenBy(SeedMap::groupOrder)
    .thenBy(SeedMap::mapOrder)
    .thenBy(SeedMap::mapCode)

private val SEED_GROUP_ORDER = compareBy<SeedGroup>(SeedGroup::categoryId)
    .thenBy(SeedGroup::displayOrder)
    .thenBy(SeedGroup::name)
