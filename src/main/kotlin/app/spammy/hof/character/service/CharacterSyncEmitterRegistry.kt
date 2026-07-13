package app.spammy.hof.character.service

import app.spammy.hof.character.dto.CharacterSyncEventResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

@Component
class CharacterSyncEmitterRegistry {
    private val log = LoggerFactory.getLogger(CharacterSyncEmitterRegistry::class.java)
    private val emitters = ConcurrentHashMap<Long, CopyOnWriteArraySet<SseEmitter>>()

    /**
     * job 하나에 여러 SSE 연결을 허용한다.
     *
     * 앱 화면 재마운트, 웹/네이티브 동시 확인, 네트워크 재연결이 겹칠 수 있으므로
     * emitter는 jobId 기준 set으로 관리하고 종료 콜백에서 즉시 정리한다.
     */
    fun register(jobId: Long): SseEmitter {
        val emitter = SseEmitter(SSE_TIMEOUT_MILLIS)
        emitters.computeIfAbsent(jobId) { CopyOnWriteArraySet() }.add(emitter)

        emitter.onCompletion { remove(jobId, emitter) }
        emitter.onTimeout {
            remove(jobId, emitter)
            emitter.complete()
        }
        emitter.onError {
            remove(jobId, emitter)
        }

        return emitter
    }

    /**
     * 특정 jobId에 연결된 모든 SSE client에게 이벤트를 전송한다.
     */
    fun publish(event: CharacterSyncEventResponse) {
        val currentEmitters = emitters[event.jobId] ?: return
        currentEmitters.forEach { emitter ->
            send(emitter, event)
        }
    }

    /**
     * SSE emitter 하나에 이벤트를 보낸다.
     */
    fun send(
        emitter: SseEmitter,
        event: CharacterSyncEventResponse,
    ) {
        runCatching {
            emitter.send(
                SseEmitter.event()
                    .id(event.eventId.toString())
                    .name(event.eventType)
                    .data(event),
            )
        }.onFailure {
            log.debug(
                "Character sync SSE send failed jobId={} eventType={} error={}",
                event.jobId,
                event.eventType,
                it.message,
            )
            remove(event.jobId, emitter)
        }
    }

    /**
     * jobId에 연결된 모든 SSE emitter를 완료 처리한다.
     */
    fun complete(jobId: Long) {
        emitters.remove(jobId)?.forEach { emitter ->
            runCatching { emitter.complete() }
        }
    }

    /**
     * 종료되거나 실패한 emitter를 registry에서 제거한다.
     */
    private fun remove(
        jobId: Long,
        emitter: SseEmitter,
    ) {
        emitters[jobId]?.remove(emitter)
        if (emitters[jobId]?.isEmpty() == true) {
            emitters.remove(jobId)
        }
    }

    private companion object {
        const val SSE_TIMEOUT_MILLIS = 30 * 60 * 1_000L
    }
}
