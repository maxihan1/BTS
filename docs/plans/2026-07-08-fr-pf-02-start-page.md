# FR-PF-02 기본 뷰/시작 페이지

> slug: fr-pf-02-start-page
> type: feature
> agent: backend-engineer (task별 재배정 — db/backend/frontend/qa)
> primary_bc: identity-access (논리 BC personalization)
> 생성: 2026-07-08

## Brief

**원문**. fr-pf-02 진행해줘

**FR-PF-02 (personalization §3.2)** — 로그인 후 사용자가 지정한 시작 페이지로 자동 라우팅.
- D1 도메인 / D2 명세(로그인 후 리다이렉트) / D3 `user_preferences.start_page` 컬럼 / D4 백엔드(기존 preferences API 활용) / D5 백엔드 테스트 / D6 프론트 UI(설정 페이지 + 로그인 후 자동 라우팅) / D7 E2E
- **선행**. §3.1 FR-PF-01(환경설정, V031) 완료 → 같은 user_preferences 테이블 확장
- **동형 참조**. FR-PF-01 (PR #245): whoami view-layer 노출, enum 검증 로컬 @ExceptionHandler, JWT-only

**classify**. 원판정 type=ui → product D1~D7 근거로 feature 교정.

## 도메인 정리 (← /bts-domain 채움)

- **BC**: identity-access (논리 personalization §3.2)
- **영향 엔티티**: `UserPreferences` 기존 확장 (`startPage` 필드 추가). **신규 엔티티·테이블 없음** — `user_preferences`(V031)에 컬럼 하나 ALTER ADD.
- **새 용어**: "시작 페이지(start page)" — 로그인 직후 자동 이동할 목적지. 논리 키로 표현. (glossary 추가 후보, Maxi 승인 대기)
- **저장 방식 (ADR D1)**: 경로 문자열 아닌 **논리 키 화이트리스트**. 오픈 리다이렉트·리네이밍 취약성 차단.
  - 허용 키: `dashboards`(기본) · `my_issues` · `issues` · `inbox`
  - 키→경로: `dashboards→/dashboards`, `my_issues→/issues?assignee=me`, `issues→/issues`, `inbox→/inbox`
- **검증 (ADR D2)**: 백엔드 `UserPreferences.START_PAGES` + 기존 `validateAllowed`/`PreferencesValidationException` 재사용. 신규 예외 0.
- **로그인 라우팅 (ADR D3)**: whoami view-layer `startPage` 노출 → `routes/login.tsx handleSuccess`에서 `user.startPage` 읽어 navigate. `routeGuard.ts redirectIfAuth` fallback도 일관화. 백엔드 리다이렉트 API 미신설.
- **기존 결정 충돌**: 없음. FR-PF-01 ADR 패턴 전면 재사용.
- **관련 ADR**: [docs/decisions/2026-07-08-fr-pf-02-start-page.md](../decisions/2026-07-08-fr-pf-02-start-page.md) (생성됨)
- **동형 참조**: FR-PF-01 (PR #245) — theme/locale/dateFormat과 동일 5계층 확장 패턴.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
