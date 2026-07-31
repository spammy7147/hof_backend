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
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.battle.repository.AccountBattleMapStateCommandRepository
import app.spammy.hof.battle.repository.BattleMapAliasCommandRepository
import app.spammy.hof.battle.repository.BattleMapGroupCommandRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.repository.BattleMapRepository
import app.spammy.hof.battle.repository.UnresolvedBattleMapCommandRepository
import app.spammy.hof.common.persistence.QueryDslConfig
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.captcha.dto.CaptchaChallengeResponse
import app.spammy.hof.captcha.service.CaptchaService
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.BattleMapParser
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.status.service.HofStatusSnapshotService
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
import org.mockito.Mockito
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
    BattleMapCapabilityObservationService::class,
    BattleMapParser::class,
    HofRequestFactory::class,
    BattleMapServiceTest.TestConfig::class,
)
class BattleMapServiceTest {
    @Autowired
    private lateinit var service: BattleMapService

    @Autowired
    private lateinit var capabilityObservationService: BattleMapCapabilityObservationService

    @Autowired
    private lateinit var gateway: FakeHofGateway

    @Autowired
    private lateinit var captchaService: CaptchaService

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
        Mockito.reset(captchaService)
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
            <form method="post" action="index.php?common=three01">
              <a href="index.php?common=three01">Three-capable map</a>
              <input type="submit" name="monster_battle_10" value="Battle !">
            </form>
        """.trimIndent()

        val response = service.findMaps(account.id, "battle_map").single()
        val state = queryRepository.findStateForExecution(account.id, "battle_map", "three01")

        assertTrue(response.supportsThreeBattles)
        assertTrue(requireNotNull(state).supportsThreeBattles)
    }

    @Test
    fun persistsResolvedUnlimitedAndUnresolvedLimitedKeyObservations() {
        val resolvedAccount = savedAccount("battle-map-unlimited-key")
        gateway.defaultBody = """
            <div>지각 내부 (1)</div>
            <div id="mapgroup1">
              <a href="index.php?sp_common=min08">Dead Pit- 지각 내부 (B4) Tuls의 문( x )</a>
            </div>
        """.trimIndent()

        val unlimited = service.findMaps(resolvedAccount.id, ADVENTURE).single()
        val unlimitedState = assertNotNull(queryRepository.findStateForExecution(resolvedAccount.id, ADVENTURE, "min08"))
        assertEquals(BattleMapKeyMode.UNLIMITED, unlimited.keyMode)
        assertNull(unlimited.keyCount)
        assertTrue(unlimited.enabled)
        assertEquals(BattleMapKeyMode.UNLIMITED, unlimitedState.keyMode)
        assertNull(unlimitedState.keyCount)
        assertTrue(unlimitedState.visible)

        val unresolvedAccount = savedAccount("battle-map-unresolved-key")
        gateway.defaultBody = """
            <div>미지 지역 (1)</div>
            <div id="mapgroup2">
              <a href="index.php?sp_hunt#">Future- 아직 모르는 이름 ( x12 )</a>
            </div>
        """.trimIndent()

        val limited = service.findMaps(unresolvedAccount.id, ADVENTURE).single()
        val unresolved = queryRepository.findVisibleUnresolvedByAccountIdAndCategoryId(unresolvedAccount.id, ADVENTURE).single()
        assertEquals(BattleMapKeyMode.LIMITED, limited.keyMode)
        assertEquals(12, limited.keyCount)
        assertEquals(BattleMapKeyMode.LIMITED, unresolved.keyMode)
        assertEquals(12, unresolved.keyCount)
    }

    @Test
    fun authoritativeDetailFormCanRefreshCapabilityWithoutGuessingFromAnUnrelatedPage() {
        val account = savedAccount("battle-map-detail-capability")
        gateway.defaultBody = """
            <form action="index.php?common=detail01">
              <a href="index.php?common=detail01">Detail map</a>
              <button name="monster_battle">Battle one</button>
            </form>
        """.trimIndent()
        service.findMaps(account.id, "battle_map")

        assertEquals(
            true,
            capabilityObservationService.observeAuthenticatedDetail(
                account.id,
                "battle_map",
                "common",
                "detail01",
                """
                    <form action="index.php?common=detail01">
                      <button name="monster_battle_10">Battle three</button>
                    </form>
                """.trimIndent(),
            ),
        )
        assertTrue(requireNotNull(queryRepository.findStateForExecution(account.id, "battle_map", "detail01")).supportsThreeBattles)

        assertNull(
            capabilityObservationService.observeAuthenticatedDetail(
                account.id, "battle_map", "common", "detail01",
                "<form action='index.php?common=other'><button name='monster_battle_10'>Unrelated</button></form>",
            ),
        )
        assertTrue(requireNotNull(queryRepository.findStateForExecution(account.id, "battle_map", "detail01")).supportsThreeBattles)
    }

    @Test
    fun unknownCategoryRefreshPreservesCapabilityAndAuthoritativeSingleFormClearsIt() {
        val account = savedAccount("battle-map-tristate-capability")
        gateway.defaultBody = """
            <form action="index.php?common=tri01">
              <a href="index.php?common=tri01">Tri-state map</a>
              <button name="monster_battle_10">Battle three</button>
            </form>
        """.trimIndent()
        assertTrue(service.findMaps(account.id, "battle_map").single().supportsThreeBattles)

        gateway.defaultBody = "<a href='index.php?common=tri01'>Tri-state map</a>"
        assertTrue(service.findMaps(account.id, "battle_map").single().supportsThreeBattles)
        assertTrue(requireNotNull(queryRepository.findStateForExecution(account.id, "battle_map", "tri01")).supportsThreeBattles)

        gateway.defaultBody = """
            <form action="index.php?common=tri01">
              <a href="index.php?common=tri01">Tri-state map</a>
              <button name="monster_battle">Battle one</button>
            </form>
        """.trimIndent()
        assertFalse(service.findMaps(account.id, "battle_map").single().supportsThreeBattles)

        val newAccount = savedAccount("battle-map-tristate-unknown")
        gateway.defaultBody = "<a href='index.php?common=unknown01'>Unknown map</a>"
        assertFalse(service.findMaps(newAccount.id, "battle_map").single().supportsThreeBattles)
        assertFalse(requireNotNull(queryRepository.findStateForExecution(newAccount.id, "battle_map", "unknown01")).supportsThreeBattles)
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
    fun keepsPreviouslyObservedResolvedMapsInTheCatalogButDisablesThem() {
        val firstAccount = savedAccount("battle-map-state-first")
        val secondAccount = savedAccount("battle-map-state-second")
        gateway.defaultBody = directAdventureHtml(sharedCount = 3, includeStale = true)
        service.findMaps(firstAccount.id, ADVENTURE)
        gateway.defaultBody = directAdventureHtml(sharedCount = 7, includeStale = false)
        service.findMaps(secondAccount.id, ADVENTURE)

        gateway.defaultBody = directAdventureHtml(sharedCount = 1, includeStale = false)
        val refreshed = service.findMaps(firstAccount.id, ADVENTURE)

        assertEquals(listOf("shared01", "stale01"), refreshed.map { it.mapCode })
        assertTrue(refreshed.single { it.mapCode == "shared01" }.enabled)
        assertFalse(refreshed.single { it.mapCode == "stale01" }.enabled)
        val staleMap = assertNotNull(queryRepository.findMapByCategoryIdAndMapCode(ADVENTURE, "stale01"))
        val staleState = assertNotNull(queryRepository.findStateByAccountIdAndMapId(firstAccount.id, staleMap.id))
        assertFalse(staleState.visible)
        assertEquals(BattleMapKeyMode.LIMITED, staleState.keyMode)
        assertEquals(10, staleState.keyCount)
        assertNotNull(queryRepository.findMapByCategoryIdAndMapCode(ADVENTURE, "stale01"))
        val sharedMap = assertNotNull(queryRepository.findMapByCategoryIdAndMapCode(ADVENTURE, "shared01"))
        assertEquals(1, queryRepository.findStateByAccountIdAndMapId(firstAccount.id, sharedMap.id)?.availableCount)
        assertEquals(7, queryRepository.findStateByAccountIdAndMapId(secondAccount.id, sharedMap.id)?.availableCount)
        assertTrue(assertNotNull(queryRepository.findStateByAccountIdAndMapId(secondAccount.id, sharedMap.id)).visible)

        gateway.defaultBody = directAdventureHtml(sharedCount = 7, includeStale = false)
        assertEquals(listOf("shared01"), service.findMaps(secondAccount.id, ADVENTURE).map { it.mapCode })
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
    fun manualMapListDoesNotDetectOrCreateCaptchaChallenges() {
        val account = savedAccount("battle-map-manual-captcha")
        gateway.defaultBody = directAdventureHtml(sharedCount = 3, includeStale = false)
        Mockito.doReturn(
            CaptchaChallengeResponse(
                id = 92L,
                accountId = account.id,
                status = "PENDING",
                prompt = "captcha",
                imageUrl = null,
                sourceUrl = "https://example.test/captcha",
                preparationVersion = 0,
                createdAt = NOW.toString(),
                answeredAt = null,
            ),
        ).`when`(captchaService).detectAndRecord(
            anyAccount(),
            anyStringValue(),
            anyStringValue(),
        )

        val maps = service.findMaps(account.id, ADVENTURE)

        assertEquals(listOf("shared01"), maps.map { it.mapCode })
        Mockito.verifyNoInteractions(captchaService)
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
    fun authenticatedAdventureRefreshPreservesExpiredSessionSignalForRecovery() {
        val account = savedAccount("battle-map-expired-session")
        gateway.defaultBody = """
            <html><body>
              <form action="index.php" method="post">
                <input type="text" name="id">
                <input type="password" name="pass">
                <input type="submit" name="Login" value="login">
              </form>
            </body></html>
        """.trimIndent()

        val error = assertFailsWith<AdventureMapRefreshException.Fatal> {
            service.refreshAdventureMaps(account.id)
        }

        val api = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<ApiException>().first()
        assertEquals(ErrorCode.HOF_SESSION_EXPIRED, api.errorCode)
    }

    @Test
    fun authenticatedAdventureRefreshDoesNotDetectOrCreateCaptchaChallenges() {
        val account = savedAccount("battle-map-captcha")
        gateway.defaultBody = directAdventureHtml(sharedCount = 3, includeStale = false)
        Mockito.doReturn(
            CaptchaChallengeResponse(
                id = 91L,
                accountId = account.id,
                status = "PENDING",
                prompt = "captcha",
                imageUrl = null,
                sourceUrl = "https://example.test/captcha",
                preparationVersion = 0,
                createdAt = NOW.toString(),
                answeredAt = null,
            ),
        ).`when`(captchaService).detectAndRecord(
            anyAccount(),
            anyStringValue(),
            anyStringValue(),
        )

        val maps = service.refreshAdventureMaps(account.id)

        assertEquals(listOf("shared01"), maps.map { it.mapCode })
        Mockito.verifyNoInteractions(captchaService)
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

    @Test
    fun currentlyObservedRaidMapsDoNotFallBackToAStaleCatalogEntry() {
        val account = savedAccount("battle-map-current-raid")
        gateway.defaultBody = """<a href="index.php?raid_common=RaidGoblin">고블린 전투 마차</a>"""

        assertEquals(listOf("RaidGoblin"), service.findMaps(account.id, "raid").map { it.mapCode })

        gateway.defaultBody = "<html><body>현재 열린 레이드가 없습니다.</body></html>"
        assertTrue(service.findCurrentlyObservedMaps(account.id, "raid").isEmpty())
        assertEquals(listOf("RaidGoblin"), service.findMaps(account.id, "raid").map { it.mapCode })
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
              ${if (includeStale) "<p><a href=\"index.php?sp_common=stale01\">Shared- 사라질 맵 ( x10 )</a> 2 가능</p>" else ""}
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

        @Bean
        fun accountHofGateway(gateway: FakeHofGateway, timeProvider: TimeProvider): AccountHofGateway =
            AccountHofGateway(gateway, Mockito.mock(HofStatusSnapshotService::class.java), timeProvider)

        @Bean
        fun loginStateParser(): LoginStateParser = LoginStateParser()

        @Bean
        fun captchaService(): CaptchaService = Mockito.mock(CaptchaService::class.java)
    }

    class FakeHofGateway : HofGateway {
        val requests = mutableListOf<HofRequest>()
        val cookies = mutableListOf<Map<String, String>>()
        val responsesByUrl = mutableMapOf<String, HofHttpResponse>()
        var defaultBody: String = ""
        var failure: Exception? = null

        override fun execute(
            accountId: Long,
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

    private fun anyAccount(): HofAccountEntity =
        Mockito.any(HofAccountEntity::class.java)
            ?: HofAccountEntity(999L, "matcher", "encrypted", NOW)

    private fun anyStringValue(): String = Mockito.anyString() ?: ""
}
