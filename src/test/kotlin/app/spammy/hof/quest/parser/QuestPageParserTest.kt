package app.spammy.hof.quest.parser

import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestPageParserTest {
    private val parser = QuestPageParser()
    private val quests by lazy {
        val html = checkNotNull(
            javaClass.classLoader.getResource("fixtures/quest/quest-sections-and-missions.html"),
        ).readText()
        parser.parse(html)
    }

    @Test
    fun sectionHeadingsDetermineMembershipInsteadOfMissionProgress() {
        val byId = quests.associateBy { it.questId }

        assertEquals(QuestSection.ACTIVE, byId.getValue("0571").section)
        assertEquals(QuestState.ACTIVE, byId.getValue("0571").state)
        assertEquals(QuestSection.AVAILABLE, byId.getValue("0171").section)
        assertEquals(QuestState.AVAILABLE, byId.getValue("0171").state)
        assertEquals(QuestSection.WAITING, byId.getValue("0999").section)
        assertEquals(QuestState.UNAVAILABLE, byId.getValue("0999").state)
        assertEquals(QuestProgress(12, 30), byId.getValue("0999").missions.single().progress)
    }

    @Test
    fun preservesOriginalQuestAndMissionOrderWithStableKeys() {
        assertEquals(
            listOf("0571", "0563", "0800", "0801", "0171", "0999"),
            quests.map { it.questId },
        )
        assertEquals((0..5).toList(), quests.map { it.sourceOrder })
        assertNotEquals("0571:0", quests.first().missions.single().key)
    }

    @Test
    fun capturesNormalizedQuestNameAndActionInformation() {
        val quest = quests.single { it.questId == "0563" }

        assertEquals("저택 동관 열쇠 수집", quest.name)
        assertEquals("east-key", quest.actionNo)
        assertEquals(QuestState.CLAIMABLE, quest.state)
    }

    @Test
    fun parsesMonsterProgressAndPreservesNormalizedTargetText() {
        val mission = quests.single { it.questId == "0571" }.missions.single()

        assertEquals(QuestMissionType.MONSTER_KILL, mission.type)
        assertEquals("Killer Maid", mission.target)
        assertEquals(QuestProgress(current = 12, required = 30), mission.progress)
        assertFalse(mission.completable)
    }

    @Test
    fun classifiesMapClearAndOtherMissionText() {
        val mapMission = quests.single { it.questId == "0800" }.missions.single()
        val otherMission = quests.single { it.questId == "0801" }.missions.single()

        assertEquals(QuestMissionType.MAP_CLEAR, mapMission.type)
        assertEquals("Castle In The Sky- 천공성(제 2탑)", mapMission.target)
        assertNull(mapMission.progress)
        assertEquals(QuestMissionType.OTHER, otherMission.type)
        assertEquals("Old Butler", otherMission.target)
    }

    @Test
    fun classifiesItemTurnInAndImmediateCompletableSemantics() {
        val itemMission = quests.single { it.questId == "0563" }.missions.single()
        val immediateMission = quests.single { it.questId == "0171" }.missions.single()

        assertEquals(QuestMissionType.ITEM_TURN_IN, itemMission.type)
        assertEquals("Silver Key", itemMission.target)
        assertEquals(QuestProgress(3, 3), itemMission.progress)
        assertTrue(itemMission.completable)
        assertEquals(QuestMissionType.IMMEDIATE, immediateMission.type)
        assertNull(immediateMission.target)
        assertNull(immediateMission.progress)
        assertTrue(immediateMission.completable)
    }

    @Test
    fun semanticMissionKeysSurviveUnrelatedInsertionsReorderingAndProgressChanges() {
        val original = parser.parse(
            questWithMissions(
                """
                미션 : 몬스터 처치( Killer Maid ) - [ 1 / 5 ]<br>
                미션 : 지역 클리어( Castle In The Sky- 천공성(제 2탑) )
                """.trimIndent(),
            ),
        ).single().missions.associateBy { it.type to it.target }
        val changed = parser.parse(
            questWithMissions(
                """
                미션 : 아이템 전달( Silver Key ) - [ 0 / 1 ]<br>
                미션 : 지역 클리어( Castle In The Sky- 천공성(제 2탑) )<br>
                미션 : 몬스터 처치( Killer Maid ) - [ 4 / 5 ]
                """.trimIndent(),
            ),
        ).single().missions.associateBy { it.type to it.target }

        val monsterIdentity = QuestMissionType.MONSTER_KILL to "Killer Maid"
        val mapIdentity = QuestMissionType.MAP_CLEAR to "Castle In The Sky- 천공성(제 2탑)"
        assertEquals(original.getValue(monsterIdentity).key, changed.getValue(monsterIdentity).key)
        assertEquals(original.getValue(mapIdentity).key, changed.getValue(mapIdentity).key)
        assertEquals(QuestProgress(4, 5), changed.getValue(monsterIdentity).progress)
        assertEquals(changed.size, changed.values.map { it.key }.distinct().size)
    }

    @Test
    fun identicalSemanticMissionsReceiveDeterministicDuplicateSuffixes() {
        val missions = parser.parse(
            questWithMissions(
                """
                미션 : 몬스터 처치( Killer Maid ) - [ 1 / 5 ]<br>
                미션 : 몬스터 처치( Killer Maid ) - [ 2 / 5 ]
                """.trimIndent(),
            ),
        ).single().missions

        assertEquals(2, missions.size)
        assertNotEquals(missions[0].key, missions[1].key)
        assertTrue(missions[1].key.startsWith(missions[0].key))
    }

    @Test
    fun parsesCompositeMissionCellsAndFormActions() {
        val parsed = edgeCaseQuests().associateBy { it.questId }
        val active = parsed.getValue("DUPB")

        assertEquals(
            listOf(QuestMissionType.MONSTER_KILL, QuestMissionType.MAP_CLEAR, QuestMissionType.ITEM_TURN_IN),
            active.missions.map { it.type },
        )
        assertEquals("Killer Maid", active.missions[0].target)
        assertEquals(QuestProgress(12, 30), active.missions[0].progress)
        assertEquals("Castle In The Sky- 천공성(제 2탑)", active.missions[1].target)
        assertEquals("Silver Key", active.missions[2].target)
        assertEquals("claim-form", active.actionNo)
        assertEquals("accept-form", parsed.getValue("DUPA").actionNo)
    }

    @Test
    fun resolvesDuplicateQuestsByStatePriorityAndRetainsChosenDomOrder() {
        val parsed = edgeCaseQuests()
        val byId = parsed.associateBy { it.questId }

        assertEquals(listOf("DONE", "WRAP", "OUTER", "DUPA", "DUPB"), parsed.map { it.questId })
        assertEquals(listOf(1, 2, 4, 5, 6), parsed.map { it.sourceOrder })
        assertEquals(QuestSection.COMPLETED, byId.getValue("DONE").section)
        assertEquals(QuestSection.AVAILABLE, byId.getValue("DUPA").section)
        assertEquals(QuestState.AVAILABLE, byId.getValue("DUPA").state)
        assertEquals(QuestSection.ACTIVE, byId.getValue("DUPB").section)
        assertEquals(QuestState.CLAIMABLE, byId.getValue("DUPB").state)
        assertTrue("NESTED" !in byId)
        assertTrue("INNER" !in byId)
    }

    private fun edgeCaseQuests() = parser.parse(
        checkNotNull(
            javaClass.classLoader.getResource("fixtures/quest/quest-parser-edge-cases.html"),
        ).readText(),
    )

    private fun questWithMissions(missions: String) = """
        <div id="contents">
          <h4>진행중인 퀘스트</h4>
          <table><tr>
            <td class="td7s">[KEYS] 안정 키 검증</td>
            <td>$missions</td>
            <td class="td8s">-</td>
          </tr></table>
        </div>
    """.trimIndent()
}
