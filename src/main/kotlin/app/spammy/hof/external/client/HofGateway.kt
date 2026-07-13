package app.spammy.hof.external.client

import app.spammy.hof.external.model.HofHttpResponse
import app.spammy.hof.external.model.HofRequest

/**
 * HOF 원본 HTML 요청을 실행하는 gateway 인터페이스다.
 */
interface HofGateway {
    /**
     * GET/POST 요청과 쿠키를 받아 HOF HTML 응답을 반환한다.
     */
    fun execute(request: HofRequest, cookies: Map<String, String> = emptyMap()): HofHttpResponse
}
