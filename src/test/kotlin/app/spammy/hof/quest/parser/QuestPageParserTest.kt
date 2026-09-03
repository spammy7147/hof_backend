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
    fun `완전한 비대상 퀘스트 목록과 오류 화면을 구분한다`() {
        val completeHtml = checkNotNull(
            javaClass.classLoader.getResource("fixtures/quest/quest-complete-empty.html"),
        ).readText()

        val complete = parser.parseObservation(
            completeHtml,
            "https://hof.zerosic.com/index.php?menu=quest",
        )
        val error = parser.parseObservation(
            "<html><body><h1>Temporary upstream error</h1></body></html>",
            "https://hof.zerosic.com/index.php?menu=quest",
        )

        assertTrue(complete.complete)
        assertEquals(listOf("0999"), complete.quests.map { it.displayCode })
        assertFalse(error.complete)
        assertTrue(error.quests.isEmpty())
    }

    @Test
    fun `오류 상태와 페이지 종료 표식이 잘린 skeleton은 대상 부재를 증명하지 못한다`() {
        val completeHtml = checkNotNull(
            javaClass.classLoader.getResource("fixtures/quest/quest-complete-empty.html"),
        ).readText()

        val serverError = parser.parseObservation(
            completeHtml,
            "https://hof.zerosic.com/index.php?menu=quest",
            statusCode = 500,
        )
        val truncated = parser.parseObservation(
            completeHtml.substringBefore("<div id=\"foot\""),
            "https://hof.zerosic.com/index.php?menu=quest",
        )

        assertFalse(serverError.complete)
        assertFalse(truncated.complete)
    }

    @Test
    fun `필수 영역의 알 수 없는 data row는 target absence로 축약하지 않는다`() {
        val completeHtml = checkNotNull(
            javaClass.classLoader.getResource("fixtures/quest/quest-complete-empty.html"),
        ).readText()
        val unknownRow = completeHtml.replace(
            "[0999] 비대상 대기 의뢰",
            "식별 규칙이 바뀐 비대상 대기 의뢰",
        )

        val observation = parser.parseObservation(
            unknownRow,
            "https://hof.zerosic.com/index.php?menu=quest",
        )

        assertFalse(observation.complete)
    }

    @Test
    fun `rowspan이 소유한 continuation row는 완전한 quest block으로 인정한다`() {
        val completeHtml = checkNotNull(
            javaClass.classLoader.getResource("fixtures/quest/quest-complete-empty.html"),
        ).readText()
        val multiRow = completeHtml.replace(
            "<tr><td class=\"td7s\">[0999] 비대상 대기 의뢰</td><td>미션 : 즉시 완료</td>" +
                "<td>1일 후 가능</td><td>-</td><td>-</td></tr>",
            "<tr><td class=\"td7s\" rowspan=\"2\">[0999] 비대상 대기 의뢰</td>" +
                "<td>연속 행 시작</td><td>1일 후 가능</td><td>-</td><td>-</td></tr>" +
                "<tr><td colspan=\"4\">미션 : 즉시 완료</td></tr>",
        )

        val observation = parser.parseObservation(
            multiRow,
            "https://hof.zerosic.com/index.php?menu=quest",
        )

        assertTrue(observation.complete)
        assertEquals(1, observation.quests.size)
        assertEquals(QuestMissionType.IMMEDIATE, observation.quests.single().missions.single().type)
    }

    @Test
    fun `필수 퀘스트 영역 하나가 잘린 응답은 대상 부재를 증명하지 못한다`() {
        val completeHtml = checkNotNull(
            javaClass.classLoader.getResource("fixtures/quest/quest-complete-empty.html"),
        ).readText()
        val partialHtml = completeHtml.replace(
            Regex("<h4>대기중인 퀘스트 목록</h4>\\s*<table>.*?</table>", RegexOption.DOT_MATCHES_ALL),
            "",
        )

        val observation = parser.parseObservation(
            partialHtml,
            "https://hof.zerosic.com/index.php?menu=quest",
        )

        assertFalse(observation.complete)
        assertTrue(observation.quests.isEmpty())
    }

    @Test
    fun `quest identity survives action disappearance and distinguishes duplicate display codes`() {
        val actionable = parser.parse(
            """
            <div id="contents"><h4>수락 가능 퀘스트</h4><table><tr>
              <td class="td7s">[0351] 마을 지하 수로</td>
              <td><a href="?action=get&amp;no=351">수락</a></td>
            </tr></table></div>
            """.trimIndent(),
        ).single()
        val waiting = parser.parse(
            """
            <div id="contents"><h4>대기중인 퀘스트</h4><table><tr>
              <td class="td7s">[0351]  마을   지하 수로 </td><td>쿨타임</td>
            </tr></table></div>
            """.trimIndent(),
        ).single()
        val duplicateCode = parser.parse(
            """
            <div id="contents"><h4>대기중인 퀘스트</h4><table>
              <tr><td class="td7s">[0000] 중앙 마력로 1탑</td></tr>
              <tr><td class="td7s">[0000] 중앙 마력로 3탑</td></tr>
            </table></div>
            """.trimIndent(),
        )

        assertEquals("0351", actionable.displayCode)
        assertEquals(actionable.questKey, waiting.questKey)
        assertEquals("351", actionable.actionNo)
        assertNull(waiting.actionNo)
        assertEquals(2, duplicateCode.map { it.questKey }.toSet().size)
    }

    @Test
    fun sectionHeadingsDetermineMembershipInsteadOfMissionProgress() {
        val active = quests.single { it.name == "저택 서관 열쇠 수집" }
        val available = quests.single { it.name == "즉시 보고" }
        val waiting = quests.single { it.name == "봉인된 의뢰" }

        assertEquals(QuestSection.ACTIVE, active.section)
        assertEquals(QuestState.ACTIVE, active.state)
        assertEquals(QuestSection.AVAILABLE, available.section)
        assertEquals(QuestState.AVAILABLE, available.state)
        assertEquals(QuestSection.WAITING, waiting.section)
        assertEquals(QuestState.UNAVAILABLE, waiting.state)
        assertEquals(QuestProgress(12, 30), waiting.missions.single().progress)
    }

    @Test
    fun preservesOriginalQuestAndMissionOrderWithStableKeys() {
        assertEquals(
            listOf("0571", "0563", "0800", "0801", "0810", "WRP1", "0171", "FORM", "0999", "0351", "0999", "0171"),
            quests.map { it.displayCode },
        )
        assertEquals((0..11).toList(), quests.map { it.sourceOrder })
        assertTrue(quests.none { it.displayCode == "NAV0" })
        assertNotEquals("0571:0", quests.first().missions.single().key)
    }

    @Test
    fun capturesNormalizedQuestNameAndActionInformation() {
        val quest = quests.single { it.displayCode == "0563" }

        assertEquals("저택 동관 열쇠 수집", quest.name)
        assertEquals("east-key", quest.actionNo)
        assertEquals(QuestState.CLAIMABLE, quest.state)
    }

    @Test
    fun usesQuestActionNumberAsIdentityAndKeepsPlaceholderRowsDistinct() {
        val parsed = parser.parse(
            """
            <div id="contents">
              <h4>진행중인 퀘스트</h4>
              <table>
                <tr>
                  <td class="td7s">[0915] 수련 - 고대의 태양신 정복</td>
                  <td>미션 : 즉시 완료</td>
                  <td><a href="?menu=quest&amp;action=get&amp;no=915">수락</a></td>
                </tr>
                <tr>
                  <td class="td7s">[0915] 동일한 표시 코드를 쓰는 다른 퀘스트</td>
                  <td>미션 : 즉시 완료</td>
                  <td><a href="?menu=quest&amp;action=get&amp;no=916">수락</a></td>
                </tr>
                <tr>
                  <td class="td7s">[0000] 중앙 마력로의 열쇠(제 1탑)</td>
                  <td>미션 : 맵 클리어( Castle In The Sky- 천공성(제 1탑) )</td>
                  <td><a href="?menu=quest&amp;action=complete&amp;no=R610">보상받기</a></td>
                </tr>
                <tr>
                  <td class="td7s">[0000] 중앙 마력로의 열쇠(제 3탑)</td>
                  <td>미션 : 맵 클리어( Castle In The Sky- 천공성(제 3탑) )</td>
                  <td><a href="?menu=quest&amp;action=complete&amp;no=R612">보상받기</a></td>
                </tr>
                <tr>
                  <td class="td7s">[0000] 중앙 마력로의 열쇠(제 4탑)</td>
                  <td>미션 : 맵 클리어( Castle In The Sky- 천공성(제 4탑) ) - [ 0 / 1 ]</td>
                  <td>-</td>
                </tr>
              </table>
            </div>
            """.trimIndent(),
        )

        assertEquals(listOf("0915", "0915", "0000", "0000", "0000"), parsed.map { it.displayCode })
        assertEquals(listOf("915", "916", "R610", "R612", null), parsed.map { it.actionNo })
    }

    @Test
    fun parsesMonsterProgressAndPreservesNormalizedTargetText() {
        val mission = quests.single { it.displayCode == "0571" }.missions.single()

        assertEquals(QuestMissionType.MONSTER_KILL, mission.type)
        assertEquals("Killer Maid", mission.target)
        assertEquals(QuestProgress(current = 12, required = 30), mission.progress)
        assertFalse(mission.completable)
    }

    @Test
    fun parsesRewardColumnInDisplayOrderWithoutMissionOrDialogue() {
        val byCode = quests.associateBy { it.displayCode }

        assertEquals(
            listOf(
                "아이템( Red Potion(99회 사용가능) ) x2",
                "아이템( Blue Potion(99회 사용가능) ) x2",
            ),
            byCode.getValue("0571").rewards,
        )
        assertTrue(byCode.getValue("0801").rewards.isEmpty())
        assertTrue(byCode.getValue("0571").rewards.none { it.contains("미션") || it.contains("길드 마스터") })
    }

    @Test
    fun ignoresRewardHeadersOwnedByNestedTables() {
        val quest = parser.parse(
            """
            <div id="contents">
              <h4>진행중인 퀘스트</h4>
              <table><tr>
                <td class="td7s">[NEST] 중첩 헤더</td>
                <td>미션 : 즉시 완료<table><tr><th>퀘스트명</th><th>보상</th></tr></table></td>
              </tr></table>
            </div>
            """.trimIndent(),
        ).single()

        assertTrue(quest.rewards.isEmpty())
    }

    @Test
    fun standaloneDataQuestContainerExcludesNestedTableMissionAndAction() {
        val quest = parser.parse(
            """
            <div id="contents">
              <h4>진행중인 퀘스트</h4>
              <section data-quest-id="WRP2">
                <h3>[WRP2] 독립 컨테이너</h3>
                <table>
                  <tr>
                    <td>미션 : 몬스터 처치( Nested Guard ) - [ 1 / 1 ]</td>
                    <td><a href="?action=complete&amp;no=nested-action">완료</a></td>
                  </tr>
                </table>
                <p>미션 : 아이템 반납( Direct Token ) - [ 0 / 1 ]</p>
                <a href="?action=complete&amp;no=direct-action">완료</a>
              </section>
            </div>
            """.trimIndent(),
        ).single()

        assertEquals(listOf(QuestMissionType.ITEM_TURN_IN), quest.missions.map { it.type })
        assertEquals("Direct Token", quest.missions.single().target)
        assertEquals(QuestProgress(0, 1), quest.missions.single().progress)
        assertEquals("direct-action", quest.actionNo)
        assertEquals(QuestState.CLAIMABLE, quest.state)
    }

    @Test
    fun actionNoIgnoresNestedFormInputBeforeDirectInput() {
        val quest = parser.parse(
            """
            <div id="contents">
              <h4>진행중인 퀘스트</h4>
              <table><tr>
                <td class="td7s">[FRM2] 폼 소유 경계</td>
                <td>
                  <form>
                    <table><tr><td><input name="no" value="nested-wrong"></td></tr></table>
                    <input name="no" value="direct-right">
                    <button name="complete">완료</button>
                  </form>
                </td>
              </tr></table>
            </div>
            """.trimIndent(),
        ).single()

        assertEquals("direct-right", quest.actionNo)
        assertEquals(QuestState.CLAIMABLE, quest.state)
    }

    @Test
    fun splitsAdjacentSectionRewardElements() {
        val quest = parser.parse(
            """
            <div id="contents">
              <h4>진행중인 퀘스트</h4>
              <table>
                <tr><th>퀘스트명</th><th>미션</th><th>보상</th><th>행동</th></tr>
                <tr>
                  <td class="td7s">[BLCK] 블록 보상</td><td>미션 : 즉시 완료</td>
                  <td><section>First</section><section>Second</section></td><td>-</td>
                </tr>
              </table>
            </div>
            """.trimIndent(),
        ).single()

        assertEquals(listOf("First", "Second"), quest.rewards)
    }

    @Test
    fun parsesProductionTdRewardHeaderWithoutTreatingRewardTextAsMission() {
        val quest = productionRewardQuests().single { it.displayCode == "0105" }

        assertEquals(
            listOf("아이템( Red Potion(99회 사용가능) ) x2", "미션 포인트 x3"),
            quest.rewards,
        )
        assertTrue(quest.missions.isEmpty())
    }

    @Test
    fun parsesHeaderlessLegacyRewardColumnWithoutTreatingRewardTextAsMission() {
        val quest = productionRewardQuests().single { it.displayCode == "LGCY" }

        assertEquals(listOf("Gold x10", "미션 포인트 x1"), quest.rewards)
        assertTrue(quest.missions.isEmpty())
    }

    @Test
    fun parsesRewardValueAfterRowLocalTdLabelWithinOwningTable() {
        val quest = productionRewardQuests().single { it.displayCode == "CELL" }

        assertEquals(listOf("명성 x2", "미션 포인트 x4"), quest.rewards)
        assertEquals(listOf(QuestMissionType.IMMEDIATE), quest.missions.map { it.type })
    }

    @Test
    fun parsesLastCellRewardAfterRowLocalTdLabelWithoutLeakingIntoMissions() {
        val quest = productionRewardQuests().single { it.displayCode == "LAST" }

        assertEquals(listOf("명성 x5", "미션 포인트 x6"), quest.rewards)
        assertEquals(listOf(QuestMissionType.IMMEDIATE), quest.missions.map { it.type })
    }

    @Test
    fun parsesProductionSiblingRowsAsBoundedQuestBlocks() {
        val parsed = productionRowBlockQuests()
        val byCode = parsed.associateBy { it.displayCode }

        assertEquals(listOf("0105", "0571", "0900", "0901", "0902"), parsed.map { it.displayCode })

        val support = byCode.getValue("0105")
        assertEquals("포션 지원", support.name)
        assertEquals(listOf("Red Potion x2", "Blue Potion x2"), support.rewards)
        assertEquals(listOf(QuestMissionType.ITEM_TURN_IN), support.missions.map { it.type })
        assertEquals("Potion Bottle", support.missions.single().target)
        assertEquals(QuestProgress(0, 1), support.missions.single().progress)

        val maid = byCode.getValue("0571")
        assertEquals("메이드 토벌", maid.name)
        assertEquals(listOf("Fund $15,000", "미션 포인트 x3"), maid.rewards)
        assertEquals(
            listOf(QuestMissionType.MONSTER_KILL, QuestMissionType.MAP_CLEAR),
            maid.missions.map { it.type },
        )
        assertEquals(listOf("Killer Maid", "Maid Hall"), maid.missions.map { it.target })
        assertEquals(QuestProgress(12, 30), maid.missions[0].progress)
        assertNull(maid.missions[1].progress)
        assertEquals("maid", maid.actionNo)
        assertEquals(QuestState.CLAIMABLE, maid.state)
    }

    @Test
    fun excludesDialogueRewardsAndNestedRowsFromSiblingBlockMissions() {
        val byCode = productionRowBlockQuests().associateBy { it.displayCode }

        assertEquals(setOf("0105", "0571", "0900", "0901", "0902"), byCode.keys)
        assertEquals(1, byCode.getValue("0105").missions.size)
        assertEquals(2, byCode.getValue("0571").missions.size)
        assertTrue(
            byCode.values
                .flatMap { it.missions }
                .none { it.target == "Nested Ghost" || it.type == QuestMissionType.OTHER },
        )
    }

    @Test
    fun appendsHeaderlessExplicitContinuationRewardOnlyToOwningBlock() {
        val byCode = productionRowBlockQuests().associateBy { it.displayCode }

        assertEquals(listOf("Gold x10"), byCode.getValue("0900").rewards)
        assertTrue(byCode.getValue("0901").rewards.isEmpty())
        assertTrue(
            byCode.values
                .flatMap { it.rewards }
                .none { it.contains("명시 보상 연속 행") || it.contains("다음 퀘스트") },
        )
    }

    @Test
    fun idlessQuestStyleCellDoesNotEndOwningSiblingBlock() {
        val quest = productionRowBlockQuests().single { it.displayCode == "0900" }

        assertEquals(listOf(QuestMissionType.IMMEDIATE), quest.missions.map { it.type })
        assertEquals("after-idless-cell", quest.actionNo)
        assertEquals(QuestState.CLAIMABLE, quest.state)
    }

    @Test
    fun classlessDirectIdCellStartsAndOwnsItsSiblingBlock() {
        val byCode = productionRowBlockQuests().associateBy { it.displayCode }
        val prior = byCode.getValue("0901")

        assertEquals(listOf(QuestMissionType.IMMEDIATE), prior.missions.map { it.type })
        assertTrue(prior.rewards.isEmpty())
        assertNull(prior.actionNo)

        val classless = byCode.getValue("0902")
        assertEquals("클래스 없는 시작", classless.name)
        assertEquals(listOf(QuestMissionType.ITEM_TURN_IN), classless.missions.map { it.type })
        assertEquals("Classless Token", classless.missions.single().target)
        assertEquals(QuestProgress(0, 1), classless.missions.single().progress)
        assertEquals(listOf("Silver Coin x2"), classless.rewards)
        assertEquals("classless-start", classless.actionNo)
        assertEquals(QuestState.CLAIMABLE, classless.state)
    }

    @Test
    fun classifiesMapClearAndOtherMissionText() {
        val mapMission = quests.single { it.displayCode == "0800" }.missions.single()
        val otherMission = quests.single { it.displayCode == "0801" }.missions.single()

        assertEquals(QuestMissionType.MAP_CLEAR, mapMission.type)
        assertEquals("Castle In The Sky- 천공성(제 2탑)", mapMission.target)
        assertNull(mapMission.progress)
        assertEquals(QuestMissionType.OTHER, otherMission.type)
        assertEquals("Old Butler", otherMission.target)
    }

    @Test
    fun classifiesItemTurnInAndImmediateCompletableSemantics() {
        val itemMission = quests.single { it.displayCode == "0563" }.missions.single()
        val immediateMission = quests.single { it.name == "즉시 보고" }.missions.single()

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
        )

        assertEquals(2, parsed.size)
        assertEquals(QuestSection.WAITING, parsed.single { it.name == "현재 대기" }.section)
        assertEquals(QuestState.UNAVAILABLE, parsed.single { it.name == "현재 대기" }.state)
        assertEquals(1, parsed.single { it.name == "현재 대기" }.sourceOrder)
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
        assertTrue(parsed.none { it.displayCode == "NAV0" })
        assertEquals("0571", parsed.first().displayCode)
        assertEquals(0, parsed.first().sourceOrder)
        assertEquals(QuestSection.WAITING, parsed.single { it.name == "봉인된 의뢰" }.section)
        assertEquals(QuestSection.COMPLETED, parsed.single { it.displayCode == "0351" }.section)
        assertEquals(3, parsed.single { it.displayCode == "0810" }.missions.size)
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
        ).associateBy { it.displayCode }

        assertEquals("raw+value%ZZ", parsed.getValue("FORM").actionNo)
        assertEquals("quest+key space", parsed.getValue("URL1").actionNo)
        assertEquals("bad%ZZ", parsed.getValue("URL2").actionNo)
    }

    @Test
    fun parsesCompositeMissionCellsAndFormActions() {
        val parsed = edgeCaseQuests()
        val active = parsed.single { it.name == "현재 복합 의뢰" }

        assertEquals(
            listOf(QuestMissionType.MONSTER_KILL, QuestMissionType.MAP_CLEAR, QuestMissionType.ITEM_TURN_IN),
            active.missions.map { it.type },
        )
        assertEquals("Killer Maid", active.missions[0].target)
        assertEquals(QuestProgress(12, 30), active.missions[0].progress)
        assertEquals("Castle In The Sky- 천공성(제 2탑)", active.missions[1].target)
        assertEquals("Silver Key", active.missions[2].target)
        assertEquals("DUPB", active.actionNo)
        assertEquals("DUPA", parsed.single { it.name == "현재 수락 의뢰" }.actionNo)
    }

    @Test
    fun resolvesDuplicateQuestsByStatePriorityAndRetainsChosenDomOrder() {
        val parsed = edgeCaseQuests()
        val byName = parsed.associateBy { it.name }

        assertEquals(listOf("DUPA", "DONE", "WRAP", "DUPB", "OUTER", "DUPA", "DUPB"), parsed.map { it.displayCode })
        assertEquals((0..6).toList(), parsed.map { it.sourceOrder })
        assertEquals(QuestSection.COMPLETED, byName.getValue("완료 보관 의뢰").section)
        assertEquals(QuestSection.AVAILABLE, byName.getValue("현재 수락 의뢰").section)
        assertEquals(QuestState.AVAILABLE, byName.getValue("현재 수락 의뢰").state)
        assertEquals(QuestSection.ACTIVE, byName.getValue("현재 복합 의뢰").section)
        assertEquals(QuestState.CLAIMABLE, byName.getValue("현재 복합 의뢰").state)
        assertTrue(parsed.none { it.displayCode == "NESTED" })
        assertTrue(parsed.none { it.displayCode == "INNER" })
    }

    private fun edgeCaseQuests() = parser.parse(
        checkNotNull(
            javaClass.classLoader.getResource("fixtures/quest/quest-parser-edge-cases.html"),
        ).readText(),
    )

    private fun productionRewardQuests() = parser.parse(
        checkNotNull(
            javaClass.classLoader.getResource("fixtures/quest/quest-production-reward-shapes.html"),
        ).readText(),
    )

    private fun productionRowBlockQuests() = parser.parse(
        checkNotNull(
            javaClass.classLoader.getResource("fixtures/quest/quest-production-row-blocks.html"),
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
