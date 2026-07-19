# FR-UX-06 Phase 2 PR7 — auth/identity-access 계열 Dialog 흡수

> slug: fr-ux-06-pr7-dialog-absorb-auth
> type: ui
> agent: frontend-engineer
> primary_bc: identity-access
> 생성: 2026-07-19

## Brief

FR-UX-06 UI/UX 개편의 Dialog 흡수 4분할 중 PR7. auth/identity-access 계열에서
`radix-ui` 메타패키지의 `Dialog as DialogPrimitive`를 직접 소비하는 7파일을
공용 `ui/dialog` compound 래퍼(PR2 신설, PR5에서 API 확정)로 흡수한다.

**실측 대상 7파일** (grep `Dialog as DialogPrimitive`, .test 제외, ui/ 제외):
1. components/admin/AddMemberDialog.tsx
2. components/auth/AddAccountDialog.tsx
3. components/auth/ReauthDialog.tsx
4. components/field-permissions/FieldPermissionFormDialog.tsx
5. components/global-permissions/GlobalPermissionFormDialog.tsx
6. components/ooo/OooModal.tsx
7. components/settings/PatTokenModal.tsx

**제외 (별도 primitive/스코프)**:
- AlertDialog 2파일 — auth/AccountLinkCard.tsx, admin/SchemeInUseModal.tsx
  (ui/alert-dialog 래퍼 부재 → PR2 후속 스코프. 흡수 대상 아님)
- automation 9파일 · slack 2파일 — 각각 PR8/후속 (같은 Dialog 패턴이나 별도 PR)

**정본 패턴** (PR5·PR6 확립, `apps/web/src/components/issues/CloneIssueDialog.tsx`):
- controlled: `open`/`onOpenChange`, Trigger 미사용
- `max-w`/`max-h`만 `DialogContent className`으로 override
- `DialogHeader`/`DialogTitle`/(`DialogDescription`) 구조, X 닫기 기본 on
- `DialogFooter`/`DialogClose`
- render() 껍데기만 교체, **비시각 로직 diff-0** (`git diff -w`로 검증)
- 시각 계약 = 옵션 B Jira 통일 (스크림 /50·우상단 X·rounded-lg·bg-popover)

**함정 6종** (PR5·PR6 반복):
1. `justify-between`은 `sm:justify-between` (bare 못 이김)
2. Cancel `onClick` 직접호출은 DialogClose 미포장 (이중호출 주의)
3. 별칭 없는 `import { Dialog }`(Dialog.Root) → 전면 교체 + titleId 제거 가능
4. test 경로 flat vs `__tests__/` 실측 필수 (반복 BLOCKER)
5. grid gap-4 재간격 (개별 margin 정리, 폼 내부 중첩 footer는 mt 유지)
6. 설명 없으면 `aria-describedby={undefined}`

**★민감도**: auth 인증 UI(AddAccountDialog/ReauthDialog/AccountLinkCard 계열)
포함 → security-engineer 가이드 필요. render 껍데기 교체가 인증 플로우
(재인증·계정 연결·MFA)의 상호작용/폼 제출 로직을 건드리지 않는지 검증.

## 도메인 정리

- **BC**: identity-access (파일 물리 위치만. 백엔드 도메인 모델·엔티티 변경 0)
- **영향 엔티티**: 없음 — 프론트 컴포넌트 `render()` 껍데기 교체만. 도메인 로직/API/DTO 무변경
- **새 용어**: 없음 — Dialog / compound 래퍼는 UI 어휘, DDD 유비쿼터스 언어(glossary) 대상 아님 (glossary grep 0건 확인)
- **기존 결정 충돌**: 없음. FR-UX-06 개편 ADR([docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md](../decisions/2026-07-17-fr-ux-06-jira-redesign.md))의 "Dialog 복붙 35~37파일 → ui/dialog 흡수" 로드맵에 정합
- **★계약 (impl 필수 준수)**: ADR §동반 계약 — `role="dialog"` **e2e 147건**이 Radix `DialogPrimitive.Content`의 role에 의존. compound 래퍼가 **같은 primitive를 감싸므로 DOM 계약 불변** → 흡수 안전. 단, 흡수 시 `DialogContent`가 primitive role을 그대로 유지하는지(래퍼가 role 제거/override 안 함) 확인
- **관련 ADR**: [docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md](../decisions/2026-07-17-fr-ux-06-jira-redesign.md) (기존, 이 PR로 변경 없음)
- **grill-with-docs**: 생략 — 도메인 모델 변경 0(순수 UI 리팩터). PR5/PR6 동일 흡수에서 도메인 영향 없음 확인됨. CLAUDE.md §2 단순성 원칙

## 스펙

전체 스펙. [docs/specs/2026-07-19-fr-ux-06-pr7-dialog-absorb-auth.md](../specs/2026-07-19-fr-ux-06-pr7-dialog-absorb-auth.md)

핵심 3줄.
- 7파일이 `radix-ui`의 `Dialog as DialogPrimitive` 직접 사용을 제거하고 `ui/dialog` compound 래퍼로 교체(정본 CloneIssueDialog).
- 비시각 로직 불변(`git diff -w` diff-0), `role="dialog"` DOM 계약 불변(래퍼가 같은 primitive 감쌈).
- 파일별 편차(Trigger 1·prop명 매핑 1·Cancel 이중호출 함정 2·bare open 1)를 실측 흡수 행렬로 명시.

## Brainstorming Check

✅ 통과 (1회, 실측 기반). DialogTrigger export 확인(래퍼 지원)·OooModal 저장닫힘=함정아님·7파일 독립(1-wave). plan 이월 결정 1건: auth 파일 agent 배정.

## Plan

> **TDD (refactor-under-green)**. 순수 render-shell 흡수라 새 behavior 없음 → 새 실패
> 테스트 작성 대신 **기존 테스트/ e2e가 회귀 가드**. RED=흡수 전 baseline green 확인,
> GREEN=흡수 후 여전히 green + `git diff -w`로 비시각 로직 diff-0. 각 task는 controller가
> `git show HEAD:<파일>`로 원본 대조([[parallel-review-mutation-contaminates-peers]] 방어).
>
> **agent 배정 (게이트 결정 대상)**. 기본안 = 7파일 전부 frontend-engineer(순수 render
> 리팩터), auth 3파일(AddAccount·Reauth·AddMember)은 codereview에서 security-engineer 검토.
> 대안 = auth 3파일을 security-engineer가 직접 구현. 게이트1에서 Maxi 확정.

### Task 1. admin/AddMemberDialog 흡수

**메타**.
- agent: `frontend-engineer` (auth 인접 — codereview에서 security 검토)
- files: [`apps/web/src/components/admin/AddMemberDialog.tsx`, `apps/web/src/components/admin/AddMemberDialog.test.tsx`]
- depends-on: []

**RED(baseline)**: `pnpm test AddMemberDialog` green 확인 (흡수 전).
**GREEN**: 정본 흡수. **Trigger 유지** — `<Dialog open onOpenChange={handleOpenChange}>` + `<DialogTrigger asChild><Button size="sm">…</Button></DialogTrigger>` + `<DialogContent className="max-w-md" aria-describedby={undefined}>` + `<DialogHeader><DialogTitle>…</DialogTitle></DialogHeader>` + body 불변 + `<DialogFooter>`(Cancel=`<DialogClose asChild><Button variant="outline">…</DialogClose>` + confirm Button 불변). Overlay/Portal/positioning/animate 문자열 제거.
**REFACTOR**: 원본 `mb-4`(Title)·`mt-6`(footer) 마진 정리(래퍼 gap이 제공). 폼 내부 `space-y-3` 유지.
**검증**: `pnpm test AddMemberDialog` green + `git diff -w apps/web/src/components/admin/AddMemberDialog.tsx` = render 껍데기 외 diff 0.

### Task 2. auth/AddAccountDialog 흡수 (auth 민감)

**메타**.
- agent: `frontend-engineer` (auth — codereview에서 security 검토 / 대안: security 구현)
- files: [`apps/web/src/components/auth/AddAccountDialog.tsx`, `apps/web/src/components/auth/AddAccountDialog.test.tsx`]
- depends-on: []

**RED(baseline)**: `pnpm test AddAccountDialog` green 확인.
**GREEN**: controlled 흡수. `<Dialog open={open} onOpenChange={handleOpenChange}>` + `<DialogContent aria-describedby={undefined}>`(설명 없음) + Header/Title + body(계정 연결 폼·mutation·에러표시 **불변**) + Footer(Cancel=`DialogClose`). **인증 제출·에러 상태 로직 한 줄도 불변**.
**REFACTOR**: 마진 정리(함정5).
**검증**: `pnpm test AddAccountDialog` green + `git diff -w` diff-0. security 관점 — 계정 연결 상호작용 무변경.

### Task 3. auth/ReauthDialog 흡수 (auth 민감)

**메타**.
- agent: `frontend-engineer` (auth — codereview에서 security 검토 / 대안: security 구현)
- files: [`apps/web/src/components/auth/ReauthDialog.tsx`, `apps/web/src/components/auth/ReauthDialog.test.tsx`]
- depends-on: []

**RED(baseline)**: `pnpm test ReauthDialog` green 확인.
**GREEN**: controlled 흡수. Dialog/Content(`aria-describedby={undefined}`)/Header/Title/Footer(Cancel=`DialogClose`). **재인증(비밀번호/MFA) 제출·검증 로직 불변**.
**REFACTOR**: 마진 정리.
**검증**: `pnpm test ReauthDialog` green + `git diff -w` diff-0. security 관점 — 재인증 플로우 무변경.

### Task 4. field-permissions/FieldPermissionFormDialog 흡수 (유닛 없음)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/field-permissions/FieldPermissionFormDialog.tsx`]
- depends-on: []

**RED(baseline)**: 유닛 테스트 없음 → e2e `field-permissions.spec.ts` 참조. 흡수 전 `git show HEAD:…` 원본 확보.
**GREEN**: controlled 흡수. **함정2** — Cancel은 이미 `onClick={() => onOpenChange(false)}`(L241)이므로 **`DialogClose`로 감싸지 않고 plain Button+onClick 유지**(이중닫힘 방지). Dialog/Content/Header/Title/Footer(plain Cancel).
**REFACTOR**: 마진 정리.
**검증**: `git diff -w` diff-0 + `pnpm test:e2e field-permissions`(로컬 가능 시) 또는 CI e2e green.

### Task 5. global-permissions/GlobalPermissionFormDialog 흡수 (prop명 매핑 + __tests__/ 경로)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/global-permissions/GlobalPermissionFormDialog.tsx`, `apps/web/src/components/global-permissions/__tests__/GlobalPermissionFormDialog.test.tsx`]
- depends-on: []

**RED(baseline)**: `pnpm test GlobalPermissionFormDialog` green 확인 (**함정4** — `__tests__/` 경로).
**GREEN**: **prop명 매핑** — 원본 `isOpen`/`onClose`. `<Dialog open={isOpen} onOpenChange={(next) => { if (!next) onClose() }}>`. **함정2** — Cancel `onClick={onClose}`(L291)은 plain Button 유지(감싸지 않음). Content/Header/Title/Footer.
**REFACTOR**: 마진 정리.
**검증**: `pnpm test GlobalPermissionFormDialog` green + `git diff -w` diff-0.

### Task 6. ooo/OooModal 흡수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/ooo/OooModal.tsx`, `apps/web/src/components/ooo/OooModal.test.tsx`]
- depends-on: []

**RED(baseline)**: `pnpm test OooModal` green 확인.
**GREEN**: controlled 흡수(Header 소유 선례). `<Dialog open={open} onOpenChange={onOpenChange}>` + Content/Header/Title. **실측 확정** — footer는 Cancel 없음, `초기화(handleClear)`+`저장(handleSave)` 두 액션 버튼이 `flex justify-between`(L256). 닫기는 X/Esc/바깥클릭만 → **함정2 해당 없음**. **함정1 적용** — `<DialogFooter className="sm:justify-between">`(bare `justify-between`이 래퍼 기본 `sm:justify-end`를 못 이김). 저장 성공 닫힘(`handleSaved`→`onOpenChange(false)`) 로직 불변.
**REFACTOR**: 마진 정리(`pt-2` 등, 래퍼 gap 반영).
**검증**: `pnpm test OooModal` green + `git diff -w` diff-0.

### Task 7. settings/PatTokenModal 흡수 (유닛 없음 + bare open)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/settings/PatTokenModal.tsx`]
- depends-on: []

**RED(baseline)**: 유닛 없음 → e2e `pat.spec.ts` 참조. `git show HEAD:…` 원본 확보.
**GREEN**: `<Dialog open onOpenChange={handleOpenChange}>`(bare open — 항상 true). **null 반환 가드(`if (issued === null) return null`) 유지**. Content/Header/Title/Footer(Cancel=`DialogClose` "닫기"). **NFR-2** — 발급 토큰 표시 전용, storage/log 미유출 원본 유지.
**REFACTOR**: 마진 정리.
**검증**: `git diff -w` diff-0 + `pnpm test:e2e pat`(가능 시) 또는 CI e2e green.

## Plan 메타

- task 수: 7 (각 파일 1 task, 전부 `depends-on: []` + files 교집합 ∅)
- 병렬 wave: **1 wave / 7-병렬** (의존성 그래프 longest path=1, 최선 효율 케이스)
- TDD 강제: refactor-under-green (기존 테스트/e2e가 회귀 가드 + `git diff -w` diff-0)
- 예상 시간: 1-wave 병렬 dispatch로 약 8~12분
- 추가 검증: `pnpm lint` + `pnpm typecheck`(tsconfig.app) + `pnpm test` 전체 + e2e(role="dialog" 147건 무회귀). qa-engineer는 auth/permissions e2e 무회귀 감사.
- ★유닛 없는 2파일(Field·PatToken)은 e2e + `git diff -w` diff-0 이중 guard 필수([[ui-pr-defer-e2e-regression-latent]])

## 리뷰 결과 (← /bts-review-plan 채움)

## 리뷰 결과

### controller 경량 어드버서리얼 self-review (2026-07-19, type=ui)

type=ui이나 시각 계약은 PR5에서 확정된 옵션 B 재사용 → 새 디자인 결정 0.
PR6 선례(정본 재사용 PR = controller self-review)대로 **실행 리스크 어드버서리얼**.

**발견 → 해소 2건**.
- **R1 (OooModal Cancel 미확정)**. Task 6이 Cancel 기전을 impl로 미뤘음 → 실측 결과
  footer에 Cancel 없음(초기화+저장 2버튼, `justify-between`). **함정2 아님, 함정1 적용**
  (`sm:justify-between`). Task 6 정정 완료.
- **R2 (테스트 취약 셀렉터 우려)**. 5개 유닛 테스트가 흡수로 제거되는 구조 클래스를
  assert하면 깨질 위험 → 실측 결과 **전부 `getByRole('dialog')` 등 role/text 기반(견고)**.
  `role="dialog"` 보존되므로 **테스트 재작성 불필요**. refactor-under-green 가정 검증됨.

**검증된 강점**.
- ✅ `DialogTrigger` export 확인(AddMember Trigger 보존)
- ✅ 함정2(Cancel 이중호출) 대상 정확 — Field(L241)·Global(L291) plain 유지, 나머지 무해
- ✅ prop명 매핑(Global `isOpen`/`onClose`) 명시
- ✅ 7파일 독립 → 1-wave, files 교집합 ∅
- ✅ typecheck는 tsconfig.app 기준([[ci-typecheck-tsconfig-app-vs-local]])

**주의(BLOCKER 아님)**.
- ⚠️ 유닛 없는 2파일(Field·PatToken) — `git diff -w` diff-0가 1차 guard(로직 불변→동작 불변),
  e2e(`field-permissions.spec.ts`·`pat.spec.ts`)는 2차. impl에서 e2e 실제 실행/미스킵 확인 필수.
- ⚠️ "role=dialog e2e 147건"은 앱 전체 수(ADR) — PR7 기준은 **무회귀**(수치 자체는 다른 PR로 변동 가능).

**BLOCKER**: 없음.

**게이트 이월 결정 1건**. auth 3파일(AddAccount·Reauth·AddMember) agent 배정.

### 게이트 1 (2026-07-19) — Maxi 승인 ✅
- 승인 → 구현 진행.
- **agent 배정 확정**: 7파일 전부 `frontend-engineer` 구현. auth 3파일(AddAccount·Reauth·AddMember)은 `/bts-codereview`에서 security-engineer 검토(인증 상호작용 무변경).
