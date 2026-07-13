package app.spammy.hof.character.service

import app.spammy.hof.character.dto.CharacterSyncEventResponse
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Service
class CharacterSyncEventService(
    private val emitterRegistry: CharacterSyncEmitterRegistry,
) {
    /**
     * 새 SSE emitter를 등록하고 현재 job 상태를 첫 이벤트로 보낸다.
     *
     * 앱은 이 첫 이벤트로 `pending/running/completed/failed` 상태를 즉시 맞춘 뒤,
     * 이후 `characterSynced` 이벤트를 받아 목록을 한 명씩 upsert한다.
     */
    fun connect(
        jobId: Long,
        initialEvent: CharacterSyncEventResponse,
    ): SseEmitter {
        val emitter = emitterRegistry.register(jobId)
        emitterRegistry.send(emitter, initialEvent)
        return emitter
    }

    /**
     * registry에 등록된 SSE client들에게 이벤트를 전달한다.
     */
    fun publish(event: CharacterSyncEventResponse) {
        emitterRegistry.publish(event)
    }

    /**
     * 특정 job의 SSE 연결들을 완료 처리한다.
     */
    fun complete(jobId: Long) {
        emitterRegistry.complete(jobId)
    }
}
