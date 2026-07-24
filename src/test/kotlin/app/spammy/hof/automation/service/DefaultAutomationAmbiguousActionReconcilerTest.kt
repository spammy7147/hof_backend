package app.spammy.hof.automation.service

import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.automation.entity.PresetSelectionMode
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestMission
import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.service.QuestGatewayService
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertIs
import org.mockito.Mockito

class DefaultAutomationAmbiguousActionReconcilerTest {
    private val now = Instant.parse("2026-07-25T00:00:00Z")
    private val questGateway = Mockito.mock(QuestGatewayService::class.java)
    private val battleMapService = Mockito.mock(BattleMapService::class.java)
    private val battleHandler = Mockito.mock(BattleMapAutomationHandler::class.java)
    private val reconciler = DefaultAutomationAmbiguousActionReconciler(
        questGateway,
        battleMapService,
        battleHandler,
        HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
        TimeProvider { now },
    )

    @Test
    fun `accepted quest state confirms ambiguous accept without replay`() {
        Mockito.`when`(questGateway.load(7, HofRequestOrigin.AUTOMATION))
            .thenReturn(listOf(quest("Q-1", QuestState.ACTIVE, null)))

        assertIs<AmbiguousActionResolution.Applied>(reconciler.reconcile(7, questAccept()))
    }

    @Test
    fun `still available quest proves ambiguous accept was not applied`() {
        Mockito.`when`(questGateway.load(7, HofRequestOrigin.AUTOMATION))
            .thenReturn(listOf(quest("Q-1", QuestState.AVAILABLE, "accept-no")))

        assertIs<AmbiguousActionResolution.Resubmit>(reconciler.reconcile(7, questAccept()))
    }

    @Test
    fun `missing quest confirms ambiguous claim was applied`() {
        Mockito.`when`(questGateway.load(7, HofRequestOrigin.AUTOMATION)).thenReturn(emptyList())

        assertIs<AmbiguousActionResolution.Applied>(
            reconciler.reconcile(
                7,
                StoredTypedAutomationActionV1(
                    10,
                    "claim-1",
                    StoredTypedActionPayload.QuestClaim("Q-1", "claim-no"),
                ),
            ),
        )
    }

    @Test
    fun `advanced quest mission confirms ambiguous quest battle`() {
        Mockito.`when`(questGateway.load(7, HofRequestOrigin.AUTOMATION)).thenReturn(
            listOf(
                quest("Q-1", QuestState.ACTIVE, null).copy(
                    missions = listOf(
                        QuestMission(
                            key = "kill",
                            type = QuestMissionType.MONSTER_KILL,
                            target = "slime",
                            progress = QuestProgress(3, 5),
                            completable = false,
                        ),
                    ),
                ),
            ),
        )

        assertIs<AmbiguousActionResolution.Applied>(reconciler.reconcile(7, questBattle(2)))
    }

    @Test
    fun `unchanged active quest mission resubmits ambiguous quest battle`() {
        Mockito.`when`(questGateway.load(7, HofRequestOrigin.AUTOMATION)).thenReturn(
            listOf(
                quest("Q-1", QuestState.ACTIVE, null).copy(
                    missions = listOf(
                        QuestMission(
                            key = "kill",
                            type = QuestMissionType.MONSTER_KILL,
                            target = "slime",
                            progress = QuestProgress(2, 5),
                            completable = false,
                        ),
                    ),
                ),
            ),
        )

        assertIs<AmbiguousActionResolution.Resubmit>(reconciler.reconcile(7, questBattle(2)))
    }

    private fun questAccept() = StoredTypedAutomationActionV1(
        10,
        "accept-1",
        StoredTypedActionPayload.QuestAccept("Q-1", "accept-no"),
    )

    private fun questBattle(observedCurrent: Int) = StoredTypedAutomationActionV1(
        10,
        "quest-battle-1",
        StoredTypedActionPayload.QuestBattle(
            questCode = "Q-1",
            questCycle = "1",
            missionKey = "kill",
            missionType = QuestMissionType.MONSTER_KILL,
            categoryId = "battle_map",
            mapCode = "map-1",
            presetMode = PresetSelectionMode.PRIMARY,
            presetId = 301,
            battleCount = 1,
            battleRequest = battleRequest("battle_map", "map-1"),
            observedCurrent = observedCurrent,
            observedRequired = 5,
        ),
    )

    private fun quest(code: String, state: QuestState, actionNo: String?) = QuestSnapshot(
        questId = code,
        name = code,
        state = state,
        section = when (state) {
            QuestState.AVAILABLE -> QuestSection.AVAILABLE
            QuestState.COMPLETED -> QuestSection.COMPLETED
            QuestState.UNAVAILABLE -> QuestSection.WAITING
            QuestState.ACTIVE, QuestState.CLAIMABLE -> QuestSection.ACTIVE
        },
        sourceOrder = 0,
        missions = emptyList(),
        actionNo = actionNo,
    )

    private fun battleRequest(categoryId: String, mapCode: String, count: Int = 1) = RunBattleRequest(
        categoryId,
        mapCode,
        listOf("character-1"),
        listOf(BattlePatternLoadRequest("character-1", 1)),
        count,
    )
}
