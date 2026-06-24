# FR-BL-01+FR-BL-02 D6/D7 — 백로그↔스프린트 프론트 UI + 백로그 조회 API + E2E

> slug: fr-bl-01-02-d6-d7-backlog-sprint-ui-e2e
> type: ui
> agent: frontend-engineer (백엔드 조회 API task=backend-engineer, E2E task=qa-engineer plan 메타)
> primary_bc: agile-planning
> 생성: 2026-06-24

## Brief

FR-BL-01(백로그 우선순위 LexoRank, 백엔드 #179)·FR-BL-02(백로그→스프린트 이동, 백엔드 #182)의
D6(프론트 UI)/D7(E2E)를 통합 구현. 양쪽 PR Deviation이 D6/D7과 "백로그 조회 API"를 본 작업으로 이연.

선행 백엔드 완료 상태.
- FR-BL-01 D5(#179): `PATCH /api/v1/issues/{key}/rank` (LexoRank rank 변경, no-bump, on-demand rebalance)
- FR-BL-02 D5(#182): `SprintController` — 스프린트 CRUD + start/complete + 이슈 할당(POST /sprints/{id}/issues)/해제(DELETE /sprints/{id}/issues/{issueKey})

미구현(본 작업 포함).
- 백로그 조회 API — rank 정렬된 스프린트 미할당 이슈 목록 GET (양쪽 Deviation 이연 항목)
- D6 프론트: @dnd-kit 백로그 ↔ 스프린트 드래그앤드롭 보드
- D7 E2E: 드래그 재정렬·스프린트 할당/해제 시나리오

classify override: type=qa→ui (E2E 키워드 오판 선례 교정).

## 도메인 정리

- **BC**: agile-planning (주, 백로그/스프린트 화면) + issue-tracking (포트 rank read 확장만) + shared-kernel (BoardIssueView)
- **신규 엔티티**: 0 (Sprint·sprint_issues·issues.rank·Board 전부 존재). 백로그는 별도 테이블 없는 **조회 전용 view**
- **신규 용어**: 0 (백로그·스프린트·LexoRank 모두 glossary 존재)
- **마이그레이션**: 0 (스키마 전부 존재)
- **핵심 결정** (ADR `2026-06-24-fr-bl-d6-d7-backlog-query-port-rank.md` 신설):
  1. 백로그 조회 API = **agile-planning 소유** (미할당 판정=sprint_issues 기준=agile 데이터. issue-tracking 주도는 역방향 의존이라 기각)
  2. rank 정렬 = `BoardIssueLookupPort.BoardIssueView`에 `rank: String?` 추가(default null, fail-safe) + issue-tracking adapter가 `issues.rank` SELECT. FR-BL-02 ADR "rank=D6 이연(포트 미노출)"의 실현
  3. 정렬 규칙 = `rank ASC NULLS LAST, created_at` (신규 이슈 rank=NULL lazy)
  4. 드래그 동작 = 기존 API 재사용 — 재정렬 `PATCH /issues/{key}/rank`(#179), 할당 `POST /sprints/{id}/issues`(#182), 해제 `DELETE /sprints/{id}/issues/{issueKey}`(#182). 신규 백엔드는 **조회만**
- **기존 결정 충돌**: 없음 (FR-BL-01/02 ADR의 명시적 연장)
- **관련 ADR**: [2026-06-24-fr-bl-d6-d7-backlog-query-port-rank.md](../decisions/2026-06-24-fr-bl-d6-d7-backlog-query-port-rank.md) (신설) · [FR-BL-01 lexorank](../decisions/2026-06-23-fr-bl-01-lexorank-backlog-ordering.md) · [FR-BL-02 sprint-issue](../decisions/2026-06-24-fr-bl-02-sprint-issue-association.md)
- **spec 미결 사항**: 스프린트별 이슈 순서 rank 정렬 포함 여부 · 페이징(1K 가상 스크롤) 방식 · `@dnd-kit/sortable` 추가 여부(board 선례는 core만 사용)
- **프론트 선례**: `apps/web/src/{api/boards.ts, hooks/use-boards.ts, components/board/*, mocks/board-handlers.ts, routes/projects.$projectKey.board.tsx}`. @dnd-kit/core 6.3.1·utilities 3.2.2 설치됨

## 스펙

전체 스펙. [docs/specs/2026-06-24-fr-bl-01-02-d6-d7-backlog-sprint-ui-e2e.md](../specs/2026-06-24-fr-bl-01-02-d6-d7-backlog-sprint-ui-e2e.md)

핵심 5줄 요약.
- 백로그 보드 한 페이지 = 백로그 칸(미할당) + 스프린트별 칸, 모두 rank 순 표시
- 신규 백엔드는 **조회만** — `GET /projects/{key}/backlog`(백로그+스프린트별 이슈, rank 정렬, truncated). 포트에 `BoardIssueView.rank` 확장
- 드래그 5종(S1~S5)은 기존 API 위임 — 재정렬 `PATCH /rank`(이웃 prev/next), 할당/해제 `POST·DELETE /sprints/{id}/issues`. 이동+위치는 2 API 순차
- 라이프사이클 전체 — 스프린트 생성/시작/완료 버튼(#182 API)
- @dnd-kit/core만(sortable 미추가)·가상스크롤 미도입(truncated)·invalidate-only·권한 게이팅+서버 재검증

범위 확정(Maxi). 스프린트 내 재정렬 **포함**, 라이프사이클 UI **전체 포함**.

## Brainstorming Check

✅ 통과 (self-check, gap 0). 완료 FR 후속이라 full brainstorming 대신 스펙 직접 점검 — 잠재 포인트(할당시 rank 부여/null 이웃·드롭 이웃 계산·플리커·COMPLETED read-only)는 모두 스펙 E1~E7/NFR에 반영 또는 plan 구현 디테일로 해소. Maxi 결정 필요 신규 gap 없음.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
