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
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
