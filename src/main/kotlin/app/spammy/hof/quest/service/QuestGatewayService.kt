package app.spammy.hof.quest.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.model.HofFormField
import app.spammy.hof.external.model.HofRequestOrigin
import app.spammy.hof.quest.model.QuestSnapshot
import app.spammy.hof.quest.model.QuestState
import app.spammy.hof.quest.parser.QuestPageParser
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.jsoup.Jsoup
import org.springframework.stereotype.Service

@Service
class QuestGatewayService(
    private val executor: TownAuthenticatedExecutor,
    private val parser: QuestPageParser,
) {
    fun load(accountId: Long, origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE): List<QuestSnapshot> =
        executor.loadProjected(accountId, QUEST_URL, origin) { html, _, _ -> parser.parse(html) }

    fun accept(accountId: Long, actionNo: String, origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE): List<QuestSnapshot> =
        execute(accountId, actionNo, "get", QuestState.AVAILABLE, origin)

    fun claim(accountId: Long, actionNo: String, origin: HofRequestOrigin = HofRequestOrigin.INTERACTIVE): List<QuestSnapshot> =
        execute(accountId, actionNo, "complete", QuestState.CLAIMABLE, origin)

    private fun execute(accountId: Long, actionNo: String, action: String, requiredState: QuestState, origin: HofRequestOrigin): List<QuestSnapshot> {
        if (actionNo.isBlank() || actionNo.length > 200) invalid("퀘스트 action 번호가 올바르지 않습니다.")
        return executor.executeObservedGetProjected(
            accountId = accountId,
            pageUrl = QUEST_URL,
            requiredQueryFields = setOf("action", "no"),
            origin = origin,
            resolveQuery = { html, finalUrl, _ ->
                val current = parser.parse(html).singleOrNull { it.actionNo == actionNo && it.state == requiredState }
                    ?: invalid("현재 HOF에서 해당 퀘스트 action을 찾지 못했습니다. 목록을 새로고침해 주세요.")
                if (current.actionNo != actionNo) invalid("퀘스트 action이 변경되었습니다.")
                requireObservedLink(html, finalUrl, action, actionNo)
                listOf(HofFormField("action", action), HofFormField("no", actionNo))
            },
            projector = { html, _, _, _ -> parser.parse(html) },
        )
    }

    /** 링크 자체의 origin/path와 query 중복까지 검사해 재구성한 GET이 관측 링크와 동치임을 보장한다. */
    private fun requireObservedLink(html: String, finalUrl: String, action: String, actionNo: String) {
        val base = URI(finalUrl.ifBlank { QUEST_URL })
        val matches = Jsoup.parse(html, base.toString()).select("a[href]").mapNotNull { anchor ->
            val href = anchor.attr("href")
            runCatching {
                if (href.startsWith("?")) URI("${base.scheme}://${base.authority}${base.path}$href").normalize()
                else base.resolve(href).normalize()
            }.getOrNull()
        }.filter { uri ->
            uri.scheme == "http" && uri.host.equals("sic.zerosic.com", true) && uri.port in setOf(-1, 80) &&
                uri.userInfo == null && uri.fragment == null && uri.path == "/ZeroHOF/index.php" &&
                query(uri) == mapOf("menu" to listOf("quest"), "action" to listOf(action), "no" to listOf(actionNo))
        }
        if (matches.size != 1) invalid("현재 HOF 퀘스트 링크를 안전하게 확인하지 못했습니다.")
    }

    private fun query(uri: URI): Map<String, List<String>>? = runCatching {
        uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank)
            .map { part -> decode(part.substringBefore('=')) to decode(part.substringAfter('=', "")) }
            .groupBy({ it.first }, { it.second })
    }.getOrNull()

    private fun decode(value: String) = URLDecoder.decode(value, StandardCharsets.UTF_8)
    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)

    private companion object { const val QUEST_URL = "http://sic.zerosic.com/ZeroHOF/index.php?menu=quest" }
}
