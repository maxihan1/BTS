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
- **F3 — 만들기 진입점 3곳**(보드 컬럼 · 백로그 섹션 · 목록 헤더) 배선. `BoardColumn.tsx` · `BacklogColumn.tsx` · `SprintColumn.tsx`. 필드는 F2 에서 이미 완결됐고 이 PR 은 **진입점만** 붙인다(ADR D-1).
- **B1 — 백엔드. 이슈 생성 시 담당자·우선순위·라벨** (`issue-tracking`). 지금은 create 후 PATCH 3회를 이어 붙여야 하고 **중간 실패 시 반쯤 만들어진 이슈가 남는다**(완제품 기준 위반). 생성 1회 제출로 확정한다.

**아키텍처**. **B1 의 지위 정정 (2026-07-29).** 2026-07-28 Maxi 결정 #3 의 *"B1 = chore"* 를 승계·정정해 **이 FR 의 D4/D5** 로 승격한다(사유는 §4.5 의 세 번째 인용 블록). 신규 다이얼로그는 `role="dialog"` 가 e2e 에 164발생이라 **고유 `aria-label`** 없이는 strict mode 충돌이 난다. 필드 컨트롤은 새로 만들지 말고 `components/issue/meta/` **8종**(Assignee·Priority·Labels·Type·Impact·Environment·CustomFields·StateTransition)을 재사용한다.

- [ ] D1. 도메인 — 생성 시점에 확정 가능한 필드 집합(유형·본문·담당자·우선순위·라벨) 정립 (책임. backend-engineer)
- [x] D2. 명세 — **PR #331 완료**. 모달 진입점 · 딥링크 라우트 유지 계약(라우트가 모달을 연다) · 필드별 optional 계약 · `assigneeId` 3-state 의 프론트 표현 · 상호작용 상태표. spec [`2026-08-01-fr-ux-09-f2-create-issue-dialog`](../../specs/2026-08-01-fr-ux-09-f2-create-issue-dialog.md) (책임. designer)
- [ ] D3. 데이터 모델 — 없음 예상 (기존 컬럼 소비. 마이그레이션 0) (책임. -)
- [x] D4. 백엔드 — **B1 완료 (PR #328)**. `CreateIssueRequest` 에 `assigneeId`(JsonNullable 3-state)·`priority`·`labels` 추가 + `AssigneeIntent` sealed 신설 + `IssueApplicationService.createIssue` 배선. 요청/응답 계약 무회귀(응답 스키마 diff 0). **단 알림은 무회귀 아님** — ADR D-4 로 `IssueAssigned` 신규 발행(REST 경로 한정, D-5) (책임. backend-engineer)
- [x] D5. 백엔드 테스트 — **B1 완료 (PR #328)**. `IssueApplicationRequestsTest`·`IssueApplicationServiceCreateTest`·`IssueControllerCreateTest`·`OpenApiContractTest`·`IssueImportAdapterTest`(S8b Import 알림 회귀 가드). 뮤테이션 M1~M3 전량 red 확인 (책임. backend-engineer)
- [ ] D6. 프론트 UI — **F2 완료 (PR #331)**. `CreateIssueDialog` + 프로젝트 셀렉터·유형·본문·담당자·우선순위·라벨 · `LabelChipsEditor` 추출 · 진입점 2곳(딥링크 라우트 · 상단바). **F3 진입점 3곳(보드·백로그·스프린트)이 남아 미완**이다 — D 마커는 완주 단위다 (책임. designer → frontend-engineer)
- [ ] D7. E2E — **F2 분 완료 (PR #331)**. `issue-create-dialog.spec.ts` 4 시나리오(상단바 URL 불변 · 딥링크 POST 1회/PATCH 0회 · 상단바 토스트 · 닫으면 `/issues`). **F3 진입점 3곳 E2E 가 남아 미완** (책임. qa-engineer)

### §4.8 FR-UX-10 — 컨텍스트 의존 단축키

**우선순위**. 높음 | **선행**. §4.3 (FR-UX-05) · §4.9 (FR-UX-11 — F11 이 F8 에 의존) | **Plan slug**. `fr-ux-10-context-shortcuts`

**정본이 이미 예약해 둔 범위다.** §4.3(FR-UX-05)이 *"컨텍스트 의존 단축키(`j/k/e/m/s`)는 **후속 FR로 제외**(Maxi 결정 2026-07-05)"* 로 명시 이연했고, 이 FR 이 그 **승계자**다. 현재 단축키는 전역 네비게이션 5종뿐이고 전부 "이동" 계열이라, 지라(25종+)를 쓰던 사람의 손이 기억하는 동작이 하나도 없다.

승계 PR 2건 (로드맵 §PR 체인 Tier 2).

- **F10 — 컨텍스트 단축키 아키텍처 + 목록 항법** `j`/`k`/`o`/`t`/`[`. 신규 `context-shortcuts.ts`·`useContextShortcuts.ts` · `ShortcutsHelpDialog.tsx` · `Sidebar.tsx`.
- **F11 — 상세 액션 단축키** `a`/`i`/`m`/`e`/`l`/`w`/`.`. `issues.$key.tsx` · `IssueMetaPanel.tsx` · `WatchersSection.tsx` · `CommentSection.tsx`.

**아키텍처**. 🛑 **`shortcuts.ts` 의 `SHORTCUTS` 를 건드리면 안 된다.** 여기에 키를 추가하면 `shortcuts.test.ts:121` `toHaveLength(5)` + `:147` `DEFAULT_KEYMAP` 완전일치 + 백엔드 `KeymapAction.kt` 5종 화이트리스트 + `user_keymap.action` CHECK 제약이 **동시에** 깨진다 — 이 4중 계약의 소유자는 §3.3 FR-PF-03 이다. 정답은 **`CONTEXT_SHORTCUTS` 별도 레지스트리 신설**이고, 성공 판정식은 "`shortcuts.test.ts:121` 이 **무수정 green** 을 유지" 다. 사용자 재배치(로드맵 B4)는 `KeymapAction` enum + `user_keymap` CHECK 신규 마이그레이션을 요구하므로 **v1 은 고정 키**로 출시한다.

- [ ] D1. 도메인 — 컨텍스트(목록/상세/보드) 별 단축키 레지스트리 개념 정립. `SHORTCUTS`(전역) 와의 분리 경계 (책임. frontend-engineer)
- [ ] D2. 명세 — 컨텍스트별 키 매핑 · 활성 컨텍스트 판정 · 입력포커스/IME 가드 · 도움말 모달 노출 (책임. designer)
- [ ] D3. 데이터 모델 — 없음 (단축키 정의는 프론트 코드 상수. 사용자 재배치는 B4 로 범위 밖) (책임. -)
- [ ] D4. 백엔드 — 없음 (v1 고정 키. `KeymapAction` enum·`user_keymap` CHECK 무변경이 계약이다) (책임. -)
- [ ] D5. 백엔드 테스트 — 해당 없음 (백엔드 변경 0) (책임. -)
- [ ] D6. 프론트 UI — F10 `CONTEXT_SHORTCUTS`·`useContextShortcuts` + 목록 항법 · F11 상세 액션 7종 (책임. frontend-engineer)
- [ ] D7. E2E — 컨텍스트별 발화 · `shortcuts.test.ts:121` `toHaveLength(5)` 무수정 green 유지 (책임. qa-engineer)

### §4.9 FR-UX-11 — 인라인 편집

**우선순위**. 높음 | **선행**. 없음 (로드맵상 의존 0 — 즉시 착수 가능) | **Plan slug**. `fr-ux-11-inline-edit`

승계 PR 2건 (로드맵 §PR 체인 Tier 2). 현재 BTS 는 인라인 편집이 **전무**해, 제목 한 글자를 고치려 해도 폼 화면으로 이동해야 한다.

- **F8 — 이슈 상세 인라인 편집.** 제목/본문을 클릭해 진입, Enter 저장, Esc 취소. `routes/issues.$key.tsx:704-726` · `IssueDescription.tsx:123-144`.
- **F9 — 이슈 목록 셀 인라인 편집**(담당자·우선순위·상태). `IssueTable.tsx` · `issue-columns.ts` · `components/issue/meta/*` 재사용 · `components/ui/popover.tsx`(소비처 0→1).

**아키텍처**. 프론트 전용 예상 — 기존 이슈 PATCH API 를 소비한다. **F8 이 두 FR 의 공통 선행**이다(F9 가 F8 에, §4.8 의 F11 이 F8 에 의존). 목록 셀은 낙관적 동시성(OCC) 409 를 만나므로 `setQueryData` 부분 갱신 대신 invalidate 로 정합을 맞춘다.

- [ ] D1. 도메인 — 인라인 편집 가능 필드 집합과 저장·취소·충돌 상태 모델 정립 (책임. frontend-engineer)
- [ ] D2. 명세 — 진입/저장/취소 상호작용 · 필드별 편집 가능 조건 · 409 충돌 표시 (책임. designer)
- [ ] D3. 데이터 모델 — 없음 예상 (기존 컬럼. 마이그레이션 0) (책임. -)
- [ ] D4. 백엔드 — 없음 예상 (기존 이슈 PATCH API 소비) (책임. -)
- [ ] D5. 백엔드 테스트 — 해당 없음 예상 (책임. -)
- [ ] D6. 프론트 UI — F8 상세 제목/본문 · F9 목록 셀 3종(담당자·우선순위·상태) (책임. designer → frontend-engineer)
- [ ] D7. E2E — 클릭 진입 → Enter 저장 → 재조회 반영 · Esc 취소가 원값 복원 (책임. qa-engineer)

### §4.10 FR-UX-12 — 검색 진입 (커맨드 팔레트 · 전역 검색)

**우선순위**. 필수 | **선행**. §4.5 (FR-UX-07) | **Plan slug**. `fr-ux-12-search-entry`

승계 PR 2건 (로드맵 §PR 체인 Tier 1 F4 + Tier 2 F13).

- **F4 — Cmd+K 실체 검색.** 지금은 슬래시 없이 텍스트를 치면 **화면이 비고 Enter 도 무반응**이다(`CommandPalette.tsx:131,165,180`). 이슈키 즉시매칭 + 프로젝트 로컬필터 + `text ~ "…"` AQL 디바운스를 얹는다. `components/ui/command.tsx`(소비처 0→1) · `api/search.ts`.
- **F13 — 상단바 전역 검색 입력창 + 자연어 폴백.** 지금 전역 검색은 입력창이 아니라 아이콘 버튼이다. `TopBar.tsx:57-66` · `routes/search.tsx` · 신규 `lib/aql-natural.ts`.

**아키텍처**. **이름표 분리 (Maxi 결정 2026-07-28 #4)** — 상단바 입력창은 `전역 검색`, 기존 `검색` 은 AQL 페이지 제출 버튼 전용이다. `검색` 정확일치가 e2e 3파일 6발생이라 이름표를 겹치면 strict mode 로 즉사한다. 팔레트 회귀 가드는 **"빈 입력 시 `QUICK_LINKS` 바로가기 4개와 순서 보존"**(`command-palette.spec.ts:252-259`). 진짜 전역 검색(프로젝트 무관 + 이슈/프로젝트/사용자 혼합)은 `AqlSearchRequest.kt:36-37` `projectKey @NotBlank` · `SearchController.kt:169` blank 거부 · `AqlFields.kt:72` 의 `project`·`assignee` 가 `PLANNED` 라는 **3층 차단**에 막혀 있어, v1 은 §4.5 의 활성 프로젝트 스코프로 낸다.

- [ ] D1. 도메인 — 팔레트 입력의 3계층(슬래시 명령 · 이슈키 · 자유 텍스트) 판별 규칙 정립. §4.2 FR-UX-04 명령 레지스트리와의 경계 (책임. frontend-engineer)
- [ ] D2. 명세 — 입력 판별 · 디바운스 · 결과 랭킹 · `전역 검색`/`검색` 이름표 계약 (책임. designer)
- [ ] D3. 데이터 모델 — 없음 예상 (기존 AQL 검색 API 소비) (책임. -)
- [ ] D4. 백엔드 — 없음 예상 (v1 은 프로젝트 스코프 유지. cross-project 검색은 3층 차단으로 범위 밖) (책임. -)
- [ ] D5. 백엔드 테스트 — 해당 없음 예상 (책임. -)
- [ ] D6. 프론트 UI — F4 팔레트 실체 검색 · F13 상단바 `전역 검색` 입력창 + 자연어 폴백 (책임. designer → frontend-engineer)
- [ ] D7. E2E — 비-슬래시 입력이 AQL 검색을 호출 · 빈 입력은 바로가기 4개와 순서 보존 · `검색` 정확일치 무회귀 (책임. qa-engineer)

### §4.11 FR-UX-13 — 백로그 사용성

**우선순위**. 필수 | **선행**. 없음 (로드맵상 F5 의존 0) | **Plan slug**. `fr-ux-13-backlog-usability`

승계 PR 3건 (로드맵 §PR 체인 Tier 1 F5 + Tier 2 F15·F16).

- **F5 — 실동작 결함 2건 봉합.** 백로그/스프린트 카드의 담당자가 **전원 `?`(이름 미확인)로 렌더된다** — 빈 `Map` 을 만들어 그대로 넘기고 채우는 코드가 없다(`BacklogBoard.tsx:217`). 보드는 정상이고 백로그만 누락이다. 조회 실패 시엔 **빈 `<div/>`** 를 반환해 에러 안내도 재시도도 없다(`:214`). `board.tsx:333` 의 `useUsersByIds` 조립 패턴을 복제한다.
- **F15 — 백로그 세로 스택 + 스프린트 다이얼로그 + 키보드 DnD.** 지금 백로그는 지라와 달리 **가로 칸반**이다. `BacklogBoard.tsx:246,248` · `SprintColumn.tsx`→`SprintSection.tsx` · 신규 `StartSprintDialog.tsx`·`CompleteSprintDialog.tsx` · `CreateSprintForm.tsx`.
- **F16 — 백로그 필터바 + 에픽 패널.** `components/filters/FilterBar.tsx` 의 슬롯 4종(`leadingSection`/`leadingChips`/`extraActiveCount`/`onReset`)이 이미 확장용 설계라 그대로 쓴다.

**아키텍처**. 프론트 전용 예상. 로드맵 **임계경로의 종점**이다(`B2 → F14 → F15 → F16`). 착수 전 `backlog.spec.ts`(536행)·`BacklogBoard.test.tsx`(759행) 재작성 범위를 먼저 산정한다. 키보드 DnD 공지는 `KanbanBoard.tsx:540` `buildDragAnnouncements`(한국어 4종, 조사 처리까지 완성)를 재사용한다. 스프린트 완료 시 미완료 이슈 이관 선택은 `SprintController.kt:221` `complete(id)` 에 이관 파라미터가 없어 **범위 밖** — v1 은 프론트가 완료 전 `DELETE /sprints/{id}/issues/{key}` 를 반복한다.

- [ ] D1. 도메인 — 백로그 세로 스택의 섹션 모델(백로그 · 스프린트 N개)과 스프린트 시작/완료 상태 전이 정립 (책임. frontend-engineer)
- [ ] D2. 명세 — 세로 스택 레이아웃 · 스프린트 시작/완료 다이얼로그 · 키보드 DnD · 필터바/에픽 패널 (책임. designer)
- [ ] D3. 데이터 모델 — 없음 예상 (기존 스프린트/백로그 API 소비) (책임. -)
- [ ] D4. 백엔드 — 없음 예상 (미완료 이슈 이관 파라미터는 범위 밖. 프론트가 기존 DELETE 를 반복) (책임. -)
- [ ] D5. 백엔드 테스트 — 해당 없음 예상 (책임. -)
- [ ] D6. 프론트 UI — F5 담당자·에러/로딩 봉합 · F15 세로 스택+스프린트 다이얼로그 · F16 필터바+에픽 패널 (책임. designer → frontend-engineer)
- [ ] D7. E2E — `backlog.spec.ts` 재작성. "담당자 있는 카드는 이니셜 아바타"(현재 `?` 로 red) · 조회 실패 시 에러+재시도 (책임. qa-engineer)

### §4.12 FR-UX-14 — 이슈 카드 밀도

**우선순위**. 높음 | **선행**. 없음 (로드맵상 B2 의존 0) | **Plan slug**. `fr-ux-14-card-density`

승계 PR 2건 (로드맵 §PR 체인 Tier 2 F14 + 백엔드 B2). 지금 보드 카드는 **3필드(제목·키·담당자)뿐**이라 지라 카드에 비해 한눈에 읽히는 정보가 없다.

- **F14 — 보드/백로그 카드 밀도**(유형 아이콘 · 라벨 칩 · 추정). `BoardCard.tsx:103-136` · `BacklogCard.tsx` · `api/boards.ts:35-66` · `components/issue/IssueTypeIcon.tsx` **재사용**(epic/story/task/subtask/bug lucide 매핑 + `role="img"` 완비).
- **B2 — 백엔드. 보드/백로그 카드 필드**(`shared-kernel` + `issue-tracking` + `agile-planning`). **F14 의 유일한 차단점** — `BoardCardResponse` 에 타입·라벨·추정이 없다.

**아키텍처**. **B2 의 지위 정정 (2026-07-29).** 2026-07-28 Maxi 결정 #3 의 *"B2 = chore"* 를 승계·정정해 **이 FR 의 D4/D5** 로 승격한다(사유는 §4.5 의 세 번째 인용 블록, §4.7 FR-UX-09 의 B1 과 동일). 3모듈 동시 변경은 **선례 커밋 `dcbf130e6`**(shared-kernel 4 + agile-planning 6 + issue-tracking 3 파일 — 같은 조합으로 보드 필터 필드를 추가한 PR)을 템플릿으로 삼는다. 라벨은 `TEXT[]` 컬럼이라 조인이 불필요하고 타입은 `listWithType` 이 이미 조인 중이므로, **N+1 회귀 가드**가 성공 판정식이다.

- [ ] D1. 도메인 — 보드/백로그 카드가 노출할 필드 집합(타입 · 라벨 · 추정) 정립 (책임. backend-engineer)
- [ ] D2. 명세 — 카드 밀도 디자인 스펙 + 응답 필드 계약 (책임. designer)
- [ ] D3. 데이터 모델 — 없음 예상 (기존 컬럼 SELECT 확장. 마이그레이션 0) (책임. -)
- [ ] D4. 백엔드 — **B2**. `shared-kernel .../board/BoardIssueLookupPort.kt:157-166` 의 `BoardIssueView` 에 `typeKey`·`typeIconName`·`labels`·`originalEstimateSeconds` 추가 + `issue-tracking .../repository/IssueRepository.kt` 보드 카드 SELECT·adapter 확장 + `agile-planning .../web/dto/BoardResponses.kt:155-162`·`BacklogResponses` (책임. backend-engineer)
- [ ] D5. 백엔드 테스트 — **B2**. `BoardControllerTest` 필드 · `IssueRepositoryIntegrationTest` SELECT · **N+1 미발생 가드** (책임. backend-engineer)
- [ ] D6. 프론트 UI — F14 `BoardCard`·`BacklogCard` 밀도 + `api/boards.ts` 스키마 확장 (책임. designer → frontend-engineer)
- [ ] D7. E2E — 카드에 유형 아이콘·라벨 칩·추정이 노출되는지 (책임. qa-engineer)

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

- [~] §2~§5 (21 FR) 모두 `[x]` 마킹 — 15/21 (2026-07-31 §4.6 프로젝트 전환·최근 항목·내 작업 완주로 15종. 인터랙션 패리티 잔여 6종 §4.7~§4.12 는 등록만 된 미착수)
      <!-- ★ 이 줄에 `FR-XX-NN` 형태를 쓰지 말 것 — verify-master-plan.sh 의 "§N 헤더 (FR-XX, N개)"
           스캐너가 헤더 선언으로 오인해 `N개` 파싱에 실패하고 EXIT 1 이 된다(2026-07-25 실제 발생). -->
- [ ] §NFR 측정표 모든 항목 임계 통과 — 미측정. 위 측정값 기록표 8행 전부 실측값이 `___` 공란. k6(프로필 조회·캘린더 30일·iCal Export) · Playwright(설정 적용·cmdk 응답) · E2E 전수(단축키) · Lighthouse CI(LCP) · axe-core(WCAG AA) 를 실제로 돌려 p95 를 채워야 한다 (2026-07-27 실측)
- [x] CHANGELOG.md 정리 — 2026-07-29 실측: 저장소 루트 `CHANGELOG.md` 의 `[Unreleased] — Phase 1` §BC 요약 표에 personalization 행 존재 (21 FR 등록 / 14 완료 / 2026-07-05~07-29 / 대표 산출 5종 + 논리 BC 각주. 인터랙션 패리티 잔여 7종은 완료 시 추가)
- [ ] README.md §7 변경 이력에 "personalization BC 완료 — YYYY-MM-DD" 추가 — 🛑 Maxi 1인 선언 대기 (에이전트 수행 불가). 2026-07-27 실측: `docs/plan/README.md` §7 은 3행뿐이고 BC 완료 행 없음 — 이 행의 날짜가 곧 선언일이므로 선언 이전에는 기입 불가
- [ ] Maxi 1인 선언 — "personalization BC 완료" — 🛑 Maxi 1인 선언 대기 (에이전트 수행 불가)
