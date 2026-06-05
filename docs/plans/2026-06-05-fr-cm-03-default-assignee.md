# FR-CM-03 — 컴포넌트별 기본 담당자 자동 할당

> slug: fr-cm-03-default-assignee
> plan_slug: issue/components-default-assignee
> type: feature
> agent: backend-engineer
> 생성: 2026-06-05

## Brief

이슈 생성 또는 컴포넌트 변경 시, 할당된 컴포넌트의 리드(`leadUserId`)를 이슈의 기본
담당자로 자동 할당한다. 담당자가 미할당(null)일 때만 채우며(명시 할당 보존), 이슈가
여러 컴포넌트에 속한 경우 컴포넌트 이름 사전순 첫 번째 컴포넌트의 리드를 채택한다.

- BC: issue-tracking
- 데이터 신설 없음 — 기존 `components.lead_user_id`(FR-CM-01) + `issue_components`(FR-CM-02) 활용
- 선행 완료: FR-CM-01(PR #59/#64), FR-CM-02(PR #81)

### 확정된 도메인 결정 (2026-06-05, Maxi)

1. 이슈↔컴포넌트는 **다중 유지**(FR-CM-02 그대로). 단일 전환은 검토했으나 취소.
2. 기본 담당자 = 컴포넌트의 기존 **`leadUserId`**(리드)를 그대로 사용. 별도 필드 신설 안 함.
3. **Trigger**: 이슈 생성 시 + 컴포넌트 변경 시 둘 다 자동 배정 평가.
4. **덮어쓰기 정책**: 담당자가 미할당(null)일 때만 자동으로 채움. 명시 할당은 보존.
5. **다중 컴포넌트 우선순위**: 리드가 지정된 컴포넌트 중 **이름 사전순 첫 번째**의 리드 채택.
6. 리드가 없는(null) 컴포넌트뿐이면 자동 배정 없음(미할당 유지).

분류: classify 오판(ui/frontend-engineer) → Maxi 확정 feature/backend-engineer.

## 도메인 정리

- BC: issue-tracking (단일 BC)
- 영향 엔티티: Issue(assigneeId, componentIds — 기존), Component(leadUserId — 기존, 재사용)
- 새 용어/엔티티: 없음. "컴포넌트 리드 = 그 컴포넌트 이슈의 기본 담당자" 의미만 명확화
- 데이터 신설: 없음(기존 components.lead_user_id + issue_components 활용)
- ground-truth 검증 완료:
  - `Issue.assigneeId: ActorId?`, `assignTo()/unassign()` 존재
  - `Issue.componentIds: List<UUID>`, `assignComponents()/clearComponents()` 존재
  - `Component.leadUserId: UUID?` 존재, 리드 실재는 컴포넌트 set 시 UserLookupPort 검증됨
  - **`createIssue`는 현재 컴포넌트/담당자 미수용** → 생성 시 자동배정 위해 componentIds 입력 추가 필요(D2)
  - FR-CM-02 `validateComponents`(같은 프로젝트+활성) 재사용 가능
- 확정 결정(상세는 ADR):
  1. 기본 담당자 = 기존 `leadUserId`(필드 신설 없음)
  2. trigger = 이슈 생성 + 컴포넌트 변경 둘 다 (생성 API에 componentIds 추가)
  3. 담당자 미할당(null)일 때만 채움
  4. 다중이면 리드 보유 컴포넌트 중 이름 사전순 첫 번째
  5. 로직은 IssueApplicationService, 담당자 설정은 도메인 assignTo() 경유
- 기존 결정 충돌: 없음(FR-CM-02 다대다 유지). 단일 전환 검토했으나 기각
- 관련 ADR: [docs/adr/2026-06-05-component-default-assignee-auto-assignment.md](../adr/2026-06-05-component-default-assignee-auto-assignment.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-05-fr-cm-03-default-assignee.md](../specs/2026-06-05-fr-cm-03-default-assignee.md)

핵심 시나리오 3줄 요약.
- 컴포넌트 지정 시(생성 또는 PATCH) 담당자 미할당이면 컴포넌트 리드를 담당자로 자동 배정
- 다중 컴포넌트면 리드 보유분 중 이름 사전순 첫 번째 리드 채택, 명시 담당자는 보존
- createIssue에 componentIds 입력 추가(생성 트랜잭션에서 issue_components 링크 영속), 자동 배정은 silent

## Brainstorming Check

✅ 통과 (1회). 자동 배정 silent 처리(기존 changeAssignee 일관) + createIssue 컴포넌트 영속 주의 보강.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
