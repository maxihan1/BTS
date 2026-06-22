# FR-BD-03 — WIP 제한 + 스윔레인 (백엔드 D1~D5) 스펙

> slug: fr-bd-03-wip-swimlane
> BC: agile-planning
> 선행 ADR: [2026-06-22-fr-bd-03-wip-swimlane](../decisions/2026-06-22-fr-bd-03-wip-swimlane.md), [2026-06-20-fr-bd-01-agile-planning-bootstrap](../decisions/2026-06-20-fr-bd-01-agile-planning-bootstrap.md)
> SDD: §13.1.1 (WIP 제한), §13.1.3 (스윔레인)

## 개요

칸반 보드에 두 기능을 추가한다.
1. **WIP 제한** — 컬럼당 최대 카드 수(`board_columns.wip_limit`). 초과 시 경고 신호만 응답(이동 차단 X).
2. **스윔레인** — 보드 가로 분리 기준(`boards.swimlane_field`: NONE/ASSIGNEE/PRIORITY). 백엔드는 설정 저장+echo, 그룹핑은 프론트(D6).

범위 = 백엔드 D1~D5. 프론트 D6(컬럼 헤더 경고 + 스윔레인 그룹 UI)/D7(E2E)은 후속 PR.

## 사용자 시나리오 (Given-When-Then)

### S1. 컬럼 WIP 제한 설정
- **Given** 보드 생성 권한(CREATE)을 가진 사용자가 보드 컬럼 "진행 중"을 본다.
- **When** 해당 컬럼의 WIP 제한을 5로 설정한다 (`PATCH /boards/{id}/columns/{columnId}` body `{wipLimit:5}`).
- **Then** 200 OK + 컬럼의 wipLimit=5가 영속된다.

### S2. WIP 초과 경고 신호
- **Given** "진행 중" 컬럼의 wipLimit=3, 현재 그 상태의 가시 카드가 5건이다.
- **When** 사용자가 보드를 조회한다 (`GET /boards/{id}`).
- **Then** 응답의 해당 컬럼에 `wipLimit:3`, `wipExceeded:true`가 포함된다. 카드 이동은 차단되지 않는다.

### S3. WIP 제한 해제
- **Given** wipLimit=5인 컬럼.
- **When** `PATCH .../columns/{columnId}` body `{wipLimit:null}`.
- **Then** 200 OK + wipLimit=null(제한 없음). 이후 조회 시 wipExceeded는 항상 false.

### S4. 스윔레인 기준 설정
- **Given** 보드 권한을 가진 사용자.
- **When** `PATCH /boards/{id}` body `{swimlaneField:"ASSIGNEE"}`.
- **Then** 200 OK + swimlaneField=ASSIGNEE 영속. 이후 보드 조회 응답에 `swimlaneField:"ASSIGNEE"` echo. (그룹핑은 프론트가 카드 assigneeId로 수행)

### S5. WIP 초과 시 이동 비차단(불변식 보존)
- **Given** wipLimit=1, 대상 컬럼에 이미 카드 1건.
- **When** 사용자가 카드를 그 컬럼으로 이동한다(`POST .../move`).
- **Then** 전이가 정상 수행된다(전이 위임 그대로). WIP 초과는 다음 조회의 wipExceeded=true로만 표시된다.

## 기능 요구사항 (FR)

- **FR-1**. `board_columns.wip_limit`(INTEGER NULL, 양수만) 컬럼 추가. null=제한 없음.
- **FR-2**. `boards.swimlane_field`(VARCHAR NOT NULL DEFAULT 'NONE', ∈ {NONE,ASSIGNEE,PRIORITY}) 컬럼 추가.
- **FR-3**. `PATCH /api/v1/boards/{boardId}/columns/{columnId}` — 컬럼 WIP 제한 설정/해제. body `{wipLimit: Int?}`. 권한 CREATE on Project.
- **FR-4**. `PATCH /api/v1/boards/{boardId}` — 보드 스윔레인 기준 설정. body `{swimlaneField: String}`. 권한 CREATE on Project.
- **FR-5**. `GET /api/v1/boards/{id}` 응답 확장 — 각 컬럼에 `wipLimit:Int?`, `wipExceeded:Boolean` 추가 + 보드 레벨 `swimlaneField:String` 추가.
- **FR-6**. WIP 초과 판정 = 응답에 포함된(필터 적용 후) 컬럼 카드 수 > wipLimit. wipLimit=null이면 wipExceeded=false.
- **FR-7**. 카드 이동(`POST .../move`)은 WIP를 검사하지 않는다(경고 전용, ADR 결정 1).

## 비기능 요구사항 (NFR)

- **NFR-1**. 기존 보드 조회 응답(`truncated`/`unplacedCount`/카드 정렬)은 그대로 보존. 신규 필드는 가산만.
- **NFR-2**. BC 격리 유지 — cross-BC는 shared-kernel 포트만. issue-tracking/project-workflow 직접 import 0.
- **NFR-3**. 마이그레이션은 ADD COLUMN(기존 행에 DEFAULT 적용). 보드 200건 렌더 임계(1.5s)에 영향 없음.
- **NFR-4**. swimlaneField echo·wipExceeded 계산은 추가 cross-BC 조회를 유발하지 않는다(기존 placeCards 결과로 산출).

## API 인터페이스 (REST)

### PATCH /api/v1/boards/{boardId} — 스윔레인 기준 설정
```
Request:  { "swimlaneField": "ASSIGNEE" }   // NONE | ASSIGNEE | PRIORITY
Response: 200 { "data": { "boardId", "projectKey", "name", "swimlaneField": "ASSIGNEE" } }
권한: CREATE on IssueScope.Project(board.projectKey)
오류: 400(미허용 값), 401(미인증), 403(권한), 404(보드 미존재/soft-deleted)
```

### PATCH /api/v1/boards/{boardId}/columns/{columnId} — WIP 제한 설정/해제
```
Request:  { "wipLimit": 5 }     // 양수 = 설정, null = 해제
Response: 200 { "data": { "columnId", "stateKey", "name", "category", "displayOrder", "wipLimit": 5 } }
권한: CREATE on IssueScope.Project(board.projectKey)
오류: 400(wipLimit ≤ 0), 401, 403, 404(보드 또는 컬럼 미존재/타 보드 소속)
```

### GET /api/v1/boards/{id} — 응답 확장 (기존 엔드포인트)
```
Response 200 data:
{
  "boardId", "projectKey", "name",
  "swimlaneField": "NONE",            // 신규
  "columns": [
    { "columnId","stateKey","name","category","displayOrder",
      "wipLimit": 3,                  // 신규 (null 가능)
      "wipExceeded": true,            // 신규 (cards.size > wipLimit)
      "cards": [ ... ] }
  ],
  "truncated": false, "unplacedCount": 0
}
```

## 데이터 모델 변경

신규 마이그레이션 `V501__board_wip_swimlane.sql` (※ V번호는 머지 직전 origin/main 재확인 — DATA.md §4.1).
```sql
ALTER TABLE board_columns
  ADD COLUMN wip_limit INTEGER NULL
    CONSTRAINT board_columns_wip_limit_positive CHECK (wip_limit IS NULL OR wip_limit > 0);
ALTER TABLE boards
  ADD COLUMN swimlane_field VARCHAR(16) NOT NULL DEFAULT 'NONE'
    CONSTRAINT boards_swimlane_field_allowed CHECK (swimlane_field IN ('NONE','ASSIGNEE','PRIORITY'));
```
- **init_codegen.sql 미러 필수** (`db/codegen/init_codegen.sql`의 boards/board_columns 정의에 동일 컬럼 추가 — 메모리 jooq-init-codegen-mirror).
- jOOQ codegen 재생성.

도메인:
- `BoardColumn`에 `wipLimit: Int?` 추가. init `require(wipLimit == null || wipLimit > 0)`.
- `Board`에 `swimlaneField: SwimlaneField` enum 추가. `enum class SwimlaneField { NONE, ASSIGNEE, PRIORITY }`. 기본값 NONE.
- `BoardRepository`에 update 메서드 신규: `updateSwimlaneField(boardId, swimlaneField): Board`, `updateColumnWipLimit(boardId, columnId, wipLimit): BoardColumn`.

## 엣지 케이스

- **E1**. wipLimit ≤ 0 → 400 (CHECK + 도메인 require + DTO 검증 3중).
- **E2**. wipLimit=null → 제한 해제(정상 200).
- **E3**. swimlaneField 미허용 값(예: "EPIC", "foo", 소문자 "assignee") → 400. enum valueOf는 대소문자 구분(허용 값은 대문자만). EPIC은 FR-EP 이연 — ADR 결정 3.
- **E4**. columnId가 해당 boardId 소속이 아님 → 404 (컬럼 소속 검증).
- **E5**. 보드 미존재/soft-deleted → 404.
- **E6**. 권한(CREATE) 미충족 → 403 (일반 메시지, 내부정보 미노출 — 기존 패턴).
- **E7**. 미인증 → 401 (actor 추출이 리소스 조회보다 선행 — auth-extraction-before-resource-lookup).
- **E8**. wipLimit=null인 컬럼의 wipExceeded는 항상 false.
- **E9**. 필터 적용 조회 시 wipExceeded는 필터 결과 카드 수 기준(프론트가 보는 카드와 일치). truncated=true면 일부 누락 가능하나 truncated 신호로 별도 표시.
- **E10**. 카드 0건 + wipLimit 설정 → wipExceeded=false.
- **E11**. WIP 카운트는 viewer 가시 카드 기준이다. 보안수준 높은 카드를 못 보는 viewer에겐 그 카드가 카운트에서 제외되어 wipExceeded가 다르게 보일 수 있다(의도된 동작 — 각자 보는 기준). E9 필터 케이스와 동일 논리.

## 제약 조건

- ADR 결정 4종 준수: WIP 경고 전용(차단X) / 스윔레인 그룹핑 프론트 / EPIC 이연 / 백엔드 D1~D5만.
- 카드 이동 경로(`moveCard`)는 변경하지 않는다(WIP 검사 추가 금지 — FR-7).
- 기존 통합테스트(BoardControllerIntegrationTest 등) 회귀 0.
- 권한은 shared-kernel IssuePermissionResolver 재사용. 신규 권한 enum 추가 0.
- 보드 생성 응답(`BoardResponse`)/목록 응답(`BoardSummaryResponse`)에 swimlaneField는 이번 범위 외(생성 직후 항상 NONE, GET 단건 조회에서 노출). 보드 PATCH는 swimlaneField만 변경하며 name 등 다른 필드 변경은 별도 범위.

## Brainstorming Check

✅ 통과 (직접 sanity check 1회). 발견 gap 3건 보강 — E11(가시성 기준 WIP 카운트), E3(enum 대소문자), 생성 응답 swimlaneField 범위 명시. Maxi 결정 필요 수준 gap 없음.

## 측정 가능한 완료 기준

- [ ] V501 마이그레이션 + init_codegen 미러 + jOOQ codegen 재생성, BoardSchemaMigrationTest 통과.
- [ ] `BoardColumn.wipLimit`/`Board.swimlaneField` 도메인 불변식 단위 테스트.
- [ ] PATCH 2종 + GET 응답 확장 통합테스트(S1~S5 + E1~E10 커버).
- [ ] 권한 게이트(401/403/404) 테스트.
- [ ] 카드 이동 시 WIP 비차단 테스트(S5).
- [ ] ktlintCheck + detekt 그린, 전체 모듈 테스트 회귀 0.
