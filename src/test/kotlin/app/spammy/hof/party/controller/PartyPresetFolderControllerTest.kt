package app.spammy.hof.party.controller

import app.spammy.hof.common.security.CurrentAccountIdArgumentResolver
import app.spammy.hof.party.dto.CreatePartyPresetFolderRequest
import app.spammy.hof.party.dto.MovePartyPresetFolderRequest
import app.spammy.hof.party.dto.PartyPresetCatalogResponse
import app.spammy.hof.party.dto.PartyPresetFolderResponse
import app.spammy.hof.party.dto.PartyPresetResponse
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
import org.springframework.test.web.servlet.ResultActions
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
        Mockito.`when`(folderService.create(42L, request)).thenReturn(catalog(10L))
        authenticate()

        expectCatalog(
            performJson(post("/api/party-preset-folders"), """{"name":"레이드","parentFolderId":3}"""),
            seed = 10L,
        )

        Mockito.verify(folderService).create(42L, request)
    }

    @Test
    fun renameBindsExactRequestAndForwardsFolderAndAccountIds() {
        val request = RenamePartyPresetFolderRequest(name = "보스")
        Mockito.`when`(folderService.rename(42L, 7L, request)).thenReturn(catalog(20L))
        authenticate()

        expectCatalog(
            performJson(patch("/api/party-preset-folders/7"), """{"name":"보스"}"""),
            seed = 20L,
        )

        Mockito.verify(folderService).rename(42L, 7L, request)
    }

    @Test
    fun reorderBindsExactRequestAndForwardsCurrentAccountId() {
        val request = ReorderPartyPresetFoldersRequest(parentFolderId = null, folderIds = listOf(7L, 8L))
        Mockito.`when`(folderService.reorder(42L, request)).thenReturn(catalog(30L))
        authenticate()

        expectCatalog(
            performJson(
                put("/api/party-preset-folders/order"),
                """{"parentFolderId":null,"folderIds":[7,8]}""",
            ),
            seed = 30L,
        )

        Mockito.verify(folderService).reorder(42L, request)
    }

    @Test
    fun moveBindsExactRequestAndForwardsFolderAndAccountIds() {
        val request = MovePartyPresetFolderRequest(parentFolderId = 3L, displayOrder = 1)
        Mockito.`when`(folderService.move(42L, 7L, request)).thenReturn(catalog(40L))
        authenticate()

        expectCatalog(
            performJson(
                put("/api/party-preset-folders/7/location"),
                """{"parentFolderId":3,"displayOrder":1}""",
            ),
            seed = 40L,
        )

        Mockito.verify(folderService).move(42L, 7L, request)
    }

    @Test
    fun deleteReturnsAuthoritativeCatalogAndForwardsFolderAndAccountIds() {
        Mockito.`when`(folderService.delete(42L, 7L)).thenReturn(catalog(50L))
        authenticate()

        expectCatalog(mockMvc.perform(delete("/api/party-preset-folders/7")), seed = 50L)

        Mockito.verify(folderService).delete(42L, 7L)
    }

    private fun performJson(
        builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder,
        json: String,
    ) = mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON).content(json))

    private fun authenticate() {
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(jwt("42"), null)
    }

    private fun expectCatalog(result: ResultActions, seed: Long) {
        result
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.folders[0].id").value(seed))
            .andExpect(jsonPath("$.folders[0].name").value("folder-$seed"))
            .andExpect(jsonPath("$.folders[0].parentFolderId").value(seed + 1))
            .andExpect(jsonPath("$.folders[0].displayOrder").value(seed.toInt()))
            .andExpect(jsonPath("$.presets[0].id").value(seed + 2))
            .andExpect(jsonPath("$.presets[0].name").value("preset-$seed"))
            .andExpect(jsonPath("$.presets[0].folderId").value(seed))
    }

    private fun catalog(seed: Long) = PartyPresetCatalogResponse(
        folders = listOf(
            PartyPresetFolderResponse(
                id = seed,
                name = "folder-$seed",
                parentFolderId = seed + 1,
                displayOrder = seed.toInt(),
                createdAt = "2026-07-27T00:00:00Z",
                updatedAt = "2026-07-27T01:00:00Z",
            ),
        ),
        presets = listOf(
            PartyPresetResponse(
                id = seed + 2,
                accountId = 42L,
                name = "preset-$seed",
                displayOrder = seed.toInt() + 1,
                isPrimary = false,
                members = emptyList(),
                createdAt = "2026-07-27T02:00:00Z",
                updatedAt = "2026-07-27T03:00:00Z",
                folderId = seed,
            ),
        ),
    )

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
