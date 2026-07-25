# /admin/slack 라우트 가드 2→4 봉합 + admin 라우트 가드 행렬 음성 테스트

> slug: admin-slack-beforeload-2-4-requirepasswordchanged
> type: auth
> agent: security-engineer
> primary_bc: identity-access (파일 위치는 `apps/web`)
> 생성: 2026-07-25

## Brief

**사용자 원문.**
`/admin/slack` 라우트의 `beforeLoad` 가드가 `composeGuards(requireAuth, requireSystemAdmin)` 2개뿐이고
`requirePasswordChanged`·`requireMfaEnrolled`가 누락됐다. 다른 admin 라우트 10개는 모두 4-가드
(`requireSystemAdminFull` 또는 동등 `composeGuards`)라 이것만 예외다. 비밀번호 미변경·MFA 미등록 계정이
슬랙 연동 관리 화면에 진입할 수 있는 권한 가드 결함이다. `router.ts`의 `/admin/slack`을 4-가드로 맞추고,
같은 누락이 재발하지 않도록 admin 11개 라우트 × 가드 4종을 행렬로 전수 열거해 검증하는 음성 테스트를
신설한다. 범위는 `apps/web` 단일 BC. ATLAS-4 fixture version 불일치는 이 PR 범위 밖(별도 항목).

**classify 결과.** type=auth · agent=security-engineer · primary_bc=identity-access · task_count=0

**착수 전 실측 (2026-07-25, main `0f892b0a3`).**

`apps/web/src/router.ts` 58 라우트 중 admin 11개 전수 열거 결과.

| 라우트 | 실코드 `beforeLoad` |
|---|---|
| `/admin` | `requireSystemAdminFull` |
| `/admin/workflow-schemes` | `requireSystemAdminFull` |
| `/admin/workflow-schemes/new` | `requireSystemAdminFull` |
| `/admin/workflow-schemes/$schemeKey` | `requireSystemAdminFull` |
| `/admin/audit-logs` | `composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled)` |
| `/admin/global-permissions` | 동일 4-가드 |
| `/admin/notification-policies` | 동일 4-가드 |
| `/admin/users/new` | 동일 4-가드 |
| `/admin/webhooks` | 동일 4-가드 |
| `/admin/webhooks/$id/deliveries` | 동일 4-가드 |
| **`/admin/slack`** | **`composeGuards(requireAuth, requireSystemAdmin)` — 2-가드 (결함)** |

`requireSystemAdminFull` = `composeGuards(requireAuth, requireSystemAdmin, requirePasswordChanged, requireMfaEnrolled)`
(`router.ts:8`). 즉 정상 라우트 10개는 표기만 두 갈래이고 가드 집합은 동일하다.

**★집계 함정 2건 (재발 방지용 기록).**
1. 가드 정본은 라우트 컴포넌트 파일(`routes/admin*.tsx`)이 아니라 **`router.ts` 중앙 집중**이다.
   라우트 파일을 세면 전부 0이 나온다.
2. `routes/admin.audit-logs.tsx`에는 JSDoc 주석 안에 `* beforeLoad: composeGuards(...)` 예시가 있어
   주석을 포함해 grep하면 **가드가 있는 것으로 오탐**된다. 판정은 주석행(`*`, `//`) 제외 후 해야 한다.

**선례.** #299 (`0d738b619` 계열, history 2026-07-20) — `/admin/workflow-schemes` 3라우트에
SYSTEM_ADMIN 가드를 넣고 **뮤테이션으로 검증**했다. 같은 방식을 따른다. 그 PR의 후속 항목에
`adminSlackRoute password/MFA 가드 누락(PRE_EXISTING)`이 이미 등재되어 있었다.

**범위 밖 (명시).**
- ATLAS-4 board/issue version 불일치 (MSW fixture, 보안 무관)
- 백엔드 `WorkflowSchemeController` 읽기 경로(list·get) 권한 — 별도 PR (project-workflow BC)
- `PublicDashboardController` 404 응답 `instance` 토큰 노출 여부 — 별도 PR (notification BC)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
