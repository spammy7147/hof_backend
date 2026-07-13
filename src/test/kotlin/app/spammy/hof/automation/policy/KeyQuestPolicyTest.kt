package app.spammy.hof.automation.policy

import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KeyQuestPolicyTest {
    private val policy = KeyQuestPolicy(EastMansionMapPolicy())

    @Test
    fun claimsThenAcceptsPriorityQuestsInTheConfiguredPriorityOrder() {
        val quests = listOf(
            quest("0571", QuestState.AVAILABLE),
            quest("0563", QuestState.CLAIMABLE),
        )

        assertEquals("0563", assertIs<QuestDecision.Claim>(policy.decide(quests, emptyMap())).questId)
    }

    @Test
    fun activeEastMansionQuestUsesTheBalancedSelectedMap() {
        val configs = mapOf(
            "0563" to QuestExecutionConfig(
                maps = listOf(
                    KeyQuestMapCandidate("Noble1021", "보쉬의 방", 4, 1),
                    KeyQuestMapCandidate("Noble1022", "하인켈의 방", 7, 0),
                ),
            ),
        )

        val decision = assertIs<QuestDecision.Battle>(policy.decide(listOf(quest("0563", QuestState.ACTIVE)), configs))

        assertEquals("Noble1022", decision.map.mapCode)
        assertEquals(3, decision.blueprint?.battleCount)
    }

    @Test
    fun supplied0571DefaultCanRunButUnknownOrPartylessQuestWaitsForConfiguration() {
        val west = assertIs<QuestDecision.Battle>(
            policy.decide(listOf(quest("0571", QuestState.ACTIVE)), emptyMap()),
        )
        assertEquals("Noble201", west.map.mapCode)

        val culvert = assertIs<QuestDecision.WaitingConfig>(
            policy.decide(listOf(quest("0351", QuestState.ACTIVE)), emptyMap()),
        )
        assertEquals("전투에 사용할 파티를 선택해 주세요.", culvert.message)

        assertIs<QuestDecision.WaitingConfig>(
            policy.decide(listOf(quest("0171", QuestState.ACTIVE)), emptyMap()),
        )
    }

    private fun quest(id: String, state: QuestState) = QuestSnapshot(id, "quest-$id", state, null, id)
}
