package app.spammy.hof.town.auction.service

import app.spammy.hof.town.auction.config.AuctionCollectorProperties
import app.spammy.hof.town.shop.repository.ShopQueryRepository
import java.time.Clock
import java.time.Duration
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class AuctionLeaseService(private val leases: ShopQueryRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun acquire(owner: String, now: java.time.Instant): Boolean = leases.tryAcquire(
        JOB, owner, now, now.plus(Duration.ofMinutes(20)), now.minus(Duration.ofMinutes(55)), now.minus(Duration.ofMinutes(5)),
    ) == 1L
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun success(owner: String, now: java.time.Instant) { check(leases.markSuccess(JOB, owner, now) == 1L) }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failure(owner: String) { leases.releaseFailure(JOB, owner) }
    companion object { const val JOB = "AUCTION_HOURLY" }
}

@Service
class AuctionCollector(
    private val properties: AuctionCollectorProperties,
    private val service: AuctionService,
    private val observations: AuctionObservationService,
    private val lease: AuctionLeaseService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val owner = UUID.randomUUID().toString()

    @Scheduled(cron = "\${hof.auction.collector-cron:0 0 * * * *}")
    fun hourlyTick() {
        val accountId = properties.collectorAccountId
        if (accountId <= 0) return
        val now = clock.instant()
        if (!lease.acquire(owner, now)) return
        try {
            // Collector는 이 GET projection 외에 어떠한 form/action도 실행하지 않는다.
            observations.observe(service.collectorPage(accountId), now)
            lease.success(owner, now)
        } catch (failure: Exception) {
            runCatching { lease.failure(owner) }.exceptionOrNull()?.let(failure::addSuppressed)
            logger.warn("Auction hourly observation failed; retaining last-known market data: {}", failure.javaClass.simpleName)
        }
    }
    private companion object { val logger = LoggerFactory.getLogger(AuctionCollector::class.java) }
}
