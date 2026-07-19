# FR-UX-06 Phase 2 — automation Dialog 흡수 (9파일) — 스펙

> slug: fr-ux-06-automation-dialog-absorb · type: ui · agent: frontend-engineer · 생성: 2026-07-19

## 배경

FR-UX-06 개편 Phase 2 "Dialog 흡수"의 다음 단계. `apps/web/src/components/automation/`의 radix Dialog 9개를 공용 `ui/dialog` compound 래퍼(정본 `issues/CloneIssueDialog.tsx`)로 교체한다. 동작·데이터·API·FR(129) 불변. PR8(ESLint 락)과 분리된 자체 PR.

## 사용자 시나리오

- 없음(내부 presentation-layer 리팩터). 최종 사용자가 보는 동작·화면은 흡수 전후 동일해야 한다. 예외: 래퍼가 추가하는 우상단 X 닫기 버튼은 의도된 디자인 시스템 표준화(모든 흡수 PR 공통, X→기존 닫기 경로로 수렴).

## 실측 스코프 (개수 눈가리개 방어 — 직접 전수 열거)

흡수 대상 9파일, 모두 `import { Dialog as DialogPrimitive } from 'radix-ui'` umbrella 사용. 각 파일 radix Dialog.Root 1개.

### Tier A — 직관적 흡수 (7파일)
preventDefault 가로채기 없음 · overlay testid 없음.
1. `AutomationRuleFormDialog.tsx` — controlled(open/onOpenChange) 폼
2. `AutomationRuleList.tsx` — file-local `DeleteConfirmDialog`
3. `GitWebhookRegisterDialog.tsx` — controlled 폼
4. `GitWebhookSection.tsx` — file-local `DeleteConfirmDialog`
5. `RuleConflictWarningModal.tsx` — 경고 모달
6. `RuleExecutionHistoryDialog.tsx` — controlled 이력 조회
7. `WebhookTokenModal.tsx` — token-at-risk이나 `handleOpenChange={if(!open)onClose()}`에 preventDefault 없음 → **PatTokenModal.tsx(PR7 흡수 완료) 정본과 동일 변환**

### Tier B — 섬세 흡수 (2파일)
보안 크리티컬 닫기 가로채기 + 유닛 테스트가 overlay testid 참조.
8. `GitWebhookUrlModal.tsx` — URL 1회 노출, 2단계 CloseConfirmPrompt. `onEscapeKeyDown`/`onPointerDownOutside`에서 `preventDefault()`로 3경로 가로채기. `data-testid="git-webhook-url-overlay"`를 `GitWebhookUrlModal.test.tsx:103`이 클릭.
9. `AutomationYamlImportDialog.tsx` — tokenAtRisk 4경로(X·ESC·오버레이·onOpenChange) 조건부 가로채기. `data-testid="automation-yaml-import-overlay"`를 `AutomationYamlImportDialog.test.tsx:349`가 클릭.

흡수 대상 아님: ActionConfigEditor·ActionListEditor·ConditionBuilder·ConditionComparisonRow·ProjectMemberSelect·RuleExecutionTraceRow (radix Dialog 미사용).

## 정본 API (흡수 대상 계약)

정본 = `issues/CloneIssueDialog.tsx` + `settings/PatTokenModal.tsx`(token-at-risk 정본).
`ui/dialog.tsx` export: `Dialog · DialogTrigger · DialogPortal · DialogOverlay · DialogContent · DialogClose · DialogHeader · DialogFooter · DialogTitle · DialogDescription`.

표준 변환:
- `DialogPrimitive.Root` → `Dialog`(open/onOpenChange 그대로). `handleOpenChange` 로직 **verbatim 보존**.
- `DialogPrimitive.Portal`+`Overlay`+`Content` → `DialogContent className="max-w-*"`. 래퍼가 Portal·Overlay·우상단 X·role="dialog"·aria-modal·포커스트랩 내장.
- `DialogPrimitive.Title` → `DialogHeader`>`DialogTitle`.
- 경고문(role="alert")은 `DialogDescription` 아니라 plain `<p role="alert">` 유지 + `DialogContent aria-describedby={undefined}`(PatTokenModal 선례).
- Footer div → `DialogFooter`. 닫기 버튼 `DialogClose asChild`.
- Tier B의 `onEscapeKeyDown`/`onPointerDownOutside`/`data-testid`(Content용) → `DialogContent`에 prop으로 전달(`{...props}` forward됨).

## 엣지 케이스 / 함정 (실측 필수)

- **함정 6종**(FR-UX-06 선례) — ①sm:justify-between(bare 못이김) ②Cancel onClick 이미 닫으면 DialogClose 미포장(이중닫힘) ③prop명 매핑(isOpen/onClose) ④test 경로 flat vs __tests__/ ⑤설명 있으면 DialogDescription/없으면 aria-describedby={undefined} ⑥Trigger 있으면 DialogTrigger 유지.
- **★Tier B overlay testid 유실** — 래퍼 내부 `<DialogOverlay/>`는 prop 미수신. 흡수 시 `git-webhook-url-overlay`·`automation-yaml-import-overlay` testid 소실 → 유닛 테스트 오버레이-클릭 검증이 깨짐. 해소: 테스트를 `[data-slot="dialog-overlay"]` 선택자로 재작성(래퍼가 실제 부여).
- **★Tier B 보안 크리티컬** — URL/token-at-risk. preventDefault 가로채기 하나라도 누락 시 원문 누출 or 닫기 불가(silent 회귀). 흡수 후 반드시 e2e로 닫기 3~4경로 검증.
- **CI에 playwright 잡 없음** — 머지 전 로컬 e2e 필수(automation-git-webhook·automation-yaml-gitops·automation-conflict-warning·automation-execution-history·automation-rules + pat).

## 측정 가능한 완료 기준

- 9파일(또는 결정된 스코프) 모두 `radix-ui` Dialog import 제거, `ui/dialog` 래퍼 사용.
- 비시각 로직 `git diff -w` 변화 최소(Tier A=사실상 diff-0, Tier B=가로채기 보존 + overlay 테스트 재작성만).
- `pnpm --filter web test` 전체 green (Tier B 테스트 재작성 포함).
- `pnpm --filter web typecheck`(tsconfig.app) green.
- 로컬 e2e green(위 automation 스펙 + pat).

## ⚠ Open Decision (Phase B 발견 — Maxi 결정 필요)

Tier B 2파일(GitWebhookUrlModal·AutomationYamlImportDialog)을 이번 PR에 포함할지. 세 옵션: (1) 9파일 전부 (2) 7파일만 + Tier B 별도 PR (3) 7파일 + Tier B는 PR8 락 직전. → AskUserQuestion으로 확정 후 이 섹션 갱신.

## Brainstorming Check (← Phase B 채움)
