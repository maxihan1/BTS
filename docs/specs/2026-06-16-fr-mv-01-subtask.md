# FR-MV-01 서브태스크 동반 이동 (노드별 매핑) — 스펙

> slug: fr-mv-01-subtask · BC: issue-tracking (cross-BC: project-workflow) · type: backend
> 도메인/결정: [docs/plans/2026-06-16-fr-mv-01-subtask.md](../plans/2026-06-16-fr-mv-01-subtask.md), [ADR issue-move-semantics §후속 결정](../adr/2026-06-16-issue-move-semantics.md)
> 선행: 단건 이동 #153 (preview/move 2단계, EC15 `ISSUE_HAS_SUBTASKS` 거부). 본 작업은 D1·D4 의 **서브태스크 동반** 확장.

## 단건 spec(2026-06-16-fr-mv-01.md) 대비 deviation (Maxi 확정 2026-06-16)

단건 spec 은 서브태스크를 (a) **parent_id 재귀 = 다단계 서브트리**, (b) **루트만 매핑 + 자식 자동 규칙** 으로 *제안*했다(§58-60, FR-11, EC13). 게이트1 이후 Maxi 가 다음으로 **확정**했다.

| 항목 | 단건 spec 제안 | 본 작업 확정 |
|---|---|---|
| 동반 범위 | parent_id 재귀(다단계) | **직접 자식(1레벨)만** |
| 자식 매핑 | 루트만 입력, 자식 자동 | **완전 노드별** (부모+각 자식 독립 매핑) |
| 다단계(자식의 자식) | 재귀 수집(EC13) | **거부**(신규 EC16) — 서브태스크 1레벨 불변식 강제 |

`hierarchy_level = -1`(subtask 최하위)로 BTS 서브태스크는 1레벨 모델이다. `parent_id` 레벨 강제 코드는 없으나 본 기능이 이동 시점에 검증한다.

## 사용자 시나리오 (Given-When-Then)

### S1. 자식 없는 이슈 이동 (단건 경로 — 회귀 보존)
- **Given** `ATLAS-12`(자식 없음), 대상 `INFRA`
- **When** move 요청에 `subtasks` 비어있음
- **Then** 단건 #153 과 동일하게 동작. 회귀 없음

### S2. 부모 + 직접 자식 동반 이동 (핵심)
- **Given** Story `ATLAS-12`(상태 `in_progress`)가 직접 자식 서브태스크 `ATLAS-13`(상태 `open`), `ATLAS-14`(상태 `done`)를 가짐. `ATLAS-12` 의 부모 Epic `ATLAS-5` 존재. 대상 `INFRA`
- **When** preview → 부모+두 자식 각각의 매핑 섹션 반환 → 사용자가 노드별 매핑 확인 → move
- **Then** `ATLAS-12`+`ATLAS-13`+`ATLAS-14` 전부 `INFRA` 로 이동. 각각 새 키(`INFRA-5/6/7`) + redirect + 308. **자식의 `parent_id`(=`ATLAS-12.id`)는 id 보존으로 자동 유지** → 이동 후에도 `INFRA-6/7` 이 `INFRA-5` 를 부모로 가짐. **`ATLAS-12` 의 부모(Epic `ATLAS-5`) 관계는 끊김**(`parent_id=null`, 최상위화). Epic 은 원본 잔류

### S3. 노드별 워크플로우 상태 비호환
- **Given** `ATLAS-12`(타입 story, 상태 `in_progress`), 자식 `ATLAS-13`(타입 subtask, 상태 `code_review`). 대상 `INFRA` 의 story 워크플로우엔 `in_progress` 없음, subtask 워크플로우엔 `code_review` 없음
- **When** preview
- **Then** 루트는 story 타입 기준 대상 상태 목록 + 제안, 자식은 subtask 타입 기준 대상 상태 목록 + 제안을 **각각** 반환(`WorkflowStateCatalog.listStates(projectKey, 각 노드 issueTypeKey)`) → move 에서 노드별 `targetStateKey` 적용

### S4. 노드별 OCC 충돌
- **Given** 동반 이동 중 자식 `ATLAS-13` 이 다른 트랜잭션에서 수정되어 version 이 올라감
- **When** move 요청의 `ATLAS-13` `expectedVersion` 불일치
- **Then** 409 — **전체 트리 롤백**(부모·다른 자식도 이동 안 됨)

### S5. 자식이 또 자식을 가짐 (다단계 거부)
- **Given** `ATLAS-12` 의 자식 `ATLAS-13` 이 또 자식 `ATLAS-99` 를 가짐(비정상 다단계)
- **When** `ATLAS-12` 이동 시도
- **Then** 422 `SUBTASK_HAS_OWN_SUBTASKS` — 1레벨 모델 위반. 이동 거부

### S6. 자식 매핑 불완전
- **Given** `ATLAS-12` 가 자식 `ATLAS-13`, `ATLAS-14` 보유
- **When** move 요청 `subtasks` 에 `ATLAS-13` 만 포함(`ATLAS-14` 누락)
- **Then** 422 `INCOMPLETE_SUBTASK_MAPPING` — 모든 직접 자식의 매핑을 제공해야 함

## 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| FR-S-1 | `POST /{key}/move/preview` 응답에 **직접 자식 노드별 매핑 섹션 배열**(`subtasks`) 추가. 각 노드는 단건과 동형(workflow/components/affects·fix versions/customFields) + `issueKey`/`issueTypeKey`/`version`. 자식 없으면 빈 배열 |
| FR-S-2 | preview/move 의 자식 워크플로우 상태 후보는 **각 노드의 `issueTypeKey`** 로 `WorkflowStateCatalog.listStates(projectKey, issueTypeKey)` 조회(루트도 동일). 단건은 `issueTypeKey=null` 이었음 — 노드별 타입 정확도 향상 |
| FR-S-3 | `POST /{key}/move` 요청에 선택적 `subtasks: [{issueKey, expectedVersion, targetStateKey, targetStateIsDone, componentMapping, affectsVersionMapping, fixVersionMapping, customFieldValues}]` 추가. **하위 호환** — 비어있으면 단건 경로. 자식 식별은 `issueKey`(preview 응답의 `issueKey` 그대로 사용, path 와 일관) |
| FR-S-4 | move 는 **단일 @Transactional** 로 루트+모든 직접 자식을 이동. 각 노드: id 보존 / 새 키 발번(루트 먼저, 자식 순서대로 `incrementKeySequence`) / redirect append-only / 조인테이블·커스텀필드 노드별 교체 / 노드별 OCC / 노드별 resolution clear |
| FR-S-5 | **자식의 `parent_id` 는 변경하지 않는다**(부모 id 보존이므로 부모-자식 관계 자동 유지). 단건 `move()` 의 `parent_id=null` 강제는 **루트(부모) 노드에만** 적용 |
| FR-S-6 | move 요청의 `subtasks` 는 이동 이슈의 **모든 직접 자식**을 포함해야 한다. 누락 시 422 `INCOMPLETE_SUBTASK_MAPPING`. 요청에 없는(존재하지 않는) 자식 id 포함 시도도 거부 |
| FR-S-7 | 직접 자식이 **또 자식을 가지면**(다단계) 422 `SUBTASK_HAS_OWN_SUBTASKS`. 이동 거부(1레벨 불변식) |
| FR-S-8 | 동반 경로에서 EC15 `ISSUE_HAS_SUBTASKS`(자식 존재 시 무조건 거부)는 **적용하지 않는다**. 자식 존재는 정상, 매핑 불완전/다단계만 거부 |
| FR-S-9 | 권한: 트리 전체가 동일 원본 프로젝트 소속 → 원본 `UPDATE` + 대상 `CREATE` **한 번** 검증(단건 코드 경로 재사용). preview 동일 |
| FR-S-10 | 모든 노드의 상태/프로젝트/키 변경을 각 노드 히스토리(`issue_change_group`/`item`)에 "이동" 으로 기록. 제거된 종속데이터도 노드별 이력 |
| FR-S-11 | move 응답에 `movedSubtasks: [{previousKey, issueKey}]` 추가(루트는 기존 `issueKey`/`previousKey`) |

## 비기능 요구사항 (NFR)

- 이동 트랜잭션 p95 < 1s (product §NFR) — 직접 자식 N개 포함. 1레벨 제한으로 N 상한
- 단일 트랜잭션 — 전 노드 UPDATE + redirect INSERT + 종속데이터 정리 + 히스토리. 일부 실패 시 전체 롤백
- 신규 키 발번 N+1건 — advisory lock 보유시간 증가. 1레벨 제한으로 완화
- cross-BC SPI(`WorkflowStateCatalog.listStates`) 재사용, project-workflow 직접 import 금지
- redirect 308 (이슈 키 영속성) — 노드별 보장
- 이동 이벤트/알림: 단건과 동일하게 **deferred**(히스토리 기록만)

## API 인터페이스 (REST)

### `POST /api/v1/issues/{key}/move/preview`
요청. `{ "targetProjectKey": "INFRA" }` (변경 없음)

응답 200 — 기존 `MovePreview` 에 `subtasks` 배열 추가.
```jsonc
{
  "workflow":        { /* 루트 — 기존과 동일 */ },
  "components":      { /* 루트 */ },
  "affectsVersions": { /* 루트 */ },
  "fixVersions":     { /* 루트 */ },
  "customFields":    { /* 루트 */ },
  "subtasks": [                                  // 신규 — 직접 자식 노드별
    {
      "issueKey": "ATLAS-13",
      "issueTypeKey": "subtask",
      "version": 1,
      "workflow":        { "compatible": false, "targetStates": [...], "suggestedStateKey": "open" },
      "components":      { "current": [...], "target": [...], "autoMapping": {...} },
      "affectsVersions": { /* 동형 */ },
      "fixVersions":     { /* 동형 */ },
      "customFields":    { "removed": [...], "requiredMissing": [...] }
    }
  ]
}
```
- 루트 섹션은 단건과 **동일 형식**(하위 호환). `subtasks` 가 비면 자식 없음.
- 루트 워크플로우 상태 후보도 본 작업부터 루트 `issueTypeKey` 로 조회(단건은 null).

### `POST /api/v1/issues/{key}/move`
요청 — 기존 평면 필드(루트) + 선택적 `subtasks` 배열.
```jsonc
{
  "targetProjectKey": "INFRA",
  "expectedVersion": 3,                          // 루트 OCC
  "targetStateKey": "open",
  "targetStateIsDone": false,
  "componentMapping": {"<srcId>": "<targetId>"},
  "affectsVersionMapping": {"<srcId>": "<targetId>"},
  "fixVersionMapping": {"<srcId>": "<targetId>"},
  "customFieldValues": {"severity": "high"},
  "subtasks": [                                  // 신규 — 모든 직접 자식 포함 필수
    {
      "issueKey": "ATLAS-13",                    // 자식 식별(이동 전 옛 키 — preview 응답 issueKey 그대로, path 와 일관)
      "expectedVersion": 1,
      "targetStateKey": "open",
      "targetStateIsDone": false,
      "componentMapping": {...},
      "affectsVersionMapping": {...},
      "fixVersionMapping": {...},
      "customFieldValues": {...}
    }
  ]
}
```
응답 200.
```jsonc
{
  "issueKey": "INFRA-5",
  "previousKey": "ATLAS-12",
  "movedSubtasks": [
    {"previousKey": "ATLAS-13", "issueKey": "INFRA-6"},
    {"previousKey": "ATLAS-14", "issueKey": "INFRA-7"}
  ]
}
```

### cross-BC SPI (재사용, 변경 없음)
`WorkflowStateCatalog.listStates(projectKey, issueTypeKey)` — 노드별 `issueTypeKey` 전달로 호출만 확장.

## 데이터 모델 변경

- **마이그레이션 신규 없음.** `issue_key_redirects`(V001), `issues`/조인테이블 기존 컬럼 UPDATE 재사용.
- 신규 repository 메서드:
  - `IssueRepository.findDirectChildren(parentId): List<Issue>` — 직접 자식 **목록**(현재 `countDirectChildren` 만 존재). 활성(deleted_at IS NULL) 한정
  - 자식 비관락 조회 — 단건 `findByKeyForUpdate` 동형으로 자식도 `SELECT FOR UPDATE` (배치 또는 노드별)
  - (필요 시) 자식 노드의 종속데이터(컴포넌트/버전/커스텀필드) 조회는 기존 `findByKey`/`findByKeyWithType` 또는 배치 조회 재사용
- move 시 각 자식도 `moveIssue`(또는 동형) + 조인테이블 교체 + redirect INSERT. **자식은 `parent_id` 미변경**.
- **동시성**: 루트+자식 전 노드를 `SELECT FOR UPDATE` 비관락. 데드락 회피 위해 **id 오름차순** 으로 락 획득(루트 포함 정렬).

## 엣지 케이스

단건 EC1~EC14 는 **루트 노드에 동일 적용**. 추가/변경분만.

| EC | 상황 | 응답 |
|---|---|---|
| EC15 (재정의) | 자식 있는데 `subtasks` 매핑 불완전(일부 자식 누락) | 422 `INCOMPLETE_SUBTASK_MAPPING` (기존 `ISSUE_HAS_SUBTASKS` 무조건거부 폐기) |
| EC16 (신규) | 직접 자식이 또 자식을 가짐(다단계) | 422 `SUBTASK_HAS_OWN_SUBTASKS` |
| EC17 (신규) | 자식 노드 OCC 충돌(`subtasks[].expectedVersion` 불일치) | 409 — 전체 트리 롤백 |
| EC18 (신규) | 자식 노드 매핑 대상 id 가 대상 프로젝트에 없음 | 422 `INVALID_TARGET_MAPPING`(노드 식별 포함) |
| EC19 (신규) | 자식 노드 상태 비호환인데 `targetStateKey` 미지정/대상에 없음 (자식 타입 워크플로우 미설정으로 `listStates` 빈 목록인 경우 포함) | 422 `INVALID_TARGET_STATE` |
| EC20 (신규) | 자식 노드 대상 필수 커스텀필드 누락 | 422 `REQUIRED_FIELD_MISSING` |
| EC21 (신규) | `subtasks` 에 이동 이슈의 자식이 아닌 id 포함 | 422 `INCOMPLETE_SUBTASK_MAPPING`(미인식 id) |

## 제약 조건

- 직접 자식(1레벨)만 동반. 다단계는 거부(EC16) — 재귀 수집 안 함
- 자식 `parent_id` 불변(부모 id 보존). 루트만 `parent_id=null`
- 키 재사용 금지, redirect append-only, id 보존으로 FK 데이터 자동 보존 — 단건과 동일
- cross-BC SPI 경유, 직접 import 금지
- bulk(여러 루트 동시 이동)은 범위 밖
- move 의 `subtasks` 식별은 `issueKey`(이동 전 옛 키 — preview 응답 `issueKey` 를 그대로 사용, path 와 일관). 루트는 path `{key}`
- 자식 보안수준(security level) 개별 권한은 단건과 **동일하게 미검증** — 트리 1회 원본 `UPDATE` + 대상 `CREATE`(프로젝트 범위)만. 자식별 보안수준 검증은 후속 FR(단건 일관성 유지)

## 측정 가능한 완료 기준

- [ ] preview 가 루트+모든 직접 자식의 비호환 항목을 노드별로 정확히 계산(노드별 `issueTypeKey` 상태 조회)
- [ ] move 가 단일 트랜잭션으로 루트+자식 전 노드 id 보존 + 새 키 발번 + redirect
- [ ] 자식 `parent_id` 유지(이동 후 자식이 이동된 부모를 가리킴), 루트 외부 부모 끊김 검증
- [ ] EC15 재정의(불완전 매핑 422), EC16(다단계 422), EC17(자식 OCC 409) 정확
- [ ] 자식 없는 이슈 이동 = 단건 회귀 보존(S1)
- [ ] 노드별 매핑 대상 미존재/상태 비호환/필수필드 누락 422 (EC18~20)
- [ ] 권한 부족 시 403 (원본 UPDATE / 대상 CREATE) — 트리 1회 검증
- [ ] 이동 후 전 노드 히스토리·링크·워처·첨부 보존

## 범위

- **이번 PR** = 백엔드 D1(도메인 노드별 검증 + 다단계 거부)·D4(preview/move 노드별) 확장. D1·D4 `[~]`→`[x]`.
- **후속 PR** = 프론트 마법사 UI(노드별 매핑 단계) + E2E (D6/D7) — 별도 작업.

## Brainstorming Check

✅ 통과 (1회 iteration). adversarial sanity check 로 발견·보강한 gap.
- **G1 식별자 불일치**: preview 는 자식을 `issueKey`, move 는 `issueId`(UUID)로 식별 → preview 응답에 issueId 부재로 프론트 매핑 불가. **`issueKey` 로 통일**(path 와 일관, 이동 전 옛 키 유효). (FR-S-3 / API / 제약 반영)
- **G2 자식 워크플로우 미설정**: 자식 타입의 대상 워크플로우 스킴 부재 시 `listStates` 빈 목록 → 상태 결정 불가 → **EC19 `INVALID_TARGET_STATE` 로 흡수** 명시.
- **G3 자식 동시성**: 자식도 `SELECT FOR UPDATE` 비관락, 데드락 회피 위해 **id 오름차순 락** (데이터 모델 반영).
- **G4 자식 보안수준 권한**: 단건과 동일하게 **프로젝트 범위 권한만**(자식 개별 보안수준 미검증) — 단건 일관성 유지, 별도 검증은 후속 FR (제약 반영).
- 확인(gap 아님): labels 자유배열·전역 resolution 유지(매핑 불필요), cross-project 링크 id 참조 보존, 노드별 히스토리는 각 노드 원본 projectId 로 기록.
