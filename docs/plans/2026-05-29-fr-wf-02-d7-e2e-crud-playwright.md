# FR-WF-02 D7 E2E — 워크플로우 스킴 Playwright 시나리오

> slug. fr-wf-02-d7-e2e-crud-playwright
> type. qa
> agent. qa-engineer
> 생성. 2026-05-29

## Brief

PR #31 ([ui] FR-WF-02 D6) 가 머지하면서 워크플로우 스킴(Workflow Scheme) 관리 UI 의 단위/통합 테스트는 완료. 그러나 spec §2.2 의 D7 (E2E) 항목은 미완료 — 유일한 미완 deliverable. 본 PR 가 그 마지막 항목을 채워서 FR-WF-02 BC 완료에 도달하는 것이 목표.

### 사용자 원문

`FR-WF-02 D7 E2E 작업하자 — 스킴 CRUD + 매핑 편집 + 표준 보호 + 사용 중 삭제 차단 모달 + 프로젝트 할당 Playwright 시나리오`

### classify-task 결과

- type. qa
- agent. qa-engineer
- slug. fr-wf-02-d7-e2e-crud-playwright
- primary_bc. null (frontend E2E 영역)

### 시나리오 후보 (사용자 명시 5건)

1. **스킴 CRUD** — 목록 → 생성 → 상세 → 수정 → 삭제 happy path
2. **매핑 편집** — 이슈 타입 ↔ 워크플로우 매핑 추가/변경/제거
3. **표준 보호** — `isDefault: true` 스킴 삭제/수정 제약 검증
4. **사용 중 삭제 차단 모달** — `SchemeInUseException` 발생 시 `SchemeInUseModal` 노출 + `usedByProjects` 리스트
5. **프로젝트 할당** — 프로젝트 ↔ 스킴 assign/reassign/unassign

## 도메인 정리

### BC

- **project-workflow** — `backend/modules/project-workflow/` 의 워크플로우 스킴 (Workflow Scheme — 이슈 타입 → 워크플로우 매핑을 묶은 단위) 관리 영역. PR #31 도입.
- 본 PR 은 frontend E2E 만 추가. backend / 도메인 모델 변경 0.

### 영향 엔티티 (모두 기존, 신규 0)

| 엔티티 | 위치 | 도입 PR |
|---|---|---|
| `WorkflowScheme` | `backend/modules/project-workflow/.../scheme/` | #31 |
| `SchemeIssueTypeMapping` | 같은 영역 | #31 |
| `ProjectWorkflowSchemeAssignment` | 같은 영역 | #31 |
| `SchemeInUseException` + `SchemeInUseModal` | backend + `apps/web/src/components/admin/` | #31 |

### grill-with-docs 스킵 사유 (qa fast-track 변형)

bts-domain SKILL.md §Fast-track 스킵 조건은 명시적으로 `chore/bugfix` 만 허용. 본 task 는 type=qa 라 원칙적으로 grill-with-docs 호출 대상. **그러나 다음 사유로 변형 스킵 적용**.
1. 신규 도메인 개념 도입 0 (위 표 4개 엔티티 모두 PR #31 기존)
2. 본 task scope = "기존 UI 의 Playwright 행위 검증" — DDD 유비쿼터스 언어 정련의 영역 아님
3. grill-with-docs 의 대화형 비용 (~10분, 3-5 round) > 신규 통찰 기대값 (0)
- Maxi 가 본 변형에 동의하지 않으면 재호출 가능 — plan §변형 사유 명시로 가시화.

### 발견된 drift (본 PR scope 외, 후속 chore PR 후보)

#### Drift-1. glossary.md 누락 4 용어 (PR #31 도입, glossary 미반영)

- 워크플로우 스킴 (Workflow Scheme) — 이슈 타입 → 워크플로우 매핑을 묶은 단위
- 스킴 매핑 (Scheme Mapping) — 한 스킴 안에서 이슈 타입과 워크플로우 1:1 연결
- 표준 스킴 (Default Scheme / `isDefault: true`) — 신규 프로젝트의 기본 + 일부 수정 제약 (전체 삭제 차단, 매핑은 수정 가능)
- 사용 중 스킴 (Scheme In Use) — 어느 프로젝트에라도 할당된 스킴 (삭제 차단, `SchemeInUseException`)

**후속 후보**. `/bts FR-WF-02 glossary drift cleanup` 또는 본 PR 머지 후 별 chore PR.

#### Drift-2. ADR 동기화 방향 의심 (repo ↔ Maxi_wiki)

- `Maxi_wiki/BTS/decisions/` 가 2026-05-28 까지 누적 (10건)
- repo `docs/decisions/` 는 2026-05-27 까지 (15건 — 일부는 2026-05-22 후 누락)
- repo 누락 ADR. `2026-05-22-issue-key-prefix-policy`, `2026-05-22-issue-permission-resolver-port`, `2026-05-22-pgmq-postgres-image`, `2026-05-26-jooq-execute-advisory-lock-exception`, `2026-05-26-workflow-transition-port-result-sealed`, `2026-05-27-bts-workflow-token-hardening`, `2026-05-28-workflow-scheme-frontend-view-layer-cross-bc-lookup`, `2026-05-28-workflow-transition-identity-policy`
- Obsidian `_index.md` §동기화 규칙은 "Repo → Obsidian 단방향" 명시. 그러나 현 상태는 Obsidian 이 repo 보다 신선 — 단방향 sync hook 의 미동작 또는 누락 commit 의심.

**후속 후보**. sync hook 점검 + 누락 ADR repo 반영 chore PR. 본 PR 영향 0 — D7 시나리오 작성에 ADR 본문 직접 인용 무 (기존 UI 행위 검증).

### 본 PR 도메인 정리 결론

- 신규 ADR. 없음 (BTS 첫 ADR-free qa task 후보)
- 신규 용어. 없음 (위 4 용어는 후속 cleanup PR)
- 기존 결정 충돌. 없음
- glossary 갱신. 본 PR 영역 아님 (Drift-1 의 후속 PR 대상)

## 스펙

전체 스펙. [docs/specs/2026-05-29-fr-wf-02-d7-e2e-crud-playwright.md](../specs/2026-05-29-fr-wf-02-d7-e2e-crud-playwright.md)

핵심 5 시나리오 (D6 spec S1~S10 매핑).

- E2E-1 **스킴 CRUD** (D6 S1+S2+S3+PUT). 목록 → 생성 → 상세 진입 → name 수정 → 좌 네비 카운트 +1 갱신
- E2E-2 **매핑 편집** (D6 S4+S5+S6). 매핑 추가 (낙관적) → 삭제 → default mapping sentinel `__default__` → POST body `null` 변환
- E2E-3 **표준 스킴 보호** (D6 S7). 삭제 disabled + tooltip / key·is_default read-only / name·description 편집 자유 (D11 결정 반영)
- E2E-4 **사용 중 삭제 차단 모달** (D6 S8). 409 SCHEME_IN_USE → SchemeInUseModal `usedByProjects` link → 프로젝트 스킴 할당 화면 navigate
- E2E-5 **프로젝트 스킴 할당** (D6 S9+S10). PUT UPSERT 첫 할당 / GET 404 → 자동 할당 안내 카드

**모드**. MSW-based (`pnpm dev` + scheme-handlers 9개 + scheme-fixtures). 백엔드 기동 무관.
**fixture 1 신규**. `e2e/fixtures/workflow-scheme-fixtures.ts` (loginAsAdmin + navigate helpers + i18nLabels 재노출).

## Brainstorming Check

controller inline brainstorming (D6 spec line 156 패턴 따름).

### 🚨 BLOCKER 1건 — Maxi 결정 완료 (2026-05-29)

- **G-BLOCKER-1. 셀렉터 정본화 정책 (FR3)**. **옵션 (C) 채택** — 신규 `apps/web/src/i18n/workflow-scheme-labels.ts` 에 E2E 셀렉터가 의존하는 라벨/텍스트만 const export. 기존 components 의 hardcoded 중 셀렉터 참조 영역만 import 로 대체. 전면 i18n migration 은 별 후속 PR scope. plan §Task 단계에서 추가 task (라벨 file 신규 + 컴포넌트 부분 마이그레이션) 흡수.

### gap 6건 (BLOCKER 아님, plan 단계 흡수)

- G1. `/api/v1/issue-types` MSW handler 위치 확인 (scheme-handlers 외)
- G2. scheme-fixtures 의 현재 카운트 (표준 4 + 커스텀 2) 가 시나리오 가정과 일치 확인
- G3. SchemeInUseModal 의 `usedByProjects` link (체크포인트 C4) — fixture 빈 list 검증
- G4. TDD 변형 — UI 사전 존재로 "RED 자연 발생" 가설, plan §Task 본문에 사유 명시 (learnings 2026-05-28 Flyway recursive 사례 패턴)
- G5. loginAsAlice 의 admin 권한 가정 확인 (AlwaysAllow stub 단계 추정)
- G6. `pnpm test:e2e` 전체 duration baseline 측정 (PR #32 머지 후)

### Final

✅ 통과 (1 iteration, BLOCKER 1 + gap 6 plan 흡수).

## Plan

### Plan 메타

- task 수. **9**
- wave 수. **4** (T1 → T2+T3 → T4~T8 (5-parallel) → T9)
- 예상 시간. ~25분 (직렬 기준 ~50분, wave 적용 절반)
- TDD 강제. yes (T4~T8 = `test:` commit pure RED→GREEN 자연 패턴 — UI 사전 존재로 RED 자연 발생 안 함, 본질은 행위 검증 신규 / learnings 2026-05-28 Flyway recursive 사례 패턴 적용)
- 병렬 dispatch. wave 메타 (depends-on + files) 로 bts-impl wave 계산
- 추가 검증. controller 가 wave 종료마다 전체 `pnpm verify` (learnings 2026-05-27 서브에이전트 scoped typecheck 맹점 회피)

---

### Task 1. 라벨 const file 신규 (`workflow-scheme-labels.ts`)

**메타**.
- agent. `frontend-engineer`
- files. [`apps/web/src/i18n/workflow-scheme-labels.ts` (신규)]
- depends-on. `[]`

**RED**. 단위 테스트 없음 (foundational const file, E2E 가 후속 task 에서 검증). commit prefix `chore:`.

**GREEN**.
- 파일. `apps/web/src/i18n/workflow-scheme-labels.ts` 신규
- 구조. 그룹별 const export. 예시.
  ```ts
  export const workflowSchemeLabels = {
    sidebar: {
      nav: '워크플로우 스킴 목록',
      standardGroup: '표준',
      customGroup: '커스텀',
      addSchemeButton: '+ 새 스킴',
    },
    detail: {
      addMappingButton: '+ 매핑 추가',
      deleteMappingButton: '삭제',
      deleteConfirmModal: '매핑 삭제 확인',
    },
    create: {
      keyLabel: '스킴 키',
      nameLabel: '이름',
      descriptionLabel: '설명 (선택)',
      submitButton: '생성',
    },
    mapping: {
      defaultOption: '기본값 (모든 이슈 타입)',
      issueTypeSelect: '이슈 타입 선택',
      workflowSelect: '워크플로우 선택',
      addRowButton: '추가',
    },
    assignment: {
      sectionLabel: '워크플로우 스킴 선택',
      assignButton: '스킴 지정',
      changeButton: '스킴 변경',
    },
    inUseModal: {
      role: 'dialog',
      // 추가 라벨 (실 컴포넌트 확인 후 보강)
    },
    standardProtect: {
      deleteDisabledTooltip: '표준 스킴은 삭제 불가',
    },
    emptyState: {
      heading: '스킴을 선택하세요',
      message: '왼쪽 목록에서 워크플로우 스킴을 선택하거나 새 스킴을 생성하세요.',
    },
  } as const
  ```

**REFACTOR**.
- 그룹별 const 의 `as const` 타입 안정성 확인
- 컴포넌트 실제 텍스트와 정확 일치 검증 (grep 또는 implementer 가 컴포넌트 파일 확인)

**검증**. `pnpm typecheck` (TS strict, const 만 export — 타입 에러 0)

---

### Task 2. E2E fixture 신규 (`workflow-scheme-fixtures.ts`)

**메타**.
- agent. `qa-engineer`
- files. [`apps/web/e2e/fixtures/workflow-scheme-fixtures.ts` (신규)]
- depends-on. `[1]`

**RED**. 단위 테스트 없음 (foundational fixture, E2E 가 후속 task 에서 검증). commit prefix `chore:`.

**GREEN**.
- 파일. `apps/web/e2e/fixtures/workflow-scheme-fixtures.ts` 신규
- 내용.
  ```ts
  import type { Page } from '@playwright/test'
  import { expect } from '@playwright/test'
  import { loginStrings } from '../../src/i18n/ko'
  import { workflowSchemeLabels } from '../../src/i18n/workflow-scheme-labels'

  export const i18nLabels = {
    workflowScheme: workflowSchemeLabels,
  } as const

  /** alice 로그인 + /dashboard 진입 (issue-fixtures 의 loginAsAlice 와 동등) */
  export async function loginAsAlice(page: Page): Promise<void> {
    await page.goto('/login')
    await page.getByLabel(loginStrings.usernameLabel).fill('alice')
    await page.getByLabel(loginStrings.passwordLabel).fill('password')
    await page.getByRole('button', { name: loginStrings.submitButton }).click()
    await page.waitForURL('**/dashboard')
  }

  /** /admin/workflow-schemes 진입 + 사이드바 visible 검증 */
  export async function navigateToSchemeList(page: Page): Promise<void> {
    await page.goto('/admin/workflow-schemes')
    await expect(page.getByRole('navigation', { name: workflowSchemeLabels.sidebar.nav })).toBeVisible()
  }

  /** /admin/workflow-schemes/{schemeKey} 진입 */
  export async function navigateToSchemeDetail(page: Page, schemeKey: string): Promise<void> {
    await page.goto(`/admin/workflow-schemes/${schemeKey}`)
  }

  /** /projects/{projectKey}/settings/workflow-scheme 진입 */
  export async function navigateToProjectAssignment(page: Page, projectKey: string): Promise<void> {
    await page.goto(`/projects/${projectKey}/settings/workflow-scheme`)
  }
  ```

**REFACTOR**.
- jsdoc 정리
- helper 시그니처 일관성 (Promise<void> 명시)

**검증**. `pnpm typecheck` (e2e dir 도 strict — 타입 에러 0)

---

### Task 3. 컴포넌트 부분 마이그레이션 — 셀렉터 영역만 라벨 const import

**메타**.
- agent. `frontend-engineer`
- files. [
    `apps/web/src/routes/admin.workflow-schemes.tsx`,
    `apps/web/src/routes/admin.workflow-schemes.$schemeKey.tsx`,
    `apps/web/src/routes/admin.workflow-schemes.new.tsx`,
    `apps/web/src/routes/projects.$projectKey.settings.workflow-scheme.tsx`,
    `apps/web/src/components/admin/WorkflowSchemeSidebar.tsx`,
    `apps/web/src/components/admin/SchemeInUseModal.tsx`,
    `apps/web/src/components/admin/SchemeMetaPanel.tsx`,
    `apps/web/src/components/admin/MappingTable.tsx`,
    `apps/web/src/components/admin/SchemeCreateForm.tsx` (있을 때),
  ]
- depends-on. `[1]`

**RED**. 단위 테스트 없음 (refactor, 동작 변경 0 — 기존 단위 테스트 회귀 0 보장). commit prefix `refactor:`.

**GREEN**.
- 각 컴포넌트의 hardcoded korean literal 중 `workflow-scheme-labels.ts` 에 정의된 라벨만 `import { workflowSchemeLabels }` 로 대체
- 영역 — `aria-label`, button text, modal title, empty state heading, sidebar group label, mapping default option text 등 E2E 가 셀렉터로 참조하는 영역만
- 영역 외 (placeholder, Zod error message, 로딩 텍스트 등) — hardcoded 그대로 둠. 전면 i18n migration 은 별 후속 PR
- 기존 단위 테스트 (`apps/web/src/routes/__tests__/admin.workflow-schemes*.test.tsx`, `apps/web/src/components/admin/__tests__/*.test.tsx`) 의 selector 가 같은 라벨 import 로 대체 — 일관성 유지

**REFACTOR**.
- import 그룹화 (i18n 영역 분리)
- 라벨 변경 가능성 검토 — 정본 const 와 실 컴포넌트 텍스트 100% 일치

**검증**.
- `pnpm typecheck` — 타입 에러 0
- `pnpm test -- workflow-scheme` — 기존 단위 테스트 회귀 0 (selectors 가 const 참조로 바뀌었지만 값 동일)
- `pnpm lint` — eslint 통과

---

### Task 4. E2E-1 spec — 스킴 CRUD happy path

**메타**.
- agent. `qa-engineer`
- files. [`apps/web/e2e/workflow-scheme-crud.spec.ts` (신규)]
- depends-on. `[2, 3]`

**RED**. `test:` commit. spec 파일 작성 → playwright 실행 → spec 시나리오 1~4 step 모두 PASS 기대 (UI 사전 존재 + 라벨 const 정합). **"RED 자연 발생 안 함" 사유 — UI 사전 존재**. learnings 2026-05-28 Flyway recursive 사례 패턴 적용 — 본질은 행위 검증 신규.
- 만약 일부 시나리오가 fail → 컨트롤러가 RED 로 간주하고 GREEN 단계 조치 (T3 보강 또는 fixture 갱신).

**GREEN**.
- 파일. `apps/web/e2e/workflow-scheme-crud.spec.ts` 신규
- 시나리오 본문 (spec §E2E-1 4 step). 사용자 로그인 → 목록 → "+ 새 스킴" → 생성 → 상세 진입 → name 수정 → 좌 네비 카운트 갱신
- 셀렉터. fixture 의 `i18nLabels.workflowScheme.*` import 후 `getByRole` / `getByLabel` / `getByText` 사용
- assertion. spec 의 Then 1~4 매칭

**REFACTOR**.
- step 주석 (── Given/When/Then ──) 패턴 일관 (issue-crud-happy.spec.ts 선례)
- test 이름 — `E2E-1 스킴 CRUD — 목록 → 생성 → 상세 → 수정 → 카운트 갱신`

**검증**. `pnpm test:e2e -- workflow-scheme-crud` 단독 실행 — PASS (or RED 발생 시 조치)

---

### Task 5. E2E-2 spec — 매핑 편집 (S4+S5+S6)

**메타**.
- agent. `qa-engineer`
- files. [`apps/web/e2e/workflow-scheme-mappings.spec.ts` (신규)]
- depends-on. `[2, 3]`

**RED**. `test:` commit. spec §E2E-2 3 step 모두 PASS 기대.

**GREEN**.
- 파일. `apps/web/e2e/workflow-scheme-mappings.spec.ts` 신규
- 시나리오 본문 (spec §E2E-2). 커스텀 스킴 상세 → "+ 매핑 추가" → 이슈 타입 + 워크플로우 select → 추가 (낙관적 검증) → "삭제" → 확인 모달 → 매핑 수 -1 → "+ 매핑 추가" 다시 → "기본값 (모든 이슈 타입)" 선택 → default 행 추가
- 셀렉터 정본. fixture 의 `i18nLabels.workflowScheme.mapping.*`
- assertion. POST `__default__` → backend null 변환 매칭은 MSW handler 가 처리, 결과만 assert (default 행 highlight)

**REFACTOR**. step 주석 + test 이름

**검증**. `pnpm test:e2e -- workflow-scheme-mappings` 단독 PASS

---

### Task 6. E2E-3 spec — 표준 스킴 보호

**메타**.
- agent. `qa-engineer`
- files. [`apps/web/e2e/workflow-scheme-standard-protect.spec.ts` (신규)]
- depends-on. `[2, 3]`

**RED**. `test:` commit. spec §E2E-3 2 step PASS 기대.

**GREEN**.
- 파일. `apps/web/e2e/workflow-scheme-standard-protect.spec.ts` 신규
- 시나리오. 표준 스킴 (예. `software-default`) 상세 → 메타패널 "삭제" disabled + tooltip + key/is_default readonly 검증 → name/description 인라인 편집 자유 (D11 결정)
- 셀렉터. `i18nLabels.workflowScheme.standardProtect.deleteDisabledTooltip` 등

**REFACTOR**. tooltip aria-describedby 검증 추가 권장

**검증**. `pnpm test:e2e -- workflow-scheme-standard-protect` 단독 PASS

---

### Task 7. E2E-4 spec — 사용 중 삭제 차단 모달

**메타**.
- agent. `qa-engineer`
- files. [`apps/web/e2e/workflow-scheme-in-use-modal.spec.ts` (신규)]
- depends-on. `[2, 3]`

**RED**. `test:` commit. spec §E2E-4 1 step PASS 기대. **gap G3 — `usedByProjects` link 가 fixture 빈 list 일 경우 link 클릭 시나리오 skip**. fixture 검증 1단계 추가 (`scheme-fixtures.ts` 의 `usedByProjects` 가 1+ 아이템 보유).

**GREEN**.
- 파일. `apps/web/e2e/workflow-scheme-in-use-modal.spec.ts` 신규
- 시나리오. 사용 중 커스텀 스킴 상세 → "삭제" → 409 SCHEME_IN_USE → `SchemeInUseModal` 노출 (role=dialog) → `usedByProjects` list 표시 → 첫 link 클릭 → `/projects/{key}/settings/workflow-scheme` navigate
- fixture 가 비어있으면 — list 표시까지 검증 + link 클릭 skip + 로그 메시지 "fixture usedByProjects 빈 list, link 클릭 시나리오 skip" 명시

**REFACTOR**. fixture 검증 후 link 클릭 assertion 추가 가능 시 추가

**검증**. `pnpm test:e2e -- workflow-scheme-in-use-modal` 단독 PASS

---

### Task 8. E2E-5 spec — 프로젝트 스킴 할당 (S9+S10)

**메타**.
- agent. `qa-engineer`
- files. [`apps/web/e2e/workflow-scheme-assignment.spec.ts` (신규)]
- depends-on. `[2, 3]`

**RED**. `test:` commit. spec §E2E-5 2 step PASS 기대.

**GREEN**.
- 파일. `apps/web/e2e/workflow-scheme-assignment.spec.ts` 신규
- 시나리오. 프로젝트 (예. ATLAS) `/settings/workflow-scheme` → 현재 스킴 표시 → select 변경 → "스킴 지정" 또는 "스킴 변경" 클릭 → PUT UPSERT → 새 스킴 표시. + 미할당 프로젝트 (fixture 또는 별도 seed) → GET 404 → "현재 적용된 스킴 없음" 안내 카드
- 셀렉터. `i18nLabels.workflowScheme.assignment.*`

**REFACTOR**. step 주석

**검증**. `pnpm test:e2e -- workflow-scheme-assignment` 단독 PASS

---

### Task 9. 통합 verify + FR-WF-02 §2.2 D7 마킹 + duration baseline

**메타**.
- agent. `qa-engineer`
- files. [
    `docs/plan/product/project-workflow.md` (D7 `[x]` 마킹),
    `apps/web/playwright.config.ts` (변경 0 검증),
  ]
- depends-on. `[4, 5, 6, 7, 8]`

**RED**. `test:` commit (E2E 통합 회귀 0 확인용 commit, 코드 변경 0 — `git commit --allow-empty` 또는 가벼운 chore 묶음).

**GREEN**.
- `pnpm verify` 전체 (lint + typecheck + test + build) → green
- `pnpm test:e2e` 신규 5 + 기존 10 → 모두 green, duration < +2분 (baseline 비교)
- `docs/plan/product/project-workflow.md` 의 FR-WF-02 §2.2 `D7. E2E (책임. qa-engineer)` `[ ]` → `[x]` 마킹
- (Maxi 결정 시) §BC 완료 게이트 (`§2 (FR-WF 2개) 모두 [x]`) 갱신 — §NFR 측정 deferred trigger 충족 여부 별도

**REFACTOR**. plan 파일의 §verification 결과 단락에 측정 duration baseline 기록

**검증**.
- `pnpm verify` exit 0
- `pnpm test:e2e` 전체 15 spec PASS
- gh PR description 의 체크리스트 5번 (`구현`) 마킹

---

## Plan 메타 요약

- task 수. 9
- wave 수. 4
- 최대 병렬도. 5 (Wave 3)
- TDD 강제. yes (T4~T8 = `test:` commit pure)
- 변형. UI 사전 존재로 "RED 자연 발생 안 함" — learnings 2026-05-28 Flyway recursive 패턴 적용. 본질 = 행위 검증 신규
- controller 통합 검증. wave 종료마다 `pnpm typecheck` 직접 실행 (서브에이전트 scoped 맹점 회피)
- 파일 충돌 0. T2/T3 disjoint, T4~T8 각 spec 파일 disjoint, T9 docs only

## 리뷰 결과

### bts-review-plan (2026-05-29)

- **fast-track 스킵 적용.** bts-review-plan SKILL.md §Step 2 표 — `TYPE ∈ {bugfix, chore, qa}` 는 리뷰 체인 스킵.
- 본 task type = qa, 따라서 plan-eng/ceo/design/devex/autoplan 호출 없음.
- controller inline 자체 검증.
  - ✅ TDD 강제 변형 사유 plan §Plan 메타 + 각 task RED 단락에 명시 (UI 사전 존재 — RED 자연 발생 안 함, learnings 2026-05-28 Flyway recursive 사례 패턴)
  - ✅ 파일 충돌 0 확인 (T2/T3 disjoint, T4~T8 spec 파일 disjoint)
  - ✅ wave 의존성 그래프 — cycle 0, longest path = 4 wave
  - ✅ controller 통합 검증 명시 (wave 종료마다 `pnpm verify`)
  - ✅ learnings (2026-05-26 vi.mock worker scope leak / 2026-05-27 lint-staged + scoped typecheck 맹점 / 2026-05-28 PRE_EXISTING D5 옵션 C / `gh pr merge --delete-branch` 5 step 우회) 모두 plan 본문 또는 spec 본문에 인용/반영
- BLOCKER. 없음 (G-BLOCKER-1 은 spec 단계 결정 완료, 옵션 (C) plan task 흡수).
- 주의 1건 (BLOCKER 아님). T9 (통합 verify) 에서 `pnpm test:e2e` 전체 duration 측정 — baseline 미존재 시 본 task 수치를 baseline 으로 기록 (PR #32 머지 직후 + D7 추가 후).
