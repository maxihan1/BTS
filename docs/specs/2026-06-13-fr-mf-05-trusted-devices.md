<!-- FR-MF-05 신뢰 디바이스(30일 MFA 면제) 백엔드 슬라이스 D1~D5 기술 스펙 -->
# FR-MF-05 — 신뢰 디바이스 (30일 MFA 면제) 백엔드 스펙

> slug: fr-mf-05-trusted-devices | BC: identity-access | type: auth
> ADR: docs/decisions/2026-06-13-trusted-device-mfa-exemption.md
> 범위: 백엔드 D1~D5 (프론트 D6/D7 후속 PR)

## 개요

MFA(2단계 인증)를 켠 사용자가 MFA verify 성공 시 "이 기기 30일 신뢰"에 동의하면, 서버가 불투명 토큰을 발급해 HttpOnly 쿠키로 내려준다. 이후 30일 동안 같은 브라우저(쿠키 보유)에서는 1단계(비밀번호)만 통과하면 MFA 챌린지 없이 정식 세션을 받는다. 사용자는 신뢰 디바이스를 수동으로 취소할 수 있고, 비밀번호 변경·MFA 비활성화 시 자동 폐기된다.

## 사용자 시나리오 (Given-When-Then)

**S1 — 신뢰 등록.**
- Given: TOTP 활성 사용자가 로그인 1단계(비밀번호) 통과 → `mfa_required` 챌린지 토큰 수령.
- When: `POST /mfa/verify`에 올바른 TOTP 코드 + `trust_device=true` 제출.
- Then: 정식 세션 발급(`mfa_verified=true`) + `Set-Cookie: trusted_device=<rawToken>; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=2592000`. DB `trusted_devices`에 `token_hash`(SHA-256) + `expires_at`(now+30일) 1행 INSERT.

**S2 — 신뢰 우회 로그인.**
- Given: S1으로 신뢰 등록한 브라우저(쿠키 보유), 30일 이내.
- When: `POST /login`(provider/username/password) — 브라우저가 `trusted_device` 쿠키 동반.
- Then: 1단계 통과 후 쿠키의 신뢰 디바이스가 유효(user 일치 + 미만료)하므로 챌린지 없이 즉시 정식 세션 발급(`mfa_verified=true`). `last_used_at` 갱신.

**S3 — TTL 만료 후 복귀.**
- Given: 신뢰 등록 후 30일 경과(`expires_at < now`).
- When: 쿠키 동반 로그인.
- Then: 신뢰 디바이스가 만료라 우회 불가 → 기존대로 `mfa_required` 챌린지 발급.

**S4 — 명시적 취소.**
- Given: 신뢰 디바이스 보유 사용자(정식 세션).
- When: `DELETE /mfa/trusted-devices/{id}`(단건) 또는 `DELETE /mfa/trusted-devices`(전체).
- Then: 해당 행 삭제. 이후 그 쿠키로 로그인해도 우회 불가(챌린지로 복귀).

**S5 — 비밀번호 변경 자동 폐기.**
- Given: 신뢰 디바이스 보유 사용자.
- When: 비밀번호 변경 성공.
- Then: 해당 user의 신뢰 디바이스 전량 자동 revoke. 다음 로그인은 챌린지.

**S6 — TOTP 비활성화 자동 폐기.**
- Given: 신뢰 디바이스 보유 사용자.
- When: `DELETE /api/v1/auth/mfa/totp` 성공(TOTP 비활성화).
- Then: 신뢰 디바이스 전량 자동 revoke.

**S7 — 신뢰 디바이스 목록 조회.**
- Given: 정식 세션(JWT).
- When: `GET /mfa/trusted-devices`.
- Then: 본인의 미만료 신뢰 디바이스 목록(`id`, `label`, `createdAt`, `lastUsedAt`, `expiresAt`) 반환(`token_hash` 등 비밀값 비노출).

## 기능 요구사항 (FR)

- **FR-1**. MFA verify 성공 + `trust_device=true` 시 신뢰 토큰 발급·저장·쿠키 설정. `trust_device` 생략/false면 기존 동작 그대로(회귀 0).
- **FR-2**. 신뢰 토큰 = 암호학적 난수 32바이트(base64url rawToken). DB는 `SHA-256(rawToken)` hex 64자(`token_hash`)만 저장. 평문 비영속·비로깅.
- **FR-3**. 로그인 1단계 통과 후, MFA 활성이고 `trusted_device` 쿠키가 유효(token_hash 일치 + `user_id` 일치 + `expires_at > now`)하면 챌린지 생략하고 `issueTokens(mfaVerified=true)`.
- **FR-4**. 신뢰 우회 성공 시 `last_used_at = now` 갱신(표시용). `expires_at`는 갱신하지 않음(고정 30일).
- **FR-5**. 신뢰 토큰 발급은 모든 2차 요소 성공 경로(TOTP/백업코드/WebAuthn) 공통(verify 성공 + trust_device=true).
- **FR-6**. `GET /mfa/trusted-devices` — 본인 미만료 목록(JWT 전용, PAT 403).
- **FR-7**. `DELETE /mfa/trusted-devices/{id}` — 소유 검증 후 단건 삭제. 미존재/타인 소유 404(IDOR 은닉). JWT 전용, PAT 403.
- **FR-8**. `DELETE /mfa/trusted-devices` — 본인 전체 삭제(204). JWT 전용, PAT 403.
- **FR-9**. 비밀번호 변경 성공 시 해당 user 신뢰 디바이스 전량 자동 revoke.
- **FR-10**. TOTP 비활성화(`MfaService.disable` 성공) 시 해당 user 신뢰 디바이스 전량 자동 revoke.
- **FR-11 (감사 로그)**. 기존 MFA self-service 일관(MFA_ENABLED/DISABLED/… 선례). 신규 `AuthEventType` 2종 — `TRUSTED_DEVICE_ADDED`(신뢰 등록 성공 시), `TRUSTED_DEVICE_REVOKED`(수동/자동 revoke로 실제 1건 이상 삭제됐을 때, metadata에 count만). 신뢰 우회 로그인은 기존 `LOGIN_SUCCESS`로 이미 기록됨(별도 enum 미추가). 비밀값(rawToken/hash)은 metadata에 담지 않음(§1.1.2).

## 비기능 요구사항 (NFR)

- **NFR-보안-1**. 토큰 256-bit 엔트로피. DB 해시만(역산 불가). 평문은 발급 응답 Set-Cookie에만 1회 노출, 절대 로깅 안 함(§1.1.2).
- **NFR-보안-2**. 쿠키 `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`(refresh_token 선례 동일). XSS 탈취·cross-site 자동전송 차단.
- **NFR-보안-3**. 신뢰 조회는 `user_id` bound. 타 사용자 토큰이 일치해도 우회 불가(FR-3). 우회 판정은 1단계(비밀번호) Success 이후에만 — 미인증 열거 차단.
- **NFR-보안-4**. fail-safe — 쿠키 부재/위조/만료/타인 시 우회하지 않고 챌린지로 폴백(보안 강화 방향). 절대 우회를 "기본 허용"하지 않음.
- **NFR-보안-5**. 관리 엔드포인트 JWT 전용·PAT 403(MFA self-service 일관). 단건 취소 404 IDOR 은닉.
- **NFR-테스트-1**. TTL 만료/30일 계산은 주입된 `Clock` 기반 — 테스트가 시각 시뮬레이션으로 결정적 검증(authcontroller-revokesession-timebomb 선례).
- **NFR-보안-6 (우회 경계)**. 신뢰 우회는 `AuthController.completeLogin`(local/LDAP password 로그인) 전용. SSO(SAML/OIDC) success handler는 별도 경로(`completeLogin` 미경유)이며 IdP가 2차 인증 관할 → 우회 의도적 비대상.
- **NFR-격리**. identity-access 단일 BC. cross-BC 없음.

## API 인터페이스 (REST)

### 수정 — `POST /api/v1/auth/login`
- 변경: 1단계 Success 후 `completeLogin`이 `trusted_device` 쿠키를 읽어 우회 판정 추가. 응답 형태 불변(우회 시 기존 `TokenResponse` + Set-Cookie, 비우회 시 기존 `mfa_required`). 쿠키 없으면 완전 기존 동작.

### 수정 — `POST /api/v1/auth/mfa/verify`
- 요청 body에 `trust_device: Boolean = false`(직렬화 키 `trust_device`) 추가.
- 성공 + `trust_device=true` → 응답에 `Set-Cookie: trusted_device=...`(refresh 쿠키와 병존).
- 응답 코드 불변(200/400/401/429).

### 신규 — `GET /api/v1/auth/mfa/trusted-devices`
- 200 `{devices: [{id, label, createdAt, lastUsedAt, expiresAt}]}` / 403 PAT / 401 미인증.

### 신규 — `DELETE /api/v1/auth/mfa/trusted-devices/{id}`
- 204 / 404 not_found(미존재·타인) / 400(비-UUID) / 403 PAT / 401.

### 신규 — `DELETE /api/v1/auth/mfa/trusted-devices`
- 204 / 403 PAT / 401.

> 모든 신규 엔드포인트는 `/api/v1/auth/mfa/**`(인증 요구·기존 self-service 일관) 아래. SecurityConfig permitAll 추가 불요. DELETE는 기존 `DELETE /webauthn/{id}` 선례와 동일 CSRF 처리.

## 데이터 모델 변경

V026 `trusted_devices` (identity-access, init_codegen 미러 불요 — jdbc-only):

```sql
CREATE TABLE trusted_devices (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash   TEXT NOT NULL UNIQUE,            -- SHA-256(rawToken) hex 64자
    label        TEXT,                            -- User-Agent 파생(D6 표시용, nullable)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ
);
CREATE INDEX idx_trusted_devices_user ON trusted_devices(user_id);
-- token_hash 는 UNIQUE 제약이 곧 조회 인덱스
```

## 엣지 케이스

- **EC1 (만료 쿠키)**. `expires_at < now`면 우회 불가 → 챌린지. 만료 행은 조회 술어로 무시(인라인 삭제 안 함).
- **EC2 (타인 쿠키)**. token_hash 일치하나 `user_id`가 인증 주체와 불일치 → 우회 불가 → 챌린지.
- **EC3 (위조/미상 쿠키)**. 어느 token_hash와도 불일치 → 우회 불가 → 챌린지.
- **EC4 (verify 실패 + trust_device=true)**. 2차 요소 검증 실패면 신뢰 등록 안 함(성공 경로에서만 등록).
- **EC5 (재신뢰)**. 같은 브라우저에서 다시 trust_device=true → 새 행 + 새 쿠키(기존 쿠키 덮어씀). 옛 행은 자연 만료(중복 dedupe 안 함 — 단순성).
- **EC6 (MFA 미활성 + 잔여 쿠키)**. MFA 비활성 사용자는 `completeLogin`이 우회 판정 전 `issueTokens(mfaVerified=false)` 분기로 빠짐 → 잔여 쿠키 무시(자연 무해).
- **EC7 (단건 취소 비-UUID)**. Spring UUID 바인딩 실패 400.
- **EC8 (WebAuthn-only + TOTP disable)**. TOTP 비활성화는 WebAuthn 보유 여부와 무관하게 전량 revoke(신뢰 근거 요소 변경 = 재신뢰 요구, 보수적).
- **EC9 (다중 디바이스)**. user별 다행 허용. 각 브라우저가 독립 쿠키. 목록은 전부 표시.
- **EC10 (Clock 경계)**. `expires_at == now`는 만료로 간주(`> now`만 유효). `created_at`/`expires_at` 모두 주입 Clock 기반으로 INSERT(DB `now()` default 혼용 금지 — 테스트 결정성).
- **EC11 (enum 카운트 가드)**. `AuthEventType`에 2종 추가 시 enum 카운트를 검증하는 타 모듈/테스트(예: `AuthEventType` 개수 단언, 매핑 테이블)가 깨질 수 있음 → 전 모듈 grep으로 동반 갱신(enum-add-breaks-crossmodule-count-guard 선례).
- **EC12 (로그아웃 잔존 쿠키)**. `logout`은 `trusted_device` 쿠키를 만료시키지 않음(신뢰 비해제 = 의도). 다음 로그인에 우회 유지. 신뢰 해제는 명시 취소/자동 폐기로만.
- **EC13 (SSO 로그인)**. SSO(SAML/OIDC)는 `completeLogin` 미경유 → 신뢰 쿠키가 있어도 우회 적용 안 됨(IdP MFA 관할, 비대상).
- **EC14 (trust 등록 실패)**. verify 성공 후 trust 등록이 실패(token_hash UNIQUE 극저확률 충돌)해도 정식 세션은 발급(쿠키만 누락, best-effort fail-safe). 세션 발급 500 금지.

## 제약 조건

- identity-access 단일 BC, V026, init_codegen 미러 불요.
- TDD red→green→refactor. `test:` 커밋이 `feat:` 선행.
- `Clock` 주입(시각 의존 로직 — TTL/30일 계산·만료 판정).
- 쿠키 속성은 refresh_token 선례(`buildRefreshCookie`)와 동일 패턴 재사용/확장.
- 비밀값(rawToken) 로깅·평문 저장 금지.
- detekt/ktlint 그린, baseline 라인시프트 주의(import/주석 추가 시).

## 측정 가능한 완료 기준

prod 프로파일 Testcontainers 통합 테스트(ground-truth):
- [ ] S1→S2: 신뢰 등록 후 같은 쿠키 로그인 → 챌린지 없이 200 TokenResponse + `mfa_verified=true`.
- [ ] S3: Clock +31일 → 쿠키 로그인 시 `mfa_required` 챌린지(우회 불가).
- [ ] S4: 단건/전체 취소 후 쿠키 로그인 → 챌린지.
- [ ] EC2: 타인 user 쿠키 → 우회 불가.
- [ ] S5: 비밀번호 변경 → 전량 revoke(목록 empty + 우회 불가).
- [ ] S6: TOTP disable → 전량 revoke.
- [ ] FR-7: 타인 디바이스 단건 취소 → 404.
- [ ] FR-6/7/8: PAT 인증 → 403.

단위 테스트:
- [ ] 토큰 생성·SHA-256 해시 형식(64자 hex).
- [ ] 만료 판정(`isExpired(now)`) Clock 경계(EC10).

빌드/정적분석:
- [ ] identity-access `./gradlew :backend:modules:identity-access:test` 그린(--rerun-tasks).
- [ ] ktlint + detekt 그린.

## Brainstorming Check

✅ 통과 (1회 iteration). 발견·보강한 gap:
- **감사 로그 누락** → FR-11 추가(기존 MFA self-service 모두 감사 emit하는 선례 일관, `TRUSTED_DEVICE_ADDED`/`TRUSTED_DEVICE_REVOKED` 2종 + enum 카운트 가드 동반 갱신 명시 EC11).
- **created_at/expires_at 시각 출처** → 둘 다 주입 Clock 기반 INSERT 명시(EC10, DB now() 혼용 금지).
- **타인 쿠키 우회 차단** → user_id bound 조회로 NFR-보안-3 명시.
- **fail-safe 폴백** → 쿠키 부재/위조/만료 시 우회 안 하고 챌린지(NFR-보안-4).

검토했으나 범위 외(의도적 deferral):
- 만료 행 물리 정리 배치(조회 술어로 무효화 충분, 후속 운영 과제).
- 재신뢰 시 중복 dedupe(자연 만료, 단순성 우선 — EC5).
- 신뢰 우회 전용 audit 이벤트(LOGIN_SUCCESS로 충족, enum 추가 최소화).
