# FR-BD-01 칸반 보드 (백엔드 D1~D5) — 스펙

> slug: fr-bd-01-kanban-board · BC: agile-planning(신설) · SDD §13.1 · PR #165
> 범위: 백엔드 D1~D5. 프론트 D6/D7(@dnd-kit, E2E)은 후속 PR.
> 관련 ADR: docs/decisions/2026-06-20-fr-bd-01-agile-planning-bootstrap.md

## 결정 요약 (Maxi 확정)

- 보드 생성 = **명시적 보드 CRUD**(POST 생성, 한 프로젝트 다중 보드 허용).
- 컬럼 = **상태별 명시 매핑**(board_columns 각 행이 워크플로우 상태 1개에 1:1 매핑).
- 카드 이동 = **agile-planning 이동 엔드포인트**가 cross-BC 전이 포트(issue-tracking 구현)에 위임. 컬럼 간 이동만(LexoRank/컬럼 내 재정렬 제외).

## 사용자 시나리오 (Given-When-Then)

1. **보드 생성** — Given 프로젝트 `BTS`의 멤버가, When `POST /api/v1/boards`로 보드를 생성하면, Then 프로젝트의 default 워크플로우 상태들이 displayOrder대로 컬럼으로 자동 시드되고 boardId가 반환된다.
2. **보드 조회** — Given 보드가 존재하고, When `GET /api/v1/boards/{id}`를 호출하면, Then 컬럼 목록(각 컬럼=상태 매핑) + 각 컬럼에 배치된 카드(이슈) 목록이 반환된다. 각 이슈는 `current_state_key`가 일치하는 컬럼에 배치된다.
3. **카드 이동** — Given 보드의 카드(이슈)가 TODO 컬럼에 있고, When `POST /api/v1/boards/{id}/cards/{issueKey}/move`로 IN_PROGRESS 컬럼으로 이동하면, Then 대상 컬럼의 state_key로 워크플로우 전이가 실행되어 이슈 상태가 바뀌고, 갱신된 카드가 반환된다.
4. **전이 불가 이동** — Given 워크플로우가 TODO→DONE 직접 전이를 허용하지 않을 때, When 카드를 TODO 컬럼에서 DONE 컬럼으로 이동하면, Then 409(전이 불가)로 거부되고 보드 상태는 변하지 않는다.
5. **권한 없는 조회** — Given 프로젝트 이슈 VIEW 권한이 없는 사용자가, When 보드를 조회하면, Then 403으로 거부된다.

## 기능 요구사항 (FR)

- **FR-BD-01-1** agile-planning BC 모듈을 신설한다(settings.gradle.kts 등록, build.gradle.kts, db/migration/agile-planning, com.bts.agileplanning.* 패키지, ArchUnit BC 격리).
- **FR-BD-01-2** 보드 CRUD: 생성(POST), 단건 조회(GET /{id}), 프로젝트별 목록(GET ?projectKey=). 수정/삭제는 이번 범위 제외(후속).
- **FR-BD-01-3** 보드 생성 시 프로젝트 default 워크플로우 상태(`WorkflowStateCatalog.listStates(projectKey, null)`)를 컬럼으로 자동 시드. 각 컬럼은 state_key·name·category·displayOrder를 갖는다.
- **FR-BD-01-4** 보드 조회 시 컬럼별로 카드(이슈)를 배치. 카드 = 프로젝트 이슈를 cross-BC 포트로 읽어 current_state_key로 컬럼에 매핑. 어떤 컬럼에도 매핑되지 않는 상태의 이슈는 보드에서 제외(엣지 케이스 E2). **컬럼 내 카드 정렬 = priority ASC(1=최상위 먼저), created_at ASC 보조**(issues.priority 존재 확인).
- **FR-BD-01-4b (보안, 리뷰 정정)** 보드 카드 목록은 viewer 기준 보안수준 필터를 적용한다. **목록 정석 경로 재사용**: `IssueSecurityDirectory.accessibleLevels(viewerUserId, projectKey)`로 접근 가능 레벨 집합을 1회 조회 → `IssueRepository.listWithType`의 SQL WHERE 푸시다운으로 비가시 행 제외(FR-PM-06 목록 필터, `IssueSecurityListFilterTest` S1~S8 경로). **단건 위임(IssueVisibilityPort/IssueSecurityDecider)은 카드 200건에 N+1이라 금지**(목록 산출 부적합·NFR-1 위반). viewer 미가시 이슈는 카드에서 제외(제목 누출 차단).
- **FR-BD-01-5** 카드 이동 = 대상 컬럼의 state_key로 워크플로우 전이. cross-BC 전이 포트에 위임(전이 규칙·권한·OCC는 issue-tracking이 강제). 전이 불가/버전 충돌/워크플로우 미설정은 그대로 전파.
- **FR-BD-01-6 (리뷰 정정, 2단 게이트)** **보드 조회 = `IssuePermission.BROWSE`**(프로젝트 목록 권한; VIEW는 단건 조회용이라 부적합) on `IssueScope.Project`. **카드 노출은 추가로 행 단위 보안수준 필터**(FR-BD-01-4b accessibleLevels) — BROWSE는 목록 자격, 보안수준은 개별 행, 둘 다 필요(listIssues 동일 패턴). **카드 이동 = TRANSITION 권한**(전이 포트가 강제). 권한 체크는 기존 `IssuePermissionResolver`(shared-kernel 포트) 재사용(신규 BoardPermissionPort 불필요). **보드 생성 = `IssuePermission.CREATE`**(Maxi 게이트1 확정 — 이슈 생성 권한 동급, 기여 멤버가 보드 생성). actor 추출은 리소스 조회(404)보다 먼저(존재 probe 차단), 권한/정합 거부 message는 일반화. 미충족 403.

## 비기능 요구사항 (NFR)

- **NFR-1** 보드 200건(이슈) 렌더용 조회 p95 < 1.5s (product §2.1 NFR). 이슈 목록 조회는 N+1 금지(단일/소수 쿼리).
- **NFR-2** BC 격리: 다른 BC 직접 import 금지. shared-kernel 포트 + (필요 시) pgmq만. ArchUnit으로 강제.
- **NFR-3** 절대 규칙(DEVELOPMENT.md §1) 준수: 신규 의존성 0(notification 템플릿 내 기존 의존만), 권한 fail-closed, 에러 처리.

## API 인터페이스 (REST)

기준 경로 `/api/v1/boards`. 인증=JWT 세션(기존). 응답 봉투=기존 `DataResponse<T>` / 에러 `{error:{code,message}}`.

### POST /api/v1/boards — 보드 생성
```
Request:  { "projectKey": "BTS", "name": "BTS 개발 보드" }
Response: 201 { "data": { boardId, projectKey, name, columns:[{columnId, stateKey, name, category, displayOrder}] } }
Errors:   400(검증), 403(권한), 404(프로젝트 없음), 422(워크플로우 스킴 미할당 → 컬럼 시드 불가)
```

### GET /api/v1/boards/{id} — 보드 단건 조회(컬럼+카드)
```
Response: 200 { "data": {
  boardId, projectKey, name,
  columns: [ { columnId, stateKey, name, category, displayOrder,
               cards: [ { issueKey, summary, assigneeId, version } ] } ]
} }
Errors:   403(권한), 404(보드 없음)
```

### GET /api/v1/boards?projectKey=BTS — 프로젝트별 보드 목록
```
Response: 200 { "data": [ { boardId, projectKey, name } ] }
Errors:   403(권한)
```

### POST /api/v1/boards/{id}/cards/{issueKey}/move — 카드 이동(전이)
```
Request:  { "toColumnId": "<uuid>", "expectedVersion": 3, "resolutionId": "<uuid>"? }
          (actor는 body에 받지 않음 — SecurityContext에서 추출)
Response: 200 { "data": { issueKey, currentStateKey, version, columnId } }
Errors:   400(검증), 403(권한), 404(보드/컬럼/이슈 없음),
          409(전이 불가 IssueTransitionNotAllowed / 버전 충돌 VersionConflict),
          422(워크플로우 미설정 / DONE 전이인데 resolution 필요)
```
- `toColumnId`로 대상 컬럼의 state_key를 도출 → 전이 포트에 `{issueKey, toStatusKey, expectedVersion, resolutionId}` 위임.
- 같은 컬럼으로의 이동(no-op)은 200(상태 불변) 또는 400 — 엣지 케이스 참조.

## 데이터 모델 (agile-planning, V500~)

```sql
-- V500__boards.sql
CREATE TABLE boards (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_key VARCHAR(64) NOT NULL,         -- BC 격리: FK 아님(notification 선례)
    name        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at  TIMESTAMPTZ NULL
);
CREATE INDEX idx_boards_project_key ON boards(project_key) WHERE deleted_at IS NULL;

CREATE TABLE board_columns (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    board_id      UUID NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    state_key     VARCHAR(50) NOT NULL,       -- 매핑된 워크플로우 상태 키(project-workflow workflow_states.key)
    name          TEXT NOT NULL,
    category      VARCHAR(16) NOT NULL,       -- TODO/IN_PROGRESS/DONE (표시·그룹용 스냅샷)
    display_order INTEGER NOT NULL DEFAULT 0,
    UNIQUE (board_id, state_key)
);
```
- `db/codegen/init_codegen.sql`에 동일 DDL 미러(jOOQ codegen).
- 컬럼은 생성 시점의 워크플로우 상태 스냅샷. 워크플로우 상태 변경 시 보드 컬럼 재동기화는 이번 범위 제외(후속/엣지 케이스 명시).

## cross-BC 포트 (shared-kernel)

1. **WorkflowStateCatalog 확장** — `WorkflowStateView`에 `category: String`(또는 StateCategory), `displayOrder: Int` 추가. **기존 호출(이슈 이동 preview) 보호 위해 default 값 부여**(category 기본·displayOrder=0). project-workflow의 `WorkflowStateCatalogImpl`이 도메인 WorkflowState에서 채움(이미 보유). 보드 생성 시 컬럼 시드에 사용.
2. **BoardIssueLookupPort (신규)** — issue-tracking 구현. 프로젝트 이슈 목록 읽기 + **viewer 기준 visibility 필터(G1)**.
   ```kotlin
   interface BoardIssueLookupPort {
       // viewerUserId가 볼 수 있는 이슈만 반환(보안수준 필터). default fail-safe = 빈 목록(데이터 조회, 권한 아님).
       fun listVisibleIssuesByProject(projectKey: String, viewerUserId: UUID): List<BoardIssueView> = emptyList()
   }
   data class BoardIssueView(val key: String, val summary: String, val currentStateKey: String, val assigneeId: UUID?, val priority: Int, val version: Long)
   ```
   issue-tracking 구현이 IssueSecurityDecider/visibility를 적용해 viewer 미가시 이슈를 제외(N+1 금지).
3. **IssueTransitionPort (신규)** — issue-tracking 구현. 카드 이동 위임. **default 없음(fail-closed)** — 전이는 권한/규칙 강제하므로 빈 부재 시 부팅 실패. allow-all/예외삼킴 금지(crossbc-resolver-nullable-fail-open 반례).
   ```kotlin
   interface IssueTransitionPort {
       fun transition(cmd: BoardTransitionCommand): BoardTransitionResult
   }
   ```
   **actor는 cmd 인자로 받지 않는다(리뷰 정정)** — adapter가 `CurrentActor.current()`로 SecurityContext에서 추출(issue-tracking 전이 정석과 동일, actor 위조/impersonation 차단). BoardTransitionCommand = `(issueKey, toStateKey, expectedVersion, resolutionId?)`.
4. **권한 포트 = 기존 `IssuePermissionResolver` 재사용(리뷰 정정, 신규 포트 X)** — shared-kernel `IssuePermissionResolver.hasPermission(actorId, permission, scope)`로 BROWSE/TRANSITION 판정. prod=identity-access `IdentityAccessIssuePermissionResolver`, non-prod=issue-tracking `AlwaysAllowIssuePermissionResolver` 자동 해석. agile-planning은 이 포트를 **non-null 주입**(빈 부재=부팅 실패, fail-closed). 통합테스트는 test-assembled stub.

## 엣지 케이스

- **E1 워크플로우 스킴 미할당 프로젝트** — 보드 생성 시 `listStates`가 빈 리스트 → 컬럼 0개. 422로 거부(보드는 워크플로우 필요).
- **E2 미매핑 상태의 이슈** — 이슈의 current_state_key가 어떤 컬럼에도 없을 때(컬럼 시드 후 워크플로우에 상태 추가됨). 보드 조회 시 해당 이슈는 제외(노출 안 함). 향후 "미분류" 컬럼은 후속.
- **E3 같은 컬럼으로 이동(no-op)** — toColumnId가 현재 상태와 동일 → 전이 없음. 200(불변) 반환(드래그 취소/원위치). 자기 전이는 전이 포트가 거부할 수 있으므로 보드 레벨에서 no-op 단축 처리.
- **E4 DONE 컬럼 이동 + resolution 필요** — 전이 포트가 422(resolution 필요) 전파. 프론트(D6)가 resolution 모달. 백엔드는 resolutionId optional 전달.
- **E5 버전 충돌** — 다른 사용자가 먼저 전이 → expectedVersion 불일치 → 409 전파.
- **E6 삭제된 이슈/보드** — deleted_at 필터. soft-deleted 이슈는 카드에서 제외, soft-deleted 보드는 404.
- **E7 권한 없는 카드 이동** — 전이 포트가 권한 강제(403). 보드 조회 권한과 별개로 전이 권한도 검증됨.
- **E8 다른 프로젝트 이슈 이동 시도** — issueKey가 보드의 project_key 소속이 아니면 404/400(보드-이슈 정합 검증).

## 제약 조건

- BC 격리: agile-planning은 issue-tracking/project-workflow/identity-access를 직접 import 금지. shared-kernel 포트만.
- 보드가 issues.current_state_key를 직접 UPDATE 금지 — 반드시 전이 포트 위임(워크플로우 불변식 우회 금지, patch-merge-domain-bypass 반례).
- 신규 외부 의존성 0(@dnd-kit·LexoRank는 이번 범위 밖).
- 마이그레이션 V번호 = V500~ (agile-planning 대역). 동시 브랜치 충돌 머지 직전 재확인.

## 측정 가능한 완료 기준

- [ ] agile-planning 모듈이 빌드/테스트에 포함되고 ArchUnit BC 격리 통과.
- [ ] 보드 생성 → default 워크플로우 상태가 컬럼으로 시드됨(통합 테스트).
- [ ] 보드 조회 → 카드가 current_state_key 기준 컬럼에 배치됨.
- [ ] 카드 이동 → 전이 포트 위임으로 상태 변경, 전이 불가 시 409, 버전 충돌 409, 권한 없음 403.
- [ ] 엣지 케이스 E1~E8 테스트.
- [ ] backend test + ktlint + detekt 그린.

## Brainstorming Check

✅ 통과 (직접 adversarial 점검, gap 3건 발견 후 보강)
- **G1 (보안)**: 보드 카드 목록 visibility 누출 위험 → FR-BD-01-4b + BoardIssueLookupPort에 viewer 기준 visibility 필터 추가(FR-NT-03 교훈).
- **G2 (권한)**: 보드 생성/조회/이동 권한 레벨 미명시 → FR-BD-01-6 구체화(조회·생성=VIEW, 이동=전이권한, security-engineer plan 검토).
- **G3 (정렬)**: 컬럼 내 카드 정렬 기준 미정 → issues.priority 존재 확인 후 priority ASC + created_at ASC로 확정.
- 잔여 후속(범위 밖, 명시): 컬럼 매핑 사용자 편집(FR-BD-03), 워크플로우 변경 시 컬럼 재동기화, 미분류 컬럼.
