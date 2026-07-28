<!-- personalization BC — 프로필/설정/캘린더/퀵필터/Slash/단축키/UI개편 13 FR -->

# personalization BC

**소속 FR**. 13개 (PR 4 + PF 3 + CA 2 + UX-01,04,05,06 4).
**책임**. 사용자 프로필 / 환경 설정 / 캘린더 / UX 편의 (퀵 필터, Slash, 단축키).
**SDD 참조**. 20장 (개인화).
**다른 BC와의 경계**. identity-access의 user 식별 사용. issue-tracking의 할당/마감일 조회 (캘린더). agile-planning의 보드 필터 (퀵 필터). **import 금지 — 이벤트/API만**.

## §0 진입 조건

- [x] identity-access §2.1 (FR-AU-01 Provider) 완료 (사용자 ID 안정) — 2026-07-27 실측: `identity-access.md` §2.1 D1~D7 전량 `[x]` (D6/D7은 FR-AU-02 PR #11·#22 흡수)
- [x] issue-tracking §6.1 (FR-PL-01 일정) 완료 (캘린더 데이터) — 2026-07-27 실측: FR-PL-01 D1~D7 전량 `[x]`. 단 소재는 issue-tracking 이 아니라 `agile-planning.md` §6.1 (fr-index 기준, 본문 참조 BC 표기가 stale)
- [x] agile-planning §2 (FR-BD 보드) 완료 (퀵 필터 컨텍스트) — 2026-07-27 실측: `agile-planning.md` §2 의 FR-BD-01/02/03 D1~D7 21줄 전량 `[x]` (미완 0)

## §1 기술 검증

이 BC 자체의 PoC는 없음.

## §2 프로필 (FR-PR, 4개)

### §2.1 FR-PR-01 — 사용자 프로필 (이름/아바타/타임존/부서)

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `personal/profile`

- [x] D1. 도메인 — UserProfile (책임. backend-engineer)
- [x] D2. 명세 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `user_profiles(user_id, avatar_object_key, timezone, department)` — display_name은 users 유지 (ADR 2026-07-05, avatar_url→avatar_object_key 파생) (책임. db-engineer)
- [x] D4. 백엔드 — `GET/PATCH /api/v1/users/me/profile` + 아바타 3종. MinIO 아바타 (책임. backend-engineer + security-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — 프로필 페이지 + 아바타 업로드 (PR #239) (책임. designer → frontend-engineer)
- [x] D7. E2E (PR #239) (책임. qa-engineer)

### §2.2 FR-PR-02 — 상태 메시지 (이모지 + 텍스트)

**우선순위**. 높음 | **선행**. §2.1 | **Plan slug**. `personal/status-msg`

- [x] D1. 도메인 — UserStatus (책임. backend-engineer)
- [x] D2. 명세 — TTL 옵션 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `user_statuses(user_id, emoji, text, expires_at)` (책임. db-engineer)
- [x] D4. 백엔드 — `PATCH /api/v1/users/me/status` (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — 아바타 옆 상태 + 설정 모달 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §2.3 FR-PR-03 — 부재중 (Out of Office)

**우선순위**. 높음 | **선행**. §2.2 | **Plan slug**. `personal/ooo`

- [x] D1. 도메인 — OutOfOffice (PR #241) (책임. backend-engineer)
- [x] D2. 명세 — 기간 + 대체 담당자 + 자동 응답(표시용 안내 메시지) (PR #241) (책임. backend-engineer)
- [x] D3. 데이터 모델 — `user_ooo(user_id, starts_at, ends_at, delegate_user_id, message)` — V029, ADR 2026-07-07 (책임. db-engineer)
- [x] D4. 백엔드 — `GET/PATCH/DELETE /api/v1/users/me/ooo` (product 원안 `POST` 표기 → 관례 수렴). **자동 위임 = 대리자 지정·저장·표시까지(A안, 단일 BC identity-access); 실제 이슈 assignee 자동 전환은 후속 FR로 분리**(Maxi 확정, ADR 2026-07-07 D3) (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (PR #241) (책임. backend-engineer)
- [x] D6. 프론트 UI — OOO 설정 모달(기간+대리자검색+메시지) + Header 부재중 표시 (PR #241) (책임. designer → frontend-engineer)
- [x] D7. E2E (PR #241) (책임. qa-engineer)

### §2.4 FR-PR-04 — LDAP 동기화 필드 vs 사용자 편집 분리

**우선순위**. 필수 | **선행**. §2.1, identity-access §2.2 (LDAP) | **Plan slug**. `personal/ldap-sync-separation`

**아키텍처**. 실제 LDAP 동기화 ∩ 사용자 편집 필드는 `display_name` 하나뿐(email 편집 불가, timezone/department/avatar는 LDAP 미접촉) → **`users.display_name_source` 단일 enum 컬럼**(`LDAP`|`USER`)으로 출처 추적. ADR [decisions/2026-07-07-fr-pr-04-ldap-field-source.md](../../decisions/2026-07-07-fr-pr-04-ldap-field-source.md). 편집 시 source=USER 전환→LDAP 재로그인 CASE 게이트가 보존, "LDAP 값으로 재설정"으로 되돌림(지연 동기화).

- [x] D1. 도메인 — display_name 출처(source: LDAP vs USER). users.display_name_source 컬럼 (책임. backend-engineer)
- [x] D2. 명세 — LDAP 동기화 필드(display_name) vs 사용자 편집 분리 + 재동기화 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `users.display_name_source` 단일 컬럼(V030, ADR 2026-07-07 D1 — display_name이 users에 있어 원안 "user_profiles 컬럼별 source"를 정정) (책임. db-engineer)
- [x] D4. 백엔드 — LDAP 재로그인 UPSERT CASE 게이트(source=USER면 보존) + resync 엔드포인트 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — 충돌 시나리오(S2 보존/S3 동기화) + resync 200/409 (책임. backend-engineer)
- [x] D6. 프론트 UI — display_name "디렉터리에서 동기화됨" 배지 + "디렉터리 값으로 재설정"(provider-중립 문구, 로컬 사용자 미노출) (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

## §3 환경 설정 (FR-PF, 3개)

### §3.1 FR-PF-01 — 환경 설정 (테마/언어/날짜포맷)

**우선순위**. 필수 | **선행**. §0 | **Plan slug**. `personal/preferences`

- [x] D1. 도메인 — UserPreferences (책임. backend-engineer)
- [x] D2. 명세 — 기본값(system/ko/iso) + 사용자 override (책임. backend-engineer)
- [x] D3. 데이터 모델 — `user_preferences(theme, locale, date_format, ...)` V031 (책임. db-engineer)
- [x] D4. 백엔드 — `GET/PATCH /api/v1/users/me/preferences` + whoami view-layer (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — theme 토글(.dark, FOUC 프리하이드레이션) + date_format 전면 이관(**네이티브 Intl**) + locale 저장. **UI 번역(i18next)은 후속 i18n 에픽 보류**(C안, 신규 의존성 0) — spec 2026-07-07 / ADR `2026-07-07-fr-pf-01-preferences-i18n-defer` (책임. frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §3.2 FR-PF-02 — 기본 뷰/시작 페이지

**우선순위**. 높음 | **선행**. §3.1 | **Plan slug**. `personal/start-page`

- [x] D1. 도메인 (책임. backend-engineer)
- [x] D2. 명세 — 로그인 후 리다이렉트 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `user_preferences.start_page` (책임. db-engineer)
- [x] D4. 백엔드 — (활용) (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — 설정 페이지 + 로그인 후 자동 라우팅 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §3.3 FR-PF-03 — 단축키 커스터마이즈

**우선순위**. 중간 | **선행**. §4.3 (FR-UX-05) | **Plan slug**. `personal/keymap-customize`

- [x] D1. 도메인 — UserKeymap(action→key_combo override), KeymapAction 5종 (책임. backend-engineer)
- [x] D2. 명세 — 충돌 검출 6종(화이트리스트·형식·빈값·완전중복·leader접두·dead `g g`) (책임. backend-engineer)
- [x] D3. 데이터 모델 — `user_keymap(user_id, action, key_combo)` V033, PK(user_id,action), action CHECK, override 패턴 (책임. db-engineer)
- [x] D4. 백엔드 — `GET/PATCH /api/v1/users/me/keymap` JWT-only, 검증우선, override 정규화, PreferencesController 미러 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — Validator 6종·Repository·Service·Controller(MVC+통합 401/400/409/PAT401) (책임. backend-engineer)
- [x] D6. 프론트 UI — SHORTCUTS action ID 정규화 + resolveKeydown 병합 + 부트 GET + `/settings/keymap` 재배치·실시간충돌·기본복원 (책임. frontend-engineer)
- [x] D7. E2E — 재배치→발화·충돌거부·기본복원(dead-leader·서버409 MSW토글) (책임. qa-engineer)

## §4 UX 편의 (FR-UX-01, 04, 05, 06)

### §4.1 FR-UX-01 — 퀵 필터 (보드 상단 즉시 필터)

**우선순위**. 필수 | **선행**. agile-planning §2.1 | **Plan slug**. `personal/quick-filters`

- [x] D1. 도메인 — QuickFilter (책임. backend-engineer)
- [x] D2. 명세 — 보드별 사전 정의 필터 (책임. backend-engineer)
- [x] D3. 데이터 모델 — `board_quick_filters(board_id, name, query)` (책임. db-engineer)
- [x] D4. 백엔드 — CRUD API + 보드 응답에 포함 (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — 보드 상단 필터 칩 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §4.2 FR-UX-04 — Slash 명령어

**우선순위**. 높음 | **선행**. §0 | **Plan slug**. `personal/slash-cmd`

**아키텍처**. 프론트 전용 (ADR [decisions/2026-07-04-fr-ux-04-slash-cmd.md](../../decisions/2026-07-04-fr-ux-04-slash-cmd.md)). 명령 3종(`/goto`·`/search`·`/issue`)이 전부 네비게이션이라 백엔드 executor 미도입 — 클라이언트 라우팅으로 dispatch. cmdk(FR-IS-09 도입) 재사용.

- [x] D1. 도메인 — 프론트 명령 레지스트리(`commands.ts` 코드 상수). 백엔드 도메인 없음 (책임. frontend-engineer)
- [x] D2. 명세 — `/goto`(이슈 이동) · `/search`(검색) · `/issue`(새 이슈 폼 프리필), 전부 네비게이션 (책임. frontend-engineer)
- [x] D3. 데이터 모델 — 없음 (명령 정의는 프론트 코드 상수) (책임. -)
- [x] D4. 백엔드 — 없음 (프론트 전용, ADR 2026-07-04). `POST /api/v1/commands/execute` 미도입 (책임. -)
- [x] D5. 백엔드 테스트 — 해당 없음 (프론트 전용) (책임. -)
- [x] D6. 프론트 UI — cmdk 명령 팔레트 (`Cmd+K`) (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §4.3 FR-UX-05 — 키보드 단축키

**우선순위**. 높음 | **선행**. §0 | **Plan slug**. `personal/keymap`

**아키텍처**. 프론트 전용 (ADR [decisions/2026-07-05-fr-ux-05-keymap.md](../../decisions/2026-07-05-fr-ux-05-keymap.md)). FR-UX-04와 상보. **커스텀 훅(의존성 0)** 으로 구현 — product 원안의 `react-hotkeys-hook`는 미도입(신규 의존성 회피 + `g i` 시퀀스 미지원, ADR D2 deviation). MVP 범위 = 전역 네비게이션 5종(`?`·`c`·`/`·`g i`·`g d`) + 도움말 모달. 컨텍스트 의존 단축키(`j/k/e/m/s`)는 후속 FR로 제외(Maxi 결정 2026-07-05). `cmd+k`는 FR-UX-04에서 이미 구현.

- [x] D1. 도메인 — 프론트 단축키 레지스트리(`shortcuts.ts` SHORTCUTS 상수). 백엔드 도메인 없음 (책임. frontend-engineer)
- [x] D2. 명세 — 전역 5종 매핑(Gmail/Linear 스타일, 네비게이션 전용) + 가드(입력포커스/IME/수정자/enabled) (책임. frontend-engineer)
- [x] D3. 데이터 모델 — 없음 (단축키 정의는 프론트 코드 상수). 커스텀 키맵은 §3.3 FR-PF-03(범위 밖) (책임. -)
- [x] D4. 백엔드 — 없음 (프론트 전용, ADR 2026-07-05) (책임. -)
- [x] D5. 백엔드 테스트 — 해당 없음 (프론트 전용) (책임. -)
- [x] D6. 프론트 UI — 커스텀 훅(`useKeyboardShortcuts`) + radix Dialog 도움말 모달(`?`) (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §4.4 FR-UX-06 — UI/UX 전면 개편 (Jira Cloud 방식)

**우선순위**. 높음 | **선행**. 없음 (Phase 0 독립) | **Plan slug**. `fr-ux-06-jira-redesign`

**아키텍처**. ADR [decisions/2026-07-17-fr-ux-06-jira-redesign.md](../../decisions/2026-07-17-fr-ux-06-jira-redesign.md) · plan [plans/2026-07-17-fr-ux-06-jira-redesign/](../../plans/2026-07-17-fr-ux-06-jira-redesign/plan.md) · 디자인 스펙 [design/fr-ux-06-jira-redesign.md](../../design/fr-ux-06-jira-redesign.md).

> **★ 논리 ≠ 물리 (ADR D5).** 논리 소속은 **personalization** 이나 물리 구현은 `apps/web` + identity-access 다. FR-UX-05 D4 의 선례를 승계한다 — personalization 은 **이미 논리 BC 이고 물리가 identity-access** 라, 프로젝트 목록 API 를 identity-access 에 둬도 일관된다.

**범위 = 개편 전체** (Maxi 확정 — 담당자 권고 *"전역 네비만 FR"* 기각). **FR 로 신설한 것도 Maxi 확정** — 담당자는 *"FR 없이 ADR 로만"* 을 권고했으나 **진척 가시성을 우선**해 기각했다.

**핵심 결정 8종 (ADR D1~D8).** ADS **v2** 팔레트 `#0C66E4`(널리 알려진 `#0052CC` 는 **구세대 v1** 이라 기각 — 2023 토큰 리프레시로 램프가 재편됐고, 신형 네비를 택했으니 세대를 맞춘다) · Jira Cloud **2025 신형 통합 사이드바** · pathless `_shell`(**파일 기반 라우팅 전환은 영구 제외**) · **라우트 이동 = `nav`+`Link` / 패널 전환 = Radix Tabs** · 논리≠물리 · **댓글은 별도 FR** · `--chart-1~5` 는 **실소비 PR 에서** 정의(소비자 0인 토큰 선채움 금지) · 동시 PR #277 과 Phase 0 선행 / Phase 4 양보.

**22 PR 체인.** Phase0 기반(PR1 문서·PR2 프리미티브 15종·PR3 ADS 토큰·PR9 1줄 prep — 4개 독립·병렬) → Phase1 정착(PR4 하드코딩 색 141건) → Phase2 Dialog(PR5→PR6∥PR7→PR8+ESLint 락) → Phase3 Shell(PR9→PR10 `_shell`→PR11 사이드바→PR12→PR13) → Phase4 IA(#277 PR-5/6 양보) → Phase5 화면(PR17 FilterBar→PR18 테이블→PR19 상세탭→PR20 split view→PR21+21b 칸반 드래그→**PR22 정리**). **임계 경로** = PR2 → PR10 → PR11 → PR13 → PR17.

> 🛑 **PR3 의 팔레트는 시안이 정본이 아니다.** `atlassian.design` 이 JS 렌더링이라 전수 검증에 실패했고 `#E9F2FF`/`#082145`/`#172B4D` 3점만 독립 확인됐다. **PR3 에서 `atlassian.design/components/tokens/all-tokens` 전수 대조를 D단계 작업으로 수행**한다.
>
> 🛑 **진짜 위험은 라우터가 아니라 `aria-label` 4종이다** ([[frontend-nav-aria-label-e2e-contract]]). `getByRole('navigation')` 18건의 유일한 계약이다 — `검색` 은 Header 단일 5spec · **관리 메뉴 기본 펼침 필수**(접으면 3spec 클릭 실패) · **뷰 전환을 Tabs 로 바꾸면 `role=navigation` 이 소멸해 10건 즉사**. pathless 재부모화 자체는 저위험(`fullPath` 불변, route-id 결합 1곳).

- [x] D1. 도메인 — 프론트 전용. 디자인 토큰 + `ui/*` 프리미티브 15종 레지스트리 (책임. designer → frontend-engineer)
- [x] D2. 명세 — 디자인 스펙 14섹션 (`docs/design/fr-ux-06-jira-redesign.md`) + ADR D1~D8 + plan 3종 — **#279 에서 완료** (책임. designer)
- [x] D3. 데이터 모델 — 없음 (프론트 전용. 프로젝트 목록 API 는 #277 PR-2~3 소관) (책임. -)
- [x] D4. 백엔드 — 없음 (ADR D5 — 물리 `apps/web`. identity-access 는 #277 이 담당) (책임. -)
- [x] D5. 백엔드 테스트 — 해당 없음 (프론트 전용) (책임. -)
- [x] D6. 프론트 UI — 22 PR 체인 (프리미티브 → ADS 토큰 → Dialog 흡수 → `_shell`/사이드바 → 화면 6종 → 정리) (책임. frontend-engineer)
- [x] D7. E2E — `aria-label` 4종 계약 보존 + 뷰 전환 `role=navigation` 회귀 가드 (책임. qa-engineer)

## §5 캘린더 (FR-CA, 2개)

### §5.1 FR-CA-01 — 개인 캘린더 (할당/마감일 통합)

**우선순위**. 높음 | **선행**. issue-tracking §6.1 (FR-PL-01) | **Plan slug**. `personal/calendar`

- [x] D1. 도메인 — CalendarEvent (책임. backend-engineer)
- [x] D2. 명세 — 할당 이슈 + 마감일 + Worklog 일정 (책임. backend-engineer)
- [x] D3. 데이터 모델 — (조회만, 별도 테이블 X) (책임. db-engineer)
- [x] D4. 백엔드 — `GET /api/v1/users/me/calendar?from=&to=` (책임. backend-engineer)
- [x] D5. 백엔드 테스트 (책임. backend-engineer)
- [x] D6. 프론트 UI — 월/주 캘린더 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §5.2 FR-CA-02 — iCal Export (외부 캘린더 연동)

**우선순위**. 높음 | **선행**. §5.1 | **Plan slug**. `personal/icalendar`

- [x] D1. 도메인 — CalendarFeedToken(불투명 토큰+SHA-256 해시) (책임. security-engineer)
- [x] D2. 명세 — RFC 5545(자체 직렬화기) + 구독 URL 토큰 (책임. backend-engineer + security-engineer)
- [x] D3. 데이터 모델 — `user_calendar_tokens(user_id PK, token_hash UNIQUE, created_at)` — V034. **원안 `token`(평문)·`revoked_at` 정정**: FR-DB-03/PAT 관례로 SHA-256 **해시만** 저장(원문 1회 노출·미저장), 취소=하드삭제(revoked_at 불요), ADR 2026-07-09 D4 (책임. db-engineer)
- [x] D4. 백엔드 — 익명 `GET /ical/feed/{token}.ics`(permitAll·GET-only·404 수렴) + 관리 `POST/GET/DELETE /api/v1/users/me/calendar/feed`(JWT me-scope·PAT 403). FR-CA-01 `UserCalendarLookupPort` 재사용(신규 포트 0) (책임. backend-engineer + security-engineer)
- [x] D5. 백엔드 테스트 — iCal 골든 문자열(이스케이핑/폴딩/CRLF) + 통합(익명GET/rotate/PAT403/CASCADE/격리/negative-probe) (책임. backend-engineer)
- [x] D6. 프론트 UI — `/settings/calendar` 구독 URL 발급/1회복사/재발급/취소 카드 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

## §NFR personalization BC 완료 게이트

### 측정값 기록표

| 항목 | 임계 | 실측 (p95) | 비고 |
|---|---|---|---|
| 프로필 조회 | 100ms | ___ | k6 |
| 환경 설정 적용 (이론적 즉시) | 200ms | ___ | Playwright |
| 캘린더 30일 조회 | 500ms | ___ | k6 |
| iCal Export 응답 | 500ms | ___ | k6 |
| 단축키 동작률 | 100% | ___ | E2E 전수 |
| 명령 팔레트 (cmdk) 응답 | 100ms | ___ | Playwright |
| LCP (프로필/설정) | 2.5s | ___ | Lighthouse CI |
| WCAG 2.1 AA | 0 violations | ___ | axe-core |

### BC 완료 조건

- [x] §2~§5 (13 FR) 모두 `[x]` 마킹 — **13/13 달성 (2026-07-25, UX 개편 PR22 머지로 D단계 91/91 완료)**
      <!-- ★ 이 줄에 `FR-XX-NN` 형태를 쓰지 말 것 — verify-master-plan.sh 의 "§N 헤더 (FR-XX, N개)"
           스캐너가 헤더 선언으로 오인해 `N개` 파싱에 실패하고 EXIT 1 이 된다(2026-07-25 실제 발생). -->
- [ ] §NFR 측정표 모든 항목 임계 통과 — 미측정. 위 측정값 기록표 8행 전부 실측값이 `___` 공란. k6(프로필 조회·캘린더 30일·iCal Export) · Playwright(설정 적용·cmdk 응답) · E2E 전수(단축키) · Lighthouse CI(LCP) · axe-core(WCAG AA) 를 실제로 돌려 p95 를 채워야 한다 (2026-07-27 실측)
- [x] CHANGELOG.md 정리 — 2026-07-27 실측: 저장소 루트 `CHANGELOG.md` 의 `[Unreleased] — Phase 1` §BC 요약 표에 personalization 행 존재 (13 FR / 2026-07-05~07-25 / 대표 산출 4종 + 논리 BC 각주)
- [ ] README.md §7 변경 이력에 "personalization BC 완료 — YYYY-MM-DD" 추가 — 🛑 Maxi 1인 선언 대기 (에이전트 수행 불가). 2026-07-27 실측: `docs/plan/README.md` §7 은 3행뿐이고 BC 완료 행 없음 — 이 행의 날짜가 곧 선언일이므로 선언 이전에는 기입 불가
- [ ] Maxi 1인 선언 — "personalization BC 완료" — 🛑 Maxi 1인 선언 대기 (에이전트 수행 불가)
