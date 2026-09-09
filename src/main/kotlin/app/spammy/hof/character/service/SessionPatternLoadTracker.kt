package app.spammy.hof.character.service

import app.spammy.hof.battle.dto.BattlePatternLoadRequest
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofRequest
import java.net.URI
import java.net.URLDecoder
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 현재 백엔드 프로세스가 계정별 HOF 세션에 마지막으로 로드한 캐릭터 패턴을 추적한다.
 *
 * 원본 쿠키 값은 보관하지 않고 세션 경계를 판별하기 위한 해시만 메모리에 유지한다.
 */
@Component
class SessionPatternLoadTracker {
    private val locks = Array(LOCK_STRIPE_COUNT) { ReentrantLock(true) }
    private val states = ConcurrentHashMap<Long, AccountState>()

    /** 모든 캐릭터 변경 경로와 응답 유실에서 기존 슬롯의 재사용 근거를 제출 전에 폐기한다. */
    fun beforeRequest(accountId: Long, request: HofRequest) {
        if (request.method != HofHttpMethod.POST) return
        val characterId = URI(request.url).rawQuery.orEmpty().split('&')
            .map { field -> field.split('=', limit = 2) }
            .firstOrNull { field -> field.size == 2 && field[0] == "char" }
            ?.get(1)?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            ?.takeIf(String::isNotBlank) ?: return
        locks[Math.floorMod(accountId.hashCode(), locks.size)].withLock {
            states[accountId]?.loadedSlots?.remove(characterId)
        }
    }

    /**
     * 같은 계정의 패턴 변경과 전투 제출이 서로 끼어들지 않도록 세션 작업을 직렬화한다.
     */
    fun <T> withSession(
        accountId: Long,
        cookies: Map<String, String>,
        operation: (SessionState) -> T,
    ): T = locks[Math.floorMod(accountId.hashCode(), locks.size)].withLock {
        val fingerprint = fingerprint(cookies)
        val accountState = states.compute(accountId) { _, current ->
            current?.takeIf { state -> state.fingerprint == fingerprint }
                ?: AccountState(fingerprint = fingerprint, loadedSlots = mutableMapOf())
        }!!
        operation(SessionState(accountState.loadedSlots))
    }

    class SessionState internal constructor(
        private val loadedSlots: MutableMap<String, Int>,
    ) {
        /** 현재 세션 상태와 다른 캐릭터 패턴만 요청 순서대로 반환한다. */
        fun requiredLoads(requested: List<BattlePatternLoadRequest>): List<BattlePatternLoadRequest> =
            requested.filter { pattern -> loadedSlots[pattern.characterId] != pattern.slot }

        /** HOF가 성공으로 응답한 패턴만 현재 상태로 기록한다. */
        fun recordLoaded(pattern: BattlePatternLoadRequest) {
            loadedSlots[pattern.characterId] = pattern.slot
        }
    }

    private fun fingerprint(cookies: Map<String, String>): String {
        val canonical = cookies.toSortedMap().entries.joinToString("\u0000") { (name, value) ->
            "$name\u0001$value"
        }
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private data class AccountState(
        val fingerprint: String,
        val loadedSlots: MutableMap<String, Int>,
    )

    private companion object {
        const val LOCK_STRIPE_COUNT = 64
    }
}
