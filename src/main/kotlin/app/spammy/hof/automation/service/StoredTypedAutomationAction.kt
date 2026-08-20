package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.battle.dto.RunBattleRequest
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.security.MessageDigest
import java.time.LocalDate
import java.time.Instant
import java.util.HexFormat
import java.nio.charset.StandardCharsets
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity

data class StoredTypedAutomationAction(
    val entryId: Long,
    val executionIdentity: String,
    val payload: StoredTypedActionPayload,
) {
    init {
        require(entryId > 0)
        require(executionIdentity.isNotBlank() && executionIdentity.length <= 128)
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(StoredTypedActionPayload.QuestClaim::class, name = "QUEST_CLAIM"),
    JsonSubTypes.Type(StoredTypedActionPayload.QuestAccept::class, name = "QUEST_ACCEPT"),
    JsonSubTypes.Type(StoredTypedActionPayload.QuestBattle::class, name = "QUEST_BATTLE"),
    JsonSubTypes.Type(StoredTypedActionPayload.HomeQuest::class, name = "HOME_QUEST"),
    JsonSubTypes.Type(StoredTypedActionPayload.BattleMap::class, name = "BATTLE_MAP"),
    JsonSubTypes.Type(StoredTypedActionPayload.AdventureMap::class, name = "ADVENTURE_MAP"),
    JsonSubTypes.Type(StoredTypedActionPayload.FishingTown::class, name = "FISHING_TOWN"),
    JsonSubTypes.Type(StoredTypedActionPayload.RaidTown::class, name = "RAID_TOWN"),
    JsonSubTypes.Type(StoredTypedActionPayload.RaidCycleAbort::class, name = "RAID_CYCLE_ABORT"),
)
sealed interface StoredTypedActionPayload {
    val display: StoredActionDisplay?

    data class QuestClaim(
        val questKey: String,
        val actionNo: String,
        override val display: StoredActionDisplay? = null,
    ) : StoredTypedActionPayload
    data class QuestAccept(
        val questKey: String,
        val actionNo: String,
        override val display: StoredActionDisplay? = null,
    ) : StoredTypedActionPayload
    data class HomeQuest(
        val questId: String,
        val actionId: String,
        val action: HomeQuestAutomationActionType,
        override val display: StoredActionDisplay? = null,
    ) : StoredTypedActionPayload
    data class QuestBattle(
        val questKey: String,
        val questCycle: String,
        val missionKey: String,
        val missionType: QuestMissionType,
        val categoryId: String,
        val mapCode: String,
        val presetMode: PresetSelectionMode,
        val presetId: Long,
        val battleCount: Int,
        val battleRequest: RunBattleRequest,
        override val display: StoredActionDisplay? = null,
        val observedCurrent: Int? = null,
        val observedRequired: Int? = null,
    ) : StoredTypedActionPayload
    data class BattleMap(
        val progressDate: LocalDate,
        val categoryId: String,
        val mapCode: String,
        val presetMode: PresetSelectionMode,
        val presetId: Long,
        val battleCount: Int,
        val battleRequest: RunBattleRequest,
        override val display: StoredActionDisplay? = null,
        val source: BattleAutomationActionSource = BattleAutomationActionSource.BATTLE_MAP_AUTOMATION,
        val sourceTargetKey: String? = null,
    ) : StoredTypedActionPayload
    data class FishingTown(
        val action: app.spammy.hof.town.fishing.model.FishingAction,
        val observedPrimaryAction: app.spammy.hof.town.fishing.model.FishingPrimaryAction,
        val observedRemainingCasts: Int? = null,
        override val display: StoredActionDisplay? = null,
    ) : StoredTypedActionPayload
    data class RaidTown(
        val action: app.spammy.hof.town.raid.model.RaidAction,
        val raidId: String? = null,
        val targetRaidId: String? = raidId,
        override val display: StoredActionDisplay? = null,
    ) : StoredTypedActionPayload
    data class RaidCycleAbort(
        val raidId: String,
        val reason: RaidCycleAbortReason = RaidCycleAbortReason.CLOSED,
        override val display: StoredActionDisplay? = null,
    ) : StoredTypedActionPayload
    data class AdventureMap(
        val categoryId: String,
        val mapCode: String,
        val presetMode: PresetSelectionMode,
        val presetId: Long,
        val battleCount: Int,
        val settingIdentity: Long,
        val battleRequest: RunBattleRequest,
        override val display: StoredActionDisplay? = null,
        val observedCooldownUntil: Instant? = null,
        val observedAttemptRemaining: Int? = null,
        val observedWinRemaining: Int? = null,
        val observedAvailableCount: Int? = null,
    ) : StoredTypedActionPayload
}

data class StoredActionDisplay(
    val questName: String? = null,
    val missionLabel: String? = null,
    val missionCurrent: Int? = null,
    val missionRequired: Int? = null,
    val mapName: String? = null,
)

data class EncodedTypedAutomationAction(val json: String, val fingerprint: String)

@Component
class StoredTypedAutomationActionCodec(private val objectMapper: ObjectMapper) {
    fun encode(action: StoredTypedAutomationAction): EncodedTypedAutomationAction {
        validate(action)
        val json = objectMapper.writeValueAsString(action)
        val fingerprint = fingerprint(json)
        return EncodedTypedAutomationAction(json, fingerprint)
    }

    fun decode(json: String): StoredTypedAutomationAction =
        objectMapper.readValue(json, StoredTypedAutomationAction::class.java).also(::validate)

    /** Verifies every duplicated persistence discriminator before a stored request may be submitted. */
    fun verifyPersisted(row: TypedAutomationActionRunEntity, expectedAccountId: Long): StoredTypedAutomationAction {
        require(fingerprint(row.payloadJson) == row.actionFingerprint) { "Stored action fingerprint mismatch." }
        val decoded = decode(row.payloadJson)
        require(row.actionKind == decoded.payload.kind()) { "Stored action kind mismatch." }
        require(row.account.id == expectedAccountId) { "Stored action account mismatch." }
        row.entry?.let { entry ->
            require(entry.account.id == expectedAccountId) { "Stored action account mismatch." }
            require(entry.id == decoded.entryId) { "Stored action entry mismatch." }
        }
        require(row.executionIdentity == decoded.executionIdentity) { "Stored action execution mismatch." }
        return decoded
    }

    private fun fingerprint(json: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(json.toByteArray(StandardCharsets.UTF_8)),
    )

    private fun validate(action: StoredTypedAutomationAction) {
        when (val payload = action.payload) {
            is StoredTypedActionPayload.QuestClaim -> require(payload.questKey.isNotBlank() && payload.actionNo.isNotBlank())
            is StoredTypedActionPayload.QuestAccept -> require(payload.questKey.isNotBlank() && payload.actionNo.isNotBlank())
            is StoredTypedActionPayload.HomeQuest -> require(payload.questId.isNotBlank() && payload.actionId.isNotBlank())
            is StoredTypedActionPayload.QuestBattle -> {
                require(payload.observedCurrent == null || payload.observedCurrent >= 0)
                require(payload.observedRequired == null || payload.observedRequired >= 0)
                require(
                    payload.observedCurrent == null ||
                        payload.observedRequired == null ||
                        payload.observedCurrent <= payload.observedRequired
                )
                validateBattle(payload.presetId, payload.battleCount, payload.categoryId, payload.mapCode, payload.battleRequest)
            }
            is StoredTypedActionPayload.BattleMap -> validateBattle(payload.presetId, payload.battleCount, payload.categoryId, payload.mapCode, payload.battleRequest)
            is StoredTypedActionPayload.AdventureMap -> validateBattle(payload.presetId, payload.battleCount, payload.categoryId, payload.mapCode, payload.battleRequest)
            is StoredTypedActionPayload.FishingTown -> {
                require(payload.action in setOf(app.spammy.hof.town.fishing.model.FishingAction.START, app.spammy.hof.town.fishing.model.FishingAction.CATCH))
                require(payload.observedRemainingCasts == null || payload.observedRemainingCasts >= 0)
            }
            is StoredTypedActionPayload.RaidTown -> require(payload.action !in setOf(app.spammy.hof.town.raid.model.RaidAction.LEAVE, app.spammy.hof.town.raid.model.RaidAction.WAIT_RESET))
            is StoredTypedActionPayload.RaidCycleAbort -> require(payload.raidId.isNotBlank())
        }
    }

    private fun validateBattle(presetId: Long, battleCount: Int, categoryId: String, mapCode: String, request: RunBattleRequest) {
        require(presetId > 0 && battleCount in setOf(1, 3))
        require(request.categoryId == categoryId && request.mapCode == mapCode && request.resolvedBattleCount() == battleCount)
        require(request.characterIds.isNotEmpty() && request.characterIds.size <= 5)
        require(request.patternLoads.size == request.characterIds.size)
        require(request.patternLoads.map { it.characterId } == request.characterIds)
    }
}

internal fun StoredTypedActionPayload.kind(): String = when (this) {
    is StoredTypedActionPayload.QuestClaim -> "QUEST_CLAIM"
    is StoredTypedActionPayload.QuestAccept -> "QUEST_ACCEPT"
    is StoredTypedActionPayload.HomeQuest -> "HOME_QUEST"
    is StoredTypedActionPayload.QuestBattle -> "QUEST_BATTLE"
    is StoredTypedActionPayload.BattleMap -> "BATTLE_MAP"
    is StoredTypedActionPayload.AdventureMap -> "ADVENTURE_MAP"
    is StoredTypedActionPayload.FishingTown -> "FISHING_TOWN"
    is StoredTypedActionPayload.RaidTown -> "RAID_TOWN"
    is StoredTypedActionPayload.RaidCycleAbort -> "RAID_CYCLE_ABORT"
}
