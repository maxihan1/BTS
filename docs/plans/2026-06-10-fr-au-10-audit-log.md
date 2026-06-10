# FR-AU-10 인증 감사 로그 (백엔드 1차)

> slug: fr-au-10-audit-log
> type: auth
> agent: security-engineer
> 생성: 2026-06-10

## Brief

FR-AU-10 — 인증 감사 로그 (identity-access BC, 우선순위 필수, SDD §2.10).
선행 FR(FR-AU-05/08/09)이 "감사 로그 emit은 FR-AU-10 위임"으로 미뤄둔 인증 시스템의 누락 조각.

**이번 PR 범위 — 백엔드 1차 (D1~D5)**. Maxi 결정 (2026-06-10).
- D1. 도메인 — AuthEvent (로그인 성공/실패 · 세션종료 · 권한변경)
- D2. 명세 — 보존 1년 (SDD §2.3.3)
- D3. 데이터 모델 — `auth_audit_logs(event_type, ip, user_agent, ...)`
- D4. 백엔드 — 모든 인증/권한 변경 이벤트 emit
- D5. 백엔드 테스트 — 이벤트 누락 0

**후속 PR**. D6 관리자 감사 로그 조회 UI (designer → frontend-engineer) + D7 E2E (qa-engineer).
최근 인증 FR 분리 패턴(FR-AU-05/08, FR-IS-10)과 동일.

classify-task가 제목 끝 "조회 UI" 키워드로 `ui/frontend-engineer` 오분류 → product 문서 D1~D5 = security-engineer 책임이므로 `auth/security-engineer`로 정정.

## 도메인 정리

- **BC**: identity-access
- **핵심 발견**: FR-AU-10은 그린필드가 아님. 감사 시스템 골격이 이미 존재 (`audit/` 패키지).
  - `AuthEventType` (이벤트 12종 enum), `AuthAuditLog` (데이터 클래스, `userId: UUID`), `AuthAuditLogService` (record/findRecent 인터페이스), `InMemoryAuthAuditLogService` (`@Service` 인메모리 임시 구현)
  - 본 작업 = `InMemory`가 KDoc에 명시한 "후속 PR: `JdbcAuthAuditLogService` + DB 테이블"의 그 후속.
- **영향 엔티티**: AuthAuditLog (기존), 신규 `auth_audit_logs` 테이블, 신규 DB-backed 서비스 구현체.
- **새 용어**: 없음 (도메인 모델 이미 정립, glossary 추가 불필요).
- **emit 갭 (D4 핵심 작업량)**: enum 12종 중 현재 4종만 emit (PAT_USED, PROJECT_MEMBER_ADDED/REMOVED, PROJECT_ROLE_CHANGED). 나머지 8종 갭 (LOGIN_SUCCESS/FAILURE, LOGOUT, LOGOUT_ALL_DEVICES, TOKEN_REFRESHED, SUSPICIOUS_REFRESH_REPLAY, USER_PROVISIONED, LDAP_UNAVAILABLE).
- **SDD 모순 해소**: §19.9("월 단위 파티션") ↔ §5.14("1M 규모 파티셔닝 불필요"). → 단순 테이블 채택 (1K 규모).
- **Maxi 결정 (2026-06-10)**:
  1. D3 — **파티셔닝 없는 단순 테이블 + 인덱스 3종** ((user_id, created_at DESC), (event_type), (created_at)). SDD §19.9 일탈, §5.14 정합.
  2. D4 — **enum 12종 전수 emit 배선** (이벤트 누락 0). FR-AU-05/08이 위임한 비번변경/계정연결 이벤트는 enum 미정의 → 별도 후속.
  3. D2 — **보존 1년 enforcement = `@Scheduled` 1년 경과 행 삭제** (파티션 DROP 대신).
  4. **동기 record() 패턴 유지** (투기적 async 미도입).
- **기존 결정 충돌**: SDD §19.9 파티셔닝 일탈 (ADR로 근거 기록). 그 외 충돌 없음.
- **관련 ADR**: [docs/decisions/2026-06-10-auth-audit-log-persistence.md](../decisions/2026-06-10-auth-audit-log-persistence.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-10-fr-au-10-audit-log.md](../specs/2026-06-10-fr-au-10-audit-log.md)

핵심 3줄 요약.
- 기존 `audit/` 골격(enum 12종 + AuthAuditLog + service)을 `JdbcAuthAuditLogService`(raw JDBC, V021 `auth_audit_logs` 테이블)로 영속화.
- enum 12종 전부 emit 배선 — 현재 4종(PAT/PROJECT_*)만 emit, 8종 갭(LOGIN_*, LOGOUT_*, TOKEN_REFRESHED, REPLAY, PROVISIONED, LDAP_UNAVAILABLE). SSO 성공은 OIDC/SAML 핸들러 3곳까지.
- `AuthAuditLog.userId` nullable화(LOGIN_FAILURE/LDAP_UNAVAILABLE), @Scheduled 1년 보존, 무중복 규칙(EC-11), 신규-only USER_PROVISIONED(xmax).

## Brainstorming Check

✅ 통과 (직접 적대적 검토, gap 3건 발견 후 스펙 반영).
- 갭 A: SSO(OIDC/SAML) LOGIN_SUCCESS 누락 → 성공 핸들러 2곳 추가 배선(EC-12).
- 갭 B: LOGIN_FAILURE↔LDAP_UNAVAILABLE 이중기록 → 무중복 규칙(EC-11).
- 갭 C: 생성자 주입 파급 + JSONB 직렬화 + @EnableScheduling + 타입 실재검증 → 스펙 §9 구현 파급(G-1~G-7).
- office-hours/brainstorming 무거운 대화형 스킬은 완성도 높은 인프라 FR에 부적합(메모리 `bts-spec-office-hours-mismatch`) → 직접 기술 스펙 + 적대적 sanity check로 대체.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
