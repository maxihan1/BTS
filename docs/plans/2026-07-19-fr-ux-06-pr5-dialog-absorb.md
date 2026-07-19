# FR-UX-06 PR5 — issue-tracking Dialog 흡수 (래퍼 API 확정)

> slug: fr-ux-06-pr5-dialog-absorb
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking (프론트 계층)
> 생성: 2026-07-19

## Brief

FR-UX-06 (Jira Cloud 방식 UI/UX 전면 개편)의 Phase 2 PR5.
issue-tracking BC의 화면들이 각자 Radix Dialog를 직접 import하고 Overlay 클래스
문자열을 복붙하는 것을, PR2에서 만든 공용 `components/ui/dialog` 래퍼로 흡수한다.

- **핵심 = 래퍼 API 확정.** Dialog 4분할(PR5 → PR6∥PR7 → PR8) 중 첫 PR.
  여기서 props 인터페이스를 잘못 정하면 PR6~8이 전부 재작업.
- 순수 프론트엔드 리팩터. 백엔드 코드 변경 0.
- classify 오판(api/backend) → controller가 ui/frontend-engineer로 정정(Maxi 승인).

### 🔒 반드시 지킬 계약 (선행 메모리)
- **role="dialog" 불변** — E2E 147건이 Radix `DialogPrimitive.Content` role에 의존.
  래퍼가 같은 primitive를 감싸는 한 DOM 계약 불변 → 흡수는 안전. 다른 걸로 교체 시 즉사.
- **PR8에서 ESLint 락** — `radix-ui` Dialog 직접 import 금지 룰(이번 PR 아님, 체인 마무리).
- 관련 메모리: [[fr-ux-06-jira-redesign-plan]] · [[frontend-nav-aria-label-e2e-contract]] ·
  [[frontend-zod-backend-dto-contract-gap]]

## 도메인 정리

- **BC**: issue-tracking (프론트 계층). 순수 UI 리팩터 — 백엔드 코드·도메인 모델 변경 0.
- **새 용어**: 없음. **기존 결정 충돌**: 없음. **관련 ADR**: FR-UX-06 상위 ADR `docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md`(D1~D8)에 종속, 신규 ADR 불필요.
- grill-with-docs 스킵 — 도메인 신규성 0(엔티티/용어/관계 무변경). domain 실질 = "래퍼 API 형태 + BC 경계"이며 실측으로 확정.

### 실측 (2026-07-19, worktree HEAD = main 62129317d)

**① 흡수 목표 = `components/ui/dialog.tsx` (PR2 #286 산출).**
- shadcn식 **compound components** API. props 단일체가 아니라 개별 export 10종:
  `Dialog · DialogTrigger · DialogPortal · DialogOverlay · DialogContent · DialogClose · DialogHeader · DialogFooter · DialogTitle · DialogDescription`.
- `DialogContent`가 `DialogPortal + DialogOverlay(bg-black/40 스크림) + DialogPrimitive.Content(우상단 X 닫기 포함)`를 캡슐화. Overlay 클래스·닫기 버튼 복붙이 사라지는 지점.
- **role="dialog" 계약 보존** — 래퍼가 `DialogPrimitive.Content`를 그대로 감쌈(파일 L1 주석 명시). E2E 147건 안전.

**② 현재 소비처 = 0개.** PR2가 만들었지만 아무 화면도 이 래퍼를 안 씀 → **PR5가 첫 소비자 = "API 확정 PR"의 실제 의미**(compound API가 실사용을 충분히 커버하는지 첫 검증·확정).

**③ 전체 Radix Dialog 직접 import = 42파일** (여러 BC). PR5는 issue-tracking BC만, 나머지는 PR6/7/8.

**④ PR5 IN-SCOPE 후보 = issue-tracking BC 12파일** (전부 파일 실재 + Radix 직접 참조 확인).

| 디렉토리 | 파일 |
|---|---|
| `issue/` (2) | AttachmentPreviewModal · ResolutionModal |
| `issues/` (5) | CloneIssueDialog · BulkEditDialog · BulkOperationResultDialog · MoveIssueDialog · BulkTransitionDialog |
| `component/` (1) | ComponentFormDialog |
| `version/` (2) | VersionFormDialog · ReleaseNotesDialog |
| `custom-fields/` (1) | CustomFieldFormDialog |
| `issue-templates/` (1) | IssueTemplateFormDialog |

- plan.md 원안 "≈10파일"과 정합(±2). 정확한 줄 수·props 패턴은 spec에서 파일별 실측.

**⑤ 경계 케이스 = `board/` 2파일** (ResolutionPickerModal · SaveQuickFilterDialog).
- board는 agile-planning일 수 있으나 resolution은 issue-tracking 개념 → **spec에서 BC 판정**(포함 시 PR5, 아니면 PR6로 이연). 자의 판정 금지.

### /bts-domain 채움

## 스펙

전체 스펙. [docs/specs/2026-07-19-fr-ux-06-pr5-dialog-absorb.md](../specs/2026-07-19-fr-ux-06-pr5-dialog-absorb.md)

**핵심 3줄.**
- issue-tracking 12파일이 radix Dialog 직접 import를 버리고 `ui/dialog` compound 래퍼 소비.
- **시각 = Jira 통일(Maxi 결정 옵션 B)** — 스크림 /50·우상단 X·rounded-lg·bg-popover·ring 동시 적용.
- max-width는 `DialogContent className`으로 override(prop 신설 불필요). 비시각 로직 100% 무변화.

**확정 래퍼 API 계약.** controlled(`open`/`onOpenChange`, Trigger 미사용) + className override(max-w/max-h) + `DialogHeader/Title/(Description)` + X닫기 기본 on + `DialogFooter`/`DialogClose`.

**결정.**
- 🔴 시각 계약 = **옵션 B Jira 통일**(Maxi 2026-07-19). PR6~8 템플릿.
- board/2파일(ResolutionPickerModal·SaveQuickFilterDialog) = **PR5 제외**(디렉토리 응집성, 게이트1 검토).

**리스크 4.** R1 래퍼 애니메이션 문법(`data-open:`) 미정의 → 구현 첫 단계 검증(안 먹으면 래퍼 수정) · R2 VersionFormDialog onClose 어댑팅 · R3 aria-describedby 12파일 통일 · R4 시각 회귀 가시성(로직 diff 0 보장).

## Brainstorming Check

✅ self-adversarial 통과 — gap 4건(R1~R4)을 스펙 리스크로 반영. AlertDialog 혼입 0·board 경계·max-width 파라미터화는 실측으로 해소. office-hours/design-shotgun은 리팩터 성격상 스킵(근거 도메인 정리 참조).

## Plan

> writing-plans 대신 실측 기반 직접 분해(반복 흡수 12x + 파일별 특이사항 정밀 반영). 형식(메타·RED/GREEN/REFACTOR) 준수.
> **wave**: wave1 = T1(래퍼 확정) → wave2 = T2~T13(12파일 병렬, 전부 depends-on[1], files 겹침 0).
> **★병렬 흡수 규칙(PR4 교훈 [[worktree-lint-staged-shared-git-stash-collision]]·[[parallel-dispatch-precommit-hook-race]])**: 각 sub-agent는 **edit-only**(커밋 금지), controller가 **자기 파일만 stage 후 순차 커밋**해 index.lock/공유stash 레이스 회피. 리뷰 뮤테이션 오염 방지 위해 대조는 `git show HEAD:` ([[parallel-review-mutation-contaminates-peers]]).

### 흡수 공통 절차 (T2~T13 전부 이 템플릿 따름)

- **RED**. 해당 `*.test.tsx`에 흡수 계약 어서션 추가 → 현재 radix 직접 import라 실패.
  1. `from 'radix-ui'` Dialog import 부재(흡수 완료 신호) — 소스 문자열/구조 검증.
  2. `role="dialog"` 존속(getByRole('dialog')).
  3. 시각 어서션이 기존에 있으면(`bg-black/40`·X버튼 부재 등) **신규 Jira값으로 갱신**(스크림 /50·X버튼 존재).
- **GREEN**. `radix-ui` Dialog 직접 구조 → `@/components/ui/dialog` compound 교체.
  - `Root/Portal/Overlay/Content` → `<Dialog open onOpenChange><DialogContent className="{기존 max-w/max-h}">`.
  - `<Title>` → `<DialogHeader><DialogTitle>`. 설명 `<p>` 있으면 `<DialogDescription>` 승격, 없으면 `<DialogContent aria-describedby={undefined}>`로 통일(R3).
  - footer `flex justify-end` → `<DialogFooter>`. `<Close asChild><Button>` 유지.
  - **비시각 로직(폼·폴링·blob·복사·상태초기화)은 diff 0 — 구조만 교체**(R4 회귀 가시성).
- **REFACTOR**. 남은 인라인 클래스 상수·불필요 import 제거.
- **검증**. `pnpm --filter web test <file>.test.tsx` + 흡수 후 `grep "radix-ui" <file>` = 0.

---

### Task 1. 래퍼 확정 — 애니메이션 문법(R1) 검증·수정 + 계약 테스트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/ui/dialog.tsx`, `apps/web/src/components/ui/dialog.test.tsx`]
- depends-on: []

**RED**: `ui/dialog.test.tsx` 신규.
- `<Dialog open><DialogContent>본문</DialogContent></Dialog>` 렌더 후:
  - `getByRole('dialog')` 존재.
  - 우상단 X = `getByRole('button', { name: 'Close' })`(sr-only) 존재.
  - **애니메이션 문법 정합** — `DialogContent`/`DialogOverlay` className이 Radix 실렌더 속성과 맞는
    `data-[state=open]:` / `data-[state=closed]:` 문법 포함. 현재 `data-open:`/`data-closed:`라 **실패(RED)**.
- 실패 근거: `index.css`에 `data-open` `@custom-variant` 미정의 + Radix는 `data-state="open"` 렌더 →
  현 문법은 매칭 안 돼 열림/닫힘 애니메이션 소실.

**GREEN**: `ui/dialog.tsx`의 `data-open:` → `data-[state=open]:`, `data-closed:` → `data-[state=closed]:` 치환
(`DialogOverlay` L40, `DialogContent` L59, `fade`/`zoom` 파생 포함). 실브라우저로 열림/닫힘 트랜지션 육안 확인.

**REFACTOR**: 없음(치환만). 스크림/애니메이션 상수 추출은 과함 → 스킵.

**검증**: `pnpm --filter web test dialog.test.tsx` + `pnpm --filter web typecheck`.

> ⚠️ 만약 실브라우저 검증 결과 `data-open:`이 tailwind v4에서 실제 동작하면(예상 밖) GREEN을 no-op으로
> 두고 테스트만 실제 문법에 맞춤. **추측 금지 — 구현 첫 액션이 실동작 확인**([[read-errors-dont-guess]] 정신).

---

### Task 2. issue/AttachmentPreviewModal 흡수

**메타**. agent `frontend-engineer` · files: [`apps/web/src/components/issue/AttachmentPreviewModal.tsx`, `apps/web/src/components/issue/AttachmentPreviewModal.test.tsx`] · depends-on: [1]
**특이사항**: `max-w-4xl`(이미지 프리뷰). blob URL 생명주기 useEffect **로직 무변화**. `aria-describedby={undefined}` 유지. 공통 절차 따름.

### Task 3. issue/ResolutionModal 흡수

**메타**. files: [`apps/web/src/components/issue/ResolutionModal.tsx`, `apps/web/src/components/issue/__tests__/ResolutionModal.test.tsx`] · depends-on: [1]
**특이사항**: test 경로가 `__tests__/`. "취소" 버튼(`name:'취소'`) 닫기 테스트 존속. 공통 절차.

### Task 4. issues/CloneIssueDialog 흡수

**메타**. files: [`apps/web/src/components/issues/CloneIssueDialog.tsx`, `apps/web/src/components/issues/CloneIssueDialog.test.tsx`] · depends-on: [1]
**특이사항**: `max-w-md`. mutation 자기소유(submitError dead-path 금지 KDoc 유지). 상태초기화 useEffect 무변화.

### Task 5. issues/BulkEditDialog 흡수

**메타**. files: [`apps/web/src/components/issues/BulkEditDialog.tsx`, `apps/web/src/components/issues/BulkEditDialog.test.tsx`] · depends-on: [1]
**특이사항**: 공통 절차. 폼 로직 무변화.

### Task 6. issues/BulkOperationResultDialog 흡수

**메타**. files: [`apps/web/src/components/issues/BulkOperationResultDialog.tsx`] (+ 있으면 test) · depends-on: [1]
**특이사항**: `max-w-lg`. 폴링 로직·aria-live 무변화. footer 닫기가 raw `<Close className=...>`(Button 아님) → `<DialogClose asChild><Button variant="outline">`로 정규화(시각 통일 취지). aria-describedby 없음 → undefined 통일.

### Task 7. issues/MoveIssueDialog 흡수

**메타**. files: [`apps/web/src/components/issues/MoveIssueDialog.tsx`, `apps/web/src/components/issues/MoveIssueDialog.test.tsx`] · depends-on: [1]
**특이사항**: 369줄, footer 2개소(복잡). 이동 로직·워크플로우 매핑 UI 무변화. footer 구조 신중 교체.

### Task 8. issues/BulkTransitionDialog 흡수

**메타**. files: [`apps/web/src/components/issues/BulkTransitionDialog.tsx`, `apps/web/src/components/issues/BulkTransitionDialog.test.tsx`] · depends-on: [1]
**특이사항**: 전이 선택 로직 무변화. 공통 절차.

### Task 9. component/ComponentFormDialog 흡수

**메타**. files: [`apps/web/src/components/component/ComponentFormDialog.tsx`, `apps/web/src/components/component/ComponentFormDialog.test.tsx`] · depends-on: [1]
**특이사항**: 폼 다이얼로그. 공통 절차.

### Task 10. version/VersionFormDialog 흡수 🔴 onClose 어댑팅(R2)

**메타**. files: [`apps/web/src/components/version/VersionFormDialog.tsx`, `apps/web/src/components/version/VersionFormDialog.test.tsx`] · depends-on: [1]
**특이사항**: 현재 **`onClose` prop**(open/onOpenChange 아님). 외부 시그니처(`onClose`) 유지하고 내부에서
`<Dialog open={open} onOpenChange={(o)=>{ if(!o) onClose() }}>` 어댑팅. 부모 호출부(VersionList 등) 무변경 확인.

### Task 11. version/ReleaseNotesDialog 흡수

**메타**. files: [`apps/web/src/components/version/ReleaseNotesDialog.tsx`] (+ 있으면 test) · depends-on: [1]
**특이사항**: `max-w-2xl max-h-[80vh] flex flex-col`(스크롤). 설명 `<p>` → `<DialogDescription>` 승격.
footer `justify-between`(메타+버튼) → `DialogFooter className="justify-between"`. 클립보드 복사 로직 무변화.
`OVERLAY_CLASS`/`CONTENT_CLASS` 상수 제거(래퍼가 담당).

### Task 12. custom-fields/CustomFieldFormDialog 흡수

**메타**. files: [`apps/web/src/components/custom-fields/CustomFieldFormDialog.tsx`, `apps/web/src/components/custom-fields/CustomFieldFormDialog.test.tsx`] · depends-on: [1]
**특이사항**: 415줄(최대). JSONB 커스텀필드 폼 로직 무변화. 공통 절차 신중 적용.

### Task 13. issue-templates/IssueTemplateFormDialog 흡수

**메타**. files: [`apps/web/src/components/issue-templates/IssueTemplateFormDialog.tsx`, `apps/web/src/components/issue-templates/IssueTemplateFormDialog.test.tsx`] · depends-on: [1]
**특이사항**: 423줄, footer 2개소. 템플릿 폼 로직 무변화.

---

### Task 14. 전수 검증 + 시각 QA (마무리)

**메타**. agent `qa-engineer` · files: [] (검증만, 코드 수정 없음) · depends-on: [2,3,4,5,6,7,8,9,10,11,12,13]
**내용**:
- `grep -rn "from 'radix-ui'" <12파일>` = 0 전수 확인(흡수 완결 증거).
- `pnpm --filter web typecheck && lint && test` 전체 green.
- 관련 E2E(이슈 상세/일괄/버전/컴포넌트) green — `role="dialog"` 147 계약 무손상.
- 시각 통일 육안 확인(스크림 /50·X버튼·rounded-lg·bg-popover) — 실브라우저 대표 3화면 스크린샷.

## Plan 메타

- task 수: **14** (T1 래퍼 확정 + T2~13 12파일 흡수 + T14 검증)
- 예상 wave: **3** (wave1=T1 · wave2=T2~13 12병렬 · wave3=T14)
- task 10 초과이나 **PR 쪼개기 아님** — 래퍼 API 확정은 원자적(12파일이 한 계약을 검증). 병렬 흡수 = 한 PR 내 wave.
- TDD 강제: yes (각 흡수 RED=계약 어서션 → GREEN=교체)
- 병렬 dispatch: wave2 12병렬은 **edit-only + controller 순차 커밋** 필수(index.lock 레이스 회피).
- 추가 검증: typecheck · lint · vitest · playwright(qa-engineer, T14)

## 리뷰 결과 (← /bts-review-plan 채움)
