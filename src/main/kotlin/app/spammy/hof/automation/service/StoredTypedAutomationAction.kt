package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.quest.model.QuestMissionType
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.security.MessageDigest
import java.time.LocalDate
import java.util.HexFormat
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

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
    data class QuestClaim(val questCode: String, val actionNo: String) : StoredTypedActionPayload
    data class QuestAccept(val questCode: String, val actionNo: String) : StoredTypedActionPayload
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
    ) : StoredTypedActionPayload
    data class BattleMap(
        val progressDate: LocalDate,
        val categoryId: String,
        val mapCode: String,
        val presetMode: PresetSelectionMode,
        val presetId: Long,
        val battleCount: Int,
    ) : StoredTypedActionPayload
    data class AdventureMap(
        val categoryId: String,
        val mapCode: String,
        val presetMode: PresetSelectionMode,
        val presetId: Long,
        val battleCount: Int,
        val settingIdentity: Long,
    ) : StoredTypedActionPayload
}

data class EncodedTypedAutomationAction(val json: String, val fingerprint: String)

@Component
class StoredTypedAutomationActionCodec(private val objectMapper: ObjectMapper) {
    fun encode(action: StoredTypedAutomationActionV1): EncodedTypedAutomationAction {
        validate(action)
        val json = objectMapper.writeValueAsString(action)
        val fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.toByteArray()))
        return EncodedTypedAutomationAction(json, fingerprint)
    }

    fun decode(schemaVersion: Int, json: String): StoredTypedAutomationActionV1 {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported typed automation action schema version: $schemaVersion" }
        return objectMapper.readValue(json, StoredTypedAutomationActionV1::class.java).also(::validate)
    }

    private fun validate(action: StoredTypedAutomationActionV1) {
        when (val payload = action.payload) {
            is StoredTypedActionPayload.QuestClaim -> require(payload.questCode.isNotBlank() && payload.actionNo.isNotBlank())
            is StoredTypedActionPayload.QuestAccept -> require(payload.questCode.isNotBlank() && payload.actionNo.isNotBlank())
            is StoredTypedActionPayload.QuestBattle -> require(payload.presetId > 0 && payload.battleCount in setOf(1, 3))
            is StoredTypedActionPayload.BattleMap -> require(payload.presetId > 0 && payload.battleCount in setOf(1, 3))
            is StoredTypedActionPayload.AdventureMap -> require(payload.presetId > 0 && payload.battleCount == 1)
        }
    }

    companion object { const val SCHEMA_VERSION = 1 }
}
