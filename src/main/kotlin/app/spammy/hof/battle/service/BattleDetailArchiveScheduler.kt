package app.spammy.hof.battle.service

import jakarta.annotation.PreDestroy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 상세 GET의 대기 시간이 자동화·복구의 예약 실행 thread를 점유하지 않게 한다. */
@Component
@Profile("dev | prod")
class BattleDetailArchiveScheduler(private val collector: BattleDetailArchiveCollector) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val collecting = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { operation ->
        Thread(operation, "battle-detail-archive").apply { isDaemon = true }
    }

    @Scheduled(fixedDelayString = "\${hof.battle-detail-archive.delay-ms:1000}")
    fun collectDue() {
        if (!collecting.compareAndSet(false, true)) return
        executor.execute {
            try {
                collector.collectDue()
            } catch (error: Exception) {
                log.warn("Battle detail archive poll unavailable errorType={}", error.javaClass.simpleName)
            } finally {
                collecting.set(false)
            }
        }
    }

    @PreDestroy
    fun close() { executor.shutdownNow() }
}
