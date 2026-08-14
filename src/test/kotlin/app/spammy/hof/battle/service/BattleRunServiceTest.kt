package app.spammy.hof.battle.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.account.repository.HofCookieRepository
import app.spammy.hof.account.service.HofCookieCipher
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.entity.BattleLogEntity
import app.spammy.hof.battle.entity.BattleLogLootEntity
import app.spammy.hof.battle.entity.BattleLogParticipantEntity
import app.spammy.hof.battle.entity.BattleMapEntity
import app.spammy.hof.battle.model.BattleMapKeyMode
import app.spammy.hof.battle.repository.BattleLogLootCommandRepository
import app.spammy.hof.battle.repository.BattleLogParticipantCommandRepository
import app.spammy.hof.battle.repository.BattleLogQueryRepository
import app.spammy.hof.battle.repository.BattleLogRepository
import app.spammy.hof.battle.repository.BattleMapQueryRepository
import app.spammy.hof.captcha.entity.CaptchaChallengeEntity
import app.spammy.hof.captcha.entity.CaptchaFormFieldEntity
import app.spammy.hof.captcha.repository.CaptchaChallengeRepository
import app.spammy.hof.captcha.repository.CaptchaFormFieldCommandRepository
import app.spammy.hof.captcha.repository.CaptchaQueryRepository
import app.spammy.hof.captcha.service.CaptchaChallengeParser
import app.spammy.hof.captcha.service.CaptchaImageFileStore
import app.spammy.hof.captcha.service.CaptchaImageManager
import app.spammy.hof.captcha.service.CaptchaService
import app.spammy.hof.captcha.service.StoredCaptchaImage
import app.spammy.hof.character.entity.CharacterEntity
import app.spammy.hof.character.repository.CharacterQueryRepository
import app.spammy.hof.character.service.SessionPatternLoadTracker
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofBinaryGateway
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.client.testAccountHofGateway
import app.spammy.hof.external.model.HofBattleOutcome
import app.spammy.hof.external.model.HofBinaryResponse
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.external.parser.BattleResultParser
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.external.parser.SharedBattleCooldownParser
import app.spammy.hof.status.entity.HofStatusSnapshotEntity
import app.spammy.hof.status.repository.HofStatusSnapshotQueryRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BattleRunServiceTest {
    private val now = Instant.parse("2026-07-08T00:00:00Z")
    private val account = HofAccountEntity(
        id = 1L,
        loginId = "abcd12",
        encryptedPassword = "qwer12",
        createdAt = now,
    )
    private val characters = listOf(
        testCharacter(id = 10L, hofCharacterId = "1683198503393759", name = "소셜2", job = "Social Knight"),
        testCharacter(id = 11L, hofCharacterId = "1683198503393760", name = "사제", job = "Cardinal"),
        testCharacter(id = 12L, hofCharacterId = "1683198503393761", name = "총잡이", job = "Desperado"),
        testCharacter(id = 13L, hofCharacterId = "1683198503393762", name = "수학자", job = "Mathematician"),
        testCharacter(id = 14L, hofCharacterId = "1683198503393763", name = "바드", job = "Troubadour"),
    )
    private val accountQueryRepository = Mockito.mock(AccountQueryRepository::class.java)
    private val cookieRepository = Mockito.mock(HofCookieRepository::class.java)
    private val cookieQueryRepository = Mockito.mock(CookieQueryRepository::class.java)
    private val characterQueryRepository = Mockito.mock(CharacterQueryRepository::class.java)
    private val battleMapQueryRepository = Mockito.mock(BattleMapQueryRepository::class.java)
    private val hofStatusSnapshotQueryRepository = Mockito.mock(HofStatusSnapshotQueryRepository::class.java)
    private val battleLogRepository = RecordingBattleLogRepository()
    private val participantRepository = RecordingBattleLogParticipantRepository()
    private val lootRepository = RecordingBattleLogLootRepository()
    private val battleLogQueryRepository = Mockito.mock(BattleLogQueryRepository::class.java)
    private val captchaChallengeRepository = RecordingCaptchaChallengeRepository()
    private val captchaFormFieldRepository = RecordingCaptchaFormFieldRepository()
    private val captchaQueryRepository = Mockito.mock(CaptchaQueryRepository::class.java)
    private val battleLogService = BattleLogService(
        battleLogRepository = battleLogRepository,
        participantRepository = participantRepository,
        lootRepository = lootRepository,
        battleLogQueryRepository = battleLogQueryRepository,
        battleMapQueryRepository = battleMapQueryRepository,
        timeProvider = TimeProvider { now },
    )
    private val gateway = FakeHofGateway()
    private val accountGateway = testAccountHofGateway(gateway, TimeProvider { now })
    private val binaryGateway = FakeHofBinaryGateway()
    private val captchaImageFileStore = FakeCaptchaImageFileStore()
    private val captchaService = CaptchaService(
        captchaChallengeRepository = captchaChallengeRepository,
        captchaFormFieldRepository = captchaFormFieldRepository,
        captchaQueryRepository = captchaQueryRepository,
        cookieRepository = cookieRepository,
        cookieQueryRepository = cookieQueryRepository,
        cookieCipher = HofCookieCipher(
            Base64.getEncoder().encodeToString(ByteArray(32) { index -> (index + 41).toByte() }),
        ),
        gateway = accountGateway,
        challengeParser = CaptchaChallengeParser(),
        loginStateParser = LoginStateParser(),
        imageManager = CaptchaImageManager(binaryGateway, captchaImageFileStore),
        timeProvider = TimeProvider { now },
    )
    private val service = BattleRunService(
        accountQueryRepository = accountQueryRepository,
        cookieQueryRepository = cookieQueryRepository,
        characterQueryRepository = characterQueryRepository,
        battleMapQueryRepository = battleMapQueryRepository,
        hofStatusSnapshotQueryRepository = hofStatusSnapshotQueryRepository,
        requestFactory = HofRequestFactory(),
        gateway = accountGateway,
        loginStateParser = LoginStateParser(),
        sharedBattleCooldownParser = SharedBattleCooldownParser(),
        battleResultParser = BattleResultParser(),
        battleLogService = battleLogService,
        captchaService = captchaService,
        sessionPatternLoadTracker = SessionPatternLoadTracker(),
        timeProvider = TimeProvider { now },
    )

    init {
        Mockito.`when`(
            battleMapQueryRepository.findStateForExecution(1L, "battle_map", "snow22"),
        ).thenReturn(battleMapState())
        Mockito.`when`(hofStatusSnapshotQueryRepository.findByAccountId(1L)).thenReturn(
            HofStatusSnapshotEntity(
                account = account,
                playerName = "《얼어붙은 손길》공민이",
                funds = 331_708_318L,
                timeCurrent = 6000,
                timeMax = 6000,
                work = "Nothing",
                auction = "Nothing",
                observedAt = now,
            ),
        )
    }

    @Test
    fun runBattleRejectsUnavailableStoredMapStatesBeforeHofRequest() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        val unavailableStates = listOf(
            null,
            battleMapState(visible = false),
            battleMapState(staticEnabled = false),
            battleMapState(cooldownUntil = now.plusSeconds(1)),
            battleMapState(keyCount = 0),
            battleMapState(availableCount = 0),
            battleMapState(attemptRemaining = 0),
            battleMapState(winRemaining = 0),
        )

        unavailableStates.forEachIndexed { index, state ->
            Mockito.`when`(
                battleMapQueryRepository.findStateForExecution(1L, "battle_map", "snow22"),
            ).thenReturn(state)
            assertEquals(
                ErrorCode.INVALID_REQUEST,
                assertFailsWith<ApiException> { service.runBattle(1L, runRequest()) }.errorCode,
                "unavailable state $index",
            )
        }
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun runBattleRejectsOnlyUnusableLimitedKeyState() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findValueMapByAccountId(1L)).thenReturn(emptyMap())

        listOf(BattleMapKeyMode.NOT_REQUIRED, BattleMapKeyMode.UNLIMITED, BattleMapKeyMode.UNKNOWN).forEach { keyMode ->
            Mockito.`when`(
                battleMapQueryRepository.findStateForExecution(1L, "battle_map", "snow22"),
            ).thenReturn(battleMapState(keyMode = keyMode))
            assertEquals(
                ErrorCode.HOF_SESSION_EXPIRED,
                assertFailsWith<ApiException> { service.runBattle(1L, runRequest()) }.errorCode,
            )
        }

        listOf(null, 0).forEach { keyCount ->
            Mockito.`when`(
                battleMapQueryRepository.findStateForExecution(1L, "battle_map", "snow22"),
            ).thenReturn(battleMapState(keyMode = BattleMapKeyMode.LIMITED, keyCount = keyCount))
            assertEquals(
                ErrorCode.INVALID_REQUEST,
                assertFailsWith<ApiException> { service.runBattle(1L, runRequest()) }.errorCode,
            )
        }
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun runBattleRejectsThreeBattlesWhenTheAccountMapStateHasNotObservedThatControl() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(
            battleMapQueryRepository.findStateForExecution(1L, "battle_map", "snow22"),
        ).thenReturn(battleMapState(supportsThreeBattles = false))

        val error = assertFailsWith<ApiException> {
            service.runBattle(1L, runRequest().copy(battleCount = 3))
        }

        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun runBattleLoadsRequestedPatternThenPostsBattleAndParsesResult() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findValueMapByAccountId(1L))
            .thenReturn(mapOf("PHPSESSID" to "abc"))
        Mockito.`when`(
            characterQueryRepository.findByAccountIdAndHofCharacterIds(
                1L,
                characters.map { it.hofCharacterId },
            ),
        ).thenReturn(characters)
        val response = service.runBattle(
            accountId = 1L,
            request = RunBattleRequest(
                categoryId = "battle_map",
                mapCode = "snow22",
                characterIds = characters.map { character -> character.hofCharacterId },
                patternLoads = characters.mapIndexed { index, character ->
                    BattlePatternLoadRequest(characterId = character.hofCharacterId, slot = index)
                },
                battleCount = 3,
            ),
        )

        assertEquals(HofBattleOutcome.VICTORY.name, response.outcome)
        assertEquals("《얼어붙은 손길》공민이은(는) 승리했다!", response.title)
        assertEquals(3660, response.funds)
        assertEquals(10590, response.experience)
        assertEquals(listOf("Silver Ingot x 1"), response.loots.map { it.name })
        assertEquals(0, response.enemy.hpCurrent)
        assertEquals(25955, response.ally.hpCurrent)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?char=1683198503393759", gateway.requests[0].url)
        assertEquals(mapOf("patternno" to "0", "loadpattern" to "LOAD"), gateway.requests[0].formFields)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?char=1683198503393763", gateway.requests[4].url)
        assertEquals(mapOf("patternno" to "4", "loadpattern" to "LOAD"), gateway.requests[4].formFields)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?common=snow22", gateway.requests[5].url)
        assertEquals(
            mapOf(
                "char_1683198503393759" to "1",
                "char_1683198503393760" to "1",
                "char_1683198503393761" to "1",
                "char_1683198503393762" to "1",
                "char_1683198503393763" to "1",
                "monster_battle_10" to "Battle !",
            ),
            gateway.requests[5].formFields,
        )
        assertEquals(List(6) { mapOf("PHPSESSID" to "abc") }, gateway.cookies)
        val savedLog = battleLogRepository.savedEntities.single()
        assertEquals("battle_map", savedLog.categoryIdSnapshot)
        assertEquals("snow22", savedLog.mapCodeSnapshot)
        assertEquals("VICTORY", savedLog.outcome)
    }

    @Test
    fun runBattleUsesStoredPlayerNameForOneSideVictoryOutcome() {
        prepareRunnableBattle()
        gateway.nextBattleBody = """
            <html><body>
              <div id="menu2">Funds : ${'$'} 1 Time : 10/10</div>
              <h1>《얼어붙은 손길》공민이은(는) 승리했다!</h1>
              <div>
                남은 HP : 25955/26197<br>
                생존자 : 5/5<br>
                총 데미지 : 365247
              </div>
            </body></html>
        """.trimIndent()

        val response = service.runBattle(1L, runRequest())

        assertEquals(HofBattleOutcome.VICTORY.name, response.outcome)
    }

    @Test
    fun `automation cooldown response is typed buffered and never parsed or logged`() {
        prepareRunnableBattle()
        gateway.nextBattleBody = sharedCooldownHtml("56초 후 전투 가능")

        val error = assertFailsWith<SharedBattleCooldownRejectedException> {
            service.runBattle(1L, runRequest(), HofRequestOrigin.AUTOMATION)
        }

        assertEquals(now.plusSeconds(58), error.retryAt)
        assertTrue(battleLogRepository.savedEntities.isEmpty())
    }

    @Test
    fun `automation confirmed cooldown without countdown uses buffered fallback`() {
        prepareRunnableBattle()
        gateway.nextBattleBody = sharedCooldownHtml("곧 전투 가능")

        val error = assertFailsWith<SharedBattleCooldownRejectedException> {
            service.runBattle(1L, runRequest(), HofRequestOrigin.AUTOMATION)
        }

        assertEquals(now.plusSeconds(62), error.retryAt)
        assertTrue(battleLogRepository.savedEntities.isEmpty())
    }

    @Test
    fun `interactive cooldown exposes source retry seconds`() {
        prepareRunnableBattle()
        gateway.nextBattleBody = sharedCooldownHtml("56초 후 전투 가능")

        val error = assertFailsWith<ApiException> {
            service.runBattle(1L, runRequest(), HofRequestOrigin.INTERACTIVE)
        }

        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode)
        assertEquals(56, error.retryAfterSeconds)
        assertEquals("1분 공유 쿨타임이 남아 있습니다.", error.message)
        assertTrue(battleLogRepository.savedEntities.isEmpty())
    }

    @Test
    fun `union battle rejects logged out ranking page before recording a battle log`() {
        prepareRunnableBattle()
        Mockito.`when`(
            battleMapQueryRepository.findStateForExecution(1L, "union", "0003"),
        ).thenReturn(battleMapState(categoryId = "union", mapCode = "0003"))
        gateway.nextBattleBody = """
            <html><body>
              <div id="menu"><a href="index.php?menu=login">로그인</a></div>
              <h4>최근의 보스전 승리 랭킹(Recent Battles)</h4>
              <a href="index.php?alog=123">도적소탕</a>
            </body></html>
        """.trimIndent()

        val error = assertFailsWith<ApiException> {
            service.runBattle(
                1L,
                runRequest().copy(categoryId = "union", mapCode = "0003"),
                HofRequestOrigin.AUTOMATION,
            )
        }

        assertEquals(ErrorCode.HOF_SESSION_EXPIRED, error.errorCode)
        assertTrue(battleLogRepository.savedEntities.isEmpty())
    }

    @Test
    fun runBattleSkipsPatternsAlreadyLoadedForTheSameSessionOnTheNextMap() {
        val cookies = mapOf("PHPSESSID" to "abc")
        val characterIds = characters.map { character -> character.hofCharacterId }
        val patternLoads = characters.mapIndexed { index, character ->
            BattlePatternLoadRequest(characterId = character.hofCharacterId, slot = index)
        }
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findValueMapByAccountId(1L)).thenReturn(cookies)
        Mockito.`when`(
            characterQueryRepository.findByAccountIdAndHofCharacterIds(1L, characterIds),
        ).thenReturn(characters)
        Mockito.`when`(
            battleMapQueryRepository.findStateForExecution(1L, "battle_map", "second"),
        ).thenReturn(battleMapState(mapCode = "second"))

        service.runBattle(
            1L,
            RunBattleRequest("battle_map", "snow22", characterIds, patternLoads),
        )
        service.runBattle(
            1L,
            RunBattleRequest("battle_map", "second", characterIds, patternLoads),
        )

        assertEquals(7, gateway.requests.size)
        assertEquals(5, gateway.requests.count { request -> request.url.contains("?char=") })
        assertEquals(
            listOf(
                "http://sic.zerosic.com/ZeroHOF/index.php?common=snow22",
                "http://sic.zerosic.com/ZeroHOF/index.php?common=second",
            ),
            gateway.requests.filterNot { request -> request.url.contains("?char=") }.map { request -> request.url },
        )
    }

    @Test
    fun runBattleReturnsAndRecordsEveryRoundFromThreeBattleResponse() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findValueMapByAccountId(1L))
            .thenReturn(mapOf("PHPSESSID" to "abc"))
        Mockito.`when`(
            characterQueryRepository.findByAccountIdAndHofCharacterIds(
                1L,
                characters.map { it.hofCharacterId },
            ),
        ).thenReturn(characters)
        gateway.nextBattleBody = threeBattleResultHtml()

        val response = service.runBattle(
            accountId = 1L,
            request = RunBattleRequest(
                categoryId = "battle_map",
                mapCode = "snow22",
                characterIds = characters.map { character -> character.hofCharacterId },
                battleCount = 3,
            ),
        )

        assertEquals(HofBattleOutcome.VICTORY.name, response.outcome)
        assertEquals(3, response.rounds.size)
        assertEquals(listOf(102450, 90686, 93937), response.rounds.map { it.ally.totalDamage })
        assertEquals(listOf(emptyList(), emptyList(), listOf("Stone x 2")), response.rounds.map { round -> round.loots.map { it.name } })

        assertEquals(listOf(102450, 90686, 93937), battleLogRepository.savedEntities.map { it.allyTotalDamage })
    }

    @Test
    fun runBattleStopsAndDoesNotRecordLogWhenVigilanteCaptchaAppears() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findValueMapByAccountId(1L))
            .thenReturn(mapOf("PHPSESSID" to "abc"))
        Mockito.`when`(cookieQueryRepository.findByAccountId(1L)).thenReturn(
            listOf(
                HofCookieEntity(
                    account = account,
                    name = "PHPSESSID",
                    value = "abc",
                    updatedAt = now,
                ),
            ),
        )
        Mockito.`when`(
            characterQueryRepository.findByAccountIdAndHofCharacterIds(
                1L,
                characters.map { it.hofCharacterId },
            ),
        ).thenReturn(characters)
        captchaChallengeRepository.nextId = 7L
        gateway.nextBattleBody = """
            <html><body>
              <div id="menu2">Funds : ${'$'} 1 Time : 10/10</div>
              <font color="red">자경단</font>
              <p>자경단에서 통행증을 발급받아주세요.</p>
            </body></html>
        """.trimIndent()
        val exception = assertFailsWith<ApiException> {
            service.runBattle(
                accountId = 1L,
                request = RunBattleRequest(
                    categoryId = "battle_map",
                    mapCode = "snow22",
                    characterIds = characters.map { character -> character.hofCharacterId },
                    patternLoads = characters.mapIndexed { index, character ->
                        BattlePatternLoadRequest(characterId = character.hofCharacterId, slot = index)
                    },
                    battleCount = 3,
                ),
            )
        }

        assertEquals(ErrorCode.CAPTCHA_REQUIRED, exception.errorCode)
        assertTrue(battleLogRepository.savedEntities.isEmpty())
        val savedCaptcha = captchaChallengeRepository.savedEntities.single()
        assertEquals("DETECTED", savedCaptcha.status)
        assertEquals("자경단에서 통행증을 발급받아주세요.", savedCaptcha.prompt)
        assertEquals(gateway.requests.last().url, savedCaptcha.sourceUrl)
        assertNull(savedCaptcha.imageUrl)
        assertNull(savedCaptcha.submitUrl)
        assertEquals("POST", savedCaptcha.submitMethod)
        assertEquals("captcha", savedCaptcha.answerFieldName)
        assertEquals(0, gateway.requests.count { it.url.contains("menu=police") })
        assertEquals(emptyList(), binaryGateway.urls)
    }

    private class RecordingBattleLogRepository : BattleLogRepository {
        val savedEntities = mutableListOf<BattleLogEntity>()

        override fun <S : BattleLogEntity> save(entity: S): S = entity.also { savedEntities += it }

        override fun <S : BattleLogEntity> saveAll(entities: Iterable<S>): List<S> =
            entities.toList().also { savedEntities += it }

        override fun delete(entity: BattleLogEntity) = Unit

        override fun deleteAll(entities: Iterable<BattleLogEntity>) = Unit

        override fun flush() = Unit
    }

    private class RecordingBattleLogParticipantRepository : BattleLogParticipantCommandRepository {
        override fun <S : BattleLogParticipantEntity> save(entity: S): S = entity

        override fun <S : BattleLogParticipantEntity> saveAll(entities: Iterable<S>): List<S> = entities.toList()

        override fun delete(entity: BattleLogParticipantEntity) = Unit

        override fun deleteAll(entities: Iterable<BattleLogParticipantEntity>) = Unit

        override fun flush() = Unit
    }

    private class RecordingBattleLogLootRepository : BattleLogLootCommandRepository {
        override fun <S : BattleLogLootEntity> save(entity: S): S = entity

        override fun <S : BattleLogLootEntity> saveAll(entities: Iterable<S>): List<S> = entities.toList()

        override fun delete(entity: BattleLogLootEntity) = Unit

        override fun deleteAll(entities: Iterable<BattleLogLootEntity>) = Unit

        override fun flush() = Unit
    }

    private class RecordingCaptchaChallengeRepository : CaptchaChallengeRepository {
        val savedEntities = mutableListOf<CaptchaChallengeEntity>()
        var nextId: Long? = null

        @Suppress("UNCHECKED_CAST")
        override fun <S : CaptchaChallengeEntity> save(entity: S): S {
            val saved = CaptchaChallengeEntity(
                id = nextId ?: entity.id,
                account = entity.account,
                status = entity.status,
                prompt = entity.prompt,
                imageUrl = entity.imageUrl,
                sourceUrl = entity.sourceUrl,
                answer = entity.answer,
                createdAt = entity.createdAt,
                answeredAt = entity.answeredAt,
                submitUrl = entity.submitUrl,
                submitMethod = entity.submitMethod,
                answerFieldName = entity.answerFieldName,
            )
            savedEntities += saved
            return saved as S
        }

        override fun <S : CaptchaChallengeEntity> saveAll(entities: Iterable<S>): List<S> = entities.map(::save)

        override fun delete(entity: CaptchaChallengeEntity) = Unit

        override fun deleteAll(entities: Iterable<CaptchaChallengeEntity>) = Unit

        override fun flush() = Unit
    }

    private class RecordingCaptchaFormFieldRepository : CaptchaFormFieldCommandRepository {
        override fun <S : CaptchaFormFieldEntity> save(entity: S): S = entity

        override fun <S : CaptchaFormFieldEntity> saveAll(entities: Iterable<S>): List<S> = entities.toList()

        override fun delete(entity: CaptchaFormFieldEntity) = Unit

        override fun deleteAll(entities: Iterable<CaptchaFormFieldEntity>) = Unit

        override fun flush() = Unit
    }

    private fun testCharacter(
        id: Long,
        hofCharacterId: String,
        name: String,
        job: String,
    ): CharacterEntity = CharacterEntity(
        id = id,
        account = account,
        hofCharacterId = hofCharacterId,
        name = name,
        job = job,
        updatedAt = now,
    )

    private fun runRequest(): RunBattleRequest =
        RunBattleRequest(
            categoryId = "battle_map",
            mapCode = "snow22",
            characterIds = listOf(characters.first().hofCharacterId),
        )

    private fun prepareRunnableBattle() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(cookieQueryRepository.findValueMapByAccountId(1L))
            .thenReturn(mapOf("PHPSESSID" to "abc"))
        Mockito.`when`(
            characterQueryRepository.findByAccountIdAndHofCharacterIds(
                1L,
                listOf(characters.first().hofCharacterId),
            ),
        ).thenReturn(listOf(characters.first()))
    }

    private fun sharedCooldownHtml(countdown: String): String = """
        <html><body><div id="menu2">Funds : ${'$'} 1 Time : 10/10</div><div class="error">
          대형 데이터를 읽는 전투를 실행한 상태입니다. ($countdown)
        </div></body></html>
    """.trimIndent()

    private fun battleMapState(
        categoryId: String = "battle_map",
        mapCode: String = "snow22",
        visible: Boolean = true,
        staticEnabled: Boolean = true,
        keyCount: Int? = null,
        keyMode: BattleMapKeyMode = if (keyCount == null) BattleMapKeyMode.UNKNOWN else BattleMapKeyMode.LIMITED,
        availableCount: Int? = null,
        attemptRemaining: Int? = null,
        winRemaining: Int? = null,
        cooldownUntil: Instant? = null,
        supportsThreeBattles: Boolean = true,
    ): AccountBattleMapStateEntity =
        AccountBattleMapStateEntity(
            account = account,
            battleMap = BattleMapEntity(
                id = 100L,
                categoryId = categoryId,
                mapCode = mapCode,
                name = "Frosty Mountain- 대충산",
                normalizedName = "frosty mountain- 대충산",
                enabled = staticEnabled,
                createdAt = now,
                updatedAt = now,
            ),
            keyMode = keyMode,
            keyCount = keyCount,
            availableCount = availableCount,
            attemptRemaining = attemptRemaining,
            winRemaining = winRemaining,
            cooldownUntil = cooldownUntil,
            supportsThreeBattles = supportsThreeBattles,
            rawHref = "index.php?common=$mapCode",
            visible = visible,
            lastSeenAt = now,
        )

    private fun threeBattleResultHtml(): String =
        """
            <html><body>
              <div id="menu2">Funds : ${'$'} 1 Time : 10/10</div>
              <h2>Show Detail( 6 turns. )</h2>
              <a href="http://sic.zerosic.com/ZeroHOF/index.php?common=gb0#">로그 주소 복사</a>
              《얼어붙은 손길》공민이은(는) 승리했다!
              <div>
                남은 HP : 0/820
                생존자 : 0/5
                총 데미지 : 0
              </div>
              <div>
                남은 HP : 26087/26087
                생존자 : 5/5
                총 데미지 : 102450
                턴 : 6/100
                획득 경험치 : 100
                획득 Funds : $ 200
              </div>
              <h2>Show Detail( 6 turns. )</h2>
              <a href="http://sic.zerosic.com/ZeroHOF/index.php?common=gb0#">로그 주소 복사</a>
              《얼어붙은 손길》공민이은(는) 승리했다!
              <div>
                남은 HP : 0/780
                생존자 : 0/5
                총 데미지 : 0
              </div>
              <div>
                남은 HP : 26087/26087
                생존자 : 5/5
                총 데미지 : 90686
                턴 : 6/100
                획득 경험치 : 100
                획득 Funds : $ 200
              </div>
              <h2>Show Detail( 6 turns. )</h2>
              <a href="http://sic.zerosic.com/ZeroHOF/index.php?common=gb0#">로그 주소 복사</a>
              《얼어붙은 손길》공민이은(는) 승리했다!
              <div>
                남은 HP : 0/820
                생존자 : 0/5
                총 데미지 : 0
              </div>
              <div>
                남은 HP : 26087/26087
                생존자 : 5/5
                총 데미지 : 93937
                턴 : 6/100
                획득 경험치 : 100
                획득 Funds : $ 200
                전리품
                Stone x 2
              </div>
            </body></html>
        """.trimIndent()

    private class FakeHofGateway : HofGateway {
        val requests = mutableListOf<HofRequest>()
        val cookies = mutableListOf<Map<String, String>>()
        var nextBattleBody: String? = null
        var policeBody: String? = null

        override fun execute(
            accountId: Long,
            request: HofRequest,
            cookies: Map<String, String>,
        ): HofHttpResponse {
            requests += request
            this.cookies += cookies
            val body = if (request.url.contains("?char=")) {
                """<div>Funds : $ 1 Time : 10/10</div>"""
            } else if (request.url.contains("menu=police")) {
                policeBody ?: "<html><body>OK</body></html>"
            } else {
                nextBattleBody ?: """
                    <html><body>
                      <div id="menu2">Funds : ${'$'} 1 Time : 10/10</div>
                      <h2>Show Detail( 36 turns. )</h2>
                      <h1>《얼어붙은 손길》공민이은(는) 승리했다!</h1>
                      <div>
                        남은 HP : 0/51930
                        생존자 : 0/7
                        총 데미지 : 3044
                      </div>
                      <div>
                        남은 HP : 25955/26197
                        생존자 : 5/5
                        총 데미지 : 365247
                        턴 : 36/100
                        획득 경험치 : 10590
                        획득 Funds : $ 3,660
                        전리품
                        Silver Ingot x 1
                        소셜 신앙심 변동 :+12
                      </div>
                    </body></html>
                """.trimIndent()
            }

            return HofHttpResponse(
                statusCode = 200,
                finalUrl = request.url,
                body = body,
                setCookies = emptyMap(),
            )
        }
    }

    private class FakeHofBinaryGateway : HofBinaryGateway {
        val urls = mutableListOf<String>()

        override fun get(
            accountId: Long,
            origin: HofRequestOrigin,
            url: String,
            cookies: Map<String, String>,
        ): HofBinaryResponse {
            urls += url
            return HofBinaryResponse(
                statusCode = 200,
                finalUrl = url,
                contentType = "image/png",
                body = byteArrayOf(1, 2, 3),
            )
        }
    }

    private class FakeCaptchaImageFileStore : CaptchaImageFileStore {
        override fun save(
            accountId: Long,
            challengeId: Long,
            preparationVersion: Int,
            contentType: String,
            bytes: ByteArray,
        ) {
        }

        override fun read(
            accountId: Long,
            challengeId: Long,
            preparationVersion: Int,
        ): StoredCaptchaImage? =
            StoredCaptchaImage(
                contentType = "image/png",
                bytes = byteArrayOf(1, 2, 3),
            )

        override fun delete(
            accountId: Long,
            challengeId: Long,
            preparationVersion: Int,
        ) {
        }
    }
}
