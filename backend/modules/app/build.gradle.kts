// 배포 조립 모듈 빌드 스크립트 — 9개 BC 를 하나의 실행 가능한 Spring Boot 앱(fat jar)으로 통합
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

// detekt — PRE_EXISTING 위반(BtsApplication)을 detekt-baseline.xml 로 동결.
// 신규 코드는 baseline 에 포함하지 않고 코드/@Suppress 로 해소한다.
// (identity-access / issue-tracking / project-workflow / notification 등 동일 패턴.)
detekt {
    baseline = file("detekt-baseline.xml")
}

dependencies {
    // ── 9개 BC 모듈 (배포 조립 대상) ──────────────────────────────────────────────
    implementation(project(":modules:shared-kernel"))
    implementation(project(":modules:identity-access"))
    implementation(project(":modules:project-workflow"))
    implementation(project(":modules:issue-tracking"))
    implementation(project(":modules:notification"))
    implementation(project(":modules:agile-planning"))
    implementation(project(":modules:search-export-import"))
    implementation(project(":modules:slack-integration"))
    implementation(project(":modules:automation"))

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
    // 조립 전역 아키텍처 봉인(GlobalControllerAdviceSealTest) — 9 BC 를 한 클래스패스에서 스캔한다.
    // 다른 BC 모듈이 이미 같은 버전을 쓰고 있어 신규 의존성이 아니라 기존 도구의 조립 레벨 재사용이다.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testImplementation("org.testcontainers:testcontainers:1.20.3")
    testImplementation("org.testcontainers:postgresql:1.20.3")
    testImplementation("org.testcontainers:junit-jupiter:1.20.3")
}

// 배포 fat jar — 진입점 명시 + 이름 고정(Dockerfile 이 예측 가능한 경로로 COPY).
springBoot {
    mainClass.set("com.bts.app.BtsApplicationKt")
}

tasks.bootJar {
    archiveFileName.set("bts-app.jar")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
    incremental = false
}

tasks.withType<Test> {
    // ★ 태그 분기를 이 한 블록에 모은다 — 두 블록으로 나누면 선언 순서에 의존해, 누군가 순서를 바꾸면
    // excludeTags 가 조용히 덮인다(가드가 기본 test 태스크로 새어 들어가 컨텍스트 이중 부팅).
    //
    // nonprod-assembly 태그는 조립 앱을 **기본(비-prod) 프로파일**로 부팅하는 NonProdAssemblyBootTest 다.
    // @ActiveProfiles·webEnvironment 는 Spring 컨텍스트 캐시 키의 일부라, prod 조립 테스트와 같은 JVM 에
    // 두면 9-BC 컨텍스트가 두 벌 뜨고 @Scheduled 워커도 두 벌이 같은 5433 dev postgres 의 pgmq 큐를
    // 동시 폴링한다(ProdAssemblyHttpTestBase KDoc). 그래서 전용 태스크 = 전용 JVM 으로 격리한다.
    useJUnitPlatform {
        if (name == "nonProdAssemblyTest") {
            includeTags("nonprod-assembly")
        } else {
            excludeTags("nonprod-assembly")
        }
    }

    // 계약 스냅샷 재생성 스위치를 테스트 JVM 으로 전달한다.
    // Gradle 은 CLI 의 -D 를 데몬 JVM 에만 심고 fork 된 테스트 JVM 에는 자동 전달하지 않으므로,
    // 이 배선이 없으면 `-Dcontract.snapshot.update=true` 가 조용히 무시되어
    // WorkflowSchemeContractSnapshotTest 가 스냅샷을 영원히 생성하지 못한다.
    System.getProperty("contract.snapshot.update")?.let { systemProperty("contract.snapshot.update", it) }
}

// 비-prod 조립 부팅 가드 — 전용 JVM. 위 useJUnitPlatform 분기가 태그로 이 태스크에만 실어 준다.
// CI 배선은 .github/workflows/backend-ci.yml 의 assembly 잡(서비스 컨테이너 pgmq + 5433)에 있고,
// scripts/workflow/ci-module-coverage.test.ts 가 그 배선의 존재를 강제한다.
val nonProdAssemblyTest =
    tasks.register<Test>("nonProdAssemblyTest") {
        group = "verification"
        description = "조립 앱을 기본(비-prod) 프로파일로 부팅해 프로파일 의존 포트 결선을 검증한다 (dev postgres 5433 필요)."
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath

        // prod 조립 테스트와 순서를 못박는다. 두 태스크가 겹쳐 돌면 automation @Scheduled 워커가
        // 두 벌이 되어 같은 pgmq 큐를 동시 폴링한다(비-prod 조립이 부팅되면서 처음 생기는 상태).
        mustRunAfter(tasks.named("test"))
    }

tasks.named("check") {
    dependsOn(nonProdAssemblyTest)
}
