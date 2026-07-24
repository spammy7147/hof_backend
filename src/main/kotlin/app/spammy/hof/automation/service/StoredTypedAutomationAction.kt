package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.battle.dto.RunBattleRequest
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat
import java.nio.charset.StandardCharsets
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import app.spammy.hof.automation.entity.TypedAutomationActionRunEntity

data class StoredTypedAutomationActionV1(
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
    JsonSubTypes.Type(StoredTypedActionPayload.BattleMap::class, name = "BATTLE_MAP"),
    JsonSubTypes.Type(StoredTypedActionPayload.AdventureMap::class, name = "ADVENTURE_MAP"),
)
sealed interface StoredTypedActionPayload {
    val display: StoredActionDisplay?

    data class QuestClaim(
        val questCode: String,
        val actionNo: String,
        override val display: StoredActionDisplay? = null,
    ) : StoredTypedActionPayload
    data class QuestAccept(
        val questCode: String,
        val actionNo: String,
        override val display: StoredActionDisplay? = null,
    ) : StoredTypedActionPayload
    data class QuestBattle(
        val questCode: String,
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
    fun encode(action: StoredTypedAutomationActionV1): EncodedTypedAutomationAction {
        validate(action)
        val json = objectMapper.writeValueAsString(action)
        val fingerprint = fingerprint(json)
        return EncodedTypedAutomationAction(json, fingerprint)
    }

    fun decode(schemaVersion: Int, json: String): StoredTypedAutomationActionV1 {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported typed automation action schema version: $schemaVersion" }
        return objectMapper.readValue(json, StoredTypedAutomationActionV1::class.java).also(::validate)
    }

    /** Verifies every duplicated persistence discriminator before a stored request may be submitted. */
    fun verifyPersisted(row: TypedAutomationActionRunEntity, expectedAccountId: Long): StoredTypedAutomationActionV1 {
        require(fingerprint(row.payloadJson) == row.actionFingerprint) { "Stored action fingerprint mismatch." }
        val decoded = decode(row.schemaVersion, row.payloadJson)
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

    private fun validate(action: StoredTypedAutomationActionV1) {
        when (val payload = action.payload) {
            is StoredTypedActionPayload.QuestClaim -> require(payload.questCode.isNotBlank() && payload.actionNo.isNotBlank())
            is StoredTypedActionPayload.QuestAccept -> require(payload.questCode.isNotBlank() && payload.actionNo.isNotBlank())
            is StoredTypedActionPayload.QuestBattle -> {
                require(payload.battleCount == 1)
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
        }
    }

    private fun validateBattle(presetId: Long, battleCount: Int, categoryId: String, mapCode: String, request: RunBattleRequest) {
        require(presetId > 0 && battleCount in setOf(1, 3))
        require(request.categoryId == categoryId && request.mapCode == mapCode && request.resolvedBattleCount() == battleCount)
        require(request.characterIds.isNotEmpty() && request.characterIds.size <= 5)
        require(request.patternLoads.size == request.characterIds.size)
        require(request.patternLoads.map { it.characterId } == request.characterIds)
    }

    companion object { const val SCHEMA_VERSION = 1 }
}

internal fun StoredTypedActionPayload.kind(): String = when (this) {
    is StoredTypedActionPayload.QuestClaim -> "QUEST_CLAIM"
    is StoredTypedActionPayload.QuestAccept -> "QUEST_ACCEPT"
    is StoredTypedActionPayload.QuestBattle -> "QUEST_BATTLE"
    is StoredTypedActionPayload.BattleMap -> "BATTLE_MAP"
    is StoredTypedActionPayload.AdventureMap -> "ADVENTURE_MAP"
}
