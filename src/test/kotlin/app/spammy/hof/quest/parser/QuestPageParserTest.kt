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
            listOf("0571", "0563", "0800", "0801", "0810", "WRP1", "0171", "FORM", "0999", "0351"),
            quests.map { it.questId },
        )
        assertEquals((0..9).toList(), quests.map { it.sourceOrder })
        assertTrue(quests.none { it.questId == "NAV0" })
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
    fun indistinguishableMissionsShareOnePersistedOverrideIdentity() {
        val missions = parser.parse(
            questWithMissions(
                """
                미션 : 몬스터 처치( Killer Maid ) - [ 1 / 5 ]<br>
                미션 : 몬스터 처치( Killer Maid ) - [ 2 / 5 ]
                """.trimIndent(),
            ),
        ).single().missions

        assertEquals(2, missions.size)
        assertEquals(missions[0].key, missions[1].key)
    }

    @Test
    fun semanticKeysIncludeRequiredCountAndStableQualifiersButExcludeCurrentProgress() {
        val originalKeysByCurrent = parser.parse(
            questWithMissions(
                """
                미션 : 몬스터 처치( Killer Maid ) - 서관 - [ 1 / 20 ]<br>
                미션 : 몬스터 처치( Killer Maid ) - 서관 - [ 2 / 30 ]<br>
                미션 : 몬스터 처치( Killer Maid ) - 야간 - [ 3 / 30 ]
                """.trimIndent(),
            ),
        ).single().missions.associate { it.progress!!.current to it.key }
        val changedMissions = parser.parse(
            questWithMissions(
                """
                미션 : 아이템 전달( Silver Key ) - [ 0 / 1 ]<br>
                미션 : 몬스터 처치( Killer Maid ) - 야간 - [ 3 / 30 ]<br>
                미션 : 몬스터 처치( Killer Maid ) - 서관 - [ 1 / 20 ]<br>
                미션 : 몬스터 처치( Killer Maid ) - 서관 - [ 2 / 30 ]
                """.trimIndent(),
            ),
        ).single().missions
        val changedKeysByCurrent = changedMissions
            .filter { it.type == QuestMissionType.MONSTER_KILL }
            .associate { it.progress!!.current to it.key }

        assertEquals(3, originalKeysByCurrent.values.toSet().size)
        assertEquals(originalKeysByCurrent, changedKeysByCurrent)
    }

    @Test
    fun waitingCurrentOccurrenceBeatsCompletedHistory() {
        val parsed = parser.parse(
            """
            <div id="contents">
              <h4>완료한 퀘스트</h4>
              <table><tr><td class="td7s">[DUPW] 과거 완료</td></tr></table>
              <h4>대기중인 퀘스트</h4>
              <table><tr><td class="td7s">[DUPW] 현재 대기</td></tr></table>
            </div>
            """.trimIndent(),
        ).single()

        assertEquals(QuestSection.WAITING, parsed.section)
        assertEquals(QuestState.UNAVAILABLE, parsed.state)
        assertEquals(1, parsed.sourceOrder)
    }

    @Test
    fun scopesQuestCandidatesToContentsAndUsesSanitizedFullPageFixture() {
        val html = checkNotNull(
            javaClass.classLoader.getResource("fixtures/quest/quest-sections-and-missions.html"),
        ).readText()
        val parsed = parser.parse(html)

        assertTrue(html.contains("SANITIZED_AUTHENTICATED_PAGE_SHAPE"))
        assertFalse(Regex("\\d{12,}").containsMatchIn(html))
        assertFalse(html.contains("PHPSESSID", ignoreCase = true))
        assertTrue(parsed.none { it.questId == "NAV0" })
        assertEquals("0571", parsed.first().questId)
        assertEquals(0, parsed.first().sourceOrder)
        assertEquals(QuestSection.WAITING, parsed.single { it.questId == "0999" }.section)
        assertEquals(QuestSection.COMPLETED, parsed.single { it.questId == "0351" }.section)
        assertEquals(3, parsed.single { it.questId == "0810" }.missions.size)
    }

    @Test
    fun decodesOnlyUrlParametersAndSurvivesMalformedEncoding() {
        val parsed = parser.parse(
            """
            <div id="contents">
              <h4>수락 가능 퀘스트</h4>
              <table>
                <tr>
                  <td class="td7s">[FORM] 폼 값</td>
                  <td><form><input name="no" value="raw+value%ZZ"><button name="get">수락</button></form></td>
                </tr>
                <tr><td class="td7s">[URL1] URL 값</td><td><a href="?action=get&amp;no=quest%2Bkey+space">수락</a></td></tr>
                <tr><td class="td7s">[URL2] 잘못된 URL 값</td><td><a href="?action=get&amp;no=bad%ZZ">수락</a></td></tr>
              </table>
            </div>
            """.trimIndent(),
        ).associateBy { it.questId }

        assertEquals("raw+value%ZZ", parsed.getValue("FORM").actionNo)
        assertEquals("quest+key space", parsed.getValue("URL1").actionNo)
        assertEquals("bad%ZZ", parsed.getValue("URL2").actionNo)
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
