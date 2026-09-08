package app.spammy.hof.automation.convergence

import app.spammy.hof.automation.service.QuestAction
import app.spammy.hof.automation.service.RaidTownAutomationAction
import app.spammy.hof.automation.raid.RaidDecision
import app.spammy.hof.automation.raid.RaidDirective
import app.spammy.hof.automation.raid.RaidIntent
import app.spammy.hof.automation.raid.RaidIntentKind
import app.spammy.hof.town.raid.model.RaidAction
import app.spammy.hof.common.time.TimeProvider
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AutomationConvergenceSelectionTest {
    private val now = Instant.parse("2026-09-09T00:00:00Z")
    private val store = InMemoryConvergenceStore()
    private val module = DefaultAutomationActionConvergenceModule(store, TimeProvider { now })

    @ParameterizedTest
    @EnumSource(AutomationConvergenceMode::class)
    fun `후보 판정은 과거 보류를 유지하고 최신 관측 뒤 같은 모양의 새 사이클을 허용한다`(mode: AutomationConvergenceMode) {
        val original = QuestAction.Accept("quest-1", "accept-1")
        val preview = StoredActionConvergenceSelectionFactory().preview(11, original)
        val attempt = assertIs<ConvergenceDirective.Submit>(module.prepare(7,
            SelectedAutomationAction(11, "held-action", preview.actionKind, preview.scope,
                "automation-action-convergence-v1", requireNotNull(preview.baselineFingerprint)),
        )).attemptId
        module.record(attempt, AutomationActionEvidence.ResultUnobserved(now, "response lost"))

        assertEquals("CONVERGENCE_SCOPE_BLOCKED", module.openSelection(7, 11, mode = mode).block(original)?.reasonCode)
        assertNull(module.openSelection(7, 11, mode = mode).block(QuestAction.Accept("quest-1", "accept-2")))
        assertNull(module.openSelection(7, 11, mode = mode).block(original))
        assertEquals(ActionConvergenceResult.RESULT_UNOBSERVED, store.get(attempt)?.result)
    }

    @Test
    fun `신청 보류는 준비 관측을 먼저 요구하고 그 요청만으로 억제를 해제하지 않는다`() {
        val registration = RaidIntent.Town(11, "raid-1", "레이드", RaidIntentKind.REGISTER)
        val action = RaidTownAutomationAction(7, RaidAction.REGISTER, "raid-1", "raid-1", "레이드")
        val preview = StoredActionConvergenceSelectionFactory().preview(11, action)
        val attempt = assertIs<ConvergenceDirective.Submit>(module.prepare(7,
            SelectedAutomationAction(11, "held-registration", preview.actionKind, preview.scope,
                "automation-action-convergence-v1", requireNotNull(preview.baselineFingerprint)),
        )).attemptId
        module.record(attempt, AutomationActionEvidence.ResultUnobserved(now, "response lost"))

        val selection = module.openSelection(7, 11, mode = AutomationConvergenceMode.SHADOW)
        val next = selection.observeRaidDecision(RaidDecision(RaidDirective.Execute(registration)))
        val refresh = assertIs<RaidIntent.Town>(assertIs<RaidDirective.Execute>(next.directive).intent)

        assertEquals(RaidIntentKind.REFRESH, refresh.kind)
        assertNull(refresh.requestRaidId)
        assertEquals("RAID_REGISTRATION_RECOVERY_REFRESH", next.directive.reasonCode)
        assertEquals(setOf(preview.baselineFingerprint), store.findSuppressedBaselines(7)[preview.scope])
        assertNull(selection.block(RaidTownAutomationAction(7, RaidAction.REFRESH, null, "raid-1", "레이드")))
        assertEquals(setOf(preview.baselineFingerprint), store.findSuppressedBaselines(7)[preview.scope])
    }
}
