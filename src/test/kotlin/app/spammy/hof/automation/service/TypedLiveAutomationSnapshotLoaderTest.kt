package app.spammy.hof.automation.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.service.HofAccountService
import app.spammy.hof.account.service.HofSessionRecoveryService
import app.spammy.hof.automation.entity.AutomationEntryEntity
import app.spammy.hof.automation.entity.AutomationType
import app.spammy.hof.automation.entity.*
import app.spammy.hof.automation.repository.TypedAutomationQueryRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.battle.service.BattleMapIdentityResolver
import app.spammy.hof.battle.service.BattleMapService
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.party.repository.PartyPresetQueryRepository
import app.spammy.hof.quest.service.QuestGatewayService
import app.spammy.hof.status.dto.HofObservedStatusResponse
import app.spammy.hof.status.service.HofStatusSnapshotService
import java.time.Instant
import kotlin.test.Test
import org.mockito.Mockito
import app.spammy.hof.party.entity.*
import app.spammy.hof.character.entity.*
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager

class TypedLiveAutomationSnapshotLoaderTest {
    @Test
    fun `quest entry probe refreshes only quest categories`() {
        val now = Instant.parse("2026-07-23T00:00:00Z")
        val account = HofAccountEntity(7, "scoped-probe", "encrypted", now)
        val questEntry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
        val battleEntry = AutomationEntryEntity(11, account, AutomationType.BATTLE_MAP, 1, true, now, now)
        val adventureEntry = AutomationEntryEntity(12, account, AutomationType.ADVENTURE_MAP, 2, true, now, now)
        val selection = QuestAutomationSelectionEntity(20, questEntry, "quest-1", true, 0)
        val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
        val mapQuery = Mockito.mock(BattleMapQueryRepository::class.java)
        val mapService = Mockito.mock(BattleMapService::class.java)
        val presets = Mockito.mock(PartyPresetQueryRepository::class.java)
        val quest = Mockito.mock(QuestGatewayService::class.java)
        val loader = TypedLiveAutomationSnapshotLoader(
            quest,
            typed,
            mapQuery,
            presets,
            Mockito.mock(BattleMapIdentityResolver::class.java),
            mapService,
            TimeProvider { now },
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
            Mockito.mock(HofStatusSnapshotService::class.java),
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry, adventureEntry))
        Mockito.`when`(typed.findQuestSelectionsByEntryIds(listOf(10))).thenReturn(listOf(selection))
        Mockito.`when`(typed.findQuestMaps(listOf(20))).thenReturn(
            listOf(QuestAutomationMapEntity(21, selection, "mission", "quest-category", "quest-map", PresetSelectionMode.PRIMARY, null, 0, true)),
        )
        Mockito.`when`(typed.findBattleSettingsByEntryIds(listOf(11))).thenReturn(
            listOf(BattleAutomationMapEntity(22, battleEntry, "battle-category", "battle-map", 20, PresetSelectionMode.PRIMARY, null, 0)),
        )
        Mockito.`when`(typed.findAdventureSettingsByEntryIds(listOf(12))).thenReturn(
            listOf(AdventureAutomationMapEntity(23, adventureEntry, "adventure-category", "adventure-map", PresetSelectionMode.PRIMARY, null, 0)),
        )
        Mockito.`when`(presets.findAllByAccountId(7)).thenReturn(emptyList())
        Mockito.`when`(presets.findMembersByPresetIds(emptyList())).thenReturn(emptyList())
        Mockito.`when`(quest.load(7, HofRequestOrigin.AUTOMATION)).thenReturn(emptyList())
        Mockito.`when`(mapQuery.findAllStatesForExecution(7)).thenReturn(emptyList())

        val snapshot = loader.loadEntry(7, 10)

        assertEquals(AutomationType.QUEST, snapshot.type)
        Mockito.verify(mapService).findMaps(7, "quest-category", HofRequestOrigin.AUTOMATION)
        Mockito.verify(mapService, Mockito.never()).findMaps(7, "battle-category", HofRequestOrigin.AUTOMATION)
        Mockito.verify(mapService, Mockito.never()).findMaps(7, "adventure-category", HofRequestOrigin.AUTOMATION)
        Mockito.verify(quest).load(7, HofRequestOrigin.AUTOMATION)
    }

    @Test
    fun `captcha live snapshot failure remains a typed captcha stop signal`() {
        val fixture = liveFailureFixture()
        Mockito.`when`(fixture.quest.load(7L, HofRequestOrigin.AUTOMATION))
            .thenThrow(ApiException(ErrorCode.CAPTCHA_REQUIRED, "captcha"))

        val error = assertFailsWith<ApiException> {
            fixture.loader.loadTyped(7L)
        }

        assertEquals(ErrorCode.CAPTCHA_REQUIRED, error.errorCode)
    }

    @Test
    fun `failed stored credential recovery remains an authentication stop signal`() {
        val fixture = liveFailureFixture()
        Mockito.`when`(fixture.quest.load(7L, HofRequestOrigin.AUTOMATION))
            .thenThrow(ApiException(ErrorCode.HOF_SESSION_EXPIRED, "expired"))
        Mockito.`when`(fixture.accountService.reauthenticate(7L, HofRequestOrigin.AUTOMATION))
            .thenThrow(ApiException(ErrorCode.HOF_LOGIN_FAILED, "rejected"))

        assertFailsWith<AutomationLoginRequiredException> {
            fixture.loader.loadTyped(7L)
        }
    }

    @Test
    fun `primary rematerializes while explicit and per snapshot execution identities remain stable`() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = HofAccountEntity(7, "login", "encrypted", now)
        val primaryA = preset(101, account, "A", now)
        val primaryB = preset(102, account, "B", now)
        val explicitX = preset(103, account, "X", now)
        val questEntry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
        val battleEntry = AutomationEntryEntity(11, account, AutomationType.BATTLE_MAP, 1, true, now, now)
        val adventureEntry = AutomationEntryEntity(12, account, AutomationType.ADVENTURE_MAP, 2, true, now, now)
        val liveMap = BattleMapEntity(
            id = 30,
            categoryId = "battle_map",
            mapCode = "qmap",
            name = "Live map",
            normalizedName = "live map",
            createdAt = now,
            updatedAt = now,
        )
        val liveState = AccountBattleMapStateEntity(
            account = account,
            battleMap = liveMap,
            keyMode = BattleMapKeyMode.UNLIMITED,
            keyCount = null,
            supportsThreeBattles = true,
            rawHref = "index.php?common=qmap",
            lastSeenAt = now,
        )
        val selection = QuestAutomationSelectionEntity(20, questEntry, "q", true, 0)
        val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
        val mapQuery = Mockito.mock(BattleMapQueryRepository::class.java)
        val mapService = Mockito.mock(BattleMapService::class.java)
        val presets = Mockito.mock(PartyPresetQueryRepository::class.java)
        val quest = Mockito.mock(QuestGatewayService::class.java)
        val identities = Mockito.mock(BattleMapIdentityResolver::class.java)
        val status = Mockito.mock(HofStatusSnapshotService::class.java)
        val loader = TypedLiveAutomationSnapshotLoader(
            quest, typed, mapQuery, presets, identities, mapService, TimeProvider { now },
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
            status,
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry, adventureEntry))
        Mockito.`when`(typed.findQuestSelectionsByEntryIds(listOf(10))).thenReturn(listOf(selection))
        Mockito.`when`(typed.findQuestMaps(listOf(20))).thenReturn(listOf(QuestAutomationMapEntity(21, selection, "m", "battle_map", "qmap", PresetSelectionMode.PRIMARY, null, 0, true)))
        Mockito.`when`(typed.findBattleSettingsByEntryIds(listOf(11))).thenReturn(listOf(BattleAutomationMapEntity(22, battleEntry, "battle_map", "qmap", 10, PresetSelectionMode.PRIMARY, null, 0)))
        Mockito.`when`(typed.findAdventureSettingsByEntryIds(listOf(12))).thenReturn(listOf(AdventureAutomationMapEntity(23, adventureEntry, "adventure_map", "amap", PresetSelectionMode.EXPLICIT, explicitX, 0)))
        Mockito.`when`(quest.load(7, HofRequestOrigin.AUTOMATION)).thenReturn(emptyList())
        Mockito.`when`(mapQuery.findAllStatesForExecution(7)).thenReturn(listOf(liveState))
        liveMap.requiredTime = 75
        Mockito.`when`(status.findLatest(7)).thenReturn(
            HofObservedStatusResponse("player", 1L, 250, 6000, "Nothing", "Nothing", now.minusSeconds(50)),
            HofObservedStatusResponse("player", 1L, 249, 6000, "Nothing", "Nothing", now.minusSeconds(50)),
        )
        Mockito.`when`(presets.findAllByAccountId(7)).thenReturn(listOf(primaryA, primaryB, explicitX))
        Mockito.`when`(presets.findPrimaryByAccountId(7)).thenReturn(primaryA, primaryA, primaryB, primaryB)
        Mockito.`when`(presets.findMembersByPresetIds(listOf(101L, 102L, 103L))).thenReturn(
            members(primaryA, "A", account, now) + members(primaryB, "B", account, now) + members(explicitX, "X", account, now),
        )

        val first = loader.loadTyped(7)
        val second = loader.loadTyped(7)
        val firstQuest = requireNotNull(first.entries[0].quest)
        val secondQuest = requireNotNull(second.entries[0].quest)
        assertEquals((0..4).map { "A-$it" }, firstQuest.selections.single().maps.single().preset.resolvedParty?.characterIds)
        assertEquals((0..4).map { "B-$it" }, secondQuest.selections.single().maps.single().preset.resolvedParty?.characterIds)
        assertEquals((1..5).toList(), secondQuest.selections.single().maps.single().preset.resolvedParty?.patternLoads?.map { it.slot })
        val firstBattle = requireNotNull(first.entries[1].battle)
        val secondBattle = requireNotNull(second.entries[1].battle)
        assertEquals(BattleMapKeyMode.UNLIMITED, firstQuest.mapStates.single().keyMode)
        assertEquals("qmap", firstQuest.mapStates.single().mapCode)
        assertEquals("Live map", firstQuest.mapStates.single().mapName)
        val firstTime = AutomationTimeSnapshot(250, 6000, now.minusSeconds(50))
        val secondTime = AutomationTimeSnapshot(249, 6000, now.minusSeconds(50))
        assertEquals(firstTime, firstQuest.timeSnapshot)
        assertEquals(secondTime, secondQuest.timeSnapshot)
        assertEquals(75, firstQuest.mapStates.single().requiredTime)
        assertEquals("qmap", firstQuest.selections.single().maps.single().mapCode)
        assertEquals(BattleMapKeyMode.UNLIMITED, firstBattle.mapStates.single().keyMode)
        assertEquals("Live map", firstBattle.mapStates.single().mapName)
        assertEquals(firstTime, firstBattle.timeSnapshot)
        assertEquals(secondTime, secondBattle.timeSnapshot)
        val battleHandler = BattleMapAutomationHandler(Mockito.mock(BattleMapAutomationProgressStore::class.java))
        val firstAction = assertIs<BattleMapAutomationAction>(
            assertIs<HandlerEvaluation.Runnable>(battleHandler.evaluate(firstBattle)).action,
        )
        val secondAction = assertIs<BattleMapAutomationAction>(
            assertIs<HandlerEvaluation.Runnable>(battleHandler.evaluate(secondBattle)).action,
        )
        assertEquals(3, firstAction.battleCount)
        assertEquals(1, secondAction.battleCount)
        assertEquals(101, firstBattle.primaryPresetId); assertEquals(102, secondBattle.primaryPresetId)
        assertNotEquals(firstBattle.executionIdentity, secondBattle.executionIdentity)
        assertTrue(firstBattle.executionIdentity.isNotBlank() && firstBattle.executionIdentity.length <= 128)
        val firstAdventure = requireNotNull(first.entries[2].adventure)
        val secondAdventure = requireNotNull(second.entries[2].adventure)
        val firstResolution = firstAdventure.presetResolutions.getValue(23) as AdventureMapPresetResolution.Valid
        val secondResolution = secondAdventure.presetResolutions.getValue(23) as AdventureMapPresetResolution.Valid
        assertEquals(103, firstResolution.resolvedPresetId); assertEquals(103, secondResolution.resolvedPresetId)
        assertEquals((0..4).map { "X-$it" }, secondResolution.resolvedParty?.characterIds)
        assertEquals(BattleMapKeyMode.UNLIMITED, firstAdventure.mapStates.single().keyMode)
        assertEquals("Live map", firstAdventure.mapStates.single().mapName)
        assertEquals(firstTime, firstAdventure.timeSnapshot)
        assertEquals(secondTime, secondAdventure.timeSnapshot)
        assertEquals(75, firstAdventure.mapStates.single().requiredTime)
        assertEquals(firstAdventure.executionIdentities.getValue(23), firstAdventure.executionIdentities.getValue(23))
        assertNotEquals(firstAdventure.executionIdentities.getValue(23), secondAdventure.executionIdentities.getValue(23))
        assertTrue(secondAdventure.executionIdentities.getValue(23).length <= 128)
    }

    @Test
    fun `snapshot resolves only configured members from a party with empty slots`() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = HofAccountEntity(7, "login-partial-party", "encrypted", now)
        val primary = preset(101, account, "three-member", now)
        val entry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
        val selection = QuestAutomationSelectionEntity(20, entry, "0571", true, 0)
        val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
        val mapQuery = Mockito.mock(BattleMapQueryRepository::class.java)
        val mapService = Mockito.mock(BattleMapService::class.java)
        val presets = Mockito.mock(PartyPresetQueryRepository::class.java)
        val quest = Mockito.mock(QuestGatewayService::class.java)
        val loader = TypedLiveAutomationSnapshotLoader(
            quest,
            typed,
            mapQuery,
            presets,
            Mockito.mock(BattleMapIdentityResolver::class.java),
            mapService,
            TimeProvider { now },
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
            Mockito.mock(HofStatusSnapshotService::class.java),
        )
        val configuredMembers = members(primary, "member", account, now).take(3)
        val emptyMembers = (3..4).map { PartyPresetMemberEntity(primary, it) }
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(entry))
        Mockito.`when`(typed.findQuestSelectionsByEntryIds(listOf(10))).thenReturn(listOf(selection))
        Mockito.`when`(typed.findQuestMaps(listOf(20))).thenReturn(listOf(
            QuestAutomationMapEntity(21, selection, "mission", "battle_map", "map", PresetSelectionMode.PRIMARY, null, 0, true),
        ))
        Mockito.`when`(presets.findAllByAccountId(7)).thenReturn(listOf(primary))
        Mockito.`when`(presets.findMembersByPresetIds(listOf(101L))).thenReturn(configuredMembers + emptyMembers)
        Mockito.`when`(presets.findPrimaryByAccountId(7)).thenReturn(primary)
        Mockito.`when`(quest.load(7, HofRequestOrigin.AUTOMATION)).thenReturn(emptyList())
        Mockito.`when`(mapQuery.findAllStatesForExecution(7)).thenReturn(emptyList())

        val snapshot = loader.loadTyped(7)

        val party = requireNotNull(snapshot.entries.single().quest)
            .selections.single().maps.single().preset.resolvedParty
        assertEquals(listOf("member-0", "member-1", "member-2"), party?.characterIds)
        assertEquals(listOf(1, 2, 3), party?.patternLoads?.map { it.slot })
    }

    @Test
    fun `category change during blocking GET rejects mixed snapshot and next load refreshes new category without transaction`() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = HofAccountEntity(7, "login-category", "encrypted", now)
        val entry = AutomationEntryEntity(11, account, AutomationType.BATTLE_MAP, 0, true, now, now)
        val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
        val mapQuery = Mockito.mock(BattleMapQueryRepository::class.java)
        val mapService = Mockito.mock(BattleMapService::class.java)
        val presets = Mockito.mock(PartyPresetQueryRepository::class.java)
        val quest = Mockito.mock(QuestGatewayService::class.java)
        val identities = Mockito.mock(BattleMapIdentityResolver::class.java)
        val database = DriverManagerDataSource("jdbc:h2:mem:typed_config_${System.nanoTime()};DB_CLOSE_DELAY=-1", "sa", "")
        val loader = TypedLiveAutomationSnapshotLoader(
            quest, typed, mapQuery, presets, identities, mapService, TimeProvider { now },
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
            Mockito.mock(HofStatusSnapshotService::class.java),
            DataSourceTransactionManager(database),
        )
        val categoryA = BattleAutomationMapEntity(21, entry, "category-a", "map", 1, PresetSelectionMode.PRIMARY, null, 0)
        val categoryB = BattleAutomationMapEntity(21, entry, "category-b", "map", 1, PresetSelectionMode.PRIMARY, null, 0)
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(entry))
        Mockito.`when`(typed.findBattleSettingsByEntryIds(listOf(11))).thenReturn(listOf(categoryA), listOf(categoryB), listOf(categoryB), listOf(categoryB))
        Mockito.`when`(presets.findAllByAccountId(7)).thenReturn(emptyList())
        Mockito.`when`(presets.findMembersByPresetIds(emptyList())).thenReturn(emptyList())
        Mockito.`when`(quest.load(7, HofRequestOrigin.AUTOMATION)).thenReturn(emptyList())
        Mockito.`when`(mapQuery.findAllStatesForExecution(7)).thenReturn(emptyList())
        Mockito.`when`(
            mapService.findMaps(Mockito.eq(7L), Mockito.anyString() ?: "", eqOrigin(HofRequestOrigin.AUTOMATION)),
        ).thenAnswer {
            assertEquals(false, TransactionSynchronizationManager.isActualTransactionActive())
            emptyList<Any>()
        }

        assertFailsWith<TypedAutomationConfigurationChangedException> { loader.loadTyped(7) }
        val refreshed = loader.loadTyped(7)

        assertEquals("category-b", requireNotNull(refreshed.entries.single().battle).settings.single().categoryId)
        Mockito.verify(mapService).findMaps(7, "category-a", HofRequestOrigin.AUTOMATION)
        Mockito.verify(mapService).findMaps(7, "category-b", HofRequestOrigin.AUTOMATION)
        database.connection.use { it.createStatement().execute("shutdown") }
    }

    @Test
    fun `primary change during blocking GET rejects old party and next load uses new primary`() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = HofAccountEntity(7, "login-primary", "encrypted", now)
        val primaryA = preset(101, account, "A", now)
        val primaryB = preset(102, account, "B", now)
        val entry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
        val selection = QuestAutomationSelectionEntity(20, entry, "q", true, 0)
        val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
        val mapQuery = Mockito.mock(BattleMapQueryRepository::class.java)
        val mapService = Mockito.mock(BattleMapService::class.java)
        val presets = Mockito.mock(PartyPresetQueryRepository::class.java)
        val quest = Mockito.mock(QuestGatewayService::class.java)
        val identities = Mockito.mock(BattleMapIdentityResolver::class.java)
        val loader = TypedLiveAutomationSnapshotLoader(
            quest, typed, mapQuery, presets, identities, mapService, TimeProvider { now },
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
            Mockito.mock(HofStatusSnapshotService::class.java),
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(entry))
        Mockito.`when`(typed.findQuestSelectionsByEntryIds(listOf(10))).thenReturn(listOf(selection))
        Mockito.`when`(typed.findQuestMaps(listOf(20))).thenReturn(listOf(QuestAutomationMapEntity(21, selection, "m", "battle_map", "map", PresetSelectionMode.PRIMARY, null, 0, true)))
        Mockito.`when`(presets.findAllByAccountId(7)).thenReturn(listOf(primaryA, primaryB))
        Mockito.`when`(presets.findMembersByPresetIds(listOf(101L, 102L))).thenReturn(members(primaryA, "A", account, now) + members(primaryB, "B", account, now))
        Mockito.`when`(presets.findPrimaryByAccountId(7)).thenReturn(primaryA, primaryB, primaryB, primaryB)
        Mockito.`when`(quest.load(7, HofRequestOrigin.AUTOMATION)).thenReturn(emptyList())
        Mockito.`when`(mapQuery.findAllStatesForExecution(7)).thenReturn(emptyList())

        assertFailsWith<TypedAutomationConfigurationChangedException> { loader.loadTyped(7) }
        val refreshed = loader.loadTyped(7)

        val party = requireNotNull(refreshed.entries.single().quest).selections.single().maps.single().preset.resolvedParty
        assertEquals((0..4).map { "B-$it" }, party?.characterIds)
    }

    @Test
    fun `enabled quest without configured combat maps loads snapshot without refreshing maps`() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = HofAccountEntity(7, "login", "encrypted", now)
        val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
        val mapQuery = Mockito.mock(BattleMapQueryRepository::class.java)
        val mapService = Mockito.mock(BattleMapService::class.java)
        val presets = Mockito.mock(PartyPresetQueryRepository::class.java)
        val quest = Mockito.mock(QuestGatewayService::class.java)
        val identities = Mockito.mock(BattleMapIdentityResolver::class.java)
        val questEntry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
        val enabledSelection = QuestAutomationSelectionEntity(20, questEntry, "0091", true, 0)
        val disabledSelection = QuestAutomationSelectionEntity(21, questEntry, "stale", false, 1)
        val loader = TypedLiveAutomationSnapshotLoader(
            quest, typed, mapQuery, presets, identities, mapService, TimeProvider { now },
            HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
            Mockito.mock(HofStatusSnapshotService::class.java),
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry))
        Mockito.`when`(typed.findQuestSelectionsByEntryIds(listOf(10))).thenReturn(listOf(enabledSelection, disabledSelection))
        Mockito.`when`(typed.findQuestMaps(listOf(20, 21))).thenReturn(listOf(
            QuestAutomationMapEntity(22, disabledSelection, "mission-1", "battle_map", "battle", PresetSelectionMode.PRIMARY, null, 0, true),
            QuestAutomationMapEntity(23, disabledSelection, "mission-2", "adventure_map", "adventure", PresetSelectionMode.PRIMARY, null, 1, true),
        ))
        Mockito.`when`(quest.load(7, HofRequestOrigin.AUTOMATION)).thenReturn(emptyList())
        Mockito.`when`(mapQuery.findAllStatesForExecution(7)).thenReturn(emptyList())
        Mockito.`when`(presets.findAllByAccountId(7)).thenReturn(emptyList())
        Mockito.`when`(presets.findMembersByPresetIds(emptyList())).thenReturn(emptyList())

        val snapshot = loader.loadTyped(7)

        assertEquals(AutomationType.QUEST, snapshot.entries.single().type)
        assertTrue(snapshot.entries.single().quest != null)
        Mockito.verify(quest).load(7, HofRequestOrigin.AUTOMATION)
        Mockito.verifyNoInteractions(mapService)
    }

    @Test
    fun `refreshes each distinct nonblank category from enabled entries exactly once`() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = HofAccountEntity(7, "login-categories", "encrypted", now)
        val questEntry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
        val battleEntry = AutomationEntryEntity(11, account, AutomationType.BATTLE_MAP, 1, true, now, now)
        val adventureEntry = AutomationEntryEntity(12, account, AutomationType.ADVENTURE_MAP, 2, true, now, now)
        val disabledBattleEntry = AutomationEntryEntity(13, account, AutomationType.BATTLE_MAP, 3, false, now, now)
        val questSelection = QuestAutomationSelectionEntity(20, questEntry, "q", true, 0)
        val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
        val mapQuery = Mockito.mock(BattleMapQueryRepository::class.java)
        val mapService = Mockito.mock(BattleMapService::class.java)
        val presets = Mockito.mock(PartyPresetQueryRepository::class.java)
        val quest = Mockito.mock(QuestGatewayService::class.java)
        val loader = TypedLiveAutomationSnapshotLoader(
            quest, typed, mapQuery, presets, Mockito.mock(BattleMapIdentityResolver::class.java), mapService,
            TimeProvider { now }, HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
            Mockito.mock(HofStatusSnapshotService::class.java),
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(questEntry, battleEntry, adventureEntry, disabledBattleEntry))
        Mockito.`when`(typed.findQuestSelectionsByEntryIds(listOf(10))).thenReturn(listOf(questSelection))
        Mockito.`when`(typed.findQuestMaps(listOf(20))).thenReturn(listOf(
            QuestAutomationMapEntity(21, questSelection, "mission-1", "battle_map", "quest-map", PresetSelectionMode.PRIMARY, null, 0, true),
            QuestAutomationMapEntity(22, questSelection, "mission-2", " ", "blank-map", PresetSelectionMode.PRIMARY, null, 1, true),
        ))
        Mockito.`when`(typed.findBattleSettingsByEntryIds(listOf(11, 13))).thenReturn(listOf(
            BattleAutomationMapEntity(23, battleEntry, "battle_map", "battle-map", 1, PresetSelectionMode.PRIMARY, null, 0),
            BattleAutomationMapEntity(24, disabledBattleEntry, "disabled_map", "disabled-map", 1, PresetSelectionMode.PRIMARY, null, 0),
        ))
        Mockito.`when`(typed.findAdventureSettingsByEntryIds(listOf(12))).thenReturn(listOf(
            AdventureAutomationMapEntity(25, adventureEntry, "adventure_map", "adventure-map", PresetSelectionMode.PRIMARY, null, 0),
            AdventureAutomationMapEntity(26, adventureEntry, "", "blank-map", PresetSelectionMode.PRIMARY, null, 1),
        ))
        Mockito.`when`(presets.findAllByAccountId(7)).thenReturn(emptyList())
        Mockito.`when`(presets.findMembersByPresetIds(emptyList())).thenReturn(emptyList())
        Mockito.`when`(quest.load(7, HofRequestOrigin.AUTOMATION)).thenReturn(emptyList())
        Mockito.`when`(mapQuery.findAllStatesForExecution(7)).thenReturn(emptyList())

        loader.loadTyped(7)

        val inOrder = Mockito.inOrder(mapService)
        inOrder.verify(mapService).findMaps(7, "battle_map", HofRequestOrigin.AUTOMATION)
        inOrder.verify(mapService).findMaps(7, "adventure_map", HofRequestOrigin.AUTOMATION)
        Mockito.verifyNoMoreInteractions(mapService)
    }

    @Test
    fun `configuration materialization uses bounded batch queries for many quest selections`() {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val account = HofAccountEntity(7, "login-batch", "encrypted", now)
        val entry = AutomationEntryEntity(10, account, AutomationType.QUEST, 0, true, now, now)
        val selections = (1L..50L).map { id -> QuestAutomationSelectionEntity(id, entry, "quest-$id", true, id.toInt()) }
        val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
        val mapQuery = Mockito.mock(BattleMapQueryRepository::class.java)
        val mapService = Mockito.mock(BattleMapService::class.java)
        val presets = Mockito.mock(PartyPresetQueryRepository::class.java)
        val quest = Mockito.mock(QuestGatewayService::class.java)
        val loader = TypedLiveAutomationSnapshotLoader(
            quest, typed, mapQuery, presets, Mockito.mock(BattleMapIdentityResolver::class.java), mapService,
            TimeProvider { now }, HofSessionRecoveryExecutor(HofSessionRecoveryService(Mockito.mock(HofAccountService::class.java))),
            Mockito.mock(HofStatusSnapshotService::class.java),
        )
        Mockito.`when`(typed.findEntries(7)).thenReturn(listOf(entry))
        Mockito.`when`(typed.findQuestSelectionsByEntryIds(listOf(10))).thenReturn(selections)
        Mockito.`when`(typed.findQuestMaps(selections.map { it.id })).thenReturn(emptyList())
        Mockito.`when`(presets.findAllByAccountId(7)).thenReturn(emptyList())
        Mockito.`when`(presets.findMembersByPresetIds(emptyList())).thenReturn(emptyList())
        Mockito.`when`(quest.load(7, HofRequestOrigin.AUTOMATION)).thenReturn(emptyList())
        Mockito.`when`(mapQuery.findAllStatesForExecution(7)).thenReturn(emptyList())

        loader.loadTyped(7)

        Mockito.verify(typed, Mockito.times(2)).findQuestSelectionsByEntryIds(listOf(10))
        Mockito.verify(typed, Mockito.times(2)).findQuestMaps(selections.map { it.id })
        Mockito.verify(typed, Mockito.never()).findQuestSelections(Mockito.anyLong())
        Mockito.verify(typed, Mockito.never()).findBattleSettings(Mockito.anyLong())
        Mockito.verify(typed, Mockito.never()).findAdventureSettings(Mockito.anyLong())
    }

    private fun preset(id: Long, account: HofAccountEntity, name: String, now: Instant) = PartyPresetEntity(id, account, name, now, now)
    private fun eqOrigin(origin: HofRequestOrigin): HofRequestOrigin = Mockito.eq(origin) ?: origin
    private fun members(preset: PartyPresetEntity, prefix: String, account: HofAccountEntity, now: Instant) = (0..4).map { slot ->
        val character = CharacterEntity(
            id = 1_000 + preset.id * 10 + slot, account = account, hofCharacterId = "$prefix-$slot",
            name = "$prefix-$slot", job = "job", level = 1, patternSlotCount = 1, imageUrl = null, updatedAt = now,
        )
        val pattern = CharacterPatternSlotEntity(2_000 + preset.id * 10 + slot, character, (slot + 1).toString(), "p", true)
        PartyPresetMemberEntity(preset, slot, character, pattern)
    }

    private fun liveFailureFixture(): LiveFailureFixture {
        val now = Instant.parse("2026-07-16T00:00:00Z")
        val typed = Mockito.mock(TypedAutomationQueryRepository::class.java)
        val maps = Mockito.mock(BattleMapQueryRepository::class.java)
        val presets = Mockito.mock(PartyPresetQueryRepository::class.java)
        val quest = Mockito.mock(QuestGatewayService::class.java)
        val accountService = Mockito.mock(HofAccountService::class.java)
        Mockito.`when`(typed.findEntries(7L)).thenReturn(emptyList())
        Mockito.`when`(presets.findAllByAccountId(7L)).thenReturn(emptyList())
        Mockito.`when`(presets.findMembersByPresetIds(emptyList())).thenReturn(emptyList())
        val loader = TypedLiveAutomationSnapshotLoader(
            quest,
            typed,
            maps,
            presets,
            Mockito.mock(BattleMapIdentityResolver::class.java),
            Mockito.mock(BattleMapService::class.java),
            TimeProvider { now },
            HofSessionRecoveryExecutor(HofSessionRecoveryService(accountService)),
            Mockito.mock(HofStatusSnapshotService::class.java),
        )
        return LiveFailureFixture(loader, quest, accountService)
    }

    private data class LiveFailureFixture(
        val loader: TypedLiveAutomationSnapshotLoader,
        val quest: QuestGatewayService,
        val accountService: HofAccountService,
    )
}
