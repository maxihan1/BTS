# FR-SL-01 Slack App 설치(OAuth 2.0) + Bot Token 보관 — 스펙

> BC: slack-integration (신규) · type: auth · 스코프: 백엔드 코어 D1~D5
> ADR: [docs/decisions/2026-07-07-fr-sl-01-slack-bot-app.md](../decisions/2026-07-07-fr-sl-01-slack-bot-app.md)

## 목표 한 줄

시스템 관리자가 BTS(Atlas) App을 Slack 워크스페이스에 **OAuth 2.0 설치 흐름**으로 연결하고, 발급받은 **Bot Token(`xoxb-…`)을 AES 암호화하여 `slack_installs`에 보관**한다.

## 사용자 시나리오 (Given-When-Then)

- **S1 설치 시작**. Given 시스템 관리자가 로그인한 상태, When `GET /slack/install` 호출, Then `https://slack.com/oauth/v2/authorize`로 302 리다이렉트(`client_id`·`scope`(SDD 09.3.1 봇 스코프)·`state`·`redirect_uri` 포함).
- **S2 설치 완료**. Given 관리자가 Slack 동의 화면에서 권한 승인, When Slack이 `GET /slack/install/callback?code=&state=`로 리다이렉트, Then `state` 검증 통과 → `oauth.v2.access`로 code↔token 교환 → bot token 암호화 저장 → 설치 완료 응답.
- **S3 재설치(같은 워크스페이스)**. Given `team_id`가 이미 설치됨, When 다시 설치 완료, Then 기존 행을 갱신(upsert) — 중복 행 없음, 토큰만 최신화.
- **S4 state 위조/불일치**. Given callback의 `state`가 서버 발급값과 불일치·서명 위조·만료, When callback, Then **거부(토큰 교환 안 함)**.
- **S5 권한 없는 사용자**. Given 비-시스템관리자, When `GET /slack/install`, Then 403.
- **S6 Slack 토큰 교환 실패**. Given `oauth.v2.access`가 `{ok:false, error:...}` 반환(예: `invalid_code`), When callback, Then 설치 실패 응답(토큰 저장 안 함), Slack error 코드만 로깅(민감정보 없음).

## 기능 요구사항 (FR)

- **FR1**. `GET /slack/install` — 시스템 관리자 가드. `state` 발급(추측 불가·만료·installed_by 바인딩) 후 Slack authorize URL로 302.
- **FR2**. `GET /slack/install/callback` — `state` 검증 → `code`로 `oauth.v2.access`(POST, `application/x-www-form-urlencoded`, `code`·`client_id`·`client_secret`·`redirect_uri`) 호출 → 응답 파싱.
- **FR3**. Bot token은 `SecretEncryptor`(AES-256-GCM, 빈 `slackSecretEncryptor`)로 암호화하여 `bot_token_encrypted`에 저장. **평문 토큰 로깅·응답 노출 금지**.
- **FR4**. `team_id`(Slack workspace id) UNIQUE 기준 **upsert** — 재설치 시 토큰·메타 갱신.
- **FR5**. Slack 응답 `ok:false` 또는 필수 필드 누락 시 저장하지 않고 실패 처리.
- **FR6**. 저장 메타. `team_id`·`team_name`·`bot_user_id`·`app_id`·`scope`(발급된 스코프 CSV)·`installed_by`(BTS user id)·`is_enterprise_install`.

## 비기능 요구사항 (NFR)

- **N1 토큰 보호**. `bot_token_encrypted` 평문 저장 금지. 복호화 평문은 메모리에만, 로그/응답/예외메시지에 절대 미포함(§1.1.2).
- **N2 CSRF**. `state`는 `SecureRandom` 기반 추측 불가값. **STATELESS 정합** — 서버 세션 없이 검증 가능해야 함(아래 §state 메커니즘).
- **N3 client_secret**. `BTS_SLACK_CLIENT_SECRET` 환경변수 주입, DB/코드/로그 저장 금지.
- **N4 부팅 안전성**. 암호화 키·Slack credentials 미설정이어도 빈은 등록되고 컨텍스트 부팅이 깨지지 않음(사용 시점 검증). `profile-scoped-bean-boot-failure`/`minio-eager-bean-fullboot-regression` 회귀 방지.
- **N5 아웃바운드 SSRF**. 토큰 교환 대상은 Slack 고정 호스트(`slack.com`)뿐 — 사용자 입력 URL 없음. 리다이렉트 자동 추적 비활성(webhook RestClient 패턴 준용).
- **N6 성능**. 설치 콜백 p95 < 3s (Slack API 왕복 포함).

## state 메커니즘 (STATELESS 정합 — 핵심 설계 결정)

BTS는 STATELESS JWT라 서버 세션에 state/installed_by를 담을 수 없다. **서명된 self-contained state**를 채택한다.
- `GET /slack/install`에서 `state = base64url(payload) + "." + HMAC-SHA256(payload, stateKey)`. `payload = { nonce(SecureRandom), installedBy(userId), exp(now+10분) }`.
- callback에서 (1) HMAC 서명 검증, (2) `exp` 만료 검증. 둘 다 통과 시 `installedBy` 추출 → 저장에 사용. 서버 저장소 0.
- `stateKey`는 `BTS_SLACK_STATE_KEY` 환경변수 주입(SecretEncryptor와 별도 서명 키). Slack `state`는 최대 길이 제약 있으므로 payload 최소화.

기각 대안. (a) HTTP 세션 — STATELESS 위반. (b) 단기 DB 테이블(`slack_oauth_states`) — 추가 마이그레이션·정리 배치 필요, 10분 수명 값에 과설계.

## API 인터페이스 (REST)

| 메서드 | 경로 | 인증/가드 | 요청 | 응답 |
|---|---|---|---|---|
| GET | `/slack/install` | JWT + 시스템 관리자 | — | 302 → Slack authorize URL |
| GET | `/slack/install/callback` | permitAll (Slack 리다이렉트, state로 자체 검증) | `code`, `state` | 302 → 설치 결과 화면 경로(프론트 D6) / 임시 성공·실패 페이지 |

- callback은 Slack이 브라우저를 통해 부르는 최상위 GET 리다이렉트이므로 사용자 JWT가 없다. 인가는 **서명 state**로 대체(installed_by가 state에 박제됨).

## 데이터 모델 변경

신규 테이블 `slack_installs` (Flyway V___). init_codegen 미러 동기화(DATA.md).

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | uuid PK | |
| `team_id` | text UNIQUE NOT NULL | Slack workspace id (`T…`) — upsert 기준 |
| `team_name` | text NOT NULL | |
| `bot_user_id` | text NOT NULL | `U…` |
| `app_id` | text NOT NULL | `A…` |
| `bot_token_encrypted` | text NOT NULL | AES-256-GCM hex (SecretEncryptor) |
| `scopes` | text NOT NULL | 발급 스코프 CSV |
| `is_enterprise_install` | boolean NOT NULL default false | |
| `installed_by` | uuid NOT NULL | BTS user id (state에서 추출) |
| `installed_at` | timestamptz NOT NULL default now() | |
| `updated_at` | timestamptz NOT NULL default now() | upsert 시 갱신 |

- 인덱스. `team_id` UNIQUE(멱등 upsert `ON CONFLICT (team_id)`).

## 엣지 케이스

- **EC1** `state` 없음/서명 위조/만료 → 거부(401/403 성격), 토큰 교환 스킵.
- **EC2** `code` 없음 또는 `error` 쿼리(사용자가 Slack에서 취소, `?error=access_denied`) → 실패 화면, 저장 안 함.
- **EC3** `oauth.v2.access` `ok:false` → 실패, Slack `error` 코드만 로깅.
- **EC4** 재설치(같은 `team_id`) → upsert 갱신, 중복 없음.
- **EC5** enterprise install(`is_enterprise_install:true`) → 플래그 저장(라우팅 심화는 FR-SL-06).
- **EC6** 암호화 키/state 키/credentials 미설정 → 부팅은 유지, 설치 시도 시 명확한 500(민감정보 없는 메시지).
- **EC7** 필수 응답 필드(`access_token`/`team.id` 등) 누락 → 저장 안 함, 실패 처리.

## 관리자 가드 (cross-BC — brainstorming 보강 G2)

`OutboundWebhookService`(search-export-import) 선례를 그대로 준용한다.
- `GET /slack/install`에서 JWT actor(userId)를 추출(webhook `OutboundWebhookActorExtractor` 패턴) → `SystemPermissionResolver.isSystemAdmin(actorId)` 판정(cross-BC 포트, **fail-closed**, non-null 기본값 — `crossbc-resolver-nullable-fail-open` 회귀 방지). 아니면 403.
- identity-access를 **직접 import하지 않는다** — resolver 포트만 소비, 구현체는 identity-access가 제공. 신규 포트 소비이므로 슬라이스/OpenApi 통합 부팅에 `@MockBean` 동반 필요(`new-crossbc-dep-openapi-mockbean-regression` 회귀 방지).
- 관리자 검증은 `/slack/install`에서 수행하고 결과를 **서명 state의 `installedBy`에 박제**한다. callback은 state 서명이 "관리자가 개시함"의 증명이므로 재판정하지 않는다(그 사이 권한 회수는 10분 범위 밖, 수용).

## enterprise install 처리 (brainstorming 보강 G1)

org-wide install(`is_enterprise_install:true`)은 응답에 `team`이 null이고 `enterprise`만 온다. **FR-SL-01은 워크스페이스 단위 설치만 지원** — `team.id`가 없으면(enterprise install) 저장하지 않고 **명시적 거부**(실패 화면, `unsupported_install_type`). enterprise 지원은 후속. `is_enterprise_install` 컬럼은 향후 확장 대비 보관하되 이번 범위에선 항상 false 저장 경로만 성공.

## redirect_uri / 완료 리다이렉트 (brainstorming 보강 G3·G4)

- **redirect_uri**. `BTS_SLACK_REDIRECT_URI` 환경변수(예: `https://<host>/slack/install/callback`). authorize 단계와 `oauth.v2.access` 교환 단계에 **동일값**을 넘긴다(불일치 시 Slack이 거부). Slack App의 Redirect URLs 등록은 수동 사전작업(§0).
- **완료 목적지**. 백엔드 코어라 프론트 D6 페이지가 아직 없으므로, callback은 프론트 결과 경로로 302한다 — 성공 `/settings/slack?installed=<teamName>`, 실패 `/settings/slack?error=<code>`. D6에서 해당 경로가 결과를 렌더한다(경로만 예약, 화면은 후속 PR).

## 제약 조건

- **BC 격리**. slack-integration은 다른 BC를 직접 import하지 않는다(`SystemPermissionResolver` 포트만 소비). installed_by userId는 값으로만 보관, users 조인/검증은 범위 외 — 후속.
- **실 App 없음**. 통합 테스트는 Slack `oauth.v2.access`를 stub(WireMock/MockWebServer 또는 RestClient 주입 대체). 실 credentials는 환경변수 후주입.
- **완제품 품질**. OAuth 흐름·암호화·에러 처리 모두 production-ready. PoC 코드 금지.

## 측정 가능한 완료 기준

- 단위: state 발급/검증(위조·만료 거부), oauth.v2.access 응답 파싱(성공/ok:false/필드누락), 토큰 암호화 round-trip.
- 통합(Testcontainers + Slack stub): 설치 완료 → `slack_installs` 행 생성/암호화 확인, 재설치 upsert(중복 0), state 불일치 거부, 시스템관리자 아닌 사용자 `/slack/install` 403.
- 보안: 평문 토큰이 로그/응답에 없음(grep/어서션).
- ktlint/detekt/ArchUnit 통과, 신규 모듈 컴파일.

## Brainstorming Check

✅ 통과 (1회 iteration). adversarial sanity check로 gap 4건 발견·보강.
- G1 enterprise install `team` null → 워크스페이스 설치만 지원, enterprise install 명시적 거부.
- G2 관리자 가드 cross-BC → `SystemPermissionResolver` 포트 재사용(OutboundWebhookService 선례), identity-access import 금지.
- G3 redirect_uri → `BTS_SLACK_REDIRECT_URI` 환경변수, authorize/exchange 동일값.
- G4 callback 완료 목적지 → 프론트 결과 경로 302(D6 경로 예약).
