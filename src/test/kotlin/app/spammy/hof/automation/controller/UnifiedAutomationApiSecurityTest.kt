package app.spammy.hof.automation.controller

import app.spammy.hof.auth.service.JwtTokenService
import app.spammy.hof.automation.dto.TypedAutomationAggregateResponse
import app.spammy.hof.automation.dto.TypedAutomationRuntimeResponse
import app.spammy.hof.automation.entity.TypedAutomationLifecycle
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

    @TestConfiguration(proxyBeanMethods = false)
    class Config {
        @Bean("securityUnifiedAutomationService")
        @Primary
        fun service(): UnifiedAutomationService = Mockito.mock(UnifiedAutomationService::class.java)
    }
}
