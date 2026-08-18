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
  sprint_issues(sprint_id, issue_key)
        └ issue_key = 이슈 키 느슨 참조 (cross-BC FK 없음)
```

- 이슈 목록/가시성 조회: 기존 `BoardIssueLookupPort.listVisibleIssuesByProject` **그대로 재사용**(확장 불필요).
- 할당/해제: `sprint_issues` INSERT / DELETE.
- "한 이슈 = 한 스프린트" 1:N 불변식은 `UNIQUE(issue_key)`로 강제 — 다른 스프린트 할당 시 기존 연관 제거 후 이동(원자적).

### 식별자 = issue_key (UUID 아님)

board의 cross-BC 표면이 전부 **이슈 키 중심**이다 — `BoardIssueView.key`(UUID 미노출), `BoardTransitionCommand.issueKey`. 그리고 포트 구현체 `BoardIssueLookupAdapter`는 **issue-tracking 모듈**에 있다. 따라서 UUID를 저장하려면 포트에 id 조회 메서드를 추가해야 하고, 그러면 issue-tracking adapter를 수정 → 단일 BC가 깨진다.

→ `sprint_issues`에 `issue_key`(VARCHAR)를 저장한다. 백로그 계산(`listVisibleIssuesByProject` 결과 keys − sprint_issues keys)과 가시성 검증이 모두 key↔key로 일관되며, 포트를 확장하지 않아 **issue-tracking 완전 무변경**이 유지된다. 키 영속성(DATA.md): 키는 재사용 금지이고 `UNIQUE(issue_key)`가 이를 보강한다. 이슈의 프로젝트 이동(FR-MV)으로 키가 바뀌는 경우의 sprint_issues 동기화는 이번 범위 밖(board의 동일 약점, 후속).

## 대안 — issues.sprint_id 컬럼 (모델 A, 기각)

product 명세대로 `issues.sprint_id UUID nullable`을 issue-tracking에 추가하는 안. 1:N을 컬럼으로 자연 표현하고 쿼리가 단순하나,

- PR이 두 BC(agile-planning + issue-tracking)를 건드려 BC 격리 원칙 예외가 필요하고,
- issues 컬럼 추가가 FR-BL-01 rank 254 파급류 회귀 위험을 다시 부른다.

→ 기각.

## 결과

- 이번 PR은 **agile-planning 주작업** + **issue-tracking `BoardIssueLookupAdapter`에 read 메서드 1개(`isVisibleIssue`)** 추가(게이트1 확정 — 할당 가시성 단건 검증, truncated 오거부/probe 회피). issues 테이블/도메인은 무변경(read view 확장만, board view-layer 확장의 연장).
- 마이그레이션은 agile-planning 모듈(V503+)에 `sprints`, `sprint_issues` 생성.
- cross-BC 통신은 shared-kernel 포트만 사용(board 패턴 일관). agile은 adapter 직접 import 0(ArchUnit).
- **product drift 정정 대상**: `agile-planning.md §3.2 D3`의 "issues.sprint_id" → "sprint_issues 조인 테이블 (agile-planning)". fr-index/SDD 동기화는 머지 PR에서 전수 반영(CLAUDE.md §명세 변경 전수 동기화).

## 결정 이력 (spec/게이트1에서 확정)

- Sprint 상태 모델 = PLANNED/ACTIVE/COMPLETED, start/complete 단방향 전환. ✅
- 동시 ACTIVE 제약 = **없음(다중 허용)**. ✅ (Maxi)
- 스프린트 내 이슈 순서(rank) = **D6 이연**(포트가 rank 미노출). ✅
- 권한 = CRUD/상태전환 `CREATE`, **할당/해제 `UPDATE`**, 조회 `BROWSE`. ✅ (Maxi 게이트1)
- 가시성 검증 = 단건 포트 `isVisibleIssue` + adapter 구현, 미가시/타프로젝트/미존재 단일 404(probe 차단). ✅ (Maxi 게이트1)
