package app.spammy.hof.automation.convergence

import app.spammy.hof.automation.raid.RaidRewardResultKind
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.town.fishing.model.FishingOutcome
import app.spammy.hof.town.fishing.model.FishingPrimaryAction
import app.spammy.hof.town.home.model.HomeQuestState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertIs

class ActionEvidencePoliciesTest {
    private val now = Instant.parse("2026-08-22T00:00:00Z")
    private val policies: ActionEvidencePolicies = DefaultActionEvidencePolicies()

    @Test
    fun `반복 퀘스트 보상 직접 응답의 대기 상태만 Applied이고 후속 진전은 Superseded다`() {
        val selection = selection(AutomationActionKind.QUEST_CLAIM, "quest-a")
        val waiting = QuestObservedState(
            fingerprint = "waiting",
            present = true,
            state = QuestState.UNAVAILABLE,
            actionNo = null,
        )

        assertIs<AutomationActionEvidence.DirectApplied>(
            policies.evaluate(selection, direct(waiting)),
        )
        assertIs<AutomationActionEvidence.StateAdvanced>(
            policies.evaluate(selection, fresh(waiting)),
        )
        assertIs<AutomationActionEvidence.SameState>(
            policies.evaluate(
                selection,
                fresh(
                    QuestObservedState("baseline", true, QuestState.CLAIMABLE, "claim-1"),
                ),
            ),
        )
    }

    @Test
    fun `캐시나 불완전 화면의 대상 부재는 결과 증거가 아니다`() {
        val absent = QuestObservedState("absent", false, null, null)
        val selection = selection(AutomationActionKind.QUEST_CLAIM, "quest-a")

        assertIs<AutomationActionEvidence.IncompleteObservation>(
            policies.evaluate(
                selection,
                ActionPolicyObservation(
                    capturedAt = now,
                    source = ActionEvidenceSource.ACCOUNT_OBSERVATION,
                    completeness = ObservationCompleteness.COMPLETE,
                    freshness = ObservationFreshness.CACHED,
                    state = absent,
                ),
            ),
        )
        assertIs<AutomationActionEvidence.IncompleteObservation>(
            policies.evaluate(
                selection,
                ActionPolicyObservation(
                    capturedAt = now,
                    source = ActionEvidenceSource.ACCOUNT_OBSERVATION,
                    completeness = ObservationCompleteness.WRONG_PAGE,
                    freshness = ObservationFreshness.FRESH,
                    state = absent,
                ),
            ),
        )
    }

    @Test
    fun `자택 공용 SUCCESS는 poststate 없이 Applied가 아니다`() {
        val selection = selection(AutomationActionKind.HOME_ACCEPT, "home-a")
        val unchanged = HomeQuestObservedState(
            fingerprint = "baseline",
            present = true,
            state = HomeQuestState.AVAILABLE,
            actionId = "accept-1",
        )

        assertIs<AutomationActionEvidence.SameState>(
            policies.evaluate(selection, direct(unchanged, genericSuccess = true)),
        )
        assertIs<AutomationActionEvidence.DirectApplied>(
            policies.evaluate(
                selection,
                direct(unchanged.copy(fingerprint = "active", state = HomeQuestState.ACTIVE, actionId = null)),
            ),
        )
    }

    @Test
    fun `전투는 직접 terminal round만 Applied이고 최신 맵 변화는 Superseded다`() {
        val selection = selection(AutomationActionKind.MAP_BATTLE, "battle-map")

        assertIs<AutomationActionEvidence.DirectApplied>(
            policies.evaluate(
                selection,
                direct(BattleObservedState("terminal", true, false, listOf("VICTORY"))),
            ),
        )
        assertIs<AutomationActionEvidence.StateAdvanced>(
            policies.evaluate(
                selection,
                fresh(BattleObservedState("gone", false, false, emptyList())),
            ),
        )
        assertIs<AutomationActionEvidence.SameState>(
            policies.evaluate(
                selection,
                fresh(BattleObservedState("baseline", true, true, emptyList())),
            ),
        )
    }

    @Test
    fun `terminal round 없는 유니온 맵 소멸과 개인 쿨다운은 superseded다`() {
        val selection = selection(AutomationActionKind.UNION_BATTLE, "union-entry")

        assertIs<AutomationActionEvidence.StateAdvanced>(
            policies.evaluate(selection, fresh(UnionObservedState("gone", mapPresent = false, personalCooldown = false))),
        )
        assertIs<AutomationActionEvidence.StateAdvanced>(
            policies.evaluate(selection, fresh(UnionObservedState("cooldown", mapPresent = true, personalCooldown = true))),
        )
        assertIs<AutomationActionEvidence.SameState>(
            policies.evaluate(selection, fresh(UnionObservedState("baseline", mapPresent = true, personalCooldown = false))),
        )
    }

    @Test
    fun `낚시 시작 회수 방해 전투 전이를 구분한다`() {
        val start = selection(AutomationActionKind.FISHING_START, "fishing-entry")
        assertIs<AutomationActionEvidence.DirectApplied>(
            policies.evaluate(
                start,
                direct(FishingObservedState("catch", FishingPrimaryAction.CATCH, 4, null, false)),
            ),
        )

        val catch = selection(AutomationActionKind.FISHING_CATCH, "fishing-entry-2")
        assertIs<AutomationActionEvidence.DirectApplied>(
            policies.evaluate(
                catch,
                direct(FishingObservedState("escaped", FishingPrimaryAction.START, 3, FishingOutcome.ESCAPED, false)),
            ),
        )
        val blocked = FishingObservedState("blocked", FishingPrimaryAction.NONE, 4, null, true)
        assertIs<AutomationActionEvidence.DirectApplied>(
            policies.evaluate(
                catch,
                direct(blocked),
            ),
        )
        assertIs<AutomationActionEvidence.StateAdvanced>(policies.evaluate(catch, fresh(blocked)))
    }

    @Test
    fun `레이드 시작은 전용 표식과 전투 중 상태가 함께 있어야 Applied다`() {
        val start = selection(AutomationActionKind.RAID_START, "raid-a")
        val inBattle = RaidObservedState("in-battle", joined = true, sharedStatus = "IN_BATTLE")

        assertIs<AutomationActionEvidence.DirectApplied>(
            policies.evaluate(start, direct(inBattle, actionSuccessMarker = true)),
        )
        assertIs<AutomationActionEvidence.StateAdvanced>(
            policies.evaluate(start, direct(inBattle, actionSuccessMarker = false)),
        )
        assertIs<AutomationActionEvidence.StateAdvanced>(
            policies.evaluate(
                start,
                fresh(RaidObservedState("external", joined = false, sharedStatus = "COMPLETED")),
            ),
        )
    }

    @Test
    fun `레이드 보상은 상시 버튼이 아니라 직접 보상 결과로 Applied를 판단한다`() {
        val reward = selection(AutomationActionKind.RAID_REWARD, "raid-a")
        val completed = RaidObservedState(
            fingerprint = "completed",
            joined = true,
            sharedStatus = "COMPLETED",
            rewardAvailable = true,
            rewardResult = RaidRewardResultKind.NOTHING_AVAILABLE,
        )

        assertIs<AutomationActionEvidence.DirectApplied>(
            policies.evaluate(reward, direct(completed)),
        )
        assertIs<AutomationActionEvidence.StateAdvanced>(
            policies.evaluate(reward, direct(completed.copy(rewardResult = null))),
        )
    }

    @Test
    fun `terminal round 없는 레이드 개인 쿨다운은 내 전투 적용 증거가 아니다`() {
        val battle = selection(AutomationActionKind.RAID_BATTLE, "raid-battle")

        assertIs<AutomationActionEvidence.StateAdvanced>(
            policies.evaluate(
                battle,
                fresh(RaidObservedState("cooldown", joined = true, sharedStatus = "IN_BATTLE", personalCooldown = true)),
            ),
        )
        assertIs<AutomationActionEvidence.StateAdvanced>(
            policies.evaluate(
                battle,
                fresh(RaidObservedState("external", joined = false, sharedStatus = "COMPLETED")),
            ),
        )
    }

    @Test
    fun `검증된 raid cycle 완료는 모든 raid action의 applied 증거다`() {
        val raidActions = listOf(
            AutomationActionKind.RAID_RESET,
            AutomationActionKind.RAID_REGISTER,
            AutomationActionKind.RAID_START,
            AutomationActionKind.RAID_REWARD,
            AutomationActionKind.RAID_REFRESH,
            AutomationActionKind.RAID_BATTLE,
            AutomationActionKind.RAID_CYCLE_ABORT,
        )

        raidActions.forEach { actionKind ->
            val selection = selection(actionKind, actionKind.name)
            assertIs<AutomationActionEvidence.DirectApplied>(
                policies.evaluate(
                    selection,
                    ActionPolicyObservation(
                        capturedAt = now,
                        source = ActionEvidenceSource.LIFECYCLE_RESULT,
                        completeness = ObservationCompleteness.COMPLETE,
                        freshness = ObservationFreshness.FRESH,
                        state = LifecycleResultObservedState(
                            fingerprint = "raid-cycle-finished",
                            actionKind = actionKind,
                            resultKind = "RaidCycleFinished",
                        ),
                    ),
                ),
            )
        }
    }

    private fun selection(kind: AutomationActionKind, key: String) = SelectedAutomationAction(
        entryId = 12L,
        executionIdentity = "execution-$key",
        actionKind = kind,
        scope = AutomationIsolationScope(
            when (kind) {
                AutomationActionKind.QUEST_ACCEPT, AutomationActionKind.QUEST_CLAIM, AutomationActionKind.QUEST_BATTLE ->
                    AutomationIsolationScopeKind.QUEST_TARGET
                AutomationActionKind.HOME_ACCEPT, AutomationActionKind.HOME_CLAIM -> AutomationIsolationScopeKind.HOME_TARGET
                AutomationActionKind.MAP_BATTLE, AutomationActionKind.ADVENTURE_BATTLE ->
                    AutomationIsolationScopeKind.BATTLE_COOLDOWN_SCOPE
                AutomationActionKind.UNION_BATTLE -> AutomationIsolationScopeKind.UNION_ENTRY
                AutomationActionKind.FISHING_START,
                AutomationActionKind.FISHING_CATCH,
                AutomationActionKind.FISHING_OBSTRUCTION_BATTLE,
                -> AutomationIsolationScopeKind.FISHING_ENTRY
                else -> AutomationIsolationScopeKind.RAID_ENTRY
            },
            key,
        ),
        policyVersion = "convergence-v1",
        baselineFingerprint = "baseline",
    )

    private fun direct(
        state: ActionObservedState,
        genericSuccess: Boolean = false,
        actionSuccessMarker: Boolean = false,
    ) = ActionPolicyObservation(
        capturedAt = now,
        source = ActionEvidenceSource.DIRECT_RESPONSE,
        completeness = ObservationCompleteness.COMPLETE,
        freshness = ObservationFreshness.FRESH,
        state = state,
        genericSuccess = genericSuccess,
        actionSuccessMarker = actionSuccessMarker,
    )

    private fun fresh(state: ActionObservedState) = ActionPolicyObservation(
        capturedAt = now,
        source = ActionEvidenceSource.ACCOUNT_OBSERVATION,
        completeness = ObservationCompleteness.COMPLETE,
        freshness = ObservationFreshness.FRESH,
        state = state,
    )
}
