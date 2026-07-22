package app.spammy.hof.external.client

import app.spammy.hof.external.model.HofBattleType
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofRequestOrigin
import org.springframework.stereotype.Component

@Component
/**
 * HOF 원본 서버에 보낼 요청 모델을 만드는 factory다.
 */
class HofRequestFactory {
    /**
     * HOF 홈 페이지 요청을 만든다.
     */
    fun home(origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE): HofRequest = HofRequest(
        method = HofHttpMethod.GET,
        url = HOF_BASE_URL,
        origin = origin,
    )

    /**
     * HOF 로그인 form 제출 요청을 만든다.
     */
    fun login(
        id: String,
        password: String,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): HofRequest = HofRequest(
        method = HofHttpMethod.POST,
        url = HOF_BASE_URL,
        formFields = mapOf(
            "id" to id,
            "pass" to password,
            "Login" to "login",
        ),
        origin = origin,
    )

    /**
     * 특정 캐릭터 상세 페이지 요청을 만든다.
     */
    fun characterPage(
        characterId: String,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): HofRequest = HofRequest(
        method = HofHttpMethod.GET,
        url = "$HOF_BASE_URL?char=$characterId",
        origin = origin,
    )

    /**
     * 전투/모험 맵 목록이 있는 페이지 요청을 만든다.
     */
    fun battleMapPage(
        pageQuery: String,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): HofRequest = HofRequest(
        method = HofHttpMethod.GET,
        url = "$HOF_BASE_URL?$pageQuery",
        origin = origin,
    )

    fun questPage(origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE): HofRequest = HofRequest(
        method = HofHttpMethod.GET,
        url = "$HOF_BASE_URL?menu=quest",
        origin = origin,
    )

    fun questAction(
        action: String,
        actionNo: String,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): HofRequest {
        require(action in setOf("get", "complete")) { "Unsupported quest action: $action" }
        return HofRequest(
            method = HofHttpMethod.GET,
            url = "$HOF_BASE_URL?menu=quest",
            formFields = mapOf("action" to action, "no" to actionNo),
            origin = origin,
        )
    }

    /**
     * 캐릭터 저장 패턴 슬롯을 원본 세션에 로드하는 form 요청을 만든다.
     */
    fun loadPattern(
        characterId: String,
        slot: Int,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): HofRequest = HofRequest(
        method = HofHttpMethod.POST,
        url = "$HOF_BASE_URL?char=$characterId",
        formFields = mapOf(
            "patternno" to slot.toString(),
            "loadpattern" to "LOAD",
        ),
        origin = origin,
    )

    /**
     * 전투 실행 form 요청을 만든다.
     */
    fun battle(
        type: HofBattleType,
        code: String,
        characterIds: List<String>,
        battleCount: Int,
        origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE,
    ): HofRequest {
        val queryName = when (type) {
            HofBattleType.ADVENTURE -> "sp_common"
            HofBattleType.COMMON -> "common"
            HofBattleType.RAID -> "raid_common"
            HofBattleType.UNION -> "union"
        }
        val characterFields = characterIds.associate { "char_$it" to "1" }
        val submitName = when (battleCount) {
            1 -> "monster_battle"
            3 -> "monster_battle_10"
            else -> throw IllegalArgumentException("Unsupported battle count: $battleCount")
        }

        return HofRequest(
            method = HofHttpMethod.POST,
            url = "$HOF_BASE_URL?$queryName=$code",
            formFields = characterFields + (submitName to "Battle !"),
            origin = origin,
        )
    }

    private companion object {
        const val HOF_BASE_URL = "http://sic.zerosic.com/ZeroHOF/index.php"
    }
}
