# FR-UX-11 F9 — 이슈 목록 셀 인라인 편집

> slug: fr-ux-11-f9-list-cell-inline-edit
> type: ui
> agent: frontend-engineer
> 생성: 2026-08-04

## Brief

**사용자 원문.** `fr-ux-11 f9 진행해줘`

**정본 근거.**
- `docs/plan/product/personalization.md:325` — **F9 — 이슈 목록 셀 인라인 편집**(담당자·우선순위·상태).
  `IssueTable.tsx` · `issue-columns.ts` · `components/issue/meta/*` 재사용 ·
  `components/ui/popover.tsx`(소비처 0→1).
- `docs/plan/product/personalization.md:327` **아키텍처** — 프론트 전용 예상. 기존 이슈 PATCH API 를
  소비한다. 목록 셀은 낙관적 동시성(OCC) 409 를 만나므로 `setQueryData` 부분 갱신 대신
  **invalidate 로 정합**을 맞춘다.
- `docs/design/jira-parity-roadmap.md:61` — F9 행. 선행 **F8**.
- FR-UX-11 의 **D6/D7 마감분**. F8(PR #337)이 D1~D5 를 닫았고 D6 본문의 「F9 목록 셀 3종」·
  D7 의 F9 잔여가 이번 PR 로 충족되면 `[x]` 로 전환된다.

**classify 결과 (override 기록).**
- 스크립트 출력. `type=backend` · `agent=backend-engineer` · `slug=fr-ux-11-f9`
- **정정.** `type=ui` · `agent=frontend-engineer` · `slug=fr-ux-11-f9-list-cell-inline-edit`
- 사유. classify 키워드 신호 0 → `backend` 기본값. 정본 3건이 프론트 전용을 명시하고
  F8(#337)이 백엔드 0줄(`git diff --exit-code` EXIT 0)로 선례를 남겼다.
- `primary_bc` = `issue-tracking` (유지)

**선행 읽기에서 고른 learnings (이 작업 관련).**
- `learnings.md:616` 메타 mutation `setQueryData`(부분응답)가 본문을 placeholder 로 덮는 플리커 —
  메타 mutation 6종은 **invalidate-only 로 통일**돼 있다. F9 가 같은 계열이다.
- `learnings.md:631` UI PR 이 E2E 를 후속 PR 로 미루면 기존 E2E 회귀가 머지 시점에 잠복 —
  목록 화면 셀렉터 충돌 위험. 같은 화면 기존 E2E 동반 실행 필요.
- `learnings.md:600` Zod 응답 스키마와 산재 인라인 mock.
- 메모리 [[fr-ux-11-f8-inline-edit-done]] 의 미해결 4건(E2E 셀렉터 `exact` 판별식 부재 ·
  `disabled:opacity-50` 전역 미적용 · 룰 E 가 D 마커 미검사 · M-3 저장 후 편집창 미닫힘).

## 도메인 정리

**판정. `/bts-domain` 의 grill-with-docs 는 스킵한다** (`type == ui` 스킵 조건 — Maxi 확정
2026-08-03). 스킵 조건의 단서인 *"신규 도메인 개념(새 엔티티·용어·라우트 신설)이 감지되면
진입"* 을 실측으로 확인했고, **감지 0** 이다.

- **BC.** `issue-tracking` (물리 BC 는 `apps/web` · 논리 BC 는 personalization — FR-UX-07 선례 승계)
- **영향 엔티티.** `Issue` 만. 신규 0
- **새 용어.** **0건.** F9 가 다루는 개념은 전부 `glossary.md` 에 이미 있다 —
  `전이 | Transition. 상태 → 상태로 가는 액션`(`:60`) · `게이트 | Gate. 전이에 걸린 조건`(`:61`) ·
  `워크플로우 | Workflow. 이슈의 상태 전이 FSM`(`:15`)
- **기존 결정 충돌.** **0건.** `docs/decisions/` 128건 중 F9 를 언급하는 2건은 **선행 관계만** 기술한다 —
  `2026-08-03-fr-ux-10-f10-context-shortcuts.md:120`(기각 사유 안에서 "이후 F9·F11 이 그 위에 쌓인다") ·
  `2026-07-28-fr-ux-07-active-project-context.md:108`(PR 카운트 표)
- **관련 ADR.** 신규 생성 없음. **승계** 2건 — 위 두 건
- **D1 승계.** FR-UX-11 의 D1 은 F8(#337)이 이미 닫았다. *"인라인 편집 = 새 저장 경로 신설이 아니라
  기존 저장 경로에 진입면을 얹는 것"* 이라는 정립을 F9 가 그대로 승계한다

### ★ spec 으로 넘기는 쟁점 2건 (도메인이 아니라 설계 쟁점)

**쟁점 1 — 상태 셀만 나머지 둘과 구조가 다르다.** 담당자·우선순위는 필드 PATCH 지만
**상태는 FSM 전이**다. 실측한 API 3종.

```
GET  /api/v1/issues/{key}/transitions          가용 전이 목록 — 이슈마다 다르다
POST /api/v1/issues/{key}/transition           body { toStatusKey, expectedVersion }
POST /api/v1/issues/bulk-transitions/available body { issueKeys } → 공통 전이 "교집합"
```

따라서 목록에서 상태 셀을 열려면 **그 행의 가용 전이를 알아야** 하고, 행마다 다르므로
N+1 조회 위험이 있다. 기존 `bulk-transitions/available` 은 **교집합**이라 행별 개별 편집에는
그대로 못 쓴다(교집합은 일괄 전이용). 또 `IssueStateTransition.tsx` 는
`unavailableReason`(`no-workflow` · `terminal`) 분기와 제어값 리셋(C1 회귀 방지)을 갖고 있다.
**조회 전략·재사용 범위를 D2 스펙에서 확정한다.**

**쟁점 2 — 목록에는 선택 개념이 이미 둘이고 F9 가 셋째 상호작용을 얹는다.**
`IssueTable.tsx:159` 가 *"★split 선택(`selectedKey`) ≠ bulk 선택(`selection`) — 이 둘은 완전히
독립된 개념이다"* 라고 코드에 경고를 박아뒀다. F10(#336)이 **커서 = `selectedKey`(URL `?selected=`)**
로 확정했고 **커서 이동이 곧 상세 교체**다. 그 위에서 셀을 클릭하면 「행을 여는 것」과
「셀을 편집하는 것」이 같은 클릭을 두고 경합한다 — F8 의 편차 D-2(텍스트 선택 중이면 진입 안 함)와
동형의 이벤트 경합이 목록에서는 **행 클릭 핸들러 · 체크박스 · 커서 단축키**를 상대로 생긴다.
**진입 제스처와 경합 해소를 D2 스펙에서 확정한다.**

## 스펙

전체 스펙. [docs/specs/2026-08-04-fr-ux-11-f9-list-cell-inline-edit.md](../specs/2026-08-04-fr-ux-11-f9-list-cell-inline-edit.md)

핵심 3줄.
- 담당자·우선순위·상태 셀에 **hover 어포던스**가 뜨고, 클릭하면 popover 로 그 자리에서 바꾼다
- 편집 가능 셀 클릭은 **행 클릭(상세 이동)으로 전파되지 않는다**. 나머지 영역은 기존 그대로
- 가용 전이·권한은 **셀을 열 때만** 조회해 N+1 을 없앤다 → 백엔드 0줄이 성립한다

**확정 결정 6건.**

| ID | 결정 | 비고 |
|---|---|---|
| D-1 | 진입 = hover 어포던스 + 셀 클릭 | **Maxi 확정 2026-08-04.** 어포던스 없는 클릭·편집모드 토글 기각 |
| D-2 | 3종 모두 popover 로 통일 | `ui/popover.tsx` 소비 1→2 |
| D-3 | 전이·권한은 셀 열 때만 조회 | `bulk-transitions/available` 은 **교집합**이라 행별 편집에 부적합 |
| D-4 | 낙관적 필드 patch + `onSettled` invalidate | `useChangeCardField` 승계. `learnings:616` 과 모순 없음(전체 교체가 아니라 필드 patch) |
| D-5 | `issue-columns.ts` 순수 계약 유지 | 편집 셀을 별도 `.tsx` 로 빼고 `createElement` 위임 |
| D-6 | 권한 미확정 시 fail-closed | `IssueAssigneeSelect` 의 기존 `canEdit` 계약 승계 |

**정본 정정 2건 (실측이 정본을 뒤집음).**
- `personalization.md:325` 의 *"`popover.tsx`(소비처 **0→1**)"* 은 거짓 — `ProjectSwitcher.tsx:9`
  가 이미 소비 중이라 **1→2**. F8 의 「인라인 편집 전무」 오류와 같은 계열
- `classify-task.ts` 의 `type=backend` — 정본 3건이 프론트 전용 명시 (Brief 에 기록)

## Brainstorming Check

**ui 경량 경로로 스킵** (Maxi 확정 2026-08-03). 대체 sanity check 를 스펙에 수행했다 —
`## Jira 대조`(계약 §1 4단계 전부) + §2 즉사 계약 교차(`role="status"` 보존이 FR9 로 승격) +
`## 시각 검증 기준`(동반 E2E 8종 + 브라우저 눈확인 7항목).

## Plan

**Goal.** 이슈 목록에서 담당자·우선순위·상태를 셀에서 직접 바꾼다. FR-UX-11 D6/D7 마감.

**Architecture.** 편집 셀은 `components/issues/cells/` 신설 `.tsx` 3종 + 공통 래퍼 1종.
`issue-columns.ts` 는 순수 계약을 유지한 채 `createElement` 로 위임만 한다(D-5). 저장은 목록
캐시 전용 mutation 훅 1개로 모으고 낙관적 필드 patch + `onSettled` invalidate 를 쓴다(D-4).
전이·권한은 popover 가 열려 `PopoverContent` 가 마운트될 때만 조회된다(D-3).

**Tech Stack.** React 19 · TanStack Query v5 · Radix Popover(`components/ui/popover.tsx`) ·
vitest + Testing Library · Playwright · MSW.

### 파일 구조

| 파일 | 책임 | 신규/수정 |
|---|---|---|
| `.husky/pre-commit` | 훅 본체 — worktree 실행 가능 명령만 | 수정 |
| `.gitignore` | `node_modules` 링크까지 무시 | 수정 |
| `scripts/workflow/worktree-hook-wiring.test.ts` | 훅 배선 + **실행 가능성** 판별식 | 수정 |
| `apps/web/src/hooks/use-issue-list-cell-field.ts` | 목록 캐시 전용 필드 변경 mutation | 신규 |
| `apps/web/src/components/issues/cells/EditableCell.tsx` | hover 어포던스 · 전파 차단 · popover 껍데기 | 신규 |
| `apps/web/src/components/issues/cells/PriorityCell.tsx` | 우선순위 편집 | 신규 |
| `apps/web/src/components/issues/cells/AssigneeCell.tsx` | 담당자 편집 | 신규 |
| `apps/web/src/components/issues/cells/StatusCell.tsx` | 상태 전이 편집 (`role="status"` 보존) | 신규 |
| `apps/web/src/components/issues/issue-columns.ts` | 컬럼 정의 — 렌더 위임만 | 수정 |
| `apps/web/src/components/issues/IssueTable.tsx` | `ctx` 에 편집 컨텍스트 전달 | 수정 |
| `apps/web/src/routes/issues.index.tsx` | `projectKey`·쿼리키를 테이블에 전달 | 수정 |
| `apps/web/e2e/issue-list-inline-edit.spec.ts` | S1~S7 | 신규 |

---

### Task 1. pre-commit 훅 봉합 — 판별식 확장 후 수정

**메타**.
- agent: `frontend-engineer`
- files: [`scripts/workflow/worktree-hook-wiring.test.ts`, `.husky/pre-commit`, `.gitignore`, `.lintstagedrc.json`, `apps/web/.lintstagedrc.json`, `.github/workflows/workflow-scripts-ci.yml`]
- depends-on: []

> **범위 확대 2회 (구현 중 실측이 초안을 넓혔다).**
>
> **① `.lintstagedrc.json` — 봉합이 절반이었다.** 훅 본체를 고쳐도 훅이 **부르는 설정**이
> `pnpm --filter @bts/web exec eslint` 를 써서, `apps/web/**` 가 staged 인 커밋에서 같은 자리에
> 다시 죽는다. 이 PR 의 Task 2~9 가 전부 그 경로다. 게다가 초안의 `HOOK_FORBIDDEN_COMMANDS`
> 부분 문자열 `pnpm exec` 는 **`pnpm --filter @bts/web exec` 를 못 잡는다**(사이에 플래그가
> 낀다) — 판별식이 초록인데 결함은 사는 양식이라 정규식으로 교체했다.
>
> **② `.github/workflows/workflow-scripts-ci.yml` — `INPUTS` 확장의 필연적 귀결.**
> 판별식의 CI 트리거 검사가 `INPUTS[].coveredBy` 에서 **파생**되므로, `.lintstagedrc.json` 을
> 입력에 넣는 순간 `on.pull_request.paths` 와 `on.push.paths` **양쪽**이 그 경로를 걸어야 한다.
> 안 걸면 *"`.lintstagedrc.json` 만 고쳐 pnpm 을 되살리는 PR 에서 이 판별식이 0회 실행"* 된다.
> `CLAUDE.md §문서 인덱싱 규칙`의 "판별식 룰 L" 과 같은 정신이다.
>
> **③ `apps/web/.lintstagedrc.json` — ★봉합이 만든 신규 충돌면 (Maxi 확정 2026-08-04).**
> ②까지의 처방은 lint-staged 의 **cwd 를 저장소 루트로 옮겼다**. ESLint 9 flat config 는
> `--config` 사용 시 상대 `files` 패턴의 기준을 **cwd** 로 잡는데, `apps/web/eslint.config.js:85-109`
> 의 PR22 원시 `<button>` 예외 목록 19항이 `'src/routes/issues.$key.tsx'` 형태(`**/` 접두 없음)라
> **루트 기준으로는 한 건도 매칭되지 않는다** → 예외가 통째로 무효화돼 선재 코드가 걸린다.
>
> Task 6 이 그 목록에 있는 파일(`issues.$key.tsx`)을 **처음으로** 건드리면서 드러났다.
> 그 전 커밋들은 전부 목록 밖 파일이라 이 경로를 밟지 않았다 — **봉합이 자기 부작용을 가린
> 구간**이 있었던 셈이다. 계열 [[seal-blinds-existing-guard]] ·
> "공유자원을 옮기면 신규 충돌면을 표로 세라"([[vite-preview-port-cors-align-done]]).
>
> **처방은 원인 치료.** `apps/web/.lintstagedrc.json` 을 신설해 프론트 파일의 cwd 를
> `apps/web` 으로 되돌린다(lint-staged 는 대상 파일에서 가장 가까운 설정을 찾고 그 위치를 cwd 로
> 삼는다). 그러면 **CI(`eslint src`, cwd=`apps/web`)와 훅이 같은 조건에서 같은 판정**을 하게 된다 —
> 지금은 둘이 서로 다른 답을 낸다. 기각안. `eslint.config.js` 19줄에 `**/` 접두(증상 치료 —
> cwd 불일치가 남아 다른 상대 패턴에서 재발하고, PR22 봉인 목록은 `button-primitive-usage.test.ts`
> 와 **짝**이라 한쪽만 고치면 어긋난다) · 별도 PR 이연(그 사이 모든 커밋이 `--no-verify`).
>
> **③-실측 정정 — 처방의 세부가 구현 중에 뒤집혔다.** 초안 지시는 *"루트 설정에서
> `apps/web/**` 항목 제거"* 였는데 **둘 다 불가**임이 샌드박스 프로브 5개로 확인됐다.
> `lint-staged` 의 `runAll.js` 가 결정적이다.
>
> ```js
> const groupCwd = hasExplicitCwd || !hasMultipleConfigs ? cwd : path.dirname(configPath)
> ```
>
> **설정이 2벌 이상일 때만** 설정 파일의 디렉토리가 cwd 가 된다. 그래서 루트 설정을
> 빈 객체로 두면 `ConfigEmptyError` 로 **모든 커밋이 즉사**하고, 삭제하면 1벌이 되어
> **cwd 가 루트로 돌아가 결함이 부활**한다. 채택안은 **2벌 유지**다.
>
> 이 불변식은 **되돌아감이 에러가 아니라 침묵**이라 개수 외에 탐지 수단이 없다 —
> `lint-staged 설정이 2벌 이상이다` 단언을 신설해 못박았고, 실패 메시지에 `runAll.js`
> 원문을 실었다.
>
> **미해결 1건 (코드리뷰 이관).** 루트 `.lintstagedrc.json` 의 `apps/web/**` 항목이 이제
> **도달 불가**다 — lint-staged 는 파일을 디렉토리 기준으로 깊은 설정에 먼저 배정하고
> 글롭 매칭은 그 다음이라, `apps/web` 안의 어떤 파일도 루트 설정에 닿지 않는다(프로브 E 에서
> 루트 태스크가 작업 목록에 뜨지도 않았다). **위험은 낮다** — 판별식이 2벌을 강제하고
> `INPUTS` 가 `apps/web` 설정의 존재를 못박아, 깊은 설정이 사라지면 판별식이 먼저 빨개진다.
> 해소안 두 가지가 실측과 함께 남아 있다. ① `.husky/pre-commit` 을 `(cd apps/web && …)` 로
> 바꿔 1벌로도 cwd 를 잡게 하고 죽은 항목 제거(프로브 F 로 검증) ② 루트 설정에 루트 스코프의
> 실제 lint 작업 부여(현재 `scripts/**` 24개 JS/TS 에 린터가 **없어** 신규 도입이 필요 —
> 추측 구현 금지에 걸려 보류).

**RED**. `scripts/workflow/worktree-hook-wiring.test.ts` 의 `INPUTS` 아래에 상수 2개와 테스트
2개를 추가한다. 기존 `describe` 블록 안에 넣는다.

```ts
/**
 * worktree 에서 실행 불가능한 명령. 훅 본체가 이걸 쓰면 연결해도 **매번 죽는다.**
 *
 * worktree 의 `node_modules` 는 main 을 가리키는 심볼릭 링크다. pnpm 11 의 실행 전
 * 의존성 검사가 경로 불일치를 감지해 `pnpm install` 을 자동 트리거하고,
 * TTY 가 없어 `ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY` 로 중단된다 (2026-08-04 실측).
 */
const HOOK_FORBIDDEN_COMMANDS = ['pnpm exec', 'pnpm run', 'pnpm install'] as const

/** worktree 가 심볼릭 링크로 갖는 경로 — 끝 슬래시를 붙이면 링크를 놓친다. */
const SYMLINKED_IGNORE_PATHS = ['node_modules', 'apps/web/node_modules', '.husky/_'] as const

test('pre-commit 훅이 worktree 에서 실행 가능한 명령만 쓴다', () => {
  const hook = read(INPUTS.hook)
  const offending = hook
    .split('\n')
    .map((line, i) => ({ line: line.trim(), no: i + 1 }))
    .filter(({ line }) => !line.startsWith('#'))
    .filter(({ line }) => HOOK_FORBIDDEN_COMMANDS.some((cmd) => line.includes(cmd)))
    .map(({ line, no }) => `${INPUTS.hook.file}:${no}  ${line}`)

  assert.deepEqual(
    offending,
    [],
    `훅이 worktree 에서 실행 불가능한 명령을 쓴다.\n${offending.join('\n')}\n\n` +
      `BTS 의 모든 실작업은 worktree 안에서 이뤄진다. 훅을 연결해도(층 1) 본체가 이 명령을\n` +
      `쓰면 매 커밋이 ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY 로 죽는다 — 결국\n` +
      `--no-verify 로 우회하게 되고 훅은 다시 장식이 된다.\n` +
      `처방. 'node_modules/.bin/<도구>' 를 직접 호출한다 (pnpm 래퍼 우회).`,
  )
})

test('.gitignore 가 worktree 심볼릭 링크를 전부 무시한다 (끝 슬래시 없음)', () => {
  const lines = read(INPUTS.gitignore)
    .split('\n')
    .map((l) => l.trim())

  const bad = SYMLINKED_IGNORE_PATHS.filter((p) => lines.includes(`${p}/`))

  assert.deepEqual(
    bad,
    [],
    `다음 규칙이 끝 슬래시로 적혀 있다: ${bad.join(', ')}\n\n` +
      `끝 슬래시는 **디렉토리만** 매칭한다. worktree 가 갖는 것은 심볼릭 링크라 매칭되지 않아\n` +
      `매 작업이 untracked 를 달고 다닌다. #329 가 '.husky/_' 에 대해 같은 결함을 고쳤다 —\n` +
      `node_modules 갈래도 같은 규칙을 따라야 한다.`,
  )
})
```

**RED 확인**. `pnpm test:workflow` (또는 `node --test scripts/workflow/`) — 위 2개가 **빨강**.
- 1번 실패 사유. `.husky/pre-commit:1` 의 `pnpm exec lint-staged`
- 2번 실패 사유. `.gitignore:24` `node_modules/` · `:25` `apps/web/node_modules/`

**GREEN**. 두 파일을 고친다.

`.husky/pre-commit` 1행.

```diff
-pnpm exec lint-staged
+# `pnpm exec` 를 쓰지 않는다 — worktree 의 심볼릭 node_modules 에서 의존성 검사가
+# pnpm install 을 트리거해 무-TTY 로 죽는다(아래 build-doc-index 주석과 같은 이유).
+node_modules/.bin/lint-staged
```

`.gitignore:24-25`.

```diff
-node_modules/
-apps/web/node_modules/
+# 끝 슬래시를 붙이지 않는다 — worktree 는 이 경로를 main 트리로 향하는 **심볼릭 링크**로
+# 갖는다(bts-start Step 3). 끝 슬래시는 디렉토리만 매칭해 그 링크를 untracked 로 남긴다.
+node_modules
+apps/web/node_modules
```

**REFACTOR**. 없음 (설정 2줄).

**검증**.
- `pnpm test:workflow` → 신규 2개 포함 전량 초록
- **뮤테이션**. `.husky/pre-commit` 1행을 `pnpm exec lint-staged` 로 되돌리면 1번이 빨강 —
  가드 비-공허 확인. `.gitignore` 에 끝 슬래시를 되붙이면 2번이 빨강
- **실물 확인**. worktree 에서 `--no-verify` **없이** 커밋이 통과하는가 (이 task 의 커밋 자체가 증인)
- `git status` 에 `?? node_modules` 가 사라졌는가
- 기존 E2E. 해당 없음 (프론트 무관)
- 브라우저 눈확인. 해당 없음

---

### Task 2. 목록 캐시 전용 셀 필드 변경 mutation 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-issue-list-cell-field.ts`, `apps/web/src/hooks/use-issue-list-cell-field.test.tsx`]
- depends-on: []

**왜 새로 만드나**. `useChangeCardField`(보드)가 같은 일을 하지만 캐시가 `BoardDetail` 전용이다
(`patchCardField` 가 `board.columns` 를 순회). `useTransitionIssue` 는 `['issue', key]` 와 전이
목록만 무효화해 **목록 캐시를 갱신하지 않는다**. 목록(`Page<IssueResponse>`)용이 없다.

**RED**. `use-issue-list-cell-field.test.tsx`.

```tsx
// 목록 셀 필드 변경 mutation 훅 테스트 — 낙관적 patch · 롤백 · invalidate (FR-UX-11 F9 D-4)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useIssueListCellField, patchIssueInList } from './use-issue-list-cell-field'
import type { IssueResponse } from '@/api/issues'

vi.mock('@/api/issues', () => ({
  updateIssue: vi.fn(),
  changeAssignee: vi.fn(),
  transitionIssue: vi.fn(),
}))

function makeIssue(overrides: Partial<IssueResponse> = {}): IssueResponse {
  return {
    key: 'ATLAS-1',
    summary: '테스트 이슈',
    currentStateKey: 'TODO',
    assigneeId: null,
    priority: 3,
    priorityName: '보통',
    version: 1,
    updatedAt: '2026-08-04T00:00:00Z',
    ...overrides,
  } as IssueResponse
}

const LIST_KEY = ['issues', 'ATLAS', 0, {}, null] as const

describe('patchIssueInList (순수 함수)', () => {
  it('대상 이슈의 지정 필드만 바꾸고 나머지 행은 그대로 둔다', () => {
    const page = { content: [makeIssue(), makeIssue({ key: 'ATLAS-2' })] }
    const next = patchIssueInList(page, 'ATLAS-1', { priority: 1 })

    expect(next.content[0].priority).toBe(1)
    expect(next.content[0].summary).toBe('테스트 이슈')   // 다른 필드 보존
    expect(next.content[1]).toBe(page.content[1])          // 무관 행은 참조까지 동일
    expect(page.content[0].priority).toBe(3)               // 원본 불변
  })
})

describe('useIssueListCellField', () => {
  let queryClient: QueryClient
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    queryClient.setQueryData(LIST_KEY, { content: [makeIssue()] })
  })

  it('저장 실패 시 낙관적 변경을 스냅샷으로 되돌린다', async () => {
    const { updateIssue } = await import('@/api/issues')
    vi.mocked(updateIssue).mockRejectedValue(new Error('409'))

    const { result } = renderHook(() => useIssueListCellField(LIST_KEY), { wrapper })
    result.current.mutate({ issueKey: 'ATLAS-1', field: 'priority', toPriority: 1, expectedVersion: 1 })

    await waitFor(() => expect(result.current.isError).toBe(true))
    const cached = queryClient.getQueryData<{ content: IssueResponse[] }>(LIST_KEY)
    expect(cached?.content[0].priority).toBe(3)   // 롤백됨
  })

  it('409 TRANSITION_NOT_ALLOWED 와 VERSION_CONFLICT 를 다른 문구로 안내한다 (FR15)', async () => {
    const { transitionIssue } = await import('@/api/issues')
    const { toast } = await import('sonner')

    vi.mocked(transitionIssue).mockRejectedValueOnce(
      new ApiError(409, { errorCode: 'TRANSITION_NOT_ALLOWED' }),
    )
    const { result } = renderHook(() => useIssueListCellField(LIST_KEY), { wrapper })
    result.current.mutate({ issueKey: 'ATLAS-1', field: 'status', toStatusKey: 'DONE', expectedVersion: 1 })
    await waitFor(() => expect(result.current.isError).toBe(true))
    const firstMessage = vi.mocked(toast.error).mock.calls.at(-1)?.[0]

    vi.mocked(transitionIssue).mockRejectedValueOnce(
      new ApiError(409, { errorCode: 'VERSION_CONFLICT' }),
    )
    const second = renderHook(() => useIssueListCellField(LIST_KEY), { wrapper })
    second.result.current.mutate({ issueKey: 'ATLAS-1', field: 'status', toStatusKey: 'DONE', expectedVersion: 1 })
    await waitFor(() => expect(second.result.current.isError).toBe(true))
    const secondMessage = vi.mocked(toast.error).mock.calls.at(-1)?.[0]

    expect(firstMessage).not.toBe(secondMessage)
  })
})
```

**RED 확인**. `cd apps/web && node_modules/.bin/vitest run src/hooks/use-issue-list-cell-field.test.tsx`
→ 실패. `use-issue-list-cell-field` 모듈 없음.

**GREEN**. `use-issue-list-cell-field.ts` 신규.

```ts
// 이슈 목록 셀에서 담당자·우선순위·상태를 바꾸는 mutation 훅 — 목록 캐시 전용 (FR-UX-11 F9)
import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { QueryKey } from '@tanstack/react-query'
import { toast } from 'sonner'
import { changeAssignee, updateIssue, transitionIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { ApiError } from '@/api/client'
import { issueDetailStrings } from '@/i18n/ko'

/** 목록 한 페이지 — 이 훅이 건드리는 최소 형태만 요구한다 */
interface IssueListPage {
  content: IssueResponse[]
}

/** 셀 편집이 적용하는 필드 patch. undefined 인 필드는 미변경. */
export interface IssueCellPatch {
  assigneeId?: string | null
  priority?: number
  currentStateKey?: string
  version?: number
}

/**
 * 목록 한 페이지에서 대상 이슈에만 patch 를 병합한다. 원본은 변형하지 않는다.
 *
 * 무관한 행은 **참조까지 그대로** 반환해 불필요한 리렌더를 막는다.
 */
export function patchIssueInList(
  page: IssueListPage,
  issueKey: string,
  patch: IssueCellPatch,
): IssueListPage {
  return {
    ...page,
    content: page.content.map((issue) => (issue.key === issueKey ? { ...issue, ...patch } : issue)),
  }
}

/** mutate 호출 변수 */
export interface IssueCellFieldVars {
  issueKey: string
  field: 'assignee' | 'priority' | 'status'
  toAssigneeId?: string | null
  toPriority?: number
  toStatusKey?: string
  expectedVersion: number
}

/** field 별 실제 API 호출 분기 */
function requestCellChange(vars: IssueCellFieldVars): Promise<IssueResponse> {
  switch (vars.field) {
    case 'assignee': {
      if (vars.toAssigneeId === undefined) {
        throw new Error('toAssigneeId is required when field is "assignee"')
      }
      return changeAssignee(vars.issueKey, {
        assigneeId: vars.toAssigneeId,
        expectedVersion: vars.expectedVersion,
      })
    }
    case 'priority': {
      if (vars.toPriority === undefined) {
        throw new Error('toPriority is required when field is "priority"')
      }
      return updateIssue(vars.issueKey, {
        priority: vars.toPriority,
        expectedVersion: vars.expectedVersion,
      })
    }
    case 'status': {
      if (vars.toStatusKey === undefined) {
        throw new Error('toStatusKey is required when field is "status"')
      }
      return transitionIssue(vars.issueKey, {
        toStatusKey: vars.toStatusKey,
        expectedVersion: vars.expectedVersion,
      })
    }
    default: {
      const exhaustiveCheck: never = vars.field
      throw new Error(`Unsupported field: ${String(exhaustiveCheck)}`)
    }
  }
}

/** onMutate 단계에 적용할 낙관적 patch. version 은 서버 응답으로만 갱신한다. */
function buildOptimisticPatch(vars: IssueCellFieldVars): IssueCellPatch {
  switch (vars.field) {
    case 'assignee':
      return { assigneeId: vars.toAssigneeId }
    case 'priority':
      return { priority: vars.toPriority }
    case 'status':
      return { currentStateKey: vars.toStatusKey }
    default: {
      const exhaustiveCheck: never = vars.field
      throw new Error(`Unsupported field: ${String(exhaustiveCheck)}`)
    }
  }
}

/** field 별 일반 실패 문구 */
function buildErrorMessage(field: IssueCellFieldVars['field']): string {
  switch (field) {
    case 'assignee':
      return '담당자 변경 중 문제가 발생했습니다. 다시 시도해 주세요.'
    case 'priority':
      return '우선순위 변경 중 문제가 발생했습니다. 다시 시도해 주세요.'
    case 'status':
      return '상태 변경 중 문제가 발생했습니다. 다시 시도해 주세요.'
    default: {
      const exhaustiveCheck: never = field
      throw new Error(`Unsupported field: ${String(exhaustiveCheck)}`)
    }
  }
}

/**
 * 실패 사유별 안내 문구 (FR15).
 *
 * 상세 화면 `issues.$key.tsx:359-378` 이 이미 이 3분기를 갖고 있다 — 목록도 같은 어휘를 쓴다.
 * `409` 를 뭉개면 "내가 못 하는 전이" 와 "남이 먼저 바꿔서 낡은 버전" 이 같은 문구로 나온다.
 */
function resolveCellErrorMessage(err: unknown, field: IssueCellFieldVars['field']): string {
  if (!(err instanceof ApiError)) return buildErrorMessage(field)

  if (err.status === 422) return issueDetailStrings.transitionWorkflowNotConfiguredError

  if (err.status === 409) {
    const body = err.body as Record<string, unknown> | undefined
    const errorCode = typeof body?.['errorCode'] === 'string' ? body['errorCode'] : ''
    if (errorCode === 'TRANSITION_NOT_ALLOWED') return issueDetailStrings.transitionNotAllowedError
    return field === 'status'
      ? issueDetailStrings.transitionVersionConflictError
      : issueDetailStrings.versionConflictError
  }

  return buildErrorMessage(field)
}

/**
 * 목록 셀 인라인 편집 mutation 훅.
 *
 * `useChangeCardField`(보드)와 같은 4단계를 쓰되 캐시 대상이 이슈 목록 페이지다.
 * `setQueryData` 로 **응답 전체를 교체하지 않고 변경 필드만 patch** 한다 —
 * PATCH 응답은 부분 뷰(`descriptionHtml` 항상 null)라 통째 교체하면 파생 필드가 사라진다
 * (learnings 2026-05-30 C2).
 *
 * @param listQueryKey 이 목록의 queryKey — `['issues', projectKey, page, filter, sort]`
 */
export function useIssueListCellField(listQueryKey: QueryKey) {
  const queryClient = useQueryClient()

  return useMutation<IssueResponse, unknown, IssueCellFieldVars, { snapshot: IssueListPage | undefined }>({
    mutationFn: requestCellChange,

    onMutate: async (vars) => {
      await queryClient.cancelQueries({ queryKey: listQueryKey })
      const snapshot = queryClient.getQueryData<IssueListPage>(listQueryKey)
      queryClient.setQueryData<IssueListPage>(listQueryKey, (prev) =>
        prev ? patchIssueInList(prev, vars.issueKey, buildOptimisticPatch(vars)) : prev,
      )
      return { snapshot }
    },

    // ★사유별 안내 — 상세 화면(`issues.$key.tsx:359-378`)의 분기를 승계한다 (FR15).
    // 단일 toast 로 뭉개면 "왜 실패했는지" 를 사용자가 알 수 없다.
    onError: (err, vars, ctx) => {
      if (ctx?.snapshot !== undefined) {
        queryClient.setQueryData(listQueryKey, ctx.snapshot)
      }
      toast.error(resolveCellErrorMessage(err, vars.field))
    },

    onSuccess: (data) => {
      queryClient.setQueryData<IssueListPage>(listQueryKey, (prev) =>
        prev
          ? patchIssueInList(prev, data.key, {
              assigneeId: data.assigneeId,
              priority: data.priority,
              currentStateKey: data.currentStateKey,
              version: data.version,
            })
          : prev,
      )
    },

    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: listQueryKey })
    },
  })
}
```

**REFACTOR**. 없음 (`useChangeCardField` 의 검증된 구조를 따랐다).

**검증**.
- `cd apps/web && node_modules/.bin/vitest run src/hooks/use-issue-list-cell-field.test.tsx` 초록
- **뮤테이션**. `onError` 의 롤백 `setQueryData` 를 지우면 롤백 테스트 빨강
- 기존 E2E. 해당 없음 (훅 단독, 아직 소비처 0)
- 브라우저 눈확인. 해당 없음

---

### Task 3. `EditableCell` 공통 래퍼 — hover 어포던스 · 전파 차단 · popover

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issues/cells/EditableCell.tsx`, `apps/web/src/components/issues/cells/EditableCell.test.tsx`]
- depends-on: []

**RED**. `EditableCell.test.tsx`.

```tsx
// 편집 가능 셀 공통 래퍼 테스트 — 전파 차단 · 어포던스 · 권한 (FR-UX-11 F9 FR2·FR3·FR10)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EditableCell } from './EditableCell'

describe('EditableCell', () => {
  it('셀 클릭이 행 클릭 핸들러로 전파되지 않는다 (FR3)', async () => {
    const onRowClick = vi.fn()
    render(
      <table><tbody>
        <tr onClick={onRowClick}><td>
          <EditableCell label="우선순위 편집" display={<span>보통</span>}>
            <div>편집 내용</div>
          </EditableCell>
        </td></tr>
      </tbody></table>,
    )

    await userEvent.click(screen.getByRole('button', { name: '우선순위 편집' }))

    expect(onRowClick).not.toHaveBeenCalled()
    expect(screen.getByText('편집 내용')).toBeInTheDocument()
  })

  it('닫혀 있는 동안 자식(popover 내용)을 마운트하지 않는다 (FR12·NFR1)', () => {
    const spy = vi.fn()
    function Probe() { spy(); return <div>편집 내용</div> }

    render(
      <EditableCell label="우선순위 편집" display={<span>보통</span>}>
        <Probe />
      </EditableCell>,
    )

    expect(spy).not.toHaveBeenCalled()
  })

  it('Esc 로 닫아도 행 클릭 핸들러가 불리지 않는다 (E2)', async () => {
    const onRowClick = vi.fn()
    render(
      <table><tbody>
        <tr onClick={onRowClick}><td>
          <EditableCell label="우선순위 편집" display={<span>보통</span>}>
            <div>편집 내용</div>
          </EditableCell>
        </td></tr>
      </tbody></table>,
    )

    await userEvent.click(screen.getByRole('button', { name: '우선순위 편집' }))
    await userEvent.keyboard('{Escape}')

    expect(screen.queryByText('편집 내용')).not.toBeInTheDocument()
    expect(onRowClick).not.toHaveBeenCalled()
  })
})
```

**RED 확인**. `node_modules/.bin/vitest run src/components/issues/cells/EditableCell.test.tsx`
→ 모듈 없음으로 실패.

**GREEN**. `EditableCell.tsx`.

```tsx
// 이슈 목록의 편집 가능 셀 공통 래퍼 — hover 어포던스·행 클릭 전파 차단·popover (FR-UX-11 F9)
import type { JSX, ReactNode } from 'react'
import { useState } from 'react'
import { Popover, PopoverTrigger, PopoverContent } from '@/components/ui/popover'

/**
 * popover 안 선택지에 붙이는 공통 크기 클래스 (디자인 리뷰 Pass 6, Maxi 확정 2026-08-04).
 *
 * 마우스는 28px(`h-7`, 목록 밀도 유지), **손가락은 44px**. `pointer: coarse` 는 정밀 포인터가
 * 없는 입력(터치)에서만 참이라 데스크톱을 건드리지 않는다. 상세 화면
 * `IssueAssigneeSelect` 가 이미 `min-h-[44px]` 계약을 갖고 있어 일관성도 맞는다.
 */
export const CELL_OPTION_CLASS = 'w-full justify-start pointer-coarse:min-h-[44px]'

/** EditableCell props */
export interface EditableCellProps {
  /** 트리거의 접근성 이름 — 셀마다 고유해야 e2e strict mode 충돌이 없다 */
  label: string
  /** 닫힌 상태에서 보이는 내용 (배지·텍스트 등). role 을 가진 노드를 그대로 넣을 수 있다 */
  display: ReactNode
  /** popover 내용. **열렸을 때만 마운트된다** (FR12 — 전이·권한 조회를 지연시키는 장치) */
  children: ReactNode
  /**
   * 제어형 열림 상태. 미전달이면 자체 관리한다.
   *
   * 제어형이 필요한 이유 둘 — ① 저장 성공 시 **코드로 닫는다**(Maxi 확정 2026-08-04)
   * ② 종료 전이는 popover 를 닫고 결의안 모달로 넘긴다(FR14).
   */
  open?: boolean
  onOpenChange?: (open: boolean) => void
}

/**
 * 편집 가능 셀 래퍼.
 *
 * - **행 클릭 전파 차단**. `IssueTable.tsx` 는 `<TableRow onClick>` 으로 행 전체를 상세로
 *   보낸다. 편집 트리거의 클릭이 거기까지 올라가면 편집과 이동이 동시에 일어난다.
 *   체크박스(`IssueTable.tsx`)·키 링크(`issue-columns.ts`)가 쓰는 것과 같은 처방이다.
 * - **닫힘 시 자식 미마운트**. Radix `PopoverContent` 는 닫히면 언마운트되므로 자식이 가진
 *   조회 훅도 돌지 않는다. 이것이 D-3(셀 열 때만 조회)의 실제 이행 수단이다.
 * - **어포던스**. hover·focus 시 테두리를 띄워 "여기는 바꿀 수 있다" 를 클릭 전에 알린다(FR2).
 *   색은 ADS 토큰만 쓴다(NFR5).
 */
export function EditableCell({
  label, display, children, open: controlledOpen, onOpenChange,
}: EditableCellProps): JSX.Element {
  const [uncontrolledOpen, setUncontrolledOpen] = useState(false)
  const open = controlledOpen ?? uncontrolledOpen
  const setOpen = onOpenChange ?? setUncontrolledOpen

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger
        aria-label={label}
        // ★토큰명 주의 — `--border-default` 는 **존재하지 않는다**(index.css 실측). 실제 이름은
        // `--border`(라이트 #DCDFE4 · 다크 #2C333A). 없는 토큰을 쓰면 ring 이 렌더되지 않아
        // hover 어포던스(FR2)가 조용히 사라진다 — 디자인 리뷰 Pass 5 가 잡은 실버그.
        className="w-full rounded px-1 text-left ring-1 ring-transparent hover:ring-(--border) focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        onClick={(event) => event.stopPropagation()}
      >
        {display}
      </PopoverTrigger>
      {/* onClick 전파 차단 — popover 내용은 DOM 상 행 밖(portal)이지만 React 합성 이벤트는
          트리거 기준으로 버블링하므로 내용 클릭도 행까지 올라간다. */}
      <PopoverContent className="w-64 p-2" onClick={(event) => event.stopPropagation()}>
        {children}
      </PopoverContent>
    </Popover>
  )
}
```

**REFACTOR**. 트리거 className 이 3개 셀에서 동일하므로 상수로 뽑지 않는다 — 래퍼 안에 한 번만
있어 중복이 없다.

**추가 RED (디자인 리뷰 Pass 5 — 실버그 회귀 가드)**. 없는 토큰을 쓰면 조용히 사라지므로
**토큰 이름을 테스트로 못박는다**. 계산값이 아니라 클래스 문자열을 본다(jsdom 은 커스텀
프로퍼티를 해석하지 않아 계산값 단언은 공허해진다 — F8 의 커서 단언 사고와 같은 함정).

```tsx
it('hover 어포던스가 실재하는 토큰을 참조한다 (Pass 5 실버그 회귀 가드)', () => {
  render(<EditableCell label="우선순위 편집" display={<span>보통</span>}><div /></EditableCell>)

  const trigger = screen.getByRole('button', { name: '우선순위 편집' })
  // index.css 의 실제 토큰은 --border 다. --border-default 는 존재하지 않는다.
  expect(trigger.className).toContain('hover:ring-(--border)')
  expect(trigger.className).not.toContain('--border-default')
})
```

**검증**.
- `node_modules/.bin/vitest run src/components/issues/cells/EditableCell.test.tsx` 초록
- **뮤테이션**. `onClick={(e) => e.stopPropagation()}` 을 지우면 전파 차단 테스트 빨강.
  토큰을 `--border-default` 로 되돌리면 Pass 5 가드 빨강
- **토큰 실재 확인**. `grep -n '\-\-border:' apps/web/src/index.css` 가 라이트·다크 양쪽에서 잡히는가
- 기존 E2E. 해당 없음 (아직 배선 전)
- **브라우저 눈확인**. 이 task 단독으로는 화면 변화 없음 (Task 7 배선 후 확인)

---

### Task 4. 우선순위 셀

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issues/cells/PriorityCell.tsx`, `apps/web/src/components/issues/cells/PriorityCell.test.tsx`]
- depends-on: [2, 3]

**RED**. 우선순위 1~5 선택 시 `onChange(값)` 호출 · 권한 없으면 비활성 · 저장 중 비활성.

```tsx
// 우선순위 셀 테스트 (FR-UX-11 F9 FR6·FR10·NFR3)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PriorityCellEditor } from './PriorityCell'

describe('PriorityCellEditor', () => {
  it('우선순위를 고르면 그 값으로 onChange 를 부른다 (FR6)', async () => {
    const onChange = vi.fn()
    render(<PriorityCellEditor value={3} canEdit onChange={onChange} isSaving={false} />)

    await userEvent.click(screen.getByRole('button', { name: '높음' }))

    expect(onChange).toHaveBeenCalledWith(2)
  })

  it('권한이 없으면 모든 선택지가 비활성이다 (FR10 fail-closed)', () => {
    render(<PriorityCellEditor value={3} canEdit={false} onChange={vi.fn()} isSaving={false} />)

    for (const button of screen.getAllByRole('button')) {
      expect(button).toBeDisabled()
    }
    expect(screen.getByText('편집 권한이 없습니다.')).toBeInTheDocument()
  })

  it('저장 중이면 비활성이다 (NFR3 중복 제출 차단)', () => {
    render(<PriorityCellEditor value={3} canEdit onChange={vi.fn()} isSaving />)

    expect(screen.getByRole('button', { name: '높음' })).toBeDisabled()
  })
})
```

**RED 확인**. `node_modules/.bin/vitest run src/components/issues/cells/PriorityCell.test.tsx` → 빨강.

**GREEN**. `PriorityCell.tsx` — 표시부(`PriorityCellDisplay`)와 편집부(`PriorityCellEditor`)를
분리 export 하고, 조립(`PriorityCell`)은 `EditableCell` 로 감싼다.

```tsx
// 이슈 목록 우선순위 셀 — 클릭해 그 자리에서 바꾼다 (FR-UX-11 F9)
import type { JSX } from 'react'
import { issueDetailStrings } from '@/i18n/ko'
import { Button } from '@/components/ui/button'
import { EditableCell } from './EditableCell'

/** 선택 가능한 우선순위 (1=가장 높음 ~ 5=가장 낮음) — IssuePrioritySelect 와 동일 집합 */
const PRIORITIES = [1, 2, 3, 4, 5] as const

/** PriorityCellEditor props */
export interface PriorityCellEditorProps {
  value: number
  canEdit: boolean
  isSaving: boolean
  onChange: (priority: number) => void
}

/**
 * popover 안에 뜨는 우선순위 선택 목록.
 *
 * 상세 화면의 `IssuePrioritySelect` 는 `min-h-[44px]`·`w-full` 네이티브 `<select>` 라
 * 목록 셀 popover 안에서는 과하다. 값 집합(1~5)과 라벨(`priorityNames`)은 **같은 정본**을
 * 쓰되 표현만 목록에 맞춘다.
 */
export function PriorityCellEditor({ value, canEdit, isSaving, onChange }: PriorityCellEditorProps): JSX.Element {
  return (
    <div className="flex flex-col gap-0.5">
      {!canEdit && <p className="px-2 py-1 text-xs text-(--text-subtle)">편집 권한이 없습니다.</p>}
      {PRIORITIES.map((p) => (
        <Button
          key={p}
          type="button"
          variant="ghost"
          size="sm"
          disabled={!canEdit || isSaving}
          aria-current={p === value ? 'true' : undefined}
          onClick={() => onChange(p)}
          className="justify-start"
        >
          {issueDetailStrings.priorityNames[p]}
        </Button>
      ))}
    </div>
  )
}
```

조립부는 Task 7 에서 `IssueColumnRenderContext` 가 확정된 뒤 붙인다 — 이 task 는 편집부까지.

**REFACTOR**. `PRIORITIES`·라벨 정본을 `IssuePrioritySelect` 와 공유할지 검토. 지금은 라벨만
공유(`issueDetailStrings.priorityNames`)하고 배열은 각자 둔다 — 5줄짜리 상수를 공유 모듈로
빼면 소비처 2곳뿐인데 파일이 하나 늘어난다(YAGNI).

**검증**.
- `node_modules/.bin/vitest run src/components/issues/cells/PriorityCell.test.tsx` 초록
- **뮤테이션**. `disabled={!canEdit || isSaving}` 에서 `|| isSaving` 을 지우면 NFR3 테스트 빨강.
  `!canEdit ||` 을 지우면 FR10 테스트 빨강
- 기존 E2E. 해당 없음
- 브라우저 눈확인. Task 7 후

---

### Task 5. 담당자 셀

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issues/cells/AssigneeCell.tsx`, `apps/web/src/components/issues/cells/AssigneeCell.test.tsx`]
- depends-on: [2, 3]

**RED**. 검색 → 후보 선택 → `onChange(uuid)` · 해제 → `onChange(null)` · 권한 없으면 비활성.

```tsx
// 담당자 셀 테스트 (FR-UX-11 F9 FR5·FR10·E6)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AssigneeCellEditor } from './AssigneeCell'
import type { UserSummary } from '@/api/users'

const USERS: UserSummary[] = [
  { id: '11111111-1111-1111-1111-111111111111', username: 'maxi', displayName: '맥시' } as UserSummary,
]

describe('AssigneeCellEditor', () => {
  it('후보를 고르면 그 UUID 로 onChange 를 부른다 (FR5)', async () => {
    const onChange = vi.fn()
    render(
      <AssigneeCellEditor
        value={null} users={USERS} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={onChange}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '맥시' }))

    expect(onChange).toHaveBeenCalledWith('11111111-1111-1111-1111-111111111111')
  })

  it('담당자가 있으면 해제 버튼이 null 로 onChange 를 부른다 (FR5)', async () => {
    const onChange = vi.fn()
    render(
      <AssigneeCellEditor
        value="11111111-1111-1111-1111-111111111111" users={[]} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={onChange}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '담당자 해제' }))

    expect(onChange).toHaveBeenCalledWith(null)
  })

  it('검색 결과가 없으면 빈 상태를 안내한다 (E6)', () => {
    render(
      <AssigneeCellEditor
        value={null} currentAssignee={null} users={[]} isLoading={false} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    expect(screen.getByText('검색 결과가 없습니다.')).toBeInTheDocument()
  })

  it('검색 중에는 "결과 없음" 대신 진행 상태를 보인다 (디자인 리뷰 Pass 2)', () => {
    render(
      <AssigneeCellEditor
        value={null} currentAssignee={null} users={[]} isLoading canEdit isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    expect(screen.getByText('검색 중…')).toBeInTheDocument()
    expect(screen.queryByText('검색 결과가 없습니다.')).not.toBeInTheDocument()
  })

  it('popover 맨 위에 현재 담당자를 보인다 (디자인 리뷰 Pass 1)', () => {
    render(
      <AssigneeCellEditor
        value={USERS[0].id} currentAssignee={USERS[0]} users={[]} isLoading={false} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    expect(screen.getByTestId('cell-assignee-current')).toHaveTextContent('맥시')
  })

  it('권한이 없으면 검색창이 비활성이다 (FR10 fail-closed)', () => {
    render(
      <AssigneeCellEditor
        value={null} users={USERS} canEdit={false} isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    expect(screen.getByRole('textbox', { name: '담당자 검색' })).toBeDisabled()
  })

  it('Enter 로 폼이 제출되지 않도록 기본동작을 막는다 (FR-UX-09 F2 회귀 방지)', async () => {
    render(
      <AssigneeCellEditor
        value={null} users={USERS} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    const input = screen.getByRole('textbox', { name: '담당자 검색' })
    input.focus()
    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    input.dispatchEvent(event)

    expect(event.defaultPrevented).toBe(true)
  })
})
```

**RED 확인**. 모듈 없음으로 빨강.

**GREEN**. `AssigneeCell.tsx` 의 `AssigneeCellEditor` — 검색 input + 후보 버튼 목록 + 해제 버튼.
`IssueAssigneeSelect` 의 **계약 3가지를 승계**한다. ① `canEdit=false → disabled`(fail-closed)
② `Enter` 기본동작 차단(폼 안 암묵 제출 방지 — FR-UX-09 F2 에서 실제 사고) ③ 표시 이름은
`displayName ?? username`.

```tsx
// 이슈 목록 담당자 셀 — 클릭해 그 자리에서 바꾼다 (FR-UX-11 F9)
import type { JSX } from 'react'
import type { UserSummary } from '@/api/users'
import { Button } from '@/components/ui/button'

/** AssigneeCellEditor props */
export interface AssigneeCellEditorProps {
  /** 현재 담당자 UUID. null 이면 미배정 */
  value: string | null
  /**
   * 현재 담당자 정보 — popover 맨 위에 보여준다 (디자인 리뷰 Pass 1).
   *
   * popover 가 셀을 가리므로 "지금 누구인지" 가 화면에서 사라진다. 상세 화면
   * `IssueAssigneeSelect` 도 같은 이유로 현재 담당자를 맨 위에 둔다.
   */
  currentAssignee: UserSummary | null
  /** 검색 결과 후보 */
  users: UserSummary[]
  /** 검색 진행 중 — true 면 "검색 결과가 없습니다" 를 띄우지 않는다 (Pass 2) */
  isLoading: boolean
  canEdit: boolean
  isSaving: boolean
  onSearch: (query: string) => void
  /** UUID 또는 null(해제) */
  onChange: (userId: string | null) => void
}

/** 표시 이름 — displayName 우선, 없으면 username (IssueAssigneeSelect 와 동일 규칙) */
function getDisplayName(user: UserSummary): string {
  return user.displayName ?? user.username
}

/** popover 안에 뜨는 담당자 검색·선택 목록. */
export function AssigneeCellEditor({
  value, currentAssignee, users, isLoading, canEdit, isSaving, onSearch, onChange,
}: AssigneeCellEditorProps): JSX.Element {
  return (
    <div className="flex flex-col gap-1.5">
      {/* 현재 담당자 — popover 가 셀을 가리므로 여기서 다시 보여준다 (Pass 1) */}
      <p className="truncate text-sm font-medium" data-testid="cell-assignee-current">
        {currentAssignee !== null ? getDisplayName(currentAssignee) : '미배정'}
      </p>

      <input
        type="text"
        aria-label="담당자 검색"
        placeholder="이름으로 검색"
        disabled={!canEdit || isSaving}
        onChange={(e) => onSearch(e.target.value)}
        // ★Enter 를 막는다 — 이 칸은 값을 넣는 곳이 아니라 검색창이다. 폼 안에 들어가면
        // HTML 암묵 제출이 일어난다(FR-UX-09 F2 에서 실제로 이슈가 생성된 사고).
        onKeyDown={(e) => { if (e.key === 'Enter') e.preventDefault() }}
        className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-40"
      />

      {!canEdit && <p className="text-xs text-(--text-subtle)">편집 권한이 없습니다.</p>}

      {value !== null && (
        <Button
          type="button" variant="ghost" size="sm"
          disabled={!canEdit || isSaving}
          onClick={() => onChange(null)}
          className={`${CELL_OPTION_CLASS} text-muted-foreground hover:text-destructive`}
        >
          담당자 해제
        </Button>
      )}

      {/* ★검색 중에는 "결과 없음" 을 띄우지 않는다 — 아직 모르는 것을 없다고 말하면 거짓이다
          (디자인 리뷰 Pass 2). 로딩과 빈 결과는 서로 다른 상태다. */}
      {isLoading ? (
        <p className="text-xs text-(--text-subtle)">검색 중…</p>
      ) : users.length === 0 ? (
        <p className="text-xs text-(--text-subtle)">검색 결과가 없습니다.</p>
      ) : (
        <ul className="flex max-h-48 flex-col gap-0.5 overflow-y-auto">
          {users.map((user) => (
            <li key={user.id}>
              <Button
                type="button" variant="ghost" size="sm"
                disabled={!canEdit || isSaving}
                onClick={() => onChange(user.id)}
                className={CELL_OPTION_CLASS}
              >
                {getDisplayName(user)}
              </Button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
```

**REFACTOR**. `getDisplayName` 이 `IssueAssigneeSelect` 와 중복이지만, 그쪽은 컴포넌트 내부
지역 함수라 export 되어 있지 않다. 공유 모듈로 빼는 것은 **이 PR 범위 밖**(상세 화면 파일을
건드리게 된다) — 코드리뷰 지적 대상으로 남기고 여기서는 복제한다.

**검증**.
- `node_modules/.bin/vitest run src/components/issues/cells/AssigneeCell.test.tsx` 초록
- **뮤테이션**. `onKeyDown` 의 `preventDefault` 를 지우면 Enter 테스트 빨강
- 기존 E2E. `issue-assignee.spec.ts` (담당자 관련 계약 확인) 동반 실행
- 브라우저 눈확인. Task 7 후

---

### Task 6. 상태 셀 — 전이 목록 + `role="status"` 보존

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issues/cells/StatusCell.tsx`, `apps/web/src/components/issues/cells/StatusCell.test.tsx`, `apps/web/src/lib/transition-availability.ts`, `apps/web/src/lib/transition-availability.test.ts`, `apps/web/src/routes/issues.$key.tsx`]
- depends-on: [2, 3]

**선행 작업 — `resolveTransitionUnavailableReason` 공용 승격**.
이 함수는 `issues.$key.tsx:55` 의 **로컬 함수**다. **422(워크플로우 미설정) vs 200+빈 배열(종료
상태)** 를 구분하는 유일한 지점인데(FR8·E16), 목록에서도 같은 판정이 필요하다. **복제하면 두
목록이 서로를 검사하지 않게 된다**([[two-lists-never-check-each-other]]) — `lib/transition-availability.ts`
로 옮기고 상세 화면은 import 로 바꾼다(동작 무변경, 기존 테스트가 증인).

```ts
// 전이 가용성 판정 — 422(워크플로우 미설정)와 200+빈 배열(종료 상태)을 가른다
// 상세 화면(routes/issues.$key.tsx)과 목록 셀(components/issues/cells/StatusCell.tsx) 공용.

/** 전이 컨트롤을 노출할 수 없는 사유 */
export type TransitionUnavailableReason = 'no-workflow' | 'terminal' | null
```

본문은 `issues.$key.tsx:55` 의 현재 구현을 **그대로 옮긴다**(로직 변경 금지 — 이번 PR 은 이동만
한다). `issues.$key.tsx` 는 로컬 정의를 지우고 import 한다.

**RED**. 가용 전이만 노출 · 0건 사유 2종 분기 · **배지가 `role="status"` 유지**.

```tsx
// 상태 셀 테스트 (FR-UX-11 F9 FR7·FR8·FR9)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { StatusCellDisplay, StatusCellEditor } from './StatusCell'
import type { IssueTransition } from '@/api/issues'

const TRANSITIONS: IssueTransition[] = [
  { key: 'start', name: '진행 시작', toStateKey: 'IN_PROGRESS' } as IssueTransition,
]

describe('StatusCellDisplay', () => {
  it('상태 배지의 role="status" 를 유지한다 (FR9 — 즉사 계약)', () => {
    render(<StatusCellDisplay currentStateKey="TODO" />)

    expect(screen.getByRole('status')).toHaveTextContent('TODO')
  })
})

describe('StatusCellEditor', () => {
  it('가용 전이만 버튼으로 노출하고 고르면 toStateKey 로 onTransition 을 부른다 (FR7)', async () => {
    const onTransition = vi.fn()
    render(
      <StatusCellEditor
        transitions={TRANSITIONS} unavailableReason={null} canTransition isSaving={false}
        isLoading={false} onTransition={onTransition}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '진행 시작' }))

    expect(onTransition).toHaveBeenCalledWith('IN_PROGRESS')
  })

  it('워크플로우 미설정이면 그 사유를 안내한다 (FR8)', () => {
    render(
      <StatusCellEditor
        transitions={[]} unavailableReason="no-workflow" canTransition isSaving={false}
        isLoading={false} onTransition={vi.fn()}
      />,
    )

    expect(screen.getByText('워크플로우가 설정되지 않았습니다.')).toBeInTheDocument()
  })

  it('종료 상태면 다른 사유를 안내한다 (FR8)', () => {
    render(
      <StatusCellEditor
        transitions={[]} unavailableReason="terminal" canTransition isSaving={false}
        isLoading={false} onTransition={vi.fn()}
      />,
    )

    expect(screen.getByText('더 진행할 전이가 없습니다.')).toBeInTheDocument()
  })

  it('전이 권한이 없으면 선택지가 비활성이다 (FR10 fail-closed)', () => {
    render(
      <StatusCellEditor
        transitions={TRANSITIONS} unavailableReason={null} canTransition={false} isSaving={false}
        isLoading={false} onTransition={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: '진행 시작' })).toBeDisabled()
  })

  it('종료 전이(toCategory=DONE)는 즉시 전이하지 않고 결의안 요청을 올린다 (FR14)', async () => {
    const onTransition = vi.fn()
    const onDoneTransition = vi.fn()
    const doneTransition = {
      key: 'finish', name: '완료', fromStateKey: 'IN_PROGRESS',
      toStateKey: 'DONE', toCategory: 'DONE',
    } as IssueTransition

    render(
      <StatusCellEditor
        transitions={[doneTransition]} unavailableReason={null} canTransition isSaving={false}
        isLoading={false} onTransition={onTransition} onDoneTransition={onDoneTransition}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '완료' }))

    expect(onDoneTransition).toHaveBeenCalledWith(doneTransition)
    expect(onTransition).not.toHaveBeenCalled()   // ★결의안 없이 전이하지 않는다
  })
})
```

**RED 확인**. 모듈 없음으로 빨강.

**GREEN**. `StatusCell.tsx`.

```tsx
// 이슈 목록 상태 셀 — 클릭해 가용 전이를 고른다 (FR-UX-11 F9)
import type { JSX } from 'react'
import type { IssueTransition } from '@/api/issues'
import { Button } from '@/components/ui/button'

/**
 * 닫힌 상태에서 보이는 상태 배지.
 *
 * ★`role="status"` 는 **즉사 계약**이다(패리티 계약 §2). `issue-columns.ts` 의 기존
 * `renderStatusCell` 이 달고 있었고 e2e 가 셀렉터로 쓴다. 편집 트리거로 감싸도 이 role 이
 * 배지에 그대로 남아야 한다.
 */
export function StatusCellDisplay({ currentStateKey }: { currentStateKey: string }): JSX.Element {
  return (
    <span
      role="status"
      className="inline-block shrink-0 rounded-full bg-(--bg-neutral) px-2 py-0.5 text-xs font-medium text-(--text-subtle)"
    >
      {currentStateKey}
    </span>
  )
}

/** StatusCellEditor props */
export interface StatusCellEditorProps {
  transitions: IssueTransition[]
  unavailableReason: TransitionUnavailableReason
  canTransition: boolean
  isSaving: boolean
  isLoading: boolean
  /** 비종료 전이 — 즉시 실행 */
  onTransition: (toStateKey: string) => void
  /**
   * ★종료 전이(`toCategory === 'DONE'`) — 즉시 실행하지 않고 결의안 선택을 요청한다.
   *
   * 해결 결과는 종료 상태 전이의 **필수** 입력이다(glossary 「해결 결과」). 상세 화면
   * `issues.$key.tsx:672` 가 같은 분기로 `ResolutionModal` 을 띄운다 (FR14).
   */
  onDoneTransition: (transition: IssueTransition) => void
}

/** popover 안에 뜨는 가용 전이 목록. */
export function StatusCellEditor({
  transitions, unavailableReason, canTransition, isSaving, isLoading, onTransition, onDoneTransition,
}: StatusCellEditorProps): JSX.Element {
  if (isLoading) {
    return <p className="text-xs text-(--text-subtle)">불러오는 중…</p>
  }

  if (transitions.length === 0) {
    return (
      <p className="text-xs text-(--text-subtle)">
        {unavailableReason === 'no-workflow'
          ? '워크플로우가 설정되지 않았습니다.'
          : '더 진행할 전이가 없습니다.'}
      </p>
    )
  }

  return (
    <div className="flex flex-col gap-0.5">
      {!canTransition && <p className="px-2 py-1 text-xs text-(--text-subtle)">전이 권한이 없습니다.</p>}
      {transitions.map((t) => (
        <Button
          key={t.key}
          type="button" variant="ghost" size="sm"
          disabled={!canTransition || isSaving}
          // 종료 전이는 결의안을 먼저 받는다 — 여기서 바로 전이하면 필수 입력이 빠진다(FR14)
          onClick={() => (t.toCategory === 'DONE' ? onDoneTransition(t) : onTransition(t.toStateKey))}
          className="justify-start"
        >
          {t.name}
        </Button>
      ))}
    </div>
  )
}
```

**REFACTOR**. `StatusCellDisplay` 의 className 이 `issue-columns.ts:108` `renderStatusCell` 과
동일하다 — Task 7 에서 `renderStatusCell` 을 이 컴포넌트로 **교체**하므로 중복이 남지 않는다.

**검증**.
- `node_modules/.bin/vitest run src/components/issues/cells/StatusCell.test.tsx` 초록
- **뮤테이션**. `role="status"` 를 지우면 FR9 테스트 빨강. `unavailableReason` 3항 분기를
  한쪽으로 고정하면 FR8 테스트 중 하나가 빨강
- 기존 E2E. `issue-table.spec.ts` 의 상태 배지 셀렉터 확인
- 브라우저 눈확인. Task 7 후

---

### Task 7. 컬럼 배선 — `issue-columns.ts` 위임 + `IssueTable` 컨텍스트 확장

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issues/issue-columns.ts`, `apps/web/src/components/issues/IssueTable.tsx`, `apps/web/src/routes/issues.index.tsx`, `apps/web/src/components/issues/cells/PriorityCell.tsx`, `apps/web/src/components/issues/cells/AssigneeCell.tsx`, `apps/web/src/components/issues/cells/StatusCell.tsx`, `apps/web/src/components/issues/cells/EditableCell.tsx`, `apps/web/src/hooks/use-issue-list-cell-field.ts`, `apps/web/src/components/issues/IssueTable.test.tsx`]
- depends-on: [4, 5, 6]

**★우선순위 표기 한글 통일 (Maxi 확정 2026-08-04 — 구현 중 발견).**

배선하고 보니 **같은 칸에서 닫히면 `Medium`(영어), 열면 `보통`(한글)** 이었다. F9 이 만든 것이
아니라 **선재 불일치** 다 — 목록은 백엔드 `priorityName`(`IssuePriority.kt` enum 의 영어
`displayName`)을, 상세는 `issueDetailStrings.priorityNames`(한글)를 써 왔고, F9 이 그 둘을
**한 칸 안에서 만나게** 하면서 드러났다.

**처방.** 닫힌 셀도 `issueDetailStrings.priorityNames[issue.priority]` 를 쓴다. 목록·popover·
상세가 **한 정본**을 본다. `e2e/` 에 우선순위 텍스트 단언이 **0건**임을 grep 으로 확인했다
(`issue-body-meta.spec.ts:79` 는 상세 화면 셀렉터). 백엔드 0줄 불변.

**하류 이득.** 나중에 다국어를 넣을 때 교체 지점이 **한 곳**으로 모인다 — 섞인 채 두면 찾아
고칠 자리가 흩어진다.

**신규 FR 2건으로 분리 (Maxi 확정).** ① **한글/영어 전환** — 사용자 환경설정에 `locale`
필드는 **이미 있으나**(`preferences-handlers.ts:42` 기본값 `ko`) 프론트에 전환 장치가 없다
(`i18n/` 53파일 7,813줄이 전부 한국어 상수, 다국어 라이브러리 0). ② **용어 직접 변경** —
`IssuePriority` 가 Kotlin enum 하드코딩이고 DB 에는 숫자 1~5 만 저장된다. 테이블 신설 +
마이그레이션 + API + 관리 화면이 필요해 **백엔드 필수**. 선례는 「해결 결과(Resolution)」
(표준 세트 불변 + 커스텀 추가 가능 구조). 둘 다 이번 PR 의 백엔드 0줄 원칙과 충돌하므로
**FR 정식 등록은 별도 작업**으로 하고 이번 PR 은 FR 총수 139 를 불변으로 둔다.

**★저장 후 popover 를 닫는다 (Maxi 확정 2026-08-04 — 디자인 리뷰 Pass 3).**
3종 셀 모두 선택 즉시 `setOpen(false)` 를 부른다. 낙관적 반영이라 닫아도 결과가 셀에 바로
보이고, 여러 행을 연달아 고칠 때 손이 멈추지 않는다. 실패는 이미 닫힌 뒤 toast 로 알린다
(FR15). 이것이 `EditableCell` 을 제어형으로 만든 이유다.

```tsx
function handleChange(next: number): void {
  setOpen(false)          // ★먼저 닫는다 — 낙관적 patch 가 셀에 즉시 반영된다
  mutation.mutate({ issueKey: issue.key, field: 'priority', toPriority: next, expectedVersion: issue.version })
}
```

**설계**. `IssueColumnRenderContext` 에 편집 컨텍스트를 **한 덩어리**로 추가한다. 컬럼 정의는
`createElement(PriorityCell, {...})` 로 위임만 하므로 **순수 함수 계약이 유지된다**(D-5).

```ts
/** 셀 인라인 편집에 필요한 컨텍스트 — 없으면(undefined) 편집 비활성, 기존 읽기 전용 렌더 */
export interface IssueCellEditContext {
  /** 목록 queryKey — mutation 훅이 이 캐시를 patch 한다 */
  listQueryKey: QueryKey
  /** 편집 기능 자체를 끌 때 false (권한 조회 실패 등 상위 판단) */
  enabled: boolean
}
```

`IssueColumnRenderContext` 에 `edit?: IssueCellEditContext` 를 추가하고, 3개 render 함수를
분기시킨다 — `ctx.edit` 가 없으면 **기존 마크업 그대로**(회귀 0), 있으면 편집 셀 컴포넌트.

```ts
/** 상태 배지 셀 렌더 — ★e2e 계약 보존(role=status). edit 컨텍스트가 있으면 인라인 편집 셀 */
function renderStatusCell(issue: IssueResponse, ctx: IssueColumnRenderContext): ReactNode {
  if (ctx.edit === undefined || !ctx.edit.enabled) {
    return createElement(StatusCellDisplay, { currentStateKey: issue.currentStateKey })
  }
  return createElement(StatusCell, { issue, listQueryKey: ctx.edit.listQueryKey })
}
```

각 `*Cell.tsx` 에 조립 컴포넌트를 추가한다 (Task 4~6 의 편집부를 `EditableCell` 로 감싸고 훅을
붙이는 부분). 예 — `StatusCell.tsx` 에 추가.

```tsx
/** StatusCell props */
export interface StatusCellProps {
  issue: IssueResponse
  listQueryKey: QueryKey
}

/**
 * 상태 셀 조립 — 배지(닫힘) + 전이 목록(열림).
 *
 * 훅은 **`EditableCell` 이 열렸을 때만 마운트되는 자식 안**에 있다. 닫힌 상태에서
 * `useIssueTransitions`·`useIssuePermissions` 가 돌지 않는 것이 D-3 의 이행이다.
 */
export function StatusCell({ issue, listQueryKey }: StatusCellProps): JSX.Element {
  return (
    <EditableCell
      label={`${issue.key} 상태 변경`}
      display={<StatusCellDisplay currentStateKey={issue.currentStateKey} />}
    >
      <StatusCellPopoverBody issue={issue} listQueryKey={listQueryKey} />
    </EditableCell>
  )
}

/** popover 가 열렸을 때만 마운트된다 — 여기서만 조회가 발생한다 (FR12·NFR1) */
function StatusCellPopoverBody({ issue, listQueryKey, onDoneTransition }: StatusCellBodyProps): JSX.Element {
  const { data: transitions = [], isLoading, isError, error } = useIssueTransitions(issue.key)
  const permissions = useIssuePermissions(issue.key)
  const mutation = useIssueListCellField(listQueryKey)

  return (
    <StatusCellEditor
      transitions={transitions}
      // ★공용 헬퍼 — 422(워크플로우 미설정)와 200+빈 배열(종료 상태)을 가른다 (FR8·E16)
      unavailableReason={resolveTransitionUnavailableReason({
        isError, error, transitionCount: transitions.length,
      })}
      // fail-closed — 권한이 확정되기 전에는 false (D-6)
      canTransition={permissions.data?.permissions.TRANSITION === true}
      isLoading={isLoading || permissions.isLoading}
      isSaving={mutation.isPending}
      onTransition={(toStateKey) =>
        mutation.mutate({
          issueKey: issue.key,
          field: 'status',
          toStatusKey: toStateKey,
          expectedVersion: issue.version,
        })
      }
      // 종료 전이는 popover 를 닫고 상위(StatusCell)가 ResolutionModal 을 띄운다 (FR14)
      onDoneTransition={onDoneTransition}
    />
  )
}
```

**결의안 모달 배선 (FR14).** `StatusCell` 이 `pendingDoneTransition` 상태를 들고 popover 를 닫은
뒤 기존 `ResolutionModal` 을 띄운다. 상세 화면 `issues.$key.tsx:672·689` 와 **같은 흐름**이다.

```tsx
export function StatusCell({ issue, listQueryKey }: StatusCellProps): JSX.Element {
  const [open, setOpen] = useState(false)
  const [pendingDone, setPendingDone] = useState<IssueTransition | null>(null)
  const mutation = useIssueListCellField(listQueryKey)

  /** 종료 전이 선택 — popover 를 닫고 결의안 모달로 넘긴다 (E14) */
  function handleDoneTransition(transition: IssueTransition): void {
    setOpen(false)
    setPendingDone(transition)
  }

  /** 결의안 확정 — resolutionId 를 실어 전이한다 */
  function handleResolutionConfirm(resolutionId: string): void {
    if (pendingDone === null) return
    mutation.mutate({
      issueKey: issue.key,
      field: 'status',
      toStatusKey: pendingDone.toStateKey,
      expectedVersion: issue.version,
      resolutionId,
    })
    setPendingDone(null)
  }

  return (
    <>
      <EditableCell
        open={open}
        onOpenChange={setOpen}
        label={`${issue.key} 상태 변경`}
        display={<StatusCellDisplay currentStateKey={issue.currentStateKey} />}
      >
        <StatusCellPopoverBody
          issue={issue}
          listQueryKey={listQueryKey}
          onDoneTransition={handleDoneTransition}
        />
      </EditableCell>

      {pendingDone !== null && (
        <ResolutionModal
          open
          onConfirm={handleResolutionConfirm}
          onCancel={() => setPendingDone(null)}
        />
      )}
    </>
  )
}
```

> `EditableCell` 은 열림 상태를 **제어형으로도 쓸 수 있어야** 한다 — 종료 전이에서 popover 를
> 코드로 닫아야 하기 때문이다. Task 3 의 내부 `useState` 를 `open`/`onOpenChange` **선택적
> prop** 으로 승격한다(미전달이면 기존처럼 자체 관리). `ResolutionModal` 의 실제 props 는
> `components/issue/ResolutionModal.tsx` 를 열어 시그니처를 그대로 맞춘다.

**★`useIssueListCellField` 에 `resolutionId` 추가.** Task 2 의 `IssueCellFieldVars` 에
`resolutionId?: string` 를 더하고 `requestCellChange` 의 `status` 분기에서 전달한다 —
`TransitionIssueInput` 이 이미 받는 선택 필드다.

`IssueTable.tsx` — `edit` prop 을 받아 `column.render` 의 ctx 에 전달한다.

```diff
   /** split view 현재 선택 이슈 키 */
   selectedKey?: string | null
+  /** 셀 인라인 편집 컨텍스트. 미전달이면 읽기 전용(기존 동작) */
+  edit?: IssueCellEditContext
```

```diff
           {column.render(issue, {
             assigneeName: issue.assigneeId !== null ? assigneeNameMap.get(issue.assigneeId) : undefined,
             formatDate,
             onNavigate: handleRowNavigate,
+            edit,
           })}
```

`issues.index.tsx` — 이미 계산해둔 queryKey 를 그대로 넘긴다.

```diff
       <IssueTable
         issues={data.content}
         ...
         selectedKey={selectedKey}
+        edit={{ listQueryKey: ['issues', projectKey, page, normalizedFilter, sort], enabled: true }}
       />
```

**RED (동반 테스트 — ui 트랙이라 red-first 면제)**. `IssueTable.test.tsx` 에 추가.

```tsx
it('edit 컨텍스트가 없으면 기존 읽기 전용 셀을 렌더한다 (회귀 0)', () => {
  renderTable({ /* edit 미전달 */ })

  expect(screen.getByRole('status')).toHaveTextContent('TODO')
  expect(screen.queryByRole('button', { name: /상태 변경/ })).not.toBeInTheDocument()
})

it('edit 컨텍스트가 있으면 편집 트리거를 렌더하되 role="status" 를 유지한다 (FR9)', () => {
  renderTable({ edit: { listQueryKey: ['issues'], enabled: true } })

  expect(screen.getByRole('button', { name: 'ATLAS-1 상태 변경' })).toBeInTheDocument()
  expect(screen.getByRole('status')).toHaveTextContent('TODO')
})
```

**검증**.
- `node_modules/.bin/vitest run src/components/issues/ src/routes/issues.index.test.tsx` 초록
- `node_modules/.bin/tsc -p tsconfig.app.json --noEmit` EXIT 0
- **기존 E2E 동반 실행** (계약 §5 사전 grep 결과 — 목록 화면 방문 18개 중 핵심 8개).
  ```
  node_modules/.bin/playwright test issue-table issue-split-view issue-filter \
    issue-bulk-operations context-shortcuts issue-crud-happy keyboard-shortcuts active-project
  ```
- **브라우저 눈확인 (계약 §6 — 여기서 처음 화면이 바뀐다)**.
  1. 라이트/다크 양쪽에서 hover 어포던스가 보이는가
  2. 3종 popover 가 열리고 값이 바뀌는가
  3. 편집 셀 클릭 시 **상세가 열리지 않는가**
  4. 요약·여백 클릭 시 **기존대로 상세가 열리는가**
  5. 좁은 폭에서 레이아웃이 깨지지 않는가

---

### Task 8. 편집 중 목록 단축키 차단 (E10) + 접근성

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issues/cells/EditableCell.tsx`, `apps/web/src/components/issues/cells/EditableCell.test.tsx`]
- depends-on: [7]

**왜 필요한가 (실측)**. `shortcuts.ts:234` `shouldIgnoreEvent` 는 `isComposing` · 수식키 ·
`isEditableTarget(e.target)` 만 본다. popover 안에서 포커스가 **버튼**에 있으면 편집 요소가
아니라 `j`/`k`(F10 커서 이동)가 그대로 발동해 **편집 중에 목록 커서가 움직이고 상세가 바뀐다**.
담당자 셀의 검색 `<input>` 은 우연히 안전하지만 우선순위·상태 셀은 아니다.

**RED**.

```tsx
it('popover 가 열려 있으면 목록 단축키가 문서로 새어나가지 않는다 (E10)', async () => {
  const onDocumentKeyDown = vi.fn()
  document.addEventListener('keydown', onDocumentKeyDown)

  render(
    <EditableCell label="우선순위 편집" display={<span>보통</span>}>
      <button type="button">높음</button>
    </EditableCell>,
  )
  await userEvent.click(screen.getByRole('button', { name: '우선순위 편집' }))
  onDocumentKeyDown.mockClear()

  await userEvent.keyboard('j')

  expect(onDocumentKeyDown).not.toHaveBeenCalled()
  document.removeEventListener('keydown', onDocumentKeyDown)
})
```

**GREEN**. `PopoverContent` 에 keydown 차단을 더한다.

```diff
       <PopoverContent
         className="w-64 p-2"
         onClick={(event) => event.stopPropagation()}
+        // ★편집 중에는 목록 단축키를 막는다. `shouldIgnoreEvent`(shortcuts.ts)는 편집 요소만
+        // 무시하므로 popover 안 **버튼**에 포커스가 있으면 j/k 커서 이동이 그대로 발동해
+        // 편집 도중 상세가 바뀐다(E10, 2026-08-04 실측). Esc 는 Radix 가 닫기에 쓰므로 통과시킨다.
+        onKeyDown={(event) => {
+          if (event.key !== 'Escape') event.stopPropagation()
+        }}
       >
```

**REFACTOR**. 없음.

**검증**.
- `node_modules/.bin/vitest run src/components/issues/cells/EditableCell.test.tsx` 초록
- **뮤테이션**. `onKeyDown` 차단을 지우면 E10 테스트 빨강
- **기존 E2E 동반**. `context-shortcuts.spec.ts` · `keyboard-shortcuts.spec.ts` — F10 자산 무손상
- `node_modules/.bin/vitest run src/components/keyboard-shortcuts/shortcuts.test.ts` —
  `toHaveLength(5)` **무수정** 초록 (즉사 계약)
- 브라우저 눈확인. popover 를 연 채 `j`/`k` 를 눌러 목록 커서가 **안 움직이는지**, `Esc` 는
  여전히 닫는지

---

### Task 9. E2E — S1~S7

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-list-inline-edit.spec.ts`]
- depends-on: [7, 8]

**시나리오**. 스펙 S1~S7 을 그대로 옮긴다. 셀렉터는 **`exact: true` 를 기본**으로 쓴다 —
F8 에서 제목이 버튼 접근성 이름이 되어 `취소` 와 부분일치한 사고가 있었다(`learnings:631` 재발면).

```ts
// FR-UX-11 F9 E2E — 이슈 목록 셀 인라인 편집 (담당자·우선순위·상태)
import { test, expect } from '@playwright/test'

test.describe('FR-UX-11 F9 목록 셀 인라인 편집', () => {
  test('S2 우선순위 셀을 클릭해 값을 바꾼다', async ({ page }) => {
    await page.goto('/issues')
    await page.getByRole('button', { name: 'ATLAS-1 우선순위 변경', exact: true }).click()
    await page.getByRole('button', { name: '높음', exact: true }).click()

    await expect(page.getByRole('button', { name: 'ATLAS-1 우선순위 변경', exact: true }))
      .toContainText('높음')
  })

  test('S4 편집 대상이 아닌 셀 클릭은 기존대로 상세로 이동한다', async ({ page }) => {
    await page.goto('/issues')
    await page.getByTestId('issue-summary-ATLAS-1').click()

    await expect(page).toHaveURL(/\/issues\/ATLAS-1|selected=ATLAS-1/)
  })

  test('S6 Esc 로 닫아도 상세로 이동하지 않는다', async ({ page }) => {
    await page.goto('/issues')
    const url = page.url()
    await page.getByRole('button', { name: 'ATLAS-1 우선순위 변경', exact: true }).click()
    await page.keyboard.press('Escape')

    await expect(page).toHaveURL(url)
  })

  test('S3 상태 셀은 그 이슈의 가용 전이만 노출한다', async ({ page }) => {
    await page.goto('/issues')
    await page.getByRole('button', { name: 'ATLAS-1 상태 변경', exact: true }).click()

    await expect(page.getByRole('button', { name: '진행 시작', exact: true })).toBeVisible()
  })

  test('S1 담당자 셀을 클릭해 담당자를 바꾼다', async ({ page }) => {
    await page.goto('/issues')
    await page.getByRole('button', { name: 'ATLAS-1 담당자 변경', exact: true }).click()
    await page.getByRole('textbox', { name: '담당자 검색', exact: true }).fill('maxi')
    await page.getByRole('button', { name: '맥시', exact: true }).click()

    await expect(page.getByRole('button', { name: 'ATLAS-1 담당자 변경', exact: true }))
      .toContainText('맥시')
  })

  test('E14 종료 전이는 결의안 모달을 거친다 (FR14)', async ({ page }) => {
    await page.goto('/issues')
    await page.getByRole('button', { name: 'ATLAS-1 상태 변경', exact: true }).click()
    await page.getByRole('button', { name: '완료', exact: true }).click()

    // popover 는 닫히고 결의안 모달이 뜬다 — 결의안 없이 전이되지 않는다
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('button', { name: 'ATLAS-1 상태 변경', exact: true }))
      .not.toContainText('DONE')
  })

  test('E10 popover 가 열린 동안 j/k 가 목록 커서를 움직이지 않는다', async ({ page }) => {
    await page.goto('/issues?selected=ATLAS-1')
    await page.getByRole('button', { name: 'ATLAS-1 우선순위 변경', exact: true }).click()
    const before = page.url()

    await page.keyboard.press('j')

    await expect(page).toHaveURL(before)
  })
})
```

**검증**.
- `node_modules/.bin/playwright test issue-list-inline-edit`
- **기존 E2E 전량 동반**. `node_modules/.bin/playwright test` (142 spec) — 회귀 0 확인.
  UI PR 이 E2E 를 미루면 회귀가 머지 시점에 잠복한다(`learnings:631`)
- 브라우저 눈확인. Task 7·8 에서 수행한 항목을 PR 본문에 기록

---

### Task 10. 정본 동기화 — §4.9 D6/D7 마감 + 오류 2건 정정

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plan/product/personalization.md`, `docs/plan/README.md`, `docs/design/jira-parity-roadmap.md`, `CHANGELOG.md`]
- depends-on: [9]

**작업 1 — 정본 오류 정정 (원문 교체, 정정 노트 아님)**.
`personalization.md:325` 의 *"`components/ui/popover.tsx`(소비처 **0→1**)"* → **1→2**.
근거. `ProjectSwitcher.tsx:9` 가 이미 소비 중(실측).

**작업 2 — D6/D7 `[x]` 전환**. §4.9 의 D6·D7 을 `[ ]` → `[x]` 로 바꾸고 본문의 *"F9 잔여"* 를
완료 서술로 교체한다. **FR-UX-11 완주**이므로 §4.9 말미에 완료 노트를 추가한다.

**작업 3 — 진척 카운트**. `- [x] D«n».` 실측치를 다시 세어 `CLAUDE.md`·`docs/plan/README.md` 의
수치를 맞춘다. D6/D7 2건이 닫히므로 **940 → 942 · 미완 25 → 23**이 예상값이지만
**반드시 실측으로 확인**한다 (`grep -c` — 계획 시점 숫자는 유통기한이 있다).

```bash
grep -rho '^- \[x\] D[0-9]*\.' docs/plan/product/*.md | wc -l   # 완료
grep -rho '^- \[ \] D[0-9]*\.' docs/plan/product/*.md | wc -l   # 미완
```

**작업 4 — 로드맵**. `jira-parity-roadmap.md:39` FR-UX-11 행을 `🔶 진행 중` → 완료로,
`:61` F9 행에 PR 번호 기록. **선재 drift 도 함께 고친다** — `:38` FR-UX-10 행이 `⬜ 미착수`인데
#336 이 머지됐다(F8 메모리 미해결 목록에 기록된 건).

**작업 5 — CHANGELOG**.

**검증**.
- `bash scripts/verify-master-plan.sh` **EXIT 0** (종료 4 면 카운트 drift)
- `node scripts/build-doc-index.mjs --check` EXIT 0
- `pnpm test:workflow` 초록
- **FR 총수 139 불변** 확인 — 이 PR 은 FR 을 추가·삭제하지 않는다
- **NFR6 백엔드 0줄** — `git diff --exit-code main -- backend/` **EXIT 0** 실측 (F8 선례)
- 신규 의존성 0 — `git diff main -- apps/web/package.json pnpm-lock.yaml` 빈 diff

---

## Plan 메타

- task 수: **10**
- 예상 wave: 4 (`[1·2·3] → [4·5·6] → [7] → [8] → [9] → [10]` — 파일 겹침 자동 직렬화 포함)
- 구현 규율: **ui 시각 검증 트랙** (red-first 면제 · 기존 E2E 동반 + 브라우저 눈확인 필수).
  단 **Task 1·2 는 순수 로직/설정이라 red-first 를 지킨다** (판별식·훅 모두 RED 를 먼저 본다)
- 병렬 dispatch: bts-impl 이 `depends-on` + `files` 로 wave 계산
- 추가 검증: `tsc --noEmit` · `eslint` · vitest · playwright · `verify-master-plan.sh` · `test:workflow`
- **worktree 실행 규약**. `pnpm` 래퍼를 쓰지 않고 `node_modules/.bin/*` 를 직접 호출한다
  (Task 1 이 훅을 고치기 전까지는 특히). sub-agent prompt 에도 명시할 것

## 리뷰 결과

### plan-design-review (2026-08-04)

`type == ui` 분기. 목업 생성기는 **쓰지 않았다** — BTS 는 `DESIGN.md`(ADS v2 토큰)와
`jira-parity-contract.md` §1(Jira Cloud 대조)이 시각 정본이라 AI 생성 시안이 그 계약과 충돌한다.
대신 7개 차원을 실측 대조했다.

| Pass | 차원 | 전 | 후 | 조치 |
|---|---|---|---|---|
| 1 | 정보 위계 | 6/10 | 9/10 | popover 맨 위 현재 담당자 표시 추가 |
| 2 | 상태 커버리지 | 7/10 | 10/10 | 담당자 검색 **로딩≠빈결과** 분리 + 상태 표 |
| 3 | 사용자 여정 | 6/10 | 9/10 | **저장 후 popover 닫기** 확정 |
| 4 | AI 슬롭 위험 | 9/10 | 9/10 | 문제 없음 (APP UI · 카드 그리드 0 · 기존 토큰만) |
| 5 | 디자인 시스템 정합 | **4/10** | 10/10 | **★실버그 봉합** — 없는 토큰 참조 |
| 6 | 반응형·접근성 | 5/10 | 9/10 | `pointer-coarse` 44px 터치 타깃 |
| 7 | 미해결 결정 | — | — | 2건 Maxi 확정, 0건 이연 |

**BLOCKER. 없음.**

#### ★ 실버그 1건 (Pass 5) — 없는 토큰이 어포던스를 조용히 지운다

초안의 `hover:ring-(--border-default)` 가 참조한 `--border-default` 는 **`index.css` 에 존재하지
않는다**(실측). 실제 토큰은 `--border`(라이트 `#DCDFE4` · 다크 `#2C333A`). 없는 커스텀
프로퍼티는 에러가 아니라 **무효 선언**이라 ring 이 아예 렌더되지 않고, **FR2 hover 어포던스가
통째로 사라진다**. D-1 진입 방식(hover 표시 + 셀 클릭)의 절반이 죽는 셈이다.

계열 [[mock-swallowed-prop-is-invisible-to-unit-tests]] — 실패가 아니라 **침묵**이라 테스트도
사람도 못 본다. 그래서 봉합을 **클래스 문자열 단언**으로 못박았다. 계산값 단언은 jsdom 이
커스텀 프로퍼티를 해석하지 않아 공허해진다(F8 의 커서 단언이 jsdom 기본값과 우연히 일치해
공허했던 사고와 같은 함정).

#### 결함 3건 (승인 불요 — 명백한 오류, 반영 완료)

1. **Pass 2.** 담당자 검색 로딩 중에 「검색 결과가 없습니다」가 떴다 — **아직 모르는 것을 없다고
   말하는** 거짓 표시. `isLoading` 분기 신설
2. **Pass 1.** popover 가 셀을 가려 「지금 담당자가 누구인지」가 화면에서 사라졌다. 맨 위 표시 추가
3. **Pass 5.** 위 토큰 실버그

#### Maxi 확정 2건 (2026-08-04)

| 결정 | 선택 | 근거 |
|---|---|---|
| popover 선택지 크기 | **마우스 28px / 터치 44px** | `pointer: coarse` 미디어 질의. 목록 밀도와 터치 접근성을 둘 다 만족. 상세 `IssueAssigneeSelect:84` 의 `min-h-[44px]` 계약과 정합 |
| 저장 후 popover | **바로 닫는다** | 연속 편집이 목록 인라인 편집의 존재 이유. 낙관적 반영이라 닫아도 결과가 보인다 |

#### 상태 매트릭스 (Pass 2 산출물)

| 셀 | LOADING | EMPTY | ERROR | SUCCESS | PARTIAL |
|---|---|---|---|---|---|
| 담당자 | `검색 중…` | `검색 결과가 없습니다.` | toast(409 버전충돌 / 일반) | popover 닫힘 + 셀 즉시 갱신 | 권한 미확정 → 컨트롤 `disabled` |
| 우선순위 | 없음 (정적 1~5) | 해당 없음 | toast(409 / 일반) | popover 닫힘 + 셀 즉시 갱신 | 권한 미확정 → `disabled` |
| 상태 | `불러오는 중…` | 사유 2종 — `워크플로우가 설정되지 않았습니다.`(422) / `더 진행할 전이가 없습니다.`(200+빈) | toast 3분기 — 전이 불가 / 버전 충돌 / 워크플로우 미설정 | popover 닫힘 + 배지 즉시 갱신. **종료 전이는 결의안 모달 경유** | 전이 권한 없음 → `disabled` |

#### NOT in scope (이연, 사유 명시)

- **`getDisplayName` 중복** — 상세 `IssueAssigneeSelect` 의 지역 함수와 같은 로직. 공유 모듈로
  빼려면 상세 화면 파일을 건드려야 해 이번 PR 범위 밖. 코드리뷰 지적 대상으로 남긴다
- **`disabled:opacity-50` 전역 미적용** — `components/ui/*` 14개 프리미티브 공유 이슈
  (F8 메모리 미해결). 프리미티브 차원 별도 작업
- **hover 어포던스의 터치 대체** — 터치에는 hover 가 없다. v1 은 44px 타깃 + 셀 클릭으로 충분하다고
  보고, 상시 편집 표시(연필 아이콘 등)는 실기기 확인 후 판단

#### What already exists (재사용 확정)

`ui/popover.tsx`(소비처 1→2) · `useChangeCardField` 패턴 · `ResolutionModal` ·
`resolveTransitionUnavailableReason`(공용 승격) · `issueDetailStrings` 전이 에러 문구 3종 ·
`useUsers`/`useUsersByIds` · `useIssueTransitions` · `useIssuePermissions` ·
`IssuePrioritySelect` 의 값 집합·라벨 정본 · `--border`/`--bg-neutral`/`--text-subtle` ADS 토큰

## 후속 항목 (이번 PR 이 의도적으로 남긴 것)

**FR 총수 139 는 불변이다.** 아래 신규 FR 후보 2건은 **정식 등록을 하지 않았다** — 등록은 별도
작업이고, 이 절은 그때 옮겨 담을 근거를 모아 둔 곳이다.

### A. 신규 FR 후보 2건 (Maxi 확정 2026-08-04 — 분리하기로 결정)

| # | 후보 | 왜 이번 PR 밖인가 | 실측 근거 |
|---|---|---|---|
| A1 | **한글/영어 전환** | 프론트에 전환 장치가 **0** 이고 다국어 라이브러리도 없다. 화면 문자열 전량 교체 규모 | 사용자 환경설정 `locale` 필드는 **이미 있다**(`preferences-handlers.ts:42` 기본값 `ko`). `apps/web/src/i18n/` **53파일 7,813줄**이 전부 한국어 상수 |
| A2 | **우선순위 용어 직접 변경** | **백엔드 필수** — 이번 PR 의 백엔드 0줄 원칙과 정면 충돌 | `IssuePriority` 가 Kotlin enum 하드코딩이고 DB 에는 숫자 1~5 만 저장된다. 테이블 신설 + 마이그레이션 + API + 관리 화면이 필요. 선례는 **「해결 결과(Resolution)」**(표준 세트 불변 + 커스텀 추가 가능 구조) |

F9 이 우선순위 표기를 한글 정본 1개(`issueDetailStrings.priorityNames`)로 통일해 둔 덕에,
A1·A2 모두 **교체 지점이 한 곳**으로 모여 있다. 섞인 채 뒀다면 흩어진 자리를 다시 찾아야 했다.

### B. QA 지적 잔여 3건 (F1 은 이번 PR 에서 봉합 — `e31781f5c`)

| # | 지적 | 실측 | 판정 |
|---|---|---|---|
| F2 | **hover 어포던스 대비 미달** | 라이트 **1.34:1** · 다크 **1.37:1**. WCAG 비-텍스트 대비 기준 **3:1** 미달 | 저장소에 **더 강한 테두리 토큰이 없다** — 신설은 **디자인 시스템 변경**이라 designer 경유가 맞다. F9 단독 봉합 불가 |
| F3 | **목록 가로 오버플로** | **선재**. main 대조군 실측 있음. F9 영향 **+16px** | 선재 결함이라 F9 범위 밖. 봉합은 컬럼 폭 정책 결정을 요구 |
| F4 | **375px 레이아웃 붕괴** | **선재**. main 대조군 실측 있음. F9 영향 **0** | 위와 같음 |

F3·F4 는 **main 대조군을 실측해 선재임을 확인**했다 — 「F9 가 만들었다」로 오귀속되지 않게
숫자를 남긴다.

### C. 도구·환경 부채 2건

- **루트 `.lintstagedrc.json` 의 `apps/web/**` 항목이 도달 불가.** lint-staged 는 파일을
  디렉토리 기준으로 **깊은 설정에 먼저 배정**하고 글롭 매칭은 그 다음이라, `apps/web` 안의
  어떤 파일도 루트 설정에 닿지 않는다(프로브 E — 루트 태스크가 작업 목록에 뜨지도 않았다).
  **지울 수는 없다** — 설정이 1벌이 되면 `runAll.js` 의
  `hasMultipleConfigs` 분기가 cwd 를 루트로 되돌려 결함이 부활한다.
  **해소안**. `.husky/pre-commit` 을 `(cd apps/web && …)` 로 바꿔 1벌로도 cwd 를 잡게 하고 죽은
  항목을 제거한다(**프로브 F 로 검증됨**). 대안 ②(루트 설정에 루트 스코프 실제 lint 부여)는
  `scripts/**` 24개 JS/TS 에 린터가 **없어** 신규 도입이 필요해 보류했다(추측 구현 금지).
  **위험은 낮다** — 판별식이 2벌을 강제하고 `INPUTS` 가 `apps/web` 설정의 존재를 못박아,
  깊은 설정이 사라지면 판별식이 먼저 빨개진다.
- **worktree Playwright 함정.** 저장소 `playwright.config.ts` 의
  `webServer.command: 'pnpm dev'` 가 worktree 에서 돌지 않는다(pnpm 이 심볼릭 `node_modules` 를
  감지해 auto install 로 넘어간다 — 계열 `worktree-pnpm-verify-deps-symlink`).
  현재는 **매 실행마다 임시 config 로 우회**하고 있다. 항구 처방은 `webServer.command` 를
  `node_modules/.bin/vite` 직접 호출로 바꾸는 것이지만, 공유 자원이라 신규 충돌면을 먼저 세야 한다.
