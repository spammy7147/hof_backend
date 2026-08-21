package app.spammy.hof.character.service

import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.parser.CharacterRosterParser
import app.spammy.hof.external.parser.LoginStateParser
import app.spammy.hof.town.common.service.AccountHofMutationFence
import java.net.URI
import java.time.Instant
import org.springframework.stereotype.Service

/** 로그인된 HOF 홈 응답 하나를 캐릭터 roster와 앱 재조회 표식으로 원자적으로 반영한다. */
@Service
class CharacterRosterObservationService(
    private val loginStateParser: LoginStateParser,
    private val rosterParser: CharacterRosterParser,
    private val mutationFence: AccountHofMutationFence,
    private val transactions: CharacterRosterObservationTransaction,
) {
    /**
     * query 없는 HOF index.php의 로그인 응답만 받아 roster를 조정한다.
     *
     * 요청 시작 시각으로 순서를 비교하므로 늦게 도착한 과거 응답이 최신 roster를 되돌릴 수 없다.
     */
    fun observe(
        accountId: Long,
        response: HofHttpResponse,
        observedAt: Instant,
    ): Boolean = mutationFence.execute(accountId) {
        if (!isHofHome(response.finalUrl)) return@execute false
        if (!loginStateParser.parse(response.body).isLoggedIn) return@execute false
        val roster = rosterParser.parse(response.body)
        if (roster.isEmpty()) return@execute false

        transactions.record(accountId, roster, observedAt)
    }

    private fun isHofHome(value: String): Boolean = runCatching { URI(value).normalize() }
        .getOrNull()
        ?.let { uri ->
            uri.scheme.equals(HOF_SCHEME, ignoreCase = true) &&
                uri.host.equals(HOF_HOST, ignoreCase = true) &&
                uri.port in setOf(-1, 80) &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                uri.path == HOF_PATH
        } == true

    private companion object {
        const val HOF_SCHEME = "http"
        const val HOF_HOST = "sic.zerosic.com"
        const val HOF_PATH = "/ZeroHOF/index.php"
    }
}
