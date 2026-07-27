package app.spammy.hof.party.controller

import app.spammy.hof.common.security.CurrentAccountIdArgumentResolver
import app.spammy.hof.party.dto.PartyPresetCatalogResponse
import app.spammy.hof.party.dto.ReorderPartyPresetsRequest
import app.spammy.hof.party.service.PartyPresetCatalogService
import app.spammy.hof.party.service.PartyPresetService
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class PartyPresetControllerTest {
    private val presetService = Mockito.mock(PartyPresetService::class.java)
    private val catalogService = Mockito.mock(PartyPresetCatalogService::class.java)
    private val controller = PartyPresetController(presetService, catalogService)
    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(CurrentAccountIdArgumentResolver())
        .build()

    @AfterTest
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun catalogUsesLiteralRouteAndForwardsCurrentAccountId() {
        Mockito.`when`(catalogService.find(42L)).thenReturn(emptyCatalog())
        authenticate(42L)

        mockMvc.perform(get("/api/party-presets/catalog"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.folders").isArray)
            .andExpect(jsonPath("$.presets").isArray)

        Mockito.verify(catalogService).find(42L)
    }

    @Test
    fun flatListRouteRemainsBackwardCompatible() {
        Mockito.`when`(presetService.findAll(42L)).thenReturn(emptyList())
        authenticate(42L)

        mockMvc.perform(get("/api/party-presets"))
            .andExpect(status().isOk)
            .andExpect(content().json("[]"))

        Mockito.verify(presetService).findAll(42L)
    }

    @Test
    fun reorderAcceptsOmittedFolderIdAndReturnsTopLevelArray() {
        Mockito.`when`(presetService.reorder(42L, ReorderPartyPresetsRequest(folderId = null, presetIds = emptyList())))
            .thenReturn(emptyCatalog())
        authenticate(42L)

        mockMvc.perform(
            put("/api/party-presets/order")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"presetIds":[]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(content().json("[]"))

        Mockito.verify(presetService).reorder(
            42L,
            ReorderPartyPresetsRequest(folderId = null, presetIds = emptyList()),
        )
    }

    @Test
    fun reorderAcceptsNullFolderIdAndReturnsTopLevelArray() {
        val request = ReorderPartyPresetsRequest(folderId = null, presetIds = listOf(9L))
        Mockito.`when`(presetService.reorder(42L, request)).thenReturn(emptyCatalog())
        authenticate(42L)

        mockMvc.perform(
            put("/api/party-presets/order")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"folderId":null,"presetIds":[9]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(content().json("[]"))

        Mockito.verify(presetService).reorder(42L, request)
    }

    @Test
    fun deletePreservesNoContentResponseAndForwardsCurrentAccountId() {
        authenticate(42L)

        mockMvc.perform(delete("/api/party-presets/9"))
            .andExpect(status().isNoContent)
            .andExpect(content().string(""))

        Mockito.verify(presetService).delete(accountId = 42L, presetId = 9L)
    }

    private fun authenticate(accountId: Long) {
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(jwt(accountId.toString()), null)
    }

    private fun emptyCatalog() = PartyPresetCatalogResponse(folders = emptyList(), presets = emptyList())

    private fun jwt(subject: String): Jwt = Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject(subject)
        .issuedAt(FIXED_NOW)
        .expiresAt(FIXED_NOW.plusSeconds(3600))
        .build()

    private companion object {
        val FIXED_NOW: Instant = Instant.parse("2026-07-27T00:00:00Z")
    }
}
