// search-export-import 모듈 빌드 스크립트 — AQL 텍스트 쿼리(JQL 호환) BC (자체 테이블 없음: jOOQ/Flyway 미포함)

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    // jOOQ/Flyway 미포함 — search-export-import는 자체 DB 테이블이 없음.
    // AST → jOOQ 변환은 issue-tracking IssueSearchAdapter가 전담한다.
    // (ADR 2026-06-25-fr-sr-02-aql-parser-and-bc §D2)
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

// detekt — 신규 모듈로 PRE_EXISTING 위반 없음. 빈 baseline으로 시작.
// 신규 코드에서 위반 발생 시 코드 수정 또는 @Suppress로 해소한다.
// (identity-access / issue-tracking / project-workflow / notification / agile-planning 동일 패턴.)
detekt {
    baseline = file("detekt-baseline.xml")
}

dependencies {
    // shared-kernel — cross-BC 공유 포트 (IssueSearchPort, IssueSearchQuery, AqlNode 등)
    implementation(project(":modules:shared-kernel"))

    // Spring 핵심 (spring-boot-starter 없이 필요한 컴포넌트만 직접 선언)
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")

    // MVC + REST controller
    implementation("org.springframework:spring-webmvc")
    implementation("org.springframework:spring-web")

    // Servlet API — spring-webmvc가 참조. 실제 구현은 런타임 컨테이너(Tomcat 등)가 제공
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    // Jakarta Validation API — @Valid, @NotBlank 등
    implementation("jakarta.validation:jakarta.validation-api")

    // @PreAuthorize + method security
    implementation("org.springframework.security:spring-security-core")
    implementation("org.springframework.security:spring-security-config")
    implementation("org.springframework.security:spring-security-web")
    implementation("org.springframework.security:spring-security-aspects")

    // Jackson (JSON 직렬화)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Kotlin 기본
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Spring Data Commons — Pageable / Page / PageImpl (검색 결과 페이지네이션)
    implementation("org.springframework.data:spring-data-commons")

    // ── 테스트 ─────────────────────────────────────────────────────────────────
    // Spring Boot 테스트 슬라이스 (JUnit Vintage 제외 — Kotest runner 사용)
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }

    // MockMvc — REST API 슬라이스 테스트
    testImplementation("org.springframework:spring-test")
    // Spring Security 테스트 유틸 (@WithMockUser 등)
    testImplementation("org.springframework.security:spring-security-test")
    // Servlet API — 테스트 환경에서 MockMvc / @WebMvcTest에 필요
    testImplementation("jakarta.servlet:jakarta.servlet-api")

    // Kotest BOM (Bill of Materials — 버전 일괄 관리 패키지)
    testImplementation(platform("io.kotest:kotest-bom:5.9.1"))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation("io.kotest:kotest-property")

    // MockK — Kotlin 전용 Mock 라이브러리
    testImplementation("io.mockk:mockk:1.13.12")

    // AssertJ — 풍부한 단언문(assertion) 라이브러리
    testImplementation("org.assertj:assertj-core:3.26.3")

    // ArchUnit — 아키텍처 규칙(BC 격리 등) 자동 검증
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

// ── KotlinCompile 옵션 ────────────────────────────────────────────────────────
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// ── 테스트 JVM 설정 ─────────────────────────────────────────────────────────
tasks.withType<Test> {
    useJUnitPlatform()
    maxHeapSize = "512m"
}
