package app.spammy.hof.quest.parser

import app.spammy.hof.quest.model.QuestProgress
import app.spammy.hof.quest.model.QuestState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuestPageParserTest {
    private val parser = QuestPageParser()

    @Test
    fun parsesTheRealHofTableShapeWithoutGuessingFromABattleCount() {
        val html = checkNotNull(javaClass.classLoader.getResource("fixtures/quest/quest-states.html")).readText()

        val quests = parser.parse(html).associateBy { it.questId }

        assertEquals(QuestState.CLAIMABLE, quests.getValue("0563").state)
        assertEquals(QuestProgress(current = 5, target = 5), quests.getValue("0563").progress)
        assertEquals("east-key", quests.getValue("0563").actionNo)
        assertEquals(QuestState.ACTIVE, quests.getValue("0571").state)
        assertEquals(QuestProgress(current = 7, target = 20), quests.getValue("0571").progress)
        assertEquals(QuestState.AVAILABLE, quests.getValue("0171").state)
        assertEquals("unknown-key", quests.getValue("0171").actionNo)
        assertEquals(QuestState.COMPLETED, quests.getValue("0351").state)
        assertEquals(QuestState.UNAVAILABLE, quests.getValue("0999").state)
        assertNull(quests.getValue("0999").progress)
    }

    @Test
    fun alsoParsesQuestBlocksWhenThePageWrapsRowsDifferently() {
        val html = """
            <section data-quest-id="0563">
              <h3>[0563] 저택 동관 열쇠 수집</h3>
              <p>Key Keeper - [ 2 / 5 ]</p>
              <a href="?menu=quest&action=complete&no=563">완료</a>
            </section>
            <section data-quest-id="0571">
              <h3>[0571] 저택 서관 열쇠 수집</h3>
              <p>Killer Maid - [ 0 / 20 ]</p>
            </section>
        """.trimIndent()

        val quests = parser.parse(html).associateBy { it.questId }

        assertEquals(QuestState.CLAIMABLE, quests.getValue("0563").state)
        assertEquals(QuestState.ACTIVE, quests.getValue("0571").state)
        assertEquals(QuestProgress(0, 20), quests.getValue("0571").progress)
    }
}
