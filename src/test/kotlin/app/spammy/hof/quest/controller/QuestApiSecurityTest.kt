package app.spammy.hof.quest.controller

import app.spammy.hof.auth.service.JwtTokenService
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.service.QuestGatewayService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(QuestApiSecurityTest.GatewayTestConfig::class)
class QuestApiSecurityTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jwtTokenService: JwtTokenService,
    @Autowired @Qualifier("testQuestGateway") private val gateway: QuestGatewayService,
) {
    @BeforeEach
    fun resetGateway() {
        Mockito.reset(gateway)
    }

    @Test
    fun realSecurityFilterRejectsUnauthenticatedQuestRequest() {
        mockMvc.perform(get("/api/quests"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"))
            .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))

        Mockito.verifyNoInteractions(gateway)
    }

    @Test
    fun realBearerJwtSubjectBecomesCurrentAccountId() {
        Mockito.`when`(gateway.load(42L)).thenReturn(listOf(snapshot()))
        val accessToken = jwtTokenService.issue(42L).value

        mockMvc.perform(
            get("/api/quests")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].questId").value("0571"))
            .andExpect(jsonPath("$[0].missions[0].type").value("MONSTER_KILL"))

        Mockito.verify(gateway).load(42L)
    }

    private fun snapshot() = QuestSnapshot(
        questId = "0571",
        name = "저택 서관 열쇠 수집",
        state = QuestState.ACTIVE,
        section = QuestSection.ACTIVE,
        sourceOrder = 0,
        missions = listOf(
            QuestMission(
                key = "0571:monster_kill:fixture",
                type = QuestMissionType.MONSTER_KILL,
                target = "Killer Maid",
                progress = QuestProgress(12, 30),
                completable = false,
            ),
        ),
        actionNo = null,
    )

    @TestConfiguration
    class GatewayTestConfig {
        @Bean
        @Primary
        fun testQuestGateway(): QuestGatewayService = Mockito.mock(QuestGatewayService::class.java)
    }
}
