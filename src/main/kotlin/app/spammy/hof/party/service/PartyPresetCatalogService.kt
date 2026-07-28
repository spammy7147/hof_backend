package app.spammy.hof.party.service

import app.spammy.hof.party.dto.PartyPresetCatalogResponse
import app.spammy.hof.party.repository.PartyPresetFolderQueryRepository
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 폴더와 프리셋 및 슬롯을 계정의 authoritative catalog 응답으로 조립한다. */
@Service
class PartyPresetCatalogService(
    private val folderQueryRepository: PartyPresetFolderQueryRepository,
    private val presetQueryRepository: PartyPresetQueryRepository,
    private val responseMapper: PartyPresetResponseMapper,
) {
    @Transactional(readOnly = true)
    fun find(accountId: Long): PartyPresetCatalogResponse {
        val folders = folderQueryRepository.findAllByAccountId(accountId)
        val presets = presetQueryRepository.findAllByAccountId(accountId)
        val membersByPresetId = presetQueryRepository
            .findMembersByPresetIds(presets.map { preset -> preset.id })
            .groupBy { member -> member.preset.id }

        return PartyPresetCatalogResponse(
            folders = folders.map(responseMapper::toFolderResponse),
            presets = presets.map { preset ->
                responseMapper.toPresetResponse(preset, membersByPresetId[preset.id].orEmpty())
            },
        )
    }
}
