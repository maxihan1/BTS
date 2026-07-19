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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
