package app.spammy.hof.town.fishing.controller

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.error.GlobalExceptionHandler
import app.spammy.hof.common.security.CurrentAccountIdArgumentResolver
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.town.fishing.dto.FishingResponse
import app.spammy.hof.town.fishing.dto.FishingExchangeResponse
import app.spammy.hof.town.fishing.model.FishingAction
import app.spammy.hof.town.fishing.model.FishingOutcome
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.fishing.service.FishingService
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import org.mockito.Mockito
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class FishingControllerTest {
    private val service = Mockito.mock(FishingService::class.java)
    private val accounts = Mockito.mock(HofAccountService::class.java)
    private val controller = FishingController(service, HofSessionRecoveryService(accounts))
    private val mvc = MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(CurrentAccountIdArgumentResolver())
        .setControllerAdvice(GlobalExceptionHandler(TimeProvider { NOW }))
        .build()

    @AfterTest
    fun clearSecurityContext() = SecurityContextHolder.clearContext()

    @Test
    fun `낚시 상태를 typed DTO로 반환한다`() {
        authenticate()
        Mockito.`when`(service.load(42L)).thenReturn(response(FishingPrimaryAction.START))

        mvc.perform(get("/api/town/fishing"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.primaryAction").value("START"))
            .andExpect(jsonPath("$.blockedByBattle").value(false))
            .andExpect(jsonPath("$.availableActions[0]").value("START"))
        Mockito.verify(service).load(42L)
    }

    @Test
    fun `semantic 낚기 action만 service에 전달한다`() {
        authenticate()
        Mockito.`when`(service.act(42L, FishingAction.CATCH)).thenReturn(response(FishingPrimaryAction.START))

        mvc.perform(post("/api/town/fishing/actions/CATCH"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.lastOutcome").value("ESCAPED"))
        Mockito.verify(service).act(42L, FishingAction.CATCH)
    }

    @Test
    fun `낚시 교환 분류 id를 별도 조회 action으로 전달한다`() {
        authenticate()
        Mockito.`when`(service.loadExchangeCategory(42L, "type_create:armor"))
            .thenReturn(FishingExchangeResponse(emptyList(), null, emptyList(), null))

        mvc.perform(get("/api/town/fishing-exchange").param("categoryCandidateId", "type_create:armor"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.categories").isArray)
            .andExpect(jsonPath("$.items").isArray)
        Mockito.verify(service).loadExchangeCategory(42L, "type_create:armor")
    }

    private fun authenticate() {
        val jwt = Jwt.withTokenValue("token").header("alg", "none").subject("42")
            .issuedAt(NOW).expiresAt(NOW.plusSeconds(3600)).build()
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(jwt, null)
    }

    private fun response(primary: FishingPrimaryAction) = FishingResponse(
        notice = null,
        remainingCasts = 17,
        waterStatus = "수면이 아름답게 빛나고있다.",
        baitCount = 0,
        shiningBaitCount = 0,
        escapeSeconds = null,
        combo = null,
        locationName = "일반 낚시터",
        primaryAction = primary,
        availableActions = setOf(FishingAction.START),
        lastOutcome = FishingOutcome.ESCAPED,
        blockedByBattle = false,
        battleTarget = null,
        catches = emptyList(),
        result = null,
    )

    private companion object { val NOW: Instant = Instant.parse("2026-07-31T00:00:00Z") }
}
