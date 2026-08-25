// identity-access BC 서브모듈 빌드 스크립트 — Spring Boot + Security + Argon2 + JWT 자체 발급(FR-AU-09) 의존성 선언

// Kotlin 버전: 2.0.10 (detekt 1.23.7 호환 상한 — build.gradle.kts 루트 주석 참고)
import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
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
    // OpenSAML(org.opensaml:*) 은 Maven Central 에 없고 Shibboleth 저장소가 호스팅한다.
    // spring-security-saml2-service-provider(FR-AU-03) 의 transitive 의존 해소에 필요.
    maven {
        url = uri("https://build.shibboleth.net/maven/releases/")
        content {
            includeGroup("org.opensaml")
            includeGroupByRegex("net\\.shibboleth.*")
        }
    }
}

// detekt — PR #8 잔재 PRE_EXISTING 위반을 detekt-baseline.xml 로 동결 (FR-AU-09 마무리 PR).
// 신규 코드는 baseline 에 포함하지 않고 코드/@Suppress 로 해소한다. baseline 의 점진적 축소는 후속.
detekt {
    baseline = file("detekt-baseline.xml")
}

// 타입 해석(type-resolution) detektTest 의 PRE_EXISTING 테스트 부채(VarCouldBeVal/ForbiddenVoid 등)를
// basic detekt-baseline.xml 와 분리된 detekt-baseline-test.xml 로 동결한다.
// detektBaselineTest 가 단일 extension baseline 을 덮어쓰면 basic main 항목이 소실되므로 파일을 분리한다.
// 신규 코드는 baseline 에 포함하지 않고 코드/@Suppress 로 해소한다(점진적 축소 후속).
tasks.withType<Detekt>().configureEach {
    if (name == "detektTest") baseline.set(file("detekt-baseline-test.xml"))
}
tasks.withType<DetektCreateBaselineTask>().configureEach {
    if (name == "detektBaselineTest") baseline.set(file("detekt-baseline-test.xml"))
}

dependencies {
    // shared-kernel — cross-BC 포트 (UserLookupPort 등)
    implementation(project(":modules:shared-kernel"))

    // 핵심 프레임워크 (의존성 카탈로그 §2.1)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // 인증 / 보안 (의존성 카탈로그 §2.3)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    // FR-AU-09 JWT 자체 발급 + PEM 파싱
    // spring-boot-starter-oauth2-resource-server 가 nimbus-jose-jwt transitive 포함하지만
    // 9.37.x 고정 — FR-AU-09 JWSSigner/JWKSet API 를 위해 9.40 명시 강제.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")
    // BouncyCastle bcpkix: PEM 파싱 (PEMParser) — bcprov 를 transitive 포함
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78")
    implementation("de.mkammerer:argon2-jvm:2.11")

    // LDAP 인증 공급자 (FR-AU-02)
    implementation("org.springframework.boot:spring-boot-starter-data-ldap")
    implementation("org.springframework.security:spring-security-ldap")

    // SAML 2.0 SSO 인증 공급자 (FR-AU-03) — 버전은 Spring Boot 3.3.5 BOM 이 관리한다.
    implementation("org.springframework.security:spring-security-saml2-service-provider")

    // JDBC + Flyway (FR-AU-02 DB 마이그레이션 + ExternalAccount Repository)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // EC-29 Caffeine 캐시 — SidRevokeJwtConverter 5s TTL 캐시 (Task 34)
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // FR-MF-01 TOTP — secret 생성 + otpauth URI + QR(ZXing) + RFC 6238 시간윈도우 검증 (Maxi 승인, 절대규칙 #17)
    implementation("dev.samstevens.totp:totp:1.7.1")
    // ZXing — TotpService 가 직접 import 하는 QR 인코딩 의존(core: BarcodeFormat/QRCodeWriter, javase: MatrixToImageWriter).
    // samstevens:totp 가 3.4.0 을 transitive 로 끌어오나, 직접 import 하므로 절대규칙 #17(직접 의존 명시 선언)에 따라 명시한다.
    implementation("com.google.zxing:core:3.4.0")
    implementation("com.google.zxing:javase:3.4.0")

    // FR-MF-03 WebAuthn — webauthn4j-core: 등록(attestation)/인증(assertion) 검증 + ObjectConverter(JSON/CBOR).
    // 버전 0.28.4.RELEASE 고정 — 0.31.0+ 는 Jackson 3 의존이라 Spring Boot 3.3.5(Jackson 2) 클래스패스와 충돌한다.
    implementation("com.webauthn4j:webauthn4j-core:0.28.4.RELEASE")
    testImplementation("com.webauthn4j:webauthn4j-test:0.28.4.RELEASE")

    // FR-PR-01 아바타 오브젝트 스토리지 — MinIO Java SDK (Maxi 승인 2026-07-05, 절대규칙 #17).
    // issue-tracking 과 동일 버전 정렬(8.5.17). identity-access 자체 배선(모듈 격리, 타 모듈 import 없음).
    implementation("io.minio:minio:8.5.17")

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
    // FR-PR-01 MinioAvatarStorageAdapterTest 통합 테스트용 — issue-tracking 과 동일 버전(1.20.3).
    testImplementation("org.testcontainers:minio:1.20.3")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    // LocalAuthFlowIntegrationTest — 401 응답 body 읽기 (HttpURLConnection 재시도 방지)
    // TestRestTemplate 의 기본 HttpURLConnection 은 401 POST 응답 시 HttpRetryException.
    // Apache HttpComponents 5 ClientHttpRequestFactory 로 교체하여 해결.
    testImplementation("org.apache.httpcomponents.client5:httpclient5:5.3.1")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
    // 증분 컴파일 캐시 손상 방지 (worktree 환경에서 반복 발생하는 PersistentEnumeratorBase 오류 회피)
    incremental = false
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Testcontainers — Docker Desktop(macOS)에서 현재 활성 context의 소켓 경로를 명시적으로 주입.
    // Docker Desktop은 /var/run/docker.sock에 정상 응답하지 않으므로 (Status 400 빈 응답),
    // 활성 context의 소켓 경로를 DOCKER_HOST 환경변수 + jvmArgs 시스템 프로퍼티 두 경로로 전달.
    // CI 환경에서 DOCKER_HOST가 이미 설정된 경우는 Gradle 상위 환경에서 상속되므로 별도 처리 불필요.
    // ★2026-08-25 — ProcessBuilder 직접 호출을 providers.exec 로 바꿨다.
    //   종전 코드는 **configuration 시점에** docker 를 실행했고, Gradle 이 그것을
    //   "external process started ... during configuration time is unsupported" 로 거부해
    //   configuration cache 를 저장하지 못했다. providers.exec 는 값을 요구받을 때까지
    //   실행을 미루므로 doFirst 에서 소비하면 실행 시점 호출이 된다. 주입 값은 그대로다.
    val dockerHost =
        providers.exec {
            commandLine("docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim().lines().firstOrNull().orEmpty() }

    doFirst {
        // docker 미설치·context 미설정이면 빈 문자열이거나 예외다. 둘 다 주입하지 않고 넘어간다
        // (CI 는 상위 환경의 DOCKER_HOST 를 상속하므로 주입이 없어도 동작한다).
        val host = runCatching { dockerHost.orNull }.getOrNull()?.takeIf { it.startsWith("unix://") }
        if (host != null) {
            this@withType.environment("DOCKER_HOST", host)
            // TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE: JVM 시스템 프로퍼티로도 전달 (환경변수 누락 방어)
            this@withType.jvmArgs("-DDOCKER_HOST=$host")
        }
    }
}
