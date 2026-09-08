package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.character.command.CharacterAutomationGate
import app.spammy.hof.character.command.CharacterInternalFormExecutor
import app.spammy.hof.character.command.HofCharacterCommandAdapter
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.identity.CharacterLifecycleService
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.external.parser.CharacterPageParseResult
import app.spammy.hof.external.parser.CharacterRosterParser
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.AccountHofMutationFence
import app.spammy.hof.town.common.service.TownActionGuard
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.mockito.Mockito

class CharacterDeepSyncServiceTest {
    @Test
    fun `정상 장비 해제 응답 뒤 조회 실패도 원본 복구를 다시 이어간다`() {
        val fixture = Fixture(equipmentRewritesPattern = true, foreignSecondPreset = true, failGetAfterEquipmentRestore = "remove_all")
        assertFailsWith<IllegalStateException> { fixture.service.synchronize(1L, 7L, 17L) }
        fixture.service.synchronize(1L, 7L, 17L)
        fixture.assertOriginalRestored(expectedCurrentWrites = 3)
        assertEquals(1, fixture.posts.count { "remove_all" in it })
        assertEquals(1, fixture.posts.count { "equip_item" in it })
    }

    @Test
    fun `정상 장비 복구 응답 뒤 조회 실패는 원본 복구 재시도를 막지 않는다`() {
        val fixture = Fixture(equipmentRewritesPattern = true, failGetAfterEquipmentRestore = "equip_item")
        assertFailsWith<IllegalStateException> { fixture.service.synchronize(1L, 7L, 17L) }
        fixture.service.synchronize(1L, 7L, 17L)
        fixture.assertOriginalRestored(expectedCurrentWrites = 3)
        assertEquals(1, fixture.posts.count { "equip_item" in it })
    }

    @Test
    fun `장비 저장 뒤쪽을 읽지 못한 화면을 슬롯 미제공으로 확정하지 않는다`() {
        val fixture = Fixture(incompleteEquipmentSlots = true)
        assertFailsWith<IllegalStateException> { fixture.service.synchronize(1L, 7L, 17L) }
        assertTrue(fixture.posts.isEmpty())
        assertTrue(fixture.equipmentSnapshots.isEmpty())
    }

    @Test
    fun `완전한 화면에 명시된 불러오기 거부를 수집 성공으로 기록하지 않는다`() {
        val fixture = Fixture(rejectedSecondPreset = true)
        assertFailsWith<IllegalStateException> { fixture.service.synchronize(1L, 7L, 17L) }
        fixture.assertOriginalRestored()
        assertEquals(1, fixture.equipmentSnapshots.size)
    }

    @Test
    fun `정상 장비 응답 이후 조회가 실패해도 채택한 설정으로 원본 복구를 이어간다`() {
        val fixture = Fixture(equipmentRewritesPattern = true, failGetAfterFirstPreset = true)
        assertFailsWith<IllegalStateException> { fixture.service.synchronize(1L, 7L, 17L) }
        fixture.assertOriginalRestored()
        assertEquals("2", fixture.equipmentSnapshots.single().snapshot.actionPatterns.single().quantity)
    }

    @Test
    fun `다른 캐릭터 화면으로 이동한 응답은 원본으로 저장하거나 변경하지 않는다`() {
        val fixture = Fixture(wrongCharacterResponse = true)

        assertFailsWith<IllegalStateException> { fixture.service.synchronize(1L, 7L, 17L) }

        assertTrue(fixture.posts.isEmpty())
    }

    @Test
    fun `저장 패턴 구역을 확인하지 못하면 슬롯이 없다고 추정하지 않는다`() {
        val fixture = Fixture(incompletePatternSlots = true)

        assertFailsWith<IllegalStateException> { fixture.service.synchronize(1L, 7L, 17L) }

        assertTrue(fixture.posts.isEmpty())
    }

    @Test
    fun `장비 불러오기의 불완전한 직접 응답을 후속 조회 성공으로 덮지 않는다`() {
        val fixture = Fixture(incompleteSecondPresetResponse = true)

        assertFailsWith<IllegalStateException> { fixture.service.synchronize(1L, 7L, 17L) }

        fixture.assertOriginalRestored()
        assertEquals(1, fixture.equipmentSnapshots.size)
    }

    @Test
    fun `완전한 서버 화면이 제공하는 장비 저장만 수집한다`() {
        val fixture = Fixture(unavailableSecondPreset = true)

        val result = fixture.service.synchronize(1L, 7L, 17L)

        fixture.assertOriginalRestored()
        assertEquals(1, fixture.equipmentSnapshots.size)
        assertFalse(fixture.posts.any { "Equip_L_2" in it })
        assertEquals(CharacterDeepSyncPhase.COMPLETED, result.progress.last().phase)
        assertEquals(4, result.progress.last().totalSteps)
    }

    @Test
    fun `정상 장비 응답의 기준값 변경을 수집하고 원래 패턴으로 복구한다`() {
        val fixture = Fixture(equipmentRewritesPattern = true)

        val result = fixture.service.synchronize(1L, 7L, 17L)

        fixture.assertOriginalRestored()
        assertEquals(listOf("2", "3"), fixture.equipmentSnapshots.map { it.snapshot.actionPatterns.single().quantity })
        assertTrue(fixture.equipmentSnapshots.all { page -> page.snapshot.equipment.none { it.name.isNotBlank() } })
        assertEquals(CharacterDeepSyncPhase.COMPLETED, result.progress.last().phase)
    }

    @Test
    fun `equipment granted pattern rows disappear and return during preset collection and restoration`() {
        val fixture = Fixture(equipmentPatternRows = true)

        val result = fixture.service.synchronize(1L, 7L, 17L)

        fixture.assertOriginalRestored()
        assertEquals(CharacterDeepSyncPhase.COMPLETED, result.progress.last().phase)
    }

    @Test
    fun `empty equipment presets restore the equipment dependent original pattern before completion`() {
        val fixture = Fixture()

        val result = fixture.service.synchronize(1L, 7L, 17L)

        fixture.assertOriginalRestored()
        assertEquals(CharacterDeepSyncPhase.COMPLETED, result.progress.last().phase)
        assertEquals(5, result.progress.last().completedSteps)
        assertEquals(1, fixture.posts.count { "ChangePattern" in it })
        assertFalse(fixture.posts.any { "remove_all" in it }, "empty equipment needs no repeated removal")
    }

    @Test
    fun `failure after unequipping still restores equipment and the original pattern before releasing the gate`() {
        val fixture = Fixture(failSecondPreset = true)

        val error = assertFailsWith<IllegalStateException> { fixture.service.synchronize(1L, 7L, 17L) }

        assertEquals("fixture preset response failed", error.message)
        fixture.assertOriginalRestored()
        assertEquals(1, fixture.posts.count { "ChangePattern" in it })
    }

    @Test
    fun `failure to commit the original makes no remote changes`() {
        val fixture = Fixture(failOriginalSave = true)

        assertFailsWith<IllegalStateException> { fixture.service.synchronize(1L, 7L, 17L) }

        assertTrue(fixture.posts.isEmpty())
    }

    @Test
    fun `progress storage failure after loading a slot still restores the original`() {
        val fixture = Fixture()

        val error = assertFailsWith<IllegalStateException> {
            fixture.service.synchronize(1L, 7L, 17L) { step ->
                if (step.phase == CharacterDeepSyncPhase.SAVED_PATTERN) error("progress storage unavailable")
            }
        }

        assertEquals("progress storage unavailable", error.message)
        fixture.assertOriginalRestored()
    }

    internal class Fixture(
        private val failSecondPreset: Boolean = false,
        private val failOriginalSave: Boolean = false,
        private val characterId: Long = 7L,
        recoveryOverride: CharacterDeepSyncRecovery? = null,
        archiveOverride: CharacterSnapshotArchiveWriter? = null,
        lifecycleOverride: CharacterLifecycleService? = null,
        automationGateOverride: CharacterAutomationGate? = null,
        private val beforePost: () -> Unit = {},
        private val stateFile: java.nio.file.Path? = null,
        private val terminateAfter: String? = null,
        private var expireFirstCapture: Boolean = false,
        private val equipmentPatternRows: Boolean = false,
        private val equipmentRewritesPattern: Boolean = false,
        private val unavailableSecondPreset: Boolean = false,
        private val incompleteSecondPresetResponse: Boolean = false,
        private val incompletePatternSlots: Boolean = false,
        private val wrongCharacterResponse: Boolean = false,
        private val rejectedSecondPreset: Boolean = false,
        private var failGetAfterFirstPreset: Boolean = false,
        private val incompleteEquipmentSlots: Boolean = false,
        private val foreignSecondPreset: Boolean = false,
        private var failGetAfterEquipmentRestore: String? = null,
    ) {
        private val now = Instant.parse("2026-09-07T05:00:00Z")
        private val account = HofAccountEntity(1L, "fixture", "encrypted", now)
        private val character = CharacterEntity(characterId, account, "10", "fixture", "Knight", updatedAt = now)
        private val accounts = Mockito.mock(AccountQueryRepository::class.java)
        private val cookies = Mockito.mock(CookieQueryRepository::class.java)
        private val query = Mockito.mock(CharacterQueryRepository::class.java)
        private val gateway = Mockito.mock(AccountHofGateway::class.java)
        private val archive = archiveOverride ?: Mockito.mock(CharacterSnapshotArchiveWriter::class.java)
        private val parser = CharacterDetailParser()
        private val recovery = recoveryOverride ?: Mockito.mock(CharacterDeepSyncRecovery::class.java)
        private var checkpoint: CharacterDeepSyncCheckpoint? = null
        private val stateMapper = tools.jackson.module.kotlin.jacksonObjectMapper()
        private val previous = stateFile?.takeIf(java.nio.file.Files::exists)?.let {
            stateMapper.readTree(java.nio.file.Files.readString(it))
        }
        private var equipped = previous?.get("equipped")?.asBoolean() ?: true
        private var foreignEquipped = false
        private var selectedSkill = previous?.get("skill")?.asString() ?: "9564"
        private var selectedQuantity = "0"
        private var selectedPosition = previous?.get("position")?.asString() ?: "front"
        private var selectedGuard = previous?.get("guard")?.asString() ?: "1"
        private var gateOwned = false
        var reauthenticationCalls = 0
            private set
        private val accountService = Mockito.mock(app.spammy.hof.account.service.HofAccountService::class.java)
        val sessionRecovery = app.spammy.hof.account.service.HofSessionRecoveryService(accountService)
        private val stored = mutableListOf<CharacterPageParseResult>()
        val posts = mutableListOf<Map<String, String>>()
        val equipmentSnapshots = mutableListOf<CharacterPageParseResult>()
        val service: CharacterDeepSyncService

        init {
            Mockito.doAnswer {
                assertTrue(gateOwned, "Session recovery must stay inside the job's automation pause")
                reauthenticationCalls++
                null
            }.`when`(accountService).reauthenticate(Mockito.eq(1L), Mockito.any(app.spammy.hof.external.model.HofRequestOrigin::class.java)
                ?: app.spammy.hof.external.model.HofRequestOrigin.INTERACTIVE)
            if (recoveryOverride == null) {
            Mockito.`when`(recovery.load(17L, 1L, characterId)).thenAnswer { checkpoint }
            Mockito.doAnswer { invocation ->
                check(!failOriginalSave) { "original storage unavailable" }
                checkpoint = invocation.getArgument(3)
                null
            }.`when`(recovery).save(Mockito.eq(17L), Mockito.eq(1L), Mockito.eq(characterId),
                Mockito.any(CharacterDeepSyncCheckpoint::class.java) ?: CharacterDeepSyncCheckpoint(CharacterRestoreState.capture(parser.parsePage("10", page()))))
            }
            Mockito.`when`(accounts.findById(1L)).thenReturn(account)
            Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "fixture"))
            Mockito.`when`(query.findByAccountIdAndId(1L, characterId)).thenReturn(character)
            Mockito.`when`(gateway.execute(Mockito.eq(1L), anyRequest(), Mockito.anyMap<String, String>()))
                .thenAnswer { invocation ->
                    val request = invocation.getArgument<HofRequest>(1)
                    if (failGetAfterEquipmentRestore != null && request.method == HofHttpMethod.GET && posts.lastOrNull()?.containsKey(failGetAfterEquipmentRestore) == true) {
                        failGetAfterEquipmentRestore = null
                        error("fixture restore follow-up GET failed")
                    }
                    if (failGetAfterFirstPreset && request.method == HofHttpMethod.GET && posts.lastOrNull()?.containsKey("Equip_L_1") == true) {
                        failGetAfterFirstPreset = false
                        error("fixture follow-up GET failed")
                    }
                    if (wrongCharacterResponse) return@thenAnswer HofHttpResponse(
                        200, request.url.replace("char=10", "char=11"), page().replace("char=10", "char=11"), emptyMap(),
                    )
                    if (expireFirstCapture && request.method == HofHttpMethod.GET) {
                        expireFirstCapture = false
                        throw app.spammy.hof.common.error.ApiException(app.spammy.hof.common.error.ErrorCode.HOF_SESSION_EXPIRED, "fixture session expired")
                    }
                    if (request.method == HofHttpMethod.POST) {
                        assertTrue(gateOwned)
                        beforePost()
                        val fields = request.formFields
                        posts += fields
                        if (rejectedSecondPreset && "Equip_L_2" in fields) {
                            return@thenAnswer HofHttpResponse(200, request.url, page() + "<div class='error'>Load rejected</div>", emptyMap())
                        }
                        if (incompleteSecondPresetResponse && "Equip_L_2" in fields) {
                            return@thenAnswer HofHttpResponse(200, request.url, "<div class='error'>Load rejected</div>", emptyMap())
                        }
                        when {
                            "loadpattern" in fields -> {
                                selectedSkill = "0"
                                selectedPosition = "back"
                                selectedGuard = "0"
                            }
                            "Equip_L_1" in fields || "Equip_L_2" in fields -> {
                                foreignEquipped = foreignSecondPreset && "Equip_L_2" in fields
                                equipped = foreignEquipped
                                if (equipmentRewritesPattern) selectedQuantity = if ("Equip_L_1" in fields) "2" else "3"
                                if (failSecondPreset && "Equip_L_2" in fields) {
                                    error("fixture preset response failed")
                                }
                            }
                            "remove_all" in fields -> {
                                equipped = false
                                foreignEquipped = false
                                if (equipmentRewritesPattern) selectedQuantity = "5"
                            }
                            "equip_item" in fields -> {
                                assertEquals("seal", fields["item_no"])
                                equipped = true
                                foreignEquipped = false
                                if (equipmentRewritesPattern) selectedQuantity = "4"
                            }
                            "ChangePattern" in fields -> {
                                assertTrue(equipped, "The original skill is only selectable with its equipment")
                                selectedSkill = requireNotNull(fields["skill0"])
                                selectedQuantity = requireNotNull(fields["quantity0"])
                            }
                            "ChangePosition" in fields -> {
                                selectedPosition = requireNotNull(fields["position"])
                                selectedGuard = requireNotNull(fields["guard"])
                            }
                        }
                        stateFile?.let {
                            java.nio.file.Files.writeString(it, stateMapper.writeValueAsString(mapOf(
                                "equipped" to equipped, "skill" to selectedSkill,
                                "position" to selectedPosition, "guard" to selectedGuard,
                            )))
                            java.nio.file.Files.writeString(it.resolveSibling("posts.jsonl"),
                                stateMapper.writeValueAsString(fields) + "\n", java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND)
                        }
                        if (terminateAfter != null && terminateAfter in fields) Runtime.getRuntime().halt(71)
                    }
                    HofHttpResponse(200, request.url, page(), emptyMap())
                }
            if (archiveOverride == null) {
            Mockito.doAnswer { invocation ->
                equipmentSnapshots += invocation.getArgument<CharacterPageParseResult>(2)
                null
            }.`when`(archive).saveEquipmentPreset(
                Mockito.eq(character) ?: character, Mockito.anyInt(),
                Mockito.any(CharacterPageParseResult::class.java) ?: parser.parsePage("10", page()),
                Mockito.eq(now) ?: now,
            )
            Mockito.doAnswer { invocation ->
                stored += invocation.getArgument<CharacterPageParseResult>(1)
                null
            }.`when`(archive).saveCurrent(
                Mockito.eq(character) ?: character,
                Mockito.any(CharacterPageParseResult::class.java) ?: parser.parsePage("10", page()),
                Mockito.eq(now) ?: now,
            )
            }
            val factory = HofRequestFactory()
            val executor = TownAuthenticatedExecutor(
                accounts, cookies, factory, gateway, LoginStateParser(), HofFormParser(), HofResultParser(),
                TownActionGuard(), AccountHofMutationFence(),
            )
            val remote = HofCharacterCommandAdapter(
                executor, factory, CharacterRosterParser(), parser, Mockito.mock(CharacterSnapshotSynchronizer::class.java),
            )
            val gate = object : CharacterAutomationGate {
                private fun <T> protectedOperation(operation: () -> T): T {
                    gateOwned = true
                    return try { operation() } finally { gateOwned = false }
                }
                override fun <T> execute(accountId: Long, unavailable: () -> T, operation: () -> T): T = protectedOperation {
                    if (automationGateOverride == null) operation() else automationGateOverride.execute(accountId, unavailable, operation)
                }
                override fun <T> executeJob(accountId: Long, jobId: Long, unavailable: () -> T, operation: () -> T): T = protectedOperation {
                    if (automationGateOverride == null) operation() else automationGateOverride.executeJob(accountId, jobId, unavailable, operation)
                }
            }
            service = CharacterDeepSyncService(
                query, executor, remote, CharacterInternalFormExecutor(executor, factory), factory, parser, archive,
                TimeProvider { now }, gate, lifecycleOverride ?: Mockito.mock(CharacterLifecycleService::class.java), recovery, sessionRecovery,
            )
        }

        fun assertOriginalRestored(expectedCurrentWrites: Int = 2) {
            assertTrue(equipped)
            assertEquals("9564", selectedSkill)
            assertEquals("0", selectedQuantity)
            assertEquals("front", selectedPosition)
            assertEquals("1", selectedGuard)
            assertFalse(gateOwned)
            assertEquals(
                mapOf("position" to "front", "guard" to "1", "ChangePosition" to "Save"),
                posts.single { "ChangePosition" in it },
            )
            assertEquals(expectedCurrentWrites, stored.size)
            assertEquals(stored.first().snapshot.actionPatterns, stored.last().snapshot.actionPatterns)
            assertEquals(stored.first().snapshot.equipment, stored.last().snapshot.equipment)
            assertEquals(stored.first().snapshot.positionGuard, stored.last().snapshot.positionGuard)
        }

        fun page(): String = """
            <div class="carpet_frame">fixture Lv.60 Knight</div>
            <form action="?char=10" method="post">
              ${if (incompletePatternSlots) "" else """<input type="hidden" name="patternno" value="0"><input type="submit" name="loadpattern" value="Load">"""}
            </form>
            <form action="?char=10" method="post">
              <select name="judge0"><option value="0" selected>Always</option></select>
              <input name="quantity0" value="$selectedQuantity">
              <select name="skill0">
                <option value="0" ${if (selectedSkill == "0") "selected" else ""}>Attack</option>
                ${if (equipped) "<option value='9564' ${if (selectedSkill == "9564") "selected" else ""}>Equipment Skill</option>" else ""}
              </select>
              ${if (equipmentPatternRows && equipped) (1..2).joinToString("\n") { index -> """
              <select name="judge$index"><option value="$index" selected>Condition $index</option></select>
              <input name="quantity$index" value="$index">
              <select name="skill$index"><option value="0" selected>Attack</option></select>
              """ } else ""}
              <input type="submit" name="ChangePattern" value="Save">
            </form>
            <form action="?char=10" method="post">
              <input type="radio" name="position" value="front" ${if (selectedPosition == "front") "checked" else ""}>Front
              <input type="radio" name="position" value="back" ${if (selectedPosition == "back") "checked" else ""}>Back
              <select name="guard">
                <option value="0" ${if (selectedGuard == "0") "selected" else ""}>None</option>
                <option value="1" ${if (selectedGuard == "1") "selected" else ""}>Protect</option>
              </select>
              <input type="submit" name="ChangePosition" value="Save">
            </form>
            <form action="?char=10" method="post"><input type="submit" name="Equip_L_1" value="Load"></form>
            ${if (unavailableSecondPreset) "" else """<form action="?char=10" method="post"><input type="submit" name="Equip_L_2" value="Load"></form>"""}
            <form action="?char=10" method="post">
              <table>
              ${(1..11).joinToString("\n") { "<tr><td class='align-right'>Empty$it</td><td><input name='spot' value='empty$it'></td></tr>" }}
              <tr><td class="align-right">SkillSeal</td><td><input name="spot" value="skillseal">
              ${if (equipped) "<img src='/seal.gif'>${if (foreignEquipped) "Other Seal" else "Skill Seal"}" else ""}</td></tr></table>
              <input type="submit" name="remove_all" value="Remove">
            </form>
            <script>
              function Listtype_equip(mode) {
                switch(mode) {
                case "skillseal":
                html = '<input type="radio" name="item_no" value="seal"><img src="/seal.gif">Skill Seal (SkillSeal) x1<br />'; break;
                }
              }
            </script>
            <form id="equip"><select name="type_equip"><option value="skillseal">SkillSeal</option></select></form>
            <form action="?char=10" method="post"><div id="list0">None.</div><input type="submit" name="equip_item" value="Equip"></form>
        """.trimIndent().let { html ->
            if (incompleteEquipmentSlots) html.substringBefore("<script>")
                .replace(Regex("<form[^>]*><input[^>]*name=\"Equip_L_[12]\"[^>]*></form>"), "")
            else html
        }
    }

    private companion object {
        fun anyRequest(): HofRequest = Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, "https://fixture.invalid")
    }
}
