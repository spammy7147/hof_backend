package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.service.BattleMapIdentityResolver
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.quest.service.QuestGatewayService
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito

class TypedLiveAutomationSnapshotLoaderTest {
    @Test
    fun `quest without configured maps refreshes battle and adventure alias categories`() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = HofAccountEntity(7, "login", "encrypted", now)
        val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
        val mapQuery = Mockito.mock(BattleMapQueryRepository::class.java)
        val mapService = Mockito.mock(BattleMapService::class.java)
        val presets = Mockito.mock(PartyPresetQueryRepository::class.java)
        val quest = Mockito.mock(QuestGatewayService::class.java)
        val identities = Mockito.mock(BattleMapIdentityResolver::class.java)
        val loader = TypedLiveAutomationSnapshotLoader(
            quest, typed, mapQuery, presets, identities, mapService, TimeProvider { now },
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)))
        Mockito.`when`(typed.findQuestSelections(10)).thenReturn(emptyList())
        Mockito.`when`(typed.findQuestMaps(emptyList())).thenReturn(emptyList())
        Mockito.`when`(quest.load(7)).thenReturn(emptyList())
        Mockito.`when`(mapQuery.findAllStatesForExecution(7)).thenReturn(emptyList())
        Mockito.`when`(presets.findAllByAccountId(7)).thenReturn(emptyList())
        Mockito.`when`(presets.findMembersByPresetIds(emptyList())).thenReturn(emptyList())

        loader.loadTyped(7)

        Mockito.verify(mapService).findMaps(7, "battle_map")
        Mockito.verify(mapService).findMaps(7, "adventure_map")
    }
}
