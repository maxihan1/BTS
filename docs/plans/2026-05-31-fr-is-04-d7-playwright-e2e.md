# FR-IS-04 D7 — Playwright E2E (PR 3/3)

> slug: fr-is-04-d7-playwright-e2e
> type: qa
> agent: qa-engineer
> 생성: 2026-05-31

## Brief

FR-IS-04(이슈 본문 Markdown + 우선순위/라벨/환경/영향도)의 마지막 조각(D7). FR 분할 3PR 중 3/3.
백엔드(PR #43)·프론트 D6(PR #46) 머지 완료. 이번 PR은 D6에서 구현한 본문/메타필드 UI를 Playwright E2E로 검증.

**시나리오 후보**: 본문 Write/Preview 작성→저장→렌더, 우선순위/영향도 셀렉터 변경(즉시 PATCH), 환경/라벨 저장.
**MSW stateful**: issue-handlers.ts에 5필드 PATCH 영속 이미 구현(D6). E2E는 그 위에서 동작.
**선례**: e2e/issue-type-change.spec.ts(D6 셀렉터), issue-transition.spec.ts(D7), issue-edit-conflict.spec.ts(OCC).

**주의 (메모리)**:
- worktree-node-modules-partial-install: E2E 전 worktree node_modules 정상성 확인 필수(Vite dev 부팅). 깨졌으면 main에서 dist cp 복구.
- e2e-orphan-vite-after-worktree-remove: worktree remove 후 5173 orphan Vite 가능.
- e2e-msw-serviceworker-block: serviceWorkers:'block' 금지(MSW 부팅 깨짐).
- playwright-getbyrole-exact-strict-mode: 같은 텍스트 버튼 여러 곳 → exact/컨테이너 한정.

## 도메인 정리

- BC: issue-tracking
- 영향 엔티티: 없음 (E2E 검증 전용, 코드 변경은 테스트 파일 + 필요 시 MSW 핸들러뿐)
- 새 용어: 없음. 도메인/계약은 PR #43(백엔드)·#46(D6 프론트)에서 전부 확정.
- 기존 결정 충돌: 없음.
- 관련 ADR: 없음 (E2E는 새 결정 0).
- grill-with-docs 스킵 — 정의된 작업, 기존 spec 시나리오를 Playwright로 옮길 뿐.

## 스펙

전체 스펙. [docs/specs/2026-05-31-fr-is-04-d7-playwright-e2e.md](../specs/2026-05-31-fr-is-04-d7-playwright-e2e.md)

E2E 6 시나리오(happy 중심): E1 본문 작성→Preview 렌더 / E2 우선순위 변경 / E3 영향도 설정+미지정 disabled / E4 라벨 추가 저장 / E5 환경 저장 / E6 OCC 409 toast. 앱 내장 MSW(D6 stateful PATCH) 위에서 동작, loginAsAlice fixture 재사용. 구현 코드(src/) 수정 금지(qa 경계).

## Brainstorming Check

✅ 통과 (직접 sanity review). gap 1건(E6 409 유도) → issue-edit-conflict.spec.ts 패턴 재사용. 실행 전제(worktree node_modules/5173 orphan/serviceWorkers block) 메모리 가드 명시.

## Plan

> qa-engineer. E2E는 단일 spec 파일이라 1 task(시나리오 분리 시 같은 파일 병렬 race). 구현 코드(src/) 수정 금지.

### Task 1. FR-IS-04 본문/메타필드 Playwright E2E (6 시나리오)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-body-meta.spec.ts`, `apps/web/src/mocks/issue-handlers.ts`(E6 409 트리거 보강 필요 시만)]
- depends-on: []

**RED**:
- `e2e/issue-body-meta.spec.ts` 신규 — E1~E6 시나리오 작성. loginAsAlice + page.goto('/issues/ATLAS-1') 패턴(issue-type-change.spec.ts 답습).
- 작성 직후 실행 → 셀렉터/타이밍 미정합으로 실패하는 시나리오 확인(첫 실행 RED). 실패 출력 첨부.

**GREEN**:
- 셀렉터/대기(expect.toBeVisible/toHaveValue/refetch 대기) 정합 맞춰 6 시나리오 전부 통과.
- 셀렉터 기준(D6 구현 확인):
  - 본문: `getByTestId('description-preview-content')`, aria `descriptionEditButton`/`descriptionWriteTab`/`descriptionSaveButton`. textarea는 Write 탭.
  - 우선순위/영향도: `getByRole('combobox', {name: prioritySelectLabel/impactSelectLabel})`. 미지정 옵션 disabled는 `option[disabled]` 확인.
  - 환경: `within(getByTestId('environment-section'))` → 입력 + `environmentSaveButton`.
  - 라벨: `within(getByTestId('labels-section'))` → `labelAddPlaceholder` 입력 + Enter + `labelsSaveButton`, 칩 표시 확인.
- **(메모리 playwright-getbyrole-exact-strict-mode)** 본문 "저장"과 메타 "저장" 버튼 중복 → 반드시 `within(섹션)` 또는 aria-label exact로 한정.
- **(메모리 msw-mutation-stateful-refetch)** E1/E4/E5는 저장 후 refetch 반영을 expect로 대기(placeholder→본문, 칩 표시). D6 핸들러가 stateful이라 통과해야 함.
- **E6 409**: issue-edit-conflict.spec.ts의 `MOCK_CONFLICT_TRIGGER` 메커니즘을 issue-handlers.ts에서 확인. 메타필드(예 환경/라벨)에서 409를 유도할 트리거가 있으면 활용, 없으면 (a) 핸들러에 메타필드용 conflict 트리거 1줄 추가 또는 (b) E6을 issue-edit-conflict가 이미 커버하므로 스킵+plan에 사유 명시. qa-engineer가 핸들러 확인 후 결정.

**REFACTOR**:
- describe/시나리오 주석 정리, 공통 navigate 헬퍼화.

**검증**: `cd apps/web && pnpm exec playwright test issue-body-meta` + 기존 E2E 회귀 확인. (실행 전 worktree node_modules 정상성 + 5173 orphan 확인 — controller 책임)

## Plan 메타

- task 수: 1 (E2E 단일 파일)
- 예상 wave: 1
- TDD: E2E는 작성→실행→정합(RED 첫실행 실패→GREEN 통과). 구현은 D6 완료.
- 함정 가드: worktree-node-modules-partial-install(실행 전제), e2e-orphan-vite(5173), e2e-msw-serviceworker-block(config 확인), playwright-getbyrole-exact-strict-mode(저장 버튼 중복), msw-mutation-stateful-refetch(refetch 반영).

## 리뷰 결과

- /bts-review-plan **스킵** — type=qa fast-track(SKILL 분기표 {bugfix,chore,qa}→skip). E2E 검증 작업이라 plan 리뷰 과함.
- controller 자체 sanity: E6 409가 유일 불확실점 → plan에서 "qa-engineer가 핸들러 확인 후 활용/추가/스킵 결정"으로 유연 처리. 셀렉터는 D6 구현 grep 확인. 단일 task라 race 0. BLOCKER 없음.
