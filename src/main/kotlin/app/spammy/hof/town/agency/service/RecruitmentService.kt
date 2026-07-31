package app.spammy.hof.town.agency.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.external.model.HofFormField
import app.spammy.hof.town.agency.dto.RecruitCharacterRequest
import app.spammy.hof.town.agency.dto.RecruitmentResponse
import app.spammy.hof.town.agency.parser.RecruitmentPageParser
import app.spammy.hof.town.common.model.TownActionRequest
import app.spammy.hof.town.common.model.TownActionSelection
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.service.TownAuthenticatedExecutor
import app.spammy.hof.town.common.service.TownLocationResolver
import org.springframework.stereotype.Service

@Service
class RecruitmentService(
    private val executor: TownAuthenticatedExecutor,
    private val locations: TownLocationResolver,
    private val parser: RecruitmentPageParser,
) {
    fun load(accountId: Long): RecruitmentResponse {
        val url = locations.resolve(TownFeatureId.TALENT_AGENCY).url
        return executor.loadProjected(accountId, url) { html, finalUrl, page ->
            RecruitmentResponse.from(parser.parse(html, finalUrl, page))
        }
    }

    fun recruit(accountId: Long, request: RecruitCharacterRequest): RecruitmentResponse {
        val name = request.name.trim()
        if (name.length !in 1..16) invalid("캐릭터 이름은 1~16자로 입력해 주세요.")
        val url = locations.resolve(TownFeatureId.TALENT_AGENCY).url
        return executor.executeRecruitmentProjected(
            accountId = accountId,
            pageUrl = url,
            resolve = { html, finalUrl, page ->
                val current = parser.parse(html, finalUrl, page)
                if (!current.recruitmentAvailable) invalid("현재 HOF에서 모집 양식을 확인하지 못했습니다.")
                if (current.currentCharacters != null && current.capacity != null && current.currentCharacters >= current.capacity) {
                    invalid("현재 캐릭터 정원이 가득 찼습니다.")
                }
                val job = current.jobs.singleOrNull { it.id == request.jobId }
                    ?: invalid("현재 HOF에서 선택할 수 없는 직업입니다.")
                val gender = current.genders.singleOrNull { it.id == request.genderId }
                    ?: invalid("현재 HOF에서 선택할 수 없는 성별입니다.")
                Triple(
                    TownActionRequest(
                        actionId = current.actionId ?: invalid("현재 HOF 모집 action을 찾지 못했습니다."),
                        selections = listOf(TownActionSelection(job.id), TownActionSelection(gender.id)),
                    ),
                    HofFormField(current.nameField ?: invalid("현재 HOF 이름 입력란을 찾지 못했습니다."), name),
                    current.nameMaxLength,
                )
            },
            projector = { html, finalUrl, result, page ->
                RecruitmentResponse.from(parser.parse(html, finalUrl, page, result))
            },
        )
    }

    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
}
