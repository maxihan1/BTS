// 배포 조립 모듈 빌드 스크립트 — 8개 BC 를 하나의 실행 가능한 Spring Boot 앱(fat jar)으로 통합
//
// 각 BC 모듈은 implementation 프로젝트 의존으로 끌어온다. project 의존은 각 모듈의 일반 jar(runtimeElements)를
// 해소하므로, 라이브러리 모듈(main 클래스 없음)이든 부팅 모듈이든 클래스가 그대로 classpath 에 들어온다.
// 각 모듈의 transitive 런타임 의존(minio·flyway·webauthn4j·saml 등)은 runtimeClasspath 에 전이되어 fat jar 에 번들된다.

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

group = "com.bts"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    // identity-access 가 transitive 로 끌어오는 OpenSAML(org.opensaml:*) 은 Maven Central 에 없고
    // Shibboleth 저장소가 호스팅한다. 조립 모듈 runtimeClasspath 해소에도 필요.
    maven {
        url = uri("https://build.shibboleth.net/maven/releases/")
        content {
            includeGroup("org.opensaml")
            includeGroupByRegex("net\\.shibboleth.*")
        }
    }
}

dependencies {
    // ── 8개 BC 모듈 (배포 조립 대상) ──────────────────────────────────────────────
    implementation(project(":modules:shared-kernel"))
    implementation(project(":modules:identity-access"))
    implementation(project(":modules:project-workflow"))
    implementation(project(":modules:issue-tracking"))
    implementation(project(":modules:notification"))
    implementation(project(":modules:agile-planning"))
    implementation(project(":modules:search-export-import"))
    implementation(project(":modules:slack-integration"))

    // ── 조립 앱 자체 프레임워크 (부팅 진입점 컴파일용) ───────────────────────────
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Flyway — FlywayAssemblyConfig 가 모듈별 이력 테이블로 직접 마이그레이션 실행 (조립 앱 compile 노출용).
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // BouncyCastle — 조립 앱이 부팅 시 "BC" 보안 프로바이더를 등록(identity PemFileKeyProvider 가 참조).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")

    // Kotlin 기본
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // PostgreSQL 드라이버 (모듈에서 transitive 로 오지만 조립 앱 런타임 명시)
    runtimeOnly("org.postgresql:postgresql")

    // ── 테스트 (조립 컨텍스트 로드 검증) ─────────────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("org.testcontainers:testcontainers:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
    incremental = false
}

tasks.withType<Test> {
    useJUnitPlatform()
}
