package app.spammy.hof.town

import app.spammy.hof.auth.service.JwtTokenService
import kotlin.test.Test
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
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
class TownApiSecurityTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jwtTokenService: JwtTokenService,
) {
    @Test
    fun `모든 마을 최초 조회 API는 인증 없이는 401이고 세션을 만들지 않는다`() {
        townReadEndpoints.forEach { endpoint ->
            mockMvc.perform(get(endpoint))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
                .andExpect { result -> assertNull(result.request.getSession(false), endpoint) }
        }
    }

    @Test
    fun `인증해도 임의 URL을 실행하는 generic town action endpoint는 존재하지 않는다`() {
        val token = jwtTokenService.issue(42L).value
        mockMvc.perform(
            post("/api/town/actions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"url":"https://evil.example/action","method":"POST"}"""),
        )
            .andExpect(status().isNotFound)
    }

    private companion object {
        /** 앱의 33개 메뉴 중 `/api/quests`를 쓰는 모험 알선소를 제외한 32개 typed 최초 조회다. */
        val townReadEndpoints = listOf(
            "/api/town/fishing",
            "/api/town/fishing-exchange",
            "/api/town/rest",
            "/api/town/shops/general",
            "/api/town/shops/sundries",
            "/api/town/shops/dark",
            "/api/town/sell",
            "/api/town/combine",
            "/api/town/auction",
            "/api/town/auction-market",
            "/api/town/pvp/colosseum",
            "/api/town/pvp/colosseum-shop",
            "/api/town/agency/recruitment",
            "/api/town/home",
            "/api/town/crafting/workbase",
            "/api/town/crafting/refine",
            "/api/town/crafting/create",
            "/api/town/crafting/veteran",
            "/api/town/exchanges/emblem",
            "/api/town/exchanges/event",
            "/api/town/crafting/claris",
            "/api/town/exchanges/legacy",
            "/api/town/exchanges/ann",
            "/api/town/cards/identify",
            "/api/town/cards/upgrade",
            "/api/town/cards/change",
            "/api/town/cards/sell",
            "/api/town/cards/soul-echo",
            "/api/town/rewards/orbs",
            "/api/town/rewards/stash",
            "/api/town/raid",
            "/api/town/pantheon",
        )
    }
}
