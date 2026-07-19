# FR-UX-06 Phase 2 — automation Dialog 흡수 (9파일)

> slug: fr-ux-06-automation-dialog-absorb
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-19

## Brief

**원문 요청**. automation 컴포넌트 디렉토리(`apps/web/src/components/automation/`)의 Dialog 9개를 CloneIssueDialog 정본 패턴으로 공용 `ui/dialog` compound 래퍼에 흡수. 순수 render-shell 리팩터라 기능·FR 불변(129). FR-UX-06 Dialog 흡수 4분할의 다음 단계이며 PR8(ESLint 락)과는 분리된 자체 PR.

**classify**. type=ui, agent=frontend-engineer, primary_bc=issue-tracking(오탐 — 실제는 apps/web 순수 프론트).

### 실측 스코프 (개수 눈가리개 방어 — 직접 전수 열거)

흡수 대상 9파일 (모두 `import { Dialog as DialogPrimitive } from 'radix-ui'` umbrella 사용, `@radix-ui/react-dialog` 아님):
1. `AutomationRuleFormDialog.tsx`
2. `AutomationRuleList.tsx` — file-local `DeleteConfirmDialog`
3. `AutomationYamlImportDialog.tsx`
4. `GitWebhookRegisterDialog.tsx`
5. `GitWebhookSection.tsx` — file-local `DeleteConfirmDialog`
6. `GitWebhookUrlModal.tsx` — `CloseConfirmPrompt` 서브구조 포함
7. `RuleConflictWarningModal.tsx`
8. `RuleExecutionHistoryDialog.tsx`
9. `WebhookTokenModal.tsx`

각 파일 = radix Dialog.Root 1개. 흡수 대상 아님: ActionConfigEditor·ActionListEditor·ConditionBuilder·ConditionComparisonRow·ProjectMemberSelect·RuleExecutionTraceRow (radix Dialog 미사용).

### 정본 API (흡수 대상 계약)

정본 소스 = `apps/web/src/components/issues/CloneIssueDialog.tsx`.
`ui/dialog.tsx` export 심볼: `Dialog · DialogTrigger · DialogPortal · DialogOverlay · DialogContent · DialogClose · DialogHeader · DialogFooter · DialogTitle · DialogDescription`.

### ★핵심 리스크 (auth PR7 대비 신규)

- **load-bearing 닫기 방지(token-at-risk) 로직** — WebhookTokenModal·GitWebhookUrlModal·RuleConflictWarningModal·AutomationYamlImportDialog는 `onOpenChange`를 가로채 X·ESC·오버레이·onOpenChange 닫기 4경로를 제어(비밀토큰 1회 노출 보호). 래퍼 기본 닫기 동작이 이 로직을 깨면 회귀. 흡수 시 `onOpenChange` 위임 정확성 실측 필수.
- **함정 6종 실측**(FR-UX-06 선례) — ①sm:justify-between ②Cancel onClick 이중닫힘 ③prop명 매핑 ④test 경로 flat vs __tests__/ ⑤DialogDescription aria ⑥DialogTrigger 유지.
- **CI에 e2e(playwright) 잡 없음** — 머지 전 로컬 e2e 필수.

## 도메인 정리

- BC: 없음 (apps/web 프론트엔드 presentation-layer 리팩터. 백엔드 도메인 모델 무관).
- 영향 엔티티: 없음. 신규 용어/유비쿼터스 언어: 없음.
- 성격: 순수 render-shell 흡수 — radix Dialog primitive → 공용 `ui/dialog` compound 래퍼. 동작·데이터·API 불변.
- 기존 결정 충돌: 없음.
- 관련 ADR: [docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md](../decisions/2026-07-17-fr-ux-06-jira-redesign.md) — **부모 승인 결정**(FR-UX-06 개편 Phase 2 Dialog 흡수). 본 작업은 그 하위 실행 단계이며 신규 결정 아님.
- grill-with-docs: 도메인 무영향 리팩터라 no-op 처리(PR5/6/7 동일 선례). 대화형 grill 생략.

## 스펙

전체 스펙. [docs/specs/2026-07-19-fr-ux-06-automation-dialog-absorb.md](../specs/2026-07-19-fr-ux-06-automation-dialog-absorb.md)

핵심 요약.
- 9파일 흡수 확정(Maxi 2026-07-19). **Tier A 7파일**(직관적, 사실상 diff-0) + **Tier B 2파일**(GitWebhookUrlModal·AutomationYamlImportDialog — 보안 크리티컬 닫기 가로채기 + overlay 테스트 재작성).
- 정본 = CloneIssueDialog(일반) + PatTokenModal(token-at-risk). WebhookTokenModal은 PatTokenModal 동형.
- Tier B: `onEscapeKeyDown`/`onPointerDownOutside`/testid를 `DialogContent`에 prop 전달({...props} forward), overlay testid 유실분은 `[data-slot="dialog-overlay"]` 선택자로 유닛 테스트 재작성.
- 머지 전 로컬 e2e 필수(CI에 playwright 잡 없음).

## Brainstorming Check

✅ 통과 (1회 iteration). Tier B overlay testid 유실 + 보안 가로채기 gap → 스코프 결정(9파일)으로 해소.

## Plan

> **리팩터 TDD 규약**. 순수 render-shell 흡수라 신규 로직·신규 테스트 없음. **RED/GREEN 오라클 = 기존 유닛 테스트**(흡수 전 green → 흡수 후 green 유지). Tier A는 `test:` 커밋 없음(edit-only refactor 커밋) — bts-impl TDD 게이트는 이 refactor 예외를 적용(PR6/7 선례: "edit-only + controller 배리어 커밋"). Tier B만 overlay-click 테스트 재작성으로 `.test.tsx` 편집 포함.
> **실행 패턴**. 각 task = 1파일 흡수(edit-only sub-agent 병렬). files 교집합 0 → bts-impl이 1 wave로 병렬. controller가 배리어 커밋 전 각 파일 `git diff -w` 실물 검증(비시각 로직 diff-0 or 의도된 최소 변경만). sub-agent 이중보고 불신 — controller 실물 대조가 유일 방어([[subagent-ktlint-false-green-controller-verify]]).

### Task 1. AutomationRuleFormDialog 흡수 (Tier A)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/AutomationRuleFormDialog.tsx`]
- depends-on: []

**RED**: 기존 `AutomationRuleFormDialog.test.tsx` green 확인(baseline).
**GREEN**: `radix-ui` Dialog import 제거 → `@/components/ui/dialog` 래퍼. `DialogPrimitive.Root`→`Dialog`, Portal+Overlay+Content→`DialogContent className="max-w-*"`, Title→`DialogHeader>DialogTitle`, footer→`DialogFooter`/`DialogClose`. controlled open/onOpenChange·onSubmit 로직 verbatim.
**REFACTOR**: 함정 6종 실측(sm:justify-between·Cancel 이중닫힘·prop명·DialogDescription/aria·Trigger).
**검증**: `pnpm --filter web test AutomationRuleFormDialog` green + `git diff -w` 비시각 로직 diff-0.

### Task 2. AutomationRuleList 흡수 (Tier A — file-local DeleteConfirmDialog)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/AutomationRuleList.tsx`]
- depends-on: []

**RED**: 기존 `AutomationRuleList.test.tsx` green 확인.
**GREEN**: file-local `DeleteConfirmDialog`의 radix→래퍼 교체(Task 1 표준 변환). 목록 로직·라벨 verbatim.
**REFACTOR**: 함정 6종 실측.
**검증**: `pnpm --filter web test AutomationRuleList` green + `git diff -w` diff-0.

### Task 3. GitWebhookRegisterDialog 흡수 (Tier A)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/GitWebhookRegisterDialog.tsx`]
- depends-on: []

**RED**: 기존 `GitWebhookRegisterDialog.test.tsx` green 확인.
**GREEN**: 표준 변환. controlled open/onOpenChange·onSubmit·Select 필드 verbatim. export API 불변(GitWebhookSection이 소비).
**REFACTOR**: 함정 6종 실측.
**검증**: `pnpm --filter web test GitWebhookRegisterDialog` green + `git diff -w` diff-0.

### Task 4. GitWebhookSection 흡수 (Tier A — file-local DeleteConfirmDialog)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/GitWebhookSection.tsx`]
- depends-on: []

**RED**: 기존 `GitWebhookSection.test.tsx` green 확인.
**GREEN**: file-local `DeleteConfirmDialog`의 radix→래퍼. 자식(Register/Url) import·조립 로직 verbatim(자식은 Task 3/8이 각자 흡수, API 불변이라 무영향).
**REFACTOR**: 함정 6종 실측.
**검증**: `pnpm --filter web test GitWebhookSection` green + `git diff -w` diff-0.

### Task 5. RuleConflictWarningModal 흡수 (Tier A)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/RuleConflictWarningModal.tsx`]
- depends-on: []

**RED**: 기존 `RuleConflictWarningModal.test.tsx` green 확인.
**GREEN**: 표준 변환. `handleOpenChange` verbatim(preventDefault 없음 확인됨). 경고문 role="alert" 유지 + aria-describedby={undefined}.
**REFACTOR**: 함정 6종 실측.
**검증**: `pnpm --filter web test RuleConflictWarningModal` green + `git diff -w` diff-0.

### Task 6. RuleExecutionHistoryDialog 흡수 (Tier A)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/RuleExecutionHistoryDialog.tsx`]
- depends-on: []

**RED**: 기존 `RuleExecutionHistoryDialog.test.tsx` green 확인.
**GREEN**: 표준 변환. controlled open/onOpenChange·필터·무한스크롤·재실행 로직 verbatim. 내부 Body 컴포넌트 구조 유지.
**REFACTOR**: 함정 6종 실측.
**검증**: `pnpm --filter web test RuleExecutionHistoryDialog` green + `git diff -w` diff-0.

### Task 7. WebhookTokenModal 흡수 (Tier A — token-at-risk, PatTokenModal 정본)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/WebhookTokenModal.tsx`]
- depends-on: []

**RED**: 기존 `WebhookTokenModal.test.tsx` green 확인.
**GREEN**: `settings/PatTokenModal.tsx` 변환과 **동일**. `handleOpenChange={if(!open)onClose()}` verbatim(preventDefault 없음). 경고문 `<p role="alert">` + aria-describedby={undefined}. 복사버튼 plain Button, 닫기 `DialogClose asChild`. code testid 보존.
**REFACTOR**: 함정 6종 실측.
**검증**: `pnpm --filter web test WebhookTokenModal` green + `git diff -w` 비시각 로직 diff-0.

### Task 8. GitWebhookUrlModal 흡수 (Tier B — 보안 크리티컬 + 테스트 재작성)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/GitWebhookUrlModal.tsx`, `apps/web/src/components/automation/GitWebhookUrlModal.test.tsx`]
- depends-on: []

**RED**: 기존 `GitWebhookUrlModal.test.tsx` green 확인(닫기 3경로·2단계 확인 EC 커버 확인).
**GREEN**:
- 표준 변환하되 **`onEscapeKeyDown`/`onPointerDownOutside`(각 `preventDefault()`+`setCloseConfirming(true)`)를 `DialogContent`에 prop 전달**(래퍼 `{...props}` forward). `handleOpenChangeAttempt` verbatim(X 버튼도 이 깔때기로 수렴). `data-testid="git-webhook-url-dialog"`는 `DialogContent`에 prop 전달. `CloseConfirmPrompt` file-local 그대로.
- overlay testid 유실: 유닛 테스트 `getByTestId('git-webhook-url-overlay')`(테스트 103줄) → 래퍼 오버레이 `[data-slot="dialog-overlay"]` 선택자로 재작성.
**REFACTOR**: 함정 6종 + preventDefault 3경로 보존 확인.
**검증**: `pnpm --filter web test GitWebhookUrlModal` green(재작성 포함). 닫기 시도 3경로가 전부 2단계 확인으로 라우팅되는지 테스트 유지.

### Task 9. AutomationYamlImportDialog 흡수 (Tier B — 보안 크리티컬 + 테스트 재작성)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/AutomationYamlImportDialog.tsx`, `apps/web/src/components/automation/AutomationYamlImportDialog.test.tsx`]
- depends-on: []

**RED**: 기존 `AutomationYamlImportDialog.test.tsx` green 확인(tokenAtRisk 4경로 EC7 커버 확인).
**GREEN**:
- 표준 변환하되 **`onEscapeKeyDown`/`onPointerDownOutside`의 조건부 `if(tokenAtRisk)preventDefault()`를 `DialogContent`에 prop 전달**. `handleOpenChangeAttempt`(`if(!next&&tokenAtRisk)`) verbatim. X 버튼 관련 EC7 주석 로직(footer Close는 tokenAtRisk로 넓히지 않음) 보존. `data-testid` Content용은 prop 전달.
- overlay testid 유실: `getByTestId('automation-yaml-import-overlay')`(테스트 349줄) → `[data-slot="dialog-overlay"]` 재작성.
**REFACTOR**: 함정 6종 + tokenAtRisk 4경로 보존 확인.
**검증**: `pnpm --filter web test AutomationYamlImportDialog` green(재작성 포함).

### Task 10. 통합 검증 (전체 유닛 + typecheck + 로컬 e2e)

**메타**.
- agent: `frontend-engineer`
- files: []
- depends-on: [1,2,3,4,5,6,7,8,9]

**검증**:
- `pnpm --filter web test` 전체 green(개수 대조 — 리팩터라 test 수 불변, Tier B 재작성분만 내용 변경).
- `pnpm --filter web typecheck`(tsconfig.app) green.
- 잔여 `radix-ui` Dialog import 0 확인: `grep -rl "Dialog as DialogPrimitive" apps/web/src/components/automation --include="*.tsx" | grep -v test` → 0건.
- 로컬 e2e: `cd apps/web && pnpm exec playwright test automation-git-webhook automation-yaml-gitops automation-conflict-warning automation-execution-history automation-rules pat` green. 후 `lsof -ti:5173 | xargs kill`.

## Plan 메타

- task 수: 10 (흡수 9 + 통합검증 1)
- 예상 wave: 2 (Task 1~9 병렬 1 wave — files 교집합 0 → 동시 dispatch, Task 10 배리어 1 wave)
- TDD 강제: refactor 예외(기존 테스트가 오라클, Tier A test: 커밋 없음 / Tier B overlay 테스트 재작성)
- 병렬 dispatch: files 교집합 0이라 1~9 전부 1 wave 후보. Tier B 2파일은 신중 검증(보안).
- 추가 검증: typecheck(tsconfig.app), vitest 전체, 로컬 playwright(CI에 e2e 잡 없음).

## 리뷰 결과

### plan-eng-review (2026-07-19) — 초점 리뷰

순수 render-shell 리팩터(강한 선례 PR5/6/7)라 full 4섹션 인터랙티브 + codex는 overkill([[bts-review-plan-autoplan-overkill]]). 관련 9파일 + 래퍼 + 정본 실측 후 초점 엔지니어링 리뷰 수행.

- ✅ **Tier B 가로채기 prop 전달 정확** — 래퍼 `DialogContent`가 `{...props}`를 `DialogPrimitive.Content`로 spread(`ui/dialog.tsx:62`). `onEscapeKeyDown`/`onPointerDownOutside`/`data-testid` 통과 보장.
- ✅ **래퍼 내장 X가 보안 가드 우회 안 함** — X→`onOpenChange(false)`→`handleOpenChangeAttempt`→2단계 확인 라우팅. 실제 닫힘 없음(URL/token 소멸 없음). 원본 설계와 일치.
- ✅ **blast radius 작음 / boring** — 기존 래퍼 재사용, 신규 추상화·인프라 0, 백엔드·cross-BC 무영향. 9 프론트 + 2 테스트.
- ⚠️ **CONCERN (BLOCKER 아님)** — cross-file 테스트 의존: `GitWebhookSection`(T4)이 자식 `GitWebhookRegisterDialog`(T3)·`GitWebhookUrlModal`(T8)을 렌더 시, 자식 흡수가 Section 테스트 DOM 단언을 흔들 가능성. → impl에서 T4/T3/T8 상호 확인 + T10 전체 테스트로 방어.
- ⚠️ **주의** — 시각 변화 1건: 래퍼가 흡수 대상에 우상단 X 닫기 버튼 추가(모든 흡수 PR 공통 표준화). token/URL-at-risk 파일도 X→2단계 확인이라 보안 보존. 게이트1에서 Maxi 확인 항목.
- **BLOCKER: 없음.**

### plan-design-review — 생략 사유
type=ui지만 순수 로직 보존 리팩터(새 비주얼 없음). 유일한 시각 델타(표준 X 버튼)는 위 주의로 게이트1 위임. design-shotgun/design-consultation 미적용(DESIGN.md 기존).
