package app.spammy.hof.character.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.character.dto.CharacterDetailResponse
import app.spammy.hof.character.command.CharacterCommand
import app.spammy.hof.character.command.CharacterCommandContext
import app.spammy.hof.character.command.CharacterCommandObservation
import app.spammy.hof.character.command.HofCharacterCommandAdapter
import app.spammy.hof.external.client.AccountHofGateway
import app.spammy.hof.external.client.DeferredCharacterRosterHofResponse
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofEquipmentCandidate
import app.spammy.hof.external.parser.CharacterDetailParser
import app.spammy.hof.external.parser.CharacterRosterParser
import app.spammy.hof.external.parser.CharacterPageSection
import app.spammy.hof.external.parser.CharacterSectionParseResult
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.parser.HofFormParser
import app.spammy.hof.town.common.parser.HofResultParser
import app.spammy.hof.town.common.service.TownActionGuard
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

class CharacterCommandLifecycleIntegrationTest {
    private val now = Instant.parse("2026-08-18T00:00:00Z")
    private val account = HofAccountEntity(1L, "account", "encrypted", now)
    private val accounts = Mockito.mock(AccountQueryRepository::class.java)
    private val cookies = Mockito.mock(CookieQueryRepository::class.java)
    private val gateway = Mockito.mock(AccountHofGateway::class.java)
    private val synchronizer = Mockito.mock(CharacterSnapshotSynchronizer::class.java)
    private val detailParser = CharacterDetailParser()
    private val forms = HofFormParser()
    private val executor = TownAuthenticatedExecutor(
        accounts,
        cookies,
        HofRequestFactory(),
        gateway,
        LoginStateParser(),
        forms,
        HofResultParser(),
        TownActionGuard(),
        app.spammy.hof.town.common.service.AccountHofMutationFence(),
    )
    private val commandAdapter = HofCharacterCommandAdapter(
        executor,
        HofRequestFactory(),
        CharacterRosterParser(),
        detailParser,
        synchronizer,
    )

    @Test
    fun `semantic item preparation projects reset candidates from the immediate action response`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        val observedCandidates = mutableListOf<HofEquipmentCandidate>()
        var observedTypes = emptySet<String>()
        Mockito.`when`(
            synchronizer.writeEquipmentCandidateSubset(
                Mockito.eq(1L),
                Mockito.anyString(),
                anyCandidates(),
                Mockito.anySet(),
            ),
        ).thenAnswer { invocation ->
            observedCandidates += invocation.getArgument<List<HofEquipmentCandidate>>(2)
            observedTypes = invocation.getArgument(3)
            Mockito.mock(CharacterDetailResponse::class.java)
        }
        val calls = AtomicInteger()
        Mockito.`when`(
            gateway.execute(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenAnswer {
            when (calls.getAndIncrement()) {
                0 -> response(BASE_PAGE)
                1 -> response(RESET_SELECTOR_PAGE)
                else -> response(BASE_PAGE)
            }
        }

        assertIs<CharacterCommandObservation.Applied>(
            execute(CharacterCommand.PrepareItems(7L, now)),
        )

        assertEquals(listOf("7510"), observedCandidates.map { it.value })
        assertEquals(setOf("resetitem"), observedTypes)
    }

    @Test
    fun `semantic item preparation rejects an unsupported selector response without deleting saved candidates`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(
            gateway.execute(Mockito.eq(1L), anyRequest(), Mockito.anyMap<String, String>()),
        ).thenReturn(response(BASE_PAGE), response(BASE_PAGE))

        val observed = assertIs<CharacterCommandObservation.Rejected>(
            execute(CharacterCommand.PrepareItems(7L, now)),
        )

        assertEquals("FORM_NOT_OBSERVED", observed.code)
        Mockito.verifyNoInteractions(synchronizer)
    }

    @Test
    fun `semantic item preparation accepts an observed empty selector as an authoritative clear`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        var observedCandidates = listOf(HofEquipmentCandidate("old", "resetitem", "Old"))
        Mockito.`when`(
            synchronizer.writeEquipmentCandidateSubset(
                Mockito.eq(1L),
                Mockito.anyString(),
                anyCandidates(),
                Mockito.anySet(),
            ),
        ).thenAnswer { invocation ->
            observedCandidates = invocation.getArgument(2)
            Mockito.mock(CharacterDetailResponse::class.java)
        }
        Mockito.`when`(
            gateway.execute(Mockito.eq(1L), anyRequest(), Mockito.anyMap<String, String>()),
        ).thenReturn(response(BASE_PAGE), response(EMPTY_RESET_SELECTOR_PAGE))

        assertIs<CharacterCommandObservation.Applied>(execute(CharacterCommand.PrepareItems(7L, now)))

        assertEquals(emptyList(), observedCandidates)
    }

    @Test
    fun `semantic reset item use reopens the transient selector and returns the common applied result`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(
            synchronizer.writeParsed(
                Mockito.eq(1L),
                Mockito.anyString(),
                anyPage(),
                Mockito.anySet(),
            ),
        ).thenReturn(Mockito.mock(CharacterDetailResponse::class.java))
        Mockito.`when`(
            gateway.execute(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenReturn(response(BASE_PAGE), response(RESET_SELECTOR_PAGE), response(BASE_PAGE))

        val observed = assertIs<CharacterCommandObservation.Applied>(
            execute(CharacterCommand.UseItem(7L, now, "7510")),
        )

        assertEquals(emptyList(), observed.messages)
        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(3)).execute(
            Mockito.eq(1L),
            capture(requests, HofRequest(HofHttpMethod.GET, CHARACTER_URL)),
            Mockito.anyMap<String, String>(),
        )
        assertEquals(HofHttpMethod.GET, requests.allValues[0].method)
        assertEquals(mapOf("showreset" to "Use"), requests.allValues[1].formFields)
        assertEquals(
            mapOf("itemUse" to "7510", "resetVarious" to "Use"),
            requests.allValues[2].formFields,
        )
    }

    @Test
    fun `semantic reset item use stops before final submission when the item is no longer offered`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(
            gateway.execute(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenReturn(response(BASE_PAGE), response(RESET_SELECTOR_PAGE))

        val observed = assertIs<CharacterCommandObservation.Rejected>(
            execute(CharacterCommand.UseItem(7L, now, "missing-item")),
        )

        assertEquals("FORM_NOT_OBSERVED", observed.code)
        Mockito.verify(gateway, Mockito.times(2)).execute(
            Mockito.eq(1L),
            anyRequest(),
            Mockito.anyMap<String, String>(),
        )
        Mockito.verifyNoInteractions(synchronizer)
    }

    @Test
    fun `semantic ordinary item use returns the same applied lifecycle result`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(
            synchronizer.writeParsed(
                Mockito.eq(1L),
                Mockito.anyString(),
                anyPage(),
                Mockito.anySet(),
            ),
        ).thenReturn(Mockito.mock(CharacterDetailResponse::class.java))
        Mockito.`when`(
            gateway.execute(Mockito.eq(1L), anyRequest(), Mockito.anyMap<String, String>()),
        ).thenReturn(response(NORMAL_ITEM_PAGE), response(BASE_PAGE))

        val observed = assertIs<CharacterCommandObservation.Applied>(
            execute(CharacterCommand.UseItem(7L, now, "potion-1")),
        )

        assertEquals(emptyList(), observed.messages)
        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(2)).execute(
            Mockito.eq(1L),
            capture(requests, HofRequest(HofHttpMethod.GET, CHARACTER_URL)),
            Mockito.anyMap<String, String>(),
        )
        assertEquals(mapOf("item_no" to "potion-1", "use_char_item" to "Use"), requests.allValues[1].formFields)
    }

    @Test
    fun `semantic equipment command selects the exact latest candidate and projects its response`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        val projected = ArgumentCaptor.forClass(app.spammy.hof.external.parser.CharacterPageParseResult::class.java)
        Mockito.`when`(
            synchronizer.writeParsed(
                Mockito.eq(1L),
                Mockito.anyString(),
                capture(
                    projected,
                    app.spammy.hof.external.parser.CharacterPageParseResult(
                        app.spammy.hof.external.model.HofCharacter(id = ""),
                        emptyMap(),
                    ),
                ),
                Mockito.anySet(),
            ),
        ).thenReturn(Mockito.mock(CharacterDetailResponse::class.java))
        Mockito.`when`(
            gateway.execute(Mockito.eq(1L), anyRequest(), Mockito.anyMap<String, String>()),
        ).thenReturn(response(EQUIPMENT_PAGE), response(EQUIPMENT_RESULT_PAGE))

        val observed = assertIs<CharacterCommandObservation.Applied>(
            execute(CharacterCommand.EquipItem(7L, now, "item-2")),
        )

        assertEquals(emptyList(), observed.messages)
        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(2)).execute(
            Mockito.eq(1L),
            capture(requests, HofRequest(HofHttpMethod.GET, CHARACTER_URL)),
            Mockito.anyMap<String, String>(),
        )
        assertEquals(
            mapOf("item_no" to "item-2", "list_type" to "armor", "equip_item" to "Equip"),
            requests.allValues[1].formFields,
        )
        assertIs<CharacterSectionParseResult.Success>(projected.value.sections[CharacterPageSection.EQUIPMENT])
    }

    @Test
    fun `semantic equipment command does not treat an ordinary item form as equipment`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        Mockito.`when`(
            gateway.execute(Mockito.eq(1L), anyRequest(), Mockito.anyMap<String, String>()),
        ).thenReturn(response(EQUIPMENT_PAGE_WITH_ORDINARY_ITEM))

        val observed = assertIs<CharacterCommandObservation.Rejected>(
            execute(CharacterCommand.EquipItem(7L, now, "potion-1")),
        )

        assertEquals("FORM_NOT_OBSERVED", observed.code)
        Mockito.verify(gateway).execute(Mockito.eq(1L), anyRequest(), Mockito.anyMap<String, String>())
    }

    @Test
    fun `semantic skill command projects the returned learned skill section`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        val projected = ArgumentCaptor.forClass(app.spammy.hof.external.parser.CharacterPageParseResult::class.java)
        Mockito.`when`(
            synchronizer.writeParsed(
                Mockito.eq(1L),
                Mockito.anyString(),
                capture(
                    projected,
                    app.spammy.hof.external.parser.CharacterPageParseResult(
                        app.spammy.hof.external.model.HofCharacter(id = ""),
                        emptyMap(),
                    ),
                ),
                Mockito.anySet(),
            ),
        ).thenReturn(Mockito.mock(CharacterDetailResponse::class.java))
        Mockito.`when`(
            gateway.execute(Mockito.eq(1L), anyRequest(), Mockito.anyMap<String, String>()),
        ).thenReturn(response(SKILL_PAGE), response(SKILL_RESULT_PAGE))

        assertIs<CharacterCommandObservation.Applied>(
            execute(CharacterCommand.LearnSkill(7L, now, "skill-2")),
        )

        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(2)).execute(
            Mockito.eq(1L),
            capture(requests, HofRequest(HofHttpMethod.GET, CHARACTER_URL)),
            Mockito.anyMap<String, String>(),
        )
        assertEquals(mapOf("newskill" to "skill-2", "learnskill" to "Learn"), requests.allValues[1].formFields)
        assertIs<CharacterSectionParseResult.Success>(projected.value.sections[CharacterPageSection.SKILLS])
    }

    @Test
    fun `semantic knockback submits both transient confirmations before observing the roster`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        val calls = AtomicInteger()
        Mockito.`when`(
            gateway.executeWithoutCharacterRosterObservation(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenAnswer {
            when (calls.getAndIncrement()) {
                0 -> deferredResponse(KNOCKBACK_PAGE)
                1 -> deferredResponse(KNOCKBACK_CONFIRMATION_PAGE)
                2 -> deferredResponse("<div>Knockback complete</div>")
                else -> deferredResponse(REPLACED_ROSTER_PAGE)
            }
        }

        val observed = assertIs<CharacterCommandObservation.KnockbackApplied>(
            execute(CharacterCommand.Knockback(7L, now, "마녀")),
        )

        assertEquals("11", observed.rosterAfter.single().hofCharacterId)
        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(4)).executeWithoutCharacterRosterObservation(
            Mockito.eq(1L),
            capture(requests, HofRequest(HofHttpMethod.GET, CHARACTER_URL)),
            Mockito.anyMap<String, String>(),
        )
        assertEquals(mapOf("knockback" to "Knockback"), requests.allValues[1].formFields)
        assertEquals(mapOf("knockback2" to "Yes"), requests.allValues[2].formFields)
    }

    @Test
    fun `semantic kick submits the real final kick confirmation before observing removal`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        val calls = AtomicInteger()
        Mockito.`when`(
            gateway.executeWithoutCharacterRosterObservation(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenAnswer {
            when (calls.getAndIncrement()) {
                0 -> deferredResponse(KICK_PAGE)
                1 -> deferredResponse(KICK_CONFIRMATION_PAGE)
                2 -> deferredResponse(KICK_FINAL_CONFIRMATION_PAGE)
                3 -> deferredResponse("<div>Kick complete</div>")
                else -> deferredResponse(ROSTER_AFTER_KICK_PAGE)
            }
        }

        val observed = assertIs<CharacterCommandObservation.KickApplied>(
            execute(CharacterCommand.Kick(7L, now, "소셜")),
        )

        assertEquals("22", observed.rosterAfter.single().hofCharacterId)
        val requests = ArgumentCaptor.forClass(HofRequest::class.java)
        Mockito.verify(gateway, Mockito.times(5)).executeWithoutCharacterRosterObservation(
            Mockito.eq(1L),
            capture(requests, HofRequest(HofHttpMethod.GET, CHARACTER_URL)),
            Mockito.anyMap<String, String>(),
        )
        assertEquals(mapOf("byebye" to "Kick"), requests.allValues[1].formFields)
        assertEquals(mapOf("byebye2" to "Dismiss"), requests.allValues[2].formFields)
        assertEquals(mapOf("kick" to "정말 해고"), requests.allValues[3].formFields)
    }

    @Test
    fun `semantic kick stops when the third confirmation form is absent`() {
        Mockito.`when`(accounts.findById(1L)).thenReturn(account)
        Mockito.`when`(cookies.findValueMapByAccountId(1L)).thenReturn(mapOf("PHPSESSID" to "session"))
        val calls = AtomicInteger()
        Mockito.`when`(
            gateway.executeWithoutCharacterRosterObservation(
                Mockito.eq(1L),
                anyRequest(),
                Mockito.anyMap<String, String>(),
            ),
        ).thenAnswer {
            when (calls.getAndIncrement()) {
                0 -> deferredResponse(KICK_PAGE)
                1 -> deferredResponse(KICK_CONFIRMATION_PAGE)
                else -> deferredResponse("<div>Third confirmation unavailable</div>")
            }
        }

        val observed = assertIs<CharacterCommandObservation.Rejected>(
            execute(CharacterCommand.Kick(7L, now, "소셜")),
        )

        assertEquals("FORM_NOT_OBSERVED", observed.code)
        Mockito.verify(gateway, Mockito.times(3)).executeWithoutCharacterRosterObservation(
            Mockito.eq(1L),
            anyRequest(),
            Mockito.anyMap<String, String>(),
        )
    }

    private fun execute(command: CharacterCommand): CharacterCommandObservation =
        commandAdapter.withSession(1L) { session ->
            session.execute(CharacterCommandContext(1L, 7L, "hof-10"), command)
        }

    private fun response(html: String) = HofHttpResponse(200, CHARACTER_URL, html, emptyMap())

    private fun deferredResponse(html: String) = DeferredCharacterRosterHofResponse(response(html), now)

    private fun anyPage(): app.spammy.hof.external.parser.CharacterPageParseResult =
        Mockito.any(app.spammy.hof.external.parser.CharacterPageParseResult::class.java)
            ?: app.spammy.hof.external.parser.CharacterPageParseResult(
                app.spammy.hof.external.model.HofCharacter(id = ""),
                emptyMap(),
            )

    private fun anyCandidates(): List<HofEquipmentCandidate> =
        Mockito.anyList<HofEquipmentCandidate>() ?: emptyList()

    private fun anyRequest(): HofRequest =
        Mockito.any(HofRequest::class.java) ?: HofRequest(HofHttpMethod.GET, CHARACTER_URL)

    private fun <T : Any> capture(captor: ArgumentCaptor<T>, fallback: T): T = captor.capture() ?: fallback

    companion object {
        private const val CHARACTER_URL = "http://sic.zerosic.com/ZeroHOF/index.php?char=hof-10"
        private const val BASE_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <input type="submit" name="showreset" value="Use">
            </form>
        """
        private const val RESET_SELECTOR_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <select name="itemUse"><option value="7510">Reset Crystal x 2</option></select>
              <input type="submit" name="resetVarious" value="Use">
            </form>
        """
        private const val EMPTY_RESET_SELECTOR_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <select name="itemUse"></select>
              <input type="submit" name="resetVarious" value="Use">
            </form>
        """
        private const val NORMAL_ITEM_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <input type="radio" name="item_no" value="potion-1">Potion
              <input type="submit" name="use_char_item" value="Use">
            </form>
        """
        private const val EQUIPMENT_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <script type="text/javascript">
              function Listtype_equip(mode) {
              switch(mode) {
              case "weapon":
              html = '<input type="radio" name="item_no" value="item-1">같은 이름<br />'; break;
              case "armor":
              html = '<input type="radio" name="item_no" value="item-2">같은 이름<br />'; break;
              }
              return(html);
              }
            </script>
            <form id="equip"><select name="type_equip"><option value="weapon">Weapon</option><option value="armor">Armor</option></select></form>
            <form action="?char=hof-10" method="post">
              <div id="list0">None.</div>
              <input type="submit" name="equip_item" value="Equip">
            </form>
        """
        private const val EQUIPMENT_PAGE_WITH_ORDINARY_ITEM = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <script type="text/javascript">
              function Listtype_equip(mode) {
              switch(mode) {
              case "weapon":
              html = '<input type="radio" name="item_no" value="item-1">Sword<br />'; break;
              }
              return(html);
              }
            </script>
            <form id="equip"><select name="type_equip"><option value="weapon">Weapon</option></select></form>
            <form action="?char=hof-10" method="post">
              <input type="radio" name="item_no" value="potion-1">Potion
              <input type="submit" name="use_char_item" value="Use">
            </form>
            <form action="?char=hof-10" method="post">
              <div id="list0"><input type="radio" name="item_no" value="item-1">Sword</div>
              <input type="submit" name="equip_item" value="Equip">
            </form>
        """
        private const val EQUIPMENT_RESULT_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <table>
              <tr><td class="align-right">Weapon :</td><td><input name="spot" value="weapon"></td></tr>
              <tr><td class="align-right">Shield :</td><td><input name="spot" value="shield"></td></tr>
              <tr><td class="align-right">Armor :</td><td><input name="spot" value="armor"></td></tr>
              <tr><td class="align-right">Head :</td><td><input name="spot" value="head"></td></tr>
              <tr><td class="align-right">Arms :</td><td><input name="spot" value="arms"></td></tr>
              <tr><td class="align-right">Feet :</td><td><input name="spot" value="feet"></td></tr>
              <tr><td class="align-right">Accessory1 :</td><td><input name="spot" value="accessory1"></td></tr>
              <tr><td class="align-right">Accessory2 :</td><td><input name="spot" value="accessory2"></td></tr>
              <tr><td class="align-right">Accessory3 :</td><td><input name="spot" value="accessory3"></td></tr>
              <tr><td class="align-right">Accessory4 :</td><td><input name="spot" value="accessory4"></td></tr>
              <tr><td class="align-right">Accessory5 :</td><td><input name="spot" value="accessory5"></td></tr>
              <tr><td class="align-right">Accessory6 :</td><td><input name="spot" value="accessory6"></td></tr>
            </table>
        """
        private const val SKILL_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <input type="radio" name="newskill" value="skill-1">Skill One
              <input type="radio" name="newskill" value="skill-2">Skill Two
              <input type="submit" name="learnskill" value="Learn">
            </form>
        """
        private const val SKILL_RESULT_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <h4>Skill</h4>
            <div class="u bold">Active</div>
            <table><tr><td>Skill Two</td></tr></table>
        """
        private const val KNOCKBACK_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <input type="submit" name="knockback" value="Knockback">
            </form>
        """
        private const val KNOCKBACK_CONFIRMATION_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <input type="submit" name="knockback2" value="Yes">
              <input type="submit" value="No">
            </form>
        """
        private const val REPLACED_ROSTER_PAGE = """
            <div class="character-card">
              <a href="?char=11"><img src="witch.gif"></a><br>
              마녀<br>
              Lv.60 Great Witch
            </div>
        """
        private const val KICK_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <input type="submit" name="byebye" value="Kick">
            </form>
        """
        private const val KICK_CONFIRMATION_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <input type="submit" name="byebye2" value="Dismiss">
              <input type="submit" value="Cancel">
            </form>
        """
        private const val KICK_FINAL_CONFIRMATION_PAGE = """
            <div class="carpet_frame">소셜<br>Lv.60 Social Knight</div>
            <form action="?char=hof-10" method="post">
              <input type="submit" class="btn" name="kick" value="정말 해고">
              <input type="submit" class="btn" value="역시 그만둘래">
            </form>
        """
        private const val ROSTER_AFTER_KICK_PAGE = """
            <a href="?char=22">다른 캐릭터</a>
        """
    }
}
