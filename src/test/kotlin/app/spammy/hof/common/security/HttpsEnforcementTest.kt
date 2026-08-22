package app.spammy.hof.common.security

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "hof.auth.require-https=true",
        "server.forward-headers-strategy=framework",
    ],
)
@AutoConfigureMockMvc
class HttpsEnforcementTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun rejectsInsecureRequestsWhenProductionEnforcementIsEnabled() {
        mockMvc.perform(get("/api/status").secure(false).header("Host", "api.example.com"))
            .andExpect(status().isUpgradeRequired)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("HTTPS_REQUIRED"))
    }

    @Test
    fun acceptsSecureRequestsWhenProductionEnforcementIsEnabled() {
        mockMvc.perform(get("/api/status").secure(true))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun acceptsTrustedForwardedHttpsWhenFrameworkHeaderHandlingIsEnabled() {
        mockMvc.perform(
            get("/api/status")
                .secure(false)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "api.example.com"),
        )
            .andExpect(status().isUnauthorized)
    }
}
