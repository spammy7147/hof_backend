package app.spammy.hof.town.common.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.town.common.entity.TownFeatureLocationEntity
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.TownEntryPageParser
import app.spammy.hof.town.common.repository.TownFeatureLocationRepository
import app.spammy.hof.town.common.repository.TownFeatureLocationQueryRepository
import java.time.Clock
import java.time.Duration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ResolvedTownLocation(
    val featureId: TownFeatureId,
    val url: String,
)

/** 고정 code를 우선하고, 미확인 메뉴만 공용 캐시 또는 실제 마을 페이지에서 해석한다. */
@Service
class TownLocationResolver(
    private val repository: TownFeatureLocationRepository,
    private val queryRepository: TownFeatureLocationQueryRepository,
    private val parser: TownEntryPageParser,
    private val clock: Clock = Clock.systemUTC(),
    private val cacheTtl: Duration = Duration.ofDays(1),
) {
    @Transactional
    fun resolve(
        featureId: TownFeatureId,
        townEntryHtml: String? = null,
    ): ResolvedTownLocation {
        featureId.menuCode?.let { return resolved(featureId, "?menu=$it") }
        val now = clock.instant()
        val cached = queryRepository.findByFeatureIdForUpdate(featureId)
            ?: throw missing(featureId)
        val cachedHref = cached.href
        val observedAt = cached.observedAt
        if (cachedHref != null && observedAt != null && observedAt.isAfter(now.minus(cacheTtl))) {
            return resolved(featureId, cachedHref)
        }

        val discovered = townEntryHtml?.let(parser::parse)?.get(featureId)
            ?: throw missing(featureId)
        cached.href = discovered.href
        cached.observedAt = now
        repository.save(cached)
        return resolved(featureId, discovered.href)
    }

    private fun resolved(featureId: TownFeatureId, href: String): ResolvedTownLocation {
        if (!href.matches(PUBLIC_MENU_HREF)) {
            throw ApiException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "${featureId.displayName}의 안전한 위치를 찾지 못했습니다.",
            )
        }
        return ResolvedTownLocation(featureId, "$HOF_ENTRY_URL$href")
    }

    private fun missing(featureId: TownFeatureId): ApiException = ApiException(
        ErrorCode.RESOURCE_NOT_FOUND,
        "${featureId.displayName} 위치를 마을 페이지에서 찾지 못했습니다.",
    )

    private companion object {
        const val HOF_ENTRY_URL = "http://sic.zerosic.com/ZeroHOF/index.php"
        val PUBLIC_MENU_HREF = Regex("^[?]menu=[A-Za-z0-9_-]{1,80}$")
    }
}
