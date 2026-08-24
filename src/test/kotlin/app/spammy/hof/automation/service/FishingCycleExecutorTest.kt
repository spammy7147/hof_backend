package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.AccountHofMutationFence
import app.spammy.hof.town.common.service.ResolvedTownLocation
import app.spammy.hof.town.common.service.TownActionGuard
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.fishing.parser.FishingPageParser
import app.spammy.hof.town.fishing.service.FishingService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.mockito.Mockito

class FishingCycleExecutorTest {
    @Test
    fun `정상 한 번 낚시는 GET START CATCH 세 요청과 영속 경계를 지킨다`() {
        val accounts = Mockito.mock(AccountQueryRepository::class.java)
        val cookies = Mockito.mock(CookieQueryRepository::class.java)
        val gateway = Mockito.mock(AccountHofGateway::class.java)
        val locations = Mockito.mock(TownLocationResolver::class.java)
        val battleMaps = Mockito.mock(BattleMapService::class.java)
        val town = TownAuthenticatedExecutor(
            accounts,
            cookies,
            HofRequestFactory(),
            gateway,
            LoginStateParser(),
            HofFormParser(),
            HofResultParser(),
            TownActionGuard(),
            AccountHofMutationFence(),
        )
        val fishingService = FishingService(town, locations, FishingPageParser(), battleMaps)
        val executor: FishingCycleExecutor = DefaultFishingCycleExecutor(fishingService)
        Mockito.`when`(accounts.findById(7L)).thenReturn(
            HofAccountEntity(7L, "fisher", "encrypted", Instant.EPOCH),
        )
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(locations.resolve(TownFeatureId.FISHING, null)).thenReturn(
            ResolvedTownLocation(TownFeatureId.FISHING, FISHING_URL),
        )
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(
            response("reset.html", mapOf("phase" to "observed")),
            response("waiting.html", mapOf("phase" to "started")),
            response("caught.html", mapOf("phase" to "caught")),
        )
        val transitions = RecordingFishingCycleTransitions(gateway)
        val observation = fishingService.loadForAutomation(7L)

        val result = executor.executeOneCast(
            FishingCycleCommand(
                accountId = 7L,
                cycleIdentity = "cycle-1",
                startExecutionIdentity = "start-1",
                catchExecutionIdentity = "catch-1",
                observation = observation,
            ),
            transitions,
        )

        assertIs<FishingCycleResult.Completed>(result)
        assertEquals(
            listOf("START_APPLIED_CATCH_PREPARED@2", "CATCH_APPLIED@3"),
            transitions.events,
        )
        val invocations = Mockito.mockingDetails(gateway).invocations
            .map { it.arguments[1] as HofRequest to (it.arguments[2] as Map<*, *>) }
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST, HofHttpMethod.POST), invocations.map { it.first.method })
        assertEquals(listOf(HofRequestOrigin.AUTOMATION, HofRequestOrigin.AUTOMATION, HofRequestOrigin.AUTOMATION), invocations.map { it.first.origin })
        assertEquals("낚시를 시작한다", invocations[1].first.formFields["do"])
        assertEquals("낚는다", invocations[2].first.formFields["do"])
        assertEquals(mapOf("PHPSESSID" to "session"), invocations[0].second)
        assertEquals(mapOf("PHPSESSID" to "session", "phase" to "observed"), invocations[1].second)
        assertEquals(
            mapOf("PHPSESSID" to "session", "phase" to "started"),
            invocations[2].second,
        )
        Mockito.verifyNoInteractions(battleMaps)
    }

    @Test
    fun `START 응답에 CATCH form이 없으면 보충 GET 없이 대기로 끝난다`() {
        val accounts = Mockito.mock(AccountQueryRepository::class.java)
        val cookies = Mockito.mock(CookieQueryRepository::class.java)
        val gateway = Mockito.mock(AccountHofGateway::class.java)
        val locations = Mockito.mock(TownLocationResolver::class.java)
        val battleMaps = Mockito.mock(BattleMapService::class.java)
        val fishingService = FishingService(
            TownAuthenticatedExecutor(
                accounts,
                cookies,
                HofRequestFactory(),
                gateway,
                LoginStateParser(),
                HofFormParser(),
                HofResultParser(),
                TownActionGuard(),
                AccountHofMutationFence(),
            ),
            locations,
            FishingPageParser(),
            battleMaps,
        )
        Mockito.`when`(accounts.findById(7L)).thenReturn(
            HofAccountEntity(7L, "fisher", "encrypted", Instant.EPOCH),
        )
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(locations.resolve(TownFeatureId.FISHING, null)).thenReturn(
            ResolvedTownLocation(TownFeatureId.FISHING, FISHING_URL),
        )
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(
            response("reset.html", mapOf("phase" to "observed")),
            response("waiting-no-catch.html", mapOf("phase" to "started")),
        )
        val events = mutableListOf<String>()

        val result = DefaultFishingCycleExecutor(fishingService).executeOneCast(
            FishingCycleCommand(7L, "cycle-1", "start-1", "catch-1", fishingService.loadForAutomation(7L)),
            object : FishingCycleTransitions {
                override fun startAppliedAndCatchPrepared(
                    command: FishingCycleCommand,
                    start: FishingCycleStepEvidence,
                    catch: FishingCyclePreparedCatch,
                ) = error("CATCH form이 없으므로 CATCH prepared 전이를 만들면 안 됩니다.")

                override fun catchApplied(command: FishingCycleCommand, catch: FishingCycleStepEvidence) =
                    error("CATCH를 제출하면 안 됩니다.")

                override fun waitingForCatch(command: FishingCycleCommand, start: FishingCycleStepEvidence) {
                    events += "WAITING"
                }
            },
        )

        assertIs<FishingCycleResult.WaitingForCatch>(result)
        assertEquals(listOf("WAITING"), events)
        val requests = Mockito.mockingDetails(gateway).invocations.map { it.arguments[1] as HofRequest }
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), requests.map(HofRequest::method))
        assertEquals(listOf(HofRequestOrigin.AUTOMATION, HofRequestOrigin.AUTOMATION), requests.map(HofRequest::origin))
        Mockito.verifyNoInteractions(battleMaps)
    }

    @Test
    fun `다음 판단에서 관측된 CATCH는 같은 GET form으로 추가 조회 없이 제출한다`() {
        val accounts = Mockito.mock(AccountQueryRepository::class.java)
        val cookies = Mockito.mock(CookieQueryRepository::class.java)
        val gateway = Mockito.mock(AccountHofGateway::class.java)
        val locations = Mockito.mock(TownLocationResolver::class.java)
        val battleMaps = Mockito.mock(BattleMapService::class.java)
        val fishingService = FishingService(
            TownAuthenticatedExecutor(
                accounts,
                cookies,
                HofRequestFactory(),
                gateway,
                LoginStateParser(),
                HofFormParser(),
                HofResultParser(),
                TownActionGuard(),
                AccountHofMutationFence(),
            ),
            locations,
            FishingPageParser(),
            battleMaps,
        )
        Mockito.`when`(accounts.findById(7L)).thenReturn(
            HofAccountEntity(7L, "fisher", "encrypted", Instant.EPOCH),
        )
        Mockito.`when`(cookies.findValueMapByAccountId(7L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(locations.resolve(TownFeatureId.FISHING, null)).thenReturn(
            ResolvedTownLocation(TownFeatureId.FISHING, FISHING_URL),
        )
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(
            response("waiting.html", mapOf("phase" to "waiting")),
            response("caught.html", mapOf("phase" to "caught")),
        )

        val observation = fishingService.loadForAutomation(7L)
        fishingService.executeObservedActionForAutomation(7L, app.spammy.hof.town.fishing.model.FishingAction.CATCH, observation)

        val invocations = Mockito.mockingDetails(gateway).invocations
            .map { it.arguments[1] as HofRequest to (it.arguments[2] as Map<*, *>) }
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), invocations.map { it.first.method })
        assertEquals("낚는다", invocations[1].first.formFields["do"])
        assertEquals("waiting", invocations[1].second["phase"])
        Mockito.verifyNoInteractions(battleMaps)
    }

    private class RecordingFishingCycleTransitions(
        private val gateway: AccountHofGateway,
    ) : FishingCycleTransitions {
        val events = mutableListOf<String>()

        override fun startAppliedAndCatchPrepared(
            command: FishingCycleCommand,
            start: FishingCycleStepEvidence,
            catch: FishingCyclePreparedCatch,
        ) {
            assertEquals("start-1", start.executionIdentity)
            assertEquals("catch-1", catch.executionIdentity)
            events += "START_APPLIED_CATCH_PREPARED@${Mockito.mockingDetails(gateway).invocations.size}"
        }

        override fun catchApplied(
            command: FishingCycleCommand,
            catch: FishingCycleStepEvidence,
        ) {
            assertEquals("catch-1", catch.executionIdentity)
            events += "CATCH_APPLIED@${Mockito.mockingDetails(gateway).invocations.size}"
        }

        override fun waitingForCatch(
            command: FishingCycleCommand,
            start: FishingCycleStepEvidence,
        ) = error("정상 응답은 잡기 대기로 끝나면 안 됩니다.")
    }

    private fun response(name: String, setCookies: Map<String, String>) = HofHttpResponse(
        statusCode = 200,
        finalUrl = FISHING_URL,
        body = fixture(name),
        setCookies = setCookies,
    )

    private fun fixture(name: String): String = checkNotNull(
        javaClass.getResource("/fixtures/town/fishing/$name"),
    ).readText()

    private fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java)
        ?: HofRequest(HofHttpMethod.GET, FISHING_URL)

    private fun anyCookies(): Map<String, String> = Mockito.anyMap<String, String>() ?: emptyMap()

    private companion object {
        const val FISHING_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=fishing"
    }
}
