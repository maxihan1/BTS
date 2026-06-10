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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
