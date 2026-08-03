package app.spammy.hof.apprelease

import app.spammy.hof.apprelease.repository.AppReleaseQueryRepository
import app.spammy.hof.apprelease.repository.AppReleaseRepository
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class AppReleaseApiTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val repository: AppReleaseRepository,
    @Autowired private val queries: AppReleaseQueryRepository,
) {
    private val apkBytes = "signed-apk-fixture".toByteArray()
    private val fileName = "hof-app-1.0.0-build-46.apk"

    @BeforeEach
    fun prepareFile() {
        Files.createDirectories(storageRoot)
        Files.write(storageRoot.resolve(fileName), apkBytes)
    }

    @AfterEach
    fun cleanDatabaseAndFiles() {
        repository.deleteAll(queries.findAll())
        Files.list(storageRoot).use { files -> files.forEach(Files::deleteIfExists) }
    }

    @Test
    fun jenkinsPublishesAndClientsReadAndDownloadReleaseWithoutBearerToken() {
        mockMvc.perform(
            post("/internal/app-releases/android")
                .header("X-HOF-Release-Token", PUBLISH_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishBody()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.versionCode").value(46))
            .andExpect(jsonPath("$.versionName").value("1.0.0+46"))
            .andExpect(jsonPath("$.downloadUrl").value("/api/app-releases/android/46/download"))

        mockMvc.perform(get("/api/app-releases/android/latest").param("currentVersionCode", "45"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.updateAvailable").value(true))
            .andExpect(jsonPath("$.release.sha256").value(sha256(apkBytes)))

        mockMvc.perform(get("/api/app-releases/android/46/download"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/vnd.android.package-archive"))
            .andExpect(content().bytes(apkBytes))
            .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, apkBytes.size.toLong()))
            .andExpect(header().string(HttpHeaders.ETAG, "\"${sha256(apkBytes)}\""))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\""))

        mockMvc.perform(get("/api/app-releases/android/latest/download"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("application/vnd.android.package-archive"))
            .andExpect(content().bytes(apkBytes))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\""))
    }

    @Test
    fun publishRequiresDedicatedTokenAndMatchingFileMetadata() {
        mockMvc.perform(
            post("/internal/app-releases/android")
                .header("X-HOF-Release-Token", "wrong-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishBody()),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("RELEASE_PUBLISH_UNAUTHORIZED"))

        mockMvc.perform(
            post("/internal/app-releases/android")
                .header("X-HOF-Release-Token", PUBLISH_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(publishBody(sha256 = "0".repeat(64))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RELEASE_CONFLICT"))
    }

    @Test
    fun repeatedIdenticalPublishIsIdempotent() {
        repeat(2) {
            mockMvc.perform(
                post("/internal/app-releases/android")
                    .header("X-HOF-Release-Token", PUBLISH_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(publishBody()),
            ).andExpect(status().isOk)
        }

        kotlin.test.assertEquals(1, queries.findAll().size)
    }

    private fun publishBody(sha256: String = sha256(apkBytes)): String =
        """
        {
          "versionCode": 46,
          "versionName": "1.0.0+46",
          "fileName": "$fileName",
          "fileSize": ${apkBytes.size},
          "sha256": "$sha256",
          "gitRevision": "0123456789abcdef0123456789abcdef01234567",
          "jenkinsBuild": 46
        }
        """.trimIndent()

    companion object {
        private const val PUBLISH_TOKEN = "test-release-publish-token"
        private val storageRoot: Path = Files.createTempDirectory("hof-app-releases-")

        @JvmStatic
        @DynamicPropertySource
        fun releaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("hof.app-release.storage-root") { storageRoot.toString() }
            registry.add("hof.app-release.publish-token") { PUBLISH_TOKEN }
        }

        @JvmStatic
        @AfterAll
        fun removeStorageRoot() {
            Files.deleteIfExists(storageRoot)
        }

        private fun sha256(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
