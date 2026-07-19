package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.battle.model.BattleMapKeyMode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class AdventureMapAutomationHandlerTest {
    private val handler = AdventureMapAutomationHandler()

    @Test
    fun `cooldown exhausted and zero key maps are skipped before one runnable battle`() {
        val snapshot = snapshot(
            settings = listOf(setting(1, "cooldown", 0), setting(2, "exhausted", 1), setting(3, "no-key", 2), setting(4, "ready", 3)),
            states = listOf(
                state("cooldown", cooldownUntil = LATER),
                state("exhausted", availableCount = 0),
                state("no-key", keyCount = 0),
                state("ready", mapName = "Ready map"),
            ),
        )

        val action = runnable(handler.evaluate(snapshot))

        assertEquals("ready", action.mapCode)
        assertEquals(1, action.battleCount)
        assertEquals(4, action.settingIdentity)
        assertEquals("execution-4", action.executionIdentity)
        assertEquals("Ready map", action.mapName)
    }

    @Test
    fun `unchanged unlimited snapshot repeatedly chooses first map without round robin`() {
        val snapshot = snapshot(
            settings = listOf(setting(1, "first", 0), setting(2, "second", 1)),
            states = listOf(state("first"), state("second")),
        )

        val firstEvaluation = handler.evaluate(snapshot)
        val secondEvaluation = handler.evaluate(snapshot)
        assertEquals("first", runnable(firstEvaluation).mapCode)
        assertEquals(firstEvaluation, secondEvaluation)
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
            state("key0", keyCount = 0),
        )

        assertSame(HandlerEvaluation.Skipped, handler.evaluate(snapshot(settings, states)))
    }

    @Test
    fun `null and positive key counts remain runnable while any known zero key count is skipped`() {
        val settings = listOf(setting(1, "zero", 0), setting(2, "positive", 1), setting(3, "unknown", 2))

        assertEquals(
            "positive",
            runnable(handler.evaluate(snapshot(settings, listOf(state("zero", keyCount = 0), state("positive", keyCount = 1), state("unknown"))))).mapCode,
        )
        assertEquals("unknown", runnable(handler.evaluate(snapshot(listOf(settings[2]), listOf(state("unknown"))))).mapCode)
    }

    @Test
    fun `visible unlimited map without a count runs while hidden limited map with keys is skipped`() {
        val unlimited = setting(1, "unlimited", 0)
        assertEquals(
            "unlimited",
            runnable(
                handler.evaluate(
                    snapshot(
                        listOf(unlimited),
                        listOf(state("unlimited", keyMode = BattleMapKeyMode.UNLIMITED)),
                    ),
                ),
            ).mapCode,
        )

        val hidden = setting(2, "hidden", 0)
        assertSame(
            HandlerEvaluation.Skipped,
            handler.evaluate(
                snapshot(
                    listOf(hidden),
                    listOf(state("hidden", visible = false, keyMode = BattleMapKeyMode.LIMITED, keyCount = 10)),
                ),
            ),
        )
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
    fun `duplicate live state identity warns deterministically and does not starve later unique map`() {
        val duplicate = setting(1, "duplicate", 0)
        val unique = setting(2, "unique", 1)
        val duplicateStates = listOf(state("duplicate", visible = true), state("duplicate", visible = false))

        val runnable = handler.evaluate(snapshot(listOf(duplicate, unique), duplicateStates + state("unique")))
        assertEquals("unique", runnable(runnable).mapCode)

        val forward = handler.evaluate(snapshot(listOf(duplicate), duplicateStates))
        val reversed = handler.evaluate(snapshot(listOf(duplicate), duplicateStates.reversed()))
        assertEquals(forward, reversed)
        assertEquals(
            "Adventure map adventure/duplicate has duplicate live states.",
            assertIs<HandlerEvaluation.ConfigurationWarning>(forward).message,
        )
    }

    @Test
    fun `duplicate setting identity warns deterministically and does not starve later unique setting`() {
        val duplicateA = setting(1, "duplicate-a", 0)
        val duplicateB = setting(1, "duplicate-b", 1)
        val unique = setting(2, "unique", 2)
        val states = listOf(state("duplicate-a"), state("duplicate-b"), state("unique"))

        assertEquals("unique", runnable(handler.evaluate(snapshot(listOf(duplicateB, unique, duplicateA), states))).mapCode)
        val forward = handler.evaluate(snapshot(listOf(duplicateA, duplicateB), states))
        val reversed = handler.evaluate(snapshot(listOf(duplicateB, duplicateA), states.reversed()))
        assertEquals(forward, reversed)
        assertEquals(
            "Adventure setting identity 1 is duplicated.",
            assertIs<HandlerEvaluation.ConfigurationWarning>(forward).message,
        )
    }

    @Test
    fun `invalid blank oversized and reused execution identities are warnings while later unique identity runs`() {
        val settings = listOf(
            setting(1, "blank", 0),
            setting(2, "oversized", 1),
            setting(3, "reused-a", 2),
            setting(4, "reused-b", 3),
            setting(5, "unique", 4),
        )
        val identities = mapOf(1L to "", 2L to "x".repeat(129), 3L to "reused", 4L to "reused", 5L to "fresh")

        val action = runnable(handler.evaluate(snapshot(settings, settings.map { state(it.mapCode) }, executionIdentities = identities)))

        assertEquals("unique", action.mapCode)
        assertEquals("fresh", action.executionIdentity)
        val onlyInvalid = handler.evaluate(snapshot(listOf(settings[0]), listOf(state("blank")), executionIdentities = mapOf(1L to "")))
        assertEquals(
            "Adventure setting 1 has a missing or invalid execution identity.",
            assertIs<HandlerEvaluation.ConfigurationWarning>(onlyInvalid).message,
        )
        val oversized = handler.evaluate(
            snapshot(listOf(settings[1]), listOf(state("oversized")), executionIdentities = mapOf(2L to "x".repeat(129))),
        )
        assertEquals(
            "Adventure setting 2 has a missing or invalid execution identity.",
            assertIs<HandlerEvaluation.ConfigurationWarning>(oversized).message,
        )
        val reusedSettings = listOf(settings[2], settings[3])
        val reusedStates = listOf(state("reused-a"), state("reused-b"))
        val reusedIdentities = mapOf(3L to "reused", 4L to "reused")
        val reusedForward = handler.evaluate(snapshot(reusedSettings, reusedStates, executionIdentities = reusedIdentities))
        val reusedReversed = handler.evaluate(snapshot(reusedSettings.reversed(), reusedStates.reversed(), executionIdentities = reusedIdentities))
        assertEquals(reusedForward, reusedReversed)
        assertEquals(
            "Adventure execution identity 'reused' is reused.",
            assertIs<HandlerEvaluation.ConfigurationWarning>(reusedForward).message,
        )
    }

    @Test
    fun `multiple warnings select deterministic first sorted warning regardless input order`() {
        val later = setting(2, "z-map", 0, category = "z-category")
        val first = setting(1, "a-map", 0, category = "a-category")
        val resolutions = mapOf(1L to invalid("first warning"), 2L to invalid("later warning"))
        val states = listOf(state("z-map", category = "z-category"), state("a-map", category = "a-category"))

        val forward = handler.evaluate(snapshot(listOf(later, first), states, resolutions))
        val reversed = handler.evaluate(snapshot(listOf(first, later), states.reversed(), resolutions))

        assertEquals(forward, reversed)
        assertEquals("first warning", assertIs<HandlerEvaluation.ConfigurationWarning>(forward).message)
    }

    @Test
    fun `invalid preset warning takes precedence over cooldown unavailable`() {
        val invalidSetting = setting(1, "invalid", 0)
        val cooling = setting(2, "cooling", 1)
        val result = handler.evaluate(
            snapshot(
                listOf(invalidSetting, cooling),
                listOf(state("invalid"), state("cooling", cooldownUntil = LATER)),
                resolutions = mapOf(1L to invalid("preset warning"), 2L to valid(102)),
            ),
        )

        assertEquals("preset warning", assertIs<HandlerEvaluation.ConfigurationWarning>(result).message)
    }

    @Test
    fun `evaluation does not mutate settings states or snapshot collections`() {
        val mutableSettings = mutableListOf(setting(2, "second", 1), setting(1, "first", 0))
        val mutableStates = mutableListOf(state("second"), state("first"))
        val mutableResolutions = mutableMapOf(2L to valid(20), 1L to valid(10))
        val mutableExecutionIdentities = mutableMapOf(2L to "execution-2", 1L to "execution-1")
        val snapshot = snapshot(mutableSettings, mutableStates, mutableResolutions, mutableExecutionIdentities)
        val expectedSettings = mutableSettings.toList()
        val expectedStates = mutableStates.toList()
        val expectedResolutions = mutableResolutions.toMap()
        val expectedExecutionIdentities = mutableExecutionIdentities.toMap()

        handler.evaluate(snapshot)

        assertEquals(expectedSettings, mutableSettings)
        assertEquals(expectedStates, mutableStates)
        assertEquals(expectedResolutions, mutableResolutions)
        assertEquals(expectedExecutionIdentities, mutableExecutionIdentities)
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
        keyCount: Int? = null,
        keyMode: BattleMapKeyMode = if (keyCount == null) BattleMapKeyMode.UNKNOWN else BattleMapKeyMode.LIMITED,
        mapName: String? = null,
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
        keyMode = keyMode,
        keyCount = keyCount,
        mapName = mapName,
    )

    private fun snapshot(
        settings: List<AdventureMapAutomationSetting>,
        states: List<AdventureMapRunnableState>,
        resolutions: Map<Long, AdventureMapPresetResolution> = settings.associate { setting ->
            setting.settingIdentity to valid(setting.preset.configuredPresetId ?: 10)
        },
        executionIdentities: Map<Long, String> = settings.associate { it.settingIdentity to "execution-${it.settingIdentity}" },
        now: Instant = NOW,
    ) = AdventureMapAutomationSnapshot(7, settings, states, resolutions, executionIdentities, now)

    private fun valid(id: Long) = AdventureMapPresetResolution.Valid(id)
    private fun invalid(message: String) = AdventureMapPresetResolution.Invalid(message)

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-16T00:00:00Z")
        val SOONER: Instant = Instant.parse("2026-07-16T00:05:00Z")
        val LATER: Instant = Instant.parse("2026-07-16T01:00:00Z")
        val NEXT_DAY: Instant = Instant.parse("2026-07-17T00:00:00Z")
    }
}
