# /admin/slack 라우트 가드 2→4 봉합 + admin 라우트 가드 행렬 음성 테스트

> slug: admin-slack-beforeload-2-4-requirepasswordchanged
> type: auth
> agent: security-engineer
> primary_bc: identity-access (파일 위치는 `apps/web`)
> 생성: 2026-07-25

## Brief

**사용자 원문.**
`/admin/slack` 라우트의 `beforeLoad` 가드가 `composeGuards(requireAuth, requireSystemAdmin)` 2개뿐이고
`requirePasswordChanged`·`requireMfaEnrolled`가 누락됐다. 다른 admin 라우트 10개는 모두 4-가드
(`requireSystemAdminFull` 또는 동등 `composeGuards`)라 이것만 예외다. 비밀번호 미변경·MFA 미등록 계정이
슬랙 연동 관리 화면에 진입할 수 있는 권한 가드 결함이다. `router.ts`의 `/admin/slack`을 4-가드로 맞추고,
같은 누락이 재발하지 않도록 admin 11개 라우트 × 가드 4종을 행렬로 전수 열거해 검증하는 음성 테스트를
신설한다. 범위는 `apps/web` 단일 BC. ATLAS-4 fixture version 불일치는 이 PR 범위 밖(별도 항목).

**classify 결과.** type=auth · agent=security-engineer · primary_bc=identity-access · task_count=0

**착수 전 실측 (2026-07-25, main `0f892b0a3`).**

`apps/web/src/router.ts` 58 라우트 중 admin 11개 전수 열거 결과.

| 라우트 | 실코드 `beforeLoad` |
|---|---|
| `/admin` | `requireSystemAdminFull` |
| `/admin/workflow-schemes` | `requireSystemAdminFull` |
| `/admin/workflow-schemes/new` | `requireSystemAdminFull` |
| `/admin/workflow-schemes/$schemeKey` | `requireSystemAdminFull` |
| `/admin/audit-logs` | `composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled)` |
| `/admin/global-permissions` | 동일 4-가드 |
| `/admin/notification-policies` | 동일 4-가드 |
| `/admin/users/new` | 동일 4-가드 |
| `/admin/webhooks` | 동일 4-가드 |
| `/admin/webhooks/$id/deliveries` | 동일 4-가드 |
| **`/admin/slack`** | **`composeGuards(requireAuth, requireSystemAdmin)` — 2-가드 (결함)** |

`requireSystemAdminFull` = `composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled)`
(`router.ts:8`). 즉 정상 라우트 10개는 표기만 두 갈래이고 가드 집합은 동일하다.

**★집계 함정 2건 (재발 방지용 기록).**
1. 가드 정본은 라우트 컴포넌트 파일(`routes/admin*.tsx`)이 아니라 **`router.ts` 중앙 집중**이다.
   라우트 파일을 세면 전부 0이 나온다.
2. `routes/admin.audit-logs.tsx`에는 JSDoc 주석 안에 `* beforeLoad: composeGuards(...)` 예시가 있어
   주석을 포함해 grep하면 **가드가 있는 것으로 오탐**된다. 판정은 주석행(`*`, `//`) 제외 후 해야 한다.

**선례.** #299 (`0d738b619` 계열, history 2026-07-20) — `/admin/workflow-schemes` 3라우트에
SYSTEM_ADMIN 가드를 넣고 **뮤테이션으로 검증**했다. 같은 방식을 따른다. 그 PR의 후속 항목에
`adminSlackRoute password/MFA 가드 누락(PRE_EXISTING)`이 이미 등재되어 있었다.

**범위 밖 (명시).**
- ATLAS-4 board/issue version 불일치 (MSW fixture, 보안 무관)
- 백엔드 `WorkflowSchemeController` 읽기 경로(list·get) 권한 — 별도 PR (project-workflow BC)
- `PublicDashboardController` 404 응답 `instance` 토큰 노출 여부 — 별도 PR (notification BC)

## 도메인 정리

- **BC**: identity-access (개념). 변경 파일은 `apps/web` (프론트 라우트 가드) — 백엔드 모듈 변경 0.
- **영향 엔티티**: 없음. 스키마 변경 0 · 마이그레이션 0 · 백엔드 API 변경 0.
- **새 용어**: 없음. `라우트 가드`(ADR 2026-06-08 §노출) · `MFA` · `신뢰 디바이스`(glossary:90)가 모두 기존 등재.
- **기존 결정 충돌**: 없음. 대신 **반드시 지켜야 하는 결정 1건**을 발견했다 (아래).
- **관련 ADR**: 신규 ADR 불필요 — 이 작업은 새 결정을 만들지 않고 기존 4-가드 관례에 미준수 라우트 1개를
  맞추는 것이다.

### ★ 지켜야 하는 기존 결정 — 가드를 공통 레이아웃으로 올리지 않는다

`docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md:70`.

> 🔴 유일한 실패 시나리오 2개. ① `/login`을 shell에 넣기 (`redirectIfAuth` + `already-authed.spec.ts`)
> ② **49개 `beforeLoad` 가드를 shell로 hoist** (가드 평가 순서가 바뀌어 `routeGuard.test.tsx`가 깨짐).
> **가드는 라우트에 그대로 둔다.**

따라서 "공통 `/admin` 레이아웃 라우트에 가드를 한 번만 선언해 11개 중복을 없앤다"는 리팩토링은
**기각된 방향**이다. 본 PR은 `/admin/slack`의 가드 목록만 다른 10개와 동일하게 맞춘다.
중복 자체는 **음성 테스트(행렬 전수 열거)로 감시**해 재발을 막는 방식으로 다룬다.

### 참조된 기존 결정 (가드 의미)

- `docs/decisions/2026-06-08-local-account-signup.md:57` — `whoami` 응답의 `mustChangePassword`가 `true`면
  프론트 라우트 가드가 `/settings/password`로 강제 리다이렉트. → `requirePasswordChanged`의 근거.
- `docs/decisions/2026-06-08-local-account-signup.md:68` — `requireSystemAdmin` 가드는 `authStore.user`의
  `isSystemAdmin`을 동기 읽기.
- `docs/decisions/2026-07-08-fr-pf-02-start-page.md:38` — `auth/routeGuard.ts`의 `redirectIfAuth` fallback도
  시작 페이지 매핑과 일관화. → 가드 파일이 이미 다수 결정의 소비 지점임.

### grill-with-docs 생략 (Maxi 승인, D4)

`bts-domain` Step 2를 생략했다. 근거 — 새 엔티티 0 · 스키마 0 · 신규 용어 0 · 기존 결정 충돌 0이고,
지켜야 할 결정은 선행 읽기(glossary + domain/identity-access + `docs/decisions` grep 3건)에서 이미
명문으로 확보했다. 대화형 캐묻기가 추가로 밝혀낼 도메인 재정의가 없다고 판단했고 Maxi가 승인했다.

## 스펙

전체 스펙. [docs/specs/2026-07-25-admin-slack-beforeload-2-4-requirepasswordchanged.md](../specs/2026-07-25-admin-slack-beforeload-2-4-requirepasswordchanged.md)

`office-hours` 대신 기술 스펙을 직접 작성했다 — 메모리 `bts-spec-office-hours-mismatch`
(이미 정의된 작업엔 YC 아이디어 진단 프레임이 안 맞는다, 2026-05-29 Maxi 결정).

핵심 3줄 요약.

- 산출법은 **`/admin/slack`의 `beforeLoad`를 `requireSystemAdminFull`로 바꾸는 1줄**뿐이다
  (공유 상수 재사용 = 가드 집합·순서 동일성을 구조적으로 보장).
- 검증은 **전 라우트를 `router.routesById`에서 파생 열거**하고, 4시나리오(미인증 / 비-관리자 /
  비밀번호 강제 / MFA 강제)의 **관측 서명**을 라우트 클래스 5종의 **기대 서명과 정확히 일치**시킨다.
  판별자는 redirect 목적지 문자열이라 어느 가드가 걸렸는지 특정된다.
- **미분류 라우트는 실패**로 처리해, 앞으로 라우트를 추가하면 클래스 선언을 강제한다. 이것이
  "가드를 안 걸었는데 아무도 모르는" 상태를 구조적으로 불가능하게 만드는 봉인이다.

## Brainstorming Check

✅ 통과 (2회 iteration). 상세는 스펙 §Phase B sanity check.

**1회 — 전제 정정 3건 + Maxi 결정 1건.**
- **B1.** `/admin/slack` 2-가드가 **의도가 아니라 누락**임을 문서로 확정(도입 `d9e418d9b`/#247 시점엔
  정상, #299가 4-가드로 정렬할 때 빠짐. `adminIndexRoute` 주석이 "강제변경 미완료 관리자 우회 차단"을
  의도로 명문화). → 수정 방향이 정책과 일치함이 확인됐다.
- **B2. ★같은 결함 클래스가 admin 밖에 5건**(`/` · `/workflows/$key` ·
  `field-permissions` · `settings/profile` · `settings/preferences`). 원래 스펙은 admin만 봤으므로
  **한 층 위의 눈가리개**였다. → **Maxi 결정 D5=B** — 수정은 슬랙 1줄로 유지, **검증 행렬만 전 라우트로
  확대**하고 의심 5건은 사유를 적어 등재(정책 판정은 후속 분리).
- **B3.** `requireAuthAndPasswordChanged`는 이름과 달리 `requireMfaEnrolled`를 **포함**한다.
  이름만 보고 "38 라우트가 MFA 미검사"라고 오판했다가 정의를 열어 정정했다.

**2회 — 개정안 자체 점검.** 런타임 `routesById` 키 집합이 정적 분석 59와 다를 수 있음(루트 라우트 항목)을
발견해 "구현 단계가 실제 키를 1회 출력해 확인 후 확정"으로 명시(E13). 남은 미해결 gap 없음.

**자체 오류 1건 기록(E12).** `staticData`를 **존재 여부**로 세어 `/dashboards/shared/$token`을 거짓
모순으로 지목했다. 값을 읽으니 `requireAuth: false` + 공개 라우트 JSDoc으로 정합이었다.
**플래그는 값을 읽어야 한다.**

## Plan

**Goal.** `/admin/slack`의 가드 누락을 1줄로 고치고, 전 라우트 가드 행렬을 클래스별 기대 서명으로
못 박아 같은 누락이 다시 잠복할 수 없게 만든다.

**Architecture.** 프로덕션 변경은 `router.ts` 1줄뿐이다. 나머지는 `router.admin-guards.test.tsx`를
"하드코딩 3라우트 × 가드 1종" → "`routesById` 파생 전 라우트 × 4시나리오 관측 서명 × 5클래스 기대 서명"
으로 개편한다. 미분류 라우트를 실패로 처리해 신규 라우트에 클래스 선언을 강제한다.

**Tech Stack.** TanStack Router(`routesById`·`isRedirect`) · vitest · zustand `useAuthStore` ·
`makeWhoami` 픽스처.

**파일 구조** (2파일, 신규 0).

| 파일 | 책임 | 변경 |
|---|---|---|
| `apps/web/src/router.ts` | 라우트 등록 정본 | `/admin/slack`의 `beforeLoad` 1줄 |
| `apps/web/src/router.admin-guards.test.tsx` | 가드 배선 회귀 가드 | 전면 개편 (파일명 유지 — 소비처 없고 git 이력 연속성 우선, 범위 확대는 파일 L1 주석으로 표기) |

**전 task 직렬.** 5개 task가 모두 같은 테스트 파일을 만지므로 `depends-on` 사슬로 직렬이다.
병렬 wave가 없어 메모리 `parallel-dispatch-precommit-hook-race`·
`worktree-lint-staged-shared-git-stash-collision` 위험이 구조적으로 없다.

**E2E 미포함 (사유 등재).** 이 변경의 검증 대상은 "라우터에 등록된 `beforeLoad` 합성"이고, 유닛 테스트가
**앱이 실제로 쓰는 `router` 객체 자체**를 호출하므로 동일 대상을 덮는다. E2E를 추가하면 브라우저 비용만
늘고 커버하는 로직은 같다. 메모리 `ui-pro-defer-e2e-regression-latent`가 경고하는 "UI 변경인데 E2E를
미루는" 경우와 다르다 — **시각·DOM 변화가 0**이다.

---

### Task 1. 완전성 + vacuous 가드 먼저 세우기 (E13 실측 해소)

**메타**.
- agent: `security-engineer`
- files: [`apps/web/src/router.admin-guards.test.tsx`]
- depends-on: []

스펙 E13 때문에 **런타임 키 집합을 추측하지 않는다.** 별도 스크립트를 만들지 않고,
완전성 테스트 자체를 먼저 세워 그 **실패 메시지로 실제 키를 관측**한다.

**RED**. 파일 전체를 아래로 교체한다(기존 3라우트 하드코딩 제거).

```tsx
// 전 라우트 beforeLoad 가드 행렬 음성 회귀 테스트 — 라우트 클래스별 기대 서명 정확 일치 + 미분류 실패
//
// 범위. #299(PR13)에서 이 파일은 workflow-schemes 3라우트 × SYSTEM_ADMIN 1종만 덮었다. 그 결과
// /admin/slack 이 2-가드인 채로 초록을 유지했다(도입 d9e418d9b/#247 → #299 정렬에서 누락).
// 이제 routesById 전 라우트 × 4시나리오를 덮고, 맵에 없는 라우트는 실패로 처리해 신규 라우트에
// 클래스 선언을 강제한다.
import { describe, it, expect } from 'vitest'
import { router } from './router'

// ★ import 는 이 task 에서 쓰는 것만 넣는다 — beforeEach/afterEach 는 Task 2 가 추가한다.
//    미사용 import 는 eslint error 이고, 각 task 는 그 시점에 커밋 가능해야 한다.

/** 가드 클래스 — 라우트가 요구하는 보호 수준 */
type GuardClass = 'ADMIN_4' | 'PROTECTED_3' | 'AUTH_ONLY' | 'LOGIN' | 'PUBLIC'

/** 경로 목록을 한 클래스로 묶는 헬퍼 — 맵 리터럴의 반복 제거 */
const cls = (c: GuardClass, ...keys: string[]): [string, GuardClass][] =>
  keys.map((k) => [k, c])

/**
 * routesById 키 → 가드 클래스. 이 맵이 정책 선언이다.
 * 키는 _shell pathless 재부모화가 접두사로 붙은 형태(router.admin-guards 이전 판의 실증 형식).
 */
const ROUTE_CLASS = new Map<string, GuardClass>([
  ...cls(
    'ADMIN_4',
    '/_shell/admin',
    '/_shell/admin/audit-logs',
    '/_shell/admin/global-permissions',
    '/_shell/admin/notification-policies',
    '/_shell/admin/slack',
    '/_shell/admin/users/new',
    '/_shell/admin/webhooks',
    '/_shell/admin/webhooks/$id/deliveries',
    '/_shell/admin/workflow-schemes',
    '/_shell/admin/workflow-schemes/new',
    '/_shell/admin/workflow-schemes/$schemeKey',
  ),
  ...cls(
    'PROTECTED_3',
    '/_shell/dashboard',
    '/_shell/dashboards',
    '/_shell/dashboards/$dashboardId',
    '/_shell/issues',
    '/_shell/issues/new',
    '/_shell/issues/$key',
    '/_shell/inbox',
    '/_shell/search',
    '/_shell/calendar',
    '/_shell/projects',
    '/_shell/projects/new',
    '/_shell/projects/$projectKey/backlog',
    '/_shell/projects/$projectKey/board',
    '/_shell/projects/$projectKey/timeline',
    '/_shell/projects/$projectKey/sprints/$sprintId/burndown',
    '/_shell/projects/$projectKey/reports/worklog',
    '/_shell/projects/$projectKey/reports/velocity',
    '/_shell/projects/$projectKey/reports/cfd',
    '/_shell/projects/$projectKey/reports/cycle-time',
    '/_shell/projects/$projectKey/settings/details',
    '/_shell/projects/$projectKey/settings/workflow-scheme',
    '/_shell/projects/$projectKey/settings/members',
    '/_shell/projects/$projectKey/settings/components',
    '/_shell/projects/$projectKey/settings/custom-fields',
    '/_shell/projects/$projectKey/settings/issue-templates',
    '/_shell/projects/$projectKey/settings/automation',
    '/_shell/projects/$projectKey/settings/slack-channels',
    '/_shell/projects/$projectKey/settings/versions',
    '/_shell/projects/$projectKey/settings/project-lead',
    '/_shell/projects/$projectKey/settings/import',
    '/_shell/settings',
    '/_shell/settings/sessions',
    '/_shell/settings/notifications',
    '/_shell/settings/account-links',
    '/_shell/settings/pats',
    '/_shell/settings/keymap',
    '/_shell/settings/calendar',
    '/_shell/settings/slack',
  ),
  ...cls(
    'AUTH_ONLY',
    '/_shell/settings/password',
    '/_shell/settings/mfa',
    '/_shell/settings/profile',
    '/_shell/settings/preferences',
    '/_shell/projects/$projectKey/settings/field-permissions',
  ),
  ...cls('LOGIN', '/login'),
  ...cls('PUBLIC', '_shell', '/_shell/', '/_shell/workflows/$key', '/_shell/dashboards/shared/$token'),
])

/**
 * 클래스가 PUBLIC·AUTH_ONLY 인 항목의 사유 등재.
 * "정당" 과 "후속 판정 필요" 를 문구로 구분한다 — 초록이 곧 정당함을 뜻하지 않는다(스펙 E10).
 */
const CLASS_NOTES: Record<string, string> = {
  '/_shell/settings/password': '정당 — requirePasswordChanged 리다이렉트 목적지 자기 경로',
  '/_shell/settings/mfa': '정당 — requireMfaEnrolled 리다이렉트 목적지 자기 경로',
  '/_shell/dashboards/shared/$token': '정당 — 공개 공유 토큰(직교 인증), 세션 불요. FR-DB-03 EC-11 로그인 리다이렉트 금지',
  '_shell': '정당 — pathless 레이아웃. ADR 2026-07-17 §70 이 가드 hoist 를 금지',
  '/_shell/settings/profile': '후속 판정 필요 — 비번·MFA 강제 대상이 접근 가능',
  '/_shell/settings/preferences': '후속 판정 필요 — 비번·MFA 강제 대상이 접근 가능',
  '/_shell/projects/$projectKey/settings/field-permissions': '후속 판정 필요 — 다른 프로젝트 설정 라우트는 PROTECTED_3',
  '/_shell/': '후속 판정 필요 — 블록 주석의 "T13 라우트 가드에서 dashboard / login 으로 리다이렉트 예정" 미완',
  '/_shell/workflows/$key': '후속 판정 필요 — 다른 상세 화면은 PROTECTED_3',
}

describe('라우트 가드 행렬 — 맵 완전성', () => {
  it('routesById 의 모든 라우트가 ROUTE_CLASS 에 분류되어 있다 (미분류 = 실패)', () => {
    const actual = Object.keys(router.routesById)
    const unclassified = actual.filter((id) => !ROUTE_CLASS.has(id))
    const stale = [...ROUTE_CLASS.keys()].filter((id) => !actual.includes(id))
    expect({ unclassified, stale }).toEqual({ unclassified: [], stale: [] })
  })

  it('발견된 라우트가 59개 이상이다 (열거 실패 시 it.each 무음 통과 차단)', () => {
    expect(Object.keys(router.routesById).length).toBeGreaterThanOrEqual(59)
  })

  // ★ 사유 등재 검증을 이 task 에 둔다 — CLASS_NOTES 를 선언만 하고 쓰지 않으면 eslint error 다.
  it('사유 등재 — AUTH_ONLY·PUBLIC 전 항목이 CLASS_NOTES 를 가진다', () => {
    const needNote = [...ROUTE_CLASS.entries()]
      .filter(([, c]) => c === 'AUTH_ONLY' || c === 'PUBLIC')
      .map(([id]) => id)
    const missing = needNote.filter((id) => CLASS_NOTES[id] === undefined)
    expect(missing).toEqual([])
  })

  it('후속 판정 필요 5건이 사유 문구로 남아 있다 (D5=B — 본 PR 에서 고치지 않음)', () => {
    const pending = Object.entries(CLASS_NOTES)
      .filter(([, note]) => note.startsWith('후속 판정 필요'))
      .map(([id]) => id)
    expect(pending).toHaveLength(5)
  })
})
```

**★의도된 hedge.** `PUBLIC`에 `'_shell'`과 `'/_shell/'`을 **둘 다** 넣어 뒀다. pathless 레이아웃 라우트의
키 형태와 인덱스 라우트의 키 형태를 확신할 수 없기 때문이다. 틀린 쪽은 `stale` 배열에 잡혀 **RED으로
드러나므로**, 이 hedge는 추측이 아니라 Task 1의 관측 장치다. GREEN에서 실제 형태 하나만 남긴다.

**실행**. `cd apps/web && ./node_modules/.bin/vitest run src/router.admin-guards.test.tsx`

**예상 실패**. 첫 테스트가 `unclassified` / `stale` 배열에 **실제 키를 나열해 출력**한다.
`__root__`(루트 라우트)·`/_shell` vs `_shell`·인덱스 라우트 키(`/_shell/` 여부) 등이 여기서 드러난다.
이 출력이 E13이 요구한 실측이다.

**GREEN**. 실패 메시지에 나온 실제 키로 맵을 정정한다.
- `unclassified`에 나온 키(예. `__root__`) → 알맞은 클래스로 추가하고 `CLASS_NOTES`에 사유를 적는다.
  루트 라우트는 `PUBLIC` + `'정당 — createRootRoute, 가드 대상 아님'`.
- `stale`에 나온 키 → 내가 추측한 형태가 틀린 것이므로 실제 형태로 교체한다.
- 두 배열이 모두 비면 GREEN.

**REFACTOR**. 없음 — 맵 자체가 산출물이다. 주석은 이미 작성돼 있다.

**검증**. 위 2 테스트 green. `expect({unclassified, stale}).toEqual(...)`로 **양방향**을 한 번에 보므로
"내 맵에만 있는 유령 키"도 잡힌다.

---

### Task 2. 서명 관측 + `ADMIN_4` 검증 → `/admin/slack` 결함 노출 → 1줄 수정

**메타**.
- agent: `security-engineer`
- files: [`apps/web/src/router.admin-guards.test.tsx`, `apps/web/src/router.ts`]
- depends-on: [1]

**RED**. Task 1 파일에 아래를 추가한다.

```tsx
import { isRedirect } from '@tanstack/react-router'
import { useAuthStore } from './auth/authStore'
import { makeWhoami } from './mocks/auth-fixtures'

/** beforeLoad 컨텍스트 중 가드가 쓰는 최소 형태 (이전 판·routeGuard.test.tsx와 동형) */
interface MinimalBeforeLoadContext {
  location: { href: string; pathname: string }
}

/** redirect() 반환 타입 — Response & { options: { to } } */
interface RedirectResponse extends Response {
  options: { to: string }
}

/** 관측 서명 — [S-1, S-2, S-3, S-4] 각 칸은 redirect 목적지 또는 통과(null) */
type Signature = [string | null, string | null, string | null, string | null]

/** 클래스별 기대 서명. LOGIN 은 방향이 반대(인증 시 내보냄)이고 목적지는 startPage 매핑 결과 /dashboards */
const EXPECTED: Record<GuardClass, Signature> = {
  ADMIN_4: ['/login', '/dashboard', '/settings/password', '/settings/mfa'],
  PROTECTED_3: ['/login', null, '/settings/password', '/settings/mfa'],
  AUTH_ONLY: ['/login', null, null, null],
  LOGIN: [null, '/dashboards', '/dashboards', '/dashboards'],
  PUBLIC: [null, null, null, null],
}

/** 시나리오 4종 — 각 시나리오는 나머지 조건을 전부 통과 상태로 두어 판별자를 유일하게 특정한다 */
const SCENARIOS = [
  { label: 'S-1 미인증', apply: () => useAuthStore.setState({ accessToken: null, user: null }) },
  {
    label: 'S-2 비-admin',
    apply: () =>
      useAuthStore.setState({
        accessToken: 'valid-token',
        user: makeWhoami({ isSystemAdmin: false }),
      }),
  },
  {
    label: 'S-3 비밀번호 강제',
    apply: () =>
      useAuthStore.setState({
        accessToken: 'valid-token',
        user: makeWhoami({ isSystemAdmin: true, mustChangePassword: true }),
      }),
  },
  {
    label: 'S-4 MFA 강제',
    apply: () =>
      useAuthStore.setState({
        accessToken: 'valid-token',
        user: makeWhoami({ isSystemAdmin: true, mfaEnrollmentRequired: true }),
      }),
  },
] as const

/** routesById 키에서 실제 pathname 을 만든다. $세그먼트는 임의값으로 치환(가드는 파라미터를 파싱하지 않음 — 스펙 E1) */
function pathnameOf(routeId: string): string {
  const stripped = routeId.replace(/^\/_shell/, '').replace(/^_shell$/, '')
  const withParams = stripped.replace(/\$[A-Za-z]+/g, 'x-1')
  return withParams === '' ? '/' : withParams
}

/** 한 라우트의 관측 서명을 만든다 */
function observe(routeId: string): Signature {
  const byId = router.routesById as Record<
    string,
    { options: { beforeLoad?: (ctx: MinimalBeforeLoadContext) => void } }
  >
  const beforeLoad = byId[routeId]?.options.beforeLoad
  const pathname = pathnameOf(routeId)
  const ctx: MinimalBeforeLoadContext = { location: { href: pathname, pathname } }

  return SCENARIOS.map((s) => {
    s.apply()
    if (beforeLoad === undefined) return null
    try {
      beforeLoad(ctx)
      return null
    } catch (e) {
      // redirect 가 아닌 진짜 예외(예. pathnameOf 버그로 인한 TypeError)는 원본을 그대로 올린다.
      // 여기서 expect(isRedirect) 로 단정하면 원인 예외가 어서션 실패 메시지에 가려진다.
      if (!isRedirect(e)) throw e
      return (e as RedirectResponse).options.to
    }
  }) as Signature
}

const idsOf = (c: GuardClass): string[] =>
  [...ROUTE_CLASS.entries()].filter(([, v]) => v === c).map(([k]) => k)

describe('라우트 가드 행렬 — ADMIN_4', () => {
  beforeEach(() => useAuthStore.setState({ accessToken: null, user: null }))
  afterEach(() => useAuthStore.setState({ accessToken: null, user: null }))

  it('ADMIN_4 클래스가 11개다 (/admin/slack 편입 확인)', () => {
    expect(idsOf('ADMIN_4')).toHaveLength(11)
  })

  it.each(idsOf('ADMIN_4'))('%s — 관측 서명이 ADMIN_4 기대와 정확히 일치', (id) => {
    expect(observe(id)).toEqual(EXPECTED.ADMIN_4)
  })
})
```

**실행**. `cd apps/web && ./node_modules/.bin/vitest run src/router.admin-guards.test.tsx`

**예상 실패**. `/_shell/admin/slack` 케이스 1건만 실패한다.

```
- Expected  ['/login', '/dashboard', '/settings/password', '/settings/mfa']
+ Received  ['/login', '/dashboard', null, null]
```

세 번째·네 번째 칸이 `null`인 것이 **비밀번호 강제·MFA 강제가 무력화된 결함 그 자체**다.
다른 10개 라우트는 통과한다 → 실패가 결함에 정확히 국소화된다.

**GREEN**. `apps/web/src/router.ts`의 `adminSlackRoute` 블록 1줄 교체.

```diff
-  beforeLoad: composeGuards(requireAuth, requireSystemAdmin),
+  beforeLoad: requireSystemAdminFull,
```

같은 블록의 JSDoc 첫 줄도 사실과 맞춘다.

```diff
- * Slack 연결 관리자 라우트 — /admin/slack, requireAuth + requireSystemAdmin (FR-SL-01 D6/D7 Task 7).
+ * Slack 연결 관리자 라우트 — /admin/slack, 다른 admin 라우트와 동일 4-가드 requireSystemAdminFull
+ * (FR-SL-01 D6/D7 Task 7 도입 시 2-가드였고 #299 정렬에서 누락 → 본 PR 봉합).
+ * 강제변경·MFA 미완료 관리자의 우회를 차단한다(FR-MF-04).
```

**REFACTOR**. `composeGuards`·`requireAuth`·`requireSystemAdmin` import가 다른 라우트에서도 쓰이는지
확인한다. 여전히 쓰이므로 **import 제거 없음** — 확인만 하고 손대지 않는다
(메모리 `constructor-change-cross-module-callsite-blindspot` 계열의 "내 변경이 만든 고아만 정리" 규칙).

**검증**. `./node_modules/.bin/vitest run src/router.admin-guards.test.tsx` — 12 테스트 green
(완전성 2 + 개수 1 + ADMIN_4 11 = 14개 중 이 시점 전부 green).

---

### Task 3. 나머지 4클래스 검증 + 사유 등재 노출

**메타**.
- agent: `security-engineer`
- files: [`apps/web/src/router.admin-guards.test.tsx`]
- depends-on: [2]

프로덕션 변경 0. 현 상태를 기대값으로 고정하는 특성화(characterization) task다.
**RED이 성립하지 않으므로 대신 인라인 뮤테이션으로 어서션이 살아 있음을 증명한다.**

**RED (대체 — 어서션 실효 증명)**. 아래를 추가하되 **`PROTECTED_3` 기대 서명을 일부러 틀리게**
(`['/login', '/dashboard', '/settings/password', '/settings/mfa']` — S-2를 `/dashboard`로) 넣고 실행한다.

```tsx
describe('라우트 가드 행렬 — 나머지 클래스', () => {
  beforeEach(() => useAuthStore.setState({ accessToken: null, user: null }))
  afterEach(() => useAuthStore.setState({ accessToken: null, user: null }))

  // ★ 클래스별 개수 어서션 — 이게 없으면 idsOf 가 빈 배열을 돌려줄 때 it.each([]) 가 무음 통과한다.
  //    R6 의 전체 하한(routesById 기준)은 이 구멍을 막지 못한다(맵/필터가 죽어도 라우트 수는 그대로).
  it.each([
    ['PROTECTED_3', 38],
    ['AUTH_ONLY', 5],
    ['LOGIN', 1],
  ] as const)('%s 클래스 멤버가 %i개다 (열거 붕괴 시 무음 통과 차단)', (c, n) => {
    expect(idsOf(c)).toHaveLength(n)
  })

  // PUBLIC 은 Task 1 실측에서 루트 라우트가 편입될 수 있어 하한으로 둔다(상한 없음).
  it('PUBLIC 클래스 멤버가 4개 이상이다', () => {
    expect(idsOf('PUBLIC').length).toBeGreaterThanOrEqual(4)
  })

  it.each(idsOf('PROTECTED_3'))('%s — PROTECTED_3 기대와 일치', (id) => {
    expect(observe(id)).toEqual(EXPECTED.PROTECTED_3)
  })

  it.each(idsOf('AUTH_ONLY'))('%s — AUTH_ONLY 기대와 일치', (id) => {
    expect(observe(id)).toEqual(EXPECTED.AUTH_ONLY)
  })

  it.each(idsOf('LOGIN'))('%s — LOGIN 기대와 일치 (인증 시 내보냄, 방향 반대)', (id) => {
    expect(observe(id)).toEqual(EXPECTED.LOGIN)
  })

  it.each(idsOf('PUBLIC'))('%s — PUBLIC 기대와 일치 (가드 없음)', (id) => {
    expect(observe(id)).toEqual(EXPECTED.PUBLIC)
  })
})
```

*(사유 등재 2 테스트는 Task 1에 있다 — `CLASS_NOTES` 미사용 eslint error 회피.)*

**예상 실패**. 38개 `PROTECTED_3` 케이스 전부 실패(S-2 칸이 `null`인데 `/dashboard`를 기대).
→ 어서션이 실제 거동을 읽고 있음이 증명된다(vacuous 아님).

**GREEN**. `EXPECTED.PROTECTED_3`의 S-2 칸을 `null`로 되돌린다(Task 2에서 정의한 원래 값).
전 케이스 green.

**REFACTOR**. 없음.

**검증**. `./node_modules/.bin/vitest run src/router.admin-guards.test.tsx` —
14 + 38 + 5 + 1 + 4(또는 5, Task 1에서 루트 라우트가 PUBLIC에 추가되면) + 2 = **64개 이상 green**.
정확한 총수는 Task 1의 실측 키 개수에 따르므로 실행 결과로 확정한다.

---

### Task 4. 뮤테이션 3종으로 실효 입증

**메타**.
- agent: `security-engineer`
- files: [`apps/web/src/router.admin-guards.test.tsx`, `apps/web/src/router.ts`]
- depends-on: [3]

**★선행 조건 — Task 3까지 전부 커밋된 상태에서만 시작한다.**
메모리 `mutation-test-requires-committed-baseline` — PR22에서 미커밋 상태로 뮤테이션 후
`git checkout --`으로 원복해 **2파일 작업이 소실**됐다. 시작 전 `git status --porcelain`이 비어 있음을
확인한다.

**RED/GREEN 없음** (검증 전용 task).

**절차**.

1. **기준선 확인**. `./node_modules/.bin/vitest run src/router.admin-guards.test.tsx` → 전량 green,
   테스트 개수 기록.
2. **뮤테이션 A (수정의 실효)**. `router.ts`의 `/admin/slack`을
   `composeGuards(requireAuth, requireSystemAdmin)`으로 되돌린다.
   기대 — `/_shell/admin/slack` ADMIN_4 케이스 **1건 red**, 나머지 전부 green.
   `git checkout -- src/router.ts`로 복원 후 green 재확인.
3. **뮤테이션 B (vacuous 차단)**. `observe`가 아니라 **열거**를 죽인다 —
   `idsOf`를 `() => []`로 바꾼다. 기대 — "발견된 라우트가 59개 이상" 어서션과
   "ADMIN_4 클래스가 11개다" 어서션이 **red**. 복원 후 green 재확인.
4. **뮤테이션 C (완전성 강제의 실효)**. `ROUTE_CLASS`에서 `'/_shell/admin/slack'` 항목 1개를 지운다.
   기대 — 완전성 테스트가 `unclassified: ['/_shell/admin/slack']`로 **red**.
   복원 후 green 재확인.
5. 각 뮤테이션마다 **red 케이스 이름을 기록**한다. "red 났다"만으로는 판별자가 없다
   (메모리 `negative-guard-needs-body-discriminator`).

**검증**. 뮤테이션 3종 각각 예상 위치에서만 red, 복원 후 3회 모두 green 복귀.
`git status --porcelain`이 비어 있음(복원 완료)을 마지막에 확인.

---

### Task 5. 전체 검증 + 기준선 대조

**메타**.
- agent: `security-engineer`
- files: []
- depends-on: [4]

**RED/GREEN 없음** (검증 전용).

**절차**.

1. `cd apps/web && ./node_modules/.bin/tsc -p tsconfig.app.json --noEmit` → 0 error.
   ★메모리 `ci-typecheck-tsconfig-app-vs-local` — CI는 `tsconfig.app.json`을 쓴다. 그걸로 맞춘다.
2. `cd apps/web && ./node_modules/.bin/eslint src` → 0 error (경고 8건은 사전 존재, PR22 기록).
3. **전량 유닛 + 기준선 대조**. `cd apps/web && ./node_modules/.bin/vitest run`
   → PR22 기준선이 **7935 passed**였다. 본 PR은 테스트를 늘리므로 `>= 7935 + (증가분)`이어야 하고
   **감소하면 커버리지 순손실**이다. 개수를 명시 기록한다
   (메모리 `gradle-batched-task-partial-test-run` 계열 — 초록보다 개수를 본다).
4. `bash scripts/verify-master-plan.sh` → EXIT 0, `129/129` 출력 확인.
   ★문서에 `FR-` + 코드 형태 문구를 새로 넣지 않았는지 확인
   (메모리 `verify-master-plan-header-scanner-false-match`).
5. **사유 문구 grep 확인**. `grep -c "후속 판정 필요" apps/web/src/router.admin-guards.test.tsx` → 5.

**검증**. 위 5항 전부 통과. 결과 수치를 게이트 2 보고에 그대로 싣는다.

## Plan 메타

- task 수: 5
- 예상 시간: 직렬 5 task × 3~5분 = 약 20분 (병렬 wave 없음 — 전 task가 같은 테스트 파일 공유)
- TDD 강제: yes (Task 2가 진짜 red→green. Task 3은 특성화라 인라인 뮤테이션으로 대체 증명)
- 병렬 dispatch: 없음 (depends-on 사슬 1→2→3→4→5)
- 추가 검증: typecheck(tsconfig.app) · eslint · 전량 vitest 개수 대조 · verify-master-plan · 뮤테이션 3종
- 프로덕션 변경: `router.ts` 1줄 + 같은 블록 JSDoc 3줄. 그 외 전부 테스트

## 리뷰 결과 (← /bts-review-plan 채움)

## 리뷰 결과

### plan-eng-review (2026-07-25) — 메인 에이전트 직접 수행

**수행 방식 (deviation, 사유 등재).** 메모리 `bts-review-plan-autoplan-overkill`은 독립 시각을 위해
code-reviewer/Plan 에이전트 dispatch를 처방하지만, 본 세션은 **에이전트 호출 금지 지시**가 있고
PR22(#308)에서도 Maxi가 "서브에이전트 없이 메인 에이전트 직접 수행"을 확정했다(파일 겹침으로 병렬 이득
0 + sub-agent 거짓 보고 전력). 지시가 메모리보다 우선이라 직접 수행했다.
**한계 명시** — plan 작성자가 자기 plan을 리뷰하는 편향이 남아 있다. 게이트 1에서 Maxi가 이 점을
감안해 판단할 수 있도록 여기 적는다.

**BLOCKER: 없음.**

**확정 결함 1건 (수정 완료).**
- **vacuous 구멍 — 클래스별 개수 어서션 부재.** `idsOf`가 빈 배열을 돌려주면 `it.each([])`가 무음
  통과한다. `ADMIN_4`에는 `toHaveLength(11)`이 있었지만 `PROTECTED_3`·`AUTH_ONLY`·`LOGIN`·`PUBLIC`에는
  없었다. R6의 전체 하한은 `routesById`를 세므로 **맵/필터가 죽어도 통과**해 이 구멍을 못 막는다.
  → Task 3에 클래스별 개수 어서션 추가(38/5/1 정확, PUBLIC은 루트 라우트 편입 가능성 때문에 하한 4).
  메모리 `guard-handler-matrix-blindfold`가 경고한 형태가 **내 계획 안에 다시 나타난 것**이다.

**개선 1건 (수정 완료).**
- `observe()`가 `expect(isRedirect(e)).toBe(true)`로 단정해, redirect가 아닌 진짜 예외(예. `pathnameOf`
  버그의 `TypeError`)가 어서션 실패 메시지에 **가려지는** 문제. → `if (!isRedirect(e)) throw e`로 원본
  예외를 그대로 올리게 변경.

**주의 2건 (구현 단계에서 확인, 차단 아님).**
- `LOGIN` 클래스 기대 목적지 `/dashboards`는 `resolveStartPageNav('dashboards', userId)`의 반환을
  **가정한 값**이다. `lib/start-page.ts`의 `FALLBACK_NAV`가 `{to:'/dashboards'}`인 것은 실측했으나
  `'dashboards'` 키의 정식 매핑 본문(20~36행)은 읽지 않았다. Task 3에서 실패하면 실측값으로 정정한다.
  ⚠️ `/dashboard`(단수, admin 거부)와 `/dashboards`(복수, 시작 페이지)를 혼동하지 말 것.
- `_shell`·인덱스 라우트의 `routesById` 키 형태는 **의도된 hedge**(두 변형 병기)로 두었고 Task 1의
  `stale` 배열이 틀린 쪽을 RED으로 드러낸다. 추측을 코드에 박지 않았음을 확인.

**통과 확인 항목.**
- ✅ BC 격리 — `apps/web` 단일. 백엔드·마이그레이션·FR 카운트 전부 무접촉(129 불변).
- ✅ ADR 준수 — 가드를 shell로 hoist하지 않는다(2026-07-17 §70). 공통 `/admin` 레이아웃 신설 없음.
- ✅ 절대 규칙 — 인증 없는 경로 신설 0. 가드는 deny-by-default(`=== true` 명시 비교) 유지.
- ✅ 회귀 표면 — 프로덕션 변경이 `beforeLoad` 1줄이라 시각·DOM·라우트 트리 변화 0.
- ✅ 뮤테이션 3종이 "수정 실효 / vacuous 차단 / 완전성 강제"를 각각 별개로 겨냥. 커밋 후 수행 조건 명시
  (메모리 `mutation-test-requires-committed-baseline`).
- ✅ 병렬 위험 0 — 전 task 직렬(같은 테스트 파일). lint-staged 공유 stash·pre-commit race 무관.
- ✅ task 5개, 메타 3필드 완비, 검증 명령 구체(파일 경로·기대 출력 명시).

### plan-ceo-review — 생략 (deviation, 사유 등재)

메모리 `bts-review-plan-autoplan-overkill`(2026-05-29 Maxi 결정) — CEO 리뷰("10-star 제품 재구상")는
기술 plan에 부적합하고 토큰만 소모한다. 본 작업은 **제품 범위 결정이 0이고**(신규 FR 0·기능 추가 0)
이미 명문화된 정책(4-가드, FR-MF-04)에 누락 1건을 맞추는 결함 수정이다. 재구상할 제품 결정이 없다.

게이트 1에서 Maxi가 이 생략을 뒤집을 수 있다.
