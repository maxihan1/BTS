# FR-UX-06 PR5 — issue-tracking Dialog 흡수 (래퍼 API 확정) — 스펙

> slug: fr-ux-06-pr5-dialog-absorb · type: ui · agent: frontend-engineer
> BC: issue-tracking (프론트 계층) · 백엔드 변경 0
> 상위: FR-UX-06 (Jira Cloud 방식 UI/UX 개편), ADR `docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md` D1~D8
> 작성: 2026-07-19

## 배경 · 목적

issue-tracking BC의 12개 Dialog가 각자 `radix-ui`의 `Dialog`를 직접 import하고,
`Portal > Overlay(스크림) > Content(+X닫기 없음)` 구조와 Overlay/Content Tailwind
클래스 문자열을 복붙한다. 이를 PR2(#286)가 만든 공용 `components/ui/dialog.tsx`
compound 래퍼로 흡수한다.

- **핵심 = 래퍼 API 확정.** Dialog 4분할(PR5 → PR6∥PR7 → PR8) 중 첫 PR. 현재 래퍼
  소비처가 0이므로 PR5가 **첫 소비자**로서 compound API의 실사용 커버리지를 검증·확정한다.
  여기서 정한 사용 계약이 PR6~8의 템플릿이 된다.
- **시각 방향 = Jira 통일(Maxi 결정, 2026-07-19, 옵션 B).** 래퍼를 그대로 소비한다 →
  12개 Dialog에 스크림 `/50`·우상단 X 닫기·`rounded-lg`·`bg-popover`·`ring`이 동시
  적용되는 **의도된 시각 통일**. 순수 리팩터가 아니라 흡수+개편을 겸한다.

## 확정 래퍼 API 계약 (★ 이 PR의 산출물)

래퍼는 이미 shadcn식 compound components 10종을 export한다
(`Dialog · DialogTrigger · DialogPortal · DialogOverlay · DialogContent · DialogClose ·
DialogHeader · DialogFooter · DialogTitle · DialogDescription`). PR5가 확정하는 **사용 계약**.

1. **controlled 패턴.** `<Dialog open={open} onOpenChange={onOpenChange}>` — Trigger 미사용
   (부모가 open 상태 소유). 12파일 전부 이 패턴이라 논쟁 없음.
   - 예외: `VersionFormDialog`는 현재 `onClose` prop 사용 → 흡수 시 `onOpenChange`로 통일하되,
     **부모 호출부 시그니처를 깨지 않도록** 컴포넌트 내부에서 `onOpenChange={(o)=>{ if(!o) onClose() }}`
     어댑팅(외부 prop은 유지). 파일별 실측으로 확정.
2. **max-width = 사용처가 `DialogContent`의 `className`으로 override.** 래퍼 기본 `max-w-lg`.
   실측 변형: `max-w-md`(Clone) · `max-w-2xl`(ReleaseNotes) · `max-w-4xl`(AttachmentPreview) 등.
   → 래퍼는 `cn()` 병합이므로 `className="max-w-4xl"`가 기본을 덮어씀. **max-width prop 신설 불필요.**
3. **스크롤 변형도 className으로.** `ReleaseNotes`의 `max-h-[80vh] flex flex-col` 등 →
   `<DialogContent className="max-w-2xl max-h-[80vh] flex flex-col">`.
4. **헤더 = `DialogHeader > DialogTitle (+ DialogDescription)`.** 기존은 `<Title>` 단독 +
   설명은 평범한 `<p>`. 흡수 시 설명 텍스트가 있으면 `DialogDescription`으로 승격(ReleaseNotes),
   없으면 생략.
5. **X 닫기 버튼 = 래퍼 `DialogContent`가 강제 렌더(기본 on).** 12파일 전부 신규로 우상단 X가
   생긴다. footer의 "닫기"/"취소"와 공존. **E2E 안전**(테스트는 `name:'취소'/'닫기'` 한국어로 찾고
   X는 `sr-only "Close"` 영어라 strict mode 비충돌).
6. **footer = `DialogFooter`.** 기존 `flex justify-end gap-2 mt-6` → `DialogFooter`로 대체
   (래퍼가 `flex-col-reverse gap-2 sm:flex-row sm:justify-end` 제공). `justify-between` 변형
   (ReleaseNotes의 메타+버튼)은 `className`으로 조정.
7. **닫기 버튼 = `DialogClose asChild > Button`.** 기존 패턴 그대로 유지 가능.

## 사용자 시나리오 (Given-When-Then)

- **S1 (열기·닫기 불변).** Given 이슈 상세에서 "클론" 클릭 · When CloneIssueDialog 오픈 ·
  Then `role="dialog"` 렌더, ESC·Overlay 클릭·"취소"·**우상단 X(신규)** 모두 닫기 동작.
- **S2 (시각 통일).** Given 임의 issue-tracking Dialog · When 오픈 · Then 스크림 `bg-black/50`,
  모서리 `rounded-lg`, 배경 `bg-popover`, `ring` — Jira Cloud 시각. (기존 `/40`·`rounded-xl`·
  `bg-background` 대비 의도된 변화.)
- **S3 (기능 무변화).** Given 각 Dialog의 폼 제출·폴링·프리뷰·복사 로직 · When 흡수 후 · Then
  mutation·상태초기화·blob 생명주기 등 **비시각 동작은 100% 동일**. 흡수는 구조 교체이지 로직 변경이 아니다.

## 기능 요구사항 (FR)

- **FR1.** issue-tracking 12파일이 `radix-ui` Dialog 직접 import를 제거하고 `@/components/ui/dialog`
  compound 래퍼를 소비한다.
- **FR2.** 각 파일의 Overlay/Content/X닫기 클래스 복붙이 사라진다(래퍼가 단일 출처).
- **FR3.** 시각이 Jira 통일값(스크림 /50·X·rounded-lg·bg-popover·ring)으로 바뀐다.
- **FR4.** 비시각 동작(폼·폴링·프리뷰·복사·상태초기화)은 무변화. 기존 유닛 테스트 그대로 통과.

## 비기능 요구사항 (NFR)

- **NFR1 (계약 불변).** `role="dialog"` 보존 — 래퍼가 `DialogPrimitive.Content`를 감싸므로 자동.
  E2E 147건(Radix Content role 의존) 무회귀.
- **NFR2 (접근성).** `DialogTitle` 필수 유지. Description 부재 파일은 래퍼 계약에 맞춰
  aria-describedby 처리(§엣지 R3).
- **NFR3 (테스트).** 기존 12파일 유닛 테스트 + 관련 E2E 무수정 통과(로직 무변화이므로). 시각 변화는
  스냅샷 없으면 수동 QA 대상으로 기록.
- **NFR4 (BC 격리·완제품).** apps/web 프론트만. 백엔드 0. PoC 코드 금지.

## IN-SCOPE (12파일 확정)

| 디렉토리 | 파일 |
|---|---|
| `issue/` | AttachmentPreviewModal · ResolutionModal |
| `issues/` | CloneIssueDialog · BulkEditDialog · BulkOperationResultDialog · MoveIssueDialog · BulkTransitionDialog |
| `component/` | ComponentFormDialog |
| `version/` | VersionFormDialog · ReleaseNotesDialog |
| `custom-fields/` | CustomFieldFormDialog |
| `issue-templates/` | IssueTemplateFormDialog |

- 전부 일반 Dialog(AlertDialog 혼입 0, 실측 확인) → 래퍼로 완전 커버.

## OUT-OF-SCOPE

- **board/ 2파일**(ResolutionPickerModal · SaveQuickFilterDialog) — **PR5 제외**. 디렉토리 응집성
  원칙: board 화면 소속은 board 흡수 PR(PR6/7)에서 처리. resolution이 issue-tracking 개념이나
  파일 위치가 board라 경계 혼선 방지 위해 이연. (게이트1 검토 대상.)
- **AlertDialog 흡수**(auth/AccountLinkCard · admin/SchemeInUseModal) — PR2에 `ui/alert-dialog`
  래퍼 부재. 별도 스코프.
- **스크림 `--overlay` 토큰화** — 이번엔 래퍼의 리터럴 `bg-black/50` 그대로. 토큰화는 미래 PR.
- **PR8의 ESLint 락**(radix Dialog 직접 import 금지) — 체인 마무리 PR 소관.

## 엣지 케이스 / 리스크 (self-adversarial 발견 4건)

- **R1 (해소됨 — 리뷰 실측으로 진단 정정).** 래퍼 `data-open:`/`data-closed:` variant는 **이미 정상 동작**한다.
  `index.css:4`의 `@import "shadcn/tailwind.css"`가 `@custom-variant data-open { &:where([data-state="open"]) ... }`를
  정의하고(tailwind v4 엔진 컴파일 확인), dropdown-menu·popover·select·tooltip 4종이 이미 프로덕션에서 소비 중.
  최초 진단(index.css에 미정의)은 `@import` 체인을 안 본 실수. → **래퍼 수정 no-op**(`data-[state=open]:` 치환 금지,
  4종과 표기 불일치). T1은 계약 회귀 가드 테스트 확장 + 실브라우저 애니메이션 육안 확인만.
- **R2 (onClose 인터페이스 변형).** `VersionFormDialog`는 `onClose` prop. §API계약 1의 어댑팅으로
  외부 시그니처 보존.
- **R3 (Description/aria-describedby, 카운트 정정).** 실측 분포 = `={undefined}` 명시 5파일 +
  미기재 6파일 + **명시 id 연결 1파일**(ResolutionModal, 이미 올바르게 배선). 래퍼 `DialogContent`는
  Description 강제 안 함 → 설명 있는 2파일(ResolutionModal·ReleaseNotes)은 `<DialogDescription>` 승격,
  나머지는 `<DialogContent aria-describedby={undefined}>`로 통일.
- **R4 (시각 회귀 가시성).** 12파일 시각 동시 변화라 "흡수 탓 회귀"와 "시각 통일 탓 변화"가 섞인다.
  완화: 각 파일 흡수는 **로직 diff 0(구조만 교체)**임을 커밋 단위로 보장 → 시각 외 변화가 diff에
  나오면 곧 버그. 스크린샷 QA는 게이트2 또는 후속.

## 측정 가능한 완료 기준

1. 12파일에서 `from 'radix-ui'` Dialog import 0건(grep). 전부 `@/components/ui/dialog` 소비.
2. `pnpm typecheck` · `pnpm lint` · `pnpm test`(기존 유닛 무회귀) green.
3. 관련 E2E green(로직 무변화). `role="dialog"` 계약 무손상.
4. 래퍼 애니메이션 문법 R1 판정 완료(먹으면 유지, 아니면 래퍼 수정 커밋).
5. 시각 통일값 육안/스냅샷 확인 — 스크림 /50·X버튼·rounded-lg·bg-popover.

## Brainstorming Check

✅ self-adversarial sanity 완료 — gap 4건(R1 애니메이션 문법·R2 onClose·R3 aria-describedby·
R4 시각 회귀 가시성)을 리스크로 반영. AlertDialog 혼입·board 경계·max-width 파라미터화는 실측으로 해소.
