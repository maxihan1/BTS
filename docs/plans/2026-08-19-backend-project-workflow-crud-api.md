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

## 도메인 정리

**BC** — `project-workflow` 단일. shared-kernel 에 권한 계약 2파일 신설(enum + resolver 포트),
identity-access 에 prod 어댑터 추가. issue-tracking 은 **테스트 픽스처만** 바뀌고 프로덕션 0줄.

**영향 엔티티** — Workflow · Status(전역 카탈로그, PR 2 신설) · WorkflowStatus(N:M 편성) ·
WorkflowTransition(읽기 경로만, CRUD 는 PR 4).

**새 용어** — 없음. `glossary.md` §워크플로우/자동화 의 「전환」·「워크플로우 스킴」·「표준 스킴」·
「기본 매핑」이 이미 정본이고 이 PR 은 새 개념을 도입하지 않는다. 「전역 상태 카탈로그」는 PR 2 가
ADR 로 등재했다. **`grill-with-docs` 미호출** — T3 이지만 신규 도메인 개념이 없다.

**기존 결정 충돌** — 없음. 이 PR 은 PR 1(#391)이 세운 ADR 2건을 **이행**한다.
- `docs/adr/2026-08-18-workflow-db-as-source-of-truth.md` — 정본이 DB. 이 PR 이 그 DB 를 쓰는 API 를 연다
- `docs/adr/2026-08-18-workflow-global-status-catalog.md` — 키 불변 원칙. 이 PR 의 F5 가 그것을 강제한다

**관련 ADR** — 위 2건 + `docs/decisions/2026-07-26-workflow-scheme-read-permission-gate.md`(권한 게이트 선례) ·
`docs/decisions/2026-07-27-workflow-scheme-canonical-vocabulary.md`(스킴 용어 정본).

### ★ 실측 — 「지금 있는 것 / 이 PR 이 만드는 것」

learnings `2026-07-17 파일 존재 ≠ 기능 존재` 를 적용해 `@*Mapping` 을 **세었다**.

`WorkflowController.kt` 의 매핑은 4개뿐이고 **쓰기 CRUD 는 0개**다 —
`GET /`(`:52`) · `GET /{key}`(`:66`) · `POST /{key}/transitions`(`:84`, 전환 *계획 계산*) ·
`POST /cache/invalidate`(`:128`). `/api/v1/statuses` 는 컨트롤러 자체가 없다.
전수 표는 스펙 §API 인터페이스.

## 스펙

정본 — [`docs/specs/2026-08-19-backend-project-workflow-crud-api.md`](../specs/2026-08-19-backend-project-workflow-crud-api.md)

핵심 시나리오 3줄.
- 워크플로우 이름을 고치면 GET 이 즉시 새 이름을 주고 캐시도 갱신된다 (S1)
- 상태 `key` 는 어떤 경로로도 바뀌지 않는다 — 이슈·자동화·검색이 문자열로 참조하기 때문 (S2)
- 스킴이 참조 중인 워크플로우는 409 로 삭제를 막는다 (S3)

## Sanity Check

✅ 통과 — 보강 1회로 gap 9건 해소(삭제 방식·복제 범위·초기값·엣지 4건·「사용 중」 3축 분리·낙관적 락 경계·좌표 컬럼 경계),
Maxi 결정 2건은 아래에서 확정.

### ★ Maxi 결정 2건 (2026-08-19)

**D1 — 죽은 권한 게이트를 이 PR 에서 고친다.**
`POST /api/v1/workflows/cache/invalidate` 가 요구하는 `WORKFLOW_MANAGE` authority 는 **발급 경로가 없다**.
실측 3건 — ①`WorkflowController.kt:129` 가 요구 ②그 문자열은 그 컨트롤러와 그 테스트에만 존재하고
정본 코드는 철자가 뒤집힌 `MANAGE_WORKFLOW`(`V013` 시드) ③authority 생성처는 저장소에 2곳뿐이고
둘 다 `ROLE_` 접두어(`SidRevokeJwtConverter:115` · `PatAuthenticationFilter:48`).
테스트는 `@WithMockUser(authorities=["WORKFLOW_MANAGE"])` 로 손수 심어 초록이었다
(MEMORY `unreachable-state-fixture-is-fake-green`).
→ resolver 게이트로 교체. 범위 추가분 = F11 · E16 · M11.

**D2 — issue-tracking 테스트 21파일을 이 PR 에 포함한다.**
읽기 경로를 바꾸는 순간 즉시 red 가 되므로 원자적으로 같이 간다. 프로덕션 0줄.
게이트 2 요약에 BC 격리 의도적 편차로 명시한다.

### 실측 정정 1건

지시문의 「32파일」을 다시 세어 **31파일**(issue-tracking 21 · project-workflow 10)로 정정했다.
별도로 `workflow_states` 를 언급만 하는 4파일은 **구형 테이블 자체를 검증하는 것이 목적**이라
이주 대상이 아니다 — 헬퍼로 감싸면 검증이 사라진다.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
