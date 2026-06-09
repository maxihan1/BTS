# FR-AU-08 계정 통합 (Account Linking)

> slug: fr-au-08-account-linking
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-09

## Brief

사용자 원문. "FR-AU-08 계정 통합 (Account Linking) 진행해줘"

classify 결과 (보정 적용).
- type: auth (classify 원판정 backend → 보정)
- agent: security-engineer (classify 원판정 backend-engineer → 보정)
- primary_bc: identity-access
- 보정 근거: identity-access BC는 CLAUDE.md상 security-engineer 담당. FR-AU-08은 인증 수단 연결을 다루는 auth 작업.

## 도메인 정리

- **BC**. identity-access (담당 security-engineer)
- **영향 엔티티**. `User`(기존), `user_external_accounts`/`ExternalAccount`(기존, V002), `StoredPasswordCredential`(기존 — 마지막 수단 카운트 시 존재 확인만). **신규 엔티티/스키마 없음**.
- **신규 코드 표면**. self-service 계정 연결 API(목록/연결/해제) + 연결 모드 분기(JIT 신규 user 생성과 분리). 기존 `AutoProvisionService`·SSO 성공 핸들러 일반 로그인 경로 무변경.
- **새 용어(glossary 갱신 대기, 머지 시 동기화)**. "계정 연결(Account Linking)", "재인증/step-up(민감 동작 전 자격증명 재확인)", "연결 해제(Unlink)".
- **현재 상태**. 명시적 연결 0건. 외부 로그인은 JIT 자동 프로비저닝(`ON CONFLICT username`)만 존재.
- **SDD 19.4 deviation**. SDD가 스케치한 `UserIdentity`(Long id + email/verifiedAt)는 stale. 실재는 `user_external_accounts`(UUID, email/verified_at 컬럼 없음). 구현은 실재 스키마 따름.

### 확정 결정 (Maxi 2026-06-09)

| # | 결정 | 값 |
|---|---|---|
| D1 | 연결 방식 | **명시적 수동 연결 전용** (이메일 자동 연결 미도입 — 계정 탈취 차단) |
| D2 | 기능 범위 | **연결 + 해제 + 목록 전체** |
| D3 | 연결 대상 | **외부 Provider(LDAP/SAML/OIDC) 한정**. LOCAL 비밀번호 추가/제거는 범위 밖 |
| D4 | 재인증 | **강제**(step-up). 구체 메커니즘은 spec 결정 |
| D5 | 충돌 처리 | 타계정 선점 **거부** / 동일계정 멱등 **no-op** / 마지막 수단 해제 **거부** |

- **기존 결정 충돌**. 없음. `2026-05-20-user-external-accounts-schema`(RESTRICT/CASCADE) 호환, 스키마 변경 없음.
- **관련 ADR**. [docs/decisions/2026-06-09-account-linking-policy.md](../decisions/2026-06-09-account-linking-policy.md) (생성됨)
- **spec 단계 미결**. 재인증 메커니즘(비밀번호 재입력 vs SSO 재수행 vs 세션 freshness 임계), 연결 모드 진입 방식(SSO/LDAP 성공 핸들러에 linking-intent 전달 경로), PAT 취급.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
