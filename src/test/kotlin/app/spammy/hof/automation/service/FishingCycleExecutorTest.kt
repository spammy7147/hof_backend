package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.auth.service.AccountExecutionSubmissionGate
import app.spammy.hof.battle.dto.BattleMapResponse
import app.spammy.hof.battle.model.BattleMapKeyMode
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
import kotlin.test.assertFailsWith
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
        val executor: FishingCycleExecutor = DefaultFishingCycleExecutor(fishingService, allowingSubmissionGate())
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
        assertEquals("낚시를 시작한다", invocations[1].first.formFields["FStart"])
        assertEquals("낚는다", invocations[2].first.formFields["FCatch"])
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

        val result = DefaultFishingCycleExecutor(fishingService, allowingSubmissionGate()).executeOneCast(
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

                override fun battleRequired(command: FishingCycleCommand, start: FishingCycleStepEvidence) =
                    error("일반 대기 응답을 전투로 처리하면 안 됩니다.")
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
    fun `START 직접 응답에 방해 전투가 있으면 CATCH 없이 전투 전이로 넘긴다`() {
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
            response("monster.html", mapOf("phase" to "battle")),
        )
        val events = mutableListOf<String>()

        val result = DefaultFishingCycleExecutor(fishingService, allowingSubmissionGate()).executeOneCast(
            FishingCycleCommand(7L, "cycle-1", "start-1", "catch-1", fishingService.loadForAutomation(7L)),
            object : FishingCycleTransitions {
                override fun startAppliedAndCatchPrepared(
                    command: FishingCycleCommand,
                    start: FishingCycleStepEvidence,
                    catch: FishingCyclePreparedCatch,
                ) = error("전투 응답 뒤 CATCH를 준비하면 안 됩니다.")

                override fun catchApplied(command: FishingCycleCommand, catch: FishingCycleStepEvidence) =
                    error("전투 응답 뒤 CATCH를 제출하면 안 됩니다.")

                override fun waitingForCatch(command: FishingCycleCommand, start: FishingCycleStepEvidence) =
                    error("확인된 전투를 일반 대기로 처리하면 안 됩니다.")

                override fun battleRequired(command: FishingCycleCommand, start: FishingCycleStepEvidence) {
                    events += "BATTLE:${start.response.battleTarget?.mapCode}"
                }
            },
        )

        assertIs<FishingCycleResult.BattleRequired>(result)
        assertEquals(listOf("BATTLE:fishing_12"), events)
        val requests = Mockito.mockingDetails(gateway).invocations.map { it.arguments[1] as HofRequest }
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), requests.map(HofRequest::method))
        Mockito.verifyNoInteractions(battleMaps)
    }

    @Test
    fun `START 직접 응답에 전투 대상 링크가 없으면 현재 낚시 전투맵을 연결한다`() {
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
        val blockedWithoutTarget = fixture("monster.html")
            .replace("<a href=\"?menu=hunt&amp;common=fishing_12\">전투</a>", "")
        Mockito.`when`(gateway.execute(Mockito.eq(7L), anyRequest(), anyCookies())).thenReturn(
            response("reset.html", mapOf("phase" to "observed")),
            HofHttpResponse(200, FISHING_URL, blockedWithoutTarget, mapOf("phase" to "battle")),
        )
        Mockito.`when`(
            battleMaps.findCurrentlyObservedMaps(7L, "battle_map", HofRequestOrigin.AUTOMATION),
        ).thenReturn(listOf(observedFishingMap("Fish02", "Fishing- 피라냐")))
        val events = mutableListOf<String>()

        val result = DefaultFishingCycleExecutor(fishingService, allowingSubmissionGate()).executeOneCast(
            FishingCycleCommand(7L, "cycle-1", "start-1", "catch-1", fishingService.loadForAutomation(7L)),
            object : FishingCycleTransitions {
                override fun startAppliedAndCatchPrepared(
                    command: FishingCycleCommand,
                    start: FishingCycleStepEvidence,
                    catch: FishingCyclePreparedCatch,
                ) = error("전투 응답 뒤 CATCH를 준비하면 안 됩니다.")

                override fun catchApplied(command: FishingCycleCommand, catch: FishingCycleStepEvidence) =
                    error("전투 응답 뒤 CATCH를 제출하면 안 됩니다.")

                override fun waitingForCatch(command: FishingCycleCommand, start: FishingCycleStepEvidence) =
                    error("확인된 전투를 일반 대기로 처리하면 안 됩니다.")

                override fun battleRequired(command: FishingCycleCommand, start: FishingCycleStepEvidence) {
                    events += "BATTLE:${start.response.battleTarget?.mapCode}"
                }
            },
        )

        assertIs<FishingCycleResult.BattleRequired>(result)
        assertEquals(listOf("BATTLE:Fish02"), events)
        val requests = Mockito.mockingDetails(gateway).invocations.map { it.arguments[1] as HofRequest }
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), requests.map(HofRequest::method))
        Mockito.verify(battleMaps).findCurrentlyObservedMaps(7L, "battle_map", HofRequestOrigin.AUTOMATION)
    }

    @Test
    fun `logout after START prevents the newly prepared CATCH from reaching HOF`() {
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
        val gate = Mockito.mock(AccountExecutionSubmissionGate::class.java)
        var authorizationChecks = 0
        Mockito.doAnswer { invocation ->
            authorizationChecks += 1
            if (authorizationChecks == 1) {
                (invocation.arguments[1] as Runnable).run()
                true
            } else {
                false
            }
        }.`when`(gate).executeIfAuthorized(Mockito.eq(7L), anyRunnable())
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
        )
        val observation = fishingService.loadForAutomation(7L)

        assertFailsWith<FishingSubmissionAuthorizationCancelledException> {
            DefaultFishingCycleExecutor(fishingService, gate).executeOneCast(
                FishingCycleCommand(7L, "cycle-1", "start-1", "catch-1", observation),
                RecordingFishingCycleTransitions(gateway),
            )
        }

        val requests = Mockito.mockingDetails(gateway).invocations.map { it.arguments[1] as HofRequest }
        assertEquals(listOf(HofHttpMethod.GET, HofHttpMethod.POST), requests.map(HofRequest::method))
        assertEquals(2, authorizationChecks)
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
        assertEquals("낚는다", invocations[1].first.formFields["FCatch"])
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

        override fun battleRequired(
            command: FishingCycleCommand,
            start: FishingCycleStepEvidence,
        ) = error("정상 응답은 전투로 전환하면 안 됩니다.")
    }

    private fun allowingSubmissionGate(): AccountExecutionSubmissionGate =
        Mockito.mock(AccountExecutionSubmissionGate::class.java).also { gate ->
            Mockito.doAnswer { invocation ->
                (invocation.arguments[1] as Runnable).run()
                true
            }.`when`(gate).executeIfAuthorized(Mockito.anyLong(), anyRunnable())
        }

    private fun anyRunnable(): Runnable = Mockito.any(Runnable::class.java) ?: Runnable {}

    private fun response(name: String, setCookies: Map<String, String>) = HofHttpResponse(
        statusCode = 200,
        finalUrl = FISHING_URL,
        body = fixture(name),
        setCookies = setCookies,
    )

    private fun observedFishingMap(mapCode: String, name: String) = BattleMapResponse(
        categoryId = "battle_map",
        mapCode = mapCode,
        name = name,
        groupName = "낚시",
        groupOrder = 0,
        mapOrder = 0,
        recommendedLevel = null,
        availableCount = null,
        attemptCount = null,
        winCount = null,
        cooldownRemainingText = null,
        cooldownRemainingSeconds = null,
        keyMode = BattleMapKeyMode.UNKNOWN,
        keyCount = null,
        requiredTime = null,
        enabled = true,
        resolved = true,
        iconUrl = null,
        rawHref = "?menu=hunt&common=$mapCode",
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
