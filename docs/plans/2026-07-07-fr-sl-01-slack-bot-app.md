# FR-SL-01 Slack App + Bot Token 방식

> slug: fr-sl-01-slack-bot-app
> type: auth
> agent: security-engineer
> primary_bc: slack-integration
> 생성: 2026-07-07

## Brief

slack-integration BC의 첫 구현. Slack App 설치 흐름(OAuth 2.0) + Bot Token 안전 보관.
- D1. 도메인 — SlackWorkspace + BotInstall
- D2. 명세 — OAuth 2.0 설치 흐름 + Token 보관
- D3. 데이터 모델 — slack_installs(workspace_id, bot_token_encrypted, ...)
- D4. 백엔드 — Slack 설치 콜백 (/slack/install/callback)
- D5. 백엔드 테스트
- D6. 프론트 UI — 관리자 "Slack 연결" 페이지
- D7. E2E

SDD 09장(알림/Slack). product: docs/plan/product/slack-integration.md §2.1

## 도메인 정리

- **BC**: slack-integration (신규 — 새 Gradle 모듈 `backend/modules/slack-integration`, 패키지 `com.atlas.bts.slack`)
- **영향 엔티티**: `SlackInstall` (신규, `slack_installs` 테이블 매핑 VO — 워크스페이스별 봇 설치 + 암호화 토큰)
- **새 용어**: "Slack 설치(Install)" = 워크스페이스에 BTS App을 OAuth로 연결해 bot token을 발급받은 상태. "봇 토큰(Bot Token)" = Slack이 App에 발급하는 워크스페이스 스코프 액세스 토큰.
- **재사용 자산**:
  - `com.bts.shared.crypto.SecretEncryptor` (shared-kernel, AES 대칭) — bot token 암호화. 빈 이름 `slackSecretEncryptor` + `@Qualifier` (OIDC `oidcSecretEncryptor` 선례)
  - webhook `WebhookHttpClientConfig` RestClient 패턴 — OAuth 토큰 교환 아웃바운드 HTTP
  - OIDC `oidc_provider_configs`/`DbClientRegistrationRepository` — 암호화 토큰 DB 저장 + 부팅 안전성 패턴
- **결정 사항 (게이트 확정)**:
  - SDK = `com.slack.api:slack-api-client` (client 층만, Bolt 프레임워크 미도입 / 신규 외부 의존성)
  - 암호화 = AES(SecretEncryptor), KMS는 v0.4+ (domain/product 문서 "KMS" 표기 정정 대상)
  - OAuth v2 설치 흐름 + `state` CSRF 검증
  - 스코프 = 백엔드 코어 D1~D5, 실 App 없음 → 통합 테스트 stub
- **기존 결정 충돌**: 없음 (slack-integration BC 첫 ADR). domain 노트 "KMS" 표기만 정정 필요 (머지 시 동기화).
- **관련 ADR**: [docs/decisions/2026-07-07-fr-sl-01-slack-bot-app.md](../decisions/2026-07-07-fr-sl-01-slack-bot-app.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-07-07-fr-sl-01-slack-bot-app.md](../specs/2026-07-07-fr-sl-01-slack-bot-app.md)

핵심 시나리오 3줄 요약.
- 시스템 관리자가 `GET /slack/install` → Slack authorize 302 (서명 state에 installedBy 박제, cross-BC `SystemPermissionResolver`로 관리자 판정)
- Slack 동의 후 `GET /slack/install/callback` → state 서명·만료 검증 → `oauth.v2.access` 토큰 교환 → bot token AES 암호화 저장(`slack_installs`, team_id upsert)
- STATELESS 정합 = 서명 self-contained state(서버 저장 0), enterprise install 거부, 실 App 없어 Slack API stub 통합 테스트

## Brainstorming Check

✅ 통과 (1회 iteration, adversarial sanity check). gap 4건 발견·보강.
- G1 enterprise install `team` null → 워크스페이스 설치만, enterprise 거부
- G2 관리자 가드 → `SystemPermissionResolver` cross-BC 포트(OutboundWebhookService 선례), identity-access import 금지
- G3 redirect_uri → `BTS_SLACK_REDIRECT_URI` 환경변수, authorize/exchange 동일값
- G4 callback 목적지 → 프론트 결과 경로 302(D6 예약)

## Plan

패키지 루트 `com.bts.slack`. 모든 경로는 `backend/modules/slack-integration/src/...` 기준. DB=JdbcTemplate, SDK=`com.slack.api:slack-api-client`(impl에서 Maven Central 최신 안정 고정, 1.45~1.49 대역), 부팅=test-boot only.

### Task 1. slack-integration 모듈 스캐폴딩 + test-boot 앱

**메타**.
- agent: `backend-engineer`
- files: [`backend/settings.gradle.kts`, `backend/modules/slack-integration/build.gradle.kts`, `backend/modules/slack-integration/detekt-baseline.xml`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/SlackIntegrationTestBootApplication.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/SlackContextLoadTest.kt`, `backend/modules/slack-integration/src/test/resources/application.yml`]
- depends-on: []

**RED**: `SlackContextLoadTest` — `@SpringBootTest`(test-boot 앱) 컨텍스트 로드. 모듈/의존성 미설정이면 컴파일·부팅 실패.

**GREEN**:
- `settings.gradle.kts`에 `include(":modules:slack-integration")` 추가.
- `build.gradle.kts` — search/notification build.gradle.kts 복제(plugins 재선언, toolchain 21, mavenCentral). `implementation(project(":modules:shared-kernel"))`, `implementation("org.springframework.boot:spring-boot-starter-web")`, `spring-boot-starter-jdbc`, `com.slack.api:slack-api-client:<버전>`, `detekt { baseline = file("detekt-baseline.xml") }`. jOOQ 블록 없음(JdbcTemplate).
- `SlackIntegrationTestBootApplication`(`@SpringBootApplication`), test `application.yml`(Testcontainers/Flyway placeholder).

**REFACTOR**: 빈 `detekt-baseline.xml`, 파일 L1 한글 주석.

**검증**: `./gradlew :modules:slack-integration:compileKotlin :modules:slack-integration:test --tests '*SlackContextLoadTest'`

### Task 2. slack_installs 마이그레이션 (V700)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/slack-integration/src/main/resources/db/migration/slack-integration/V700__slack_installs.sql`, `backend/modules/slack-integration/src/test/resources/application.yml`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/persistence/SlackInstallSchemaMigrationTest.kt`]
- depends-on: [1]

**RED**: `SlackInstallSchemaMigrationTest` — Testcontainers PostgreSQL에 Flyway migrate 후 `slack_installs` 테이블·컬럼·`team_id` UNIQUE 존재 확인.

**GREEN**: `V700__slack_installs.sql`(스펙 §데이터 모델 컬럼: id/team_id UNIQUE/team_name/bot_user_id/app_id/bot_token_encrypted/scopes/is_enterprise_install/installed_by/installed_at/updated_at). test `application.yml`에 `spring.flyway.locations: classpath:db/migration/slack-integration` + `baseline-on-migrate: true` + `placeholder-replacement: false`.

**REFACTOR**: 컬럼 주석(COMMENT ON), 인덱스 정리.

**검증**: `./gradlew :modules:slack-integration:test --tests '*SlackInstallSchemaMigrationTest'`

### Task 3. SlackInstall 도메인 VO + OAuth 토큰 응답 파싱

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/domain/SlackInstall.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/oauth/SlackOAuthTokenResponse.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/domain/SlackInstallTest.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/oauth/SlackOAuthTokenResponseTest.kt`]
- depends-on: [1]

**RED**: `SlackOAuthTokenResponseTest`(성공 파싱, `ok:false` 감지, 필수 필드 누락, enterprise install=`team` null 감지), `SlackInstallTest`(`fromToken` 팩토리 — team_id/scopes 매핑, `installedBy` 주입).

**GREEN**: `SlackOAuthTokenResponse`(slack-api-client `OAuthV2AccessResponse` 매핑 또는 자체 data class), `SlackInstall` VO + `fromToken(response, installedBy)` 팩토리(enterprise install이면 `IllegalArgumentException` → 상위서 unsupported 처리).

**REFACTOR**: 검증 로직 함수 추출, KDoc.

**검증**: `./gradlew :modules:slack-integration:test --tests '*SlackInstallTest' --tests '*SlackOAuthTokenResponseTest'`

### Task 4. state 서명 서비스 (SlackOAuthStateSigner)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/oauth/SlackOAuthStateSigner.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/oauth/SlackOAuthStateSignerTest.kt`]
- depends-on: [1]

**RED**: `SlackOAuthStateSignerTest` — 발급 후 검증 통과·`installedBy` 복원, 서명 위조 거부, `exp` 만료 거부(Clock 주입), 변조 payload 거부.

**GREEN**: `state = base64url(payload) + "." + HMAC-SHA256(payload, stateKey)`. payload=`{nonce, installedBy, exp}`. `Clock` 주입(만료 테스트). `stateKey`는 `@Value("\${bts.slack.state-key:}")`. 미설정 시 발급/검증 호출 시점 검증(부팅 안전).

**REFACTOR**: 상수/예외 정리, 평문·키 로깅 금지 KDoc.

**검증**: `./gradlew :modules:slack-integration:test --tests '*SlackOAuthStateSignerTest'`

### Task 5. SlackEncryptionConfig (slackSecretEncryptor 빈)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/config/SlackEncryptionConfig.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/config/SlackEncryptionConfigTest.kt`]
- depends-on: [1]

**RED**: `SlackEncryptionConfigTest` — 빈 등록 확인, encrypt→decrypt round-trip, 키 미설정이어도 빈 생성(부팅 안전)·암호화 호출 시 `IllegalStateException`.

**GREEN**: `WebhookEncryptionConfig` 복제 — `@Bean("slackSecretEncryptor") SecretEncryptor(key, salt)`, `bts.slack-encryption.{key,salt}`(env `BTS_SLACK_ENCRYPTION_*`).

**REFACTOR**: companion 상수(PROPERTY_KEY/SALT), KDoc(빈 이름 격리 이유).

**검증**: `./gradlew :modules:slack-integration:test --tests '*SlackEncryptionConfigTest'`

### Task 6. SlackOAuthClient (oauth.v2.access 토큰 교환)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/oauth/SlackOAuthClient.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/config/SlackProperties.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/oauth/SlackOAuthClientTest.kt`]
- depends-on: [1, 3]

**RED**: `SlackOAuthClientTest` — endpoint 오버라이드(MockWebServer 또는 slack-api-client `methodsEndpointUrlPrefix`)로 stub. 성공 응답 파싱, `ok:false` → 도메인 실패, 필드 누락 처리. authorize URL 생성(client_id/scope/state/redirect_uri).

**GREEN**: `SlackOAuthClient` 인터페이스 + 구현(slack-api-client `MethodsClient.oauthV2Access(code, clientId, clientSecret, redirectUri)`). `SlackProperties`(clientId/clientSecret/redirectUri/scopes, env `BTS_SLACK_CLIENT_ID`·`BTS_SLACK_CLIENT_SECRET`·`BTS_SLACK_REDIRECT_URI`). authorize URL 빌더.

**REFACTOR**: 응답→`SlackOAuthTokenResponse` 매핑 정리, secret 로깅 금지.

**검증**: `./gradlew :modules:slack-integration:test --tests '*SlackOAuthClientTest'`

### Task 7. JdbcSlackInstallRepository (upsert / findByTeamId)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackInstallRepository.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/persistence/JdbcSlackInstallRepository.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/persistence/JdbcSlackInstallRepositoryTest.kt`]
- depends-on: [2, 3]

**RED**: `JdbcSlackInstallRepositoryTest`(Testcontainers) — `upsert` 신규 insert, 같은 `team_id` 재upsert 시 갱신(중복 행 0·updated_at 갱신), `findByTeamId` 존재/부재.

**GREEN**: `SlackInstallRepository` 포트 + `JdbcSlackInstallRepository`(JdbcTemplate, `INSERT ... ON CONFLICT (team_id) DO UPDATE`, 파라미터 바인딩 `?`). `@Repository`.

**REFACTOR**: SQL 상수화, RowMapper 정리.

**검증**: `./gradlew :modules:slack-integration:test --tests '*JdbcSlackInstallRepositoryTest'`

### Task 8. SlackInstallService (오케스트레이션 + 관리자 가드)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackInstallService.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackInstallExceptions.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/application/SlackInstallServiceTest.kt`]
- depends-on: [3, 4, 5, 6, 7]

**RED**: `SlackInstallServiceTest`(fake repo/oauth client, stub `SystemPermissionResolver`) — `startInstall`(관리자→state 발급+authorize URL, 비관리자 `SlackForbiddenException`), `completeInstall`(state 검증→토큰교환→암호화→upsert, state 불일치 거부, `ok:false` 실패, enterprise install 거부).

**GREEN**: `SlackInstallService`(`@Service @Transactional`) — `SystemPermissionResolver`(생성자 주입, fail-closed)·`SlackOAuthStateSigner`·`SlackOAuthClient`·`@Qualifier("slackSecretEncryptor") SecretEncryptor`·`SlackInstallRepository` 오케스트레이션. `SlackInstallExceptions`(Forbidden/StateInvalid/OAuthFailed/UnsupportedInstall).

**REFACTOR**: 가드를 리소스 접근 이전에(auth-extraction-before-lookup), 예외 메시지 민감정보 차단.

**검증**: `./gradlew :modules:slack-integration:test --tests '*SlackInstallServiceTest'`

### Task 9. Web 레이어 (Controller/ActorExtractor/ExceptionHandler) + 통합 테스트

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/web/SlackInstallController.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/web/SlackActorExtractor.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/web/SlackInstallExceptionHandler.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/web/SlackInstallIntegrationTest.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/SlackTestSecurityConfig.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/StubSystemPermissionResolver.kt`]
- depends-on: [8]

**RED**: `SlackInstallIntegrationTest`(test-boot + Slack stub + `SlackTestSecurityConfig` + `StubSystemPermissionResolver` fail-closed) — `GET /slack/install` 관리자 302(Location=authorize)·비관리자 403, `GET /slack/install/callback` permitAll + 설치완료→`slack_installs` 저장(암호화 확인)·재설치 upsert·state 불일치 거부, 응답/로그에 평문 토큰 미노출(어서션).

**GREEN**: `SlackInstallController`(`/slack/install` 302, `/slack/install/callback` 결과 경로 302), `SlackActorExtractor`(object, SecurityContext→UUID), `SlackInstallExceptionHandler`(`@RestControllerAdvice(assignableTypes=[SlackInstallController])`, ProblemDetail). test `SlackTestSecurityConfig`(`/slack/install/callback` permitAll GET·`/slack/install` authenticated), `StubSystemPermissionResolver`(fail-closed 빈).

**REFACTOR**: DTO 정리, 에러 코드 매핑(EC1~EC7).

**검증**: `./gradlew :modules:slack-integration:test --tests '*SlackInstallIntegrationTest'`

## Plan 메타

- task 수: 9 (각 TDD 사이클)
- 예상 wave: 5 (w1=T1 / w2=T2·T3·T4·T5 / w3=T6·T7 / w4=T8 / w5=T9)
- TDD 강제: yes. agent 배분 — security-engineer 6(T3·4·5·6·8·9), backend-engineer 2(T1·7), db-engineer 1(T2)
- 추가 검증: ktlint/detekt(신규 모듈 baseline), ArchUnit(BC 격리 — identity-access import 0)

## 리스크 / 주의 (learnings 반영)

- **새 모듈 첫 컴파일 wave 직렬화**(`bts-plan-wave-gradle-module-compile`). T1(모듈 스캐폴딩)이 단독 wave1 — 완료 전 다른 task 컴파일 불가. T1 실패 시 전체 blocked.
- **cross-BC 포트 소비 → test-boot fail-closed stub**(`new-crossbc-dep-openapi-mockbean-regression`·`crossbc-resolver-nullable-fail-open`). `SystemPermissionResolver`는 slack test-boot에 실 구현 없음 → `StubSystemPermissionResolver`(admins 비면 전부 403) 빈 필수.
- **부팅 안전성**(`profile-scoped-bean-boot-failure`·`minio-eager-bean-fullboot-regression`). 암호화 키·state 키·Slack credentials 미설정이어도 빈 등록·컨텍스트 부팅 유지, 사용 시점 검증.
- **detektMain type-resolved 엄격**(`module-first-scheduled-worker-detektmain-traps`). 신규 모듈 detekt는 타입 해석 엄격 — baseline 초기화.
- **평문 토큰 로깅 금지**(N1). T9에서 로그/응답 어서션으로 검증.
- **BC 격리**. slack-integration은 identity-access를 import하지 않음 — `SystemPermissionResolver`·`SecretEncryptor`·`OutboundHttpClientConfig`는 모두 shared-kernel 경유. 중앙 SecurityConfig 미수정(배포 조립 후속, ADR D7).
- **문서 동기화**. 신규 모듈이지만 FR-SL-01은 기존 FR(총수 123 불변). 머지 시 domain 노트 "KMS"→"AES" 정정, product `slack-integration.md` D1~D5 체크박스.

## 리뷰 결과 (← /bts-review-plan 채움)
