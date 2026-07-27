plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("kapt") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.3.21"
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
	runtimeOnly("com.h2database:h2")
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
	// Bound unique Spring test contexts so the default 512 MB test worker does not retain the entire suite graph.
	systemProperty("spring.test.context.cache.maxSize", "16")
}

tasks.register<Test>("fastTest") {
	description = "Runs tests that do not start a Spring application or JPA test context."
	group = "verification"
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	useJUnitPlatform()
	exclude(
		"**/HofApplicationTests*.class",
		"**/AccountQueryRepositoryTest*.class",
		"**/AuthApiSecurityTest*.class",
		"**/RefreshTokenQueryRepositoryTest*.class",
		"**/UnifiedAutomationApiSecurityTest*.class",
		"**/AccountAutomationLeaseServiceTest*.class",
		"**/AutomationOutboxLocalReplayIntegrationTest*.class",
		"**/AutomationRecoverySchedulerTransactionIntegrationTest*.class",
		"**/AutomationJobQueryRepositoryTest*.class",
		"**/AutomationProfileQueryRepositoryTest*.class",
		"**/TypedAutomationPersistenceTest*.class",
		"**/AutomationAfterCommitWakeupIntegrationTest*.class",
		"**/AutomationDailyPreflightMapSyncTest*.class",
		"**/AutomationDailyPreflightTest*.class",
		"**/AutomationJobServiceTest*.class",
		"**/AutomationProfileServiceTest*.class",
		"**/BattleMapAutomationHandlerTest*.class",
		"**/BattleMapAutomationProgressStorePersistenceTest*.class",
		"**/QuestAutomationHandlerTest*.class",
		"**/QuestAutomationProgressStorePersistenceTest*.class",
		"**/TypedRuntimeWakeAtomicityIntegrationTest*.class",
		"**/UnifiedAutomationTypedLifecycleBridgeIntegrationTest*.class",
		"**/BattleLogQueryRepositoryTest*.class",
		"**/BattleMapQueryRepositoryTest*.class",
		"**/BattleMapSeedTest*.class",
		"**/BattleLogServiceTest*.class",
		"**/BattleMapIdentityResolverTest*.class",
		"**/BattleMapServiceTest*.class",
		"**/CaptchaQueryRepositoryTest*.class",
		"**/CaptchaServicePersistenceTest*.class",
		"**/CharacterQueryRepositoryTest*.class",
		"**/QueryDslConfigTest*.class",
		"**/HttpsEnforcementTest*.class",
		"**/PartyPresetQueryRepositoryTest*.class",
		"**/PartyPresetFolderQueryRepositoryTest*.class",
		"**/PartyPresetConcurrencyTest*.class",
		"**/PartyPresetCatalogServiceTest*.class",
		"**/PartyPresetServiceTest*.class",
		"**/FreshSchemaTest*.class",
		"**/QuestApiSecurityTest*.class",
	)
}

tasks.register<JavaExec>("generateBattleMapSeed") {
	dependsOn(tasks.testClasses)
	classpath = sourceSets["test"].runtimeClasspath
	mainClass.set("app.spammy.hof.battle.seed.BattleMapSeedGenerator")
}
