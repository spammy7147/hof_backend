package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import tools.jackson.module.kotlin.jacksonObjectMapper

class StoredTypedAutomationActionCodecTest {
    private val codec = StoredTypedAutomationActionCodec(jacksonObjectMapper())

    @Test
    fun `round trips a versioned battle action with stable fingerprint`() {
        val action = StoredTypedAutomationActionV1(
            entryId = 12,
            executionIdentity = "execution-1",
            payload = StoredTypedActionPayload.BattleMap(
                LocalDate.parse("2026-07-16"), "battle_map", "gb0", PresetSelectionMode.PRIMARY, 44, 3,
            ),
        )
        val encoded = codec.encode(action)
        assertEquals(action, codec.decode(1, encoded.json))
        assertEquals(encoded.fingerprint, codec.encode(action).fingerprint)
        assertEquals(64, encoded.fingerprint.length)
    }

    @Test
    fun `rejects unknown schema versions`() {
        assertFailsWith<IllegalArgumentException> { codec.decode(2, "{}") }
    }
}
