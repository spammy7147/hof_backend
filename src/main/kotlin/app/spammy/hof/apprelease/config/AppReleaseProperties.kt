package app.spammy.hof.apprelease.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("hof.app-release")
data class AppReleaseProperties(
    val storageRoot: String = "/var/lib/hof/releases",
    val publishToken: String = "",
)
