# ADR: 칸반 보드 WIP 제한 + 스윔레인 (FR-BD-03)

> 날짜: 2026-06-22
> 상태: 채택
> 범위: agile-planning BC, FR-BD-03 백엔드 (D1~D5)
> 관련 SDD: §13.1.1 (WIP 제한 = 컬럼당 최대 이슈 수), §13.1.3 (스윔레인 = 담당자/Epic/우선순위별 가로 분리)
> 선행 ADR: [2026-06-20-fr-bd-01-agile-planning-bootstrap](2026-06-20-fr-bd-01-agile-planning-bootstrap.md)
> 관련 PR: #172

## 맥락

FR-BD-01(칸반 보드)·FR-BD-02(보드 필터)가 완료됐다. 보드는 `boards`(메타) + `board_columns`(워크플로우 상태 1:1 매핑) 구조이며, 백엔드가 `placeCards`로 컬럼별 카드 배치를 완성해 `BoardDetailResponse`(columns + truncated + unplacedCount)로 응답한다. 카드 뷰(`BoardIssueView`)는 key·summary·currentStateKey·assigneeId·priority·version 필드를 노출한다.

FR-BD-03은 두 가지를 추가한다. (1) **WIP 제한** — 컬럼당 최대 카드 수. (2) **스윔레인** — 보드를 담당자/Epic/우선순위별로 가로 분리.

## 결정 1 — WIP 제한 = 컬럼 속성(nullable), 경고 전용

`board_columns.wip_limit`(INTEGER NULL)을 추가한다. null = 제한 없음. 양수만 허용. 보드 단건 조회 응답의 각 컬럼에 `wipLimit`(설정값)과 `wipExceeded`(현재 카드 수 > wipLimit) 신호를 포함한다. **WIP 초과 시 카드 이동을 차단하지 않는다** — 백엔드는 경고 신호만 응답하고, 시각 경고는 프론트(D6)가 컬럼 헤더에 표시한다.

**근거**. product 명세 §2.3 D2 "WIP 초과 시 시각 경고만(이동 차단 옵션)". Jira 기본 동작도 경고 전용이다. 이동 차단은 전환 위임 경로(`IssueTransitionPort`)에 추가 가드가 필요해 범위·복잡도를 키운다. 차단 모드는 후속 FR로 명시 이연(Maxi 확정).

## 결정 2 — 스윔레인 = 보드 속성, 그룹핑은 프론트

`boards.swimlane_field`(VARCHAR, NOT NULL DEFAULT `'NONE'`)를 추가한다. 허용 값 = `NONE` · `ASSIGNEE` · `PRIORITY`. 백엔드는 이 설정값을 저장하고 보드 응답에 echo한다. **실제 스윔레인 그룹핑(가로 분리)은 프론트(D6)가 수행한다** — 카드의 `assigneeId`·`priority`가 이미 응답에 노출돼 있어 프론트가 group by 할 수 있고, 담당자 displayName은 기존 사용자 조회 패턴을 재사용한다.

**근거**. 스윔레인은 표시 방식이라 프론트 관심사에 가깝다. 백엔드가 컬럼×스윔레인 2차원 그룹핑을 완성하면 그룹 라벨(담당자 이름 등) cross-BC 조회 부담이 커지고 응답 구조가 복잡해진다. 그룹핑 키는 이미 카드에 있으므로 설정값만 노출하는 것이 가볍고 BC 격리 친화적이다(Maxi 확정).

## 결정 3 — Epic 스윔레인 이연 (enum 미포함)

SDD §13.1.3은 스윔레인 기준으로 담당자/Epic/우선순위를 든다. 그러나 Epic은 FR-EP(agile-planning §7)가 미구현이라 카드(`BoardIssueView`)에 epic 데이터가 없다. 이번 FR-BD-03은 **ASSIGNEE/PRIORITY/NONE만 지원**하며, `swimlane_field` enum에 `EPIC`을 두지 않는다. Epic 스윔레인은 FR-EP 완료 후 별도로 추가한다.

**근거**. 미구현 데이터를 enum에 두면 가짜 옵션이 UI에 노출되거나 빈 그룹/거부 처리가 필요해진다. 데이터가 있는 기준만 지원하는 것이 정직하다(Maxi 확정). enum 추가는 FR-EP 시점에 카운트 가드와 함께 1회 처리.

## 결정 4 — 범위 = 백엔드 D1~D5

이번 작업은 백엔드(스키마 + 도메인 + 설정 API + 조회 응답 확장 + 테스트)로 한정한다. 프론트 D6(컬럼 헤더 WIP 경고 + 스윔레인 그룹 UI)/D7(E2E)은 후속 PR.

**근거**. FR-BD-01/02 동일 패턴(백엔드/프론트 분리). product §2.3 D6 책임도 `designer → frontend-engineer`로 분리 명시.

## 대안

- **WIP 초과 시 이동 차단** — 전환 위임 경로 추가 가드 + 범위 확대. 기각(결정 1, 경고 전용).
- **백엔드 2차원 그룹핑** — 그룹 라벨 cross-BC 조회 부담 + 응답 복잡. 기각(결정 2, 프론트 그룹핑).
- **EPIC enum forward-looking 포함** — 미구현 데이터의 가짜 옵션 노출. 기각(결정 3).

## 결과

- `board_columns.wip_limit`(nullable) + `boards.swimlane_field`(NONE/ASSIGNEE/PRIORITY) 스키마 추가.
- 보드 응답에 컬럼별 `wipLimit`/`wipExceeded` + 보드 `swimlaneField` echo 추가.
- WIP는 경고 신호 전용(이동 무차단), 스윔레인 그룹핑은 프론트 위임, Epic 스윔레인은 FR-EP로 이연.
- 설정 쓰기 API(wip_limit · swimlane_field)는 spec 단계에서 엔드포인트·권한 확정.
