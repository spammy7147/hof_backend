package app.spammy.hof.party.service

import app.spammy.hof.party.dto.PartyPresetFolderResponse
import app.spammy.hof.party.dto.PartyPresetMemberResponse
import app.spammy.hof.party.dto.PartyPresetResponse
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetFolderEntity
import app.spammy.hof.party.entity.PartyPresetMemberEntity
import org.springframework.stereotype.Component

/** 프리셋과 폴더 entity를 API 응답으로 변환하는 공용 mapper다. */
@Component
class PartyPresetResponseMapper {
    fun toFolderResponse(folder: PartyPresetFolderEntity): PartyPresetFolderResponse =
        PartyPresetFolderResponse(
            id = folder.id,
            name = folder.name,
            parentFolderId = folder.parent?.id,
            displayOrder = folder.displayOrder,
            createdAt = folder.createdAt.toString(),
            updatedAt = folder.updatedAt.toString(),
        )

    fun toPresetResponse(
        preset: PartyPresetEntity,
        members: List<PartyPresetMemberEntity>,
    ): PartyPresetResponse =
        PartyPresetResponse(
            id = preset.id,
            accountId = preset.account.id,
            name = preset.name,
            displayOrder = preset.displayOrder,
            isPrimary = preset.isPrimary,
            members = members
                .sortedBy { member -> member.slotIndex }
                .map(::toMemberResponse),
            createdAt = preset.createdAt.toString(),
            updatedAt = preset.updatedAt.toString(),
            folderId = preset.folder?.id,
        )

    private fun toMemberResponse(member: PartyPresetMemberEntity): PartyPresetMemberResponse =
        PartyPresetMemberResponse(
            slotIndex = member.slotIndex,
            characterId = member.character?.hofCharacterId,
            patternSlot = member.patternSlot?.slotCode?.toIntOrNull(),
        )
}
