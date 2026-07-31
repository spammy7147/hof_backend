package app.spammy.hof.quest.controller

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.error.GlobalExceptionHandler
import app.spammy.hof.common.security.CurrentAccountIdArgumentResolver
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.service.QuestGatewayService
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class QuestControllerTest {
    private val gateway = Mockito.mock(QuestGatewayService::class.java)
    private val accountService = Mockito.mock(HofAccountService::class.java)
    private val controller = QuestController(
        recovery = HofSessionRecoveryService(accountService),
        gateway = gateway,
    )
    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(CurrentAccountIdArgumentResolver())
        .setControllerAdvice(GlobalExceptionHandler(TimeProvider { FIXED_NOW }))
        .build()

    @AfterTest
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun authenticatedGetUsesCurrentAccountAndReturnsStructuredJson() {
        Mockito.`when`(gateway.load(42L)).thenReturn(listOf(snapshot()))
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(jwt("42"), null)

        mockMvc.perform(get("/api/quests"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$[0].questKey").value("0571"))
            .andExpect(jsonPath("$[0].section").value("ACTIVE"))
            .andExpect(jsonPath("$[0].missions[0].type").value("MONSTER_KILL"))
            .andExpect(jsonPath("$[0].missions[0].progress.current").value(12))
            .andExpect(jsonPath("$[0].missions[0].progress.required").value(30))
            .andExpect(jsonPath("$[0].rewards[0]").value("아이템( Red Potion ) x2"))
        Mockito.verify(gateway).load(42L)
        Mockito.verifyNoInteractions(accountService)
    }

    @Test
    fun wrapsGatewayCallInSessionRecovery() {
        Mockito.`when`(gateway.load(42L))
            .thenThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
            .thenReturn(listOf(snapshot()))

        val response = controller.findAll(42L)

        assertEquals(QuestState.ACTIVE, response.single().state)
        Mockito.verify(accountService).reauthenticate(42L)
        Mockito.verify(gateway, Mockito.times(2)).load(42L)
    }

    @Test
    fun unauthenticatedGetIsRejectedByCurrentAccountResolver() {
        SecurityContextHolder.clearContext()

        mockMvc.perform(get("/api/quests"))
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"))
            .andExpect(jsonPath("$.message").value("로그인 정보가 올바르지 않습니다."))
        Mockito.verifyNoInteractions(gateway)
    }

    @Test
    fun manualAcceptAndClaimUsePostAndCurrentAccount() {
        Mockito.`when`(gateway.accept(42L, "351")).thenReturn(listOf(snapshot()))
        Mockito.`when`(gateway.claim(42L, "R610")).thenReturn(listOf(snapshot()))
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(jwt("42"), null)

        mockMvc.perform(post("/api/quests/351/accept")).andExpect(status().isOk)
        mockMvc.perform(post("/api/quests/R610/claim")).andExpect(status().isOk)

        Mockito.verify(gateway).accept(42L, "351")
        Mockito.verify(gateway).claim(42L, "R610")
    }

    private fun jwt(subject: String): Jwt = Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject(subject)
        .issuedAt(FIXED_NOW)
        .expiresAt(FIXED_NOW.plusSeconds(3600))
        .build()

    private fun snapshot() = QuestSnapshot(
        questKey = "0571",
        name = "저택 서관 열쇠 수집",
        state = QuestState.ACTIVE,
        section = QuestSection.ACTIVE,
        sourceOrder = 0,
        missions = listOf(
            QuestMission(
                key = "0571:0",
                type = QuestMissionType.MONSTER_KILL,
                target = "Killer Maid",
                progress = QuestProgress(12, 30),
                completable = false,
            ),
        ),
        actionNo = null,
        rewards = listOf("아이템( Red Potion ) x2"),
    )

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-07-15T00:00:00Z")
    }
}
