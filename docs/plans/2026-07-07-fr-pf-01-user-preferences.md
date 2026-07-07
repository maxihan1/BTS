# FR-PF-01 환경 설정 (테마/언어/날짜포맷)

> slug: fr-pf-01-user-preferences
> type: api (full-stack feature)
> agent: backend-engineer (+ db / frontend / qa)
> primary_bc: identity-access (논리 BC personalization)
> 생성: 2026-07-07

## Brief

FR-PF-01 — 사용자별 환경 설정. 테마(라이트/다크) · 언어(locale) · 날짜 포맷.
- D1 도메인. UserPreferences
- D2 명세. 기본값 + 사용자 override
- D3 데이터 모델. user_preferences(theme, locale, date_format, ...)
- D4 백엔드. GET/PATCH /api/v1/users/me/preferences
- D5 백엔드 테스트
- D6 프론트. i18next + theme 토글 + date-fns-tz
- D7 E2E

정본. docs/plan/product/personalization.md §3.1

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
