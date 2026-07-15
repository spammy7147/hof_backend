package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class AdventureMapAutomationHandlerTest {
    private val handler = AdventureMapAutomationHandler()

    @Test
    fun `cooldown exhausted and missing key maps are skipped before one runnable battle`() {
        val snapshot = snapshot(
            settings = listOf(setting(1, "cooldown", 0), setting(2, "exhausted", 1), setting(3, "no-key", 2), setting(4, "ready", 3)),
            states = listOf(
                state("cooldown", cooldownUntil = LATER),
                state("exhausted", availableCount = 0),
                state("no-key", requiresKey = true, keyCount = null),
                state("ready"),
            ),
        )

        val action = runnable(handler.evaluate(snapshot))

        assertEquals("ready", action.mapCode)
        assertEquals(1, action.battleCount)
        assertEquals(4, action.settingIdentity)
        assertEquals("execution-1", action.executionIdentity)
    }

    @Test
    fun `unchanged unlimited snapshot repeatedly chooses first map without round robin`() {
        val snapshot = snapshot(
            settings = listOf(setting(1, "first", 0), setting(2, "second", 1)),
            states = listOf(state("first"), state("second")),
        )

        assertEquals("first", runnable(handler.evaluate(snapshot)).mapCode)
        assertEquals("first", runnable(handler.evaluate(snapshot)).mapCode)
    }

    @Test
    fun `live cooldown state naturally advances and refreshed next day restores first map`() {
        val settings = listOf(setting(1, "first", 0), setting(2, "second", 1))
        val cooling = snapshot(settings, listOf(state("first", cooldownUntil = LATER), state("second")))
        val refreshed = snapshot(settings, listOf(state("first"), state("second")), now = NEXT_DAY)

        assertEquals("second", runnable(handler.evaluate(cooling)).mapCode)
        assertEquals("first", runnable(handler.evaluate(refreshed)).mapCode)
    }

    @Test
    fun `all temporary and unresolved map states are skipped`() {
        val mapCodes = listOf("setting-disabled", "hidden", "map-disabled", "unresolved", "daily0", "attempt0", "win0", "available0", "key0")
        val settings = mapCodes.mapIndexed { order, code -> setting(order.toLong(), code, order, enabled = code != "setting-disabled") }
        val states = listOf(
            state("setting-disabled"),
            state("hidden", visible = false),
            state("map-disabled", enabled = false),
            state("unresolved", resolved = false),
            state("daily0", dailyRemaining = 0),
            state("attempt0", attemptRemaining = 0),
            state("win0", winRemaining = 0),
            state("available0", availableCount = 0),
            state("key0", requiresKey = true, keyCount = 0),
        )

        assertSame(HandlerEvaluation.Skipped, handler.evaluate(snapshot(settings, states)))
    }

    @Test
    fun `active cooldown alone returns its exact next run time`() {
        val result = handler.evaluate(
            snapshot(
                settings = listOf(setting(1, "later", 0), setting(2, "earlier", 1)),
                states = listOf(state("later", cooldownUntil = LATER), state("earlier", cooldownUntil = SOONER)),
            ),
        )

        assertEquals(SOONER, assertIs<HandlerEvaluation.Unavailable>(result).nextRunAt)
    }

    @Test
    fun `invalid primary does not starve later explicit and primary is dynamically rebound per snapshot`() {
        val primary = setting(1, "primary", 0, mode = PresetSelectionMode.PRIMARY)
        val explicit = setting(2, "explicit", 1, mode = PresetSelectionMode.EXPLICIT, configuredPresetId = 20)
        val states = listOf(state("primary"), state("explicit"))
        val invalidPrimary = snapshot(
            listOf(primary, explicit),
            states,
            resolutions = mapOf(1L to invalid("primary unavailable"), 2L to valid(20)),
        )

        assertEquals(20, runnable(handler.evaluate(invalidPrimary)).presetId)
        val warning = handler.evaluate(snapshot(listOf(primary), listOf(state("primary")), resolutions = mapOf(1L to invalid("primary unavailable"))))
        assertEquals("primary unavailable", assertIs<HandlerEvaluation.ConfigurationWarning>(warning).message)

        val primaryA = runnable(handler.evaluate(snapshot(listOf(primary), listOf(state("primary")), resolutions = mapOf(1L to valid(10)))))
        val primaryB = runnable(handler.evaluate(snapshot(listOf(primary), listOf(state("primary")), resolutions = mapOf(1L to valid(11)))))
        assertEquals(10, primaryA.presetId)
        assertEquals(11, primaryB.presetId)
        assertEquals(PresetSelectionMode.PRIMARY, primaryB.presetMode)
        assertEquals(null, primary.preset.configuredPresetId)
    }

    @Test
    fun `invalid explicit selection warns without falling back to current primary`() {
        val explicit = setting(1, "explicit", 0, mode = PresetSelectionMode.EXPLICIT, configuredPresetId = 99)
        val result = handler.evaluate(
            snapshot(listOf(explicit), listOf(state("explicit")), resolutions = mapOf(1L to invalid("explicit preset unavailable"))),
        )

        assertEquals("explicit preset unavailable", assertIs<HandlerEvaluation.ConfigurationWarning>(result).message)
    }

    @Test
    fun `equal execution order uses category and map tie break independent of input order`() {
        val z = setting(1, "z-map", 0, category = "z-category")
        val b = setting(2, "b-map", 0, category = "a-category")
        val a = setting(3, "a-map", 0, category = "a-category")
        val states = listOf(state("z-map", category = "z-category"), state("b-map", category = "a-category"), state("a-map", category = "a-category"))

        assertEquals("a-map", runnable(handler.evaluate(snapshot(listOf(z, b, a), states))).mapCode)
        assertEquals("a-map", runnable(handler.evaluate(snapshot(listOf(b, a, z), states.reversed()))).mapCode)
    }

    @Test
    fun `evaluation does not mutate settings states or snapshot collections`() {
        val mutableSettings = mutableListOf(setting(2, "second", 1), setting(1, "first", 0))
        val mutableStates = mutableListOf(state("second"), state("first"))
        val mutableResolutions = mutableMapOf(2L to valid(20), 1L to valid(10))
        val snapshot = snapshot(mutableSettings, mutableStates, mutableResolutions)
        val expectedSettings = mutableSettings.toList()
        val expectedStates = mutableStates.toList()
        val expectedResolutions = mutableResolutions.toMap()

        handler.evaluate(snapshot)

        assertEquals(expectedSettings, mutableSettings)
        assertEquals(expectedStates, mutableStates)
        assertEquals(expectedResolutions, mutableResolutions)
    }

    private fun runnable(result: HandlerEvaluation): AdventureMapAutomationAction =
        assertIs<AdventureMapAutomationAction>(assertIs<HandlerEvaluation.Runnable>(result).action)

    private fun setting(
        identity: Long,
        mapCode: String,
        order: Int,
        enabled: Boolean = true,
        category: String = "adventure",
        mode: PresetSelectionMode = PresetSelectionMode.EXPLICIT,
        configuredPresetId: Long? = if (mode == PresetSelectionMode.EXPLICIT) identity + 100 else null,
    ) = AdventureMapAutomationSetting(identity, enabled, category, mapCode, AdventureMapPresetSelection(mode, configuredPresetId), order)

    private fun state(
        mapCode: String,
        category: String = "adventure",
        resolved: Boolean = true,
        visible: Boolean = true,
        enabled: Boolean = true,
        cooldownUntil: Instant? = null,
        dailyRemaining: Int? = null,
        attemptRemaining: Int? = null,
        winRemaining: Int? = null,
        availableCount: Int? = null,
        requiresKey: Boolean = false,
        keyCount: Int? = null,
    ) = AdventureMapRunnableState(
        categoryId = category,
        mapCode = mapCode,
        resolved = resolved,
        visible = visible,
        enabled = enabled,
        cooldownUntil = cooldownUntil,
        dailyRemaining = dailyRemaining,
        attemptRemaining = attemptRemaining,
        winRemaining = winRemaining,
        availableCount = availableCount,
        requiresKey = requiresKey,
        keyCount = keyCount,
    )

    private fun snapshot(
        settings: List<AdventureMapAutomationSetting>,
        states: List<AdventureMapRunnableState>,
        resolutions: Map<Long, AdventureMapPresetResolution> = settings.associate { setting ->
            setting.settingIdentity to valid(setting.preset.configuredPresetId ?: 10)
        },
        now: Instant = NOW,
    ) = AdventureMapAutomationSnapshot(7, settings, states, resolutions, "execution-1", now)

    private fun valid(id: Long) = AdventureMapPresetResolution.Valid(id)
    private fun invalid(message: String) = AdventureMapPresetResolution.Invalid(message)

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-16T00:00:00Z")
        val SOONER: Instant = Instant.parse("2026-07-16T00:05:00Z")
        val LATER: Instant = Instant.parse("2026-07-16T01:00:00Z")
        val NEXT_DAY: Instant = Instant.parse("2026-07-17T00:00:00Z")
    }
}
