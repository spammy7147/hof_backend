package app.spammy.hof.automation.policy

import app.spammy.hof.automation.entity.AutomationModuleType
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class AutomationDecisionPolicyTest {
    private val policy = AutomationDecisionPolicy()

    @Test
    fun appliesTheApprovedPriorityOrder() {
        val keyBattle = QuestDecision.Battle(
            "0563",
            KeyQuestMapCandidate("Noble102", "저택 동관(복도)", null, 0),
            null,
        )
        val base = snapshot(
            priorityQuestDecision = keyBattle,
            timeCurrent = 96,
            union = UnionCandidate("union", 500),
            cooldown = map("cooldown"),
        )

        assertEquals(AutomationModuleType.KEY_QUEST, policy.decide(base).moduleType)
        assertEquals(AutomationModuleType.TIME_BURN, policy.decide(base.copy(priorityQuestDecision = null)).moduleType)
        assertEquals(
            AutomationModuleType.UNION,
            policy.decide(base.copy(priorityQuestDecision = null, timeCurrent = 90)).moduleType,
        )
        assertEquals(
            AutomationModuleType.COOLDOWN_ADVENTURE,
            policy.decide(base.copy(priorityQuestDecision = null, timeCurrent = 90, unionTarget = UnionCandidate("dead", 0))).moduleType,
        )
    }

    @Test
    fun allClaimableQuestsWinBeforePriorityAcceptanceAndExactThresholdSleeps() {
        val claim = quest("0999", QuestState.CLAIMABLE)
        val accept = quest("0563", QuestState.AVAILABLE)
        assertEquals(AutomationDecisionType.CLAIM_QUEST, policy.decide(snapshot(claimable = claim, acceptable = accept)).type)

        val sleep = policy.decide(snapshot(timeCurrent = 90, next = Instant.parse("2026-07-14T00:00:00Z")))
        assertEquals(AutomationDecisionType.SLEEP, sleep.type)
        assertEquals(Instant.parse("2026-07-14T00:00:00Z"), sleep.nextRunAt)
    }

    private fun snapshot(
        claimable: QuestSnapshot? = null,
        acceptable: QuestSnapshot? = null,
        priorityQuestDecision: QuestDecision? = null,
        timeCurrent: Int = 0,
        union: UnionCandidate? = null,
        cooldown: AutomationMapCandidate? = null,
        next: Instant? = null,
    ) = AutomationSnapshot(
        claimableQuest = claimable,
        acceptablePriorityQuest = acceptable,
        priorityQuestDecision = priorityQuestDecision,
        timeCurrent = timeCurrent,
        timeMax = 100,
        timeThresholdPercent = 90,
        timeMap = map("normal"),
        unionTarget = union,
        readyCooldownMap = cooldown,
        readyDailyMap = null,
        normalQuestDecision = null,
        earliestNextRunAt = next,
    )

    private fun quest(id: String, state: QuestState) = QuestSnapshot(id, id, state, null, id)
    private fun map(code: String) = AutomationMapCandidate(code, code, 0)
}
