# ADR: FR-SL-01 — Slack App 설치(OAuth 2.0) + Bot Token 보관, slack-integration BC 신설

> 날짜: 2026-07-07
> 상태: Accepted (Maxi 게이트 확정)
> 관련 FR: FR-SL-01 (Slack App + Bot Token 방식)
> 관련 ADR: [2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md](2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md) (아웃바운드 HTTP + RestClient 재사용 패턴)
> 관련 PR: PR #244 (slack-integration BC 첫 구현, 백엔드 코어 D1~D5)

## 맥락

FR-SL-01은 **slack-integration BC의 첫 구현**이다. 이 BC는 아직 코드가 하나도 없는 신규 바운디드 컨텍스트로, 새 Gradle 모듈(`backend/modules/slack-integration`) 신설부터 시작한다. 목표는 Slack 워크스페이스에 BTS(Atlas) App을 **OAuth 2.0 설치 흐름**으로 연결하고, 획득한 **Bot Token을 암호화 저장**하는 것이다.

벤치마크로 확인한 Jira Cloud for Slack은 표준 OAuth 2.0 설치 흐름("연결" → authorization 화면 → 권한 승인 → secure token 보관)을 따른다. 이 사용자 경험은 어떤 구현 방식으로도 동일하게 달성 가능하다 — 사용성을 결정하는 것은 SDK 선택이 아니라 OAuth 설치 흐름과 관리자 UI다.

## 결정

### D1. slack-integration BC를 신규 Gradle 모듈로 신설

`backend/modules/slack-integration`. 다른 BC를 직접 import하지 않는다(CLAUDE.md §핵심 패턴 BC 격리). notification BC가 발행하는 알림 이벤트를 Slack으로 변환(FR-SL-02+), issue-tracking URL을 Unfurl(FR-SL-03)하는 등의 연동은 이벤트/포트 경유로만 한다.

- **패키지 루트 `com.bts.slack`**. 최신 BC 관례(`com.bts.notification`/`com.bts.search`)와 일관. `com.atlas.bts.identity`는 최초 모듈의 예외 표기.
- **부팅 모델 = test-boot only**. search-export-import 등 최신 BC처럼 프로덕션 `@SpringBootApplication` 없이 `SlackIntegrationTestBootApplication`(test)으로 통합 검증한다. 통합 배포 조립 앱은 현재 리포에 부재(Phase 1, `no-cross-bc-deployment-assembly`) — 후속.
- **build.gradle.kts는 각 모듈 직접 선언**(루트 subprojects 상속 없음). `implementation(project(":modules:shared-kernel"))` + `com.slack.api:slack-api-client:<버전>` 리터럴 + `detekt { baseline = file("detekt-baseline.xml") }`.

### D2. Slack 연동 = `slack-api-client` (공식 SDK의 client 층만, Bolt 프레임워크 미도입)

Slack 공식 Java SDK(`java-slack-sdk`)는 두 층으로 나뉜다.
- `com.slack.api:slack-api-client` — Web API 클라이언트(`oauth.v2.access`, `chat.postMessage`, HMAC `SignatureVerifier`). 단순 라이브러리.
- `com.slack.api:bolt(+bolt-servlet)` — Events/Slash/Interactive를 Servlet 기반으로 라우팅하는 풀 프레임워크.

**결정: `slack-api-client`만 도입한다**(신규 외부 의존성, Maxi 승인). 수신 엔드포인트(설치 콜백, 후속 Slash/Interactive)는 기존 `SecurityConfig`·Spring MVC 컨트롤러 관례로 직접 관리한다.

대안 기각.
- **자체 HTTP 클라이언트(의존성 0)**: FR-SL-01만 보면 `oauth.v2.access`가 단순 POST라 RestClient로 충분하나, 후속 FR의 HMAC 서명 검증·Block Kit·API 호출을 손으로 구현해야 하고 서명 검증 자체 구현은 보안 실수 위험이 크다.
- **Bolt for Java 풀 프레임워크**: Jira의 Node.js Bolt와 대응되지만, Java Bolt는 Servlet 기반 자체 관례(`SLACK_APP_TOKEN`, 리스너 디렉토리)를 강제해 우리 `SecurityFilterChain`/컨트롤러 관례와 이질적. FR-SL-01 범위에는 과설계.

### D3. Bot Token 암호화 = `SecretEncryptor`(AES) 재사용, `slackSecretEncryptor` 빈

`bot_token_encrypted`는 shared-kernel의 `com.bts.shared.crypto.SecretEncryptor`(대칭 AES)로 암호화 저장한다 — OIDC `client_secret`, webhook secret과 동일 패턴. BC별 키 격리를 위해 빈 이름을 `slackSecretEncryptor`로 명시하고 소비처는 `@Qualifier("slackSecretEncryptor")`로 by-name 주입한다(OIDC의 `oidcSecretEncryptor` 선례). 키/salt는 `BTS_SLACK_ENCRYPTION_KEY`/`BTS_SLACK_ENCRYPTION_SALT` 환경변수 → `bts.slack.encryption.{key,salt}` 프로퍼티 경유. **부팅 안전성**: 키 미설정이어도 빈은 항상 등록하고 검증은 encrypt/decrypt 호출 시점으로 미룬다(`profile-scoped-bean-boot-failure` 회귀 방지).

**정정**: `Maxi_wiki/BTS/domain/slack-integration.md`와 product 문서가 "KMS 암호화"로 표기했으나, Phase 1 인프라는 KMS 없이 앱 레벨 AES(SecretEncryptor)를 쓴다(OIDC/webhook과 일관). product NFR 표의 "KMS or AES-256" 중 AES-256 채택. KMS 전환은 v0.4+ 후속.

### D4. OAuth 2.0 설치 흐름 + state 파라미터 CSRF 검증

- `GET /slack/install` — Slack authorize URL로 302 리다이렉트. 관리자 권한 가드. 무작위 `state`를 발급·서버 보관(재생 방지).
- `GET /slack/install/callback?code=&state=` — `state` 검증(불일치 시 거부) → `oauth.v2.access`로 code↔token 교환(아웃바운드 HTTP는 webhook `WebhookHttpClientConfig` RestClient 패턴 참조) → bot token·team(workspace) 메타 암호화 저장.
- 복호화된 평문 토큰은 메모리에만 두고 **절대 로깅 금지**(OIDC §1.1.2 선례).

### D5. 스코프 = 백엔드 코어(D1~D5). 실 Slack App 없음 → 통합 테스트는 Slack API stub

이번 PR은 D1(도메인)~D5(백엔드 테스트)까지. D6(관리자 "Slack 연결" 프론트 UI)·D7(E2E)은 후속 PR. 실제 Slack 워크스페이스/App credentials가 없으므로 통합 테스트는 Slack `oauth.v2.access` 응답을 stub/mock하고, 실 credentials(`BTS_SLACK_CLIENT_ID`/`BTS_SLACK_CLIENT_SECRET`)는 환경변수로 후주입한다. 완제품 품질의 OAuth 흐름 코드는 실 App 유무와 무관하게 작성한다.

### D6. DB 접근 = JdbcTemplate

`slack_installs`는 단일 테이블 단순 CRUD(`team_id` upsert + `findByTeamId`)다. identity-access(동일 auth/security 영역)의 JdbcTemplate 관례를 따른다 — jOOQ codegen 스택(Testcontainers introspection·`src/generated/jooq` 소스셋·`init_codegen.sql` 미러) 신설 부담을 피하고 새 모듈 첫 컴파일 리스크를 최소화. DATA.md §5(파라미터 바인딩 `?` placeholder로 injection 방어)를 준수한다. Flyway 로케이션 `classpath:db/migration/slack-integration` + 버전 블록 **V700~V799**(BC별 100단위 예약 관례).

### D7. SecurityFilterChain = 이번 범위는 test-boot SecurityConfig, 배포 조립 시 중앙 등록은 후속

프로덕션 `SecurityFilterChain`은 identity-access 중앙 `SecurityConfig`에만 있다. `/slack/install/callback` permitAll을 이 중앙 config에 넣으면 **BC 격리 위반**이므로, 이번 PR은 slack test-boot의 자체 SecurityConfig로 검증만 한다(`/slack/install` 인증 필요·`/slack/install/callback` permitAll GET). 배포 조립 시점에 중앙 `SecurityConfig`에 콜백 permitAll을 추가하는 것은 **DEVELOPMENT.md §1.4 예외로 ADR/게이트 승인이 필요한 후속 작업**(public dashboards 경로 선례와 동일 취급).

## 알려진 한계 (수용)

- **단일 워크스페이스 가정 여부**: `slack_installs`는 워크스페이스(team_id) 단위 다중 설치를 표현할 수 있는 스키마로 두되, FR-SL-01 범위의 라우팅 로직은 최소화(다중 워크스페이스 라우팅은 FR-SL-06 채널 매핑에서 심화).
- **토큰 로테이션**: Slack token rotation(만료·refresh)은 FR-SL-01 범위 외. bot token은 비만료 토큰으로 저장하고, rotation 대응은 후속.

## 영향 / 후속

- 본 ADR의 slack-api-client 도입·`slackSecretEncryptor`·OAuth 설치 흐름은 후속 FR-SL-02~06이 재사용하는 BC 기반 결정이다.
- domain 노트/product 문서의 "KMS" 표기는 머지 시 "AES(SecretEncryptor), KMS는 v0.4+" 로 동기화한다.
- D6/D7(프론트 관리자 페이지·E2E)은 후속 PR. FR-SL-01은 그때까지 `[~]`(백엔드 완성).
