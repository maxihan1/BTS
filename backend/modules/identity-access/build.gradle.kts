// identity-access BC 서브모듈 빌드 스크립트 — Spring Boot + Security + Argon2 의존성 선언

// Kotlin 버전: 2.0.10 (detekt 1.23.7 호환 상한 — build.gradle.kts 루트 주석 참고)
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

group = "com.atlas.bts"
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
    // 핵심 프레임워크 (의존성 카탈로그 §2.1)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // 인증 / 보안 (의존성 카탈로그 §2.3)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("de.mkammerer:argon2-jvm:2.11")

    // LDAP 인증 공급자 (FR-AU-02)
    implementation("org.springframework.boot:spring-boot-starter-data-ldap")
    implementation("org.springframework.security:spring-security-ldap")

    // JDBC + Flyway (FR-AU-02 DB 마이그레이션 + ExternalAccount Repository)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Kotlin 기본
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // 테스트 (의존성 카탈로그 §2.5)
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.testcontainers:testcontainers:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Testcontainers — Docker Desktop(macOS)에서 현재 활성 context의 소켓 경로를 명시적으로 주입.
    // Docker Desktop은 /var/run/docker.sock에 정상 응답하지 않으므로 (Status 400 빈 응답),
    // 활성 context의 소켓 경로를 DOCKER_HOST 환경변수 + jvmArgs 시스템 프로퍼티 두 경로로 전달.
    // CI 환경에서 DOCKER_HOST가 이미 설정된 경우는 Gradle 상위 환경에서 상속되므로 별도 처리 불필요.
    val dockerSocketPath =
        runCatching {
            val contextOutput =
                ProcessBuilder("docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}")
                    .start().inputStream.bufferedReader().readLine() ?: ""
            // "unix:///path" → "/path"
            contextOutput.removePrefix("unix://")
        }.getOrNull()?.takeIf { it.isNotBlank() }

    if (dockerSocketPath != null) {
        environment("DOCKER_HOST", "unix://$dockerSocketPath")
        // TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE: JVM 시스템 프로퍼티로도 전달 (환경변수 누락 방어)
        jvmArgs("-DDOCKER_HOST=unix://$dockerSocketPath")
    }
}
