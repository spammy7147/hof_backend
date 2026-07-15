package app.spammy.hof.battle.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.account.repository.HofCookieRepository
import app.spammy.hof.account.service.HofCookieCipher
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
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.BattleMapParser
import java.io.IOException
import java.net.http.HttpTimeoutException
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@DataJpaTest
@ActiveProfiles("test")
@Import(
    QueryDslConfig::class,
    AccountQueryRepository::class,
    CookieQueryRepository::class,
    BattleMapQueryRepository::class,
    BattleMapIdentityResolver::class,
    BattleMapCatalogService::class,
    BattleMapCatalogTransactionService::class,
    BattleMapService::class,
    BattleMapParser::class,
    HofRequestFactory::class,
    BattleMapServiceTest.TestConfig::class,
)
class BattleMapServiceTest {
    @Autowired
    private lateinit var service: BattleMapService

    @Autowired
    private lateinit var gateway: FakeHofGateway

    @Autowired
    private lateinit var accountRepository: HofAccountRepository

    @Autowired
    private lateinit var cookieRepository: HofCookieRepository

    @Autowired
    private lateinit var groupRepository: BattleMapGroupCommandRepository

    @Autowired
    private lateinit var mapRepository: BattleMapRepository

    @Autowired
    private lateinit var aliasRepository: BattleMapAliasCommandRepository

    @Autowired
    private lateinit var queryRepository: BattleMapQueryRepository

    @BeforeEach
    fun resetGateway() {
        gateway.reset()
    }

    @Test
    fun resolvesArbitrarySeededPlaceholderAliasesOnFirstSyncWithCooldownSeconds() {
        val account = savedAccount("battle-map-aliases")
        val group = savedGroup(ADVENTURE, "행사 지역", 4)
        val festival = savedMap(ADVENTURE, "test-festival03", "Festival Catalog", group, 20)
        val noble = savedMap(ADVENTURE, "test-Noble205", "Noble Catalog", group, 30)
        saveAliases(festival, "Festival- 별빛 축제")
        saveAliases(noble, "Noble's Mansion- 귀족 무도회")
        gateway.defaultBody = """
            <div>행사 지역 (적정 레벨 : 50-70)(2)</div>
            <div id="mapgroup4">
              <p><a href="index.php?sp_hunt#">Festival- 별빛 축제</a> (45분) 남음 (Time : 100)</p>
              <p><a href="index.php?sp_hunt#">Noble's Mansion- 귀족 무도회</a> (1시간) 남음 (Time : 200)</p>
            </div>
        """.trimIndent()

        val maps = service.findMaps(account.id, ADVENTURE)

        assertEquals(listOf("test-festival03", "test-Noble205"), maps.map { it.mapCode })
        assertTrue(maps.all { it.resolved })
        assertFalse(maps.any { it.enabled })
        assertEquals(listOf(2_700L, 3_600L), maps.map { it.cooldownRemainingSeconds })
        assertEquals(listOf("45분", "1시간 0분"), maps.map { it.cooldownRemainingText })
        assertEquals(listOf("index.php?sp_hunt#", "index.php?sp_hunt#"), maps.map { it.rawHref })
        assertEquals("Festival- 별빛 축제", festival.name)
        assertEquals("Noble's Mansion- 귀족 무도회", noble.name)
    }

    @Test
    fun persistsObservedThreeBattleCapabilityAndReturnsItInTheResponse() {
        val account = savedAccount("battle-map-three-capability")
        gateway.defaultBody = """
            <a href="index.php?common=three01">Three-capable map</a>
            <form method="post"><input type="submit" name="monster_battle_10" value="Battle !"></form>
        """.trimIndent()

        val response = service.findMaps(account.id, "battle_map").single()
        val state = queryRepository.findStateForExecution(account.id, "battle_map", "three01")

        assertTrue(response.supportsThreeBattles)
        assertTrue(requireNotNull(state).supportsThreeBattles)
    }

    @Test
    fun persistsAmbiguousAndUnknownPlaceholdersAsDisabledUnresolvedRows() {
        val account = savedAccount("battle-map-unresolved")
        val group = savedGroup(ADVENTURE, "미지 지역", 2)
        val first = savedMap(ADVENTURE, "ambiguous01", "First Catalog", group, 1)
        val second = savedMap(ADVENTURE, "ambiguous02", "Second Catalog", group, 2)
        saveAliases(first, "Future- 겹치는 이름")
        saveAliases(second, "Future- 겹치는 이름")
        gateway.defaultBody = """
            <div>미지 지역 (적정 레벨 : ??)(2)</div>
            <div id="mapgroup2">
              <p><a href="index.php?sp_hunt#">Future- 겹치는 이름</a> (Time : 10)</p>
              <p><a href="index.php?sp_hunt#">Future- 아직 모르는 이름</a> (Time : 20)</p>
            </div>
        """.trimIndent()

        val maps = service.findMaps(account.id, ADVENTURE)

        assertEquals(2, maps.size)
        assertTrue(maps.all { it.mapCode == null })
        assertTrue(maps.none { it.resolved })
        assertTrue(maps.none { it.enabled })
        assertEquals(
            listOf("Future- 겹치는 이름", "Future- 아직 모르는 이름"),
            queryRepository.findVisibleUnresolvedByAccountIdAndCategoryId(account.id, ADVENTURE).map { it.observedName },
        )
        assertTrue(queryRepository.findVisibleStatesByAccountIdAndCategoryId(account.id, ADVENTURE).isEmpty())
    }

    @Test
    fun bootstrapsDirectFutureCodeAndRemovesTheMatchingPreviouslyUnresolvedRow() {
        val account = savedAccount("battle-map-future")
        gateway.defaultBody = """
            <div>미래 지역 (1)</div>
            <div id="mapgroup6">
              <a href="index.php?sp_hunt#">Future Zone- 미래 지역(신규)</a>
            </div>
        """.trimIndent()
        val unresolved = service.findMaps(account.id, ADVENTURE).single()
        assertNull(unresolved.mapCode)
        assertEquals(1, queryRepository.findUnresolvedByAccountIdAndCategoryId(account.id, ADVENTURE).size)

        gateway.defaultBody = """
            <div>미래 지역 (1)</div>
            <div id="mapgroup6">
              <a href="index.php?sp_common=future900">Future Zone- 미래 지역(신규)</a>
            </div>
        """.trimIndent()

        val resolved = service.findMaps(account.id, ADVENTURE).single()

        assertEquals("future900", resolved.mapCode)
        assertTrue(resolved.resolved)
        assertTrue(resolved.enabled)
        val catalogMap = assertNotNull(queryRepository.findMapByCategoryIdAndMapCode(ADVENTURE, "future900"))
        val aliases = queryRepository.findAliasesByMapId(catalogMap.id)
        assertEquals(
            setOf("future zone- 미래 지역(신규)", "미래 지역(신규)"),
            aliases.map { it.normalizedAlias }.toSet(),
        )
        assertEquals(
            setOf("Future Zone- 미래 지역(신규)", "미래 지역(신규)"),
            aliases.map { it.alias }.toSet(),
        )
        assertTrue(queryRepository.findUnresolvedByAccountIdAndCategoryId(account.id, ADVENTURE).isEmpty())
    }

    @Test
    fun marksOnlyRefreshingAccountsStaleStateInvisibleWithoutDeletingStaticCatalog() {
        val firstAccount = savedAccount("battle-map-state-first")
        val secondAccount = savedAccount("battle-map-state-second")
        gateway.defaultBody = directAdventureHtml(sharedCount = 3, includeStale = true)
        service.findMaps(firstAccount.id, ADVENTURE)
        gateway.defaultBody = directAdventureHtml(sharedCount = 7, includeStale = false)
        service.findMaps(secondAccount.id, ADVENTURE)

        gateway.defaultBody = directAdventureHtml(sharedCount = 1, includeStale = false)
        val refreshed = service.findMaps(firstAccount.id, ADVENTURE)

        assertEquals(listOf("shared01"), refreshed.map { it.mapCode })
        val staleMap = assertNotNull(queryRepository.findMapByCategoryIdAndMapCode(ADVENTURE, "stale01"))
        assertFalse(assertNotNull(queryRepository.findStateByAccountIdAndMapId(firstAccount.id, staleMap.id)).visible)
        assertNotNull(queryRepository.findMapByCategoryIdAndMapCode(ADVENTURE, "stale01"))
        val sharedMap = assertNotNull(queryRepository.findMapByCategoryIdAndMapCode(ADVENTURE, "shared01"))
        assertEquals(1, queryRepository.findStateByAccountIdAndMapId(firstAccount.id, sharedMap.id)?.availableCount)
        assertEquals(7, queryRepository.findStateByAccountIdAndMapId(secondAccount.id, sharedMap.id)?.availableCount)
        assertTrue(assertNotNull(queryRepository.findStateByAccountIdAndMapId(secondAccount.id, sharedMap.id)).visible)
    }

    @Test
    fun preservesVisibleStateWhenUpstreamPageHasNoMapObservations() {
        val account = savedAccount("battle-map-empty-source")
        gateway.defaultBody = directAdventureHtml(sharedCount = 3, includeStale = false)
        assertEquals(listOf("shared01"), service.findMaps(account.id, ADVENTURE).map { it.mapCode })
        val catalogMap = assertNotNull(queryRepository.findMapByCategoryIdAndMapCode(ADVENTURE, "shared01"))

        val invalidBodies = listOf(
            "",
            """<html><body><form action="index.php" method="post"><input name="id"></form></body></html>""",
            """
                <html><body>
                  <h1>Temporary upstream error</h1>
                  <div id="mapgroup1"><a href="#">Back</a></div>
                </body></html>
            """.trimIndent(),
        )
        invalidBodies.forEach { body ->
            gateway.defaultBody = body

            val preserved = service.findMaps(account.id, ADVENTURE)

            assertEquals(listOf("shared01"), preserved.map { it.mapCode })
            assertEquals(3, preserved.single().availableCount)
            assertTrue(assertNotNull(queryRepository.findStateByAccountIdAndMapId(account.id, catalogMap.id)).visible)
        }
    }

    @Test
    fun authenticatedAdventureRefreshRejectsPageWithoutMapObservations() {
        val account = savedAccount("battle-map-strict-refresh")
        gateway.defaultBody = "<html><body><h1>Temporary upstream error</h1></body></html>"

        assertFailsWith<AdventureMapRefreshException.Fatal> {
            service.refreshAdventureMaps(account.id)
        }
        assertEquals(
            listOf("http://sic.zerosic.com/ZeroHOF/index.php?sp_hunt"),
            gateway.requests.map { it.url },
        )
    }

    @Test
    fun authenticatedAdventureRefreshRejectsNonSuccessHttpResponse() {
        val account = savedAccount("battle-map-http-error")
        val url = "http://sic.zerosic.com/ZeroHOF/index.php?sp_hunt"
        gateway.responsesByUrl[url] = HofHttpResponse(
            statusCode = 503,
            finalUrl = url,
            body = directAdventureHtml(sharedCount = 3, includeStale = false),
            setCookies = emptyMap(),
        )

        assertFailsWith<AdventureMapRefreshException.Retryable> {
            service.refreshAdventureMaps(account.id)
        }
        assertTrue(queryRepository.findVisibleStatesByAccountIdAndCategoryId(account.id, ADVENTURE).isEmpty())
    }

    @Test
    fun authenticatedAdventureRefreshRejectsClientHttpResponseAsFatal() {
        val account = savedAccount("battle-map-http-client-error")
        val url = "http://sic.zerosic.com/ZeroHOF/index.php?sp_hunt"
        gateway.responsesByUrl[url] = HofHttpResponse(
            statusCode = 403,
            finalUrl = url,
            body = directAdventureHtml(sharedCount = 3, includeStale = false),
            setCookies = emptyMap(),
        )

        assertFailsWith<AdventureMapRefreshException.Fatal> {
            service.refreshAdventureMaps(account.id)
        }
        assertTrue(queryRepository.findVisibleStatesByAccountIdAndCategoryId(account.id, ADVENTURE).isEmpty())
    }

    @Test
    fun authenticatedAdventureRefreshClassifiesTransportAndTimeoutFailuresAsRetryable() {
        val account = savedAccount("battle-map-transport-errors")

        listOf(IOException("connection reset"), HttpTimeoutException("timed out")).forEach { failure ->
            gateway.failure = failure

            assertFailsWith<AdventureMapRefreshException.Retryable> {
                service.refreshAdventureMaps(account.id)
            }
        }
    }

    @Test
    fun authenticatedAdventureRefreshPreservesNearestFatalTypeOverNestedTransportCause() {
        val account = savedAccount("battle-map-fatal-wrapped-transport")
        val fatal = AdventureMapRefreshException.Fatal("fatal parse", IOException("nested transport"))
        gateway.failure = fatal

        val actual = assertFailsWith<AdventureMapRefreshException.Fatal> {
            service.refreshAdventureMaps(account.id)
        }

        assertEquals(fatal, actual)
    }

    @Test
    fun authenticatedAdventureRefreshRestoresInterruptAndDoesNotClassifyCancellationAsRetryable() {
        val account = savedAccount("battle-map-interrupted")
        gateway.failure = InterruptedException("cancelled")

        try {
            assertFailsWith<AdventureMapRefreshException.Fatal> {
                service.refreshAdventureMaps(account.id)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun fetchesScenarioOceanDetailPageBeforeSynchronizingMaps() {
        val account = savedAccount("battle-map-scenario")
        gateway.responsesByUrl["http://sic.zerosic.com/ZeroHOF/index.php?raid_hunt"] = HofHttpResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/index.php?raid_hunt",
            body = """<a href="index.php?menu=MapSunkenShip">대해</a>""",
            setCookies = mapOf("NO" to "scenario"),
        )
        gateway.responsesByUrl["http://sic.zerosic.com/ZeroHOF/index.php?menu=MapSunkenShip"] = HofHttpResponse(
            statusCode = 200,
            finalUrl = "http://sic.zerosic.com/ZeroHOF/index.php?menu=MapSunkenShip",
            body = """
                <div>대해 (적정 레벨 : 40-60) (2)</div>
                <div id="mapgroup1">
                  <a href="index.php?common=sea001">Scenario- 대해(난파선 입구)</a>
                  <a href="index.php?common=sea002">Scenario- 대해(침몰한 갑판)</a>
                </div>
            """.trimIndent(),
            setCookies = emptyMap(),
        )

        val maps = service.findMaps(account.id, "scenario_ocean")

        assertEquals(listOf("sea001", "sea002"), maps.map { it.mapCode })
        assertEquals("대해", maps.first().groupName)
        assertTrue(maps.all { it.resolved })
        assertEquals(
            listOf(
                "http://sic.zerosic.com/ZeroHOF/index.php?raid_hunt",
                "http://sic.zerosic.com/ZeroHOF/index.php?menu=MapSunkenShip",
            ),
            gateway.requests.map { it.url },
        )
        assertEquals(mapOf("PHPSESSID" to "abc", "NO" to "scenario"), gateway.cookies[1])
    }

    private fun savedAccount(loginId: String): HofAccountEntity {
        val account = accountRepository.save(
            HofAccountEntity(
                loginId = loginId,
                encryptedPassword = "encrypted",
                createdAt = NOW,
            ),
        )
        cookieRepository.save(
            HofCookieEntity(
                account = account,
                name = "PHPSESSID",
                value = "abc",
                updatedAt = NOW,
            ),
        )
        return account
    }

    private fun savedGroup(
        categoryId: String,
        name: String,
        order: Int,
    ): BattleMapGroupEntity =
        groupRepository.save(
            BattleMapGroupEntity(
                categoryId = categoryId,
                name = name,
                displayOrder = order,
            ),
        )

    private fun savedMap(
        categoryId: String,
        mapCode: String,
        name: String,
        group: BattleMapGroupEntity,
        order: Int,
    ): BattleMapEntity =
        mapRepository.save(
            BattleMapEntity(
                categoryId = categoryId,
                mapCode = mapCode,
                group = group,
                name = name,
                normalizedName = BattleMapIdentityNormalizer.normalize(name),
                displayOrder = order,
                requiredTime = null,
                iconUrl = null,
                enabled = true,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )

    private fun saveAliases(
        map: BattleMapEntity,
        name: String,
    ) {
        aliasRepository.saveAll(
            BattleMapIdentityNormalizer.aliases(name).map { normalizedAlias ->
                BattleMapAliasEntity(
                    battleMap = map,
                    alias = name,
                    normalizedAlias = normalizedAlias,
                )
            },
        )
        aliasRepository.flush()
    }

    private fun directAdventureHtml(
        sharedCount: Int,
        includeStale: Boolean,
    ): String =
        """
            <div>공유 지역 (2)</div>
            <div id="mapgroup1">
              <p><a href="index.php?sp_common=shared01">Shared- 공유 맵</a> $sharedCount 가능</p>
              ${if (includeStale) "<p><a href=\"index.php?sp_common=stale01\">Shared- 사라질 맵</a> 2 가능</p>" else ""}
            </div>
        """.trimIndent()

    @TestConfiguration
    class TestConfig {
        @Bean
        fun hofCookieCipher(): HofCookieCipher =
            HofCookieCipher(Base64.getEncoder().encodeToString(ByteArray(32) { 11 }))

        @Bean
        fun timeProvider(): TimeProvider = TimeProvider { NOW }

        @Bean
        fun fakeHofGateway(): FakeHofGateway = FakeHofGateway()
    }

    class FakeHofGateway : HofGateway {
        val requests = mutableListOf<HofRequest>()
        val cookies = mutableListOf<Map<String, String>>()
        val responsesByUrl = mutableMapOf<String, HofHttpResponse>()
        var defaultBody: String = ""
        var failure: Exception? = null

        override fun execute(
            request: HofRequest,
            cookies: Map<String, String>,
        ): HofHttpResponse {
            failure?.let { throw it }
            requests += request
            this.cookies += cookies
            return responsesByUrl[request.url] ?: HofHttpResponse(
                statusCode = 200,
                finalUrl = request.url,
                body = defaultBody,
                setCookies = emptyMap(),
            )
        }

        fun reset() {
            requests.clear()
            cookies.clear()
            responsesByUrl.clear()
            defaultBody = ""
            failure = null
        }
    }

    private companion object {
        const val ADVENTURE = "adventure_map"
        val NOW: Instant = Instant.parse("2026-07-08T00:00:00Z")
    }
}
