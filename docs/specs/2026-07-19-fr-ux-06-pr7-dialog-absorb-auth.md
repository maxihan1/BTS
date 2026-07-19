# FR-UX-06 Phase 2 PR7 — auth/identity-access Dialog 흡수 — 스펙

> slug: fr-ux-06-pr7-dialog-absorb-auth
> type: ui (순수 프론트 리팩터)
> 작성: 2026-07-19
> 선행: PR5(#289 issue-tracking 12)·PR6(#290 소규모 BC 9). 정본 = CloneIssueDialog

## 배경

FR-UX-06 개편 로드맵(ADR 2026-07-17)의 "Dialog 복붙 35~37파일 → `ui/dialog` 흡수".
PR5가 shadcn compound 래퍼의 첫 소비자로 API를 확정했고, PR7은 auth/identity-access
계열 **7파일**을 같은 래퍼로 흡수한다. 백엔드/도메인 변경 0.

## 사용자 시나리오 (Given-When-Then)

- **S1 (동작 보존)**. Given 사용자가 7개 Dialog 중 하나를 연다, When 흡수 후 같은 조작
  (검색·선택·폼 입력·제출·취소·Esc·바깥 클릭)을 한다, Then 흡수 전과 **동일하게 동작**한다
  (열림/닫힘/제출/상태 초기화/mutation 발사가 모두 불변).
- **S2 (시각 통일)**. When Dialog가 열린다, Then Jira 시각 계약(옵션 B) 적용 — 스크림,
  우상단 X 닫기 버튼, rounded-lg, bg-popover가 7개 Dialog에 **일관** 적용된다.
- **S3 (a11y 계약)**. When e2e가 `role="dialog"`로 Dialog를 조회한다, Then 래퍼가 같은
  `DialogPrimitive.Content`를 감싸 DOM role 계약이 **불변**이라 147건 e2e가 그대로 통과한다.
- **S4 (auth 무결성)**. Given 재인증(ReauthDialog)·계정 연결(AddAccountDialog) 플로우,
  When 흡수한다, Then 인증 제출·에러 표시·MFA/비밀번호 상호작용 로직이 **한 줄도 안 바뀐다**.

## 기능 요구사항 (FR)

- **FR-1**. 7파일이 `radix-ui`의 `Dialog as DialogPrimitive` 직접 사용을 제거하고
  `@/components/ui/dialog`의 compound 래퍼(`Dialog`/`DialogContent`/`DialogHeader`/
  `DialogTitle`/`DialogFooter`/`DialogClose`, 필요 시 `DialogTrigger`)로 교체한다.
- **FR-2**. 비시각 로직(state·handler·mutation·props·조건 렌더)은 **불변**. `git diff -w`
  기준 로직 diff-0(공백 무시 시 render 껍데기 외 변경 없음).
- **FR-3**. 각 Dialog의 제어 방식을 원본대로 보존한다 (아래 흡수 행렬).

## 흡수 행렬 (파일별 실측 — 추측 아님)

| # | 파일 (L수) | 제어 | Trigger | Cancel/Close 원본 | 흡수 처리 |
|---|---|---|---|---|---|
| 1 | admin/AddMemberDialog (256) | 내부 `useState open` | **있음** | `DialogPrimitive.Close` 래핑 | `<Dialog open onOpenChange>` + `<DialogTrigger asChild>` 유지 · Cancel=`DialogClose` |
| 2 | auth/AddAccountDialog (337) | controlled `open`/`onOpenChange` | 없음 | `DialogPrimitive.Close`("취소") | controlled · Cancel=`DialogClose` · **auth 민감** |
| 3 | auth/ReauthDialog (364) | controlled | 없음 | `DialogPrimitive.Close`("취소") | controlled · Cancel=`DialogClose` · **auth 민감** |
| 4 | field-permissions/FieldPermissionFormDialog (300) | controlled | 없음 | **plain Button `onClick={onOpenChange(false)}`** | **함정2**: Cancel을 `DialogClose`로 감싸지 말 것(이중닫힘). plain Button+onClick 유지 |
| 5 | global-permissions/GlobalPermissionFormDialog (350) | **`isOpen`/`onClose`** | 없음 | **plain Button `onClick={onClose}`** | prop명 매핑 `<Dialog open={isOpen} onOpenChange={(n)=>{if(!n)onClose()}}>` · **함정2** Cancel plain 유지 |
| 6 | ooo/OooModal (271) | controlled (Header 소유) | 없음 | `onOpenChange(false)` | controlled · Cancel 기전 원본 실측 후 보존 |
| 7 | settings/PatTokenModal (107) | `open`(bare, 항상 true)+`onClose` | 없음 | `DialogPrimitive.Close`("닫기") | `<Dialog open onOpenChange={handleOpenChange}>` · null 반환 가드 유지 · Cancel=`DialogClose` |

## 비기능 요구사항 (NFR)

- **NFR-1 (a11y)**. 설명(Description) 없는 Dialog는 `<DialogContent aria-describedby={undefined}>`로
  Radix 경고 억제. Title은 `DialogTitle`로 보존(스크린리더 계약).
- **NFR-2 (auth 보안)**. PatTokenModal의 발급 토큰은 표시 전용 — storage/log 유출 금지(원본 유지,
  흡수가 새 유출 통로를 만들지 않음). Reauth/AddAccount의 비밀번호·MFA 입력값은 흡수 무관.
- **NFR-3 (테스트 무회귀)**. 유닛(각 파일 .test) + 관련 e2e(`role="dialog"` 147건) 전부 green.
- **NFR-4 (LOC 감소)**. Overlay/animate/positioning 문자열 복붙 제거로 순 LOC 감소(부수 효과, 목표 아님).

## 엣지 케이스 (PR5·PR6 반복 함정)

1. **함정1 — `justify-between`**. footer에 `justify-between` 필요 시 `sm:justify-between`
   (래퍼 `DialogFooter` 기본이 `sm:justify-end`라 bare `justify-between`이 못 이김).
2. **함정2 — Cancel 이중호출**. 이미 `onClick`으로 닫는 Cancel(Field·Global)은 `DialogClose`로
   감싸지 않는다. 감싸면 DialogClose 자동닫힘 + onClick 수동닫힘 이중 발화.
3. **함정3 — 별칭 없는 Dialog import**. 해당 없음(7파일 모두 `Dialog as DialogPrimitive` 별칭 사용).
4. **함정4 — test 경로**. flat(`Foo.test.tsx`) vs `__tests__/Foo.test.tsx` 파일별 실측 필수
   (AddAccount·Reauth·AddMember·Ooo = flat / SchemeInUse류 = `__tests__/`). 반복 BLOCKER.
5. **함정5 — grid gap 재간격**. `DialogHeader`/`DialogContent`가 gap을 제공하므로 원본의
   개별 `mb-4`/`mt-6` 마진 정리. 단 폼 내부 중첩 footer의 `mt`는 유지(PR6 선례).
6. **함정6 — aria-describedby**. 설명 없는 Dialog는 `aria-describedby={undefined}` 명시.
7. **prop명 매핑(Global)**. `isOpen`/`onClose` → 래퍼는 `open`/`onOpenChange` 요구. 루트에서 어댑트.
8. **Trigger 보존(AddMember)**. 유일하게 트리거 버튼 소유 → `DialogTrigger asChild`로 보존.

## 제약 조건

- **BC 격리**. identity-access 프론트만. 백엔드/다른 BC 변경 금지.
- **정본 재사용**. `apps/web/src/components/issues/CloneIssueDialog.tsx` 구조 그대로.
  `import { Dialog, DialogClose, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'`.
- **AlertDialog 제외**. auth/AccountLinkCard·admin/SchemeInUseModal은 `AlertDialog` primitive라
  이 PR 범위 아님(ui/alert-dialog 래퍼 부재 → 별도 스코프).
- **automation 9·slack 2 제외**. 각각 PR8/후속.

## 측정 가능한 완료 기준

- [ ] 7파일 모두 `radix-ui` `Dialog as DialogPrimitive` import 제거(grep 0건)
- [ ] `git diff -w`로 각 파일의 비시각 로직 변경 0 확인(render 껍데기 외 diff 없음)
- [ ] `pnpm test` 유닛 전부 green (7파일 관련 .test 포함, 회귀 0)
- [ ] `role="dialog"` e2e 147건 green (DOM 계약 불변 검증)
- [ ] `pnpm lint` + `pnpm typecheck`(tsconfig.app) green
- [ ] auth 3파일(AddAccount·Reauth + AddMember) security-engineer 관점 검토 — 인증 상호작용 무변경 확인
