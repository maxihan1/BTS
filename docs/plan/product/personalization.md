<!-- personalization BC — 프로필/설정/캘린더/퀵필터/Slash/단축키/UI개편/활성프로젝트/인터랙션 패리티 21 FR -->

# personalization BC

**소속 FR**. 21개 (PR 4 + PF 3 + CA 2 + UX-01,04~14 12).
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

## §4 UX 편의 (FR-UX-01, 04, 05, 06, 07, 08, 09, 10, 11, 12, 13, 14)

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

### §4.5 FR-UX-07 — 활성 프로젝트 컨텍스트

**우선순위**. 필수 | **선행**. §4.4 (FR-UX-06) | **Plan slug**. `fr-ux-07-active-project-key`

이슈 목록으로 가는 진입로 4곳(사이드바 "이슈" · `g i` · 명령 팔레트 "내 이슈" · 로그인 후 시작 페이지)이 전부 `DEFAULT_PROJECT_KEY = 'ATLAS'` 하드코딩이라, ATLAS 외 프로젝트 사용자는 자기 이슈를 볼 수단이 아예 없었다. 이 FR 은 **활성 프로젝트(Active Project)** 를 "저장된 값 하나"가 아니라 **4단 해소 함수**(① URL `projectKey` → ② localStorage 저장값 → ③ 접근 가능한 첫 프로젝트 → ④ 빈 상태)로 정의해 4곳을 한 번에 푼다. 진입로 4곳은 **무변경** — 링크가 아니라 라우트가 해소하므로 자동으로 따라온다.

**범위 정정 — 27 PR 로드맵에서 F1 로 좁힘 (Maxi 확정 2026-07-29).** 원안의 FR-UX-07 은 "Jira 인터랙션 패리티" 27 PR 로드맵 **전체**를 한 FR 로 묶고 있었고, 그 결과 D1(도메인)이 `[~]`("절반 완료")가 되는 모순이 생겼다. 단축키·인라인 편집·생성 모달·백로그는 **각자 도메인 정리와 스펙이 따로 필요한 별개 기능**이라 한 FR 의 D1 로 닫히지 않는다. 선례인 FR-UX-06(§4.4, 22 PR)이 단일 FR 로 성립한 것은 **디자인 스펙 1벌 · ADR 1벌로 굴러가는 하나의 캠페인**이었기 때문이고, 이 로드맵은 그렇지 않다. 따라서 FR-UX-07 은 F1 1 PR(PR #320)로 좁히고 나머지를 아래로 이관한다.

| 이관처 | 이름 | 승계 PR |
|---|---|---|
| §4.6 FR-UX-08 | 프로젝트 전환 · 최근 항목 · 내 작업 | F12, F17 |
| §4.7 FR-UX-09 | 이슈 생성 흐름 (모달 · 진입점) | F2, F3, **B1** |
| §4.8 FR-UX-10 | 컨텍스트 의존 단축키 | F10, F11 |
| §4.9 FR-UX-11 | 인라인 편집 | F8, F9 |
| §4.10 FR-UX-12 | 검색 진입 (커맨드 팔레트 · 전역 검색) | F4, F13 |
| §4.11 FR-UX-13 | 백로그 사용성 | F5, F15, F16 |
| §4.12 FR-UX-14 | 이슈 카드 밀도 | F14, **B2** |
| (FR 아님 — chore 10 PR) | F6 도움말 배선(§4.3 FR-UX-05 결손) · F7 댓글 기본탭(FR-CO 결손) · F18~F25 Tier 3 마감(§4.4 FR-UX-06 결손) | F6, F7, F18~F25 |

FR-UX-07 1 + 이관 16 = 17 PR, chore 10 PR 을 더해 **27 PR** — 로드맵 총량은 불변이다. 로드맵 정본은 `~/.claude/plans/ui-ux-sorted-kay.md` 이고 F 번호는 그 §PR 체인의 것이다.

> **★ 논리 ≠ 물리 (ADR §D2).** 논리 소속은 **personalization** 이나 물리 구현은 `apps/web` 이다. FR-UX-05 D4 · FR-UX-06 D5 선례를 승계한다.
> **★ 백엔드 B1·B2 의 지위 정정 (2026-07-29).** 2026-07-28 Maxi 결정 #3 은 *"B1/B2 는 기존 FR 결손 봉합이라 chore"* 였다. 분할 후에는 **B1 = §4.7 FR-UX-09 의 D4/D5 · B2 = §4.12 FR-UX-14 의 D4/D5** 다 — 「백엔드 없음」으로 비던 칸이 실제 내용으로 채워지는 쪽이 정확하다. **조용한 변경이 아니라 원 결정의 명시적 승계·정정이다.**
> **★ 여전히 범위 밖 — cross-project 조회(로드맵 B3).** `IssueApplicationService.kt:1021` 이 `assertPermission(actor, BROWSE, IssueScope.Project(projectKey))` 로 프로젝트 스코프를 강제하고 `UserCalendarLookupAdapter.kt:25-38` 이 *"모든 visibility 술어가 `PROJECTS.KEY.eq(projectKey)` 단일 축으로 하드코딩돼 재사용 불가"* 를 명시한다. 합집합 조립은 fail-open 사고라 v1 의 답은 "프로젝트 무관 조회"가 아니라 **"프로젝트 전환을 쉽게"**(§4.6 FR-UX-08)다.

- [x] D1. 도메인 — 활성 프로젝트(Active Project) = 4단 해소 함수(URL → 저장값 → 첫 프로젝트 → 빈 상태). ADR [decisions/2026-07-28-fr-ux-07-active-project-context.md](../../decisions/2026-07-28-fr-ux-07-active-project-context.md) D1~D5 착지 (책임. frontend-engineer)
- [x] D2. 명세 — `docs/specs/2026-07-28-fr-ux-07-active-project-key.md`. 시나리오 S1~S8 · 기능 요구사항 FR1~FR9 · 비기능 NFR1~NFR5 · 엣지 케이스 E1~E10 · 알려진 한계 L1~L4 (책임. frontend-engineer)
- [x] D3. 데이터 모델 — 없음 (클라이언트 localStorage 키 `bts.active-project` 만. 마이그레이션 0, ADR D4 — 서버 `user_preferences` 확장은 기각·후속 FR 후보) (책임. -)
- [x] D4. 백엔드 — 없음 (ADR §D2 논리 ≠ 물리, 백엔드 변경 0). 로드맵 B1·B2 는 §4.7 FR-UX-09 · §4.12 FR-UX-14 로 이관 (책임. -)
- [x] D5. 백엔드 테스트 — 해당 없음 (백엔드 변경 0) (책임. -)
- [x] D6. 프론트 UI — `DEFAULT_PROJECT_KEY` 프로덕션 코드 0건 · 4단 해소 훅 3종(`use-active-project`(영속 스토어) · `use-resolved-active-project`(조합 해소) · `use-track-active-project`(경로 파라미터 기록)) · `ActiveProjectGate`(프로젝트 0개·권한 거부 게이트) · 라우트 2곳(`routes/issues.index.tsx` · `routes/search.tsx`) (책임. frontend-engineer)
- [x] D7. E2E — `apps/web/e2e/active-project.spec.ts` **13/13 통과 2회 연속**(S1~S7 · S8 · E8 · E2 2종 · B2 · NFR5). **알려진 한계 1건** — S8(시작 페이지 파생 결함)은 MSW 목이 이슈를 프로젝트로 필터링하지 않아 "빈 화면 → 채워짐"을 문자 그대로 재현하지 못했다. 대신 결함의 정확한 원인(`DEFAULT_PROJECT_KEY='ATLAS'` 하드코딩)이 사라졌다는 관측 가능 증거 — 재로그인 후 실제 `GET /api/v1/issues` 요청의 `projectKey` 쿼리가 활성 프로젝트(MIDDLE)로 나가는지 — 를 네트워크 인터셉트로 확인했다. 완전 검증에는 `GET /api/v1/issues` 의 실 project-scoping 이 필요하고 e2e 스펙 약 120건에 파급되므로 범위 밖이다. **e2e 라벨 정정 1건** — 기존에 `S8` 로 적혀 있던 "검색 화면도 같은 활성 프로젝트를 따른다" 테스트는 실제로는 스펙 §7 엣지 케이스 **E8**(`/search` 는 이미 `?projectKey=` 를 읽으므로 폴백 상수만 해소 결과로 교체)이다. E2E 파일은 정정 완료 (책임. qa-engineer)

### §4.6 FR-UX-08 — 프로젝트 전환 · 최근 항목 · 내 작업

**우선순위**. 높음 | **선행**. §4.5 (FR-UX-07) | **Plan slug**. `fr-ux-08-project-switcher`

승계 PR 2건 (로드맵 §PR 체인 Tier 2).

- **F12 — 프로젝트 스위처 + 트리 펼침 영속.** §4.5 가 활성 프로젝트라는 컨텍스트를 만들었지만 그것을 **손으로 바꿀 UI 가 없다**. 신규 `components/project/ProjectSwitcher.tsx` · `TopBar.tsx` · `ProjectTree.tsx:368-370` · 신규 `hooks/use-recent-projects.ts`.
- **F17 — 사이드바 "내 작업"(프로젝트 스코프) + "최근 항목".** `i18n/nav-labels.ts:9` 의 제외 주석을 해제하고 `Sidebar.tsx:47-51` 에 배선한다. 지라의 cross-project "내 작업"은 로드맵 B3 로 제외됐으므로 v1 은 **활성 프로젝트 스코프**(`?assignee=<whoami.userId>`)로 낸다.

**PR 분할 (2026-07-30 Maxi 확정).** 위 승계 PR 2건을 그대로 두 PR 로 낸다 — 두 그룹은 **변경 파일 교집합이 0** 이다.

| PR | 로드맵 | 범위 |
|---|---|---|
| **PR-A** (#326) | **F12** | 프로젝트 스위처 + 트리 펼침 영속 + 선재 갭 해소 |
| **PR-B** (후속) | **F17** | 사이드바 "내 작업"·"최근 항목" + nav 라벨 전수 판별식 |

D1~D7 마커는 **완주 단위**이므로 F12·F17 이 **둘 다** 끝나야 `[x]` 가 된다. PR-A 는 FR 카운트·D 마커·진척 열을 건드리지 않는다. **PR-B 는 PR-A 머지 후 착수**한다 — 코드 파일은 안 겹치지만 이 문서·스펙·plan 세 문서를 공유한다.

> **★ 위 F17 의 `?assignee=` 표기는 2026-07-30 실측으로 정정됐다.** 원문은 `?assignee=me` 였으나 **`me` 센티널은 실재하지 않는다** — `IssueFilterQueryParser.kt:42` 가 인정하는 센티널은 `unassigned` 하나뿐이고 그 외 값은 UUID 로 파싱해 실패 시 **400** 이다. 정정 후는 `?assignee=<whoami.userId>` — `WhoamiResponse.userId`(`api/schemas.ts:22`) + 선례 `useGadgetData.ts:92-107` `assigneeIds: [userId]`. **신규 API 0 전제는 그대로 유지된다.** 정정 노트만 달고 원문을 남기면 PR-B 가 틀린 전제 위에서 만들어지므로 **원문 자체를 고쳤다.**
>
> **★ D1 의 "최근 프로젝트" 는 스위처 내부 정렬 축 전용이다.** 사이드바 "최근 항목"은 **최근 본 이슈**다 — 사이드바에 이미 전체 프로젝트 트리가 있어 중복이기 때문(ADR §D1).
>
> 상세는 [ADR 2026-07-30-fr-ux-08-project-switcher](../../decisions/2026-07-30-fr-ux-08-project-switcher.md) (D1~D6) · [스펙](../../specs/2026-07-30-fr-ux-08-project-switcher.md) (§11 PR 분할).

**아키텍처**. 프론트 전용 (기존 `useProjects()` 소비, 신규 API 0). 🛑 **스위처를 `<nav>` 로 만들면 안 된다** — `프로젝트` 가 기존 `aria-label="프로젝트 뷰 전환"` 의 substring 이라 `getByRole('navigation')` 계약과 충돌한다. `components/ui/popover.tsx`(현재 소비처 0) + `role="listbox"` 가 정답이다. localStorage 영속은 `hooks/use-sidebar-collapsed.ts:15-45`(zustand + fail-safe 3중 폴백) 템플릿을 §4.5 와 같은 방식으로 복제한다.

- [x] D1. 도메인 — 최근 프로젝트(Recent Projects, 스위처 정렬 축 전용) · 최근 본 이슈(Recent Issues, 사이드바 "최근 항목") 정의·상한 5·MRU 정립. ADR [decisions/2026-07-30-fr-ux-08-project-switcher.md](../../decisions/2026-07-30-fr-ux-08-project-switcher.md) D1~D6 착지 (책임. frontend-engineer)
- [x] D2. 명세 — `docs/specs/2026-07-30-fr-ux-08-project-switcher.md`. 시나리오 S1~S10 · FR1~FR16-b · NFR1~NFR7 · 엣지 E1~E14 · 한계 L1~L6 · §8-A 디자인 확정 · §11 PR 분할 · §11-B PR-B 완료 기준 (책임. frontend-engineer)
- [x] D3. 데이터 모델 — 없음 (클라이언트 localStorage 3키 `bts.recent-projects`·`bts.recent-issues`·`bts.project-tree.expanded` 만. 마이그레이션 0) (책임. -)
- [x] D4. 백엔드 — 없음 (기존 `GET /api/v1/projects`·`GET /api/v1/issues/{key}`·`GET /api/v1/issues` 소비만. Kotlin 0줄) (책임. -)
- [x] D5. 백엔드 테스트 — 해당 없음 (백엔드 변경 0) (책임. -)
- [x] D6. 프론트 UI — PR-A(#326) F12 `ProjectSwitcher` + 트리 펼침 영속 · PR-B(#327) F17 사이드바 "내 작업"·"최근 항목" + nav 라벨 전수 판별식 (책임. designer → frontend-engineer)
- [x] D7. E2E — `project-switcher.spec.ts`(PR-A) · `sidebar-my-work-recent.spec.ts`(PR-B, S7·S8·S9 + 접힘 미렌더). `role="navigation"` 계약 무회귀 (책임. qa-engineer)

### §4.7 FR-UX-09 — 이슈 생성 흐름 (모달 · 진입점)

**우선순위**. 필수 | **선행**. §4.5 (FR-UX-07) | **Plan slug**. `fr-ux-09-issue-create-flow`

승계 PR 3건 (로드맵 §PR 체인 Tier 1 + 백엔드 B1).

- **F2 — 이슈 생성 모달 + 유형·본문 + 담당자·우선순위·라벨.** (2026-08-01 Maxi 확정 재분할 — 3필드를 F3 에서 F2 로 이관했다. `components/issue/meta/` 8종 재사용이라 신규 컴포넌트가 0이고, F3 로 미루면 같은 폼 스키마·제출 페이로드·단위테스트를 두 번 열어야 한다. ADR [`2026-08-01-fr-ux-09-f2-create-issue-dialog`](../../decisions/2026-08-01-fr-ux-09-f2-create-issue-dialog.md) D-1) 지금 생성 폼은 **프로젝트를 자유 텍스트로 타이핑**해야 하고 **이슈 유형을 못 고른다**. 백엔드 `CreateIssueRequest.kt:30-35` 에 `typeId`·`description` 이 **이미 있는데 프론트가 안 보낸다**(`routes/issues.new.tsx:215-232`). 신규 `components/issue/CreateIssueDialog.tsx` · `api/issues.ts:266-278,551-568` · `TopBar.tsx:69-78`. `routes/issues.new` 라우트는 **딥링크 계약이라 유지**한다.
- **F3 — 만들기 진입점 3곳**(백로그 칸 · 스프린트 칸 · **보드 헤더**) 배선. `BacklogColumn.tsx` · `SprintColumn.tsx` · `routes/projects.$projectKey.board.tsx`. 필드는 F2 에서 이미 완결됐다.

> **★ 위 F3 서술은 2026-08-03 실측·Maxi 확정으로 두 군데가 정정됐다. 정정 노트만 달지 않고 원문을 고쳤다** — 남겨 두면 다음 세션이 틀린 전제 위에 짓는다(FR-UX-08 PR-A 의 `?assignee=me` 정정과 같은 이유).
>
> 1. **산문과 파일 목록이 서로 달랐다.** 산문은 세 번째 진입점을 「목록 헤더」로 적고 파일 목록은 `SprintColumn.tsx` 를 적었다. [F2 ADR D-1](../../decisions/2026-08-01-fr-ux-09-f2-create-issue-dialog.md) 이 이미 세 칸으로 확정했으므로 **산문 쪽이 stale** 이었다.
> 2. **보드는 컬럼별이 아니라 보드 헤더 1곳이다.** 생성 계약(`CreateIssueRequest.kt`)에 **상태(`stateKey`)가 없어**(2026-08-03 실측) 컬럼별 버튼은 「여기서 만들면 여기에 생긴다」는 **지키지 못할 약속**이 된다. 지키지 못할 약속은 하지 않는 쪽이 정답이다. 따라서 `BoardColumn.tsx` 는 **이 PR 에서 diff 0줄**이다. 근거 = [ADR 2026-08-03 D-1·D-3](../../decisions/2026-08-03-fr-ux-09-f3-create-issue-entry-points.md).
> 3. **컨텍스트 반영은 스프린트만** (2026-08-03 Maxi 확정, 3안 중 B). 백로그는 스프린트 미지정이 곧 백로그라 기본 동작이 정답이고, 보드는 위 이유로 불가하다. 스프린트는 생성 후 기존 `POST /api/v1/sprints/{id}/issues` 를 한 번 더 부르며, **배정만 실패하면 「이슈는 만들어졌다」를 경고 톤으로 먼저 알린다**(ADR D-2). 백엔드에 `sprintId` 를 추가하는 안은 범위 2~3배라 **별도 FR 후보**로 남긴다.
- **B1 — 백엔드. 이슈 생성 시 담당자·우선순위·라벨** (`issue-tracking`). 지금은 create 후 PATCH 3회를 이어 붙여야 하고 **중간 실패 시 반쯤 만들어진 이슈가 남는다**(완제품 기준 위반). 생성 1회 제출로 확정한다.

**아키텍처**. **B1 의 지위 정정 (2026-07-29).** 2026-07-28 Maxi 결정 #3 의 *"B1 = chore"* 를 승계·정정해 **이 FR 의 D4/D5** 로 승격한다(사유는 §4.5 의 세 번째 인용 블록). 신규 다이얼로그는 `role="dialog"` 가 e2e 에 164발생이라 **고유 `aria-label`** 없이는 strict mode 충돌이 난다. 필드 컨트롤은 새로 만들지 말고 `components/issue/meta/` **8종**(Assignee·Priority·Labels·Type·Impact·Environment·CustomFields·StateTransition)을 재사용한다.

- [x] D1. 도메인 — **완료**. 생성 시점에 확정 가능한 필드 집합(유형·본문·담당자·우선순위·라벨)은 B1 ADR 이 확정했고, **진입점이 반영할 수 있는 컨텍스트 경계**를 [ADR 2026-08-03](../../decisions/2026-08-03-fr-ux-09-f3-create-issue-entry-points.md) D-1 이 더해 닫았다 — 생성 계약에 `sprintId`·`stateKey` 가 없으므로 반영 가능한 것은 스프린트뿐이고 그것도 2회 호출이다 (책임. backend-engineer → frontend-engineer)
- [x] D2. 명세 — **PR #331 완료**. 모달 진입점 · 딥링크 라우트 유지 계약(라우트가 모달을 연다) · 필드별 optional 계약 · `assigneeId` 3-state 의 프론트 표현 · 상호작용 상태표. spec [`2026-08-01-fr-ux-09-f2-create-issue-dialog`](../../specs/2026-08-01-fr-ux-09-f2-create-issue-dialog.md) (책임. designer)
- [x] D3. 데이터 모델 — **없음 확정**. 기존 컬럼·기존 API 소비만. 마이그레이션 0 · 신규 API 0 (책임. -)
- [x] D4. 백엔드 — **B1 완료 (PR #328)**. `CreateIssueRequest` 에 `assigneeId`(JsonNullable 3-state)·`priority`·`labels` 추가 + `AssigneeIntent` sealed 신설 + `IssueApplicationService.createIssue` 배선. 요청/응답 계약 무회귀(응답 스키마 diff 0). **단 알림은 무회귀 아님** — ADR D-4 로 `IssueAssigned` 신규 발행(REST 경로 한정, D-5) (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — **B1 완료 (PR #328)**. `IssueApplicationRequestsTest`·`IssueApplicationServiceCreateTest`·`IssueControllerCreateTest`·`OpenApiContractTest`·`IssueImportAdapterTest`(S8b Import 알림 회귀 가드). 뮤테이션 M1~M3 전량 red 확인 (책임. backend-engineer)
- [x] D6. 프론트 UI — **F2 완료 (PR #331)**. `CreateIssueDialog` + 프로젝트 셀렉터·유형·본문·담당자·우선순위·라벨 · `LabelChipsEditor` 추출 · 진입점 2곳(딥링크 라우트 · 상단바). **F3 완료 (PR #333)** — `CreateIssueEntryButton` 공용 진입점 + 백로그 칸 · 스프린트 칸 · 보드 헤더 3곳 배선 + `initialProjectKey` 명시 전달. `BoardColumn.tsx` diff 0줄 · `CreateIssueDialog` 라우터 훅 import 0 유지 (책임. designer → frontend-engineer)
- [x] D7. E2E — **F2 분 완료 (PR #331)**. `issue-create-dialog.spec.ts` 4 시나리오(상단바 URL 불변 · 딥링크 POST 1회/PATCH 0회 · 상단바 토스트 · 닫으면 `/issues`). **F3 완료 (PR #333)**. `issue-create-entry-points.spec.ts` 5 시나리오(백로그 칸 · 스프린트 칸 생성1+배정1 · COMPLETED 미렌더 · 보드 제자리+토스트 · 보드 진입점 화면당 1개). ★E2E 가 단위 테스트가 구조적으로 못 보는 결함 1건을 잡았다 — 백로그 경로 목록 미갱신 (책임. qa-engineer)

### §4.8 FR-UX-10 — 컨텍스트 의존 단축키

**우선순위**. 높음 | **선행**. §4.3 (FR-UX-05) · §4.9 (FR-UX-11 — F11 이 F8 에 의존) | **Plan slug**. `fr-ux-10-context-shortcuts`

**정본이 이미 예약해 둔 범위다.** §4.3(FR-UX-05)이 *"컨텍스트 의존 단축키(`j/k/e/m/s`)는 **후속 FR로 제외**(Maxi 결정 2026-07-05)"* 로 명시 이연했고, 이 FR 이 그 **승계자**다. 현재 단축키는 전역 네비게이션 5종뿐이고 전부 "이동" 계열이라, 지라(25종+)를 쓰던 사람의 손이 기억하는 동작이 하나도 없다.

> **★ 이연 목록 대사표 — "승계"가 아니라 "기준 교체"다 (2026-08-03 판정).**
> 이연 시점의 `j/k/e/m/s` 는 **Gmail/Linear 기준**이었다(§4.3 D2 가 *"Gmail/Linear 스타일"* 로
> 참조 기준을 명시). 승계 시점의 F10·F11 은 **Jira 기준**이다. 두 세트가 달라 **글자만 겹치고
> 동작이 다른 항목 2건 + 대응이 없어 조용히 빠진 항목 1건**이 생겼다. 전수 대조로 닫는다.
>
> | 이연 (Gmail 기준) | 승계 (Jira 기준) | 판정 |
> |---|---|---|
> | `j` 다음 항목 | `j` go down — **F10** | 동일 |
> | `k` 이전 항목 | `k` go up — **F10** | 동일 |
> | `e` 보관(archive) | `e` 편집(edit) — **F11** | **글자만 같음** |
> | `m` 음소거(mute) | `m` 댓글(comment) — **F11** | **글자만 같음** |
> | `s` 별표(star) | 지라에 `s` 는 **있으나 검색 조건 공유**(이슈 상세와 무관) → **`s` 즐겨찾기 토글로 F11 에 배치** | **누락분 복원 + 세 번째 「글자만 같음」** |
>
> `s` 의 BTS 대응은 **이슈 즐겨찾기**이고 기능이 이미 완비돼 있다 — `useFavorites('ISSUE')` ·
> `FavoriteController`(notification BC) 의 `@PostMapping`(201/200 멱등) ·
> `@DeleteMapping`(204 멱등) · `@GetMapping` 실측. **UI 도 이미 있다** —
> `IssueMetaPanel.tsx:322` 의 `<FavoriteButton targetType="ISSUE">`(FR-UX-02 산출물). 위 실측이
> REST 3매핑만 보고 「완비」라 적었으나 화면 쪽도 이미 서 있었고, F11 은 **새 버튼이 아니라 기존
> 버튼의 동작을 재사용**했다(2026-08-04 착수 전 실측). 지라의 `w`(관심)와는 **별개 기능**이라
> F11 에서 `s`·`w` 두 키가 공존한다.
> 두 목록이 서로를 검사하지 않아 항목이 빠진 사례 — 계열 교훈 `two-lists-never-check-each-other`.

승계 PR 2건 (로드맵 §PR 체인 Tier 2).

- **F10 — 컨텍스트 단축키 아키텍처 + 목록 항법** `j`/`k`/`o`/`t`/`[`. 신규 `context-shortcuts.ts`·`useContextShortcuts.ts` · `ShortcutsHelpDialog.tsx`.
- **F11 — 상세 액션 단축키** `a`/`i`/`m`/`e`/`l`/`s`/`w`/`.` (**8종** — `s` 복원분 포함). `issues.$key.tsx` · `IssueMetaPanel.tsx` · `WatchersSection.tsx` · `CommentSection.tsx` · 즐겨찾기(`api/favorites.ts`) 소비.

**아키텍처**. 🛑 **`shortcuts.ts` 의 `SHORTCUTS` 를 건드리면 안 된다.** 여기에 키를 추가하면 `shortcuts.test.ts:121` `toHaveLength(5)` + `:147` `DEFAULT_KEYMAP` 완전일치 + 백엔드 `KeymapAction.kt` 5종 화이트리스트 + `user_keymap.action` CHECK 제약이 **동시에** 깨진다 — 이 4중 계약의 소유자는 §3.3 FR-PF-03 이다. 정답은 **`CONTEXT_SHORTCUTS` 별도 레지스트리 신설**이고, 성공 판정식은 "`shortcuts.test.ts:121` 이 **무수정 green** 을 유지" 다. 사용자 재배치(로드맵 B4)는 `KeymapAction` enum + `user_keymap` CHECK 신규 마이그레이션을 요구하므로 **v1 은 고정 키**로 출시한다.

- [x] D1. 도메인 — **F10 완료 (PR #336)**. 컨텍스트를 **레이어**로 정립(`app-shell` ⊃ `issue-list`) + `SHORTCUTS`(전역) 와의 분리 경계를 **도메인 지위 차이**로 정의 — 전역은 `user_keymap` 영속을 가진 identity-access 개념, 컨텍스트는 영속 0 의 화면 지역 규약. B4 승격 경로가 의도된 경로가 된다 (책임. frontend-engineer)
- [x] D2. 명세 — **F10 완료 (PR #336)**. 키 5종 매핑 + Jira Cloud 공식 문서 대조 기록(레포 근거 0건이던 것) · 활성 컨텍스트 라우트 기반 판정 · `shouldIgnoreEvent` 가드 승계 · 도움말 그룹 2종. 커서 단축키는 **와이드 전용**(E13 — 구현 중 실측이 초안을 뒤집음) (책임. frontend-engineer)
- [x] D3. 데이터 모델 — **없음 확정**. 단축키 정의는 프론트 코드 상수. 마이그레이션 0 (책임. -)
- [x] D4. 백엔드 — **없음 확정**. v1 고정 키. `KeymapAction` enum·`user_keymap` CHECK **무변경 기계 확인**(`git diff --exit-code`). 키맵 예약 키 가드도 프론트 단일 게이트로 두어 백엔드 0 을 지켰다(ADR D-4) (책임. -)
- [x] D5. 백엔드 테스트 — **해당 없음 확정** (백엔드 변경 0) (책임. -)
- [x] D6. 프론트 UI — **F10 (PR #336) + F11 (PR #339) 완주**. F10 — `context-shortcuts.ts`(레지스트리+판별+커서 경계) · `useContextShortcuts.ts`(zustand 등록/`enabled` 게이트) · `useKeyboardShortcuts` 폴백 확장 · `ShortcutsHelpDialog` 그룹 2종 + 실효 키맵 표기 · `issues.index.tsx` 커서 배선 · `ShellLayout` `[` 등록 · `KeymapForm` 예약 키 가드. F11 — 상세 액션 **8종**(`a`/`i`/`m`/`e`/`l`/`s`/`w`/`.`, `s` 복원분 포함) · `issue-detail` 레이어 신설 + **등록 인지 폴백**(정적 폴백표만으로는 전체화면 상세에서 판별만 성공하고 `preventDefault` 로 끝난다) · 포커스 손잡이 5종(라벨은 4단 관통) · 팔레트 zustand 전환 후 `.` 배선 · 도움말 3그룹 + 별칭 행 병합(「또는」 구분자) · `aria-keyshortcuts` 5종(조건부) (책임. frontend-engineer)
- [x] D7. E2E — **F10 (PR #336) + F11 (PR #339) 완주**. F10 — `context-shortcuts.spec.ts` 8 시나리오 + 회귀 30 동반. F11 — `detail-action-shortcuts.spec.ts` **12 시나리오 2회 연속 green**(S9 와이드 동시 생존 · E5 헛도는 `preventDefault` · E1 입력 중 무발화 포함) + 회귀 7스펙 55건 동반. `shortcuts.test.ts` `toHaveLength(5)` **무수정 green 유지**(`git diff` 0줄로 기계 확인) (책임. qa-engineer)

> **F10 완료 (PR #336, 2026-08-03).** 독립 리뷰 3종이 결함 12건을 적발해 전량 봉합 —
> 실버그 1(`?selected=` 직접 진입 시 스크롤 미동작) · **공허 가드 4**(E13 커버리지 0 ·
> E2E S7/E12 negative 가 동기 URL 읽기 · FR10 테스트 0) · 신규 충돌면 1(키맵 재배치로
> 기능 사망 → ADR D-4) · 선재 3(계약 문서 실측 명령 사망 · 도움말 정적 표기 · `s` 키 실종) ·
> 문서 drift 3 · 성능·규칙 1. 뮤테이션 M1~M12 로 전 가드의 비-공허 확인.
> **완주 순서** — §4.9 FR-UX-11 **완주**(F8 #337 · F9 #338, 2026-08-04) → F11 **완주**(#339) → D6/D7 `[x]`.

> **★ FR-UX-10 완주 (PR #339, 2026-08-04).** 착수 전 실측이 **정본 2건을 뒤집었고**(위 대사표
> `s` 항목 · 즐겨찾기 UI 실재), **설계 함정 1건**을 착수 전에 잡았다 — 와이드 split view 는
> 목록과 상세가 **동시 마운트**라(`issues.index.tsx:28`) `issue-detail` 을 좁다는 이유로 단독
> 활성으로 두면 **F10 이 만든 `j`/`k` 가 그 화면에서만 죽는다**. 폴백을 열되 **등록되지 않은
> 레이어는 건너뛰게** 해 반대 함정(전체화면 상세에서 판별만 성공하고 `preventDefault` 로
> 끝나는 ADR D-5-a 형태)도 함께 닫았다.
>
> **★★ 「봉합이 절반」이 또 나왔다 — 이번엔 실브라우저가 잡았다.** 리뷰가 High 로 잡은
> **`s`/`w` 스크린리더 무음**에 `focus()` → `click()` 처방을 넣고 **유닛 25/25 초록**을 받았는데,
> 실브라우저 추적이 그다음을 보여줬다 — 뮤테이션이 시작되면 버튼이 `disabled` 로 바뀌고
> **브라우저가 그 포커스를 `<body>` 로 떨어뜨린다.** enabled 로 돌아와도 복귀하지 않아
> `aria-pressed` 가 바뀌는 순간 포커스가 없다. **jsdom 은 이 브라우저 규칙을 구현하지 않아**
> 유닛이 영영 못 잡는 종류였다. 처방은 `aria-disabled` 전환이고, 그 부작용으로 **E8(중복 발행
> 금지)의 증인이 「브라우저가 무시」에서 「컴포넌트 자체 가드」로 옮겨가** 뮤테이션 3회로 그
> 가드를 새 증인으로 세웠다. 계열 — F8 #337 의 「눈확인이 가짜 봉합을 막았다」.
>
> **낡은 가드 3건 교체.** F10 시점 스냅샷(`담당자 지정` 등 금지 문구 하드코딩)이 F11 구현으로
> 하나는 **거짓**이 되고 하나는 **처음부터 공허**했다(`즐겨찾기 토글` — 실제 문구는
> `즐겨찾기 켜기/끄기` 라 영원히 통과). 삭제가 아니라 **레지스트리 파생형으로 교체**해
> FR-UX-05 FR8 계약을 살리고 F12 이후에도 썩지 않게 했다. 유닛·E2E 양층에 같은 기준으로 배치.
>
> **검증.** 유닛 **8,719**(549 파일) · E2E 신규 12 **2회 연속** + 회귀 55 · `test:workflow` 95/95 ·
> tsc 0 · eslint 0 error · 판정식 `shortcuts.ts`·`shortcuts.test.ts` **git diff 0** ·
> 브라우저 눈확인 라이트/다크(포커스 링 4종 · 토글 2종 · 도움말 3그룹 · 구분자).
> **백엔드 0줄 · 마이그레이션 0 · 신규 의존성 0 · 신규 UI 컴포넌트 0 · FR 총수 139 불변.**

### §4.9 FR-UX-11 — 인라인 편집

**우선순위**. 높음 | **선행**. 없음 (로드맵상 의존 0 — 즉시 착수 가능) | **Plan slug**. `fr-ux-11-inline-edit`

승계 PR 2건 (로드맵 §PR 체인 Tier 2). 착수 시점의 BTS 는 제목·본문을 **이슈 상세 화면 안에서 이미 편집할 수 있었다**(OCC `expectedVersion` 포함). 부족한 것은 **진입과 키보드**였다 — 진입이 버튼 클릭 한 경로뿐이라 지라를 쓰던 손이 텍스트를 클릭해도 아무 일이 일어나지 않았고, `Enter` 저장·`Esc` 취소가 없었다. F8 이 그 간극을 닫았고, F9 가 목록 셀 3종(담당자·우선순위·상태)까지 넓혀 **FR-UX-11 은 완주**했다.

- **F8 — 이슈 상세 인라인 편집.** 제목/본문을 클릭해 진입, Enter 저장, Esc 취소. `routes/issues.$key.tsx` · `IssueDescription.tsx`. **완료 (PR #337)**.
- **F9 — 이슈 목록 셀 인라인 편집**(담당자·우선순위·상태). `IssueTable.tsx` · `issue-columns.ts` · `components/issue/meta/*` 재사용 · `components/ui/popover.tsx`(소비처 **1→2** — `ProjectSwitcher.tsx:9` 가 이미 소비 중이다. 초안의 `0→1` 은 실측상 거짓이라 정정). **완료 (PR #338)**.

**아키텍처**. 프론트 전용 예상 — 기존 이슈 PATCH API 를 소비한다. **F8 이 두 FR 의 공통 선행**이다(F9 가 F8 에, §4.8 의 F11 이 F8 에 의존). 목록 셀은 낙관적 동시성(OCC) 409 를 만나므로 `setQueryData` 부분 갱신 대신 invalidate 로 정합을 맞춘다.

- [x] D1. 도메인 — **F8 완료 (PR #337)**. 인라인 편집을 *"새 저장 경로 신설"* 이 아니라 **기존 저장 경로에 진입면·키보드를 얹는 것**으로 정립했다 — 저장·취소·409 충돌 모델은 `useUpdateIssueSummary`(OCC `expectedVersion` · 롤백 · toast)를 **그대로 승계**한다. 편집 가능 필드 집합은 기존 `canEdit`(UPDATE 권한, fail-closed) 판정을 재사용한다 (책임. frontend-engineer)
- [x] D2. 명세 — **F8 완료 (PR #337)**. `docs/specs/2026-08-03-fr-ux-11-f8-inline-edit.md`. 진입(텍스트 클릭 + 기존 버튼 병존) · 제목 `Enter` 저장 / 본문 `Ctrl`·`Cmd`+`Enter` 저장(맨 `Enter` 는 개행 보존) · `Esc` 취소 · 예외 E1~E10(pane 이중 발화 · IME 조합 · 중복 제출 · 텍스트 선택 · 링크 클릭 · 권한). 본문 `Esc` 는 **편차 D-1** 로 확인 절차를 둔다(지라 JRACLOUD-36670 결함 미복제) (책임. designer)
- [x] D3. 데이터 모델 — **없음 확정**. 기존 컬럼만 쓴다. 마이그레이션 0 (책임. -)
- [x] D4. 백엔드 — **없음 확정**. 기존 이슈 PATCH API 소비. `git diff --exit-code main -- backend/` **EXIT 0 실측** (백엔드 0줄) (책임. -)
- [x] D5. 백엔드 테스트 — **해당 없음 확정** (백엔드 변경 0) (책임. -)
- [x] D6. 프론트 UI — **F8 완료 (PR #337)**. 상세 제목·본문 — 텍스트 클릭 진입면(`heading` 안쪽만 감싸 접근성 이름 보존) · 진입 시 포커스 + 커서 텍스트 끝 · `Enter`/`Ctrl`+`Enter` 저장 · `Esc` 취소 · 기존 `✎ 제목 수정`·`본문 편집` 버튼 병존(FR7). 원시 `<button>` 은 PR22 판정식상 OUT(P6)으로 `eslint.config.js`·`button-primitive-usage.test.ts` 에 등재. **F9 완료 (PR #338)** — 목록 셀 3종. 공통 래퍼 `cells/EditableCell.tsx`(hover 어포던스 · 행 클릭 전파 차단 · popover) + `cells/{Assignee,Priority,Status}Cell.tsx` + 목록 캐시 전용 `hooks/use-issue-list-cell-field.ts`(낙관적 반영 · 409 롤백 · invalidate) · `lib/transition-availability.ts` 공용 승격(상세 라우트의 지역 함수를 옮겨 상세·목록이 같은 응답을 같게 설명한다) · 종료 전이는 `ResolutionModal` 경유 · popover 열림 동안 목록 단축키 차단(E10) (책임. designer → frontend-engineer)
- [x] D7. E2E — **F8 완료 (PR #337)**. `e2e/inline-edit.spec.ts` 4 시나리오(S1·S2 제목 클릭→`Enter` 저장 / S3 제목 `Esc` 원본 복원 / S4·S5 본문 클릭→`Ctrl`+`Enter` 저장 / S7 본문 `Esc` 확인 패널). 기존 `issue-crud-happy`·`issue-edit-conflict`·`issue-permission`·`issue-ui-regression` 동반 통과. **F9 완료 (PR #338)** — `e2e/issue-list-inline-edit.spec.ts` 11 시나리오(S1 담당자 검색·선택 / S2 우선순위 / S3 가용 전이만 노출 / S4 요약·행 여백 클릭은 기존대로 상세 / FR3 편집 셀 클릭은 상세 미개방 / S6 `Esc` 후 이동 없음 / E14 결의안 모달 2건 — 미선택 시 전이 요청 0회 / E10 popover 중 `j`·`k` 커서 고정 / 드래그 텍스트 선택 회귀 가드). S3·E10 은 대조군을 Given 에 세워 비-공허를 보장했다 (책임. qa-engineer)

> **F8 완료 (PR #337, 2026-08-03).** 병행 wave 로 진행했고 독립 검증이 결함 3건을 적발해 봉합 —
> **스펙 미충족 1**(진입 시 포커스·커서 끝이 미구현. E2E `locator.press()` 가 자동 포커스를 줘
> **가짜 그린**으로 가려져 있었다) · **공허 가드 1**(본문 `Esc` 의 pane 이중 발화 방지에 증인 0 —
> 컴포넌트 단독 테스트에 pane 컨텍스트가 없어 구조적 미커버였고 라우트 테스트로 이관) ·
> **정본 서술 오류 1**(이 절의 *"인라인 편집이 전무"* 가 실측상 거짓 — 위 산문 정정).
> 뮤테이션으로 전 가드의 비-공허를 확인했고, 그 과정에서 커서 단언 1건이 **jsdom 기본값과
> 우연히 일치해 공허**함을 발견해 테스트 이름을 실제 잡는 범위로 좁혔다.
> **완주 순서** — F9(목록 셀) → D6/D7 `[x]`. §4.8 F11 이 이 F8 에 의존한다.

> **F9 완료 (PR #338, 2026-08-04) — FR-UX-11 완주.** 목록 셀 3종(담당자·우선순위·상태)을
> popover 인라인 편집으로 열었다. **백엔드 0줄**(`git diff --exit-code main -- backend/` EXIT 0) ·
> **마이그레이션 0** · **신규 의존성 0**(`package.json`·`pnpm-lock.yaml` diff 0) ·
> **FR 총수 139 불변**(다국어 전환·용어 직접 변경은 신규 FR 2건으로 분리 — 아래 후속).
>
> **구현 중 뒤집힌 정본 5건** (전부 실측이 문서를 이겼다).
> ① 이 절의 *"`popover.tsx` 소비처 0→1"* 이 거짓 — `ProjectSwitcher.tsx:9` 가 이미 소비 중이라
> **1→2**(위 산문 정정). ② plan 초안의 hover 어포던스 토큰 `--border-default` 가 `index.css` 에
> **부재** — 없는 커스텀 프로퍼티는 에러가 아니라 **무효 선언**이라 FR2 어포던스가 통째로 침묵
> 소멸한다. `--border` 로 교체하고 **클래스 문자열 단언**으로 못박았다(계산값 단언은 jsdom 이
> 커스텀 프로퍼티를 해석하지 않아 공허해진다 — F8 커서 단언과 같은 함정).
> ③ 훅 판별식의 부분 문자열 `pnpm exec` 가 `pnpm --filter @bts/web exec` 를 **못 잡았다**
> (사이에 플래그가 낀다) — 판별식은 초록인데 결함은 사는 양식이라 정규식으로 교체.
> ④ 초안 처방 *"루트 `.lintstagedrc.json` 에서 `apps/web/**` 항목 제거"* 가 **양쪽 다 불가** —
> `lint-staged` 의 `runAll.js` 는 **설정이 2벌 이상일 때만** 설정 디렉토리를 cwd 로 삼는다.
> 비우면 `ConfigEmptyError` 로 전 커밋 즉사, 지우면 1벌이 되어 cwd 가 루트로 돌아가 결함 부활.
> 채택안은 **2벌 유지**이고, 되돌아감이 에러가 아니라 **침묵**이라 `설정 2벌 이상` 단언을 신설했다.
> ⑤ 우선순위 표기가 **같은 칸에서 닫히면 `Medium`(영어) 열면 `보통`(한글)** — F9 이 만든 게
> 아니라 목록(백엔드 `priorityName`)과 상세(`issueDetailStrings.priorityNames`)가 서로 다른
> 정본을 보던 **선재 불일치**를 한 칸에서 만나게 해 드러났다. 한글 정본 1개로 통일(백엔드 0줄).
>
> **★봉합이 만든 신규 충돌면 1건.** pre-commit 훅 cwd 를 저장소 루트로 옮기자 ESLint 9 flat
> config 의 상대 `files` 패턴이 기준을 잃어 `apps/web/eslint.config.js:85-109` 의 **PR22 원시
> `<button>` 예외 19항이 통째로 무효화**됐다. Task 6 이 그 목록에 있는 파일(`issues.$key.tsx`)을
> **처음** 건드리며 드러났다 — 그 전 커밋은 전부 목록 밖이라 **봉합이 자기 부작용을 가린 구간**이
> 있었다(계열 `seal-blinds-existing-guard`). 처방은 `apps/web/.lintstagedrc.json` 신설로 프론트
> 파일의 cwd 를 `apps/web` 으로 되돌려 **훅과 CI 가 같은 조건에서 같은 판정**을 하게 한 것.
>
> **후속 (이번 PR 에서 의도적으로 남김 — FR 신설 아님).** 신규 FR 후보 2건(한글/영어 전환 ·
> 우선순위 용어 직접 변경) · QA F2 hover 어포던스 대비 미달 · QA F3/F4 목록 가로 오버플로와
> 375px 붕괴(둘 다 선재) · 루트 `.lintstagedrc.json` 의 도달 불가 항목 · worktree Playwright
> `webServer` 함정. 전량 `docs/plans/2026-08-04-fr-ux-11-f9-list-cell-inline-edit.md §후속 항목`.

### §4.10 FR-UX-12 — 검색 진입 (커맨드 팔레트 · 전역 검색)

**우선순위**. 필수 | **선행**. §4.5 (FR-UX-07) | **Plan slug**. `fr-ux-12-search-entry`

승계 PR 2건 (로드맵 §PR 체인 Tier 1 F4 + Tier 2 F13).

- **F4 — Cmd+K 실체 검색.** 지금은 슬래시 없이 텍스트를 치면 **화면이 비고 Enter 도 무반응**이다(`CommandPalette.tsx:131,165,180`). 이슈키 즉시매칭 + 프로젝트 로컬필터 + `text ~ "…"` AQL 디바운스를 얹는다. `components/ui/command.tsx`(소비처 0→1) · `api/search.ts`.
- **F13 — 상단바 전역 검색 입력창 + 자연어 폴백.** ✅ **#341 완료.** 아이콘 버튼을 `role="searchbox"` + 접근성 이름 `전역 검색` 입력창으로 교체했다. 자연어 폴백은 **3갈래 판별**(이슈키 → 그 이슈로 · AQL 문법 → 원문 통과 · 그 외 → `text ~ "…"` 래핑)로 확정 — 키워드 매핑은 목록에 없는 말이 나오는 순간 신뢰가 깎여 **의도적으로 채택하지 않았다**(Maxi 확정 M2). `TopBar.tsx` · 신규 `lib/aql-natural.ts` · 신규 `lib/issue-key.ts`. **`routes/search.tsx` 는 무변경** — 상단바가 `projectKey` 를 싣지 않고 목적지가 4단 해소와 `ActiveProjectGate` 를 이미 소유함이 실측으로 확인돼 게이트 복제를 피했다.

**아키텍처**. **이름표 분리 (Maxi 결정 2026-07-28 #4)** — 상단바 입력창은 `전역 검색`, 기존 `검색` 은 AQL 페이지 제출 버튼 전용이다. `검색` 정확일치가 e2e 3파일 6발생이라 이름표를 겹치면 strict mode 로 즉사한다. 팔레트 회귀 가드는 **"빈 입력 시 `QUICK_LINKS` 바로가기 4개와 순서 보존"**(`command-palette.spec.ts:252-259`). 진짜 전역 검색(프로젝트 무관 + 이슈/프로젝트/사용자 혼합)은 `AqlSearchRequest.kt:36-37` `projectKey @NotBlank` · `SearchController.kt:169` blank 거부 · `AqlFields.kt:72` 의 `project`·`assignee` 가 `PLANNED` 라는 **3층 차단**에 막혀 있어, v1 은 §4.5 의 활성 프로젝트 스코프로 낸다.

- [x] D1. 도메인 — 팔레트 입력의 3계층(슬래시 명령 · 이슈키 · 자유 텍스트) 판별 규칙 정립. §4.2 FR-UX-04 명령 레지스트리와의 경계 (책임. frontend-engineer) — **F4 #340**. ADR 5건. 판별을 `commands.ts` 와 **분리된 레이어**에 두고 `ParsedCommand` 6갈래를 동결했다(FR-UX-04 ADR D3 이 레지스트리 범위를 "명령 3종"으로 못박았고 이슈키/자유텍스트는 명령이 아니다). 경계는 흔적을 남기지 않으므로 `boundary.test.ts` 6건이 기계 강제
- [x] D2. 명세 — 입력 판별 · 디바운스 · 결과 랭킹 · `전역 검색`/`검색` 이름표 계약 (책임. designer) — **F4 #340**. FR 13 · NFR 6 · 엣지 12 · S1~S8. **Jira Cloud/DC 공식 대조 6건 + 의도적 편차 3건**(★`/` 의 의미가 Jira 와 **정반대** — Jira 는 `/`=항목검색, BTS 는 `/`=명령). `전역 검색` 이름표는 **F13 소관**이라 이 PR 범위 밖 — 대신 팔레트 안에 `검색` 이름을 새로 만들지 않는 것을 즉사 계약으로 지켰다
- [x] D3. 데이터 모델 — **없음 확정** (기존 AQL 검색 API 소비. 마이그레이션 0 · 영속 상태 0) (책임. -)
- [x] D4. 백엔드 — **없음 확정** (v1 은 프로젝트 스코프 유지. cross-project 는 `AqlSearchRequest.kt:36` `projectKey @NotBlank` · `SearchController.kt:167` blank 거부 · `AqlFields.kt:73` `PLANNED_FIELDS` 의 **3층 차단**으로 범위 밖 — 실측 확인) (책임. -)
- [x] D5. 백엔드 테스트 — **해당 없음 확정** (백엔드 변경 0줄) (책임. -)
- [x] D6. 프론트 UI — F4 팔레트 실체 검색 · F13 상단바 `전역 검색` 입력창 + 자연어 폴백 (책임. designer → frontend-engineer) — **F4 #340 + F13 #341 로 완주.** F13 이 아이콘 버튼을 `role="searchbox"` + `전역 검색` 입력창으로 교체하고, 입력을 이슈키 / AQL / 자유 텍스트 **3갈래**로 가르는 순수 함수 `lib/aql-natural.ts` 를 신설했다. **`components/ui/input.tsx` 프리미티브 재사용**(계약 §4) — raw `<input>` 인라인 클래스는 plan 리뷰에서 BLOCKER 로 걸러졌다
- [x] D7. E2E — 비-슬래시 입력이 AQL 검색을 호출 · 빈 입력은 바로가기 4개와 순서 보존 · `검색` 정확일치 무회귀 (책임. qa-engineer) — **본문 3항목은 F4 가 전량 충족(#340, S9~S14 신규 6 + S4 갱신)**. F13 #341 이 E2E **6파일**의 sentinel 을 `searchbox`+`navLabels.globalSearch` 로 교체하며 `HEADER_SEARCH_ARIA_LABEL` 하드코딩을 전량 제거해 i18n 정본 참조로 전환했다(2026-05-26 교훈 해소). ★`saved-filters.spec.ts:206` 은 **plan 이 「AQL 제출 버튼」으로 오분류**했던 상단바 진입 헬퍼로, 같은 PR 에서 봉합했다

### §4.11 FR-UX-13 — 백로그 사용성

**우선순위**. 필수 | **선행**. 없음 (로드맵상 F5 의존 0) | **Plan slug**. `fr-ux-13-backlog-usability`

승계 PR 3건 (로드맵 §PR 체인 Tier 1 F5 + Tier 2 F15·F16) + **후속 B3 1건** (아래).

- **F5 — 실동작 결함 2건 봉합.** ✅ **완료 (PR #342)**. 백로그/스프린트 카드의 담당자가 **전원 `?`(이름 미확인)로 렌더**됐다 — 빈 `Map` 을 만들어 그대로 넘기고 채우는 코드가 없었다(`BacklogBoard.tsx:102`). 조회 실패 시엔 **빈 `<div/>`** 를 반환해 에러 안내도 재시도도 없었다(`:99`). **★ 이 항목의 원래 서술 3건이 착수 시 실측으로 뒤집혔다** — 줄번호 `:217`·`:214` 는 실제 `:102`·`:99` 였고(파일 전체가 174줄), 처방으로 적혀 있던 *"`board.tsx:333` 의 `useUsersByIds` 조립 패턴을 복제한다"* 는 **두 겹으로 틀렸다**. 보드 라우트(`projects.$projectKey.board.tsx:335`)는 `useUsersByIds` 가 아니라 `useQuery(['users'], fetchUsers)` **전체 목록**을 쓰고, 그 방식은 `UsersController.kt:53` 의 `MAX_RESULTS = 50` 때문에 **1,000명 조직에서 임의의 50명만** 돌려준다. 즉 **보드 화면이 같은 잠재 결함을 이미 갖고 있고**(개발 시드 5명이라 안 드러남), 복제했으면 결함을 옮기는 것이었다. 실제 처방은 **담당자 id 만 모아 50개씩 나눠 전량 조회**(`useUsersByIdsChunked`)다. **보드의 동일 결함은 별도 후속 항목** (Maxi 확정 2026-08-05 M2 — 한 PR = 한 관심사).
- **F15 — 백로그 세로 스택 + 스프린트 다이얼로그 + 키보드 DnD.** ✅ **완료 (PR #343)**. 가로 칸반(`BacklogBoard.tsx:178` `overflow-x-auto` — 정본의 `:246,248` 은 **실측으로 틀렸다**)을 `flex flex-col` 세로 스택으로 바꾸고 렌더 순서를 뒤집었다(스프린트 위 · 백로그 아래 — **클라이언트 정렬 없이** 백엔드 `sprintComparator` 순서를 그대로 그린다). 신규 `StartSprintDialog.tsx`·`CompleteSprintDialog.tsx` · 섹션 접기(`use-backlog-collapsed.ts`, 프로젝트별 localStorage) · 키보드 DnD(신규 `backlog-keyboard-coordinates.ts` — dnd-kit 기본 좌표계산기는 **방향키 1회 25px** 이라 세로 스택에서 섹션 간 이동이 물리적으로 불가능했다) · 한국어 드래그 공지(`backlog-announcements.ts`). `SprintColumn.tsx` **파일명 유지**(`button-primitive-usage.test.ts` 가 실재를 단언).
  **★ 이 PR 의 지배 제약 — 완료된 스프린트의 이슈는 영구 동결된다.** `SprintRepository.unassignIssue` 가 `status <> COMPLETED` 조건부 DELETE 라 **조용히 204** 를 주고, `UNIQUE(issue_key)` 때문에 다른 스프린트로도 못 옮긴다. 「이관 먼저 → 전건 성공 → 완료 직전 재검증 → 완료」와 「분류/목록을 못 믿으면 완료 차단」이 전부 여기서 파생됐다. 상세는 ADR `2026-08-05-fr-ux-13-f15-backlog-vertical-stack.md`.
  **★ 코드 리뷰가 BLOCKER 4건을 잡았고 전량 봉합했다** — ① Esc 가 취소가 아니라 드롭(dnd-kit 이 `end` 를 먼저 검사해 `cancel: [Esc]` 가 도달 불가 공허 가드가 됐다. 스크린리더 안내가 거짓이었다) ② 분류 정보 없이 완료하면 **완료된 이슈까지 전량 반출**(테스트가 오히려 그 동작을 못박고 있었다) ③ 409 가 내 입력 파기 ④ **그 봉합이 문제를 옮겨** 409 재시도가 남의 저장분을 삭제. ④는 GREEN 설계마저 공허해(폼을 서버값으로 맞추니 가드가 장식이 됨) REFACTOR 가 **사본 대신 파생**으로 뒤집었다.
  검증 — 유닛 **9,083건/566파일** · E2E `backlog.spec.ts` **19/19** + 백로그 참조 **10파일 동반 59/59** · 뮤테이션 검증 **24회** · 브라우저 눈확인 라이트/다크 **13항목** · **백엔드 0줄**.
- **F16 — 백로그 필터바 + 에픽 패널.** ✅ **완료 (PR #344)**. 세로 스택 **바깥 상단**에 필터바(제목 검색 · 담당자 · 미배정)와 **접이식 에픽 패널**을 얹어 백로그·스프린트 **모든 섹션을 동시에** 좁힌다. 섹션 헤더 카드 수는 필터 후 집합으로 갱신되고, 0건이면 `FilteredEmptyState`(재사용)가 초기화 진입을 준다. 신규 `BacklogFilterBar.tsx` · `BacklogEpicPanel.tsx` · `lib/backlog-filter.ts` · `hooks/use-backlog-epics.ts` · `i18n/backlog-labels.ts`. 공유 `FilterBar.tsx` 에는 **선택적 `hiddenSections` prop(기본 = 표시)** 만 더해 소비처 2곳(`BoardFilterBar`·`IssueFilterBar`)이 diff 0줄이다. `CreateSprintForm` 을 백로그 섹션 헤더로 옮겨(F15 스펙 `:524` 약속 이행) Jira 와 같은 자리에 붙였고, 필터를 URL search 에 실어(`router.ts` `validateSearch` — 보드 선례) 새로고침·링크 공유가 보존된다. **백엔드 0줄 · 마이그레이션 0 · 신규 API 0 · 신규 의존성 0.**
  **★ 이 항목의 원래 서술이 실측으로 뒤집혔다 — 이 FR 에서 3번째다** (F5 줄번호 · F15 `:246,248` 에 이어). 정본은 *"`FilterBar` 의 슬롯 4종이 이미 확장용 설계라 **그대로 쓴다**"* 였으나, 그대로 쓰면 **동작하지 않는 장식 필터 2개**(라벨 · 컴포넌트)가 화면에 생긴다. 백로그 응답 `backlogIssueSchema`(`api/backlog.ts:25-42`)의 실측 필드가 `key`·`summary`·`currentStateKey`·`assigneeId`·`priority`·`rank`·`version`·`epicKey` 뿐이고 **`labels`·`componentIds`·`typeKey` 가 아예 없기** 때문이다. **보드와 백로그는 필터 기전이 다르다** — 보드는 `buildBoardFilterQuery`(`api/boards.ts:292`)가 쿼리를 만들어 **서버가** 거르지만, 백로그는 `getBacklog(@PathVariable projectKey)` 가 쿼리 파라미터 0개라 **클라이언트가** 걸러야 하고 거를 필드가 없으면 못 거른다. 그래서 필터 축을 **있는 것 4종**(제목 · 담당자 · 미배정 · 에픽)으로 한정하고 공유 컴포넌트를 **바꿨다**(Maxi 확정 2026-08-06 D1). 상세는 ADR `2026-08-06-fr-ux-13-f16-backlog-filter-epic.md`.
  **★ 코드 리뷰 BLOCKER 2건 — 둘 다 「두 경로 중 하나만 봉합」 이라는 같은 양식이었다.** F15 BLOCKER ④(「그 봉합이 문제를 옮겼다」)와 같은 결이 **PR 3개 연속** 재현됐다. ① **DnD 칸 경로** — 「표시 집합 ≠ 동작 집합」 방어가 `resolveOverToDropZone` 의 **카드 경로에만** 걸렸다. 카드 경로는 `orderedKeysOf(view, …)` 로 원본에서 이웃 키를 다시 뽑지만, **칸 경로는 `over.data.current.orderedKeys` 를 그대로 쓰고** 그 값은 칸 컴포넌트가 **필터된 `issues` prop** 으로 만든 것이다 — 필터가 걸린 채 칸 빈 자리에 놓으면 rank 이웃이 보이는 것만으로 계산된다. ② **필터바 초기화 되돌림** — 초기화 진입이 **두 곳**(필터바 자체의 「초기화」 · 0건 빈 상태의 「초기화」)인데 한쪽만 로컬 검색 입력까지 비웠고, 다른 쪽은 250ms 디바운스가 **지운 검색어를 도로 밀어 올려** 초기화가 튕겼다. **두 건 다 머지 전 봉합 완료** — ① `867536477`(칸 경로도 `orderedKeysOf(view,…)` 를 쓰게 통일. 단 `data.orderedKeys` 자체는 **지우지 않았다** — `backlog-keyboard-coordinates.ts:56` `isEmptyColumn` 이 그 값으로 키보드 착지 후보를 판정하는데 **그쪽은 「보이는 것」이 맞다**. 같은 데이터의 소비자 둘이 서로 다른 의미를 요구한다는 비대칭을 두 KDoc 에 못박았다. 지웠으면 필터 활성 중 키보드 DnD 가 죽었을 것 — **봉합이 문제를 옮기는 것을 한 겹 더 막은 사례**) · ② `7a1ed7a12`(effect 가드를 「디바운스가 **현재 입력에 정착했을 때만** 올린다」로 강화. 리뷰가 제시한 후보 가드는 `exhaustive-deps` 때문에 매 키 입력마다 effect 가 재실행돼 **낡은 중간값이 새는 창을 새로 여는** 또 하나의 반쪽 봉합이 될 뻔했다).
  **★ 두 결함이 살아남은 이유도 같다 — 테스트 하네스가 실물을 안 본다.** DnD 는 `DndContext` 를 mock 해 `over.data` 를 테스트가 손으로 지어내므로 **컴포넌트가 실제로 싣는 값**을 아무도 관측하지 않았고, 초기화는 `BacklogFilterBar.test.tsx` 가 **비제어 하네스**라 되돌림이 원리적으로 관측 불가였다. 두 봉합 모두 **하네스부터 고쳤다** — 전자는 렌더된 `data-ordered-keys` 를 되읽는 `liveColumnDropTarget`, 후자는 `onChange→value` 되먹임 고리를 가진 `renderControlled`.
  **★ 브랜드 타입 방어의 정확한 한계 — 전개 연산자에서 한 홉 만에 벗겨진다.** plan 리뷰 BLOCKER-1 의 처방으로 `FilteredBacklogView`(`readonly` 배열 기반, 런타임 마커·캐스팅 없음)를 도입해 완료·DnD 핸들러가 필터 결과를 받으면 컴파일이 깨지게 했다. **그런데 그 방어는 `buildDisplay` 를 넘지 못한다** — 거기서 `sections = filtered.sprints.map(g => ({ meta: g.sprint, issues: [...g.issues] }))` 로 전개하는 순간 `readonly` 가 사라진다. 실제로 뮤테이션 검증에서 `{ sprint: s.meta, issues: s.issues }` 로 되돌리는 변형이 **`tsc` 를 통과했고**, 그것을 잡은 것은 타입이 아니라 **테스트**였다. 필드 이름을 `sprint` 가 아니라 `meta` 로 둔 것(TypeScript 는 프로퍼티의 `readonly` 를 대입 가능성 판정에서 무시한다)이 실질 방어의 절반이다. **즉 「타입으로 닫았다」는 경계 한 겹에서만 참이고, 그 너머는 여전히 테스트가 증인이다.**
  검증 — 유닛 **9,231건 / 570파일**(F15 기준선 9,083/566 → +148건 / +4파일) · E2E `backlog.spec.ts` **35 tests** + 백로그 참조 **10파일 동반 75 tests**(전체 스위트 **696 tests / 144파일**) · 브라우저 눈확인 라이트/다크 · **백엔드 0파일**(`git diff --name-only main...HEAD | grep -c '^backend/'` → 0). 측정 함정 1건도 함께 적발했다 — **Playwright 1.60 은 파일명 조각 인자를 필터로 쓰지 않고 조용히 전량을 돈다.** 경로를 다 적지 않으면 「11파일만 돌렸다」고 믿은 채 전량 결과를 보게 되고, 초록이면 아무도 눈치채지 못한다.
  **★ 여기 적혀 있던 4개 수치가 후속 ⑦ 착수 시 실측으로 뒤집혔다 (2026-08-06 정정).** 원문은 「유닛 **9,214**건 / 570파일 · E2E `backlog.spec.ts` **82/82** + 10파일 동반 **47/47**」이었으나, `vitest run` 실측이 **9,231**(파일 570 은 일치), `playwright test --list` 실측이 **35** 와 **75** 였다. 파일 수만 맞고 **테스트 건수는 유닛·E2E 양쪽 다 틀렸다** — 바로 위에 적힌 「Playwright 가 조용히 전량을 돈다」는 측정 함정이 남긴 잔재로 보인다(82·47 은 어느 실행 단위에도 대응하지 않는다). **후속 ⑦(PR #345) 반영 후 유닛은 571파일 / 9,237건**(판별식 파일 1 + 테스트 6).
- **B3 — 백로그 조회 범위 축소 (백엔드). 🆕 후속 항목** (Maxi 확정 2026-08-05 · F15 착수 중 신설). **F15/F16 범위 밖이고 이 FR 의 D 단계에도 넣지 않는다** — 별도 항목으로 착수한다. **사유.** F15 가 「`truncated=true` 면 스프린트 완료를 차단」을 도입하는데(영구 동결 방지 — 아래 인용 블록), 그 상태를 빠져나갈 수단이 **현재도 F16 후에도 없다**. 착수 중 실측 — `BacklogController.kt:56-59` `getBacklog(@PathVariable projectKey)` 는 **쿼리 파라미터 0** 이고 `BacklogApplicationService` 는 `BoardIssueLookupPort.kt:94` 의 `BoardCardFilter` 오버로드를 **쓰지 않는다**. F16 은 정본상 「프론트 전용」이라 **이미 잘려서 도착한 응답을 클라이언트에서 다시 거를 뿐** `truncated`(`IssueRepository.kt:1204` `BOARD_CARD_FETCH_LIMIT = 1000`)를 내릴 수 없다. **즉 B3 가 오기 전까지 이슈 1,000건을 넘긴 프로젝트는 스프린트를 완료할 수 없다.** 범위 — 백로그 조회에 필터/페이지 파라미터를 추가해 `truncated` 를 실제로 내릴 수 있게 한다.
  **★ 구현 중 발견으로 범위 1건 추가 (2026-08-05).** 응답에 **`hiddenIssueCount`**(또는 `sprint_issues` 키 수 대비 반환 수)도 함께 실어야 한다 — `BacklogApplicationService.kt:144-150` 의 `keys.mapNotNull { issueByKey[it] }` 가 **가시성 제한 이슈**(`IdentityAccessIssueSecurityDirectory.kt:77-79`, 프로젝트에 이슈 보안 스킴이 배정된 경우)와 **soft delete 이슈**(`IssueRepository.kt:955` `DELETED_AT.isNull`)를 조용히 떨어뜨리는데 **`truncated` 가 서지 않아** 프론트가 같은 완료 차단을 걸 수 없다. 관측 신호가 0이라 프론트 단독으로는 가드가 불가능하다(가드를 달면 그 자체가 가짜 그린). 시더·마이그레이션에 스킴 배정이 0건이라 **신규 배포는 휴면**이고, 운영 인스턴스의 활성 여부는 **미확인**이다.
- **COMPLETED 스프린트 카드 위 드롭 (선재 결함 · F15 E2E 단계에서 발견, 범위 밖).** 칸 droppable 은 `SprintColumn.tsx` 가 `disabled: isCompleted || collapsed` 로 막지만, **그 안의 카드 droppable 은 `BacklogCard.tsx` 가 `disabled: isDragging` 뿐**이라 완료 여부를 모르고, `resolveOverToDropZone` 에도 스프린트 상태 검사가 없다. 즉 **COMPLETED 스프린트 안의 카드 위에 놓으면 `assign` 이 나간다**. **데이터 손상은 없다** — `SprintController.kt:242` 가 `409 — COMPLETED 스프린트 할당 불가` 로 막는다. 다만 드롭 가능해 보이는데 반드시 실패하는 UX 결함이다. **선재 확정** — `BacklogCard.tsx` 는 F15 에서 무변경이고 `origin/main` 도 동일하다. 단 **세로 스택 전환으로 COMPLETED 섹션이 항상 보이게 되어 도달 가능성이 올라갔다**. 처방 후보는 ① 카드 droppable 에 완료 여부를 넘기거나 ② `resolveOverToDropZone` 에 상태 검사를 넣는 것.
- **모바일 셸 반응형 (선재 결함 · F15 구현 중 발견, 범위 밖).** 375px 에서 본문 폭이 **111px** 로 짜부라진다 — 셸 사이드바(264px)가 md 미만에서 접히지 않는다. **F15 가 건드리지 않은 보드·이슈 목록 화면도 동일**해 선재 결함이 확정이며, 수정 대상이 `apps/web/src/components/layout/` 이라 F15·F16 범위 밖이다. 세로 스택 전환으로 증상 모양만 바뀐다(전 288px 고정폭 가로 스크롤 → 현 섹션 111px 짜부라짐).

#### 🆕 F16 이 남긴 후속 — 등재 12건 / **완료 1 · 잔여 11** (전부 **차단 아님**)

2026-08-06 PR #344 머지 시 ①~⑧ **8건 등재** → 후속 ⑦ 처리(PR #345) 중 **⑨·⑩ 2건 추가** ·
**⑦ 완료** → PR #345 코드리뷰 CONCERNS 봉합 중 **⑪ 1건 추가** → PR #345 **재리뷰 중 ⑫ 1건 추가**.
머지 전 눈확인(라이트/다크 6항목)과 코드리뷰 2종이 ①~⑧ 을 적발했고, **차단 사유 0건**으로
판정해 이월했다.
착수 시 **줄번호를 재측정할 것** — 이 FR 은 정본 수치가 실측에 뒤집힌 사례가 **5건** 있다
(F5 줄번호 · F15 `:246,248` · F16 「슬롯 그대로 쓴다」 · ⑦ 「복사본 2벌」 · ⑫ `tsconfig` 줄번호).

**시각 (눈확인 적발 · 실측 수치 동반)**
- **① 320px 폼 오버플로.** 백로그 칸 헤더의 스프린트 생성 폼 우측이 헤더 우측을 **54px 초과**한다
  (`main.scrollWidth 286 > clientWidth 256`). **375px 은 0.7px 여유로 통과.** 같은 폭에서
  `SprintColumnHeader` 도 3행으로 터지므로 위 「모바일 셸 반응형」과 **한 건으로 묶어** 처리하는
  편이 낫다.
- **② 다크 체크박스 대비 2.64:1.** `Checkbox` 체크 상태 `--brand-hover`(`#0055CC`) vs 패널 배경
  (`#161A1D`). WCAG 1.4.11 이 UI 컴포넌트에 요구하는 **3:1 미달**(라이트는 6.62:1). `Checkbox`
  프리미티브가 24종 중 **F16 이 첫 소비처**라 드러났다 — F16 코드가 아니라 `DESIGN.md` 색 토큰 소관.
- **③ 네이티브 vs shadcn 체크박스 다크 불일치.** `FilterBar.tsx` 의 `미배정` 은 원시
  `<input type="checkbox">`, 에픽 패널은 shadcn `Checkbox` 인데 **프로젝트 어디에도
  `color-scheme: dark` 선언이 없어** 다크에서 미배정만 새하얀 네모로 뜬다. **선재**지만 F16 이
  둘을 한 화면에 나란히 놓아 처음 눈에 띄었다.
- **④ 필터바 baseline 48px 단차.** `FilterBar.tsx` 의 `items-end` + 담당자 블록이 `미배정`
  체크박스까지 품어 더 높은 탓에, `백로그 검색` 입력의 **위 끝(205)이 담당자 입력의 아래 끝(201)보다
  낮다** — 세로 겹침 0이라 「살짝 어긋남」이 아니라 **다른 줄에 있는 것처럼** 보인다. 이 화면에서
  가장 눈에 띄는 미관 결함. **단 `FilterBar` 는 보드·이슈 목록도 쓰는 공유 컴포넌트**라
  `items-end` 를 건드리면 회귀 위험이 있다 — `leadingSection` 슬롯만 자기 정렬을 갖게 하는 국소
  처방이 가능한지 검토.

**구조 (코드리뷰 적발)**
- **⑤ 라벨 5종이 컴포넌트 모듈 잔류.** `EPIC_PANEL_TITLE`·`EPIC_LIST_ARIA_LABEL`·
  `EPIC_SCOPE_NOTICE`·`BACKLOG_FILTERED_EMPTY_TITLE`·`BACKLOG_FILTER_RESET_LABEL`. 3종은
  `fcf93d9c6` 로 `i18n/backlog-labels.ts` 에 옮겼으나 나머지는 남았다. 실제 비용 —
  `i18n/__tests__/create-entry-point-names.test.ts` 가 **순수 i18n 판별식인데**
  `@/components/backlog/BacklogBoard` 를 import 하느라 React·dnd-kit 트리를 통째로 끌어온다.
- **⑥ 숨긴 섹션의 훅이 계속 돈다.** `FilterBar` 의 `hiddenSections` 는 **렌더만** 끄고
  `useComponents(projectKey)`·`useUsers` 는 무조건 실행된다. 백로그를 열면 **영원히 안 쓰이는**
  컴포넌트 목록 조회가 1건 나간다. 같은 PR 의 `SprintDialogHost` 는 "요청 1건 증가"를 이유로
  조건부 마운트를 택했는데 **같은 기준을 필터바에는 적용하지 않았다.**
- **⑦ C4 짝 테스트에 대조 장치가 없다.** ✅ **완료 (PR #345 · 2026-08-06)**.
  `BacklogFilterBar.test.tsx`(부재 0) ↔ `BacklogEpicPanel.test.tsx`(존재 4) 의 셀렉터 헬퍼가
  **글자 단위로 같지만 복사본 2벌**이고 동기화 강제는 주석 한 줄뿐이었다. 한쪽만 고치면
  → 나머지 테스트만 red → 그쪽만 고침 → **필터바 쪽은 존재하지 않는 role 을 0개 세며 영구 초록**.
  관련 [[two-lists-never-check-each-other]].
  **★ 이 항목의 원래 서술이 두 군데 틀렸다 — 이 FR 에서 4번째다** (F5 줄번호 · F15 `:246,248` ·
  F16 「슬롯 그대로 쓴다」에 이어).
  **(가) 「복사본 2벌」이 아니라 8개였다.** 헬퍼가 참조하는 이름 상수 6개도 두 파일에 각각
  따로 정의돼 있었다. role·헬퍼 2개만 옮겼으면 나머지 6개가 어긋난 채 남아 이 FR 이 3연속으로
  맞은 **「봉합이 절반」**의 네 번째 판이 됐을 것이다.

  | 심볼 | 구 `BacklogEpicPanel.test.tsx` | 구 `BacklogFilterBar.test.tsx` | 값 |
  |---|---|---|---|
  | `EPIC_CONTROL_ROLE` | `:83` | `:116` | `'checkbox'` |
  | `queryEpicControls()` | `:86-96` | `:119-129` | 본문 동일 |
  | `EPIC_ALPHA` | `:40` | `:60` | `'ATLAS-100'` |
  | `EPIC_BETA` | `:41` | `:61` | `'ATLAS-200'` |
  | `EPIC_UNRESOLVED` | `:43` | `:63` | `'ATLAS-900'` |
  | `EPIC_ALPHA_NAME` | `:45` | `:65` | `'결제 개편'` |
  | `EPIC_BETA_NAME` | `:46` | `:66` | `'알림 리팩터'` |
  | `NO_EPIC_LABEL` | `:26` | `:58` | `backlogLabels.filter.noEpic` (값은 i18n 정본 파생이라 안전 · 바인딩만 중복) |

  **(나) 「패널이 Radix 메뉴로 바뀌면」이라는 가정법이 이미 사실이었다.** 같은 저장소의
  `ColumnSelector` 가 Radix 메뉴라 `menuitemcheckbox` 를 쓴다
  (`ColumnSelector.test.tsx:49,56,64,68,81,89` · `issues.index.test.tsx:1243,1264`).
  `BacklogEpicPanel` 은 shadcn `Checkbox`(`BacklogEpicPanel.tsx:221`)라 `checkbox` 다. 즉
  **「목록에서 여러 개 고르기」라는 같은 성격 UI 두 개가 서로 다른 a11y role 로 이미 공존한다** —
  ⑦ 의 위험 서술은 과대가 아니라 **과소평가**였다. 다만 role 은
  `docs/design/jira-parity-contract.md` 에 **명시 0건**(「에픽」 언급 자체가 0건)이라 제품 계약이
  아니라 **선택한 프리미티브의 부산물**이고, 그래서 공유 정본을 프로덕션이 아니라 **테스트 공간**에
  뒀다.

  **처방 (프로덕션 0줄).** 셀렉터 자산 8종을 테스트 전용 공유 모듈
  **`apps/web/src/test/backlog-epic-control-contract.ts`** 로 승격해 두 파일이 `import` 만 하게
  했고, 로컬 복사본 부활은 판별식
  **`apps/web/src/components/backlog/epic-control-contract.test.ts`**(6건 — 지역정의 봉인 2 ·
  **named import 목록 대조 2** · **양방향 차집합 1** · 비-공허 자체검증 1)이 차단한다. 커밋
  `b176fb21f`(RED) → `1ca433394`(GREEN) → `9cb6c3b0f`(코드리뷰 봉합). 유닛
  **571파일 / 9,237건 전량 통과** (기준선 570/9,231 + 판별식 파일 1 + 테스트 6).

  **★ 그 「차단한다」가 처음에는 거짓이었다 — 봉인 자체가 영구초록이었다 (코드리뷰 2종 적발 ·
  `9cb6c3b0f` 봉합).** 초판의 「공유 계약 모듈에서 읽는다」 단언이
  `source.includes(CONTRACT_MODULE)` **부분 문자열 일치**였는데, 같은 PR 이 두 짝 테스트에 직접
  넣은 **주석**(`// 셀렉터 자산은 '<모듈>' 가 단독 소유한다.`)이 그 경로를 담고 있어 조건을
  **영구 만족**시켰다. 실측 — **import 문을 통째로 지워도 6/6 green** 이었고, 나아가 8종을
  이름만 바꿔(`EPIC_CONTROL_ROLE`→`EPIC_CTRL_ROLE`) 로컬 복사본으로 되살려도
  **판별식 6/6 · `tsc` EXIT=0 · 짝 테스트 58/58 전부 green** — 이 시나리오의 감지기가 **0개**였다.
  `hasLocalDefinition` 도 `const`/`function` 두 형태만 봐 `let`·`var` 가 우회했다.
  처방은 `importedContractSymbols()` 로 `import { … } from '<모듈>'` 블록의 **지정자만 뽑아
  선언 8종과 정확히 대조**하는 것(+ `(?:const|let|var)` 확장). 봉합 후 뮤테이션 3종
  (import 삭제 · 이름 바꾼 복사본 · `let` 지역정의) **전부 red** 를 실측했다.
  **교훈 — 뮤테이션 검증의 대상은 「판별식이 무는가」만이 아니라 「판별식이 **무엇으로** 무는가」다.**
  뮤테이션 B(로컬 복사본 1줄 부활)는 **지역정의 봉인**이 물어서 red 였고, 그 성공이
  **바로 옆 단언이 죽어 있다는 사실을 가렸다.** 판별식이 여러 개면 뮤테이션마다 **어느 단언이
  red 인지**를 확인해야 한다. 관련 [[two-lists-never-check-each-other]] ·
  [[unreachable-state-fixture-is-fake-green]].
  **드리프트는 이론이 아니라 이미 진행 중이었다** — 두 복사본의 KDoc 이 갈라져 있었다
  (`EPIC_UNRESOLVED` 설명이 패널은 「조회 상한을 넘은」, 필터바는 「아직 로딩 중인」).
  **값보다 설명이 먼저 갈렸다.**

  **★ 뮤테이션 D — 판별식은 `tsc` 의 중복 안전망이 아니다.** 뮤테이션 4종(A 런타임 공유 ·
  B 판별식 비-공허 · C 양방향 차집합 · D 중복성 반증) 전부 성공했는데, **D 가 이 판별식의
  존재 이유다.** 타입 충돌이 **없는** 깔끔한 로컬 복사본을 되살리자 `tsc --noEmit` 이 `EXIT=0`
  이고 기존 25건도 전부 green 인데 **판별식만 red** 였다. 즉 **이 시나리오에서 감지기는
  판별식 하나뿐**이다 — 나중에 「타입 검사가 잡아 주니 지워도 된다」는 이유로 이 파일을
  삭제하려는 시도가 있으면 이 문단이 반증이다.
- **⑧ 픽스처 뷰 간 불일치.** 백로그는 `ATLAS-1 ∈ ATLAS-EPIC-A` 라고 말하는데 같은 이슈의 상세
  픽스처 `mocks/issue-fixtures.ts` 에는 `epic` 필드가 없다(`IssueResponse` 는 그 필드를 정의한다).
  **두 화면이 서로 다른 말을 하는데 대조 장치가 없다.** 현재 소비처 0이라 무해.

**신규 (후속 ⑦ 처리 중 발견 · PR #345 · 2026-08-06)**
- **⑨ 에픽 패널(`checkbox`) ↔ 컬럼 셀렉터(`menuitemcheckbox`) a11y role 불일치.**
  「목록에서 여러 개 고르기」라는 **같은 성격의 UI 두 개**가 서로 다른 role 로 렌더된다 —
  `BacklogEpicPanel` 은 shadcn `Checkbox`(`BacklogEpicPanel.tsx:221`), `ColumnSelector` 는
  Radix 메뉴(`ColumnSelector.test.tsx:49` 외 5곳 · `issues.index.test.tsx:1243,1264`).
  **접근성·Jira 패리티 관점의 디자인 일관성 문제**이지 테스트 배선 문제가 아니라 **PR #345
  범위 밖**이다(#345 는 현재 role 을 바꾸지 않고 **사실로 고정**만 했다). 착수하려면 어느 쪽이
  정본인지부터 정해야 하고, `docs/design/jira-parity-contract.md` 에 에픽 컨트롤 role 명시가
  **0건**이라 그 결정이 곧 계약 신설이다.
- **⑩ 필터바 부재 단언의 개별 공허성 — 감지기가 단 1개다.** ⑦ 이 **파일 수준 영구초록**은
  해소했으나(공유 role 을 바꾸면 필터바 파일이 red 로 떨어진다), **단언 수준에서는 여전히
  감지기가 하나뿐**이다. 뮤테이션 A(공유 role → `menuitemcheckbox`)에서
  `BacklogFilterBar.test.tsx` **25건 중 red 가 된 것은 `:245` 한 줄**
  (`getByRole(EPIC_CONTROL_ROLE, { name: 미배정 })` — S3a 의 비-공허 짝)뿐이었다.
  나머지 부재 단언(`queryEpicControls()).toHaveLength(0)` — `:247`·`:254`)은 **존재하지 않는
  role 을 0개 세므로 뮤테이션 하에서도 green** 이다. 즉 **그 한 줄이 지워지거나 「미배정」이
  체크박스가 아니게 되는 순간 필터바는 role 드리프트에 다시 무감각해진다.** 처방 후보 —
  ① 각 부재 단언마다 같은 트리에서 살아 있는 대조 단언을 짝지어 두거나, ② 「이 트리에 해당 role 이
  최소 1개 존재한다」를 `beforeEach` 급으로 올리는 것, ③ **「미배정」 필터의 role 을 별도 상수로
  분리해 `:245` 가 에픽 role 카나리아를 겸하지 않게 하는 것.**
  **③ 이 필요한 이유 (PR #345 코드리뷰 봉합 중 확정).** `:245` 가 `EPIC_CONTROL_ROLE` 을 거는
  대상은 `FilterBar` 소유의 **「미배정」 체크박스**이지 에픽 컨트롤이 아니다(선재, PR #344).
  즉 **소유자가 다른 두 값이 한 상수를 공유**하고 있어, ⑨ 를 처리해 패널이 정당하게
  `menuitemcheckbox` 로 가는 날 그 줄은 **거짓 red** 가 된다. 그때 가장 자연스러운 대응이
  「그 줄만 `'checkbox'` 리터럴로 되돌리기」인데 그것이 곧 **⑦ 이 없앤 영구초록의 부활**이다.
  이 함정은 `backlog-epic-control-contract.ts` 의 `EPIC_CONTROL_ROLE` KDoc 에도 못박아 뒀다.
  관련 [[unreachable-state-fixture-is-fake-green]].
  **⚠️ ③ 을 하려면 ⑫ 를 먼저 해소해야 한다** — `:245` 가 `EPIC_CONTROL_ROLE` 의 **필터바 쪽
  유일한 사용처**라, 별도 상수로 갈라내는 순간 사용처가 0이 되어 `tsc`(`noUnusedLocals`)와
  ⑦ 판별식(8종 정확 대조)이 **동시에** 막는다. 탈출 경로 4개가 전부 막혀 있다(⑫ 표).
- **⑪ 판별식의 `PAIRED_TEST_FILES` 가 하드코딩 2개다 — 제3 소비처를 안 본다.**
  `epic-control-contract.test.ts:17` 이 짝 테스트 2파일을 이름으로 적어 두므로, 나중에 세 번째
  파일이 같은 셀렉터를 로컬 복사본으로 들고 와도 봉인이 **그 파일을 아예 열지 않는다**.
  **차단 아님 · 현재 제3 소비처 0건**이고, 파일명이 바뀌면 `readFileSync` 가 `ENOENT` 로 던져
  **안전하게 red** 라 조용한 실패는 없다. **PR #345 에서 의도적으로 고치지 않았다** —
  자동 탐색(`readdirSync`)으로 바꾸면 무관한 테스트 파일을 긁어 새 실패 모드를 만든다.
  착수 조건은 **제3 소비처가 실제로 생기는 시점**이고, 그때도 탐색 범위를 「공유 모듈을 import 하는
  파일」로 한정하는 편이 안전하다.

**신규 (PR #345 재리뷰 중 발견 · 2026-08-07)**
- **⑫ 공유 계약 모듈의 export 는 「두 짝 파일이 **둘 다 실제로 써야** 한다」 — ⑩③ 이 여기 걸린다.**
  ⑦ 봉합(`importedContractSymbols()` + `toEqual` **정확** 대조)이
  `tsconfig.app.json:14` `noUnusedLocals: true` 와 맞물려 **어디에도 서술되지 않은 강한 불변식**을
  만들었다. 판별식은 두 짝 파일이 **8종 전부를 named import** 하기를 요구하고
  (`epic-control-contract.test.ts:80-83`), 타입 검사는 **import 해 놓고 안 쓰면** `TS6133` 으로
  문다. 두 규칙의 교집합이 곧 **「모든 export 를 두 파일이 둘 다 실제 사용」** 이다 —
  ⑦ 이 내건 「로컬 복사본 금지」보다 **훨씬 센 제약**인데 코드에도 정본에도 서술이 0건이었다.
  (재리뷰 실측 정정 2건 — dispatch 는 `tsconfig.app.json:24`·「패널 20곳」이라 했으나 실측은
  **`:14`** 와 **22곳**이다. `grep -o` 23줄 중 `:14` 는 import 지정자. 이 FR 의 정본 수치가
  실측에 뒤집힌 **5번째** 사례.)

  **⑩ 의 처방 후보 ③ 과 정면 충돌한다.** ③(「미배정」 필터 role 을 별도 상수로 분리)을 하면
  `BacklogFilterBar.test.tsx` 의 `EPIC_CONTROL_ROLE` **사용처가 0** 이 된다 — 실측상 사용처는
  `:245` **단 한 줄**뿐이고 `:16` 은 import 지정자다(`grep -o` 2건). 재리뷰가 탈출 경로 4개를
  **전부 태워 봤고 합법 상태가 없었다.**

  | 시도 | 결과 |
  |---|---|
  | (a) import 유지 | `tsc` RED `TS6133 'EPIC_CONTROL_ROLE' is declared but its value is never read` |
  | (b) import 지정자 제거 | 판별식 RED (`… 8종을 모두 named import 한다`) |
  | (c) `SHARED_SYMBOLS` 에서도 제거 | 판별식 **RED 3건** (import 2 + 양방향 차집합 1) |
  | (d) 공유 모듈 export 까지 제거 | 판별식 RED 2 + `tsc` `TS2459` — `BacklogEpicPanel.test.tsx` 가 22곳에서 쓴다 |

  **⚠️ RED 건수는 조합에 따라 달라진다 — 착수 시 다시 재라.** (c)·(d) 는 **두 파일이 8종을 그대로
  import 한 상태**에서 잰 값이다. 판별식의 import 대조는 `it.each(PAIRED_TEST_FILES)` 라 **파일당
  1건**이므로, (b) 를 누적해 필터바 import 까지 뺀 조합이면 필터바 쪽은 7 ⟺ 7 로 통과해 (c) 는
  **2건**이 된다. (d) 에서 양방향 차집합이 살아나는 것도 같은 이유다(선언 7 ⟺ export 7).

  **해소하려면 판별식 자체를 손봐야 한다.** 처방 후보 —
  ⓐ `SHARED_SYMBOLS` 단일 목록을 **파일별 기대 목록**으로 쪼갠다
  (`{ 'BacklogEpicPanel.test.tsx': […8], 'BacklogFilterBar.test.tsx': […7] }`). 정확 대조를
  유지하므로 봉인 강도는 안 떨어지지만 목록이 2벌이 되어 대조면이 하나 늘고, 양방향 차집합은
  **합집합 ⟺ export 표면**으로 다시 걸어야 한다([[two-lists-never-check-each-other]]).
  ⓑ 정확 대조(`toEqual`)를 **부분집합 대조**로 완화한다. 한 줄이면 되지만 **「선언했는데 아무도
  안 쓰는 심볼」을 다시 놓아준다** — ⑦ 이 없앤 구멍의 절반이 되돌아오는 trade-off 다.

  **차단 아님.** 양방향 모두 **시끄럽게 실패**하고(판별식 red 또는 `tsc` red) **조용한 green 이
  0건**이며 **프로덕션 영향 0**이다(전부 `*.test.*`·`src/test/` 공간). 다만 **⑩ 을 착수하는
  사람은 이 항목을 반드시 먼저 읽어야 한다** — 모르고 들어가면 위 4칸을 순서대로 다시 태우게 된다.

**부수 — 에픽 이름 폴백 3상태 미구분** (수용 가능으로 판정, 기록만).
`use-backlog-epics` 는 ① 조회 중 ② 조회 실패 ③ **상한 50 초과** 를 전부 **키 표시**로 합친다.
사용자는 「곧 로딩되겠지」와 「영원히 안 나온다」를 구분할 수 없다. 에픽 목록 API 부재라는 제약상
최선이나, 「이름을 못 불러왔습니다」 같은 구분 표시는 후속 후보.

> **⚠️ 후속으로 열지 **않은** 것 1건 (오검 정정).** 코드리뷰가 *"pre-commit 훅과 `pnpm lint` 가
> 서로 다른 eslint 결과를 낸다"* 를 선재 구조 결함으로 올렸으나 **실측으로 반증됐다.**
> 리뷰어는 **루트 설정의 명령줄을 손으로 흉내 내** 재서 error 1건을 봤는데, 설정이 **2벌**이라
> `apps/web` 파일은 `apps/web/.lintstagedrc.json` 이 잡고 **cwd 가 `apps/web`** 이 된다(위
> §4.9 「★봉합이 만든 신규 충돌면」에서 이미 처방된 상태). **결정적 증거** — F16 T2 가 PR22 예외
> 목록에 실재하는 `components/filters/FilterBar.tsx` 를 **`--no-verify` 없이** 커밋했고 훅이
> 정상 통과했다(`100557bc7`). **지적은 채택하되 처방은 검증한다**([[seal-blinds-existing-guard]])의
> 반대 방향 사례 — 지적 자체가 거짓일 수도 있다.

> **★ 왜 완료를 차단하는가 (F15 착수 중 실측 · ADR `2026-08-05-fr-ux-13-f15-backlog-vertical-stack.md`).**
> `SprintRepository.unassignIssue` 는 `WHERE … AND EXISTS (SELECT 1 FROM sprints WHERE id = ? AND status <> 'COMPLETED')` **조건부 DELETE** 라 COMPLETED 스프린트에서는 **아무 행도 지우지 않고 204** 를 준다(실패가 아니라 침묵). 그리고 `V503__sprints.sql` 의 `CONSTRAINT sprint_issues_issue_key_unique UNIQUE (issue_key)` 때문에 한 이슈는 전역에서 한 스프린트에만 속하고, 스키마 주석이 "다른 스프린트로 재할당하려면 먼저 제거해야 한다"고 못박는데 **그 제거가 위에서 막힌다**. 결과 — **완료된 스프린트에 남은 이슈는 꺼낼 수도 옮길 수도 없다.** 「이관 먼저」·「부분 실패 시 완료 중단」·「truncated 면 완료 차단」이 전부 이 한 줄에서 파생된다.

**아키텍처**. **프론트 전용 확정** (F5 #342 · F15 #343 · F16 #344 3PR 전량 `backend/` 변경 **0파일**). 로드맵 **임계경로의 종점**이다(`B2 → F14 → F15 → F16`). **★ 재작성 산정 대상 행수는 정본이 세 번 틀린 자리다** — 원래 적혀 있던 `backlog.spec.ts`(536행)·`BacklogBoard.test.tsx`(759행)는 F15 가 두 파일을 키운 뒤 갱신되지 않은 값이었고, F16 착수 시점 실측은 **1,521 / 1,702행**(2.8배 · 2.2배), F16 완료 시점 실측은 **2,490 / 2,572행**(2026-08-06 `wc -l`)이다. 정본 줄번호·행수는 착수 때마다 다시 재는 것이 이 FR 의 규율이다. 키보드 DnD 공지는 `KanbanBoard.tsx:540` `buildDragAnnouncements`(한국어 4종, 조사 처리까지 완성)를 재사용한다. 스프린트 완료 시 미완료 이슈 이관 선택은 `SprintController.kt:221` `complete(id)` 에 이관 파라미터가 없어 **범위 밖** — v1 은 프론트가 완료 전 `DELETE /sprints/{id}/issues/{key}` 를 반복한다.

- [x] D1. 도메인 — 백로그 세로 스택의 섹션 모델(백로그 · 스프린트 N개)과 스프린트 시작/완료 상태 전이 정립 (책임. frontend-engineer) — **F15 #343 + F16 #344**. 섹션 모델은 「스프린트 N개 위 · 백로그 아래」 세로 스택으로 확정하고 **클라이언트 정렬을 두지 않는다**(백엔드 `sprintComparator` 가 유일 정렬 소유자 — 규칙을 두 벌로 가르지 않기 위함). 상태 전이는 `PLANNED → ACTIVE → COMPLETED` **단방향 FSM** 이고 **COMPLETED 는 흡수 상태**다(위 인용 블록 — 남은 이슈는 꺼낼 수도 옮길 수도 없다). F16 이 그 위에 **「표시 집합 ≠ 동작 집합」** 을 더했다 — 필터는 섹션의 **표시만** 좁히고 스프린트 완료 이관·DnD 의 **대상 집합은 원본 그대로**다. 신규 도메인 개념·용어는 **0건**(`Maxi_wiki/BTS/glossary.md` 갱신 불필요)
- [x] D2. 명세 — 세로 스택 레이아웃 · 스프린트 시작/완료 다이얼로그 · 키보드 DnD · 필터바/에픽 패널 (책임. designer) — **F5 #342 · F15 #343 · F16 #344**. spec 3건(`docs/specs/2026-08-05-fr-ux-13-f5-backlog-card-assignee.md` · `2026-08-05-fr-ux-13-f15-backlog-vertical-stack.md` · `2026-08-06-fr-ux-13-f16-backlog-filter-epic.md`). F16 분은 **FR 12 · NFR 5 · 엣지 9 · S1~S8** + Jira 대조 **5갭**(G1~G5) + **의도적 이탈 2건**(에픽 패널은 「백로그에 이슈가 있는 에픽」만 · 에픽 색상 막대 없음) + **조합 위험 6건**(3결정 합산 되짚기 — 최고 위험 R6 이 브랜드 타입 처방을 낳았다)
- [x] D3. 데이터 모델 — **없음 확정** (기존 스프린트/백로그 API 소비. 마이그레이션 0 · 신규 컬럼 0. 영속 상태는 브라우저 `localStorage` 의 섹션·패널 접힘뿐) (책임. -) — 3PR 전량 `backend/db/migration/**` 변경 **0파일**
- [x] D4. 백엔드 — **없음 확정** (미완료 이슈 이관 파라미터는 범위 밖 — 프론트가 기존 `DELETE /sprints/{id}/issues/{key}` 를 반복. 백로그 필터도 서버가 아니라 클라이언트가 건다) (책임. -) — 3PR 전량 `backend/` 변경 **0파일** (`git diff --name-only main...HEAD | grep -c '^backend/'` → **0**)
- [x] D5. 백엔드 테스트 — **해당 없음 확정** (백엔드 변경 0줄) (책임. -)
- [x] D6. 프론트 UI — F5 담당자·에러/로딩 봉합 · F15 세로 스택+스프린트 다이얼로그 · F16 필터바+에픽 패널 (책임. designer → frontend-engineer) — **F5 #342 + F15 #343 + F16 #344 로 완주.** F5 가 담당자 전원 `?` 와 빈 `<div/>` 를 봉합(`useUsersByIdsChunked` — 보드의 전체목록 방식을 복제했으면 `MAX_RESULTS = 50` 결함을 옮기는 것이었다), F15 가 가로 칸반을 세로 스택으로 뒤집고 시작/완료 다이얼로그와 키보드 DnD 를 얹었으며, F16 이 필터바·에픽 패널·스프린트 생성 폼 재배치·URL 왕복을 마무리했다. **신규 UI 프리미티브 0** — `FilterBar`·`FilteredEmptyState`·`components/ui/*` 재사용이고, 공유 `FilterBar` 변경은 **기본값 보존 선택적 prop** 한 개뿐이다
- [x] D7. E2E — `backlog.spec.ts` 재작성. "담당자 있는 카드는 이니셜 아바타"(현재 `?` 로 red) · 조회 실패 시 에러+재시도 (책임. qa-engineer) — **본문 2항목은 F5 #342 가 전량 충족**(이니셜 아바타 · S10 조회 실패 안내 · S11 재시도 성공 · S12 재시도 실패). F15 가 세로 스택·다이얼로그·키보드 DnD 를, F16 이 필터 축 4종·에픽 패널·URL 왕복·초기화·잘림 경고를 얹어 `backlog.spec.ts` 가 **536행 → 2,490행**이 됐다. ★F16 이 **커버리지 구멍 1건**을 스스로 적발해 봉합했다 — `DEFAULT_BACKLOG` 이슈 7건이 전부 `epicKey: null` 이라 간판 기능인 「이름 있는 에픽으로 좁히기」에 **e2e 증인이 아예 없었다**. 이슈 총수 7 을 유지한 채 2건에 에픽을 붙였고(기존 테스트 파손 0), tripwire 를 **삭제가 아니라 방향을 뒤집었다**(「에픽이 생기면 알려라」 → 「에픽 2종·섹션 분산·이름≠키·미지정 대조군이 무너지면 알려라」) — 셋 중 하나만 무너지면 테스트는 **초록인 채로** 재는 것만 조용히 줄어든다

### §4.12 FR-UX-14 — 이슈 카드 밀도

**우선순위**. 높음 | **선행**. 없음 (로드맵상 B2 의존 0) | **Plan slug**. `fr-ux-14-card-density`

승계 PR 2건 (로드맵 §PR 체인 Tier 2 F14 + 백엔드 B2). 지금 보드 카드는 **3필드(제목·키·담당자)뿐**이라 지라 카드에 비해 한눈에 읽히는 정보가 없다.

- **F14 — 보드/백로그 카드 밀도**(유형 아이콘 · 라벨 칩 · 추정). `BoardCard.tsx:103-136` · `BacklogCard.tsx` · `api/boards.ts:35-66` · `components/issue/IssueTypeIcon.tsx` **재사용**(epic/story/task/subtask/bug lucide 매핑 + `role="img"` 완비).
- **B2 — 백엔드. 보드/백로그 카드 필드**(`shared-kernel` + `issue-tracking` + `agile-planning`). **F14 의 유일한 차단점** — `BoardCardResponse` 에 타입·라벨·추정이 없다.

**아키텍처**. **B2 의 지위 정정 (2026-07-29).** 2026-07-28 Maxi 결정 #3 의 *"B2 = chore"* 를 승계·정정해 **이 FR 의 D4/D5** 로 승격한다(사유는 §4.5 의 세 번째 인용 블록, §4.7 FR-UX-09 의 B1 과 동일). 3모듈 동시 변경은 **선례 커밋 `dcbf130e6`**(shared-kernel 4 + agile-planning 6 + issue-tracking 3 파일 — 같은 조합으로 보드 필터 필드를 추가한 PR)을 템플릿으로 삼는다. 라벨은 `TEXT[]` 컬럼이라 조인이 불필요하고 타입은 `listWithType` 이 이미 조인 중이므로, **N+1 회귀 가드**가 성공 판정식이다.

- [x] D1. 도메인 — 보드/백로그 카드가 노출할 필드 집합 정립 (책임. backend-engineer) — **B2 #346**. 노출 3필드는 `typeKey`·`labels`·`originalEstimateSeconds` 로 확정했다(Maxi 2026-08-07). **`typeIconName`·`typeName` 은 제외** — 소비자 `IssueTypeIcon` 의 props 계약이 `{iconName, typeName}` 이고 그 값은 프론트가 **기존 타입 목록 API**(`apps/web/src/api/issue-types.ts`)에서 이미 얻고 있다. 이슈 상세 화면이 정확히 그 방식이고(`IssueMetaPanel.tsx:272`), 자매 포트 `TimelineItemView.issueType` 도 식별자만 담는다. 신규 도메인 용어 **0건**
- [x] D2. 명세 — 응답 필드 계약(**B2 #346**) + 카드 밀도 디자인 스펙(**F14 #349**) (책임. designer). 계약 분은 [spec](../../specs/2026-08-07-fr-ux-14-b2-card-fields.md)(FR1~FR10 · NFR1~NFR5 · 엣지 E1~E10) + [ADR](../../decisions/2026-08-07-fr-ux-14-b2-card-fields.md)(D-1~D-4). 밀도 스펙은 [spec](../../specs/2026-08-07-fr-ux-14-f14.md) §1 Jira 대조(대응 화면 **있음** → Jira 배치 승계, Maxi 확정) + FR1~FR17 · NFR1~NFR7 · E1~E10. **D 마커는 완주 단위**라 두 절반이 다 끝난 지금 닫힌다(§4.11 ADR §D6 선례 승계). ★plan-design-review 가 **결함 2건 적발** — 라벨 칩이 제목과 같은 글자 무게(→ `text-muted-foreground`), 접힌 라벨을 `title` 로만 알림(→ 터치엔 hover 가 없어 접근성 이름에 전문 수록)
- [x] D3. 데이터 모델 — **없음 확정** (마이그레이션 0 · 신규 컬럼 0) (책임. -) — **B2 #346**. 필요한 컬럼이 이미 전부 있었다 — `issues.labels TEXT[] NOT NULL DEFAULT '{}'`(V006) · `issues.original_estimate_seconds INT NULL`(V027) · `issue_types.key`(V003). `git diff --name-only main...HEAD | grep -c 'db/migration'` → **0**
- [x] D4. 백엔드 — **B2 #346**. `shared-kernel .../board/BoardIssueLookupPort.kt` 의 `BoardIssueView` 8→11필드(`typeKey` 필수 · `labels`=`emptyList()` · `originalEstimateSeconds`=`null`) + `issue-tracking .../repository/IssueRepository.kt` `listVisibleForBoard` 에 **ISSUE_TYPES INNER JOIN** + `BoardIssueEntry.typeKey` + 어댑터 매핑 + `agile-planning .../web/dto/BoardResponses.kt`(7→10) · `BacklogResponses.kt`(8→11) (책임. backend-engineer). **★정본 전복 2건** — ⑴ 「타입은 `listWithType` 이 이미 조인 중」은 **경로 혼동**이다(그건 이슈 목록 경로이고, 보드 경로는 `IssueRepository.kt` 에 *"type JOIN 생략"* 을 명문화해 뒀다). ⑵ 「보드 카드 SELECT 확장」도 부정확 — 라벨·추정은 `ISSUES.fields()` 로 **이미 조회 중**이었고 어댑터 매핑에서만 버려지고 있었다. 실제 쿼리 변경은 **type JOIN 하나뿐**이다
- [x] D5. 백엔드 테스트 — **B2 #346**. 계약(`BoardPortContractTest` 필드 계약 정본 + 기본값 규약) · 실 DB 대조군(`BoardIssueLookupAdapterTest` D1/D2 — 유형 3종 · 라벨 2종+빈 · 추정 2종+null 을 **키 단위 대조**) · **N+1 회귀 가드**(신규 `BoardCardQueryCountTest` — 카드 3건/33건에서 SQL 문 수 1 로 동일) · 응답 JSON(보드·백로그 양쪽) (책임. backend-engineer). **★가드 2건 모두 비-공허 확인 완료** — 어댑터 `labels` 매핑을 `emptyList()` 로, 저장소 fetch 람다에 카드당 조회를 각각 임시로 넣어 **두 테스트가 실제로 FAILED 되는 것을 확인**하고 역방향 Edit 으로 되돌렸다
- [x] D6. 프론트 UI — **F14 #349**. `BoardCard`·`BacklogCard` 하단 행을 `[유형아이콘][키] … [추정][담당자]` 로 재배치 + 라벨 칩 행 신설 + `api/boards.ts`·`api/backlog.ts` 스키마 3필드 **엄격 필수**화 (책임. frontend-engineer). 공용 조각 2종 신설 — `components/issue/CardLabelChips.tsx`(최대 3 + `+N`) · `CardEstimateBadge.tsx`(`formatSeconds` 위임) · 문자열 정본 `i18n/card-labels.ts`. 유형 해석은 라우트/`BacklogBoard` 가 `useIssueTypes()` **1회** 호출 후 맵을 내려보내고, 카드는 **원시 prop 2개**(`typeIconName`·`typeName`)만 받아 `memo` 를 유지한다. ★**`.default()` 금지 결정의 대가는 29파일 보강**이었다(계획 시점 grep 추정 ≈15). 「컴파일러가 뽑은 목록이 정본」이 실제로 작동했다
- [x] D7. E2E — **F14 #349**. 신규 `e2e/card-density.spec.ts` 4시나리오(3요소 노출 · 라벨 4개 `+N` 접힘과 접근성 이름 · 라벨/추정 부재 시 DOM 미생성 · 백로그 동형) (책임. qa-engineer). ★**회귀 2건 동반 봉합** — 라벨 행으로 카드가 높아지자 `dragCardOntoCard` 의 좌표-정밀 드래그가 @dnd-kit 자동 스크롤 드리프트에 걸렸다. `backlog.spec.ts` 의 `bringPairIntoView`(F15 Task 10, 같은 근본 문제의 다른 화면판)를 이식해 견고화(test-only · 단언 비약화 0 · 픽스처 회피 0). **선재 4건은 손대지 않았다**(main 에서도 동일 실패 — `TODOS.md` 후보)

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

- [~] §2~§5 (21 FR) 모두 `[x]` 마킹 — **17/21** (2026-08-04 실측. 2026-07-31 §4.6 프로젝트 전환·최근 항목·내 작업 완주로 15종 → 08-03 §4.7 이슈 생성 흐름 완주로 16종 → 08-04 §4.9 인라인 편집 완주로 17종. 잔여 4종 = §4.8(D1~D5 만 완료 — D6/D7 은 상세 액션 단축키 대기) · §4.10 · §4.11 · §4.12)
      <!-- ★ 이 줄에 `FR-XX-NN` 형태를 쓰지 말 것 — verify-master-plan.sh 의 "§N 헤더 (FR-XX, N개)"
           스캐너가 헤더 선언으로 오인해 `N개` 파싱에 실패하고 EXIT 1 이 된다(2026-07-25 실제 발생). -->
- [ ] §NFR 측정표 모든 항목 임계 통과 — 미측정. 위 측정값 기록표 8행 전부 실측값이 `___` 공란. k6(프로필 조회·캘린더 30일·iCal Export) · Playwright(설정 적용·cmdk 응답) · E2E 전수(단축키) · Lighthouse CI(LCP) · axe-core(WCAG AA) 를 실제로 돌려 p95 를 채워야 한다 (2026-07-27 실측)
- [x] CHANGELOG.md 정리 — 2026-08-04 재실측: 저장소 루트 `CHANGELOG.md` 의 `[Unreleased] — Phase 1` §BC 요약 표에 personalization 행 존재 (21 FR 등록 / **17 완료** / 2026-07-05~08-04 / 대표 산출 8종 + 논리 BC 각주. 인터랙션 패리티 잔여 4종은 완료 시 추가)
- [ ] README.md §7 변경 이력에 "personalization BC 완료 — YYYY-MM-DD" 추가 — 🛑 Maxi 1인 선언 대기 (에이전트 수행 불가). 2026-07-27 실측: `docs/plan/README.md` §7 은 3행뿐이고 BC 완료 행 없음 — 이 행의 날짜가 곧 선언일이므로 선언 이전에는 기입 불가
- [ ] Maxi 1인 선언 — "personalization BC 완료" — 🛑 Maxi 1인 선언 대기 (에이전트 수행 불가)
