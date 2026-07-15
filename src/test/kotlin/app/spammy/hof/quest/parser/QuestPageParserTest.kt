package app.spammy.hof.quest.parser

import app.spammy.hof.quest.model.QuestMissionType
import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestSection
import app.spammy.hof.quest.model.QuestState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals("0571:0", quests.first().missions.single().key)
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
}
