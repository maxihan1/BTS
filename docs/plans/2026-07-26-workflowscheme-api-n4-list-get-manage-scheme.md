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

## 도메인 정리

- **BC**: project-workflow (단일). identity-access 의 prod resolver 는 **읽기만 하고 수정하지 않음** → cross-BC 변경 0
- **영향 엔티티**: `WorkflowScheme` · `WorkflowSchemeMapping` (둘 다 기존). **신규 엔티티 0**
- **새 용어**: **0건.** `MANAGE_SCHEME` · `ASSIGN_SCHEME` · `WorkflowSchemeScope.Global/Project` 전부 기존 등재.
  glossary L71 "시스템 관리자 — 전역 자원(워크플로우 스킴 등) 관리 주체" 가 이미 본 작업의 판정 모델을 서술한다.
  → **glossary 갱신 불요**
- **기존 결정 충돌**: **없음. 오히려 정합 복원.**

### ★ 핵심 발견 — 이것은 신규 정책이 아니라 스펙 준수 복원이다

`docs/specs/2026-05-28-fr-wf-02-d6-ui-crud.md:57`.

> base `/api/v1`. **모든 endpoint 권한 검증** — `WorkflowSchemePermission.MANAGE_SCHEME` (**4.1**, 4.2, 4.4)
> / `ASSIGN_SCHEME` (4.3).

§4.1 = 스킴 CRUD 5개이고 그 안에 `list`·`get` 이 포함된다. **스펙은 처음부터 읽기에도 `MANAGE_SCHEME` 을
요구했고 구현이 이행하지 않았다.** 같은 spec L119 가 "권한 없는 사용자 — FR-PM-04 후속 정식 RBAC 도입 시
401/403 분기 추가" 로 이연했으나, FR-PM-04(#73)가 쓰기 5개만 결선하고 읽기 2개를 남겼다. **문서↔구현 drift.**

BC 관례도 반대 방향 — `PostActionController.kt:74` 는 GET 에도 `MANAGE_SCHEME` 요구.

### 기존 ADR 과의 정합

[2026-06-04-workflow-scheme-permission-prod-resolver](../decisions/2026-06-04-workflow-scheme-permission-prod-resolver.md)
§spec 단계 확정(2026-06-05).

| ADR 확정 사항 | 본 작업 |
|---|---|
| D3 Global — 스킴 CRUD(`MANAGE_SCHEME`/Global) = **시스템 관리자 전용** (Jira Cloud 모델, 스킴은 전역 자원) | D1 이 읽기까지 일관 적용 |
| D3 Project — 스킴 배정(`ASSIGN_SCHEME`/Project) = **프로젝트 관리자** | D2 가 배정용 읽기 창구에 그대로 적용 |
| D4 시드 — `MANAGE_WORKFLOW` × `PROJECT_ADMIN` 1행(V013), Global 은 매트릭스 미경유 | **시드 변경 0** — 기존 시드로 판정됨 |

[2026-06-05-workflow-scheme-controller-actor-wiring](../decisions/2026-06-05-workflow-scheme-controller-actor-wiring.md)
이 확립한 `CurrentActor.current()` → `actor.toUuid()` 결선 패턴을 읽기 2개에도 동일 적용한다.

### 신규 ADR

[docs/decisions/2026-07-26-workflow-scheme-read-permission-gate.md](../decisions/2026-07-26-workflow-scheme-read-permission-gate.md) **생성됨**.
D1(읽기 게이트) · D2(배정용 프로젝트 스코프 창구) · D3(배정용 응답은 부분집합) · D4(enum·마이그레이션 0).
엔드포인트 경로·응답 DTO 형태는 **spec 단계로 이연**(2026-06-04 ADR 이 판정 모델을 spec 으로 이연한 선례와 동형).

### 워크플로우 편차 기록 — `grill-with-docs` 생략

**사유.** 이 단계의 목적은 (a) 새 용어 발굴 (b) 도메인 모델 검증 (c) 기존 결정 충돌 탐지인데,
읽기 전 조사에서 셋 다 결론이 났다 — 새 용어 0 · 신규 엔티티 0 · 충돌 0(오히려 기존 ADR·spec 과 정합).
대화형 grilling 이 추가로 밝힐 미지가 없고 세션 예산만 소모한다.
대신 이 단계의 실질 산출물인 **ADR 1건은 정상 생성**했다.
(선례 — 2026-07-26 N1 작업도 같은 사유로 생략하고 기록함.)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
