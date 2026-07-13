package app.spammy.hof.character.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
/**
 * 캐릭터 동기화 작업을 백그라운드 스레드에서 실행하기 위한 설정이다.
 */
class CharacterSyncTaskExecutorConfig {
    /**
     * 캐릭터 상세 페이지를 순차 파싱하는 전용 TaskExecutor를 만든다.
     */
    @Bean("characterSyncTaskExecutor")
    fun characterSyncTaskExecutor(): TaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 1
        executor.maxPoolSize = 2
        executor.queueCapacity = 20
        executor.setThreadNamePrefix("character-sync-")
        executor.initialize()
        return executor
    }
}
