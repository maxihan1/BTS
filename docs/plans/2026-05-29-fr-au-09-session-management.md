# FR-AU-09 마무리 — 내 활성 세션 관리 (목록 조회 + 강제 종료)

> slug: fr-au-09-session-management
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-05-29

## Brief

**사용자 원문**.
> FR-AU-09 작업 마무리. 진척 확인 결과 D4(백엔드)·D6(프론트 UI)·D7(E2E)가 "내 활성 세션 관리" 기능으로 통째로 비어 있음.

**요구사항**.
- 백엔드. 활성 세션 목록 조회 API (`GET /api/v1/auth/sessions`) + 특정 세션 강제 종료 API (`DELETE /api/v1/auth/sessions/{sid}`).
- 프론트. 활성 세션 목록 화면 + 각 세션 강제 로그아웃 버튼.
- E2E. 목록 조회 → 강제 종료 → 해당 토큰 차단 확인 시나리오.

**선행 컨텍스트 (진척 확인, 2026-05-29)**.
- `SessionService.revoke(sid)` / `revokeAllOfUser(userId)` / `lookup(sid)` / `markLastSeen(sid)` 이미 구현됨 (`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/SessionService.kt`).
- `AuthController` (`/api/v1/auth`) 에 login / logout / refresh 만 존재. 세션 목록·강제종료 엔드포인트 부재.
- `SidRevokeJwtConverter` + `SessionService.lookup` 기반 revoke 검증 필터 이미 동작 (revoke 시 해당 sid JWT 차단). EC-29 Caffeine 5s TTL 캐시.
- 프론트 `apps/web/src/auth/` 에 login/logout mutation + authStore 만 존재. 세션 목록 UI 부재.
- `apps/web/e2e/` 에 login 계열 E2E 만 존재. 세션관리 E2E 부재.

**classify 보정**. classify-task 가 'E2E' 키워드로 qa 오분류 → Maxi 확인 후 auth / security-engineer / identity-access 로 확정 (2026-05-29).

**제약**.
- DEVELOPMENT.md §1.17 — 토큰 localStorage 금지 (sessionStorage 강제).
- 한 PR = 한 BC (identity-access). 단, frontend + same BC view layer 변경은 한 PR 내 허용 (learnings 2026-05-22 옵션 C 패턴).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
