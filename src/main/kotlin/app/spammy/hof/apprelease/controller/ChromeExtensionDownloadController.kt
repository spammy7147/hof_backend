package app.spammy.hof.apprelease.controller

import app.spammy.hof.apprelease.config.AppReleaseProperties
import app.spammy.hof.common.error.ApiException
import app.spammy.hof.common.error.ErrorCode
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class ChromeExtensionDownloadController(private val properties: AppReleaseProperties) {
    @GetMapping("/extension/lastest")
    fun downloadLatest(): ResponseEntity<Resource> {
        val file = try {
            val root = Path.of(properties.storageRoot).toRealPath()
            // Resolve the atomically replaced link once, so an ongoing download keeps its version.
            root.resolve("hof-chrome-extension-latest.zip").toRealPath().also {
                if (it.parent != root || !Files.isRegularFile(it) || !it.fileName.toString().endsWith(".zip")) {
                    throw notFound()
                }
            }
        } catch (_: NoSuchFileException) {
            throw notFound()
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/zip"))
            .contentLength(Files.size(file))
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName.toString()).build().toString())
            .body(FileSystemResource(file))
    }

    private fun notFound() = ApiException(ErrorCode.RESOURCE_NOT_FOUND, "등록된 Chrome 확장 프로그램이 없습니다.")
}
