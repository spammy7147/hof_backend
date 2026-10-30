package app.spammy.hof.automation.controller

import app.spammy.hof.auth.service.JwtTokenService
import app.spammy.hof.automation.dto.*
import app.spammy.hof.automation.dto.SaveAutomationPresetRequest
import app.spammy.hof.automation.service.AutomationPresetService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AutomationPresetApiSecurityTest {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var tokens: JwtTokenService
    @MockitoBean private lateinit var presets: AutomationPresetService

    @Test
    fun `전체 설정 저장 응답은 확인한 저장 버전과 구성 전체를 반환한다`() {
        val configuration = AutomationPresetSettings(emptyList())
        Mockito.`when`(presets.save(42, SaveAutomationPresetRequest("일상", configuration), null))
            .thenReturn(AutomationPresetDetailResponse(3, "일상", "4", configuration))
        mvc.perform(post("/api/automation/unified/presets").header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(42).value}")
            .contentType(MediaType.APPLICATION_JSON).content("""{"name":"일상","settings":{"entries":[]}}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.id").value(3))
            .andExpect(jsonPath("$.revision").value("4")).andExpect(jsonPath("$.settings.entries").isEmpty)
    }

    @Test
    fun `프리셋의 모든 API는 인증을 요구한다`() {
        listOf(get("/api/automation/unified/presets"), post("/api/automation/unified/presets"),
            put("/api/automation/unified/presets/3"), delete("/api/automation/unified/presets/3"),
            post("/api/automation/unified/presets/3/apply")).forEach {
            mvc.perform(it.contentType(MediaType.APPLICATION_JSON).content("""{"name":"일상"}"""))
                .andExpect(status().isUnauthorized)
        }
        Mockito.verifyNoInteractions(presets)
    }

    @Test
    fun `프리셋 저장은 토큰의 계정만 사용하고 이름을 검증한다`() {
        Mockito.`when`(presets.save(42, SaveAutomationPresetRequest("일상"), null))
            .thenReturn(AutomationPresetDetailResponse(3, "일상", "0", AutomationPresetSettings(List(4) {
                AutomationPresetEntry(0, app.spammy.hof.automation.entity.AutomationType.BATTLE_MAP, false)
            })))
        val authorization = "Bearer ${tokens.issue(42).value}"
        mvc.perform(post("/api/automation/unified/presets?accountId=999")
            .header(HttpHeaders.AUTHORIZATION, authorization).contentType(MediaType.APPLICATION_JSON).content("""{"name":"일상"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.name").value("일상"))
            .andExpect(jsonPath("$.entryCount").value(4))
        Mockito.verify(presets).save(42, SaveAutomationPresetRequest("일상"), null)
        mvc.perform(post("/api/automation/unified/presets").header(HttpHeaders.AUTHORIZATION, authorization)
            .contentType(MediaType.APPLICATION_JSON).content("""{"name":" "}"""))
            .andExpect(status().isBadRequest)
        Mockito.verifyNoMoreInteractions(presets)
    }
}
