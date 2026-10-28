# R11 스킴 교체 · 유형 재배정 상태 이관 스펙 (FR-WF-02 · FR-WF-07)

대상: FR-WF-02(스킴 · 유형별 매핑)와 FR-WF-07(초안 · 발행 · 상태 이관 마법사)의 **범위 변경**. 새 FR 을 만들지 않는다(FR 총수 불변).
근거: 결정 D4(`docs/decisions/2026-10-28-jira-cloud-rea-parity-decisions.md`) · 갭 JCW-1(`docs/design/2026-10-28-jira-cloud-rea-gap.md`, REA 관찰 2026-10-28 · 결정 D1) · 워크플로 spec `docs/specs/2026-10-20-workflow-jira-parity-gap.md` §6 순 4(B5 · G14).
로드맵: `docs/design/jira-parity-roadmap.md` 「2차 캠페인」 R11 행. BTS 기준 `main` `974cf8856`.
Maxi 승인 범위(2026-10-28): **단계 A + B**(R11), **단계 C**(R11C). 스킴 초안은 **화면 안**(서버 테이블 없음 · 마이그레이션 0).

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

직렬화: 게시 · 교체는 첫 읽기 전에 스킴 변경 전역 트랜잭션 advisory lock 을 잡는다(같은 프로젝트 동시 교체 · 게시와 교체 겹침 차단). 워크플로우가 바뀌는 유형이 하나라도 있으면 남는 이슈가 없어도 진행 중 이관을 본다(끝난 이관의 도착 상태가 새 워크플로우에 없을 수 있다). 같은 (유형, 출발 상태)에 매핑이 둘이면 400.

순서: 매핑 교체 → 큐잉(같은 tx, pgmq MANDATORY). 이관이 끝나는 몇 초 동안 그 이슈는 새 워크플로에 없는 상태라 전환이 실패할 수 있다(승인된 잔여). 판정과 COMMIT 사이에 들어온 전환은 잡지 못한다 — #524 · 발행 차단과 같은 잔여 창이다. 큐잉 뒤 그 상태로 들어온 이슈는 워커가 실행 시점에 재조회해 함께 옮긴다.

### 단계 B — web 마법사 (PR 2)

| ID | 동작 |
|---|---|
| B1 | 스킴 편집 화면: 매핑 추가 · 삭제 · 워크플로 변경이 **화면 안 초안**에 쌓인다. 「게시」 · 「초안 폐기」 버튼, 변경이 있을 때만 활성 |
| B2 | 「게시」 → A1. 409 `strandedByType` 이면 마법사 1/2 — 유형별로 남는 상태마다 도착 상태 select(새 워크플로 상태). 「다음」 → `statusMappings` 를 실어 재요청 |
| B3 | 마법사 2/2 — 받은 `bulkOperationIds` 진행률(기존 bulk-operation 폴링) · 끝나면 닫기 |
| B4 | 프로젝트 설정 → 워크플로 스킴 교체도 A2 + 같은 마법사 |

### 단계 C — 워크플로 편집 즉시 반영 (R11C · Maxi 승인 2026-10-28)

Jira Cloud(H7): 편집기에서 상태를 지우고 「워크플로 업데이트」를 누르면 모달 「업무 항목을 새 상태로 이동」 — 지운 상태마다 기본 대상 1개(같은 범주 상태가 기본 선택) + 「예외 추가」(스페이스 · 그 스페이스의 업무 유형 · 대상).

| ID | 동작 | 결정 |
|---|---|---|
| C1 | `POST /api/v1/workflows/{key}/publish/migrate` 가 `exceptions?:[{projectKey, issueTypeId, fromStatusKey, toStatusKey}]` 를 받는다. 응답에 `bulkOperationIds:[…]` 를 더한다(`bulkOperationId` = 첫 id, 하위 호환) | 명령을 **기본 1건**(예외 (프로젝트, 유형)을 그 프로젝트 필터에서 뺀 범위 · `mappings` 그대로) + **예외 (프로젝트, 유형)마다 1건**(`{issueTypeIds:{t}, exceptListed:false}` · 기본 매핑에 예외를 덮은 목록)으로 쪼갠다. 범위가 겹치지 않는다. 기본 범위가 다 빠지면 기본 명령은 없다. shared-kernel 무변경 |
| C2 | 예외 검증 — 하나라도 어기면 400 `WORKFLOW_MIGRATION_INVALID_MAPPING`, 포트 미호출 | 출발 상태에 기본 대상이 있어야 한다 · 도착은 초안과 지금 편성에 함께 있어야 한다(F8 · F16 과 같은 축) · 프로젝트가 이 워크플로우 범위 안이고 그 유형을 이 워크플로우가 맡아야 한다 · (프로젝트, 유형, 출발) 중복 금지 |
| C3 | `POST /publish/preview` 응답에 `scopes:[{projectKey, issueTypeIds, exceptListed}]` — 예외 선택지의 근거 | 이관 범위와 같은 조회(`findMigrationScopesByWorkflowId`) 1회. 유형 이름은 화면이 전역 이슈 타입 목록으로 푼다 |
| C4 | web: 편집기의 「발행」 → 「워크플로 업데이트」. 누르면 저장 flush → preview → 이슈가 남는 상태가 없으면 바로 발행, 있으면 C5 모달 | 서버 초안 테이블 · API 는 편집기 자동저장 버퍼로 그대로 둔다(마이그레이션 0). 화면에서 「초안」 · 「발행」 문구를 걷고 「초안 폐기」는 「변경 취소」 |
| C5 | 모달 「업무 항목을 새 상태로 이동」 — 지운 상태마다 기본 대상 select(같은 범주 상태 기본 선택) + 「예외 추가」 행(프로젝트 · 유형 · 대상 · 「예외 제거」). 「워크플로 업데이트」 → C1 → 진행률(작업 여러 건 합산) → **전부 COMPLETED · 실패 0 이면 자동 발행** | 일부 실패면 자동 발행하지 않고 오류와 함께 다시 누르게 한다(발행이 409 로 되돌아오는 것을 사용자가 누르지 않은 조작으로 보지 않게) |

FR-WF-07 은 제목 · 범위가 「워크플로우 업데이트(편집 버퍼 → 한 번에 반영) + 상태 이관 모달(기본 대상 + 프로젝트 × 유형 예외)」로 바뀐다(fr-sync — web PR).

## 3. 잔여 (리뷰 2026-10-28 · 이번에 닫지 않음)

- 워크플로우 발행 이관(F15 락)과 스킴 변경 락은 키 공간이 달라, 둘이 동시에 진행 중 이관을 판정하면 서로의 미커밋 큐잉을 못 본다.
- `resolveMappingKeys` 가 `WorkflowRepository.findIdByKey` 를 그대로 쓴다(선재) — 전역 · 프로젝트 소유 같은 키 공존 시 500, 다른 프로젝트 소유 워크플로우를 매핑할 수 있다. 기존 매핑 추가 API 와 같은 구멍이고 이관이 붙어 영향이 커졌다 — 스코프 검사는 별도 작업.
- 기존 `PUT` · 매핑 추가/삭제 API 는 락 없이 그대로 409 판정만 한다.

## 4. 편차

- 초안이 서버에 남지 않는다 — 페이지를 떠나면 사라진다(Jira 는 서버 초안 · Maxi 결정 2026-10-28).
- 진행률이 유형마다 작업 1건이라 여러 개다 — 화면은 합쳐 보인다.
- 단계 C 의 편집 버퍼는 서버(`workflow_drafts`)에 자동저장된다 — Jira 편집기는 「업데이트」 전 변경을 서버에 남기지 않는다. 새로고침에도 편집이 살아남는 쪽을 택했다.
- 단계 C 의 예외 명령은 (프로젝트, 유형)마다 작업 1건이다 — 진행률은 화면이 합친다.
