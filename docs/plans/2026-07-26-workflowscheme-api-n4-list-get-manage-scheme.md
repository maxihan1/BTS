# WorkflowScheme 읽기 API 권한 게이트 봉합 (N4)

> slug: workflowscheme-api-n4-list-get-manage-scheme
> type: api (classify 판정) — **브랜치/PR 접두사는 `auth`** (실질이 권한 가드, N1~N3 형제 PR #311·#312 선례)
> agent: backend-engineer (주) + security-engineer 검토 (권한 가드, CLAUDE.md §sub-agent)
> primary_bc: project-workflow
> 생성: 2026-07-26

## Brief

### 사용자 원문

WorkflowSchemeController 읽기 API 권한 갭(N4) 봉합. `list`(L105)·`get`(L121)이 `requirePermission` 0건이라
"인증만 되면 누구나" 전 스킴 이름·매핑·사용처 카운트를 읽는다.

처방은 **Maxi 결정 B안**으로 확정 (결정 ID `6bdd1944`, 2026-07-26).

1. `WorkflowSchemeController.list`·`get` 에 `MANAGE_SCHEME` / `WorkflowSchemeScope.Global` 게이트 추가
2. 배정 화면용으로 `ProjectWorkflowSchemeController` 에
   `ASSIGN_SCHEME` / `WorkflowSchemeScope.Project(projectKey)` 게이트 목록 엔드포인트 신설
3. 프론트 `apps/web/src/hooks/use-workflow-schemes.ts` 를 신설 창구로 전환해
   `projects.$projectKey.settings.workflow-scheme` 화면이 403 으로 깨지지 않게 한다

**마이그레이션 0 · 신규 enum 0 · FR 129 불변 예정.**

### classify 결과

```json
{
  "type": "api",
  "agent": "backend-engineer",
  "primary_bc": "project-workflow",
  "slug": "workflowscheme-api-n4-list-get-manage-scheme"
}
```

**접두사 편차 기록.** classify 는 `type=api` 로 판정했으나 브랜치/PR 접두사는 `auth` 를 쓴다.
실질이 권한 가드 추가이고, 같은 경로토큰 유출 트랙의 형제 PR(#311 `[auth]`·#312 `[auth]`)과
정렬하기 위함. 에이전트 배정은 classify 대로 backend-engineer 유지(주 BC 가 project-workflow 이고
security-engineer 의 주 작업 영역은 `identity-access/**` 이므로) + 권한 가드 부분 security-engineer 검토.

### 배경 — 경로토큰 유출 트랙의 마지막 항목

2026-07-26 병렬 조사로 확정된 표면 4건 중 **N4 가 유일한 잔여**.

| 항목 | 상태 |
|---|---|
| N1 nginx 접속로그 토큰 마스킹 | ✅ #311 `17043db71` |
| N2 인증요청 `/error` 경로토큰 유출 | ✅ #312 `6cc7184d9` |
| N3 잠복 전역 advice `instance` 자동채움 | ✅ #313 `2b5d4e951` |
| **N4 WorkflowScheme 읽기 권한 갭** | ⬜ **본 작업** |

진실출처. 메모리 `path-token-leak-surface-four-findings-2026-07-26`.

### 착수 전 반증 결과 (2026-07-26, /context-restore 단계에서 수행)

근거 5건 **전부 유효**.

| 주장 | 실측 |
|---|---|
| 쓰기 5개만 `requirePermission` (L82·146·177·203·241) | ✅ 일치 |
| `list`(L105)·`get`(L121) 권한 0건 | ✅ 본문에 가드 없음 |
| enum 은 `MANAGE_SCHEME`·`ASSIGN_SCHEME` 2종뿐 | ✅ `WorkflowSchemePermission.kt:20-36` |
| prod resolver Global=`isSystemAdmin` | ✅ `IdentityAccessWorkflowSchemePermissionResolver.kt:54` |
| 배정 화면이 `useWorkflowSchemes` 소비 | ✅ `projects.$projectKey.settings.workflow-scheme.tsx:61` |

**정정 2건.**

- 라우트 가드는 "`requireAuth` 뿐"이 아니라 **`requireAuthAndPasswordChanged`**(이름과 달리 MFA 포함).
  systemAdmin 이 아니라는 결론은 불변 → 프로젝트 관리자 도달 성립.
- **★형제 컨트롤러가 이미 정답 패턴이다.** `ProjectWorkflowSchemeController` 는 GET(L107)·PUT(L78)
  **둘 다** `ASSIGN_SCHEME` + `WorkflowSchemeScope.Project(projectKey)` 로 게이트돼 있고,
  문제의 배정 화면이 그 GET 을 `useGetAssignment` 로 **이미 호출해 정상 동작 중**이다(L60).
  ⇒ "프로젝트 관리자가 `ASSIGN_SCHEME`/Project 게이트를 통과한다"가 그 화면에서 **이미 실증**됐다.
  최초 조사는 이 대칭을 놓쳐 B안 비용을 실제보다 비싸게 봤다.

### 기각된 대안

- **A안 — `get` 단건만 게이트.** `list` 가 전 스킴 이름·사용처 카운트를 계속 노출. 문을 반만 닫는다.
- **C안 — `VIEW_SCHEME` enum 신설.** shared-kernel enum + prod resolver 분기 + 권한 시드 마이그레이션 +
  `PermissionSchemaMigrationTest` 커플링. 폭발 반경이 셋 중 가장 크다.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
