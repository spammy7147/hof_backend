package app.spammy.hof

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
/**
 * Spring Boot 애플리케이션의 시작점이다.
 */
class HofApplication

/**
 * 애플리케이션을 실행한다.
 */
fun main(args: Array<String>) {
	runApplication<HofApplication>(*args)
}
