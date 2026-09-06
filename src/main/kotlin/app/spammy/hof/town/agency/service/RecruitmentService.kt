package app.spammy.hof.town.agency.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
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
        if (request.jobId.length > 256 || request.genderId.length > 256) {
            invalid("현재 HOF에서 선택할 수 없는 모집 항목입니다.")
        }
        if (!validName(name)) {
            invalid("캐릭터 이름은 영문·숫자 1칸, 한글·일본어 등은 2칸으로 계산해 1~16칸으로 입력해 주세요.")
        }
        val url = locations.resolve(TownFeatureId.TALENT_AGENCY).url
        return executor.executeResolvedTextProjected(
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
                Pair(
                    TownActionRequest(
                        actionId = current.actionId ?: invalid("현재 HOF 모집 action을 찾지 못했습니다."),
                        selections = listOf(TownActionSelection(job.id), TownActionSelection(gender.id)),
                    ),
                    parser.resolveNameField(html, finalUrl, current, name),
                )
            },
            projector = { html, finalUrl, result, page ->
                RecruitmentResponse.from(parser.parse(html, finalUrl, page, result))
            },
        )
    }

    /** HOF가 표시하는 이름 길이 규칙(일본어 문자는 2로 계산)을 동일하게 적용한다. */
    private fun validName(value: String): Boolean {
        if (value.isEmpty()) return false
        var width = 0
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val type = Character.getType(codePoint)
            if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() ||
                type == Character.SURROGATE.toInt() || type == Character.PRIVATE_USE.toInt()
            ) return false
            width += if (codePoint <= 0x7f) 1 else 2
            if (width > 16) return false
            offset += Character.charCount(codePoint)
        }
        return width in 1..16
    }

    private fun invalid(message: String): Nothing = throw ApiException(ErrorCode.INVALID_REQUEST, message)
}
