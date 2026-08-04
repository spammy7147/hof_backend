package app.spammy.hof.captcha.config

import java.util.concurrent.ThreadPoolExecutor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
class CaptchaFeedbackTaskExecutorConfig {
    @Bean("captchaFeedbackTaskExecutor")
    fun captchaFeedbackTaskExecutor(): TaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 1
        executor.maxPoolSize = 1
        executor.queueCapacity = 200
        executor.setThreadNamePrefix("captcha-feedback-")
        executor.setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
        executor.initialize()
        return executor
    }
}
