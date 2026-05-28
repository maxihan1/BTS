// project-workflow 모듈 빌드 스크립트 (Flyway + jOOQ + Konform — identity-access 패턴)

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
    // jOOQ 코드 생성: DDL → 타입 안전 Kotlin DSL 자동 생성
    id("nu.studer.jooq")
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
    // shared-kernel — WorkflowTransitionPort + TransitionRequest/Result/Plan/FieldChange/DomainEvent (PR #25 Task 3)
    //   + IssueTypeId/IssueTypeKey 공유 VO. issue-tracking 직접 의존을 제거해 순환(issue-tracking ↔ project-workflow) 회피.
    implementation(project(":modules:shared-kernel"))

    // issue-tracking — 테스트 런타임 전용. project-workflow V202 마이그레이션이 issue-tracking V001 의
    //   projects 테이블을 FK 참조하므로, 통합 테스트(Testcontainers)에서 그 마이그레이션 SQL 이 classpath 에 있어야 한다.
    //   컴파일 의존이 아니라 순환을 만들지 않고(issue-tracking 은 shared-kernel 만 의존), BC 격리 ArchUnit 도 import 가 아니라 통과.
    testRuntimeOnly(project(":modules:issue-tracking"))

    // 도메인 검증 (Konform — Kotlin-native 선언형 검증 라이브러리, ADR 2026-05-21 GAP-17)
    implementation("io.konform:konform-jvm:0.7.0")

    // Spring 핵심 (spring-boot-starter 없이 필요한 컴포넌트만 직접 선언 — CONCERN-2)
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")

    // MVC + REST controller
    implementation("org.springframework:spring-webmvc")
    implementation("org.springframework:spring-web")
    // Servlet API — spring-webmvc 가 참조. 실제 구현은 런타임 컨테이너(Tomcat 등)가 제공
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    // Jakarta Validation API — @Valid, @NotBlank 등 (Task 24 WorkflowSchemeController 에서 사용)
    implementation("jakarta.validation:jakarta.validation-api")

    // @PreAuthorize + method security
    implementation("org.springframework.security:spring-security-core")
    implementation("org.springframework.security:spring-security-config")
    implementation("org.springframework.security:spring-security-web")
    implementation("org.springframework.security:spring-security-aspects")

    // Jackson (JSON/YAML 직렬화 — workflow YAML 파싱 포함)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    // Java 8 date/time (Instant 등) 직렬화 — WorkflowScheme 도메인 이벤트 occurredAt 필드용
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // Kotlin 기본
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // JDBC + Flyway (런타임 DB 마이그레이션 및 Repository 접근)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // SpEL — Spring Expression Language (Task 20 CustomExpression validator 에서 사용)
    implementation("org.springframework:spring-expression")

    // PostgreSQL 드라이버 — SchemeIssueTypeMappingRepository 가 PSQLException.serverErrorMessage 로
    // 제약조건 이름을 추출하므로 컴파일 타임에 직접 참조한다.
    // (이전엔 issue-tracking implementation 의존을 통해 transitive 로 노출됐으나, 순환 회피로 그 의존을 끊으며 명시화)
    implementation("org.postgresql:postgresql")

    // jOOQ 런타임 (jOOQ: SQL을 코드로 안전하게 작성하는 라이브러리)
    implementation("org.jooq:jooq")

    // ── jOOQ 코드 생성 전용 classpath ─────────────────────────────────────────
    // PostgresDatabase: 실제 PostgreSQL 인스턴스를 통해 jOOQ 코드 생성 (nu.studer.jooq codegen 전용)
    jooqGenerator("org.jooq:jooq-codegen")
    jooqGenerator("org.jooq:jooq-meta")
    // Testcontainers PostgreSQL: codegen 시 자동으로 PostgreSQL 컨테이너를 띄워 스키마 introspection
    jooqGenerator("org.testcontainers:postgresql:1.20.3")
    jooqGenerator("org.testcontainers:jdbc:1.20.3")
    jooqGenerator("org.postgresql:postgresql:42.7.3")
    // Flyway: 컨테이너 기동 후 V001 SQL 적용 (onMigrate hook 사용)
    jooqGenerator("org.flywaydb:flyway-core:10.17.3")
    jooqGenerator("org.flywaydb:flyway-database-postgresql:10.17.3")

    // ── 테스트 ─────────────────────────────────────────────────────────────────
    // Spring Boot 테스트 슬라이스 (JUnit Vintage 제외 — Kotest runner 사용)
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }

    // MockMvc — REST API 슬라이스 테스트
    testImplementation("org.springframework:spring-test")
    // Spring Security 테스트 유틸 (@WithMockUser 등)
    testImplementation("org.springframework.security:spring-security-test")
    // Servlet API — 테스트 환경에서 MockMvc / @WebMvcTest 에 필요
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

    // Testcontainers (테스트용 DB를 도커로 자동 실행하는 라이브러리)
    testImplementation("org.testcontainers:testcontainers:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")

    // ArchUnit — 아키텍처 규칙(BC 격리 등) 자동 검증
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

// ── jOOQ 코드 생성 설정 ───────────────────────────────────────────────────────
// PostgresDatabase: Testcontainers가 PostgreSQL 컨테이너를 띄우고 Flyway onMigrate hook으로
// V001 SQL을 적용한 뒤 information_schema introspection으로 정확한 Kotlin 소스를 생성한다.
jooq {
    configurations {
        create("main") {
            generateSchemaSourceOnCompilation.set(false) // generateJooq 태스크를 수동 트리거 (wave-2 종료 후 controller 실행)

            jooqConfiguration.apply {
                logging = org.jooq.meta.jaxb.Logging.WARN

                // Testcontainers PostgreSQL JDBC URL — 자동 컨테이너 기동 + TC_INITSCRIPT로 V001 적용
                // TC_INITSCRIPT: 컨테이너 기동 직후 JDBC를 통해 SQL 파일을 실행하므로 PostgreSQL 문법 그대로 적용 가능
                jdbc.apply {
                    driver = "org.testcontainers.jdbc.ContainerDatabaseDriver"
                    url = "jdbc:tc:postgresql:16-alpine:///bts_codegen" +
                        "?TC_INITSCRIPT=file:src/main/resources/db/migration/project-workflow/V200__init_workflow.sql"
                    user = "test"
                    password = "test"
                }

                generator.apply {
                    name = "org.jooq.codegen.KotlinGenerator"

                    database.apply {
                        // PostgresDatabase: 실제 PostgreSQL information_schema로 스키마 introspection
                        name = "org.jooq.meta.postgres.PostgresDatabase"
                        inputSchema = "public"
                        includes = ".*"
                        excludes = "flyway_schema_history"
                    }

                    generate.apply {
                        isRecords = true
                        isPojos = true
                        isKotlinNotNullPojoAttributes = true
                        isKotlinNotNullRecordAttributes = true
                        isKotlinNotNullInterfaceAttributes = true
                    }

                    target.apply {
                        packageName = "com.bts.workflow.jooq"
                        directory = "src/generated/jooq"
                    }
                }
            }
        }
    }
}

// ── compileKotlin → generateJooq 명시적 의존 선언 ────────────────────────────
// generateSchemaSourceOnCompilation = false 로 jOOQ 자동 트리거를 끈 상태에서도
// clean 빌드 시 compileKotlin 이 src/generated/jooq 를 읽기 전에 generateJooq 가
// 반드시 먼저 실행되도록 Gradle 태스크 의존을 명시한다 (Gradle 8.10 implicit dependency 오류 해소).
tasks.named<KotlinCompile>("compileKotlin") {
    dependsOn("generateJooq")
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
    // Testcontainers + jOOQ 동시 실행 시 메모리 확보. 포크 1개씩 순차 실행으로 OOM 방지
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

// ── 생성된 jOOQ 소스를 컴파일 소스 셋에 등록 ────────────────────────────────
// generateJooq 실행 후 src/generated/jooq 디렉터리를 코틀린 소스로 인식하게 한다.
sourceSets {
    main {
        kotlin {
            srcDir("src/generated/jooq")
        }
    }
}

// ── ktlintMainSourceSetCheck 가 generated 소스를 검사하지 않도록 source 재설정 ──
// 설계 결정 (ktlint generated 제외 — issue-tracking PR #14 lint fix 와 동일 패턴):
//   nu.studer.jooq 9.0 플러그인이 target.directory 를 자동으로 SourceDirectorySet.srcDir() 에 등록.
//   sourceSets.main.kotlin.srcDir 를 제거해도 JooqPlugin 이 재등록하므로 효과 없음.
//   ktlint plugin 12.x 가 KotlinSourceSet.kotlin.sourceDirectories 를 수집해 task source 확정.
//   exclude("**/generated/**") 패턴은 각 srcDir root 기준 상대경로 매칭이므로
//   src/generated/jooq root 기준 파일 경로에 "generated" 세그먼트가 없어 매칭 불가.
//   → afterEvaluate 에서 runKtlintCheckOverMainSourceSet task 의 source 를 직접 재설정:
//     src/main/kotlin 만 포함하는 FileTree 로 교체 — generated 완전 제외.
//     컴파일은 sourceSets.main.kotlin.srcDir 경유로 정상 포함.
//
//   KtlintExtension.filter { exclude { ... } } 는 리포트 필터일 뿐 task 입력(source)을 줄이지 못함.
//   Gradle 8.10 implicit-dependency 감지는 task 입력 기준이므로 filter 로는 오류가 남는다.
//   → setSource 로 task 입력 자체를 src/main/kotlin 로 한정해야 implicit-dependency 해소.
afterEvaluate {
    tasks.named<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>("runKtlintCheckOverMainSourceSet") {
        setSource(fileTree("src/main/kotlin"))
    }
}
