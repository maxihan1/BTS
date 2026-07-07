# FR-PF-01 환경 설정 (테마/언어/날짜포맷) — 스펙

> 날짜: 2026-07-07 | slug: fr-pf-01-user-preferences
> 물리 BC: identity-access | 논리 BC: personalization
> 정본: docs/plan/product/personalization.md §3.1
> 도메인: docs/plans/2026-07-07-fr-pf-01-user-preferences.md `## 도메인 정리`

## 스코프 결정 (Maxi 확정 2026-07-07)

| 항목 | 결정 | 근거 |
|---|---|---|
| **theme** | light / dark / system 3종. `.dark` 클래스 전역 토글 + 저장. 완전 동작 | CSS 다크 변수(`index.css .dark`)·`@custom-variant dark` 이미 존재. 토글 로직만 신설 |
| **locale (언어)** | user_preferences에 **저장만** + `<html lang>` 반영. UI 문자열 번역(i18next)은 **후속 i18n 에픽 보류** | C안. 앱은 현재 100% 한국어 단일. 반쪽 번역 방지. i18next 신규 도입 없음 |
| **date_format** | 프리셋 + 공통 preference-aware 포맷터로 **앱 전체 즉시 적용**(중앙 유틸 2 + 인라인 ~20 이관) | 네이티브 Intl 사용(신규 의존성 0). Maxi "전체 즉시 적용" 선택 |
| **날짜 라이브러리** | **네이티브 Intl.DateTimeFormat** (date-fns-tz 미도입) | FR-PR-01 타임존 선례와 일관. 번들 증가 0 |

**신규 외부 의존성: 없음** (i18next·date-fns-tz 모두 미도입).

## 사용자 시나리오 (Given-When-Then)

- **S1 테마 전환**. Given 로그인 사용자가 설정 화면. When 테마를 '다크'로 선택. Then 즉시 앱 전체가 다크로 바뀌고(< 200ms) 서버에 저장되어, 재로그인·새 탭에서도 다크 유지.
- **S2 시스템 테마**. Given 테마='시스템'. When OS 다크모드 토글. Then 앱이 OS를 따라 즉시 전환(matchMedia 구독).
- **S3 FOUC 방지**. Given 다크 사용자가 페이지 새로고침. When 앱 부팅. Then 프리퍼런스 fetch 완료 전에도 다크가 즉시 적용(localStorage 프리하이드레이션) — 흰 화면 깜빡임 없음.
- **S4 날짜 포맷**. Given date_format='us'(MM/DD/YYYY). When 이슈/보드/변경이력 등 날짜 표시 화면. Then 모든 날짜가 선택 포맷으로 렌더. 설정 화면에 라이브 프리뷰.
- **S5 언어 저장**. Given locale='en' 선택. When 저장. Then user_preferences에 저장 + `<html lang="en">`. (UI 문자열은 이번 범위 밖 — 한국어 유지, 후속 i18n에서 번역).
- **S6 기본값**. Given preferences 행 없는 신규 사용자. When GET. Then 기본값(theme=system, locale=ko, date_format=iso) 반환. 첫 변경 시 행 생성(lazy upsert).

## 기능 요구사항 (FR)

- **F1**. `user_preferences(user_id PK/FK, theme, locale, date_format, created_at, updated_at)` 테이블 신설(V031). user_id = `users.id ON DELETE CASCADE`.
- **F2**. `GET /api/v1/users/me/preferences` — 현재 유효 설정 반환. 행 없으면 기본값. JWT 세션 인증(프로필 엔드포인트와 동일).
- **F3**. `PATCH /api/v1/users/me/preferences` — 부분 수정(제공된 필드만). upsert(INSERT ... ON CONFLICT (user_id) DO UPDATE). 유효 enum 검증 실패 시 400. CSRF 보호. 갱신된 유효 설정 반환.
- **F4**. **선행 stub 이관**. `POST /api/v1/users/me/preferences`(본문 미사용, CSRF 데모 전용) **제거** → CSRF 데모 테스트 3곳(`PreferencesControllerCsrfTest`, `PatAndConcurrencyIntegrationTest` CSRF-B)을 `PATCH` 대상으로 이관(PATCH도 상태변경 → CSRF 보호 대상, 데모 의도 보존).
- **F5**. 프론트 부팅 시 preferences 로드 → (a) theme를 `<html>` `.dark` 클래스로 적용(system이면 matchMedia), (b) 공통 date 포맷터의 현재 포맷 설정, (c) `<html lang>` 설정.
- **F6**. `/settings/preferences` 라우트 + 페이지. theme 셀렉터(light/dark/system), date_format 셀렉터(라이브 프리뷰), locale 셀렉터(저장; "UI 번역 후속" 안내 문구). 변경 시 PATCH 저장.
- **F7**. 공통 preference-aware 날짜 포맷터(`useDateFormat` 훅 + 포맷 함수) 신설. 기존 중앙 유틸(`lib/datetime.ts`, `lib/date-format.ts`) + 인라인 ~20 렌더 사이트 중 **절대 날짜 표시**를 이 포맷터로 이관. **상대시간 표시("N일 전" 등)는 이관 대상 아님**(프리셋은 절대 날짜 패턴만 지배) — 이관 시 상대→절대 뭉갬 금지(G2).
- **F8**. theme 선택을 localStorage에도 미러(부팅 FOUC 방지용 프리하이드레이션). 서버가 단일 진실 출처, localStorage는 부팅 힌트.

## date_format 프리셋 (Intl 매핑)

| 키 | 날짜 예시(2026-07-07) | Intl 옵션 |
|---|---|---|
| `iso` (기본) | 2026-07-07 | year numeric, month 2-digit, day 2-digit → YYYY-MM-DD 정규화 |
| `kr` | 2026. 07. 07. | ko-KR 스타일 |
| `us` | 07/07/2026 | MM/DD/YYYY |
| `eu` | 07/07/2026 | DD/MM/YYYY |

- 날짜+시각 표시 = 날짜 프리셋 + " " + `HH:mm`(24h). 시각 부분은 프리셋 무관 고정.
- 타임존은 기존대로 `Asia/Seoul` 고정(FR-PF-01 범위에 타임존 설정 미포함 — FR-PR-01 프로필 타임존이 별도).
- 기본 `iso`는 현재 `date-format.ts` 출력(YYYY-MM-DD)과 동일. `datetime.ts`의 ko-KR 롱폼 소비처는 프리셋 체계로 정규화됨(회귀 반경 감수 — Maxi 확정).

## 지원 값 (enum 검증)

- theme: `light` | `dark` | `system` (기본 `system`)
- locale: `ko` | `en` (기본 `ko`)
- date_format: `iso` | `kr` | `us` | `eu` (기본 `iso`)

허용 외 값 → 400 (백엔드 검증 + 프론트 Zod).

## API 인터페이스 (REST)

```
GET   /api/v1/users/me/preferences
  200 { "theme": "system", "locale": "ko", "dateFormat": "iso" }

PATCH /api/v1/users/me/preferences   (CSRF 필요)
  body { "theme"?: "...", "locale"?: "...", "dateFormat"?: "..." }
  200  { "theme": "dark", "locale": "ko", "dateFormat": "us" }   // 갱신된 유효값
  400  잘못된 enum 값
  401  미인증
  403  CSRF 토큰 없음/불일치
```

## 데이터 모델 변경

```sql
-- V031__user_preferences.sql
CREATE TABLE user_preferences (
    user_id      UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    theme        VARCHAR(16) NOT NULL DEFAULT 'system',
    locale       VARCHAR(16) NOT NULL DEFAULT 'ko',
    date_format  VARCHAR(16) NOT NULL DEFAULT 'iso',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- 행은 사용자당 lazy 생성(첫 PATCH 시). GET은 행 부재 시 기본값 반환.
- ⚠️ SchemaMigrationTest 카운트 가드가 있으면 V031 반영(메모리 `fr-pm-permission-seed-migration-test-coupling` 계열).

## 엣지 케이스

- **E1** preferences 행 없음 → GET 기본값. PATCH는 upsert로 첫 행 생성.
- **E2** PATCH 빈 본문 `{}` → 변경 없음, 현재값 반환(멱등).
- **E3** 잘못된 enum(예: theme='blue') → 400, 저장 안 함.
- **E4** theme=system + OS 전환 → 앱 즉시 반영(matchMedia change 구독, theme≠system이면 구독 해제).
- **E5** FOUC — 부팅 시 preferences fetch 지연 → localStorage 미러로 즉시 적용, fetch 완료 후 서버값으로 재조정.
- **E6** 로그아웃 상태 → 서버 preferences 없음. localStorage/시스템 기본으로 표시. 로그인 후 서버값 적용.
- **E7** date_format 이관 누락 사이트 → 회귀. 이관 대상 전수 grep(`toLocaleDateString`/`toLocaleString`/`Intl.DateTimeFormat`)로 커버 확인.
- **E8** 동시 PATCH(멀티 탭) → 마지막 쓰기 승리(upsert). OCC 불필요(단일 사용자 자기 설정).

## 제약 조건

- 신규 외부 의존성 금지(i18next/date-fns-tz 미도입) — 네이티브 Intl + CSS 변수만.
- BC 격리 — identity-access 단일 모듈. cross-BC 호출 없음.
- 완제품 품질 — 절대 규칙(DEVELOPMENT.md §1) 준수. PoC 표현이 있던 기존 stub 주석은 제거.
- date_format 이관은 표시 로직만 — 저장/전송 값은 계속 ISO(서버 계약 불변).

## 비기능 요구사항 (NFR)

- **N1** 테마 적용 체감 < 200ms(product 문서 측정표). localStorage 프리하이드레이션으로 FOUC 0.
- **N2** date_format 이관이 기존 날짜 단위 테스트를 깨지 않게 — 이관 사이트의 테스트 동반 갱신.
- **N3** 기본 프리셋(iso)은 기존 `date-format.ts` 출력과 동일 → 미변경 사용자 회귀 최소.

## 측정 가능한 완료 기준

- [ ] V031 `user_preferences` 마이그레이션 + jOOQ codegen 반영
- [ ] GET/PATCH 백엔드 + 단위/통합 테스트(기본값·upsert·enum 400·CSRF·JWT-only)
- [ ] POST stub 제거 + CSRF 데모 테스트 3곳 PATCH 이관(green)
- [ ] 프론트 `/settings/preferences` 페이지 + 테마 토글(light/dark/system 라이브) + date_format 라이브 프리뷰 + locale 저장
- [ ] 공통 date 포맷터 + 앱 전체 날짜 사이트 이관(전수 grep 커버)
- [ ] FOUC 방지 프리하이드레이션
- [ ] E2E(테마 전환 지속·date_format 반영·설정 저장)
- [ ] `pnpm verify` + `./gradlew test` green

## Brainstorming Check ✅

집중 gap 분석(대화형 재작성 아님) 결과 4건 발견 → 반영.

- **G1 whoami 통합 (plan 결정 사항)**. FR-PR-02/03이 배지를 `whoami`에 실은 선례. preferences를 whoami에 실으면 세션 부팅 1회 왕복으로 적용(FOUC 창 축소). 별도 GET과 중복 우려. **plan 단계 결정**: (a) whoami에 theme/locale/dateFormat 추가 + 설정 페이지는 GET/PATCH, 또는 (b) 부팅도 GET 사용. 권장 = (a) — 부팅 왕복 절감 + FR-PR view-layer 패턴 일관.
- **G2 상대시간 vs 절대날짜**. 이관 대상은 절대 날짜만. 상대시간("N일 전")은 프리셋 무관 → F7에 명시 반영.
- **G3 설정 내비**. `/settings/*` 탭 레이아웃에 "환경 설정" 항목 추가 → F6 범위에 포함.
- **G4 PAT 접근 정책**. 기존 stub이 `PatAndConcurrencyIntegrationTest`에서 참조됨. 이관 후 PATCH preferences의 PAT 정책 재확인 필요 → 프로필 엔드포인트와 동일(JWT 세션)로 맞춤. 기존 테스트의 PAT/CSRF 기대 전수 확인 후 이관(F4).
