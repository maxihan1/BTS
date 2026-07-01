// BTS 공유 커널 모듈 — issue-tracking ↔ project-workflow 가 공유하는 port/VO/DTO (순환 의존 차단)

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
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

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.4")
    }
}

// detekt 1.23.7 은 Kotlin 2.0.10 으로 컴파일됨. shared-kernel 은 org.springframework.boot 플러그인
// 대신 Spring BOM 을 직접 import 하므로, BOM 이 detekt 분석 classpath 의 kotlin-compiler-embeddable 을
// 1.9.25 로 강등시킨다(나머지 3개 모듈은 boot 플러그인 경유로 자동 회피). detekt configuration 에 한해
// detekt 가 지원하는 Kotlin 버전으로 고정해 "compiled with 2.0.10 but running with 1.9.25" 충돌을 해소.
// https://detekt.dev/docs/gettingstarted/gradle (Spring dependency-management 충돌 공식 권장 fix)
configurations.matching { it.name == "detekt" }.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(io.gitlab.arturbosch.detekt.getSupportedKotlinVersion())
        }
    }
}

dependencies {
    // 도메인 검증 (Konform — Kotlin-native 선언형 검증 라이브러리)
    implementation("io.konform:konform-jvm:0.7.0")

    // Spring 핵심 — @Component / @Bean 메타 (버전은 Spring BOM 관리)
    implementation("org.springframework:spring-context")

    // 트랜잭션 추상화 — WorkflowTransitionPort 의 @Transactional(MANDATORY) 선언에 필요
    implementation("org.springframework:spring-tx")

    // SSRF 감사 로그 — OutboundUrlValidator 의 slf4j LoggerFactory 용 (버전은 Spring BOM 관리)
    implementation("org.slf4j:slf4j-api")

    // 아웃바운드 HTTP 클라이언트 — OutboundHttpClientConfig 의 RestClient/JdkClientHttpRequestFactory 용 (버전은 Spring BOM 관리)
    implementation("org.springframework:spring-web")

    // Kotlin 기본 리플렉션
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // ── 테스트 ────────────────────────────────────────────────────────────────
    // Spring Boot Test (JUnit Vintage 제외 — JUnit 5 / Kotest runner 사용)
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }

    // Kotest BOM (Bill of Materials — 버전 일괄 관리 패키지)
    testImplementation(platform("io.kotest:kotest-bom:5.9.1"))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation("io.kotest:kotest-property")

    // MockK — Kotlin 전용 Mock 라이브러리
    testImplementation("io.mockk:mockk:1.13.12")

    // AssertJ — 풍부한 단언문(assertion) 라이브러리
    testImplementation("org.assertj:assertj-core:3.26.3")

    // ArchUnit — 아키텍처 규칙(BC 격리 등) 자동 검증 (Task 5에서 사용)
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

// ── KotlinCompile 옵션 ────────────────────────────────────────────────────────
tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// ── 테스트 JVM 설정 ───────────────────────────────────────────────────────────
tasks.withType<Test> {
    useJUnitPlatform()
}
