package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import tools.jackson.module.kotlin.jacksonObjectMapper
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.automation.entity.*
import java.time.Instant
import tools.jackson.databind.SerializationFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder

class StoredTypedAutomationActionCodecTest {
    private val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())

    @Test
    fun `round trips a versioned battle action with stable fingerprint`() {
        val action = StoredTypedAutomationActionV1(
            entryId = 12,
            executionIdentity = "execution-1",
            payload = StoredTypedActionPayload.BattleMap(
                LocalDate.parse("2026-07-16"), "battle_map", "gb0", PresetSelectionMode.PRIMARY, 44, 3,
                RunBattleRequest("battle_map", "gb0", listOf("c1"), listOf(app.spammy.hof.battle.dto.BattlePatternLoadRequest("c1", 2)), 3),
            ),
        )
        val encoded = codec.encode(action)
        assertEquals(action, codec.decode(1, encoded.json))
        assertEquals(encoded.fingerprint, codec.encode(action).fingerprint)
        assertEquals(64, encoded.fingerprint.length)
        val changed = action.copy(payload = (action.payload as StoredTypedActionPayload.BattleMap).copy(
            battleRequest = RunBattleRequest("battle_map", "gb0", listOf("c2"), listOf(app.spammy.hof.battle.dto.BattlePatternLoadRequest("c2", 4)), 3),
        ))
        assertNotEquals(encoded.fingerprint, codec.encode(changed).fingerprint)
    }

    @Test
    fun `legacy payloads without display decode with null display`() {
        val legacyJson = """{"entryId":12,"executionIdentity":"execution","payload":{"kind":"QUEST_CLAIM","questKey":"quest","actionNo":"claim"}}"""

        val decoded = codec.decode(1, legacyJson)

        assertNull((decoded.payload as StoredTypedActionPayload.QuestClaim).display)
    }

    @Test
    fun `all current payload kinds remain decodable when display is absent`() {
        val request = RunBattleRequest(
            "battle_map", "gb0", listOf("c1"),
            listOf(app.spammy.hof.battle.dto.BattlePatternLoadRequest("c1", 2)), 1,
        )
        val payloads = listOf<StoredTypedActionPayload>(
            StoredTypedActionPayload.QuestClaim("quest", "claim"),
            StoredTypedActionPayload.QuestAccept("quest", "accept"),
            StoredTypedActionPayload.QuestBattle(
                "quest", "1", "mission", app.spammy.hof.quest.model.QuestMissionType.MONSTER_KILL,
                "battle_map", "gb0", PresetSelectionMode.PRIMARY, 44, 1, request,
            ),
            StoredTypedActionPayload.BattleMap(
                LocalDate.parse("2026-07-16"), "battle_map", "gb0", PresetSelectionMode.PRIMARY, 44, 1, request,
            ),
            StoredTypedActionPayload.AdventureMap(
                "battle_map", "gb0", PresetSelectionMode.PRIMARY, 44, 1, 55, request,
            ),
        )

        payloads.forEachIndexed { index, payload ->
            val encoded = codec.encode(StoredTypedAutomationActionV1(12, "execution-$index", payload))
            val legacyJson = encoded.json.replace(Regex(",?\\\"display\\\":null"), "")
            assertNull(codec.decode(1, legacyJson).payload.display)
        }
    }

    @Test
    fun `round trips quest and battle display snapshots`() {
        val questDisplay = StoredActionDisplay(
            questName = "초보자 임무",
            missionLabel = "몬스터 처치 · 슬라임",
            missionCurrent = 2,
            missionRequired = 5,
            mapName = "푸른 초원",
        )
        val claim = StoredTypedAutomationActionV1(
            12, "claim-display", StoredTypedActionPayload.QuestClaim("quest", "claim", questDisplay),
        )
        val request = RunBattleRequest(
            "battle_map", "gb0", listOf("c1"),
            listOf(app.spammy.hof.battle.dto.BattlePatternLoadRequest("c1", 2)), 1,
        )
        val battle = StoredTypedAutomationActionV1(
            13, "battle-display", StoredTypedActionPayload.BattleMap(
                LocalDate.parse("2026-07-16"), "battle_map", "gb0", PresetSelectionMode.PRIMARY, 44, 1, request,
                StoredActionDisplay(mapName = "푸른 초원"),
            ),
        )

        assertEquals(questDisplay, (codec.decode(1, codec.encode(claim).json).payload as StoredTypedActionPayload.QuestClaim).display)
        assertEquals("푸른 초원", (codec.decode(1, codec.encode(battle).json).payload as StoredTypedActionPayload.BattleMap).display?.mapName)
    }

    @Test
    fun `rejects unknown schema versions`() {
        assertFailsWith<IllegalArgumentException> { codec.decode(2, "{}") }
    }

    @Test
    fun `verifies original payload bytes even when verifier serialization settings differ`() {
        val action = StoredTypedAutomationActionV1(12, "execution", StoredTypedActionPayload.QuestClaim("quest", "claim"))
        val encoded = codec.encode(action)
        val account = HofAccountEntity(7, "login", "encrypted", Instant.EPOCH)
        val entry = AutomationEntryEntity(12, account, AutomationType.QUEST, 0, true, Instant.EPOCH, Instant.EPOCH)
        val row = TypedAutomationActionRunEntity(
            1, account, entry, action.executionIdentity, "QUEST_CLAIM", 1, encoded.json, encoded.fingerprint,
            TypedAutomationActionStatus.PREPARED, leaseToken = "token", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
        val alternateMapper = jacksonMapperBuilder().enable(SerializationFeature.INDENT_OUTPUT).build()
        assertNotEquals(encoded.json, alternateMapper.writeValueAsString(action))

        assertEquals(action, StoredTypedAutomationActionCodec(alternateMapper).verifyPersisted(row, 7))
    }
}
