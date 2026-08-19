# [backend] project-workflow — 워크플로우·상태 CRUD API

> 티어: T3
> slug: backend-project-workflow-crud-api
> type: api
> agent: backend-engineer
> 생성: 2026-08-19

## Brief

워크플로우 편집기 로드맵(`~/.claude/plans/cozy-hatching-otter.md`) 10 PR 중 **PR 3**.
FR-WF-04(워크플로우 CRUD). 선행 PR 2 = #392(squash `213e8a317`) 머지 완료.

classify 결과 — `type=api` · `agent=backend-engineer` · `primary_bc=project-workflow` ·
`tier=T3`(Maxi 지정. `classify-task.ts:517` 은 티어를 추론하지 않고 `input.tier ?? DEFAULT_TIER` 다).
실측 표면도 T3 — `backend/**/main`(BE_MAIN·T2) + `shared-kernel` 신규 enum·resolver(SHARED_KERNEL·T3) 혼합 → 최고 티어 지배.

### 범위 7건 (사용자 원문)

1. **선행 — 읽기 경로 2단 join 전환.** PR 2 에서 D1 로 이관된 분. `statuses` + `workflow_statuses`
   2단 join 으로 읽기를 바꾸고, 원시 SQL 로 워크플로우 상태를 심는 **32파일**(issue-tracking 21 ·
   project-workflow 11)을 픽스처 헬퍼 1개 도입으로 일괄 이주한다.
2. `POST/PUT/DELETE /api/v1/workflows` · `POST /api/v1/workflows/{key}/duplicate`
3. `GET/POST/PUT/DELETE /api/v1/statuses` (전역 상태 카탈로그)
4. `POST/DELETE /api/v1/workflows/{key}/statuses` · `PUT .../statuses/order`
5. **권한** — shared-kernel 에 `WorkflowDefinitionPermission`(CREATE/UPDATE/DELETE/PUBLISH) +
   `WorkflowDefinitionPermissionResolver` 신설. `VersionPermission`·`ComponentPermission`·
   `TemplatePermission` 의 「도메인당 enum 1개 + resolver 1개」 관례 그대로. prod 어댑터는
   identity-access 의 `MANAGE_WORKFLOW` 코드 매핑, non-prod 는 `AlwaysAllow` stub.
   **`WorkflowSchemePermission` 에 끼워 넣지 않는다** — 이름이 스킴을 뜻하는데 워크플로우 정의를
   담게 되어 다음 사람이 오해한다.
6. **키 불변 강제** — `PUT /statuses/{id}` 는 `name`·`description`·`category` 만 수용하고
   `key` 는 받지 않는다. 테스트로 못박는다.
7. **캐시 무효화** — 모든 쓰기 경로 끝에 `WorkflowCache.invalidate(workflowKey)`.
   누락 감지 테스트를 별도로 둔다.

### red-first 4건

- 이름 수정 후 GET 이 새 이름을 주는가
- `key` 변경 시도가 400 인가
- 사용 중 워크플로우 삭제가 409 인가
- 편집 후 캐시가 갱신되는가

### 체인에 물린 learnings 2건

- **2026-05-23 fixture 옵션 B 패턴** — mirror data(fixture/seed/mock)는 정적 문자열 대신 helper
  호출로 drift 를 **본질 차단**한다. 회귀 가드는 보조. 32파일 픽스처 헬퍼 설계에 직접 적용.
- **2026-07-17 파일 존재 ≠ 기능 존재** — 「백엔드 완비」는 컨트롤러 HTTP 매핑을 세어서 판정한다.
  기존 `WorkflowController` 의 실제 `@*Mapping` 개수를 먼저 실측할 것.

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
