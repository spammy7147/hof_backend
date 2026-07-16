package app.spammy.hof.automation.controller

import app.spammy.hof.auth.service.JwtTokenService
import app.spammy.hof.automation.dto.TypedAutomationAggregateResponse
import app.spammy.hof.automation.dto.BattleMapDailyProgressResponse
import app.spammy.hof.automation.dto.TypedAutomationEntryResponse
import app.spammy.hof.automation.dto.TypedAutomationRuntimeResponse
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.service.UnifiedAutomationService
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
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import(UnifiedAutomationApiSecurityTest.Config::class)
class UnifiedAutomationApiSecurityTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jwtTokenService: JwtTokenService,
    @Autowired @Qualifier("securityUnifiedAutomationService") private val service: UnifiedAutomationService,
) {
    @BeforeEach
    fun resetService() = Mockito.reset(service)

    @Test
    fun unauthenticatedTypedSettingsRequestsAreRejectedBeforeServiceInvocation() {
        mockMvc.perform(get("/api/automation/unified"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"))
        mockMvc.perform(
            post("/api/automation/unified/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"QUEST"}"""),
        ).andExpect(status().isUnauthorized)
        Mockito.verifyNoInteractions(service)
    }

    @Test
    fun bearerSubjectIsTheOnlyAccountIdentityPassedToTypedApi() {
        val response = TypedAutomationAggregateResponse(
            emptyList(), TypedAutomationRuntimeResponse(TypedAutomationLifecycle.STOPPED),
        )
        Mockito.`when`(service.getTyped(42L)).thenReturn(response)

        mockMvc.perform(
            get("/api/automation/unified")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtTokenService.issue(42L).value}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.entries").isEmpty)
            .andExpect(jsonPath("$.runtime.lifecycle").value("STOPPED"))

        Mockito.verify(service).getTyped(42L)
    }

    @Test
    fun typedAggregateSerializesBattleProgressWithExactFieldNamesOnlyOnBattleEntry() {
        val response = TypedAutomationAggregateResponse(
            entries = listOf(
                TypedAutomationEntryResponse(
                    id = 1L,
                    type = AutomationType.QUEST,
                    enabled = false,
                    priority = 0,
                    ready = true,
                    warnings = emptyList(),
                ),
                TypedAutomationEntryResponse(
                    id = 2L,
                    type = AutomationType.BATTLE_MAP,
                    enabled = true,
                    priority = 1,
                    ready = true,
                    warnings = emptyList(),
                    battleMapProgress = listOf(BattleMapDailyProgressResponse("battle_map", "gb0", 6)),
                ),
            ),
            runtime = TypedAutomationRuntimeResponse(TypedAutomationLifecycle.STOPPED),
        )
        Mockito.`when`(service.getTyped(42L)).thenReturn(response)

        mockMvc.perform(
            get("/api/automation/unified")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${jwtTokenService.issue(42L).value}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.entries[0].battleMapProgress").isEmpty)
            .andExpect(jsonPath("$.entries[1].battleMapProgress[0].categoryId").value("battle_map"))
            .andExpect(jsonPath("$.entries[1].battleMapProgress[0].mapCode").value("gb0"))
            .andExpect(jsonPath("$.entries[1].battleMapProgress[0].successfulRuns").value(6))

        Mockito.verify(service).getTyped(42L)
    }

    @Test
    fun authenticatedNegativeTypedOrdersAreRejectedBeforeServiceInvocation() {
        val token = jwtTokenService.issue(42L).value
        val invalidRequests = listOf(
            "/api/automation/unified/quest" to
                """{"enabled":false,"quests":[{"questCode":"Q-1","enabled":true,"sourceOrder":-1,"maps":[]}]}""",
            "/api/automation/unified/quest" to
                """{"enabled":false,"quests":[{"questCode":"Q-1","enabled":true,"sourceOrder":0,"maps":[{"missionKey":"mission","categoryId":"battle_map","mapCode":"gb0","presetMode":"PRIMARY","partyPresetId":null,"executionOrder":-1,"manuallyOverridden":false}]}]}""",
            "/api/automation/unified/battle-maps" to
                """{"enabled":false,"maps":[{"categoryId":"battle_map","mapCode":"gb0","dailyTargetCount":1,"presetMode":"PRIMARY","partyPresetId":null,"executionOrder":-1}]}""",
            "/api/automation/unified/adventure-maps" to
                """{"enabled":false,"maps":[{"categoryId":"adventure_map","mapCode":"Noble101","presetMode":"PRIMARY","partyPresetId":null,"executionOrder":-1}]}""",
        )
        invalidRequests.forEach { (path, body) ->
            mockMvc.perform(
                put(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        Mockito.verifyNoInteractions(service)
    }

    @Test
    fun authenticatedDuplicateTypedOrdersAndOversizedQuestMapsAreRejectedBeforeServiceInvocation() {
        val token = jwtTokenService.issue(42L).value
        fun questMaps(prefix: String, count: Int) = (0 until count).joinToString(",") { index ->
            """{"missionKey":"$prefix-m$index","categoryId":"battle_map","mapCode":"$prefix-map$index","presetMode":"PRIMARY","partyPresetId":null,"executionOrder":$index,"manuallyOverridden":false}"""
        }
        val firstQuestMaps = questMaps("a", 51)
        val secondQuestMaps = questMaps("b", 50)
        val invalidRequests = listOf(
            "/api/automation/unified/quest" to
                """{"enabled":false,"quests":[{"questCode":"Q-1","enabled":true,"sourceOrder":0,"maps":[]},{"questCode":"Q-2","enabled":true,"sourceOrder":0,"maps":[]}]}""",
            "/api/automation/unified/quest" to
                """{"enabled":false,"quests":[{"questCode":"Q-1","enabled":true,"sourceOrder":0,"maps":[{"missionKey":"m1","categoryId":"battle_map","mapCode":"a","presetMode":"PRIMARY","partyPresetId":null,"executionOrder":0,"manuallyOverridden":false},{"missionKey":"m2","categoryId":"battle_map","mapCode":"b","presetMode":"PRIMARY","partyPresetId":null,"executionOrder":0,"manuallyOverridden":false}]}]}""",
            "/api/automation/unified/battle-maps" to
                """{"enabled":false,"maps":[{"categoryId":"battle_map","mapCode":"a","dailyTargetCount":1,"presetMode":"PRIMARY","partyPresetId":null,"executionOrder":0},{"categoryId":"battle_map","mapCode":"b","dailyTargetCount":1,"presetMode":"PRIMARY","partyPresetId":null,"executionOrder":0}]}""",
            "/api/automation/unified/adventure-maps" to
                """{"enabled":false,"maps":[{"categoryId":"adventure_map","mapCode":"a","presetMode":"PRIMARY","partyPresetId":null,"executionOrder":0},{"categoryId":"adventure_map","mapCode":"b","presetMode":"PRIMARY","partyPresetId":null,"executionOrder":0}]}""",
            "/api/automation/unified/quest" to
                """{"enabled":false,"quests":[{"questCode":"Q-1","enabled":true,"sourceOrder":0,"maps":[$firstQuestMaps]},{"questCode":"Q-2","enabled":true,"sourceOrder":1,"maps":[$secondQuestMaps]}]}""",
        )
        invalidRequests.forEach { (path, body) ->
            mockMvc.perform(
                put(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
        Mockito.verifyNoInteractions(service)
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean("securityUnifiedAutomationService")
        @Primary
        fun service(): UnifiedAutomationService = Mockito.mock(UnifiedAutomationService::class.java)
    }
}
