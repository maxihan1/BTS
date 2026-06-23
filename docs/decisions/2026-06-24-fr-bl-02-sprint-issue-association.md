<!-- FR-BL-02 Sprint-Issue 연관 모델 결정 (sprint_issues 조인, agile-planning 단독) -->
# ADR — FR-BL-02 Sprint↔Issue 연관 모델

> 날짜: 2026-06-24
> 상태: 채택 (Maxi 확정)
> 관련 FR: FR-BL-02 (백로그→스프린트 이동)
> 관련 plan: docs/plans/2026-06-24-fr-bl-02-sprint-backend.md

## 맥락

FR-BL-02는 이슈를 스프린트에 할당/해제하는 기능이다. "이슈가 어느 스프린트에 속하는가"를 데이터로 표현하는 방식이 BC 경계를 좌우한다.

- product `agile-planning.md §3.2 D3`은 **`issues.sprint_id`** 컬럼을 명시한다(issue-tracking 테이블 변경).
- 그러나 agile-planning의 board 선례(FR-BD-01)는 **issue-tracking 내부를 직접 import하지 않고 shared-kernel 포트(`BoardIssueLookupPort`, `IssueTransitionPort`)로만** 이슈를 읽는다. agile-planning repository는 issues 테이블에 전혀 접근하지 않는다.
- FR-BL-01에서 issues 테이블에 스칼라 컬럼(rank)을 추가했다가 기존 테스트 254개가 파급된 교훈이 있다([[fr-bl-01-lexorank-done]]).

## 결정

**Sprint↔Issue 연관을 agile-planning이 소유하는 `sprint_issues` 조인 테이블로 모델링한다. issues 테이블은 변경하지 않는다.**

```
agile-planning BC (단독)
  sprints(id, project_key, name, goal, status, start_date, end_date, ...)
  sprint_issues(sprint_id, issue_id)
        └ issue_id = UUID 느슨 참조 (cross-BC FK 없음)
```

- 이슈 목록/가시성 조회: 기존 `BoardIssueLookupPort` 재사용 또는 확장 (board 선례 동일 패턴).
- 할당/해제: `sprint_issues` INSERT / DELETE.
- "한 이슈 = 한 스프린트(활성)" 1:N 불변식은 조인 테이블 제약(예: `UNIQUE(issue_id)` 또는 service 검증)으로 강제 — 구체 방식은 spec에서 확정.

## 대안 — issues.sprint_id 컬럼 (모델 A, 기각)

product 명세대로 `issues.sprint_id UUID nullable`을 issue-tracking에 추가하는 안. 1:N을 컬럼으로 자연 표현하고 쿼리가 단순하나,

- PR이 두 BC(agile-planning + issue-tracking)를 건드려 BC 격리 원칙 예외가 필요하고,
- issues 컬럼 추가가 FR-BL-01 rank 254 파급류 회귀 위험을 다시 부른다.

→ 기각.

## 결과

- 이번 PR은 **단일 BC(agile-planning)** 로 유지된다. issue-tracking 무변경.
- 마이그레이션은 agile-planning 모듈(V503+)에 `sprints`, `sprint_issues` 생성.
- cross-BC 통신은 shared-kernel 포트만 사용(board 패턴 일관).
- **product drift 정정 대상**: `agile-planning.md §3.2 D3`의 "issues.sprint_id" → "sprint_issues 조인 테이블 (agile-planning)". fr-index/SDD 동기화는 머지 PR에서 전수 반영(CLAUDE.md §명세 변경 전수 동기화).

## 미결 (→ spec)

- Sprint 상태 모델(PLANNED/ACTIVE/COMPLETED) 및 라이프사이클(시작/종료) 범위.
- 한 프로젝트의 동시 ACTIVE 스프린트 개수 제약.
- 스프린트 내 이슈 순서(rank 재사용 vs 별도) — 백로그 rank와의 관계.
- 권한 모델(스프린트 CRUD/할당에 필요한 IssuePermission 또는 신규 권한).
