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
    fun `unknown feature uses shared cached location`() {
        `when`(queryRepository.findByFeatureIdForUpdate(TownFeatureId.PANTHEON)).thenReturn(
            TownFeatureLocationEntity(
                TownFeatureId.PANTHEON,
                "?menu=pantheon",
                Instant.parse("2026-07-30T12:00:00Z"),
            ),
        )

        assertEquals(
            "http://sic.zerosic.com/ZeroHOF/index.php?menu=pantheon",
            resolver.resolve(TownFeatureId.PANTHEON).url,
        )
    }

    @Test
    fun `rejects an unsafe href even if it is already present in cache`() {
        `when`(queryRepository.findByFeatureIdForUpdate(TownFeatureId.PANTHEON)).thenReturn(
            TownFeatureLocationEntity(
                TownFeatureId.PANTHEON,
                "?menu=pantheon&action=buy",
                Instant.parse("2026-07-30T12:00:00Z"),
            ),
        )

        val error = assertFailsWith<ApiException> { resolver.resolve(TownFeatureId.PANTHEON) }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.errorCode)
    }

    @Test
    fun `discovery saves only the parsed public href`() {
        `when`(queryRepository.findByFeatureIdForUpdate(TownFeatureId.PANTHEON)).thenReturn(
            TownFeatureLocationEntity(TownFeatureId.PANTHEON, null, null),
        )
        val anyLocation = any(TownFeatureLocationEntity::class.java)
            ?: TownFeatureLocationEntity(TownFeatureId.PANTHEON, "?menu=placeholder", Instant.EPOCH)
        `when`(repository.save(anyLocation)).thenAnswer { it.arguments[0] }
        val html = "<a href='?menu=pantheon'>신전 거리(Pantheon)</a>"

        val location = resolver.resolve(TownFeatureId.PANTHEON, html)

        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=pantheon", location.url)
        val saved = ArgumentCaptor.forClass(TownFeatureLocationEntity::class.java)
        verify(repository).save(
            saved.capture() ?: TownFeatureLocationEntity(TownFeatureId.PANTHEON, "?menu=placeholder", Instant.EPOCH),
        )
        assertEquals("?menu=pantheon", saved.value.href)
        assertNull(saved.value.href?.takeIf { it.contains("token") || it.contains("action") })
    }

    @Test
    fun `missing discovery reports resource not found with feature name`() {
        `when`(queryRepository.findByFeatureIdForUpdate(TownFeatureId.PANTHEON)).thenReturn(
            TownFeatureLocationEntity(TownFeatureId.PANTHEON, null, null),
        )

        val error = assertFailsWith<ApiException> {
            resolver.resolve(TownFeatureId.PANTHEON, "<a href='?menu=other'>다른 곳</a>")
        }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.errorCode)
        assertEquals(true, error.message.orEmpty().contains(TownFeatureId.PANTHEON.displayName))
    }

    @Test
    fun `expired cache is refreshed from current town entry html`() {
        val cached = TownFeatureLocationEntity(TownFeatureId.PANTHEON, "?menu=oldPantheon", Instant.EPOCH)
        `when`(queryRepository.findByFeatureIdForUpdate(TownFeatureId.PANTHEON)).thenReturn(cached)

        val location = resolver.resolve(
            TownFeatureId.PANTHEON,
            "<a href='?menu=newPantheon'>신전 거리(Pantheon)</a>",
        )

        assertEquals("http://sic.zerosic.com/ZeroHOF/index.php?menu=newPantheon", location.url)
        assertEquals("?menu=newPantheon", cached.href)
        assertEquals(Instant.parse("2026-07-31T00:00:00Z"), cached.observedAt)
        verify(repository).save(cached)
    }

    @Test
    fun `expired cache is not used when current town html is unavailable`() {
        `when`(queryRepository.findByFeatureIdForUpdate(TownFeatureId.PANTHEON)).thenReturn(
            TownFeatureLocationEntity(TownFeatureId.PANTHEON, "?menu=stalePantheon", Instant.EPOCH),
        )

        val error = assertFailsWith<ApiException> { resolver.resolve(TownFeatureId.PANTHEON) }

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.errorCode)
        verify(repository, never()).save(anyLocation())
    }

    @Test
    fun `expired cache stays unchanged when current html no longer contains the feature`() {
        val cached = TownFeatureLocationEntity(TownFeatureId.PANTHEON, "?menu=stalePantheon", Instant.EPOCH)
        `when`(queryRepository.findByFeatureIdForUpdate(TownFeatureId.PANTHEON)).thenReturn(cached)

        assertFailsWith<ApiException> {
            resolver.resolve(TownFeatureId.PANTHEON, "<a href='?menu=other'>다른 시설</a>")
        }

        assertEquals("?menu=stalePantheon", cached.href)
        assertEquals(Instant.EPOCH, cached.observedAt)
        verify(repository, never()).save(anyLocation())
    }

    private fun anyLocation(): TownFeatureLocationEntity =
        any(TownFeatureLocationEntity::class.java)
            ?: TownFeatureLocationEntity(TownFeatureId.PANTHEON, "?menu=placeholder", Instant.EPOCH)
}
