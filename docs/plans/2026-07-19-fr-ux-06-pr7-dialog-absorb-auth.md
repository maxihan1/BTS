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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
