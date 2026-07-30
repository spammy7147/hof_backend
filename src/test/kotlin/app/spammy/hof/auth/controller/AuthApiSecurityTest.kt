package app.spammy.hof.auth.controller

import app.spammy.hof.auth.dto.TokenResponse
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertNull
import kotlin.test.assertTrue

@ActiveProfiles("test")
@SpringBootTest(
    properties = [
        "hof.auth.login-rate-limit-per-id=2",
        "hof.auth.login-rate-limit-per-ip=1000",
        "hof.auth.refresh-rate-limit-per-ip=1000",
    ],
)
@AutoConfigureMockMvc
class AuthApiSecurityTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {
    @Test
    fun nativeLoginReturnsAccessAndRefreshTokensWithoutAccountId() {
        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"native-auth","password":"qwer12","clientType":"NATIVE"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isString)
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.refreshToken").isString)
            .andExpect(jsonPath("$.accessTokenExpiresAt").isString)
            .andExpect(jsonPath("$.refreshTokenExpiresAt").isString)
            .andExpect(jsonPath("$.accountId").doesNotExist())
            .andExpect(cookie().doesNotExist("hof_refresh_token"))
            .andExpect(header().string("Cache-Control", "no-store"))
    }

    @Test
    fun webLoginStoresRefreshTokenOnlyInHttpOnlyStrictCookie() {
        mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"web-auth","password":"qwer12","clientType":"WEB"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isString)
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andExpect(cookie().httpOnly("hof_refresh_token", true))
            .andExpect(cookie().path("hof_refresh_token", "/api/auth"))
            .andExpect(header().string("Set-Cookie", containsString("SameSite=Strict")))
            .andExpect(header().string("Cache-Control", "no-store"))
    }

    @Test
    fun nativeRefreshRotatesTokenAndLogoutRevokesItsFamily() {
        val login = mockMvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"rotation-auth","password":"qwer12","clientType":"NATIVE"}"""),
        )
            .andExpect(status().isOk)
            .andReturn()
        val firstRefresh = objectMapper
            .readValue(login.response.contentAsString, TokenResponse::class.java)
            .refreshToken!!

        val refresh = mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$firstRefresh"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.accessToken").isString)
            .andExpect(jsonPath("$.refreshToken").isString)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()
        val secondRefresh = objectMapper
            .readValue(refresh.response.contentAsString, TokenResponse::class.java)
            .refreshToken!!

        mockMvc.perform(
            post("/api/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$secondRefresh"}"""),
        )
            .andExpect(status().isNoContent)
            .andExpect(header().string("Cache-Control", "no-store"))

        mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"$secondRefresh"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"))
            .andExpect(header().string("Cache-Control", "no-store"))
    }

    @Test
    fun protectedApiRejectsRequestWithoutBearerToken() {
        mockMvc.perform(get("/api/status"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"))
            .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
            .andExpect { result -> assertNull(result.request.getSession(false)) }
    }

    @Test
    fun unspecifiedAuthSubpathsAreNotImplicitlyPublic() {
        mockMvc.perform(get("/api/auth/internal"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun apiAllowsCredentialedCorsPreflightForExpoWeb() {
        mockMvc.perform(
            options("/api/auth/login")
                .header("Origin", "http://localhost:8081")
                .header("Access-Control-Request-Method", "POST"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"))
            .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
    }

    @Test
    fun rateLimitedResponsesExposeRetryAfterWithoutSensitiveDetails() {
        repeat(2) { index ->
            mockMvc.perform(
                post("/api/auth/login")
                    .with { request -> request.remoteAddr = "198.51.100.77"; request }
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"loginId":"limited-user","password":"wrong-$index","clientType":"NATIVE"}"""),
            )
        }

        mockMvc.perform(
            post("/api/auth/login")
                .with { request -> request.remoteAddr = "198.51.100.77"; request }
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"limited-user","password":"wrong-final","clientType":"NATIVE"}"""),
            )
            .andExpect(status().isTooManyRequests)
            .andExpect { result ->
                val retryAfter = requireNotNull(result.response.getHeader("Retry-After")).toLong()
                assertTrue(retryAfter in 1L..60L)
            }
            .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
            .andExpect(header().string("Cache-Control", "no-store"))
    }

    @TestConfiguration
    class GatewayTestConfig {
        @Bean
        @Primary
        fun fakeHofGateway(): HofGateway =
            object : HofGateway {
                override fun execute(
                    accountId: Long,
                    request: HofRequest,
                    cookies: Map<String, String>,
                ): HofHttpResponse =
                    if (request.method == HofHttpMethod.GET) {
                        HofHttpResponse(200, request.url, "<html></html>", mapOf("PHPSESSID" to "initial"))
                    } else {
                        HofHttpResponse(
                            200,
                            request.url,
                            """<a href="?char=1683198503393759">소셜</a>""",
                            mapOf("NO" to "42"),
                        )
                    }
            }
    }
}
