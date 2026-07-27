package app.spammy.hof.party.controller

import app.spammy.hof.party.dto.CreatePartyPresetRequest
import app.spammy.hof.party.dto.PartyPresetCatalogResponse
import app.spammy.hof.party.dto.PartyPresetResponse
import app.spammy.hof.party.dto.ReorderPartyPresetsRequest
import app.spammy.hof.party.dto.UpdatePartyPresetRequest
import app.spammy.hof.party.service.PartyPresetCatalogService
import app.spammy.hof.party.service.PartyPresetService
import app.spammy.hof.common.security.CurrentAccountId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PutMapping

@RestController
@RequestMapping("/api/party-presets")
/**
 * 캐릭터 탭의 파티 프리셋 API다.
 */
class PartyPresetController(
    private val presetService: PartyPresetService,
    private val catalogService: PartyPresetCatalogService,
) {
    /**
     * 계정에 저장된 파티 프리셋 목록을 조회한다.
     */
    @GetMapping
    fun findAll(
        @CurrentAccountId accountId: Long,
    ): List<PartyPresetResponse> =
        presetService.findAll(accountId)

    /** 계정의 폴더와 프리셋을 authoritative catalog로 조회한다. */
    @GetMapping("/catalog")
    fun findCatalog(
        @CurrentAccountId accountId: Long,
    ): PartyPresetCatalogResponse =
        catalogService.find(accountId)

    /**
     * 새 파티 프리셋을 저장한다.
     */
    @PostMapping
    fun create(
        @CurrentAccountId accountId: Long,
        @RequestBody request: CreatePartyPresetRequest,
    ): PartyPresetResponse =
        presetService.create(accountId = accountId, request = request)

    /** 요청한 nullable 폴더의 모든 파티 프리셋을 전달된 순서로 저장한다. */
    @PutMapping("/order")
    fun reorder(
        @CurrentAccountId accountId: Long,
        @RequestBody request: ReorderPartyPresetsRequest,
    ): List<PartyPresetResponse> =
        presetService.reorder(accountId = accountId, request = request).presets

    /**
     * 기존 파티 프리셋 이름과 슬롯 정보를 수정한다.
     */
    @PatchMapping("/{presetId}")
    fun update(
        @CurrentAccountId accountId: Long,
        @PathVariable presetId: Long,
        @RequestBody request: UpdatePartyPresetRequest,
    ): PartyPresetResponse =
        presetService.update(accountId = accountId, presetId = presetId, request = request)

    /**
     * 소유한 파티 프리셋을 계정의 기본 프리셋으로 지정한다.
     */
    @PostMapping("/{presetId}/primary")
    fun makePrimary(
        @CurrentAccountId accountId: Long,
        @PathVariable presetId: Long,
    ): PartyPresetResponse =
        presetService.makePrimary(accountId = accountId, presetId = presetId)

    /**
     * 파티 프리셋을 삭제한다.
     */
    @DeleteMapping("/{presetId}")
    fun delete(
        @CurrentAccountId accountId: Long,
        @PathVariable presetId: Long,
    ): ResponseEntity<Void> {
        presetService.delete(accountId = accountId, presetId = presetId)
        return ResponseEntity.noContent().build()
    }
}
