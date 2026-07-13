package app.spammy.hof.battle.service

import app.spammy.hof.account.entity.HofAccountEntity
import app.spammy.hof.account.entity.HofCookieEntity
import app.spammy.hof.account.repository.AccountQueryRepository
import app.spammy.hof.account.repository.CookieQueryRepository
import app.spammy.hof.account.repository.HofCookieRepository
import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.battle.dto.RunBattleRequest
import app.spammy.hof.battle.entity.AccountBattleMapStateEntity
import app.spammy.hof.battle.entity.BattleLogEntity
import app.spammy.hof.battle.entity.BattleLogLootEntity
import app.spammy.hof.battle.entity.BattleLogParticipantEntity
import app.spammy.hof.battle.entity.BattleMapEntity
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
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofBinaryGateway
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.client.HofRequestFactory
import app.spammy.hof.external.model.HofBattleOutcome
import app.spammy.hof.external.model.HofBinaryResponse
import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.parser.BattleResultParser
import app.spammy.hof.external.parser.LoginStateParser
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        testCharacter(id = 10L, hofCharacterId = "1683198503393759", name = "《얼어붙은 손길》공민이", job = "Social Knight"),
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
    private val binaryGateway = FakeHofBinaryGateway()
    private val captchaImageFileStore = FakeCaptchaImageFileStore()
    private val captchaService = CaptchaService(
        captchaChallengeRepository = captchaChallengeRepository,
        captchaFormFieldRepository = captchaFormFieldRepository,
        captchaQueryRepository = captchaQueryRepository,
        cookieRepository = cookieRepository,
        cookieQueryRepository = cookieQueryRepository,
        gateway = gateway,
        challengeParser = CaptchaChallengeParser(),
        imageManager = CaptchaImageManager(binaryGateway, captchaImageFileStore),
        timeProvider = TimeProvider { now },
    )
    private val service = BattleRunService(
        accountQueryRepository = accountQueryRepository,
        cookieQueryRepository = cookieQueryRepository,
        characterQueryRepository = characterQueryRepository,
        battleMapQueryRepository = battleMapQueryRepository,
        requestFactory = HofRequestFactory(),
        gateway = gateway,
        loginStateParser = LoginStateParser(),
        battleResultParser = BattleResultParser(),
        battleLogService = battleLogService,
        captchaService = captchaService,
        timeProvider = TimeProvider { now },
    )

    init {
        Mockito.`when`(
            battleMapQueryRepository.findStateForExecution(1L, "battle_map", "snow22"),
        ).thenReturn(battleMapState())
    }

    @Test
    fun runBattleRejectsMissingOrInvisibleStoredMapStateBeforeHofRequest() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(
            battleMapQueryRepository.findStateForExecution(1L, "battle_map", "snow22"),
        ).thenReturn(null)

        val missing = assertFailsWith<ApiException> { service.runBattle(1L, runRequest()) }
        assertEquals(ErrorCode.INVALID_REQUEST, missing.errorCode)

        Mockito.`when`(
            battleMapQueryRepository.findStateForExecution(1L, "battle_map", "snow22"),
        ).thenReturn(battleMapState(visible = false))
        val invisible = assertFailsWith<ApiException> { service.runBattle(1L, runRequest()) }
        assertEquals(ErrorCode.INVALID_REQUEST, invisible.errorCode)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun runBattleRejectsStaticDisabledMapAndActiveCooldownBeforeHofRequest() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        Mockito.`when`(
            battleMapQueryRepository.findStateForExecution(1L, "battle_map", "snow22"),
        ).thenReturn(battleMapState(staticEnabled = false))
        assertEquals(
            ErrorCode.INVALID_REQUEST,
            assertFailsWith<ApiException> { service.runBattle(1L, runRequest()) }.errorCode,
        )

        Mockito.`when`(
            battleMapQueryRepository.findStateForExecution(1L, "battle_map", "snow22"),
        ).thenReturn(battleMapState(cooldownUntil = now.plusSeconds(1)))
        assertEquals(
            ErrorCode.INVALID_REQUEST,
            assertFailsWith<ApiException> { service.runBattle(1L, runRequest()) }.errorCode,
        )
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun runBattleRejectsZeroKeyAndEveryZeroRemainingLimitBeforeHofRequest() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
        val unavailableStates = listOf(
            battleMapState(keyCount = 0),
            battleMapState(availableCount = 0),
            battleMapState(attemptRemaining = 0),
            battleMapState(winRemaining = 0),
        )

        unavailableStates.forEach { state ->
            Mockito.`when`(
                battleMapQueryRepository.findStateForExecution(1L, "battle_map", "snow22"),
            ).thenReturn(state)
            assertEquals(
                ErrorCode.INVALID_REQUEST,
                assertFailsWith<ApiException> { service.runBattle(1L, runRequest()) }.errorCode,
            )
        }
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun runBattleLoadsRequestedPatternThenPostsBattleAndParsesResult() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
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
    fun runBattleReturnsAndRecordsEveryRoundFromThreeBattleResponse() {
        Mockito.`when`(accountQueryRepository.findById(1L)).thenReturn(account)
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
              <font color="red">자경단</font>
              <p>자경단에서 통행증을 발급받아주세요.</p>
            </body></html>
        """.trimIndent()
        gateway.policeBody = """
            <html><body>
              <form action="/ZeroHOF/index.php?menu=police" method="post">
                <img src="simple-php-captcha.php?_CAPTCHA=1">
                <input type="text" name="AnswerV">
                <input type="submit" name="AnswerOut" value="입니다.">
              </form>
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
        assertEquals("PENDING", savedCaptcha.status)
        assertEquals("자경단에서 통행증을 발급받아주세요.", savedCaptcha.prompt)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=police", savedCaptcha.sourceUrl)
        assertEquals("http://sic.zerosic.com/ZeroHOF/simple-php-captcha.php?_CAPTCHA=1", savedCaptcha.imageUrl)
        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=police", savedCaptcha.submitUrl)
        assertEquals("POST", savedCaptcha.submitMethod)
        assertEquals("AnswerV", savedCaptcha.answerFieldName)
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

    private fun battleMapState(
        visible: Boolean = true,
        staticEnabled: Boolean = true,
        keyCount: Int? = null,
        availableCount: Int? = null,
        attemptRemaining: Int? = null,
        winRemaining: Int? = null,
        cooldownUntil: Instant? = null,
    ): AccountBattleMapStateEntity =
        AccountBattleMapStateEntity(
            account = account,
            battleMap = BattleMapEntity(
                id = 100L,
                categoryId = "battle_map",
                mapCode = "snow22",
                name = "Frosty Mountain- 대충산",
                normalizedName = "frosty mountain- 대충산",
                enabled = staticEnabled,
                createdAt = now,
                updatedAt = now,
            ),
            keyCount = keyCount,
            availableCount = availableCount,
            attemptRemaining = attemptRemaining,
            winRemaining = winRemaining,
            cooldownUntil = cooldownUntil,
            rawHref = "index.php?common=snow22",
            visible = visible,
            lastSeenAt = now,
        )

    private fun threeBattleResultHtml(): String =
        """
            <html><body>
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

        override fun execute(request: HofRequest, cookies: Map<String, String>): HofHttpResponse {
            requests += request
            this.cookies += cookies
            val body = if (request.url.contains("?char=")) {
                """<div>Funds : $ 1 Time : 10/10</div>"""
            } else if (request.url.contains("menu=police")) {
                policeBody ?: "<html><body>OK</body></html>"
            } else {
                nextBattleBody ?: """
                    <html><body>
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

        override fun get(url: String, cookies: Map<String, String>): HofBinaryResponse {
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
            contentType: String,
            bytes: ByteArray,
        ) {
        }

        override fun read(
            accountId: Long,
            challengeId: Long,
        ): StoredCaptchaImage? =
            StoredCaptchaImage(
                contentType = "image/png",
                bytes = byteArrayOf(1, 2, 3),
            )

        override fun delete(
            accountId: Long,
            challengeId: Long,
        ) {
        }
    }
}
