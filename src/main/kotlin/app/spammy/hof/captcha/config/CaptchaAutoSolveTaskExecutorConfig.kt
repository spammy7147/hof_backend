package app.spammy.hof.captcha.config

import java.util.concurrent.ThreadPoolExecutor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
class CaptchaAutoSolveTaskExecutorConfig {
    @Bean("captchaAutoSolveTaskExecutor")
    fun captchaAutoSolveTaskExecutor(): TaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 1
        executor.maxPoolSize = 2
        executor.queueCapacity = 50
        executor.setThreadNamePrefix("captcha-auto-solve-")
        executor.setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
        executor.initialize()
        return executor
    }
}
