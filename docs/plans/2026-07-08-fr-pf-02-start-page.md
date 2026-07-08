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

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
