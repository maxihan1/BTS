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
> **wave**: wave1 = T1(래퍼 확정) → wave2 = T2~T13(12파일 병렬, 전부 depends-on[1], files 겹침 0) → wave3 = T14.
> **★병렬 흡수 규칙(리뷰 C2 반영, PR4 교훈 [[worktree-lint-staged-shared-git-stash-collision]]·[[parallel-dispatch-precommit-hook-race]])**:
> 1. 각 sub-agent는 **edit-only**(커밋 절대 금지).
> 2. **커밋 배리어** — controller는 **12개 편집이 전부 반환된 뒤에만** 첫 커밋 시작. 편집 진행 중 커밋하면 lint-staged 부분 stash가 진행 중 파일을 소실시킴.
> 3. controller가 **자기 파일만 stage 후 순차 커밋**(index.lock 회피).
> 4. **검증 병렬도 제한** — `pnpm --filter web test <file>` 를 12 동시 실행 금지, **3~4개 배치**(vitest 워커 폭증·orphan vite [[e2e-orphan-vite-after-worktree-remove]] 회피).
> 5. 리뷰 대조는 `git show HEAD:` ([[parallel-review-mutation-contaminates-peers]]).

### 흡수 공통 절차 (T2~T13 전부 이 템플릿 따름)

- **RED**. 해당 `*.test.tsx`에 흡수 계약 어서션 **추가**(수정 금지, 리뷰 C1) → 현재 radix 직접 import라 실패.
  1. `from 'radix-ui'` Dialog import 부재(흡수 완료 신호) — 소스 문자열/구조 검증.
  2. `role="dialog"` 존속(getByRole('dialog')).
  - ★ **기존 행위 어서션은 절대 수정하지 않는다** — 12파일 test에 시각 어서션(bg-black/40·X부재·rounded-xl)이 **0건**임이 리뷰로 확인됨. 기존 테스트를 통과시키려 손대야 한다면 그건 곧 로직/계약 변화 신호 → 멈추고 controller 보고.
- **GREEN**. `radix-ui` Dialog 직접 구조 → `@/components/ui/dialog` compound 교체.
  - `Root/Portal/Overlay/Content` → `<Dialog open onOpenChange><DialogContent className="{기존 max-w/max-h}">`.
  - `<Title>` → `<DialogHeader><DialogTitle>`. 설명 `<p>` 있으면 `<DialogDescription>` 승격, 없으면 `<DialogContent aria-describedby={undefined}>`로 통일(R3).
  - footer `flex justify-end` → `<DialogFooter>`. `justify-between` 변형은 **`className="sm:justify-between"`**(bare `justify-between`은 래퍼 `sm:justify-end`를 못 이김, 리뷰 B2). `<Close asChild><Button>` 유지.
  - ★ **grid gap-4 재간격 정리(리뷰 C1)** — 래퍼 `DialogContent`=`grid gap-4`, `DialogHeader`=`flex gap-2`다. 기존의 Title `mb-4`·본문 `mb-*`·footer `mt-6` 같은 **개별 margin을 제거**해 이중 간격을 없앤다. 이건 "구조 교체에 수반되는 정당한 편집"이며 로직 변화가 아니다.
  - **비시각 로직(폼·폴링·blob·복사·상태초기화·핸들러·effect)은 diff 0 — render() 반환부만 교체**(R4). 리뷰는 `git show HEAD:`로 핸들러/effect 라인 byte-identical 확인.
- **REFACTOR**. 남은 인라인 클래스 상수·불필요 import 제거.
- **검증**. `pnpm --filter web test <file>.test.tsx`(3~4개 배치) + 흡수 후 `grep "radix-ui" <file>` = 0.

---

### Task 1. 래퍼 계약 테스트 확장 + 애니메이션 문법(R1) 실브라우저 확인

> ★ 리뷰로 정정됨: 래퍼 애니메이션은 **이미 동작한다**(shadcn/tailwind.css의 `@custom-variant data-open`이 `[data-state="open"]`로 컴파일, dropdown/popover/select/tooltip 4종이 이미 소비). **래퍼 코드 수정 없음(no-op)** — `data-[state=open]:`으로 치환 금지(4종과 표기 불일치). `ui/dialog.test.tsx`는 **이미 존재**(117줄) → "확장".

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/ui/dialog.test.tsx`] (dialog.tsx는 무수정 — 문법 이미 정상)
- depends-on: []

**RED(확장)**: 기존 `ui/dialog.test.tsx`(TC-1~3)에 어서션 추가.
- `DialogContent`/`DialogOverlay` className이 `data-open:` / `data-closed:` variant를 **보유**(현 정상 문법)함을 어서션 — 회귀 가드. (기존 X버튼 `name:/close/i` 어서션은 그대로.)
- 주의: jsdom은 애니메이션을 실행하지 않아 "애니메이션이 실제 먹는지"는 유닛으로 못 잡음. 유닛은 클래스 문자열 존속만.

**GREEN**: 래퍼 코드 변경 없음. **실브라우저**(pnpm dev)로 Dialog 열림/닫힘 트랜지션이 실제 재생되는지 육안 1회 확인 → R1 종결.

**REFACTOR**: 없음.

**검증**: `pnpm --filter web test dialog.test.tsx` + `pnpm --filter web typecheck` + 실브라우저 애니메이션 확인.

> T1이 wave1 선행인 이유는 이제 "래퍼 수정"이 아니라 **래퍼 계약 회귀 가드를 12파일 착수 전에 확립**(compound API 계약 고정)이다. depends-on[1]은 유효.

---

### Task 2. issue/AttachmentPreviewModal 흡수

**메타**. agent `frontend-engineer` · files: [`apps/web/src/components/issue/AttachmentPreviewModal.tsx`, `apps/web/src/components/issue/AttachmentPreviewModal.test.tsx`] · depends-on: [1]
**특이사항**: `max-w-4xl`(이미지 프리뷰). blob URL 생명주기 useEffect **로직 무변화**. `aria-describedby={undefined}` 유지. 공통 절차 따름.

### Task 3. issue/ResolutionModal 흡수

**메타**. files: [`apps/web/src/components/issue/ResolutionModal.tsx`, `apps/web/src/components/issue/__tests__/ResolutionModal.test.tsx`] · depends-on: [1]
**특이사항**: test 경로가 `__tests__/`. onCancel/handleOpenChange 패턴(VersionForm과 동형 — 로컬 handleOpenChange를 `onOpenChange`로 전달). **이미 `aria-describedby={MODAL_DESCRIPTION_ID}` + `<p id>` 올바르게 배선**(미처리 아님) → 설명 `<p>`를 `<DialogDescription>`로 승격(2개 승격 대상 중 하나, 나머지는 ReleaseNotes). "취소" 버튼(`name:'취소'`) 닫기 테스트 존속. 공통 절차.

### Task 4. issues/CloneIssueDialog 흡수

**메타**. files: [`apps/web/src/components/issues/CloneIssueDialog.tsx`, `apps/web/src/components/issues/CloneIssueDialog.test.tsx`] · depends-on: [1]
**특이사항**: `max-w-md`. mutation 자기소유(submitError dead-path 금지 KDoc 유지). 상태초기화 useEffect 무변화.

### Task 5. issues/BulkEditDialog 흡수

**메타**. files: [`apps/web/src/components/issues/BulkEditDialog.tsx`, `apps/web/src/components/issues/BulkEditDialog.test.tsx`] · depends-on: [1]
**특이사항**: 공통 절차. 폼 로직 무변화.

### Task 6. issues/BulkOperationResultDialog 흡수

**메타**. files: [`apps/web/src/components/issues/BulkOperationResultDialog.tsx`, `apps/web/src/components/issues/BulkOperationResultDialog.test.tsx`] · depends-on: [1]
**특이사항**: `max-w-lg`. 폴링 로직·aria-live 무변화. footer 닫기가 raw `<Close className=...>`(Button 아님) → `<DialogClose asChild><Button variant="outline">`로 정규화(시각 통일 취지). aria-describedby 없음 → undefined 통일. test에 `aria-labelledby||aria-label` 존재 검사 있음 → DialogTitle이 aria-labelledby 유지하므로 통과.

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

**메타**. files: [`apps/web/src/components/version/ReleaseNotesDialog.tsx`, `apps/web/src/components/version/ReleaseNotesDialog.test.tsx`] · depends-on: [1]
**특이사항**: `max-w-2xl max-h-[80vh] flex flex-col`(스크롤). 설명 `<p>` → `<DialogDescription>` 승격.
footer `justify-between`(메타+버튼) → **`DialogFooter className="sm:justify-between"`**(bare `justify-between`은 래퍼 `sm:justify-end`를 못 이김, 리뷰 B2). 클립보드 복사 로직 무변화.
`OVERLAY_CLASS`/`CONTENT_CLASS` 상수 제거(래퍼가 담당).

### Task 12. custom-fields/CustomFieldFormDialog 흡수

**메타**. files: [`apps/web/src/components/custom-fields/CustomFieldFormDialog.tsx`, `apps/web/src/components/custom-fields/__tests__/CustomFieldFormDialog.test.tsx`] · depends-on: [1]
**특이사항**: 415줄(최대). test 경로 `__tests__/`. JSONB 커스텀필드 폼 로직 무변화. 공통 절차 신중 적용.

### Task 13. issue-templates/IssueTemplateFormDialog 흡수

**메타**. files: [`apps/web/src/components/issue-templates/IssueTemplateFormDialog.tsx`, `apps/web/src/components/issue-templates/__tests__/IssueTemplateFormDialog.test.tsx`] · depends-on: [1]
**특이사항**: 423줄, footer 2개소, test 경로 `__tests__/`. 템플릿 폼 로직 무변화.

---

### Task 14. 전수 검증 + 시각 QA (마무리)

**메타**. agent `qa-engineer` · files: [] (검증만, 코드 수정 없음) · depends-on: [2,3,4,5,6,7,8,9,10,11,12,13]
**내용**:
- `grep -rn "from 'radix-ui'" <12파일>` = 0 전수 확인(흡수 완결 증거).
- `pnpm --filter web typecheck && lint && test` 전체 green.
- 관련 E2E(이슈 상세/일괄/버전/컴포넌트) green — `role="dialog"` 147 계약 무손상.
- 시각 통일 육안 확인(스크림 /50·X버튼·rounded-lg·bg-popover) — 실브라우저 대표 3화면 스크린샷.
- **내부 간격(gap) 회귀 확인(리뷰 C1)** — grid gap-4 도입 후 잔존 margin 이중간격 없는지, 헤더/본문/footer 세로 리듬이 흡수 전과 어색하지 않은지 육안. 특히 큰 폼(Move·CustomField·IssueTemplate).

## Plan 메타

- task 수: **14** (T1 래퍼 확정 + T2~13 12파일 흡수 + T14 검증)
- 예상 wave: **3** (wave1=T1 · wave2=T2~13 12병렬 · wave3=T14)
- task 10 초과이나 **PR 쪼개기 아님** — 래퍼 API 확정은 원자적(12파일이 한 계약을 검증). 병렬 흡수 = 한 PR 내 wave.
- TDD 강제: yes (각 흡수 RED=계약 어서션 → GREEN=교체)
- 병렬 dispatch: wave2 12병렬은 **edit-only + controller 순차 커밋** 필수(index.lock 레이스 회피).
- 추가 검증: typecheck · lint · vitest · playwright(qa-engineer, T14)

## 리뷰 결과

### 엔지니어링 초점 경량 리뷰 (2026-07-19, sub-agent 2종 병렬, 실측 기반)

리뷰 방식 = Maxi 선택(디자인 목업 대신 실행 리스크 어드버서리얼). frontend-engineer + code-reviewer 병렬, 코드 무수정.

**BLOCKER 3건 (전부 plan 수정으로 해소 — 아래 반영 완료).**
- **B1. R1 진단 오류.** 래퍼 `data-open:`/`data-closed:` variant는 **이미 정상 동작**. `apps/web/src/index.css:4`의 `@import "shadcn/tailwind.css"`가 `@custom-variant data-open { &:where([data-state="open"]) ... }`를 정의(실제 tailwind v4 엔진 컴파일 확인). dropdown-menu·popover·select·tooltip 4종이 이미 프로덕션에서 이 컨벤션 소비. 내 spec R1(index.css에 미정의) 진단은 `@import` 체인 미확인 실수. → **T1은 래퍼 수정 no-op, 테스트 확장만.** `data-[state=open]:`으로 치환하면 4종과 표기 불일치 생기니 **치환 금지**.
- **B2. ReleaseNotes footer.** Task 11의 `className="justify-between"`은 래퍼 `DialogFooter` 기본 `sm:justify-end`와 tailwind-merge상 충돌 그룹이 안 묶여 **둘 다 생존 → 데스크톱(sm+)서 justify-end가 이김**(space-between 의도 무효). `twMerge` 직접 실행 검증. → **`sm:justify-between` 지정**으로 정정.
- **B3. files 경로 선언 오류.** Task 12·13 test는 실제 `__tests__/` 하위인데 flat로 선언. Task 6·11 test는 실재하는데 미선언("있으면"). wave 규약(files 선언 파일만 수정) 저해. → 4개 Task files 정정.

**CONCERN 4건 (반영 완료).**
- **C1. grid gap-4 재간격(가장 실질).** 래퍼 `DialogContent`=`grid gap-4`, `DialogHeader`=`flex gap-2`인데 기존 12파일은 block 흐름 + 개별 margin(Title `mb-4`·footer `mt-6` 등). gap과 잔존 margin이 **이중 간격**. 흡수는 margin 정리라는 구조 편집을 동반 → 흡수 공통 절차·T14 QA·spec 시각 델타에 명시. **강제 기전**: 기존 행위 어서션 **수정 금지(추가만)** + 리뷰는 `git show HEAD:`로 render()부 한정 확인.
- **C2. 병렬 커밋 배리어.** edit-only+순차커밋은 index.lock은 막으나 lint-staged 부분 stash 레이스([[worktree-lint-staged-shared-git-stash-collision]])는 못 막음. → **"12편집 전부 완료(배리어) 후에만 첫 커밋 시작"** 명문화. 검증은 **3~4개 배치**(12 동시 vitest 워커 폭증·orphan vite 위험).
- **C3. Task 1은 "확장".** `ui/dialog.test.tsx` **이미 존재**(117줄, TC-1~3, X를 `name:/close/i`로 이미 의존). 신규분은 애니메이션 문법 어서션뿐. R1은 jsdom이 애니메이션 미실행이라 테스트로 못 잡음 → **실브라우저 확인**.
- **C4. Task 3 ResolutionModal.** onCancel/handleOpenChange 패턴(VersionForm과 동형) 특이사항 누락. 또 이미 `aria-describedby={MODAL_DESCRIPTION_ID}` 올바르게 배선(미처리 아님). spec R3 카운트 정정(5 undefined + 6 미기재 + 1 명시연결).

**OK (문제없음).**
- 시각 변화로 깨질 기존 테스트 **0건**(12파일 test 전수 grep — bg-black/40·rounded-xl·X부재·overlay 클래스 어서션 없음, 전부 role/label/text 기반). plan 무회귀 주장 성립.
- role="dialog" E2E 안전(실측 164회). X버튼 영어 `Close` vs 닫기 한국어 `취소`/`닫기` → strict mode 무충돌. 대상 dialog 내 이름없는 `getByRole('button')`·버튼개수 어서션 0.
- 놓친 파일 0(issue-tracking 12 + board 2 전수 확인). compound API 커버리지 완전(onEscapeKeyDown 등 특수요구 0).
- board/2 제외 = BC 격리상 타당. **이월 주의**: resolution 다이얼로그 2개(issue/ResolutionModal + board/ResolutionPickerModal) → PR6/7에 ResolutionPickerModal 흡수 명시.

**BLOCKER 처리**: 전부 근본 설계 결함이 아니라 문구·경로·클래스명 오류 → plan 수정으로 해소, 재리뷰 불요. 게이트1 진입.
