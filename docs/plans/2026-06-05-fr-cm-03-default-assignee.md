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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
