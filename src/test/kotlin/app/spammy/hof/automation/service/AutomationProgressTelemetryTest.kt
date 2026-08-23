package app.spammy.hof.automation.service

import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationWorkType
import app.spammy.hof.common.time.TimeProvider
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationProgressTelemetryTest {
    private var now = Instant.parse("2026-08-24T00:00:00Z")
    private val registry = SimpleMeterRegistry()
    private val telemetry = AutomationProgressTelemetry(registry, TimeProvider { now })

    @Test
    fun `같은 quest stale의 연속 횟수와 경과 시간을 추적한다`() {
        val trace = staleDecision(entryId = 11).trace.single().copy(
            workSessionId = 91,
            scope = "QUEST:quest-1",
            targetKey = "quest-1",
        )
        telemetry.recordDecision(
            1,
            staleDecision(entryId = 11).copy(trace = listOf(trace, trace.copy(sequence = 1))),
        )

        val snapshot = telemetry.snapshot(1)

        assertEquals(11, snapshot.staleEntryId)
        assertEquals(91, snapshot.staleWorkSessionId)
        assertEquals("QUEST:quest-1", snapshot.staleScope)
        assertEquals(now.plusSeconds(10), snapshot.staleNextAllowedAt)
        assertEquals(2, snapshot.consecutiveStaleCount)
        assertEquals(0, snapshot.staleElapsedSeconds)
        assertEquals(2.0, registry.counter("hof.automation.quest.stale").count())
    }

    @Test
    fun `진행 압력이 180초 동안 terminal action으로 해소되지 않으면 stall을 한 번 기록한다`() {
        telemetry.recordDecision(1, staleDecision(entryId = 11))
        now = now.plusSeconds(181)
        telemetry.recordDecision(1, staleDecision(entryId = 11))
        telemetry.recordDecision(1, staleDecision(entryId = 11))

        assertTrue(telemetry.snapshot(1).stalled)
        assertEquals(1.0, registry.counter("hof.automation.progress.stall", "reason", "NO_TERMINAL_ACTION").count())

        telemetry.recordTerminalAction(1, AutomationType.QUEST)

        assertFalse(telemetry.snapshot(1).stalled)
        assertEquals(1.0, registry.counter("hof.automation.action.terminal", "type", "QUEST").count())
    }

    @Test
    fun `행동이 반복 선택돼도 terminal 성공이 없으면 180초 stall을 기록한다`() {
        telemetry.recordDecision(1, runnableDecision(entryId = 8))
        now = now.plusSeconds(181)

        telemetry.recordDecision(1, runnableDecision(entryId = 8))

        assertTrue(telemetry.snapshot(1).stalled)
        assertEquals(
            1.0,
            registry.counter("hof.automation.progress.stall", "reason", "NO_TERMINAL_ACTION").count(),
        )
    }

    @Test
    fun `새 decision이 없어도 wall clock monitor가 180초 stall을 기록한다`() {
        telemetry.recordDecision(1, runnableDecision(entryId = 8))
        now = now.plusSeconds(181)

        telemetry.detectStalls()

        assertTrue(telemetry.snapshot(1).stalled)
        assertEquals(
            1.0,
            registry.counter("hof.automation.progress.stall", "reason", "NO_TERMINAL_ACTION").count(),
        )
    }

    @Test
    fun `scope release 뒤 다음 runnable 선택 지연과 due session 지연을 측정한다`() {
        telemetry.recordDecision(1, convergenceBlockedDecision(entryId = 7))
        telemetry.recordDueSession(AutomationWorkType.RAID, now.minusSeconds(45))
        now = now.plusSeconds(20)
        telemetry.recordDecision(1, runnableDecision(entryId = 8))

        assertEquals(
            45.0,
            registry.get("hof.automation.work.due.delay").tag("type", "RAID").summary().max(),
        )
        assertEquals(
            20.0,
            registry.get("hof.automation.scope.release.selection.delay").summary().max(),
        )
    }

    @Test
    fun `같은 판단의 blocked trace 시각부터 하위 runnable 선택까지 지연을 측정한다`() {
        telemetry.recordDecision(
            1,
            runnableDecision(entryId = 8).copy(
                trace = listOf(
                    AutomationEvaluationTrace(
                        sequence = 0,
                        entryId = 7,
                        type = AutomationType.RAID,
                        outcome = AutomationDecisionOutcome.WAITING,
                        reasonCode = "CONVERGENCE_SCOPE_BLOCKED",
                        message = "blocked",
                        observedAt = now.minusSeconds(24),
                    ),
                ),
            ),
        )

        assertEquals(
            24.0,
            registry.get("hof.automation.scope.release.selection.delay").summary().max(),
        )
    }

    @Test
    fun `Held trace도 같은 판단의 하위 runnable 선택 지연을 측정한다`() {
        telemetry.recordDecision(
            1,
            runnableDecision(entryId = 8).copy(
                trace = listOf(
                    AutomationEvaluationTrace(
                        sequence = 0,
                        entryId = 7,
                        type = AutomationType.QUEST,
                        outcome = AutomationDecisionOutcome.WAITING,
                        reasonCode = "OBSERVATION_GAP_HELD",
                        message = "held",
                        observedAt = now.minusSeconds(19),
                    ),
                ),
            ),
        )

        assertEquals(
            19.0,
            registry.get("hof.automation.scope.release.selection.delay").summary().max(),
        )
    }

    @Test
    fun `captcha battle gate만 열린 정상 대기는 terminal action stall 압력을 만들지 않는다`() {
        telemetry.recordDecision(
            1,
            AutomationCoordination.Idle(
                warnings = emptyList(),
                trace = listOf(
                    AutomationEvaluationTrace(
                        sequence = 0,
                        entryId = 7,
                        type = AutomationType.BATTLE_MAP,
                        outcome = AutomationDecisionOutcome.WAITING,
                        reasonCode = "CAPTCHA_BATTLE_GATE_BLOCKED",
                        message = "captcha",
                    ),
                ),
            ),
        )
        now = now.plusSeconds(181)

        telemetry.detectStalls()

        assertFalse(telemetry.snapshot(1).stalled)
        assertEquals(null, registry.find("hof.automation.progress.stall").counter())
    }

    @Test
    fun `ownership transfer를 reason별 counter로 기록한다`() {
        telemetry.recordOwnershipTransfer(AutomationOwnershipTransferReason.PRIORITY_YIELD, duplicateRepair = false)
        telemetry.recordOwnershipTransfer(AutomationOwnershipTransferReason.PRIORITY_YIELD, duplicateRepair = false)
        telemetry.recordOwnershipTransfer(AutomationOwnershipTransferReason.DUE_RESUME, duplicateRepair = true)

        assertEquals(
            2.0,
            registry.counter(
                "hof.automation.work.ownership.transfer",
                "reason",
                "PRIORITY_YIELD",
                "duplicate_repair",
                "false",
            ).count(),
        )
        assertEquals(
            1.0,
            registry.counter(
                "hof.automation.work.ownership.transfer",
                "reason",
                "DUE_RESUME",
                "duplicate_repair",
                "true",
            ).count(),
        )
    }

    private fun staleDecision(entryId: Long) = AutomationCoordination.Unavailable(
        nextRunAt = now.plusSeconds(10),
        warnings = emptyList(),
        trace = listOf(
            AutomationEvaluationTrace(
                sequence = 0,
                entryId = entryId,
                type = AutomationType.QUEST,
                outcome = AutomationDecisionOutcome.WAITING,
                reasonCode = "QUEST_PROGRESS_STALE",
                message = "stale",
                nextRunAt = now.plusSeconds(10),
            ),
        ),
    )

    private fun convergenceBlockedDecision(entryId: Long) = AutomationCoordination.Idle(
        warnings = emptyList(),
        trace = listOf(
            AutomationEvaluationTrace(
                sequence = 0,
                entryId = entryId,
                type = AutomationType.RAID,
                outcome = AutomationDecisionOutcome.WAITING,
                reasonCode = "CONVERGENCE_SCOPE_BLOCKED",
                message = "blocked",
            ),
        ),
    )

    private fun runnableDecision(entryId: Long) = AutomationCoordination.Runnable(
        entryId = entryId,
        action = HomeQuestAutomationAction(1, "quest", "quest", "action", HomeQuestAutomationActionType.ACCEPT),
        warnings = emptyList(),
    )
}
