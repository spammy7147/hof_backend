package app.spammy.hof.external.client

import app.spammy.hof.external.model.HofBinaryResponse

/**
 * HOF 원본 서버에서 이미지 같은 바이너리 응답을 받아오는 gateway 인터페이스다.
 */
interface HofBinaryGateway {
    /**
     * 주어진 URL을 GET으로 호출하고 응답 bytes를 반환한다.
     */
    fun get(url: String, cookies: Map<String, String> = emptyMap()): HofBinaryResponse
}
