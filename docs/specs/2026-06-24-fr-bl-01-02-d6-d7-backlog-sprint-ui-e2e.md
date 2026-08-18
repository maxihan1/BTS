<!-- FR-BL-01/02 D6/D7 백로그↔스프린트 보드 UI + 백로그 조회 API 스펙 -->
# FR-BL-01+FR-BL-02 D6/D7 — 백로그↔스프린트 보드 UI + 백로그 조회 API — 스펙

> slug: fr-bl-01-02-d6-d7-backlog-sprint-ui-e2e · type: ui · BC: agile-planning(주)+issue-tracking(포트 rank read)+shared-kernel
> 선행 백엔드: rank PATCH(#179) · sprint CRUD/start/complete/할당/해제(#182)
> ADR: 2026-06-24-fr-bl-d6-d7-backlog-query-port-rank.md
> 범위 확정(Maxi 게이트): 스프린트 내 재정렬 **포함** · 라이프사이클(생성/시작/완료) UI **전체 포함**

## 개요

프로젝트의 백로그 계획 화면 한 페이지에서 (1) 백로그(미할당 이슈)를 rank 순으로 보고 드래그로 우선순위를 재배치하고, (2) 스프린트를 만들고/시작/완료하며, (3) 이슈를 백로그↔스프린트, 스프린트↔스프린트로 드래그 이동하고, (4) 스프린트 안에서도 순서를 재배치한다.

핵심 원칙 — **신규 백엔드는 "조회"뿐**. 모든 변경(rank·할당·해제·스프린트 생성/전환)은 이미 구현된 API에 위임한다.

## 사용자 시나리오 (Given-When-Then)

- **S1 백로그 재정렬**. Given 백로그에 rank 순 이슈 목록이 있고, When 사용자가 한 카드를 다른 두 카드 사이로 드래그하면, Then 그 위/아래 이웃 사이로 이동(서버 LexoRank between)하고 새 순서가 유지된다.
- **S2 백로그→스프린트 할당**. Given 백로그 카드와 PLANNED/ACTIVE 스프린트 칸, When 카드를 스프린트 칸의 특정 위치로 드롭하면, Then 그 이슈가 스프린트에 할당되고 그 위치(rank)로 배치된다.
- **S3 스프린트→백로그 해제**. Given 스프린트에 담긴 카드, When 백로그 칸으로 드롭하면, Then 스프린트 연관이 제거되고 백로그의 드롭 위치로 배치된다.
- **S4 스프린트↔스프린트 이동**. Given 스프린트 A의 카드, When 스프린트 B로 드롭하면, Then A 연관이 제거되고 B에 할당된다(UNIQUE(issue_key) 원자 이동) + B의 드롭 위치로 배치.
- **S5 스프린트 내 재정렬**. Given 한 스프린트 안 여러 카드, When 같은 스프린트 안에서 위치를 바꾸면, Then 그 스프린트 내 이웃 사이로 rank 변경된다.
- **S6 스프린트 생성**. Given 프로젝트, When 이름/목표/기간으로 "스프린트 만들기"를 제출하면, Then PLANNED 스프린트가 생기고 화면에 새 칸으로 나타난다.
- **S7 스프린트 시작/완료**. Given PLANNED 스프린트, When "시작"하면 ACTIVE로, ACTIVE에서 "완료"하면 COMPLETED로 전환된다(단방향). COMPLETED 스프린트엔 할당/해제/재정렬 불가.
- **S8 권한 게이팅**. BROWSE 없으면 화면 접근 불가(403). 재정렬/할당/해제는 issue UPDATE+sprint UPDATE, 스프린트 생성/전환은 CREATE 권한 없으면 버튼 비활성+서버 403.

## 기능 요구사항 (FR)

- **F1**. 백로그 보드 조회 — 프로젝트의 가시 이슈를 백로그(미할당)와 각 스프린트별로 그룹핑해 rank 순으로 한 번에 반환(신규 API).
- **F2**. 백로그 카드 드래그 재정렬 → `PATCH /issues/{key}/rank`(prev/next = 백로그 이웃).
- **F3**. 스프린트 내 카드 드래그 재정렬 → `PATCH /issues/{key}/rank`(prev/next = 해당 스프린트 이웃).
- **F4**. 백로그→스프린트 / 스프린트↔스프린트 이동 → `POST /sprints/{id}/issues`(할당, 재할당 원자) + 드롭 위치가 양 끝이 아니면 이어서 `PATCH /rank`.
- **F5**. 스프린트→백로그 해제 → `DELETE /sprints/{id}/issues/{issueKey}` + 드롭 위치 `PATCH /rank`.
- **F6**. 스프린트 생성 폼 → `POST /sprints`(이름 필수, 목표/기간 옵션).
- **F7**. 스프린트 시작/완료 버튼 → `POST /sprints/{id}/start` · `POST /sprints/{id}/complete`(상태 따라 노출).
- **F8**. 권한 게이팅 — 버튼/드래그를 `MyProjectPermission` 요약으로 게이팅(선례: ui-permission-gating-needs-summary-api-exposure). 서버는 항상 재검증.
- **F9**. truncated 신호 — F1 응답의 truncated=true면 "일부 이슈가 표시되지 않음" 경고 배너(board 선례 동일).

## 비기능 요구사항 (NFR)

- **N1**. 페이징/가상 스크롤 — board 선례(`BOARD_CARD_FETCH_LIMIT` + truncated 플래그) 동일. 별도 가상 스크롤 라이브러리 미도입(product §1.2 "1K 가상 스크롤"은 board가 truncated 방식으로 대체한 선례 따름).
- **N2**. 드래그 라이브러리 — `@dnd-kit/core`만(board 선례). `@dnd-kit/sortable` 추가하지 않음(새 의존성 회피).
- **N3**. 낙관적 업데이트는 invalidate-only 패턴(setQueryData 부분응답 플리커 회피, 메모리 mutation-setquerydata-partial-response-flicker). 드래그 후 `invalidateQueries`로 재조회.
- **N4**. rank no-bump(version 불변) — 재정렬은 OCC 충돌 안 일으킴. 할당/해제는 sprint_issues 변경만.

## API 인터페이스 (REST)

### 신규 — 백로그 보드 조회 (agile-planning)

```
GET /api/v1/projects/{projectKey}/backlog
권한: BROWSE
200 OK {
  data: {
    backlog: [ BacklogIssue ],            // 미할당, rank 순
    sprints: [ { sprint: SprintMeta, issues: [ BacklogIssue ] } ],  // 스프린트별, rank 순
    truncated: boolean
  }
}
BacklogIssue = { key, summary, currentStateKey, assigneeId?, priority, rank?, version, epicKey? }
SprintMeta   = { sprintId, name, goal?, status, startDate?, endDate?, version }
```
- 정렬: `rank ASC NULLS LAST, created_at`. rank=null(미부여) 이슈는 맨 뒤.
- 구현: `BoardIssueLookupPort.listVisibleIssuesByProject`(viewer 가시성 필터) 결과를 `sprint_issues`로 그룹핑. **포트에 rank 노출 확장 필요**(BoardIssueView.rank).
- 스프린트 정렬: status(ACTIVE→PLANNED→COMPLETED) 후 startDate. (스펙 확정, 표시 순서)

### 기존 재사용 (신규 백엔드 아님)

| 동작 | API | PR |
|---|---|---|
| 재정렬 | `PATCH /api/v1/issues/{key}/rank` `{previousIssueKey?, nextIssueKey?}` | #179 |
| 할당/재할당 | `POST /api/v1/sprints/{id}/issues` `{issueKey}` | #182 |
| 해제 | `DELETE /api/v1/sprints/{id}/issues/{issueKey}` | #182 |
| 스프린트 생성 | `POST /api/v1/sprints` | #182 |
| 시작/완료 | `POST /api/v1/sprints/{id}/start` · `/complete` | #182 |

### 포트 확장 (shared-kernel + issue-tracking)

- `BoardIssueView`에 `rank: String? = null` 추가(default → 인라인 fake/미override adapter 안전).
- `BoardIssueLookupAdapter`(issue-tracking) SELECT에 `issues.rank` 추가. 도메인/테이블 무변경(read view 확장).

## 데이터 모델 변경

- **DB 스키마 변경 0** (issues.rank·sprints·sprint_issues 전부 존재).
- VO만: `BoardIssueView.rank` 필드 추가.

## 엣지 케이스

- **E1**. 드래그 prev/next 둘 다 없음(빈 칸에 첫 카드) — 이동만 수행, rank PATCH 생략(서버 400 회피). 미할당↔할당만 변경.
- **E2**. COMPLETED 스프린트 대상 할당/해제/재정렬 — 서버가 거부(#182 COMPLETED 가드). 프론트는 COMPLETED 칸을 드롭 불가(droppable 비활성)로 표시.
- **E3**. rank=null 이슈를 다른 null 이슈 옆으로 드롭 — 이웃 키가 유효하면 between 정상. 이웃이 null rank면 서버 rerank가 처리(이웃 rank 조회). (스프린트 할당 시 신규 rank 부여 흐름 plan에서 확인.)
- **E4**. 동시 드래그(다른 사용자가 같은 이슈 이동) — rank no-bump last-write-wins, 할당은 sprint_issues UNIQUE 원자. invalidate 재조회로 최종 상태 수렴.
- **E5**. truncated=true — 경고 배너 + 드래그는 표시된 카드 범위 내에서만 동작(이웃 키가 표시 목록 기준).
- **E6**. 권한 없는 사용자가 드래그 시도 — 버튼/드래그 비활성(F8) + 서버 403 시 토스트 + invalidate 롤백.
- **E7**. 스프린트 0개 — 백로그만 표시 + "스프린트 만들기" CTA. 할당 대상 없음.

## 제약 조건

- BC 격리 — agile-planning은 issue-tracking 직접 import 0(shared-kernel 포트만, ArchUnit). 포트 확장은 board view-layer 확장의 연장.
- 인증 — issue-tracking은 `apiFetch`만(CSRF), agile-planning 프론트 api 관례는 board 선례(`apiGet/apiPost/apiFetch` + dataResponseSchema local) 따름(메모리 frontend-api-convention-per-bc).
- 신규 의존성 0.

## 측정 가능한 완료 기준

- [ ] `GET /projects/{key}/backlog`가 백로그+스프린트별 이슈를 rank 순으로 반환, viewer 가시성 필터, truncated 신호. 통합 테스트(가시성/그룹핑/정렬/truncated).
- [ ] BoardIssueView.rank 확장 + adapter SELECT, 기존 board 테스트 회귀 0.
- [ ] 백로그 페이지: 백로그+스프린트 칸 렌더, 드래그 5 시나리오(S1~S5) 동작, 라이프사이클(S6/S7) 버튼.
- [ ] 권한 게이팅(S8) — 무권한 버튼 비활성 + 서버 403 처리.
- [ ] E2E: 백로그 재정렬 / 백로그→스프린트 / 스프린트→백로그 / 스프린트 내 재정렬 / 스프린트 생성·시작. (실드래그 PointerSensor, MSW 시드)
- [ ] product agile-planning.md §3.1·§3.2 D6/D7 [x] 마킹 + 대시보드 재생성 + verify-master-plan PASS.
- [ ] 프론트 vitest/typecheck/lint, 백엔드 모듈 test/ktlint/detekt 그린.
