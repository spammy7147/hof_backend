package app.spammy.hof.party.dto

/** 계정이 소유한 폴더 트리와 프리셋을 한 번에 내려주는 카탈로그 응답이다. */
data class PartyPresetCatalogResponse(
    val folders: List<PartyPresetFolderResponse>,
    val presets: List<PartyPresetResponse>,
)

/** 파티 프리셋 폴더의 계층과 표시 순서를 표현한다. */
data class PartyPresetFolderResponse(
    val id: Long,
    val name: String,
    val parentFolderId: Long?,
    val displayOrder: Int,
    val createdAt: String,
    val updatedAt: String,
)
