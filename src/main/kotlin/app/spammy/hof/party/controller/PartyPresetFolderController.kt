package app.spammy.hof.party.controller

import app.spammy.hof.common.security.CurrentAccountId
import app.spammy.hof.party.dto.CreatePartyPresetFolderRequest
import app.spammy.hof.party.dto.MovePartyPresetFolderRequest
import app.spammy.hof.party.dto.PartyPresetCatalogResponse
import app.spammy.hof.party.dto.RenamePartyPresetFolderRequest
import app.spammy.hof.party.dto.ReorderPartyPresetFoldersRequest
import app.spammy.hof.party.service.PartyPresetFolderService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 파티 프리셋 폴더 변경 후 authoritative catalog를 반환하는 API다. */
@RestController
@RequestMapping("/api/party-preset-folders")
class PartyPresetFolderController(
    private val folderService: PartyPresetFolderService,
) {
    @PostMapping
    fun create(
        @CurrentAccountId accountId: Long,
        @RequestBody request: CreatePartyPresetFolderRequest,
    ): PartyPresetCatalogResponse =
        folderService.create(accountId, request)

    @PatchMapping("/{folderId}")
    fun rename(
        @CurrentAccountId accountId: Long,
        @PathVariable folderId: Long,
        @RequestBody request: RenamePartyPresetFolderRequest,
    ): PartyPresetCatalogResponse =
        folderService.rename(accountId, folderId, request)

    @PutMapping("/order")
    fun reorder(
        @CurrentAccountId accountId: Long,
        @RequestBody request: ReorderPartyPresetFoldersRequest,
    ): PartyPresetCatalogResponse =
        folderService.reorder(accountId, request)

    @PutMapping("/{folderId}/location")
    fun move(
        @CurrentAccountId accountId: Long,
        @PathVariable folderId: Long,
        @RequestBody request: MovePartyPresetFolderRequest,
    ): PartyPresetCatalogResponse =
        folderService.move(accountId, folderId, request)

    @DeleteMapping("/{folderId}")
    fun delete(
        @CurrentAccountId accountId: Long,
        @PathVariable folderId: Long,
    ): PartyPresetCatalogResponse =
        folderService.delete(accountId, folderId)
}
