// agile-planning 모듈 빌드 스크립트 (Flyway V500~V599 + jOOQ codegen — notification 템플릿 기반)

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

// detekt — 신규 모듈로 PRE_EXISTING 위반 없음. 빈 baseline으로 시작.
// 신규 코드에서 위반 발생 시 코드 수정 또는 @Suppress로 해소한다.
// (identity-access / issue-tracking / project-workflow / notification 동일 패턴.)
detekt {
    baseline = file("detekt-baseline.xml")
}

dependencies {
    // shared-kernel — cross-BC 공유 포트 (WorkflowStateCatalog, BoardIssueLookupPort, IssueTransitionPort 등)
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

    // JDBC + Flyway (런타임 DB 마이그레이션 및 Repository 접근)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // PostgreSQL 드라이버
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
    // Flyway: 컨테이너 기동 후 V500 SQL 적용 (TC_INITSCRIPT hook 사용)
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

    // Testcontainers (테스트용 DB를 도커로 자동 실행하는 라이브러리)
    testImplementation("org.testcontainers:testcontainers:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")

    // ArchUnit — 아키텍처 규칙(BC 격리 등) 자동 검증
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

// ── jOOQ 코드 생성 설정 ───────────────────────────────────────────────────────
// Testcontainers가 PostgreSQL 컨테이너를 띄우고 TC_INITSCRIPT hook으로
// V500 SQL을 적용한 뒤 information_schema introspection으로 정확한 Kotlin 소스를 생성한다.
jooq {
    configurations {
        create("main") {
            generateSchemaSourceOnCompilation.set(false) // generateJooq 태스크를 수동 트리거

            jooqConfiguration.apply {
                logging = org.jooq.meta.jaxb.Logging.WARN

                // Testcontainers PostgreSQL JDBC URL — 자동 컨테이너 기동 + TC_INITSCRIPT로 V500 적용
                // TC_INITSCRIPT: 컨테이너 기동 직후 JDBC를 통해 SQL 파일을 실행하므로 PostgreSQL 문법 그대로 적용 가능
                jdbc.apply {
                    driver = "org.testcontainers.jdbc.ContainerDatabaseDriver"
                    url = "jdbc:tc:postgresql:16-alpine:///bts_codegen" +
                        "?TC_INITSCRIPT=file:src/main/resources/db/codegen/init_codegen.sql"
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
                        packageName = "com.bts.agileplanning.jooq"
                        directory = "src/generated/jooq"
                    }
                }
            }
        }
    }
}

// ── compileKotlin → generateJooq 명시적 의존 선언 ────────────────────────────
// generateSchemaSourceOnCompilation = false로 jOOQ 자동 트리거를 끈 상태에서도
// clean 빌드 시 compileKotlin이 src/generated/jooq를 읽기 전에 generateJooq가
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

// ── ktlintMainSourceSetCheck가 generated 소스를 검사하지 않도록 source 재설정 ──
// 설계 결정 (ktlint generated 제외 — issue-tracking PR #14 lint fix와 동일 패턴):
//   nu.studer.jooq 9.0 플러그인이 target.directory를 자동으로 SourceDirectorySet.srcDir()에 등록.
//   sourceSets.main.kotlin.srcDir를 제거해도 JooqPlugin이 재등록하므로 효과 없음.
//   ktlint plugin 12.x가 KotlinSourceSet.kotlin.sourceDirectories를 수집해 task source 확정.
//   exclude("**/generated/**") 패턴은 각 srcDir root 기준 상대경로 매칭이므로
//   src/generated/jooq root 기준 파일 경로에 "generated" 세그먼트가 없어 매칭 불가.
//   → afterEvaluate에서 runKtlintCheckOverMainSourceSet task의 source를 직접 재설정:
//     src/main/kotlin만 포함하는 FileTree로 교체 — generated 완전 제외.
//     컴파일은 sourceSets.main.kotlin.srcDir 경유로 정상 포함.
//
//   KtlintExtension.filter { exclude { ... } }는 리포트 필터일 뿐 task 입력(source)을 줄이지 못함.
//   Gradle 8.10 implicit-dependency 감지는 task 입력 기준이므로 filter로는 오류가 남는다.
//   → setSource로 task 입력 자체를 src/main/kotlin로 한정해야 implicit-dependency 해소.
afterEvaluate {
    tasks.named<org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask>("runKtlintCheckOverMainSourceSet") {
        // package-info.kt: Java 관례에서 가져온 파일명으로 ktlint PascalCase 규칙을 충족하지 못한다.
        // 패키지 문서 목적이므로 컴파일 소스에는 포함하되 ktlint 검사에서는 제외한다.
        setSource(fileTree("src/main/kotlin") { exclude("**/package-info.kt") })
    }
}
