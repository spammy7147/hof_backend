package app.spammy.hof.apprelease.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "app_releases")
class AppReleaseEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "platform", nullable = false, length = 20)
    val platform: String,

    @Column(name = "version_code", nullable = false)
    val versionCode: Long,

    @Column(name = "version_name", nullable = false, length = 100)
    val versionName: String,

    @Column(name = "file_name", nullable = false, length = 255)
    val fileName: String,

    @Column(name = "file_size", nullable = false)
    val fileSize: Long,

    @Column(name = "sha256", nullable = false, length = 64)
    val sha256: String,

    @Column(name = "git_revision", nullable = false, length = 40)
    val gitRevision: String,

    @Column(name = "jenkins_build", nullable = false)
    val jenkinsBuild: Long,

    @Column(name = "published_at", nullable = false)
    val publishedAt: Instant,
)
