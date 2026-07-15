package app.spammy.hof.party.controller

import app.spammy.hof.party.dto.CreatePartyPresetRequest
import app.spammy.hof.party.dto.PartyPresetMemberRequest
import app.spammy.hof.party.dto.PartyPresetMemberResponse
import app.spammy.hof.party.dto.PartyPresetResponse
import app.spammy.hof.party.dto.UpdatePartyPresetRequest
import app.spammy.hof.party.service.PartyPresetService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals

class PartyPresetControllerTest {
    private val presetService = Mockito.mock(PartyPresetService::class.java)
    private val controller = PartyPresetController(presetService)

    @Test
    fun listDelegatesToService() {
        Mockito.`when`(presetService.findAll(accountId = 1L)).thenReturn(listOf(preset()))

        val response = controller.findAll(accountId = 1L)

        assertEquals(1, response.size)
        assertEquals("고블린 범용 파티", response.single().name)
        Mockito.verify(presetService).findAll(accountId = 1L)
    }

    @Test
    fun createAndUpdateAndDeleteDelegateToService() {
        val members = listOf(
            PartyPresetMemberRequest(slotIndex = 0, characterId = "char-1", patternSlot = 0),
            PartyPresetMemberRequest(slotIndex = 1, characterId = "char-2", patternSlot = 1),
            PartyPresetMemberRequest(slotIndex = 2, characterId = null, patternSlot = null),
            PartyPresetMemberRequest(slotIndex = 3, characterId = null, patternSlot = null),
            PartyPresetMemberRequest(slotIndex = 4, characterId = null, patternSlot = null),
        )
        val createRequest = CreatePartyPresetRequest(name = "고블린 범용 파티", members = members)
        val updateRequest = UpdatePartyPresetRequest(name = "모험 기본 파티", members = members)
        Mockito.`when`(presetService.create(accountId = 1L, request = createRequest)).thenReturn(preset())
        Mockito.`when`(presetService.update(accountId = 1L, presetId = 3L, request = updateRequest))
            .thenReturn(preset(name = "모험 기본 파티"))

        val created = controller.create(accountId = 1L, request = createRequest)
        val updated = controller.update(accountId = 1L, presetId = 3L, request = updateRequest)
        val deleted = controller.delete(accountId = 1L, presetId = 3L)

        assertEquals("고블린 범용 파티", created.name)
        assertEquals(false, created.isPrimary)
        assertEquals(listOf("char-1", "char-2", null, null, null), created.members.map { it.characterId })
        assertEquals(listOf(0, 1, null, null, null), created.members.map { it.patternSlot })
        assertEquals("모험 기본 파티", updated.name)
        assertEquals(204, deleted.statusCode.value())
        Mockito.verify(presetService).delete(accountId = 1L, presetId = 3L)
    }

    @Test
    fun makePrimaryDelegatesToServiceAndReturnsPrimaryState() {
        Mockito.`when`(presetService.makePrimary(accountId = 1L, presetId = 3L))
            .thenReturn(preset(isPrimary = true))

        val response = controller.makePrimary(accountId = 1L, presetId = 3L)

        assertEquals(true, response.isPrimary)
        Mockito.verify(presetService).makePrimary(accountId = 1L, presetId = 3L)
    }

    private fun preset(
        name: String = "고블린 범용 파티",
        isPrimary: Boolean = false,
    ): PartyPresetResponse =
        PartyPresetResponse(
            id = 3L,
            accountId = 1L,
            name = name,
            isPrimary = isPrimary,
            members = listOf(
                PartyPresetMemberResponse(slotIndex = 0, characterId = "char-1", patternSlot = 0),
                PartyPresetMemberResponse(slotIndex = 1, characterId = "char-2", patternSlot = 1),
                PartyPresetMemberResponse(slotIndex = 2, characterId = null, patternSlot = null),
                PartyPresetMemberResponse(slotIndex = 3, characterId = null, patternSlot = null),
                PartyPresetMemberResponse(slotIndex = 4, characterId = null, patternSlot = null),
            ),
            createdAt = "2026-07-11T00:00:00Z",
            updatedAt = "2026-07-11T00:00:00Z",
        )
}
