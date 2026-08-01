package app.spammy.hof.town.common.service

import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import app.spammy.hof.town.common.entity.TownFeatureLocationEntity
import app.spammy.hof.town.common.model.TownFeatureId
import app.spammy.hof.town.common.parser.TownEntryPageParser
import app.spammy.hof.town.common.repository.TownFeatureLocationRepository
import app.spammy.hof.town.common.repository.TownFeatureLocationQueryRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any

class TownLocationResolverTest {
    private val repository = mock(TownFeatureLocationRepository::class.java)
    private val queryRepository = mock(TownFeatureLocationQueryRepository::class.java)
    private val resolver = TownLocationResolver(
        repository = repository,
        queryRepository = queryRepository,
        parser = TownEntryPageParser(),
        clock = Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun `known feature resolves directly without cache access`() {
        val location = resolver.resolve(TownFeatureId.SUNDRIES_STORE)

        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=buy2", location.url)
        verify(queryRepository, never()).findByFeatureIdForUpdate(TownFeatureId.SUNDRIES_STORE)
    }

    @Test
    fun `recruitment and housing resolve directly without town page discovery`() {
        assertEquals(
            "http://sic.zerosic.com/ZeroHOF/index.php?menu=recruit",
            resolver.resolve(TownFeatureId.TALENT_AGENCY).url,
        )
        assertEquals(
            "http://sic.zerosic.com/ZeroHOF/index.php?menu=housing",
            resolver.resolve(TownFeatureId.HOME_MANAGEMENT).url,
        )
        verify(queryRepository, never()).findByFeatureIdForUpdate(TownFeatureId.TALENT_AGENCY)
        verify(queryRepository, never()).findByFeatureIdForUpdate(TownFeatureId.HOME_MANAGEMENT)
    }

    @Test
    fun `unknown feature uses shared cached location`() {
        `when`(queryRepository.findByFeatureIdForUpdate(TownFeatureId.EVENT_SHOP)).thenReturn(
            TownFeatureLocationEntity(
                TownFeatureId.EVENT_SHOP,
                "?menu=event2026",
                Instant.parse("2026-07-30T12:00:00Z"),
            ),
        )

        assertEquals(
            "http://sic.zerosic.com/ZeroHOF/index.php?menu=event2026",
            resolver.resolve(TownFeatureId.EVENT_SHOP).url,
        )
    }

    @Test
    fun `rejects an unsafe href even if it is already present in cache`() {
        `when`(queryRepository.findByFeatureIdForUpdate(TownFeatureId.EVENT_SHOP)).thenReturn(
            TownFeatureLocationEntity(
                TownFeatureId.EVENT_SHOP,
                "?menu=event2026&action=buy",
                Instant.parse("2026-07-30T12:00:00Z"),
            ),
        )

        val error = assertFailsWith<ApiException> { resolver.resolve(TownFeatureId.EVENT_SHOP) }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.errorCode)
    }

    @Test
    fun `discovery saves only the parsed public href`() {
        `when`(queryRepository.findByFeatureIdForUpdate(TownFeatureId.EVENT_SHOP)).thenReturn(
            TownFeatureLocationEntity(TownFeatureId.EVENT_SHOP, null, null),
        )
        val anyLocation = any(TownFeatureLocationEntity::class.java)
            ?: TownFeatureLocationEntity(TownFeatureId.EVENT_SHOP, "?menu=placeholder", Instant.EPOCH)
        `when`(repository.save(anyLocation)).thenAnswer { it.arguments[0] }
        val html = "<a href='?menu=event2026'>특별 교환상점(Event Shop)</a>"

        val location = resolver.resolve(TownFeatureId.EVENT_SHOP, html)

        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=event2026", location.url)
        val saved = ArgumentCaptor.forClass(TownFeatureLocationEntity::class.java)
        verify(repository).save(
            saved.capture() ?: TownFeatureLocationEntity(TownFeatureId.EVENT_SHOP, "?menu=placeholder", Instant.EPOCH),
        )
        assertEquals("?menu=event2026", saved.value.href)
        assertNull(saved.value.href?.takeIf { it.contains("token") || it.contains("action") })
    }

    @Test
    fun `missing discovery reports resource not found with feature name`() {
        `when`(queryRepository.findByFeatureIdForUpdate(TownFeatureId.EVENT_SHOP)).thenReturn(
            TownFeatureLocationEntity(TownFeatureId.EVENT_SHOP, null, null),
        )

        val error = assertFailsWith<ApiException> {
            resolver.resolve(TownFeatureId.EVENT_SHOP, "<a href='?menu=other'>다른 곳</a>")
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.errorCode)
        assertEquals(true, error.message.orEmpty().contains(TownFeatureId.EVENT_SHOP.displayName))
    }

    @Test
    fun `expired cache is refreshed from current town entry html`() {
        val cached = TownFeatureLocationEntity(TownFeatureId.EVENT_SHOP, "?menu=oldEvent", Instant.EPOCH)
        `when`(queryRepository.findByFeatureIdForUpdate(TownFeatureId.EVENT_SHOP)).thenReturn(cached)

        val location = resolver.resolve(
            TownFeatureId.EVENT_SHOP,
            "<a href='?menu=newEvent'>특별 교환상점(Event Shop)</a>",
        )

        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=newEvent", location.url)
        assertEquals("?menu=newEvent", cached.href)
        assertEquals(Instant.parse("2026-07-31T00:00:00Z"), cached.observedAt)
        verify(repository).save(cached)
    }

    @Test
    fun `expired cache is not used when current town html is unavailable`() {
        `when`(queryRepository.findByFeatureIdForUpdate(TownFeatureId.EVENT_SHOP)).thenReturn(
            TownFeatureLocationEntity(TownFeatureId.EVENT_SHOP, "?menu=staleEvent", Instant.EPOCH),
        )

        val error = assertFailsWith<ApiException> { resolver.resolve(TownFeatureId.EVENT_SHOP) }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.errorCode)
        verify(repository, never()).save(anyLocation())
    }

    @Test
    fun `expired cache stays unchanged when current html no longer contains the feature`() {
        val cached = TownFeatureLocationEntity(TownFeatureId.EVENT_SHOP, "?menu=staleEvent", Instant.EPOCH)
        `when`(queryRepository.findByFeatureIdForUpdate(TownFeatureId.EVENT_SHOP)).thenReturn(cached)

        assertFailsWith<ApiException> {
            resolver.resolve(TownFeatureId.EVENT_SHOP, "<a href='?menu=other'>다른 시설</a>")
        }

        assertEquals("?menu=staleEvent", cached.href)
        assertEquals(Instant.EPOCH, cached.observedAt)
        verify(repository, never()).save(anyLocation())
    }

    private fun anyLocation(): TownFeatureLocationEntity =
        any(TownFeatureLocationEntity::class.java)
            ?: TownFeatureLocationEntity(TownFeatureId.EVENT_SHOP, "?menu=placeholder", Instant.EPOCH)
}
