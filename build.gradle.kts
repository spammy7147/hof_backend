plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("kapt") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.3.21"
	id("com.google.cloud.tools.jib") version "3.5.4"
}

group = "app.spammy"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("com.querydsl:querydsl-jpa:5.1.0:jakarta")
	implementation("com.google.firebase:firebase-admin:9.10.0")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jsoup:jsoup:1.22.2")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	// H2 #4302: DDL 연결 종료 후 다른 연결에서 CHECK 제약 검증이 실패하는 오류 수정.
	runtimeOnly("com.h2database:h2:2.5.250")
	runtimeOnly("org.postgresql:postgresql")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	kapt("com.querydsl:querydsl-apt:5.1.0:jakarta")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.kafka:spring-kafka-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

kapt {
	correctErrorTypes = true
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// JenkinsfileTest executes these files; changes must invalidate Gradle's test cache.
	inputs.files("Jenkinsfile", fileTree("scripts") { include("**/*.py") })
		.withPathSensitivity(PathSensitivity.RELATIVE)
	// Keep the complete Spring suite within the 4 GB Jenkins agent while avoiding the default 512 MB test OOM.
	maxHeapSize = "1g"
	systemProperty("spring.test.context.cache.maxSize", "8")
	// 연결 재사용 테스트는 운영 환경변수와 독립적으로 4초 정책을 검증한다.
	systemProperty("jdk.httpclient.keepalive.timeout", "4")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	// 환경변수로 지정한 JVM 옵션을 우선하고, 없으면 배포 이미지와 같은 기본값을 사용한다.
	environment("JAVA_TOOL_OPTIONS", providers.environmentVariable("JAVA_TOOL_OPTIONS")
		.orElse("-Djdk.httpclient.keepalive.timeout=4").get())
}

tasks.register<JavaExec>("generateBattleMapSeed") {
	dependsOn(tasks.testClasses)
	classpath = sourceSets["test"].runtimeClasspath
	mainClass.set("app.spammy.hof.battle.seed.BattleMapSeedGenerator")
}

jib {
	configurationName = "productionRuntimeClasspath"
	from {
		image = "eclipse-temurin:21-jre@sha256:7a65df4b22d2de92d4e04056e884f3b9122d70b21e2847fd66084278bd0ce037"
		platforms {
			platform {
				architecture = "amd64"
				os = "linux"
			}
		}
	}
	to { image = providers.environmentVariable("IMAGE").orElse("hof-backend:local").get() }
	container {
		mainClass = "app.spammy.hof.HofApplicationKt"
		workingDirectory = "/app"
		ports = listOf("8080")
		environment = mapOf("JAVA_TOOL_OPTIONS" to "-Djdk.httpclient.keepalive.timeout=4")
		labels = mapOf(
			"org.opencontainers.image.revision" to providers.environmentVariable("GIT_REVISION").orElse("local").get(),
			"app.jenkins.build" to providers.environmentVariable("BUILD_NUMBER").orElse("local").get(),
		)
	}
}
