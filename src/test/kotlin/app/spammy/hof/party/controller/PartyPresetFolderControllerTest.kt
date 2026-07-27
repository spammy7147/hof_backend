package app.spammy.hof.party.controller

import app.spammy.hof.common.security.CurrentAccountIdArgumentResolver
import app.spammy.hof.party.dto.CreatePartyPresetFolderRequest
import app.spammy.hof.party.dto.MovePartyPresetFolderRequest
import app.spammy.hof.party.dto.PartyPresetCatalogResponse
import app.spammy.hof.party.dto.RenamePartyPresetFolderRequest
import app.spammy.hof.party.dto.ReorderPartyPresetFoldersRequest
import app.spammy.hof.party.service.PartyPresetFolderService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class PartyPresetFolderControllerTest {
    private val folderService = Mockito.mock(PartyPresetFolderService::class.java)
    private val controller = PartyPresetFolderController(folderService)
    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(CurrentAccountIdArgumentResolver())
        .build()

    @AfterTest
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun createBindsExactRequestAndReturnsAuthoritativeCatalog() {
        val request = CreatePartyPresetFolderRequest(name = "레이드", parentFolderId = 3L)
        Mockito.`when`(folderService.create(42L, request)).thenReturn(emptyCatalog())
        authenticate()

        performJson(post("/api/party-preset-folders"), """{"name":"레이드","parentFolderId":3}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.folders").isArray)
            .andExpect(jsonPath("$.presets").isArray)

        Mockito.verify(folderService).create(42L, request)
    }

    @Test
    fun renameBindsExactRequestAndForwardsFolderAndAccountIds() {
        val request = RenamePartyPresetFolderRequest(name = "보스")
        Mockito.`when`(folderService.rename(42L, 7L, request)).thenReturn(emptyCatalog())
        authenticate()

        performJson(patch("/api/party-preset-folders/7"), """{"name":"보스"}""")
            .andExpect(status().isOk)

        Mockito.verify(folderService).rename(42L, 7L, request)
    }

    @Test
    fun reorderBindsExactRequestAndForwardsCurrentAccountId() {
        val request = ReorderPartyPresetFoldersRequest(parentFolderId = null, folderIds = listOf(7L, 8L))
        Mockito.`when`(folderService.reorder(42L, request)).thenReturn(emptyCatalog())
        authenticate()

        performJson(
            put("/api/party-preset-folders/order"),
            """{"parentFolderId":null,"folderIds":[7,8]}""",
        ).andExpect(status().isOk)

        Mockito.verify(folderService).reorder(42L, request)
    }

    @Test
    fun moveBindsExactRequestAndForwardsFolderAndAccountIds() {
        val request = MovePartyPresetFolderRequest(parentFolderId = 3L, displayOrder = 1)
        Mockito.`when`(folderService.move(42L, 7L, request)).thenReturn(emptyCatalog())
        authenticate()

        performJson(
            put("/api/party-preset-folders/7/location"),
            """{"parentFolderId":3,"displayOrder":1}""",
        ).andExpect(status().isOk)

        Mockito.verify(folderService).move(42L, 7L, request)
    }

    @Test
    fun deleteReturnsAuthoritativeCatalogAndForwardsFolderAndAccountIds() {
        Mockito.`when`(folderService.delete(42L, 7L)).thenReturn(emptyCatalog())
        authenticate()

        mockMvc.perform(delete("/api/party-preset-folders/7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.folders").isArray)
            .andExpect(jsonPath("$.presets").isArray)

        Mockito.verify(folderService).delete(42L, 7L)
    }

    private fun performJson(
        builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder,
        json: String,
    ) = mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(json))

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(jwt("42"), null)
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
