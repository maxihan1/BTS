# ADR — FR-PF-01 환경 설정: i18n 도입 보류 + 네이티브 Intl + 신규 의존성 0

> 날짜: 2026-07-07
> 상태: 결정됨 (Maxi 확정)
> 관련 FR: FR-PF-01 (환경 설정 — 테마/언어/날짜포맷)
> 관련 slug: fr-pf-01-user-preferences | PR #245
> 배치 선례: [2026-07-05-fr-pr-01-user-profile-placement](2026-07-05-fr-pr-01-user-profile-placement.md) (논리 BC personalization → 물리 identity-access)

## 맥락

product 문서(`docs/plan/product/personalization.md §3.1`)의 D6는 "i18next + theme 토글 + date-fns-tz"를 명시했다. 그러나 세 요소의 실제 도입 범위가 FR 하나의 크기를 크게 좌우해 스코프 확정이 필요했다.

- 앱은 현재 **100% 한국어 하드코딩**(i18next 미설치). 전면 i18n 도입은 수백 개 문자열을 번역 키로 추출하는 대공사 — FR 하나가 아니라 별도 에픽 규모.
- 날짜 라이브러리(date-fns-tz)는 미설치. FR-PR-01 타임존이 이미 **네이티브 Intl**로 처리된 선례가 있다.
- 테마는 `index.css`에 다크 변수(`.dark` + `@custom-variant dark`)가 이미 존재하나 토글 코드만 없었다.

## 결정

### D1. 언어(i18n) = 저장만, i18next 도입 보류 (C안)

`user_preferences.locale`에 사용자 언어를 **저장**하고 `<html lang>`에만 반영한다. 실제 UI 문자열 번역(i18next 프레임워크 도입 + 문자열 추출)은 **별도 i18n 에픽으로 보류**한다.

**근거.** 앱이 한국어 단일이라 부분 번역은 "반쪽 상태"를 만든다(완제품 기준 위배). locale을 검증된 값(`ko`|`en`)으로 저장해 두면 후속 i18n 에픽이 바로 소비할 수 있다. i18next 신규 의존성을 지금 추가하지 않는다(절대 규칙 §1.17).

### D2. 테마 = 완전 동작 (light/dark/system)

`<html>`에 `.dark` 클래스를 토글해 앱 전체에 즉시 적용한다. `theme=system`은 `prefers-color-scheme` matchMedia를 추종한다. FOUC(첫 페인트 전 깜빡임)는 `index.html` 인라인 스크립트가 **비민감 `bts.theme` localStorage 키**를 읽어 방지한다.

**근거.** CSS 인프라가 이미 있어 토글+저장만 추가하면 전역 완성. `bts.theme`는 테마 enum(비민감)만 담으므로 규칙 §1.18(토큰 localStorage 금지)·authStore의 `bts.auth` sessionStorage 정책과 무관하다(next-themes 표준 패턴).

### D3. 날짜 포맷 = 네이티브 Intl, 앱 전면 적용

`date_format`(`iso`|`kr`|`us`|`eu`) 프리셋을 **네이티브 `Intl.DateTimeFormat`** 공통 포맷터(`formatDateByPreset`)로 구현하고, 중앙 유틸 2개 + 인라인 ~15개 절대 날짜 표시 사이트를 전면 이관한다. 상대시간("N일 전")은 대상 밖. 저장/전송 값은 계속 ISO(서버 계약 불변).

**근거.** Intl로 충분해 date-fns-tz 신규 의존성이 불필요(번들 증가 0, FR-PR-01 선례 일관). "전면 적용"으로 반쪽 상태를 피한다(회귀 반경 감수 — Maxi 확정).

### D4. 모듈 = identity-access (FR-PR-01 ADR 재사용)

UserPreferences는 User와 1:1이므로 identity-access에 둔다. `PreferencesController` POST stub(CSRF 데모 전용)을 제거하고 `GET/PATCH`로 이관하며, CSRF 데모 테스트는 PATCH 대상으로 옮겨 의도를 보존한다.

## 영향

- 신규 마이그레이션 `V031__user_preferences.sql` (identity-access, JdbcTemplate 전용 → jOOQ codegen 불요).
- 신규 엔드포인트 `GET/PATCH /api/v1/users/me/preferences` + whoami에 theme/locale/dateFormat view-layer 필드(백엔드 non-null 기본값, 프론트 Zod optional).
- **신규 외부 의존성 0** (i18next·date-fns-tz 미도입).
- 후속.