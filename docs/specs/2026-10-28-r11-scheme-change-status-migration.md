# R11 스킴 교체 · 유형 재배정 상태 이관 스펙 (FR-WF-02 · FR-WF-07)

대상: FR-WF-02(스킴 · 유형별 매핑)와 FR-WF-07(초안 · 발행 · 상태 이관 마법사)의 **범위 변경**. 새 FR 을 만들지 않는다(FR 총수 불변).
근거: 결정 D4(`docs/decisions/2026-10-28-jira-cloud-rea-parity-decisions.md`) · 갭 JCW-1(`docs/design/2026-10-28-jira-cloud-rea-gap.md`, REA 관찰 2026-10-28 · 결정 D1) · 워크플로 spec `docs/specs/2026-10-20-workflow-jira-parity-gap.md` §6 순 4(B5 · G14).
로드맵: `docs/design/jira-parity-roadmap.md` 「2차 캠페인」 R11 행. BTS 기준 `main` `974cf8856`.
Maxi 승인 범위(2026-10-28): **단계 A + B**. 초안은 **화면 안**(서버 테이블 없음 · 마이그레이션 0).

## 1. 지금 있는 것

| 구분 | 동작 | 정본 |
|---|---|---|
| 스킴 매핑 편집 | 매핑 추가 · 삭제가 한 건씩 즉시 반영 | `WorkflowSchemeController` `POST/DELETE /workflow-schemes/{k}/mappings` |
| 프로젝트 스킴 교체 | 배정 UPSERT 한 번 | `ProjectWorkflowSchemeController` `PUT /projects/{p}/workflow-scheme` |
| 남는 이슈 | 새 워크플로에 없는 상태에 이슈가 남으면 **409 `SCHEME_CHANGE_STRANDS_ISSUES`** — 옮기지 않고 막는다 | `WorkflowSchemeApplicationService.requireNoStrandedIssues` (#524 · FR-WF-02 D8) |
| 이관 실행 | `StatusMigrationCommand`(프로젝트별 유형 필터 + 출발 → 도착 매핑) → issue-tracking `bulk_operations` STATUS_MIGRATION | shared-kernel `IssueStatusMigrationPort` (FR-WF-07 D2 · D10) |
| 워크플로 편집 | 초안(JSONB) 자동저장 → 발행 · 빠지는 상태는 이관 마법사 | FR-WF-07 D1~D8 |

Jira Cloud: 활성 스킴 편집은 초안 스킴 → 「게시」. 게시 1/2단계에서 **업무 유형별로** 옛 상태 전부를 새 상태에 매핑, 2/2단계에서 비동기 진행률. 프로젝트 스킴 교체도 같은 마법사.

## 2. 단계

### 단계 A — project-workflow API (PR 1)

| ID | 동작 | 결정 |
|---|---|---|
| A1 | `POST /api/v1/workflow-schemes/{schemeKey}/publish` `{mappings:[{issueTypeKey?, workflowKey}], statusMappings?:[{issueTypeId, fromStatusKey, toStatusKey}]}` — 매핑 전체를 한 번에 교체한다 | 권한 MANAGE_SCHEME. 기본 매핑(`issueTypeKey` null) 정확히 1개 · 유형 중복 없음, 아니면 400 `SCHEME_MAPPINGS_INVALID`. 교체는 차집합만 지우고 더한다 |
| A2 | `POST /api/v1/projects/{projectKey}/workflow-scheme/switch` `{schemeKey, statusMappings?}` — 스킴 교체 + 이관 | 권한 · 가드 순서는 기존 `PUT` 과 같다(인증 → ASSIGN_SCHEME → 프로젝트 조회). 기존 `PUT` 은 그대로 둔다(남으면 409) |
| A3 | 남는 이슈가 있고 `statusMappings` 가 그 (유형, 상태)를 다 덮지 않으면 409 `SCHEME_CHANGE_STRANDS_ISSUES` + **`strandedByType`** `[{issueTypeId, workflowKey, statuses:{statusKey: count}}]` — 마법사 1/2 의 입력 | 기존 `strandedByStatus` 도 그대로 싣는다(하위 호환). 도착 상태가 그 유형의 새 워크플로에 없으면 400 `SCHEME_STATUS_MAPPING_INVALID` |
| A4 | 다 덮이면 **같은 트랜잭션**에서 매핑(또는 배정)을 바꾸고 **바뀌는 유형마다** `StatusMigrationCommand` 1건을 큐잉 → 200 `{bulkOperationIds:[…]}` | shared-kernel 무변경 — 유형 필터 `{issueTypeIds:{t}, exceptListed:false}` 로 좁힌다. 매핑에는 남는 상태만 싣는다(출발 ∉ 새 워크플로 · 도착 ∈ 새 워크플로라 E1 · E17 연쇄가 생기지 않는다) |
| A5 | 범위에 **아카이브 프로젝트를 넣는다**(게시 A1) | 이제 이관 경로가 있어 아카이브 이슈도 옮길 수 있다(워커는 아카이브 가드를 건너뛴다 · WF-07-4). 기존 매핑 추가 · 삭제 API 는 이관이 없어 그대로 아카이브를 뺀다 |
| A6 | 끝나지 않은 이관이 범위 프로젝트에 있으면 409 `SCHEME_MIGRATION_IN_FLIGHT` | 발행 이관(F15)과 같은 판정 포트 |

순서: 매핑 교체 → 큐잉(같은 tx, pgmq MANDATORY). 이관이 끝나는 몇 초 동안 그 이슈는 새 워크플로에 없는 상태라 전환이 실패할 수 있다(승인된 잔여). 판정과 COMMIT 사이에 들어온 전환은 잡지 못한다 — #524 · 발행 차단과 같은 잔여 창이다. 큐잉 뒤 그 상태로 들어온 이슈는 워커가 실행 시점에 재조회해 함께 옮긴다.

### 단계 B — web 마법사 (PR 2)

| ID | 동작 |
|---|---|
| B1 | 스킴 편집 화면: 매핑 추가 · 삭제 · 워크플로 변경이 **화면 안 초안**에 쌓인다. 「게시」 · 「초안 폐기」 버튼, 변경이 있을 때만 활성 |
| B2 | 「게시」 → A1. 409 `strandedByType` 이면 마법사 1/2 — 유형별로 남는 상태마다 도착 상태 select(새 워크플로 상태). 「다음」 → `statusMappings` 를 실어 재요청 |
| B3 | 마법사 2/2 — 받은 `bulkOperationIds` 진행률(기존 bulk-operation 폴링) · 끝나면 닫기 |
| B4 | 프로젝트 설정 → 워크플로 스킴 교체도 A2 + 같은 마법사 |

### 단계 C — 워크플로 편집 즉시 반영 (다음 구간 · 이번 범위 밖)

편집기 「발행」 → 「워크플로 업데이트」 한 번(이관 끝나면 자동 발행) · 상태 삭제 시 「기본 대상 + 프로젝트 × 유형 예외」 모달. 백엔드 `migrate` 가 예외를 받아 명령 여러 건으로 나눈다(shared-kernel 무변경 가능).

## 3. 편차

- 초안이 서버에 남지 않는다 — 페이지를 떠나면 사라진다(Jira 는 서버 초안 · Maxi 결정 2026-10-28).
- 진행률이 유형마다 작업 1건이라 여러 개다 — 화면은 합쳐 보인다.
- 워크플로 편집의 초안 · 발행(FR-WF-07 D1~D8)은 단계 C 전까지 그대로다.
