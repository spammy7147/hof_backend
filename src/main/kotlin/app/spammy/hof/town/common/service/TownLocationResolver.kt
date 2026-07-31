package app.spammy.hof.town.common.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.town.common.entity.TownFeatureLocationEntity
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.TownEntryPageParser
import app.spammy.hof.town.common.repository.TownFeatureLocationRepository
import app.spammy.hof.town.common.repository.TownFeatureLocationQueryRepository
import java.time.Clock
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
) {
    @Transactional
    fun resolve(
        featureId: TownFeatureId,
        townEntryHtml: String? = null,
    ): ResolvedTownLocation {
        featureId.menuCode?.let { return resolved(featureId, "?menu=$it") }
        queryRepository.findByFeatureId(featureId)?.let { return resolved(featureId, it.href) }

        val discovered = townEntryHtml?.let(parser::parse)?.get(featureId)
            ?: throw ApiException(
                ErrorCode.RESOURCE_NOT_FOUND,
                "${featureId.displayName} 위치를 마을 페이지에서 찾지 못했습니다.",
            )
        val saved = repository.save(
            TownFeatureLocationEntity(featureId, discovered.href, clock.instant()),
        )
        return resolved(featureId, saved.href)
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

    private companion object {
        const val HOF_ENTRY_URL = "http://sic.zerosic.com/ZeroHOF/index.php"
        val PUBLIC_MENU_HREF = Regex("^[?]menu=[A-Za-z0-9_-]{1,80}$")
    }
}
