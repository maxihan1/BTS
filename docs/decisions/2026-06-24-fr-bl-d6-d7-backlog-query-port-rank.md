<!-- FR-BL-01/02 D6/D7 백로그 조회 API BC 위치 + 포트 rank 확장 결정 -->
# ADR — FR-BL D6/D7 백로그 조회 API 위치 + BoardIssueLookupPort rank 확장

> 날짜: 2026-06-24
> 상태: 채택
> 관련 FR: FR-BL-01(백로그 LexoRank, #179) · FR-BL-02(백로그→스프린트, #182) D6/D7
> 관련 ADR: [lexorank-backlog-ordering](2026-06-23-fr-bl-01-lexorank-backlog-ordering.md) · [sprint-issue-association](2026-06-24-fr-bl-02-sprint-issue-association.md)
> 관련 plan: docs/plans/2026-06-24-fr-bl-01-02-d6-d7-backlog-sprint-ui-e2e.md

## 맥락

FR-BL-01/02의 D6(프론트)/D7(E2E)을 구현하려면 "백로그(스프린트 미할당 이슈)를 rank 순으로 조회하는 API"가 필요하다. 이 API는 두 BC의 데이터를 교차한다.

- **rank** = issue-tracking 소유(`issues.rank`, FR-BL-01 ADR 결정 1).
- **스프린트 할당 여부** = agile-planning 소유(`sprint_issues(sprint_id, issue_key)`, FR-BL-02 ADR).

현재 cross-BC 포트 `BoardIssueLookupPort.BoardIssueView`는 rank를 노출하지 않는다(key/summary/currentStateKey/assigneeId/priority/version/epicKey만). FR-BL-02 ADR이 "스프린트 내 이슈 순서(rank) = **D6 이연**(포트가 rank 미노출)"으로 이 확장을 명시 예고했다.

## 결정 1 — 백로그 조회 API는 agile-planning BC가 소유

백로그 조회 엔드포인트를 agile-planning BC(신규 `BacklogController` 또는 `SprintController` 인접)가 제공한다. issue-tracking에 백로그 엔드포인트를 두지 않는다.

**근거**.
- "백로그 = 미할당 이슈 목록"(glossary)이고 "미할당"의 기준은 `sprint_issues`(agile-planning 데이터)다. 백로그 계산(`listVisibleIssuesByProject` keys − `sprint_issues` keys)은 FR-BL-02 ADR이 이미 agile-planning에 귀속시켰다.
- issue-tracking이 백로그를 제공하면 `sprint_issues`(agile-planning)를 역방향 의존해야 한다. board의 의존 방향(agile-planning ──port──▶ shared-kernel ◀──impl── issue-tracking)을 깬다.
- glossary/domain 노트가 "백로그 정렬(LexoRank)"을 agile-planning 책임으로 명시.

## 결정 2 — BoardIssueLookupPort에 rank 노출 확장 (default-safe)

`BoardIssueView`에 `rank: String?` 필드를 추가하고, issue-tracking의 `BoardIssueLookupAdapter`가 `issues.rank`를 SELECT해 채운다. agile-planning은 이 rank로 백로그/스프린트 이슈를 정렬한다.

**근거 · 안전장치**.
- FR-BL-02 ADR이 예고한 D6 확장의 실현. board view-layer(read) 확장의 연장이므로 issue-tracking 무변경 원칙의 정당한 예외(FR-BL-02 `isVisibleIssue` 추가와 동류).
- 신규 필드는 `rank: String? = null` **기본값**, 포트 메서드 시그니처는 가능하면 유지. 인라인 fake/미override adapter는 rank=null로 안전 동작(메모리 [[interface-extension-default-method]] — default=fail-safe 방향).
- 정렬 규칙 = `rank ASC NULLS LAST, created_at`(FR-BL-01 Deviation: 신규 이슈 rank=NULL lazy 부여).

## 결정 3 — 재정렬/할당/해제는 기존 API 재사용 (신규 백엔드 최소화)

D6 드래그 동작은 이미 구현된 API에 위임한다. 신규 백엔드는 "백로그/스프린트 이슈 **조회**"(읽기)뿐이다.

- 백로그 내 rank 재정렬 → `PATCH /api/v1/issues/{key}/rank`(#179, issue-tracking).
- 백로그 → 스프린트 할당 → `POST /api/v1/sprints/{id}/issues`(#182).
- 스프린트 → 백로그 해제 → `DELETE /api/v1/sprints/{id}/issues/{issueKey}`(#182).

마이그레이션 0, 신규 도메인 엔티티 0, 신규 glossary 용어 0.

## 대안

- **issue-tracking이 `GET /api/v1/issues?backlog=true` 제공** — rank가 issue-tracking이라 단순하나, "미할당" 판정에 agile-planning `sprint_issues` 역방향 의존 필요 → BC 격리 위반. 기각.
- **포트 미확장 + agile-planning이 rank 별도 조회** — agile-planning이 issues.rank를 직접 읽으려면 issue-tracking 테이블 접근 필요(ArchUnit 위반). 기각.
- **백로그 정렬을 priority로 대체** — 포트가 이미 priority 노출. 그러나 FR-BL-01의 핵심이 LexoRank 자유 재배치이고 rank PATCH가 이미 존재 → priority 정렬은 기능 후퇴. 기각.

## 결과

- agile-planning: 백로그 조회 service/controller 신설(읽기 전용) + `sprint_issues` 기준 미할당 필터.
- shared-kernel: `BoardIssueView.rank: String?` 추가(default null).
- issue-tracking: `BoardIssueLookupAdapter`가 rank SELECT(read view 확장만, 도메인/테이블 무변경).
- 스프린트별 이슈 순서 정렬 포함 여부, 페이징(1K 가상 스크롤), `@dnd-kit/sortable` 추가 여부는 spec에서 확정.
