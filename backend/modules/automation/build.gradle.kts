// automation 모듈 빌드 스크립트 (test-boot only, DB=JdbcTemplate — FR-AT-01 Task 1, slack-integration 미러)

// Kotlin 버전: 2.0.10 (detekt 1.23.7 호환 상한 — build.gradle.kts 루트 주석 참고)
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    // DB 마이그레이션: Flyway (DB 스키마 변경을 버전 관리하는 도구)
    id("org.flywaydb.flyway")
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
// (identity-access / issue-tracking / project-workflow / notification / agile-planning / search-export-import / slack-integration 동일 패턴.)
detekt {
    baseline = file("detekt-baseline.xml")
}

dependencies {
    // shared-kernel — cross-BC 공유 포트 (AutomationPermissionResolver 등)
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

    // JDBC + Flyway (jOOQ 미도입 — automation_rules 단일 테이블 단순 CRUD, DATA.md §5)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // PostgreSQL 드라이버
    implementation("org.postgresql:postgresql")

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
    // 임베디드 Tomcat(테스트 전용) — AutomationWebhookControllerTest 의 webEnvironment=RANDOM_PORT +
    // TestRestTemplate 실 HTTP 검증에 필요. payload 크기 상한(G2, 413)은 애플리케이션 계층에서 직접
    // 구현하므로 MockMvc 로도 로직 자체는 통과하지만, 서블릿 컨테이너 경계까지 포함한 end-to-end 를
    // 실증하기 위해 실서블릿으로 검증한다([[multipart-default-limit-app-policy-false-green]] 반면교사).
    // prod 배포조립(automation 모듈 전조립)은 이 FR 범위 밖이라 이 의존성은 test 스코프로 한정한다.
    testImplementation("org.springframework.boot:spring-boot-starter-tomcat")

    // Kotest BOM (Bill of Materials — 버전 일괄 관리 패키지)
    testImplementation(platform("io.kotest:kotest-bom:5.9.1"))
    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
    testImplementation("io.kotest:kotest-property")

    // MockK — Kotlin 전용 Mock 라이브러리
    testImplementation("io.mockk:mockk:1.13.12")

    // AssertJ — 풍부한 단언문(assertion) 라이브러리
    testImplementation("org.assertj:assertj-core:3.26.3")

    // Testcontainers (테스트용 DB를 도커로 자동 실행하는 라이브러리) — Repository/마이그레이션 통합 테스트
    testImplementation("org.testcontainers:testcontainers:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")

    // ArchUnit — 아키텍처 규칙(BC 격리 등) 자동 검증
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

// ── KotlinCompile 옵션 ────────────────────────────────────────────────────────
tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

// ── 테스트 JVM + Docker 소켓 설정 ─────────────────────────────────────────────
tasks.withType<Test> {
    useJUnitPlatform()
    // Testcontainers 동시 실행 시 메모리 확보. 포크 1개씩 순차 실행으로 OOM 방지
    maxHeapSize = "1024m"
    maxParallelForks = 1

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

// ── ktlintMainSourceSetCheck가 package-info.kt를 검사하지 않도록 source 재설정 ──
// package-info.kt: Java 관례에서 가져온 파일명으로 ktlint PascalCase 파일명 규칙을 충족하지 못한다
// (agile-planning 선례 동형). 패키지 문서 목적이므로 컴파일 소스에는 포함하되 ktlint 검사에서는 제외한다.
afterEvaluate {
    tasks.named<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>("runKtlintCheckOverMainSourceSet") {
        setSource(fileTree("src/main/kotlin") { exclude("**/package-info.kt") })
    }
}
