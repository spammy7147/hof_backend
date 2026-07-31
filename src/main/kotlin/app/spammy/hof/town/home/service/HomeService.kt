package app.spammy.hof.town.home.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.model.HofFormField
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import app.spammy.hof.town.home.dto.HomeResponse
import app.spammy.hof.town.home.model.*
import app.spammy.hof.town.home.parser.HomePageParser
import org.springframework.stereotype.Service

@Service
class HomeService(
    private val executor: TownAuthenticatedExecutor,
    private val locations: TownLocationResolver,
    private val parser: HomePageParser,
) {
    fun load(accountId: Long, mode: HomeMode): HomeResponse {
        val url = url(mode)
        return executor.loadProjected(accountId, url) { html, finalUrl, page -> HomeResponse.from(parser.parse(mode, html, finalUrl, page)) }
    }

    fun runHomeQuest(accountId: Long, actionId: String): HomeResponse {
        val url = url(HomeMode.HOME)
        return executor.executeObservedGetProjected(
            accountId, url, setOf("action", "no"),
            resolveQuery = { html, finalUrl, page ->
                val quest = parser.parse(HomeMode.HOME, html, finalUrl, page).quests.singleOrNull { it.actionId == actionId }
                    ?: invalid("현재 HOF에서 해당 자택 퀘스트 action을 찾지 못했습니다.")
                val action = quest.action ?: invalid("현재 자택 퀘스트 action이 변경되었습니다.")
                val no = quest.actionNo ?: invalid("현재 자택 퀘스트 번호가 변경되었습니다.")
                val expectedState = if (action == "get") HomeQuestState.AVAILABLE else HomeQuestState.CLAIMABLE
                if (quest.state != expectedState) invalid("현재 자택 퀘스트 상태에서는 실행할 수 없습니다.")
                listOf(HofFormField("action", action), HofFormField("no", no))
            },
            projector = { html, finalUrl, result, page -> HomeResponse.from(parser.parse(HomeMode.HOME, html, finalUrl, page, result)) },
        )
    }

    fun restore(accountId: Long, actionId: String): HomeResponse {
        val url = url(HomeMode.REST)
        return executor.executeProjected(
            accountId, url,
            resolveAction = { html, finalUrl, page ->
                val action = parser.parse(HomeMode.REST, html, finalUrl, page).actions.singleOrNull { it.id == actionId && it.type == HomeActionType.RESTORE }
                    ?: invalid("현재 HOF에서 사용할 수 있는 휴식 action이 아닙니다.")
                TownActionRequest(action.formActionId ?: invalid("휴식 action 양식이 변경되었습니다."))
            },
            projector = { html, finalUrl, result, page -> HomeResponse.from(parser.parse(HomeMode.REST, html, finalUrl, page, result)) },
        )
    }

    private fun url(mode: HomeMode) = locations.resolve(
        if (mode == HomeMode.HOME) TownFeatureId.HOME_MANAGEMENT else TownFeatureId.REST_ROOM,
    ).url
    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
}
