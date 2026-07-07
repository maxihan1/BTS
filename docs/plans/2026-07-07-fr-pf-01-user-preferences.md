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

## 도메인 정리

- **물리 BC**. identity-access | **논리 BC**. personalization ("논리 BC ≠ 물리 모듈" 패턴, FR-PR-01 ADR·FR-UX-01 선례로 확립).
- **신규 엔티티**. `UserPreferences` — User와 1:1 (FR-PR-01 `UserProfile`, FR-PR-02 `UserStatus`, FR-PR-03 `UserOoo`와 동형). PK/FK = `user_id → users.id (ON DELETE CASCADE)`.
- **새 용어**. "환경 설정 (User Preferences)" — 사용자별 UI 표시 설정. 필드. `theme`(라이트/다크/시스템), `locale`(언어), `date_format`(날짜 표기).
- **기존 결정 충돌**. 없음. FR-PR-01 ADR(`docs/decisions/2026-07-05-fr-pr-01-user-profile-placement.md`)의 배치 패턴을 그대로 확장.
- **선행 stub 이관 (핵심)**. `PreferencesController.kt`에 `POST /api/v1/users/me/preferences` stub이 이미 존재 — 본문 미사용, 유일한 목적은 CSRF 토큰 검증 흐름 시연(T5). 의존 테스트 3곳.
  - `web/PreferencesControllerCsrfTest.kt` (POST 토큰 유/무 → 200/403)
  - `integration/PatAndConcurrencyIntegrationTest.kt` CSRF-B (토큰 없는 POST → 403)
  - `web/PasswordControllerMvcTest.kt` (참조)
  - **결정**. stub POST → 실제 `GET`(조회) + `PATCH`(수정)로 이관. CSRF 데모는 상태변경 메서드면 되므로 `PATCH` 대상으로 옮겨 의도 보존. spec에서 이관 방식 확정.
- **마이그레이션 번호**. identity-access 마지막 = V030. 신규 = **V031** (`user_preferences`). ⚠️ 동시 세션(fr-sl-01)은 slack-integration 모듈이라 V번호 네임스페이스 분리 — 머지 직전 재확인.
- **관련 ADR**. FR-PR-01 배치 ADR 재사용. FR-PF-01 신규 결정(기본값 정책 · POST→PATCH 이관 · i18n 범위)은 spec 확정 후 ADR 후보.
- **spec으로 미룬 갈림길**. (1) 기본값 정책(theme=system? locale=ko? date_format=?), (2) **i18next 범위** — 앱 전체 문자열 전수 번역 vs 인프라+설정 저장만(하드코딩 문자열은 후속), (3) locale 지원 언어 목록.

## 스펙

전체 스펙. [docs/specs/2026-07-07-fr-pf-01-user-preferences.md](../specs/2026-07-07-fr-pf-01-user-preferences.md)

**스코프 (Maxi 확정 2026-07-07)**.
- theme (light/dark/system) — `.dark` 전역 토글 + 저장. 완전 동작.
- locale — 저장 + `<html lang>`만. UI 번역(i18next)은 후속 에픽 보류(C안).
- date_format — 프리셋(iso/kr/us/eu) + 공통 포맷터로 앱 전체 즉시 적용(중앙 유틸 2 + 인라인 ~20 이관). 네이티브 Intl(신규 의존성 0).

**핵심 3줄**.
- 백엔드. `user_preferences`(V031) + `GET/PATCH /api/v1/users/me/preferences`. POST stub 제거 + CSRF 데모 3테스트 PATCH 이관.
- 프론트. `/settings/preferences` + 테마 토글(FOUC 방지 localStorage 프리하이드레이션) + date_format 라이브 프리뷰 + locale 저장.
- date_format 전면 이관 — 절대 날짜만, 상대시간 제외.

## Brainstorming Check

✅ 통과 (집중 gap 분석, 4건 발견·반영). G1 whoami 통합은 plan 결정 사항(권장: whoami에 preferences 추가). G2 상대시간 제외·G3 설정 내비·G4 PAT 정책은 스펙 반영.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
