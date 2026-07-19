# FR-UX-06 PR6 — 소규모 BC Dialog 흡수 (9파일)

> slug: fr-ux-06-pr6-dialog-absorb-agile
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-19

## Brief

FR-UX-06 Dialog 흡수 4분할 중 PR6. **정본 = PR5(#289 CloneIssueDialog)**.
plan 원안 "PR6=agile-planning"은 실측상 board/ 2파일뿐 → Maxi 결정으로 **소규모 BC 묶음 9파일**로 재배분.
auth 계열 10·automation 9는 PR7/PR8로 이연.

### IN-SCOPE (9파일, 실측)
| BC | 파일 |
|---|---|
| agile-planning `board/` | ResolutionPickerModal · SaveQuickFilterDialog |
| search-export-import `search/` | ExportDialog · SaveFilterDialog · ShareFilterDialog |
| project-workflow `workflow/` | PostActionFormDialog |
| notification-dashboard `dashboard/` | GadgetCatalogModal |
| personalization `keyboard-shortcuts/` | ShortcutsHelpDialog |
| personalization `status/` | StatusModal |

### 흡수 정본 패턴 (PR5 확립, 재사용)
- render() 껍데기만 교체(Root/Portal/Overlay/Content/Title/footer div → 래퍼). 비시각 로직 diff-0(`git diff -w` 검증).
- 시각=Jira 통일(스크림 /50·X·rounded-lg·bg-popover는 래퍼 위임). max-w/max-h만 `DialogContent className`.
- grid gap-4 재간격(개별 margin 정리). 함정: `sm:justify-between`(bare 못이김) · Cancel onClick은 DialogClose 미포장(이중호출).
- 정본 소스: `git show 67a641300:apps/web/src/components/issues/CloneIssueDialog.tsx` 또는 현 apps/web/src/components/issues/CloneIssueDialog.tsx.
- 관련: [[fr-ux-06-pr5-dialog-absorb-done]] · [[fr-ux-06-jira-redesign-plan]]

## 도메인 정리

- 여러 소규모 BC의 프론트 Dialog 9개. 흡수는 **순수 UI 껍데기 교체**(BC 로직·백엔드 무관). 새 용어 0, ADR 충돌 0. 상위 FR-UX-06 ADR 종속.
- grill-with-docs 스킵(도메인 신규성 0, PR5와 동일). 정본 = PR5(#289). 관련 [[fr-ux-06-pr5-dialog-absorb-done]].

### 실측 (worktree HEAD = main 67a641300, PR5 흡수 포함)
- **9파일 전부 일반 Dialog**(AlertDialog 0) → compound 래퍼로 완전 커버.
- import 별칭: **7파일 `Dialog as DialogPrimitive`**(표준), **2파일 별칭 없는 `Dialog`**(workflow/PostActionFormDialog·dashboard/GadgetCatalogModal → `Dialog.Root`/`Dialog.Content` 형태, 흡수 시 별칭 처리 주의).
- 줄 수: ShortcutsHelp 97 ~ ExportDialog 529. PR5 대비 파일 수 적고(9 vs 12) 래퍼 확정 단계 불필요.

## 스펙

**전체 계약 = PR5 spec 재사용** (`docs/specs/2026-07-19-fr-ux-06-pr5-dialog-absorb.md`, 머지됨). 확정 compound API 계약(controlled·className override·DialogHeader/Title/Footer·X닫기 기본on) 그대로.

**FR.** 9파일이 `radix-ui` Dialog 직접 import 제거 → `@/components/ui/dialog` compound 래퍼 소비. 시각 Jira 통일(스크림 /50·X·rounded-lg·bg-popover, 래퍼 위임). 비시각 로직 diff-0.

**흡수 정본 패턴 (PR5 §흡수 공통 절차 그대로).**
- render() 껍데기(Root/Portal/Overlay/Content/Title/footer div)만 → 래퍼. max-w/max-h만 `DialogContent className`. `<DialogHeader><DialogTitle>(+Description 승격)`. footer → `DialogFooter`.
- 로직(useState/useEffect/핸들러/mutation) byte-identical. `git diff -w`로 검증.
- grid gap-4 재간격(개별 mb-*/mt-* 정리). 함정: `justify-between`→`sm:justify-between`, Cancel `onClick` 직접호출은 `DialogClose` 미포장(이중호출 방지), 설명 없으면 `aria-describedby={undefined}`.
- 흡수 완료 신호로 test에 X닫기(`name:/close/i`) 어서션 1개 추가, 기존 행위 어서션 수정 금지.

**리스크.**
- R1 (별칭 없는 2파일) — `Dialog.Root`/`Dialog.Content`를 쓰는 workflow/PostActionFormDialog·dashboard/GadgetCatalogModal은 흡수 시 `Dialog`(로컬 별칭 아님, 래퍼 export) import로 교체하되 컴포넌트 참조 형태 변화 주의.
- R2 (StatusModal 재열림 초기화) — personalization, `useEffect([open,...])` 재열림 stale 방어 로직 있음. render 껍데기만 교체, effect 무변화.
- R3 (ExportDialog 529줄) — search-export-import formula injection 방어 폼. 껍데기만 교체, 폼 로직 무관.

## Brainstorming Check

✅ self-adversarial 통과 — gap 3건(R1 별칭·R2 재열림·R3 큰파일) 리스크 반영. 정본·API·함정은 PR5에서 확립·검증됨.

## Plan

> **wave 1개** — 9파일 전부 독립(파일 겹침 0, 래퍼 무수정이라 depends-on 없음). PR5와 달리 래퍼 확정 선행 불필요.
> **★병렬 규칙(PR5 재사용)**: sub-agent edit-only(커밋 금지) → 9편집 배리어 후 controller 순차커밋. 검증 vitest 3~4배치.
> **흡수 공통 절차** = 스펙 §흡수 정본 패턴. 각 task는 아래 파일별 특이사항만 추가.

### Task 1. board/ResolutionPickerModal 흡수
**메타**. files: [`apps/web/src/components/board/ResolutionPickerModal.tsx`, `apps/web/src/components/board/ResolutionPickerModal.test.tsx`] · depends-on: []
**특이사항**: 126줄. `as DialogPrimitive`. resolution 선택 로직 무변화.

### Task 2. board/SaveQuickFilterDialog 흡수
**메타**. files: [`apps/web/src/components/board/SaveQuickFilterDialog.tsx`, `apps/web/src/components/board/SaveQuickFilterDialog.test.tsx`] · depends-on: []
**특이사항**: 292줄. 퀵필터 저장 폼 로직 무변화.

### Task 3. search/ExportDialog 흡수
**메타**. files: [`apps/web/src/components/search/ExportDialog.tsx`, `apps/web/src/components/search/ExportDialog.test.tsx`] · depends-on: []
**특이사항**: 529줄(최대). formula injection 방어·export 옵션 폼 로직 무변화(R3).

### Task 4. search/SaveFilterDialog 흡수
**메타**. files: [`apps/web/src/components/search/SaveFilterDialog.tsx`, `apps/web/src/components/search/SaveFilterDialog.test.tsx`] · depends-on: []
**특이사항**: 277줄. 필터 저장 폼.

### Task 5. search/ShareFilterDialog 흡수
**메타**. files: [`apps/web/src/components/search/ShareFilterDialog.tsx`, `apps/web/src/components/search/ShareFilterDialog.test.tsx`] · depends-on: []
**특이사항**: 202줄. 공유 토큰 UI.

### Task 6. workflow/PostActionFormDialog 흡수 🔴 별칭없음
**메타**. files: [`apps/web/src/components/workflow/PostActionFormDialog.tsx`, `apps/web/src/components/workflow/__tests__/PostActionFormDialog.test.tsx`] · depends-on: []
**특이사항**: 271줄. **`import { Dialog } from 'radix-ui'`(별칭 없음 → `Dialog.Root`/`Dialog.Content` 형태)**. 흡수 시 래퍼 `Dialog`와 이름 충돌 주의 — radix `Dialog.*` 참조를 래퍼 compound로 전면 교체(R1).

### Task 7. dashboard/GadgetCatalogModal 흡수 🔴 별칭없음
**메타**. files: [`apps/web/src/components/dashboard/GadgetCatalogModal.tsx`, `apps/web/src/components/dashboard/GadgetCatalogModal.test.tsx`] · depends-on: []
**특이사항**: 193줄. **`import { Dialog } from 'radix-ui'`(별칭 없음)**. R1 동일 처리.

### Task 8. keyboard-shortcuts/ShortcutsHelpDialog 흡수
**메타**. files: [`apps/web/src/components/keyboard-shortcuts/ShortcutsHelpDialog.tsx`, `apps/web/src/components/keyboard-shortcuts/ShortcutsHelpDialog.test.tsx`] · depends-on: []
**특이사항**: 97줄(최소). 단축키 도움말 표시.

### Task 9. status/StatusModal 흡수
**메타**. files: [`apps/web/src/components/status/StatusModal.tsx`, `apps/web/src/components/status/StatusModal.test.tsx`] · depends-on: []
**특이사항**: 134줄. personalization. `useEffect([open,...])` 재열림 초기화 방어(R2) — render 껍데기만, effect 무변화.

### Task 10. 전수 검증
**메타**. agent `qa-engineer` · files: [] · depends-on: [1,2,3,4,5,6,7,8,9]
**내용**: `grep "from 'radix-ui'" <9파일>`=0 · typecheck·lint·전체 test 무회귀 · role="dialog" 계약 무손상.

## Plan 메타
- task 수: 10 (T1~9 파일별 흡수 + T10 검증)
- 예상 wave: 2 (9병렬 → 검증). 래퍼 확정 선행 불필요(PR5서 확정됨).
- 병렬: edit-only + 배리어 후 controller 순차커밋. 검증 3~4배치.

## 리뷰 결과

### controller self-review (2026-07-19) — plan이 PR5 정본 재사용이라 경량

PR5에서 흡수 정본·compound API·함정(sm:justify-between·Cancel onClick·aria-describedby·gap 재간격)·병렬 규칙(edit-only+배리어)이 모두 확립·검증됨. PR6 plan은 그 재사용 + 9파일 특이사항. plan 자체 신규 리스크 낮음. 실행 리스크는 impl 후 게이트2 code-reviewer가 `git diff -w` 로직 diff-0 검증으로 커버(PR5 선례).

**self-adversarial 확인.**
- ✅ **files 경로**(PR5 BLOCKER 재발 방지) — 9파일 test 경로 실측: 8 flat + **workflow/PostActionFormDialog `__tests__/`**(정정 완료).
- ✅ **primitive** — 9파일 전부 일반 Dialog(AlertDialog 0), compound 래퍼 완전 커버.
- ✅ **별칭 없는 2파일**(R1) — workflow·dashboard `import { Dialog } from 'radix-ui'`는 `Dialog.Root`/`.Content` 형태. 흡수 시 래퍼 `Dialog`와 이름 충돌 없이 전면 교체하도록 Task 6·7 특이사항 명시.
- ✅ **래퍼 무수정** — PR5서 확정·검증(TC-4 회귀가드 존재). PR6는 래퍼 선행 불필요, 9파일 전부 독립 병렬.
- ✅ **함정 3종** 정본 참조로 반영(공통 절차).

**게이트2 위임** — impl 후 code-reviewer가 9파일 로직 diff-0·시각 통일·별칭 처리·이중닫기 전수 검증.
