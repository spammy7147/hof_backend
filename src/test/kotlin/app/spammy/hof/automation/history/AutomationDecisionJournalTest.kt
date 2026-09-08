package app.spammy.hof.automation.history

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.HofAccountRepository
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.raid.RaidCycleOutcome
import app.spammy.hof.automation.raid.RaidCycleOutcomeKind
import app.spammy.hof.automation.raid.RaidCooldownSource
import app.spammy.hof.automation.service.*
import app.spammy.hof.common.time.TimeProvider
import jakarta.persistence.EntityManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import tools.jackson.module.kotlin.jacksonObjectMapper

@DataJpaTest
@ActiveProfiles("test")
class AutomationDecisionJournalTest {
    @Autowired private lateinit var accounts: HofAccountRepository
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var cycleCommands: AutomationDecisionCycleCommandRepository
    @Autowired private lateinit var eventCommands: AutomationDecisionEventCommandRepository
    private val now = Instant.parse("2026-08-12T01:00:00Z")

    @Test
    fun `낚시 반복은 다른 유형 성공과 재생성에 가려지지 않고 한 사건으로 보존된다`() {
        val account = account("fishing-repetition")
        val fishing = entry(account, AutomationType.FISHING, 0)
        val fishingId = fishing.id
        fun recovery(identity: String, remaining: Int = 5) {
            val journal = journal()
            val cycle = journal.appendDecision(account.id, AutomationCoordination.Idle(emptyList()))
            journal.appendActionResult(cycle, AutomationActionTrace(
                AutomationHistoryEventKind.SKIPPED, "FISHING_BATTLE_RECOVERED_FROM_START", "전투 재확인",
                fishingId, AutomationType.FISHING, "START", diagnosticContext = """{
                    "version":1,"source":"DIRECT_RESPONSE","executionIdentity":"$identity","recheckRequired":true,
                    "fishing":{"primaryAction":"NONE","remainingCasts":$remaining,"blockedByBattle":true,
                    "battleMapCode":"Fish03","battleObservationComplete":true}}
                """.trimIndent(),
            ))
            journal.appendActionResult(cycle, AutomationActionTrace(
                AutomationHistoryEventKind.ACTION_SUCCEEDED, "ACTION_SUCCEEDED", "유니온 성공", type = AutomationType.UNION,
            ))
            entityManager.flush()
            entityManager.clear()
        }
        fun warnings() = journal().page(account.id, AutomationHistoryQuery()).cycles.flatMap { it.events }
            .filter { it.reasonCode == "FISHING_RECOVERY_REPEATED" }
        recovery("first")
        recovery("second")
        assertTrue(warnings().isEmpty())
        recovery("third")
        val warning = warnings().single()
        assertEquals(AutomationHistoryEventKind.CONFIGURATION_WARNING, warning.kind)
        assertEquals("Fish03", warning.targetKey)
        val context = jacksonObjectMapper().readTree(assertNotNull(warning.diagnosticContext))
        assertEquals(3, context["repetition"]["count"].asInt())
        assertTrue(context["repetition"]["firstEventId"].asLong() > 0)
        recovery("fourth")
        assertEquals(listOf(warning.id), warnings().map { it.id })
        val latest = journal().page(account.id, AutomationHistoryQuery()).cycles.first().events.first()
        assertEquals(4, jacksonObjectMapper().readTree(assertNotNull(latest.diagnosticContext))["repetition"]["count"].asInt())

        // 확인된 낚시 진전과 설정 세대 변경은 각각 새 반복 사건의 경계다.
        val cycle = journal().appendDecision(account.id, AutomationCoordination.Idle(emptyList()))
        journal().appendActionResult(cycle, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "FISHING_CATCH_APPLIED", "잡기 완료", fishingId, AutomationType.FISHING, "CATCH",
        ))
        entityManager.flush(); entityManager.clear()
        recovery("after-catch-1"); recovery("after-catch-2")
        assertEquals(1, warnings().size)
        recovery("after-catch-3")
        assertEquals(2, warnings().size)
        entityManager.find(AutomationEntryEntity::class.java, fishingId).settingsRevision += 1
        entityManager.flush(); entityManager.clear()
        recovery("new-generation-1"); recovery("new-generation-2")
        assertEquals(2, warnings().size)
        recovery("new-generation-3")
        assertEquals(3, warnings().size)
        recovery("progress-1", remaining = 4); recovery("progress-2", remaining = 4)
        assertEquals(3, warnings().size)

        val stored = StoredTypedAutomationAction(fishingId, "external-recovery",
            StoredTypedActionPayload.FishingTown(app.spammy.hof.town.fishing.model.FishingAction.START,
                app.spammy.hof.town.fishing.model.FishingPrimaryAction.START, 4))
        val resolved = AutomationDecisionDiagnostics.fishingProbe(stored,
            """{"stage":"ACTION_RESULT","source":"LATEST_OBSERVATION","fishing":{"blockedByBattle":false,"battleObservationComplete":true}}""",
            app.spammy.hof.automation.convergence.AutomationActionEvidence.StateAdvanced(now, "external-battle-resolved"),
            app.spammy.hof.automation.convergence.ConvergenceDirective.ContinueSelection)
        assertEquals("FISHING_RECOVERY_RESOLVED", resolved.reasonCode)
        journal().appendResultObservation(account.id, resolved)
        entityManager.flush(); entityManager.clear()
        recovery("after-external-resolution-1", remaining = 4)
        recovery("after-external-resolution-2", remaining = 4)
        assertEquals(3, warnings().size)
        recovery("after-external-resolution-3", remaining = 4)
        assertEquals(4, warnings().size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `낚시 미선택의 해소 관측은 같은 평가 아래 한 번 표시된다`(selectOther: Boolean) {
        val account = account("fishing-resolution-$selectOther")
        val fishing = entry(account, AutomationType.FISHING, 0)
        val other = entry(account, AutomationType.HOME_QUEST, 1)
        val journal = journal()
        val first = journal.appendDecision(account.id, AutomationCoordination.Idle(emptyList()))
        journal.appendActionResult(first, AutomationActionTrace(
            AutomationHistoryEventKind.SKIPPED, "FISHING_BATTLE_RECOVERED_FROM_START", "전투 재확인",
            fishing.id, AutomationType.FISHING, "START",
        ))
        val trace = listOf(AutomationEvaluationTrace(
            0, fishing.id, AutomationType.FISHING, AutomationDecisionOutcome.SKIPPED,
            "FISHING_STATE_INCOMPLETE", "낚시 상태 대기",
            diagnosticContext = """{"stage":"ENTRY_EVALUATION","snapshot":{"primaryAction":"NONE","battleObservationComplete":true,"blockedByBattle":false}}""",
        ))
        val decision = if (selectOther) AutomationCoordination.Runnable(
            other.id, HomeQuestAutomationAction(account.id, "A", "하위 자택", "accept-A", HomeQuestAutomationActionType.ACCEPT), emptyList(), trace + AutomationEvaluationTrace(
                1, other.id, AutomationType.HOME_QUEST, AutomationDecisionOutcome.SELECTED, "RUNNABLE", "수락",
            ),
        ) else AutomationCoordination.Idle(emptyList(), trace)
        val id = journal.appendDecision(account.id, decision)
        entityManager.flush(); entityManager.clear()
        val cycle = journal.page(account.id, AutomationHistoryQuery()).cycles.single { it.id == id }
        assertEquals(if (selectOther) 2 else 1, cycle.topLevelStepCount)
        assertEquals(cycle.steps.size, cycle.steps.map { it.event.entryId }.distinct().size)
        val step = cycle.steps.single { it.event.entryId == fishing.id }
        assertEquals("FISHING_STATE_INCOMPLETE", step.event.reasonCode)
        assertEquals("FISHING_RECOVERY_RESOLVED", step.executionEvents.single().reasonCode)
    }

    @Test
    fun `판단 불가 진단은 다음 판단 이후에도 같은 이력에서 조회된다`() {
        val account = account("history-diagnostic")
        val battle = entry(account, AutomationType.BATTLE_MAP, 0)
        val diagnostic = """{"stage":"ENTRY_EVALUATION","snapshot":{"keyMode":"UNKNOWN","attemptRemaining":null}}"""
        val journal = journal()
        val cycleId = journal.appendDecision(account.id, AutomationCoordination.Idle(emptyList(), listOf(
            AutomationEvaluationTrace(
                0, battle.id, AutomationType.BATTLE_MAP, AutomationDecisionOutcome.SKIPPED,
                "NOT_RUNNABLE", "실행 조건을 확정하지 못했습니다.", diagnosticContext = diagnostic,
            ),
        )))
        journal.appendDecision(account.id, AutomationCoordination.Idle(emptyList()))
        entityManager.flush()
        entityManager.clear()

        val event = journal.page(account.id, AutomationHistoryQuery()).cycles.first { it.id == cycleId }.events.single()
        assertEquals(AutomationHistoryEventKind.SKIPPED, event.kind)
        assertEquals(diagnostic, event.diagnosticContext)
    }

    @Test
    fun `all ineligible entries record no action while scheduling another check`() {
        val account = account("history-skipped-conditions")
        val battle = entry(account, AutomationType.BATTLE_MAP, 0)
        val journal = journal()
        val nextRunAt = now.plusSeconds(120)
        journal.appendDecision(account.id, AutomationCoordination.Unavailable(
            nextRunAt = nextRunAt,
            warnings = emptyList(),
            trace = listOf(AutomationEvaluationTrace(
                0, battle.id, AutomationType.BATTLE_MAP, AutomationDecisionOutcome.SKIPPED,
                "COOLDOWN", "쿨다운 중이므로 건너뜁니다.", nextRunAt,
            )),
        ))
        entityManager.flush()
        entityManager.clear()

        val cycle = journal.page(account.id, AutomationHistoryQuery()).cycles.single()
        assertEquals(AutomationDecisionResult.IDLE, cycle.result)
        assertNull(cycle.selectedEntryId)
        assertEquals(AutomationHistoryEventKind.SKIPPED, cycle.events.single().kind)
        assertEquals(nextRunAt, cycle.events.single().nextRunAt)
    }

    @Test
    fun `pending action transition still records a wait`() {
        val account = account("history-pending-transition")
        val journal = journal()
        journal.appendDecision(account.id, AutomationCoordination.Unavailable(
            nextRunAt = now.plusSeconds(5),
            warnings = emptyList(),
            waitScope = AutomationWaitScope.HOLD_CURRENT_WORK,
        ))
        assertEquals(AutomationDecisionResult.WAITING, journal.page(account.id, AutomationHistoryQuery()).cycles.single().result)
    }

    @Test
    fun `stores evaluated order and isolates account history`() {
        val first = account("history-first")
        val second = account("history-second")
        val quest = entry(first, AutomationType.QUEST, 0)
        val union = entry(first, AutomationType.UNION, 1)
        entityManager.flush()
        val journal = journal()
        val decision = AutomationCoordination.Idle(emptyList(), listOf(
            AutomationEvaluationTrace(0, quest.id, AutomationType.QUEST, AutomationDecisionOutcome.SKIPPED, "QUEST_DONE", "완료"),
            AutomationEvaluationTrace(
                1, union.id, AutomationType.UNION, AutomationDecisionOutcome.WAITING, "UNION_COOLDOWN", "대기",
                now.plusSeconds(60), "BATTLE", "union/0001", "도적 소탕", 99,
                diagnosticKind = AutomationDiagnosticKind.RAID_LOCAL_SAFETY_GATE,
                cooldownSource = RaidCooldownSource.LOCAL_FALLBACK,
                impactScope = AutomationImpactScope.RAID_ONLY,
                releaseCondition = "최신 상태 재확인",
            ),
        ))

        val cycleId = journal.appendDecision(first.id, decision)
        journal.appendActionResult(cycleId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED, "DONE", "완료", union.id, AutomationType.UNION,
        ))
        entityManager.flush(); entityManager.clear()

        val page = journal.page(first.id, AutomationHistoryQuery())
        assertEquals(listOf(0, 1, 2), page.cycles.single().events.map { it.sequence })
        assertEquals(listOf("QUEST_DONE", "UNION_COOLDOWN", "DONE"), page.cycles.single().events.map { it.reasonCode })
        with(page.cycles.single().events[1]) {
            assertEquals("union/0001", targetKey)
            assertEquals("도적 소탕", targetName)
            assertEquals("BATTLE", actionKind)
            assertEquals(99, presetId)
            assertEquals(AutomationDiagnosticKind.RAID_LOCAL_SAFETY_GATE, diagnosticKind)
            assertEquals(RaidCooldownSource.LOCAL_FALLBACK, cooldownSource)
            assertEquals(AutomationImpactScope.RAID_ONLY, impactScope)
            assertEquals("최신 상태 재확인", releaseCondition)
        }
        assertNull(journal.page(second.id, AutomationHistoryQuery()).cycles.singleOrNull())
    }

    @Test
    fun `stores a resumed prepared action as a separately visible attempt`() {
        val account = account("history-resume")
        val raid = entry(account, AutomationType.RAID, 0)
        entityManager.flush()
        val journal = journal()

        journal.appendPreparedActionAttempt(account.id, AutomationActionTrace(
            kind = AutomationHistoryEventKind.WAITING,
            reasonCode = "AMBIGUOUS_RESULT_VERIFY",
            message = "전투 시작 단계의 적용 여부를 재확인합니다.",
            entryId = raid.id,
            type = AutomationType.RAID,
            actionKind = "START",
            targetKey = "Raid001",
            targetName = "고블린 전투 마차",
            nextRunAt = now.plusSeconds(10),
        ))
        entityManager.flush(); entityManager.clear()

        val cycle = journal.page(account.id, AutomationHistoryQuery()).cycles.single()
        assertEquals(AutomationDecisionResult.ACTION_SELECTED, cycle.result)
        assertEquals(raid.id, cycle.selectedEntryId)
        with(cycle.events.single()) {
            assertEquals("AMBIGUOUS_RESULT_VERIFY", reasonCode)
            assertEquals("START", actionKind)
            assertEquals("고블린 전투 마차", targetName)
            assertEquals(now.plusSeconds(10), nextRunAt)
        }
    }

    @Test
    fun `history snapshots the map group name used at decision time`() {
        val account = account("history-map-group")
        val group = entry(account, AutomationType.BATTLE_MAP, 0).also { it.displayName = "최우선 보스" }
        entityManager.flush()
        val journal = journal()

        journal.appendDecision(
            account.id,
            AutomationCoordination.Idle(
                emptyList(),
                listOf(
                    AutomationEvaluationTrace(
                        0,
                        group.id,
                        AutomationType.BATTLE_MAP,
                        AutomationDecisionOutcome.SKIPPED,
                        "DAILY_LIMIT",
                        "오늘 목표를 완료했습니다.",
                    ),
                ),
            ),
        )
        group.displayName = "나중 이름"
        entityManager.flush()
        entityManager.clear()

        val event = journal.page(account.id, AutomationHistoryQuery()).cycles.single().events.single()
        assertEquals("최우선 보스", event.entryDisplayName)
    }

    @Test
    fun `stores a manual raid handoff as a stable cycle outcome`() {
        val account = account("history-raid-handoff")
        val raid = entry(account, AutomationType.RAID, 0)
        entityManager.flush()
        val journal = journal()

        journal.appendRaidCycleOutcome(
            account.id,
            RaidCycleOutcome(raid.id, "Raid001", RaidCycleOutcomeKind.HANDED_OFF_MANUAL),
        )
        entityManager.flush()
        entityManager.clear()

        val event = journal.page(account.id, AutomationHistoryQuery()).cycles.single().events.single()
        assertEquals(AutomationHistoryEventKind.CYCLE_ABORTED, event.kind)
        assertEquals(RaidCycleOutcomeKind.HANDED_OFF_MANUAL.name, event.reasonCode)
        assertEquals("Raid001", event.targetKey)
    }

    @Test
    fun `maps a decision-time raid completion to cycle history instead of skipped`() {
        val account = account("history-raid-complete")
        val raid = entry(account, AutomationType.RAID, 0)
        entityManager.flush()
        val journal = journal()

        journal.appendDecision(
            account.id,
            AutomationCoordination.Idle(
                warnings = emptyList(),
                trace = listOf(
                    AutomationEvaluationTrace(
                        sequence = 0,
                        entryId = raid.id,
                        type = AutomationType.RAID,
                        outcome = AutomationDecisionOutcome.CYCLE_COMPLETED,
                        reasonCode = RaidCycleOutcomeKind.COMPLETED.name,
                        message = "레이드 사이클을 완료했습니다.",
                        targetKey = "Raid001",
                    ),
                ),
            ),
        )
        entityManager.clear()

        val event = journal.page(account.id, AutomationHistoryQuery()).cycles.single().events.single()
        assertEquals(AutomationHistoryEventKind.CYCLE_COMPLETED, event.kind)
        assertEquals(RaidCycleOutcomeKind.COMPLETED.name, event.reasonCode)
    }

    @Test
    fun `낚시 START CATCH와 방해 전투는 서로 다른 전역 판단 사이클에 기록된다`() {
        val account = account("history-fishing-cycle")
        val home = entry(account, AutomationType.HOME_QUEST, 0)
        val fishing = entry(account, AutomationType.FISHING, 1)
        entityManager.flush()
        val journal = journal()
        val castCycleId = journal.appendDecision(
            account.id,
            AutomationCoordination.Runnable(
                fishing.id,
                FishingTownAutomationAction(
                    account.id,
                    app.spammy.hof.town.fishing.model.FishingAction.START,
                    app.spammy.hof.town.fishing.model.FishingPrimaryAction.START,
                    18,
                ),
                emptyList(),
                listOf(
                    AutomationEvaluationTrace(
                        0,
                        home.id,
                        AutomationType.HOME_QUEST,
                        AutomationDecisionOutcome.SKIPPED,
                        "HOME_IDLE",
                        "실행할 자택 행동이 없습니다.",
                    ),
                    AutomationEvaluationTrace(
                        1,
                        fishing.id,
                        AutomationType.FISHING,
                        AutomationDecisionOutcome.SELECTED,
                        "ACTION_SELECTED",
                        "낚시를 선택했습니다.",
                    ),
                ),
            ),
        )
        journal.appendActionResult(castCycleId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED,
            "FISHING_START_APPLIED",
            "START 적용",
            fishing.id,
            AutomationType.FISHING,
            actionKind = "START",
        ))
        journal.appendActionResult(castCycleId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED,
            "FISHING_CATCH_APPLIED",
            "CATCH 적용",
            fishing.id,
            AutomationType.FISHING,
            actionKind = "CATCH",
        ))
        val battleCycleId = journal.appendDecision(
            account.id,
            AutomationCoordination.Runnable(
                fishing.id,
                BattleMapAutomationAction(
                    accountId = account.id,
                    progressDate = java.time.LocalDate.parse("2026-08-12"),
                    categoryId = "battle_map",
                    mapCode = "fish-monster",
                    presetMode = app.spammy.hof.automation.entity.PresetSelectionMode.PRIMARY,
                    presetId = 1,
                    battleCount = 1,
                    executionIdentity = "fishing-battle-1",
                    source = BattleAutomationActionSource.FISHING_AUTOMATION,
                ),
                emptyList(),
                listOf(
                    AutomationEvaluationTrace(
                        0,
                        home.id,
                        AutomationType.HOME_QUEST,
                        AutomationDecisionOutcome.SKIPPED,
                        "HOME_IDLE",
                        "실행할 자택 행동이 없습니다.",
                    ),
                    AutomationEvaluationTrace(
                        1,
                        fishing.id,
                        AutomationType.FISHING,
                        AutomationDecisionOutcome.SELECTED,
                        "ACTION_SELECTED",
                        "낚시 방해 전투를 선택했습니다.",
                    ),
                ),
            ),
        )
        journal.appendActionResult(battleCycleId, AutomationActionTrace(
            AutomationHistoryEventKind.ACTION_SUCCEEDED,
            "FISHING_OBSTRUCTION_BATTLE_APPLIED",
            "방해 전투 적용",
            fishing.id,
            AutomationType.FISHING,
            actionKind = "BATTLE",
        ))
        entityManager.flush()
        entityManager.clear()

        val cycles = journal.page(account.id, AutomationHistoryQuery()).cycles.sortedBy(AutomationHistoryCycle::id)

        assertEquals(2, cycles.size)
        assertEquals(listOf(2, 2), cycles.map(AutomationHistoryCycle::topLevelStepCount))
        assertEquals(listOf("HOME_IDLE", "ACTION_SELECTED"), cycles[0].steps.map { it.event.reasonCode })
        assertEquals(
            listOf("FISHING_START_APPLIED", "FISHING_CATCH_APPLIED"),
            cycles[0].steps.last().executionEvents.map(AutomationHistoryEvent::reasonCode),
        )
        assertEquals(listOf("HOME_IDLE", "ACTION_SELECTED"), cycles[1].steps.map { it.event.reasonCode })
        assertEquals(
            listOf("FISHING_OBSTRUCTION_BATTLE_APPLIED"),
            cycles[1].steps.last().executionEvents.map(AutomationHistoryEvent::reasonCode),
        )
    }

    private fun account(login: String) = accounts.save(HofAccountEntity(
        loginId = login, encryptedPassword = "encrypted", createdAt = now,
    ))

    private fun journal() = JpaAutomationDecisionJournal(
        entityManager,
        TimeProvider { now },
        cycleCommands,
        eventCommands,
    )

    private fun entry(account: HofAccountEntity, type: AutomationType, priority: Int): AutomationEntryEntity =
        AutomationEntryEntity(account = account, type = type, priority = priority, enabled = true, createdAt = now, updatedAt = now)
            .also(entityManager::persist)
}
