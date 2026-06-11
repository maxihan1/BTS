# FR-MF-01 — TOTP (Authenticator 앱 기반 다중 요소 인증) — 스펙

> BC: identity-access · type: auth · agent: security-engineer
> SDD: §19.7(2FA), §19.7.1(TOTP 필수 RFC 6238), §19.7.4(설정 흐름), §19.5(`mfa_verified` 클레임)
> 범위(Maxi 확정): **FR-MF-01 단독** — TOTP only. 백업코드(FR-MF-02)·강제정책(FR-MF-04)·신뢰디바이스(FR-MF-05) 제외.

## 용어

- **TOTP** (Time-based One-Time Password) — RFC 6238. 공유 비밀(secret) + 현재 시각(30초 time-step)을 HMAC-SHA1으로 묶어 만든 6자리 코드. Authenticator 앱(Google Authenticator 등)이 30초마다 갱신.
- **otpauth:// URI** — Authenticator 앱이 QR로 읽어 secret을 등록하는 표준 URI 형식(`otpauth://totp/BTS:user@x?secret=...&issuer=BTS`).
- **MFA 챌린지 토큰** — 1단계(비밀번호) 통과 후 2단계(TOTP) 대기 상태를 들고 가는 단명(5분) 서명 JWT. 정식 세션이 아님.
- **time-step** — `floor(epochSecond / 30)`. 같은 코드가 유효한 30초 구간의 정수 인덱스. replay 방어에 사용.

## 사용자 시나리오 (Given-When-Then)

### S1. TOTP 등록 (설정 흐름, SDD §19.7.4)
- **Given** 로그인한 사용자가 보안 설정 화면에 있고 TOTP 미활성 상태
- **When** "2FA 활성화"를 누르면
- **Then** 서버가 새 secret(PENDING)을 만들고 QR(otpauth:// 기반 PNG) + secret 문자열을 응답한다. 화면에 QR이 표시된다.
- **When** 사용자가 Authenticator 앱으로 스캔 후 앱이 보여주는 6자리 코드를 입력하면
- **Then** 서버가 코드를 검증하고 일치하면 TOTP를 ACTIVE로 전환, "활성화 완료"를 응답한다(`MFA_ENABLED` 감사).
- **When** 코드가 틀리면
- **Then** 400 `invalid_code`를 응답하고 PENDING 상태를 유지한다(재시도 가능).

### S2. TOTP 사용 로그인 (2단계)
- **Given** TOTP가 ACTIVE인 사용자
- **When** `POST /login`으로 올바른 비밀번호를 제출하면
- **Then** 서버는 **정식 세션을 발급하지 않고** 200 `{ mfa_required: true, mfa_challenge_token, expires_in }`를 응답한다.
- **When** 사용자가 챌린지 토큰 + Authenticator의 현재 6자리 코드로 `POST /auth/mfa/verify`를 호출하면
- **Then** 서버가 챌린지 토큰(미만료·purpose 검증)과 TOTP 코드를 검증하고, 성공 시 정식 세션(access token + refresh cookie, `mfa_verified=true`)을 발급한다(`MFA_CHALLENGE_SUCCESS` 감사).
- **When** TOTP 코드가 틀리면
- **Then** 401 `invalid_code`를 응답한다(`MFA_CHALLENGE_FAILURE` 감사). 챌린지 토큰은 만료 전까지 재시도 가능(제한 횟수 내).

### S3. TOTP 미사용 로그인 (회귀 없음)
- **Given** TOTP 미활성 사용자
- **When** 올바른 비밀번호로 `POST /login` 하면
- **Then** 기존과 동일하게 즉시 정식 세션을 발급한다(`mfa_verified=false`). **기존 로그인 흐름 무변경**.

### S4. TOTP 비활성화 (step-up)
- **Given** TOTP가 ACTIVE인 로그인 사용자
- **When** 현재 Authenticator 코드를 함께 제출하며 `DELETE /auth/mfa/totp`를 호출하면
- **Then** 코드 검증 성공 시 TOTP를 제거하고 204를 응답한다(`MFA_DISABLED` 감사 + SDD §19.10 사용자·관리자 알림은 notification BC 책임 → 본 PR은 감사 이벤트만, 알림 emit은 후속).
- **When** 코드가 틀리면
- **Then** 400 `invalid_code`. TOTP는 그대로 유지된다(무단 비활성화 차단).

### S5. 상태 조회
- **Given** 로그인 사용자
- **When** 보안 설정 화면 진입(`GET /auth/mfa/totp`)
- **Then** `{ enabled: boolean }`로 활성 여부를 받아 "활성화" vs "비활성화" UI를 분기한다.

## 기능 요구사항 (FR)

- **FR-1** secret 생성 — CSPRNG 기반 base32 secret(160bit 권장)을 생성하고 AES-256-GCM으로 암호화해 저장한다(평문 저장 금지).
- **FR-2** QR provisioning — `otpauth://totp/{issuer}:{label}?secret=...&issuer={issuer}&algorithm=SHA1&digits=6&period=30` URI를 만들고, 백엔드가 ZXing(samstevens)으로 QR PNG(data URI)를 함께 반환한다(프론트 QR 의존성 불요). **(GAP-4)** issuer=`"BTS"`(고정), label=사용자 email(없으면 username). otpauth label은 URL 인코딩.
- **FR-3** enable 확정 — PENDING secret에 대해 사용자가 입력한 코드를 검증해 일치 시 ACTIVE로 전환한다(setup→verify 2-step, 잘못된 secret 영구 활성화 방지).
- **FR-4** 로그인 2단계 — 1단계 인증 성공 후 사용자가 ACTIVE TOTP를 가지면 정식 세션 대신 MFA 챌린지 토큰을 발급한다.
- **FR-5** 챌린지 검증 — 챌린지 토큰(서명·미만료·purpose=`mfa_challenge`) + TOTP 코드 검증 성공 시 정식 세션을 `mfa_verified=true`로 발급한다.
- **FR-6** `mfa_verified` 실체화 — `JwtIssuer.issue`에 `mfaVerified` 파라미터를 추가해 클레임을 실제 값으로 발급한다(기존 `false` 더미 제거). 비-MFA 로그인은 `false`.
- **FR-6b (GAP-1)** `mfa_verified` 영속 — `sessions` 테이블에 `mfa_verified BOOLEAN NOT NULL DEFAULT false` 컬럼을 추가하고 Session 도메인 엔티티에 전파한다. login 2단계 verify로 만든 세션은 `true`, 일반 로그인 세션은 `false`. **refresh 토큰 회전 시 `RefreshTokenService.rotate`가 `session.mfaVerified`를 `JwtIssuer.issue`에 전달**해 재발급 JWT의 클레임이 소실되지 않게 한다(rotate는 line 110에서 session 조회하므로 접근 가능).
- **FR-7** 비활성화 — step-up(현재 코드 검증) 후 TOTP 제거.
- **FR-8** 상태 조회 — 본인 TOTP 활성 여부 조회 엔드포인트.
- **FR-9** 감사 — `MFA_ENABLED` / `MFA_CHALLENGE_SUCCESS` / `MFA_CHALLENGE_FAILURE` / `MFA_DISABLED` 이벤트를 best-effort 기록(FR-AU-10 인프라 재사용).
- **FR-10** 프론트 UI — 보안 설정 화면(`/settings/mfa`)에서 활성화(QR 스캔+코드 입력)·비활성화, 로그인 화면 MFA 코드 입력 단계.

## 비기능 요구사항 (NFR)

- **NFR-1 (암호화)** TOTP secret은 AES-256-GCM(random IV)으로 암호화 후 저장. 기존 `SecretEncryptor` 패턴 재사용 + 전용 키 `BTS_MFA_ENCRYPTION_KEY/SALT`(OIDC 키와 격리). secret 평문은 응답(QR 등록 시점 1회 제외)·로그·예외 메시지에 절대 노출 금지.
- **NFR-2 (brute-force 방어, GAP-2 본 PR 포함)** 6자리(±1 window) = 추측 공간 작음. **Caffeine 기반 사용자별 시도 카운터**(`MfaAttemptLimiter`)로 verify(로그인 2단계)·enable 실패를 집계해 연속 **5회 실패 / 5분 window** 초과 시 일시 차단(429 `too_many_attempts`, 원인 비노출). 성공 시 카운터 리셋. 기존 Caffeine 의존성 재사용(신규 dep 0, `SidRevokeJwtConverter` 선례). 차단은 메모리 only(단일 호스트) — 영구 lockout/계정 잠금은 FR-MF-04로 이연.
- **NFR-3 (replay 방어)** 검증 성공한 time-step을 `last_verified_step`에 저장하고, 동일·과거 time-step의 코드 재제출을 거부한다(RFC 6238 §5.2).
- **NFR-4 (시각 동기)** 검증 시 ±1 time-step(±30초) 허용으로 클럭 드리프트 수용. 검증·secret 로직은 주입된 `Clock` 사용(테스트 가능성 — 메모리 authcontroller-revokesession-timebomb).
- **NFR-5 (계정 열거 방지)** setup/enable/disable/status는 JWT 인증 필수(본인만). 로그인 2단계의 챌린지 토큰은 userId를 담되, 실패 응답은 원인 무관 일반 메시지.
- **NFR-6 (상수시간 비교)** 코드 비교는 라이브러리(samstevens `CodeVerifier`)의 상수시간 비교에 위임.
- **NFR-7 (가용성)** 감사 INSERT 실패가 로그인/설정 흐름을 막지 않도록 best-effort(기존 `recordAuditBestEffort` 패턴).

## API 인터페이스 (REST)

| 메서드 | 경로 | 인증 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/api/v1/auth/mfa/totp/setup` | JWT | — | 200 `{ otpauth_uri, secret_base32, qr_png_data_uri }` / 409 `already_enabled` |
| POST | `/api/v1/auth/mfa/totp/enable` | JWT | `{ code }` | 204 / 400 `invalid_code` / 409 `no_pending_setup` |
| GET | `/api/v1/auth/mfa/totp` | JWT | — | 200 `{ enabled: boolean }` |
| DELETE | `/api/v1/auth/mfa/totp` | JWT | `{ code }` | 204 / 400 `invalid_code` / 404 `not_enabled` |
| POST | `/api/v1/auth/mfa/verify` | 챌린지 토큰(body) | `{ mfa_challenge_token, code }` | 200 `{ access_token }` + Set-Cookie refresh / 401 `invalid_code` / 401 `mfa_challenge_expired` |
| POST | `/api/v1/auth/login` (변경) | — | 기존 | TOTP 활성 사용자: 200 `{ mfa_required: true, mfa_challenge_token, expires_in }` · 그 외: 기존 토큰 응답 |

- setup/enable/disable/status는 JWT 전용(PAT 403 — 세션 관리 선례와 동일 원칙).
- `/auth/mfa/verify`·`/auth/login`은 SecurityConfig에서 CSRF/permitAll 처리(login 선례 동일). 챌린지 토큰은 Authorization 헤더가 아닌 body로 받는다(아직 정식 세션 아님).
- **(GAP-3) login 200 응답은 discriminated union** — TOTP 활성 사용자는 `{ mfa_required: true, mfa_challenge_token, expires_in }`, 그 외는 `{ access_token, token_type, expires_in }`. 프론트 `useLoginMutation`은 `TokenResponseSchema.parse` 전에 `mfa_required` 플래그로 분기한다(현재 무분기 parse는 MFA 응답에서 Zod 실패). 기존 401 `mfa_required`(AuthnResult.RequiresMfa dead path)와 의미 구분 — 200+`mfa_required:true`는 "정상 2단계 진입", 401은 미사용 레거시. 프론트 `LOGIN_ERROR_MESSAGES.mfa_required`(에러문구)는 2단계 진입 처리로 대체.

## 데이터 모델 변경

`V022__totp_secrets.sql` (identity-access, jdbc-only → init_codegen 미러 불요)

```sql
CREATE TABLE totp_secrets (
    user_id            UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    secret_cipher      TEXT NOT NULL,                       -- AES-256-GCM(base32 secret), hex
    status             VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE')),
    last_verified_step BIGINT,                              -- replay 방어 (RFC 6238 §5.2)
    confirmed_at       TIMESTAMPTZ,                         -- ACTIVE 전환 시각
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- user당 1행(PK user_id). setup → PENDING upsert(기존 PENDING 덮어쓰기), enable → ACTIVE, disable → DELETE.
- 사용자 삭제 시 CASCADE(메모리 join-table-fk-cascade 관례 일치).

**(GAP-1) 같은 V022에 sessions 컬럼 추가**

```sql
ALTER TABLE sessions ADD COLUMN mfa_verified BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN sessions.mfa_verified IS '이 세션이 MFA(2FA) 2단계를 통과했는지. login 2단계 verify=true, 일반 로그인=false. refresh 회전 시 JWT mfa_verified 클레임 원천 (FR-MF-01)';
```

- 마이그레이션 1개(`V022__mfa_totp.sql`)에 `CREATE totp_secrets` + `ALTER sessions`를 함께 둔다(응집된 단일 변경). 동시 브랜치 V번호 충돌은 머지 직전 재확인(메모리 migration-vnumber-concurrent-branch-collision).

## 엣지 케이스

- **EC-1** 이미 ACTIVE인데 setup 재호출 → 409 `already_enabled`(비활성화 먼저 유도). PENDING 재호출은 새 secret으로 재생성 허용.
- **EC-2** enable인데 PENDING 없음(setup 안 함) → 409 `no_pending_setup`.
- **EC-3** 챌린지 토큰 만료(5분 초과) → 401 `mfa_challenge_expired`(로그인부터 재시도).
- **EC-4** 챌린지 토큰 서명/purpose 위조 → 401 `invalid_code`(원인 비노출).
- **EC-5** replay — 직전 성공 코드 재제출(같은 30초 창) → 거부(`last_verified_step` ≥ 제출 step).
- **EC-6** 클럭 드리프트 — 앱·서버 ±30초 차 → ±1 window로 수용. 그 이상은 실패.
- **EC-7** disable 시 코드 누락/오류 → 무단 비활성화 차단(400).
- **EC-8** PAT로 setup/enable/disable/status 호출 → 403(세션 관리 선례).
- **EC-9** 동시성 — 두 탭에서 동시에 verify(login 2단계) → replay 방어로 한쪽만 성공. advisory lock 또는 조건부 UPDATE(`WHERE last_verified_step < :step`)로 TOCTOU 차단(메모리 advisory-lock-bigint-toctou).
- **EC-10** 암호화 키 미설정 환경(슬라이스 테스트) → `SecretEncryptor` 선례처럼 빈은 항상 등록, 키는 사용 시점 검증(부팅 안전성, 메모리 profile-scoped-bean-boot-failure).

## 제약 조건

- 신규 maven 의존성 `dev.samstevens.totp:totp:1.7.1`(절대 규칙 #17 Maxi 승인 완료). `gradle/libs.versions.toml` 등록.
- 단일 BC(identity-access). 프론트 UI는 same-BC view layer로 같은 PR(선례 — learnings 2026-05-22 옵션 C).
- 기존 로그인 흐름(TOTP 미활성 사용자)은 무변경(회귀 0). `AuthnResult.RequiresMfa` 프로바이더 경로는 본 PR이 controller-layer MFA 게이트를 쓰므로 손대지 않는다(향후 프로바이더-측 MFA용으로 보존).
- 백업코드 부재로 폰 분실 시 복구 불가 → **FR-MF-02 즉시 후속 권장**(본 PR 후속 작업에 명시).

## 측정 가능한 완료 기준

1. TOTP 미활성 사용자 로그인 회귀 0(기존 `AuthControllerTest` green).
2. setup→enable→login(2단계)→verify→정식세션(`mfa_verified=true`) end-to-end 통합 테스트 통과(Testcontainers).
3. secret이 DB에 평문으로 저장되지 않음(암호문 검증 테스트).
4. replay 방어: 같은 코드 2회 제출 시 2번째 거부 테스트.
5. 틀린 코드/만료 토큰/PAT 접근 각 에러코드 검증 테스트.
6. `./gradlew :backend:modules:identity-access:test ktlintCheck detekt` green.
7. 프론트 `/settings/mfa` 활성화·비활성화 + 로그인 MFA 단계 E2E(Playwright) 통과, `pnpm verify` green.
8. SDD §19.7 / fr-index FR-MF-01 마킹 + product/identity-access 체크박스 동기화(verify-master-plan.sh 통과).

## Brainstorming Check

✅ 통과 (1회 iteration). 발견 4건 모두 해소.
- GAP-1 (correctness): refresh 회전 시 mfa_verified 소실 → sessions.mfa_verified 컬럼 + Session 엔티티 전파 (FR-6b).
- GAP-2 (Maxi 결정): brute-force rate-limit → Caffeine MfaAttemptLimiter 본 PR 포함 (NFR-2).
- GAP-3 (clarify): login 200 discriminated union → 프론트 분기 + 레거시 401 mfa_required 구분.
- GAP-4 (clarify): otpauth issuer/label → BTS / email(fallback username).

## 알려진 함정 (구현 시 회귀 가드)
- `AuthEventType` enum에 MFA 이벤트 4종 추가 → `AuthEventEmitCoverageTest` emit 커버리지 갱신 필요(메모리 enum-add-breaks-crossmodule-count-guard, 단 identity-access 단일 모듈).
- secret 암호화 키 미설정 환경 부팅 안전성 → SecretEncryptor 선례(빈 항상 등록, 사용 시점 검증).
- 시각 의존(time-step) 로직은 주입된 Clock 사용(메모리 authcontroller-revokesession-timebomb).
- verify 동시성 TOCTOU → 조건부 UPDATE(`WHERE last_verified_step < :step`) 또는 advisory lock(메모리 advisory-lock-bigint-toctou).
- identity-access prod+RANDOM_PORT 통합테스트 부팅 레시피 준수(메모리 identity-access-prod-randomport-boot-recipe).
