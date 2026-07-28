package app.spammy.hof.party.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.dto.CreatePartyPresetFolderRequest
import app.spammy.hof.party.dto.MovePartyPresetFolderRequest
import app.spammy.hof.party.dto.PartyPresetCatalogResponse
import app.spammy.hof.party.dto.RenamePartyPresetFolderRequest
import app.spammy.hof.party.dto.ReorderPartyPresetFoldersRequest
import app.spammy.hof.party.entity.PartyPresetEntity
import app.spammy.hof.party.entity.PartyPresetFolderEntity
import app.spammy.hof.party.repository.PartyPresetFolderQueryRepository
import app.spammy.hof.party.repository.PartyPresetFolderRepository
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.party.repository.PartyPresetRepository
import java.util.Locale
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 계정 잠금 아래 파티 프리셋 폴더 계층과 형제 표시 순서를 변경한다. */
@Service
class PartyPresetFolderService(
    private val accountQueryRepository: AccountQueryRepository,
    private val folderQueryRepository: PartyPresetFolderQueryRepository,
    private val folderRepository: PartyPresetFolderRepository,
    private val presetQueryRepository: PartyPresetQueryRepository,
    private val presetRepository: PartyPresetRepository,
    private val timeProvider: TimeProvider,
    private val catalogService: PartyPresetCatalogService,
) {
    /** 목적지 형제의 맨 앞에 폴더를 만들고 계층 깊이와 이름 고유성을 검증한다. */
    @Transactional
    fun create(accountId: Long, request: CreatePartyPresetFolderRequest): PartyPresetCatalogResponse {
        val state = lockAndLoad(accountId)
        val parent = request.parentFolderId?.let { state.ownedFolder(it) }
        if (parent != null && depthOf(parent.id, state.byId) >= MAX_LEVEL) levelError()
        val name = normalizeName(request.name)
        ensureUniqueName(name, request.parentFolderId, state.childrenByParent)

        val siblings = state.childrenByParent[request.parentFolderId].orEmpty().sortedByOrder()
        siblings.forEachIndexed { index, sibling -> sibling.displayOrder = index + 1 }
        val now = timeProvider.now()
        folderRepository.save(
            PartyPresetFolderEntity(
                account = state.account,
                parent = parent,
                name = name,
                displayOrder = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        folderRepository.flush()
        return catalogService.find(accountId)
    }

    /** 같은 부모 아래의 다른 폴더와 충돌하지 않는 정규화된 이름으로 변경한다. */
    @Transactional
    fun rename(accountId: Long, folderId: Long, request: RenamePartyPresetFolderRequest): PartyPresetCatalogResponse {
        val state = lockAndLoad(accountId)
        val folder = state.ownedFolder(folderId)
        val name = normalizeName(request.name)
        ensureUniqueName(name, folder.parent?.id, state.childrenByParent, excludingId = folder.id)
        folder.name = name
        folder.updatedAt = timeProvider.now()
        folderRepository.flush()
        return catalogService.find(accountId)
    }

    /** 요청 부모의 현재 형제 ID 전체 집합을 검증하고 요청 배열 순서로 0-based 순서를 저장한다. */
    @Transactional
    fun reorder(accountId: Long, request: ReorderPartyPresetFoldersRequest): PartyPresetCatalogResponse {
        val state = lockAndLoad(accountId)
        if (request.parentFolderId != null) state.ownedFolder(request.parentFolderId)
        val siblings = state.childrenByParent[request.parentFolderId].orEmpty()
        val ownedIds = siblings.map { it.id }.toSet()
        if (request.folderIds.distinct().size != request.folderIds.size || request.folderIds.toSet() != ownedIds) {
            invalid("현재 부모의 모든 폴더를 중복 없이 지정해야 합니다.")
        }
        val byId = siblings.associateBy { it.id }
        request.folderIds.forEachIndexed { order, id -> byId.getValue(id).displayOrder = order }
        folderRepository.flush()
        return catalogService.find(accountId)
    }

    /** 폴더의 전체 subtree를 보존해 목적지로 옮기고 출발지와 목적지 형제 순서를 함께 정규화한다. */
    @Transactional
    fun move(accountId: Long, folderId: Long, request: MovePartyPresetFolderRequest): PartyPresetCatalogResponse {
        val state = lockAndLoad(accountId)
        val moving = state.ownedFolder(folderId)
        val destination = request.parentFolderId?.let { state.ownedFolder(it) }
        if (destination?.id == moving.id) invalid("폴더를 자기 자신 아래로 이동할 수 없습니다.")
        if (destination != null && isDescendant(destination.id, moving.id, state.childrenByParent)) {
            invalid("폴더를 하위 폴더 아래로 이동할 수 없습니다.")
        }

        val destinationDepth = destination?.let { depthOf(it.id, state.byId) } ?: 0
        val subtreeHeight = subtreeHeightOf(moving.id, state.childrenByParent)
        if (destinationDepth + subtreeHeight > MAX_LEVEL) levelError()

        val sourceParentId = moving.parent?.id
        val destinationParentId = destination?.id
        val sourceSiblings = state.childrenByParent[sourceParentId].orEmpty().filterNot { it.id == moving.id }.sortedByOrder()
        val destinationSiblings = if (sourceParentId == destinationParentId) {
            sourceSiblings.toMutableList()
        } else {
            state.childrenByParent[destinationParentId].orEmpty().sortedByOrder().toMutableList()
        }
        if (request.displayOrder !in 0..destinationSiblings.size) invalid("표시 순서가 목적지 범위를 벗어났습니다.")
        ensureUniqueName(moving.name, destinationParentId, state.childrenByParent, excludingId = moving.id)

        if (sourceParentId != destinationParentId) sourceSiblings.forEachIndexed { index, sibling -> sibling.displayOrder = index }
        destinationSiblings.add(request.displayOrder, moving)
        destinationSiblings.forEachIndexed { index, sibling -> sibling.displayOrder = index }
        moving.parent = destination
        moving.updatedAt = timeProvider.now()
        folderRepository.flush()
        return catalogService.find(accountId)
    }

    /** 폴더만 삭제하고 직속 프리셋과 자식 폴더는 각각 미분류와 삭제 폴더의 부모 끝으로 옮긴다. */
    @Transactional
    fun delete(accountId: Long, folderId: Long): PartyPresetCatalogResponse {
        val state = lockAndLoad(accountId)
        val deleting = state.ownedFolder(folderId)
        val parentId = deleting.parent?.id

        val unassigned = presetQueryRepository.findAllByAccountIdAndFolderId(accountId, null)
        val directPresets = presetQueryRepository.findAllByAccountIdAndFolderId(accountId, deleting.id)
        val finalUnassigned = unassigned.sortedPresetsByOrder() + directPresets.sortedPresetsByOrder()

        val destinationSiblings = state.childrenByParent[parentId]
            .orEmpty()
            .filterNot { it.id == deleting.id }
            .sortedByOrder()
        val immediateChildren = state.childrenByParent[deleting.id].orEmpty().sortedByOrder()
        val finalDestinationSiblings = destinationSiblings + immediateChildren

        finalUnassigned.forEachIndexed { index, preset ->
            preset.folder = null
            preset.displayOrder = index
        }
        finalDestinationSiblings.forEachIndexed { index, folder ->
            folder.displayOrder = index
        }
        immediateChildren.forEach { child -> child.parent = deleting.parent }

        presetRepository.flush()
        folderRepository.flush()
        folderRepository.delete(deleting)
        folderRepository.flush()
        return catalogService.find(accountId)
    }

    private fun lockAndLoad(accountId: Long): FolderState {
        val account = accountQueryRepository.findByIdForUpdate(accountId)
            ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "HOF 계정을 찾지 못했습니다.")
        val folders = folderQueryRepository.findAllByAccountId(accountId)
        return FolderState(
            account = account,
            byId = folders.associateBy { it.id },
            childrenByParent = folders.groupBy { it.parent?.id },
        )
    }

    private fun normalizeName(raw: String): String {
        val name = raw.trim()
        if (name.isBlank() || name.length > MAX_NAME_LENGTH) invalid("폴더 이름은 1자 이상 100자 이하여야 합니다.")
        return name
    }

    private fun ensureUniqueName(
        name: String,
        parentId: Long?,
        childrenByParent: Map<Long?, List<PartyPresetFolderEntity>>,
        excludingId: Long? = null,
    ) {
        val key = name.lowercase(Locale.ROOT)
        if (childrenByParent[parentId].orEmpty().any { it.id != excludingId && it.name.trim().lowercase(Locale.ROOT) == key }) {
            invalid("같은 부모 아래에 동일한 이름의 폴더가 있습니다.")
        }
    }

    private fun depthOf(folderId: Long, byId: Map<Long, PartyPresetFolderEntity>): Int {
        var currentId: Long? = folderId
        var depth = 0
        val visited = mutableSetOf<Long>()
        while (currentId != null) {
            if (!visited.add(currentId)) invalid("폴더 계층에 순환이 있습니다.")
            val current = byId[currentId] ?: invalid("폴더 계층이 올바르지 않습니다.")
            depth += 1
            currentId = current.parent?.id
        }
        return depth
    }

    private fun subtreeHeightOf(folderId: Long, childrenByParent: Map<Long?, List<PartyPresetFolderEntity>>): Int {
        var height = 0
        var frontier = listOf(folderId)
        val visited = mutableSetOf<Long>()
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<Long>()
            frontier.forEach { id ->
                if (!visited.add(id)) invalid("폴더 계층에 순환이 있습니다.")
                next += childrenByParent[id].orEmpty().map { it.id }
            }
            height += 1
            frontier = next
        }
        return height
    }

    private fun isDescendant(candidateId: Long, ancestorId: Long, childrenByParent: Map<Long?, List<PartyPresetFolderEntity>>): Boolean {
        val pending = ArrayDeque<Long>()
        pending.add(ancestorId)
        val visited = mutableSetOf<Long>()
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!visited.add(id)) invalid("폴더 계층에 순환이 있습니다.")
            childrenByParent[id].orEmpty().forEach { child ->
                if (child.id == candidateId) return true
                pending.add(child.id)
            }
        }
        return false
    }

    private fun List<PartyPresetFolderEntity>.sortedByOrder() = sortedWith(compareBy({ it.displayOrder }, { it.id }))
    private fun List<PartyPresetEntity>.sortedPresetsByOrder() =
        sortedWith(compareBy({ it.displayOrder }, { it.id }))

    private fun FolderState.ownedFolder(id: Long): PartyPresetFolderEntity =
        byId[id] ?: throw ApiException(ErrorCode.RESOURCE_NOT_FOUND, "파티 프리셋 폴더를 찾지 못했습니다.")

    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
    private fun levelError(): Nothing = invalid("폴더는 최대 5단계까지 만들 수 있습니다.")

    private data class FolderState(
        val account: HofAccountEntity,
        val byId: Map<Long, PartyPresetFolderEntity>,
        val childrenByParent: Map<Long?, List<PartyPresetFolderEntity>>,
    )

    private companion object {
        const val MAX_LEVEL = 5
        const val MAX_NAME_LENGTH = 100
    }
}
