package app.spammy.hof.automation.policy

import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutomationDecisionPolicyTest {
    private val policy = AutomationDecisionPolicy(
        keyQuestPolicy = KeyQuestPolicy(EastMansionMapPolicy()),
        adventureMapPolicy = AdventureMapPolicy(),
        selectedQuestPolicy = SelectedQuestPolicy(),
    )

    @Test
    fun `returns the first runnable decision in persisted module order`() {
        val snapshot = snapshot(
            modules = listOf(
                module(41, AutomationModuleType.DAILY_ADVENTURE, 0, maps = listOf(map("daily"))),
                module(42, AutomationModuleType.TIME_BURN, 1, threshold = 90, maps = listOf(map("time"))),
            ),
            states = listOf(state("daily"), state("time")),
            timeCurrent = 100,
        )

        val decision = policy.decide(snapshot)

        assertEquals(41L, decision.moduleConfigId)
        assertEquals("daily", decision.map?.mapCode)
    }

    @Test
    fun `skips an unavailable module and evaluates the next module`() {
        val snapshot = snapshot(
            modules = listOf(
                module(51, AutomationModuleType.TIME_BURN, 0, threshold = 95, maps = listOf(map("time"))),
                module(52, AutomationModuleType.DAILY_ADVENTURE, 1, maps = listOf(map("daily"))),
            ),
            states = listOf(state("time"), state("daily")),
            timeCurrent = 90,
        )

        val decision = policy.decide(snapshot)

        assertEquals(52L, decision.moduleConfigId)
        assertEquals("daily", decision.map?.mapCode)
    }

    @Test
    fun `evaluates two instances of the same type with independent maps`() {
        val snapshot = snapshot(
            modules = listOf(
                module(61, AutomationModuleType.TIME_BURN, 0, threshold = 80, maps = listOf(map("hidden"))),
                module(62, AutomationModuleType.TIME_BURN, 1, threshold = 80, maps = listOf(map("visible"))),
            ),
            states = listOf(state("hidden", visible = false), state("visible")),
            timeCurrent = 80,
        )

        val decision = policy.decide(snapshot)

        assertEquals(62L, decision.moduleConfigId)
        assertEquals("visible", decision.map?.mapCode)
    }

    @Test
    fun `sleeps until the earliest next availability when no module is runnable`() {
        val early = NOW.plusSeconds(120)
        val late = NOW.plusSeconds(300)
        val snapshot = snapshot(
            modules = listOf(
                module(71, AutomationModuleType.COOLDOWN_ADVENTURE, 0, maps = listOf(map("late"))),
                module(72, AutomationModuleType.COOLDOWN_ADVENTURE, 1, maps = listOf(map("early"))),
            ),
            states = listOf(state("late", cooldownUntil = late), state("early", cooldownUntil = early)),
        )

        val decision = policy.decide(snapshot)

        assertEquals(AutomationDecisionType.SLEEP, decision.type)
        assertNull(decision.moduleConfigId)
        assertEquals(early, decision.nextRunAt)
    }

    @Test
    fun `quest module never claims or accepts an unconfigured quest`() {
        val snapshot = snapshot(
            modules = listOf(
                module(
                    81,
                    AutomationModuleType.OTHER_QUEST,
                    0,
                    quests = listOf(ConfiguredAutomationQuest("configured", 0, emptyList())),
                ),
            ),
            quests = listOf(
                quest("outside", QuestState.CLAIMABLE),
                quest("configured", QuestState.AVAILABLE),
            ),
        )

        val decision = policy.decide(snapshot)

        assertEquals(AutomationDecisionType.ACCEPT_QUEST, decision.type)
        assertEquals(81L, decision.moduleConfigId)
        assertEquals("configured", decision.questId)
    }

    @Test
    fun `key quest module without configured quests never falls back to global quest catalog`() {
        val snapshot = snapshot(
            modules = listOf(module(82, AutomationModuleType.KEY_QUEST, 0)),
            quests = listOf(quest("0563", QuestState.CLAIMABLE)),
        )

        val decision = policy.decide(snapshot)

        assertEquals(AutomationDecisionType.WAITING_CONFIG, decision.type)
        assertEquals(82L, decision.moduleConfigId)
        assertNull(decision.questId)
    }

    @Test
    fun `time burn runs at the exact module threshold and unsupported modules are ignored`() {
        val snapshot = snapshot(
            modules = listOf(
                module(90, AutomationModuleType.UNION, 0),
                module(91, AutomationModuleType.TIME_BURN, 1, threshold = 90, maps = listOf(map("time"))),
            ),
            states = listOf(state("time")),
            timeCurrent = 90,
        )

        val decision = policy.decide(snapshot)

        assertEquals(AutomationDecisionType.RUN_BATTLE, decision.type)
        assertEquals(91L, decision.moduleConfigId)
    }

    private fun snapshot(
        modules: List<AutomationModuleSnapshot>,
        states: List<AutomationMapState> = emptyList(),
        quests: List<QuestSnapshot> = emptyList(),
        timeCurrent: Int = 0,
    ) = AutomationSnapshot(
        accountId = 7L,
        modules = modules,
        accountStatus = AutomationAccountStatus(timeCurrent, 100),
        questState = quests,
        mapStates = states,
        now = NOW,
    )

    private fun module(
        id: Long,
        type: AutomationModuleType,
        priority: Int,
        threshold: Int? = null,
        maps: List<ConfiguredAutomationMap> = emptyList(),
        quests: List<ConfiguredAutomationQuest> = emptyList(),
    ) = AutomationModuleSnapshot(id, type, priority, threshold, maps, quests)

    private fun map(code: String) = ConfiguredAutomationMap(
        categoryId = "battle_map",
        mapCode = code,
        mapName = code,
        partyPresetId = 300L + code.length,
        executionOrder = 0,
    )

    private fun state(
        code: String,
        visible: Boolean = true,
        cooldownUntil: Instant? = null,
    ) = AutomationMapState(
        categoryId = "battle_map",
        mapCode = code,
        mapName = code,
        visible = visible,
        enabled = true,
        cooldownUntil = cooldownUntil,
        winRemaining = null,
        attemptRemaining = null,
        availableCount = null,
        keyCount = null,
    )

    private fun quest(id: String, state: QuestState) = QuestSnapshot(id, id, state, null, "action-$id")

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-14T00:00:00Z")
    }
}
