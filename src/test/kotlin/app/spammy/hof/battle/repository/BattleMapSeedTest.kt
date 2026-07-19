package app.spammy.hof.battle.repository

import app.spammy.hof.battle.entity.BattleMapAliasEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.model.BattleMapIdentityNormalizer
import app.spammy.hof.battle.seed.BattleMapSeedGenerator
import app.spammy.hof.common.persistence.QueryDslConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** 실제 V2 SQL을 PostgreSQL 호환 H2에 적용하고 QueryDSL 조회 결과로 정적 시드를 검증한다. */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QueryDslConfig::class, BattleMapQueryRepository::class)
class BattleMapSeedTest {
    @Autowired
    private lateinit var queryRepository: BattleMapQueryRepository

    @Autowired
    private lateinit var dataSource: DataSource

    @Test
    fun seedsCapturedBattleAndAdventureMapsAsRows() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "select setting_value from information_schema.settings where setting_name = 'MODE'",
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("PostgreSQL", rows.getString(1))
                }
            }
        }

        val battleMaps = queryRepository.findMapsByCategoryId(BATTLE_CATEGORY)
        val adventureMaps = queryRepository.findMapsByCategoryId(ADVENTURE_CATEGORY)

        assertEquals(96, battleMaps.size)
        assertEquals(127, adventureMaps.size)
        assertEquals(setOf(BATTLE_CATEGORY, ADVENTURE_CATEGORY), queryStrings("select distinct category_id from battle_maps"))
        assertEquals(50L, queryLong("select count(*) from battle_map_groups"))
        assertEquals(483L, queryLong("select count(*) from battle_map_aliases"))
        assertNotNull(queryRepository.findMapByCategoryIdAndMapCode(BATTLE_CATEGORY, "gb0"))
        assertNotNull(queryRepository.findMapByCategoryIdAndMapCode(ADVENTURE_CATEGORY, "Simul001"))
        assertNotNull(queryRepository.findMapByCategoryIdAndMapCode(ADVENTURE_CATEGORY, "Noble205"))
        assertNotNull(queryRepository.findMapByCategoryIdAndMapCode(BATTLE_CATEGORY, "Fish02"))
        assertNotNull(queryRepository.findMapByCategoryIdAndMapCode(ADVENTURE_CATEGORY, "DAYM0050"))
        setOf("0001", "0002", "0003", "0004", "raid002").forEach { excludedCode ->
            assertNull(queryRepository.findMapByCategoryIdAndMapCode(BATTLE_CATEGORY, excludedCode))
            assertNull(queryRepository.findMapByCategoryIdAndMapCode(ADVENTURE_CATEGORY, excludedCode))
        }
    }

    @Test
    fun resolvesCapturedCooldownAliasesToOneSourceCode() {
        assertEquals("gb7", mapCodeForAlias(ADVENTURE_CATEGORY, "고블린 콜로세움"))
        assertEquals("Noble205", mapCodeForAlias(ADVENTURE_CATEGORY, "저택 서관(놀이방)"))
    }

    @Test
    fun normalizesPermanentKeyMapNameWhilePreservingLegacyAlias() {
        val map = assertNotNull(queryRepository.findMapByCategoryIdAndMapCode(ADVENTURE_CATEGORY, "min08"))

        assertEquals(PERMANENT_KEY_CLEAN_NAME, map.name)
        assertEquals(
            BattleMapIdentityNormalizer.normalize(PERMANENT_KEY_CLEAN_NAME),
            queryString(
                "select normalized_name from battle_maps " +
                    "where category_id = 'adventure_map' and map_code = 'min08'",
            ),
        )
        assertEquals("min08", mapCodeForAlias(ADVENTURE_CATEGORY, PERMANENT_KEY_LEGACY_NAME))
    }

    @Test
    fun preservesDeterministicTreeOrderAndUniqueAliases() {
        listOf(BATTLE_CATEGORY, ADVENTURE_CATEGORY).forEach { categoryId ->
            val maps = queryRepository.findMapsByCategoryId(categoryId)
            assertEquals(maps.sortedWith(MAP_ORDER), maps)

            val aliases = queryRepository.findAliasesByCategoryId(categoryId)
            assertEquals(
                aliases.size,
                aliases.distinctBy { alias -> alias.battleMap.id to alias.normalizedAlias }.size,
            )
            assertTrue(aliases.all { alias -> alias.alias.isNotBlank() && alias.normalizedAlias.isNotBlank() })
        }
    }

    @Test
    fun generatedMigrationIsDeterministicPlainInsertSql() {
        val migrationSql = Files.readString(SEED_MIGRATION, StandardCharsets.UTF_8)
        val statements = migrationSql.lineSequence().filter(String::isNotBlank).toList()

        assertEquals(migrationSql, BattleMapSeedGenerator.generateSql())
        assertTrue(statements.all { statement -> statement.startsWith("insert into ") })
        assertFalse(migrationSql.contains("_json", ignoreCase = true))
        assertFalse(migrationSql.contains("config_json", ignoreCase = true))
        assertFalse(migrationSql.contains("_files", ignoreCase = true))
        assertTrue(migrationSql.contains("Noble''s Mansion"))
        assertTrue(
            migrationSql.contains(
                "'adventure_map', 'min08', (select id from battle_map_groups where category_id = " +
                    "'adventure_map' and name = '지각 내부'), '$PERMANENT_KEY_LEGACY_NAME', " +
                    "'dead pit- 지각 내부 (b4) tuls의 문( x )', 0, 100",
            ),
        )
        assertTrue(
            migrationSql.contains(
                "map_code = 'min08'), '$PERMANENT_KEY_LEGACY_NAME', " +
                    "'dead pit- 지각 내부 (b4) tuls의 문( x )'",
            ),
        )

        val lastGroup = statements.indexOfLast { it.startsWith("insert into battle_map_groups ") }
        val firstMap = statements.indexOfFirst { it.startsWith("insert into battle_maps ") }
        val lastMap = statements.indexOfLast { it.startsWith("insert into battle_maps ") }
        val firstAlias = statements.indexOfFirst { it.startsWith("insert into battle_map_aliases ") }
        assertTrue(lastGroup in 0 until firstMap)
        assertTrue(lastMap in firstMap until firstAlias)
    }

    @Test
    fun removesCapturedLocalIconUrlsFromStaticSeed() {
        val iconUrls = queryStrings("select icon_url from battle_maps where icon_url is not null")

        assertTrue(iconUrls.none { iconUrl -> iconUrl.contains("_files", ignoreCase = true) })
        assertTrue(iconUrls.none { iconUrl -> iconUrl.contains("Hall of Fame Ver ZeroHOF_files", ignoreCase = true) })
    }

    private fun queryLong(sql: String): Long =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    assertTrue(rows.next())
                    rows.getLong(1)
                }
            }
        }

    private fun queryStrings(sql: String): Set<String> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    buildSet {
                        while (rows.next()) add(rows.getString(1))
                    }
                }
            }
        }

    private fun queryString(sql: String): String =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    assertTrue(rows.next())
                    rows.getString(1)
                }
            }
        }

    private fun mapCodeForAlias(
        categoryId: String,
        alias: String,
    ): String =
        queryRepository.findAliasesByCategoryIdAndNormalizedAliases(
            categoryId,
            BattleMapIdentityNormalizer.aliases(alias),
        ).map(BattleMapAliasEntity::battleMap)
            .distinctBy(BattleMapEntity::mapCode)
            .single()
            .mapCode

    companion object {
        private const val BATTLE_CATEGORY = "battle_map"
        private const val ADVENTURE_CATEGORY = "adventure_map"
        private const val PERMANENT_KEY_CLEAN_NAME = "Dead Pit- 지각 내부 (B4) Tuls의 문"
        private const val PERMANENT_KEY_LEGACY_NAME = "Dead Pit- 지각 내부 (B4) Tuls의 문( x )"
        private val databaseName = "battle_map_seed_${UUID.randomUUID().toString().replace("-", "")}"
        private val SEED_MIGRATION = Path.of("src/main/resources/db/migration/V2__seed_battle_map_catalog.sql")
        private val MAP_ORDER = compareBy<BattleMapEntity> { it.group?.displayOrder ?: Int.MAX_VALUE }
            .thenBy(BattleMapEntity::displayOrder)
            .thenBy(BattleMapEntity::name)
            .thenBy(BattleMapEntity::id)

        @JvmStatic
        @DynamicPropertySource
        fun seedDatabase(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:h2:mem:$databaseName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;" +
                    "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
            }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
            registry.add("spring.flyway.enabled") { "true" }
        }
    }
}
