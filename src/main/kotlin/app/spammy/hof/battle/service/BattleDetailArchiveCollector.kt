package app.spammy.hof.battle.service

import app.spammy.hof.battle.repository.BattleLogQueryRepository
import app.spammy.hof.common.time.TimeProvider
import app.spammy.hof.external.client.HofAutomationDeferredException
import app.spammy.hof.external.client.HofGateway
import app.spammy.hof.external.model.HofHttpMethod
import app.spammy.hof.external.model.HofRequest
import app.spammy.hof.external.model.HofRequestOrigin
import java.net.URI
import java.time.Instant
import java.util.UUID
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 기록에 결합된 공개 로그만 읽는다. 쿠키와 최신 상태 관측은 사용하지 않는다. */
internal fun safeBattleDetailUrl(value: String?): String? = runCatching {
    val uri = URI(value?.trim() ?: return null)
    val base = URI("https://hof.zerosic.com/index.php")
    if (uri.scheme != base.scheme || uri.host != base.host || uri.port != base.port ||
        uri.rawUserInfo != null || uri.path != base.path ||
        !Regex("(?:a?log)=\\d+").matches(uri.rawQuery.orEmpty())) return null
    URI(uri.scheme, uri.rawAuthority, uri.path, uri.rawQuery, null).toASCIIString()
}.getOrNull()

data class BattleDetailArchiveClaim(val id: Long, val accountId: Long, val url: String, val token: String)

/** 짧은 DB transaction에서 claim과 완료만 처리하며 HTTP 중에는 row 잠금을 유지하지 않는다. */
@Service
class BattleDetailArchiveStore(
    private val queries: BattleLogQueryRepository,
    private val time: TimeProvider,
) {
    @Transactional(readOnly = true)
    fun due(): List<Long> = queries.findDueDetailArchives(time.now())

    @Transactional
    fun claim(id: Long): BattleDetailArchiveClaim? {
        val row = queries.findForDetailArchiveUpdate(id) ?: return null
        val now = time.now()
        if (row.detailArchiveStatus != "PENDING" || row.detailArchiveNextAt?.isAfter(now) == true ||
            row.detailArchiveLeaseUntil?.isAfter(now) == true) return null
        val url = safeBattleDetailUrl(row.rawLogUrl)
        if (url == null || row.detailArchiveAttempts >= MAX_ATTEMPTS || !now.isBefore(row.createdAt.plusSeconds(BUDGET_SECONDS))) {
            row.detailArchiveStatus = "FAILED"
            row.detailArchiveNextAt = null
            return null
        }
        val token = UUID.randomUUID().toString()
        row.detailArchiveLeaseToken = token
        row.detailArchiveLeaseUntil = now.plusSeconds(LEASE_SECONDS)
        row.detailArchiveAttempts += 1
        return BattleDetailArchiveClaim(row.id, row.account.id, url, token)
    }

    @Transactional
    fun finish(claim: BattleDetailArchiveClaim, html: String?, retryAt: Instant? = null, attempted: Boolean = true) {
        val row = queries.findForDetailArchiveUpdate(claim.id) ?: return
        if (row.detailArchiveStatus != "PENDING" || row.detailArchiveLeaseToken != claim.token) return
        row.detailArchiveLeaseToken = null
        row.detailArchiveLeaseUntil = null
        if (!attempted) row.detailArchiveAttempts -= 1
        if (html != null) {
            row.detailHtml = html
            row.detailArchiveStatus = "READY"
            row.detailArchiveNextAt = null
        } else {
            val next = retryAt ?: time.now().plusSeconds(30)
            if (row.detailArchiveAttempts >= MAX_ATTEMPTS || !next.isBefore(row.createdAt.plusSeconds(BUDGET_SECONDS))) {
                row.detailArchiveStatus = "FAILED"
                row.detailArchiveNextAt = null
            } else row.detailArchiveNextAt = next
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val BUDGET_SECONDS = 600L
        const val LEASE_SECONDS = 120L
    }
}

/** 기존 계정 요청 제한을 지키며 과거 상세를 읽는다. 전투 제출과 결과 수렴은 호출하지 않는다. */
@Service
class BattleDetailArchiveCollector(
    private val store: BattleDetailArchiveStore,
    private val gateway: HofGateway,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun collectDue() {
        store.due().forEach { id ->
            val claim = store.claim(id) ?: return@forEach
            try {
                val response = gateway.execute(claim.accountId,
                    HofRequest(HofHttpMethod.GET, claim.url, origin = HofRequestOrigin.AUTOMATION, followRedirects = false, timeoutSeconds = 20))
                val document = Jsoup.parse(response.body)
                val contents = document.selectFirst("#contents")
                // 실제 HOF 상세의 양측 행동 셀과 행동 내용이 있어야 한다. 결과 요약은 제외한다.
                val detailed = contents != null && contents.select("td.ttd1").isNotEmpty() &&
                    contents.select("td.ttd2").isNotEmpty() &&
                    contents.select(".dmg, .support, .recover, .charge, .hpsp").isNotEmpty()
                val html = if (response.statusCode == 200 && safeBattleDetailUrl(response.finalUrl) == claim.url && detailed) {
                    archiveBattleHtml(contents.outerHtml(), detailed = true)
                } else null
                store.finish(claim, html)
            } catch (error: HofAutomationDeferredException) {
                store.finish(claim, null, error.retryAt, error.requestAttempted)
            } catch (error: Exception) {
                log.warn("Battle detail archive unavailable logId={} errorType={}", claim.id, error.javaClass.simpleName)
                store.finish(claim, null)
            }
        }
    }
}
