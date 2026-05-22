// issue-tracking 모듈 — DEVELOPMENT.md §모듈 격리 + project-workflow 패턴 일관
// issue-tracking 모듈 빌드 스크립트 — project-workflow 패턴 일관, DEVELOPMENT.md §모듈 격리

// Kotlin 버전: 2.0.10 (detekt 1.23.7 호환 상한 — build.gradle.kts 루트 주석 참고)

// ── buildscript — jOOQ generateJooq 훅에서 사용할 Testcontainers + Flyway classpath ────────────
// Testcontainers JDBC URL(jdbc:tc:)의 imageTag 파싱 정규식([^:]+)은
// quay.io/tembo/pg16-pgmq:latest 형식을 지원하지 않는다.
// (PostgreSQLContainerProvider.newInstance 가 DockerImageName.parse("postgres").withTag(tag) 호출 —
//  레지스트리 경로를 tag로 취급해 postgres:<레지스트리경로> 로 잘못 조합됨)
// 따라서 generateJooq doFirst 훅에서 PostgreSQLContainer 를 직접 기동하고 JDBC URL 을 주입한다.
// ADR 2026-05-22-pgmq-postgres-image: quay.io/tembo/pg16-pgmq:latest 채택 결정.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // Testcontainers: PostgreSQLContainer 직접 기동용 (generateJooq doFirst 훅)
        classpath("org.testcontainers:postgresql:1.20.3")
        classpath("org.testcontainers:testcontainers:1.20.3")
        // Flyway 제거 — Flyway Community Edition 이 PostgreSQL 16.x 미지원 (Commercial 전용).
        // V001 + V002 SQL 은 JDBC 직접 실행 방식으로 적용 (db/codegen/init_codegen.sql).
        // PostgreSQL JDBC 드라이버 (Testcontainers + Flyway 에 모두 필요)
        classpath("org.postgresql:postgresql:42.7.3")
        // SLF4J: Testcontainers 로깅 (NoSLF4J warning 방지)
        classpath("org.slf4j:slf4j-simple:2.0.13")
    }
}

import nu.studer.gradle.jooq.JooqGenerate
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.sql.DriverManager

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

    // @PreAuthorize + method security
    implementation("org.springframework.security:spring-security-core")
    implementation("org.springframework.security:spring-security-config")
    implementation("org.springframework.security:spring-security-web")
    implementation("org.springframework.security:spring-security-aspects")

    // Jackson (JSON 직렬화)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Kotlin 기본
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // JDBC + Flyway (런타임 DB 마이그레이션 및 Repository 접근)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // PostgreSQL 드라이버 (런타임만 — 컴파일 타임 불필요)
    runtimeOnly("org.postgresql:postgresql")

    // jOOQ 런타임 (jOOQ: SQL을 코드로 안전하게 작성하는 라이브러리)
    implementation("org.jooq:jooq")

    // ── jOOQ 코드 생성 전용 classpath ─────────────────────────────────────────
    // PostgresDatabase: 실제 PostgreSQL 인스턴스를 통해 jOOQ 코드 생성 (nu.studer.jooq codegen 전용)
    // 3.19.14 로 명시 — BOM 에서 오는 3.19.1 과 충돌(version mismatch warning) 방지
    jooqGenerator("org.jooq:jooq-codegen:3.19.14")
    jooqGenerator("org.jooq:jooq-meta:3.19.14")
    // PostgreSQL JDBC 드라이버: jooqGenerator classpath 에도 필요 (codegen 시 직접 JDBC 연결)
    jooqGenerator("org.postgresql:postgresql:42.7.3")

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
// generateJooq doFirst 훅에서 quay.io/tembo/pg16-pgmq:latest 컨테이너를 직접 기동하고
// Flyway 로 V001 + V002 를 적용한 뒤 실제 JDBC URL 을 jooqConfiguration 에 주입한다.
//
// 설계 결정:
//   1. 이미지 — jdbc:tc: URL 의 PostgreSQLContainerProvider 는 imageTag 를
//      DockerImageName.parse("postgres").withTag(tag) 로 조합하므로 레지스트리 경로 지정 불가.
//      doFirst 훅에서 PostgreSQLContainer("quay.io/tembo/pg16-pgmq:latest") 직접 생성으로 우회.
//      (ADR 2026-05-22-pgmq-postgres-image)
//   2. 마이그레이션 — TC_INITSCRIPT 단일 파일 제한으로 V001 + V002 를 Flyway 로 직접 적용.
//      db/codegen/init_codegen.sql 은 TC_INITSCRIPT 방식이 필요할 때를 대비해 유지.
//   3. pgmq excludes — inputSchema = "public" 로 pgmq 스키마는 이미 introspection 대상 외.
//      pgmq 호출은 raw SQL (DATA.md §7.2). BC 격리 준수.
jooq {
    configurations {
        create("main") {
            generateSchemaSourceOnCompilation.set(false) // generateJooq 태스크를 수동 트리거

            jooqConfiguration.apply {
                logging = org.jooq.meta.jaxb.Logging.WARN

                // JDBC URL 은 doFirst 훅에서 컨테이너 기동 후 동적으로 교체된다.
                // 아래 값은 플레이스홀더 — 실제 실행 전 generateJooq doFirst 에서 덮어씀.
                jdbc.apply {
                    driver = "org.postgresql.Driver"
                    url = "jdbc:postgresql://localhost:5432/bts_codegen"
                    user = "test"
                    password = "test"
                }

                generator.apply {
                    name = "org.jooq.codegen.KotlinGenerator"

                    database.apply {
                        // PostgresDatabase: 실제 PostgreSQL information_schema 로 스키마 introspection
                        name = "org.jooq.meta.postgres.PostgresDatabase"
                        // inputSchema = "public" — pgmq 스키마는 이미 대상 외.
                        // pgmq 호출은 raw SQL (dsl.execute("SELECT pgmq.send(...)")) — ADR 2026-05-22-pgmq-postgres-image.
                        inputSchema = "public"
                        includes = ".*"
                        // flyway_schema_history: Flyway 내부 메타테이블 제외.
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
                        packageName = "com.bts.issue.jooq"
                        directory = "src/generated/jooq"
                    }
                }
            }
        }
    }
}

// ── generateJooq doFirst 훅 — quay.io/tembo/pg16-pgmq:latest 컨테이너 기동 ──────
// nu.studer.jooq 플러그인이 생성하는 generateJooq 태스크 실행 직전에:
//   1. quay.io/tembo/pg16-pgmq:latest 컨테이너를 PostgreSQLContainer 로 직접 기동.
//   2. Flyway 로 V001 + V002 마이그레이션 적용.
//   3. jooqConfiguration.jdbc.url / user / password 를 실제 컨테이너 접속 정보로 교체.
// 컨테이너는 doLast 훅에서 종료한다.
afterEvaluate {
    val dockerSocketPath =
        runCatching {
            val contextOutput =
                ProcessBuilder("docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}")
                    .start().inputStream.bufferedReader().readLine() ?: ""
            contextOutput.removePrefix("unix://")
        }.getOrNull()?.takeIf { it.isNotBlank() }

    if (dockerSocketPath != null) {
        System.setProperty("DOCKER_HOST", "unix://$dockerSocketPath")
        // Testcontainers 가 DOCKER_HOST 환경변수를 읽으므로 시스템 프로퍼티와 함께 전달.
    }

    tasks.named<JooqGenerate>("generateJooq") {
        // 컨테이너 참조를 doFirst/doLast 사이에서 공유하기 위한 상태 컨테이너
        val containerHolder = arrayOfNulls<PostgreSQLContainer<*>>(1)

        doFirst {
            // Docker 소켓 경로를 시스템 프로퍼티로 주입 (Testcontainers 인식용)
            if (dockerSocketPath != null) {
                System.setProperty("DOCKER_HOST", "unix://$dockerSocketPath")
            }

            // quay.io/tembo/pg16-pgmq:latest — pgmq 확장 사전 설치 이미지 (ADR 2026-05-22-pgmq-postgres-image).
            // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회.
            // Tembo 이미지는 postgres 호환 — pgmq 확장이 추가된 공식 postgres 16 기반 이미지.
            val temboImage = org.testcontainers.utility.DockerImageName
                .parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")
            val container = PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_codegen")
                .withUsername("test")
                .withPassword("test")
            container.start()
            containerHolder[0] = container

            // V001 + V002 SQL 을 JDBC 직접 실행으로 적용.
            // Flyway Community Edition 은 PostgreSQL 16.x 미지원 (Commercial 전용).
            // db/codegen/init_codegen.sql 에 V001 + V002 통합 — 단일 JDBC execute.
            Class.forName("org.postgresql.Driver")
            val initSql = File("${project.projectDir}/src/main/resources/db/codegen/init_codegen.sql")
                .readText()
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
                .use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute(initSql)
                    }
                }

            // JooqGenerate.jooqConfiguration 은 private 필드이므로 reflection 으로 접근.
            // afterEvaluate 에서 jooqExt 를 통한 변경은 task 가 이미 캡처한 jooqConfiguration 참조에
            // 반영되지 않으므로, reflection 으로 동일 객체의 jdbc 속성을 직접 수정.
            val jooqConfigField = JooqGenerate::class.java.getDeclaredField("jooqConfiguration")
            jooqConfigField.isAccessible = true
            val jooqConfig = jooqConfigField.get(this as JooqGenerate) as org.jooq.meta.jaxb.Configuration
            jooqConfig.jdbc.apply {
                driver = "org.postgresql.Driver"
                url = container.jdbcUrl
                user = container.username
                password = container.password
            }
        }

        doLast {
            // generateJooq 완료 후 컨테이너 종료 — 리소스 반환
            containerHolder[0]?.stop()
        }
    }
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
