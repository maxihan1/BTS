# ADR — 전이 identity 를 (from, to) 2튜플에서 전환 ID 로 옮긴다

> 날짜. 2026-08-18
> 상태. **채택 (Active)**
> 관련 FR. FR-WF-05 (전환 ID 식별자 — 다중 전환 + 전역/최초 전환) · FR-WF-06 (전환 규칙 편집 — 규칙이 전환에 매달리므로 identity 변경의 직접 영향권이다)
> 관련 문서. `docs/plan/product/project-workflow.md §2.5` · `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/domain/WorkflowTransition.kt`
> 대체 관계. **`docs/adr/2026-05-28-workflow-transition-identity-policy.md` 를 대체한다 (supersedes).** 그 ADR 이 §대안 채택 조건에 직접 적어 둔 탈출구를 발동하는 것이다.

## 맥락

`2026-05-28-workflow-transition-identity-policy.md` 는 전이의 1급 식별자를 `(fromStateKey,
toStateKey)` 2튜플로 확정했다. 당시 맥락은 PR #27 의 BLOCKER — 도메인 모델이 3튜플과 2튜플을
동시에 선언하는 모순 때문에 production 전건 409 가 나던 사고 — 였고, 2튜플 채택은 그 사고에 대한
정확한 처방이었다.

그 ADR 은 자신의 한계도 정확히 적어 뒀다.

> **(i) 같은 `(from, to)`에 여러 전이 정의 영구 차단** — 예. "Cancel"과 "Reject"가 같은
> `(from, to)`를 가지는 경우 불가. 탈출구는 아래 §대안 채택 조건 참조.

그리고 탈출구를 이렇게 열어 뒀다.

> 같은 `(from, to)`에 여러 전이가 필요한 비즈니스 시나리오 출현 시 → 새 ADR 발행 + transition
> identity 를 `(from, to, label)` 또는 `(from, to, guard)` 분기 메커니즘으로 확장.

**그 시나리오가 왔다.** Jira Cloud 패리티를 목표로 워크플로우 편집기를 만들면서, 사용자가 화면에서
직접 만드는 전환은 다음 셋을 요구한다.

1. 같은 상태쌍에 이름이 다른 전환 여럿 — 「검토 중 → 완료」에 "승인" 과 "조건부 승인"
2. **전역 전환** — 「모든 상태에서 → 완료」 같은 강제 종료
3. **최초 전환** — 이슈를 만들면 어느 상태로 진입하는가

2·3 은 구 ADR 의 예상 탈출구(`(from, to, label)`)로도 표현되지 않는다. 둘 다 `from` 이 없기 때문이다.

### 시작 상태가 지금은 암묵적으로 도출된다

`WorkflowKeyResolverImpl.kt:83` 이 `workflow.states.minByOrNull { it.displayOrder }` 로 시작
상태를 정한다. 즉 **관리자가 편집기에서 상태 순서를 바꾸는 순간 이슈 생성 상태가 조용히 바뀐다.**
읽기 전용이던 시절에는 드러나지 않던 결함이 편집을 열면 바로 사고가 된다.

## 결정

### D1. 전환의 1급 식별자는 `workflow_transitions.id` (UUID) 다

`WorkflowTransition.key` (`from__to` 합성)는 하위호환용 계산 프로퍼티로 남기되 매칭의 기준이
아니다. `UNIQUE(workflow_id, from_state_id, to_state_id)` 를 해제한다.

### D2. `kind` 로 세 종류를 가른다

```
workflow_transitions.kind TEXT CHECK(NORMAL|GLOBAL|INITIAL) NOT NULL DEFAULT 'NORMAL'
from_status_id  NULL 허용 — GLOBAL·INITIAL 일 때만 NULL
PARTIAL UNIQUE(workflow_id) WHERE kind='INITIAL'
```

`from = NULL` 하나로는 전역 전환과 최초 전환을 구별할 수 없다. **둘 다 출발 상태가 없지만 의미가
정반대다** — GLOBAL 은 아무 상태에서나 쓸 수 있고, INITIAL 은 상태가 아직 없을 때 딱 한 번 쓴다.
Jira Cloud 도 다이어그램에 별도의 Create 노드를 그려 이 둘을 나눈다.

INITIAL 백필은 각 워크플로우의 `display_order` 최소 상태를 대상으로 1건씩 만든다 — **현행
`minByOrNull` 동작을 그대로 보존**한 뒤, 이후로는 순서가 아니라 이 명시 전환이 시작 상태를 정한다.

### D3. 이슈 전이 API 는 하위호환을 유지한다

`POST /api/v1/issues/{key}/transition` 이 `transitionId` 를 **추가로** 받는다.

- `transitionId` 가 오면 그것으로 실행한다.
- 없고 `toStatusKey` 만 오면 (from, to) 로 후보를 찾아 **정확히 1개일 때만** 실행한다.
- 후보가 2개 이상이면 `409 AMBIGUOUS_TRANSITION` 과 후보 목록을 돌려준다.

`GET /api/v1/issues/{key}/transitions` 응답에 `transitionId` 를 추가하되 기존 `key` 는 남긴다.
shared-kernel 의 `TransitionRequest` 와 `board/IssueTransitionPort.BoardTransitionCommand` 에는
`transitionId: UUID?` 를 nullable 로 더한다 — 기존 호출부의 컴파일이 깨지지 않는다.

### D4. 구 ADR 의 fail-fast 정신은 유지한다

「같은 (from, to) 중복 금지」는 없어지지만, 그 조항이 지키려던 것 — **정의가 모호하면 런타임이
아니라 정의 시점에 막는다** — 은 남긴다. `Workflow.of()` 는 GLOBAL·INITIAL 의 `from` 이 NULL 인지,
INITIAL 이 워크플로우당 1개인지를 검증한다. 모호함은 정의 시점(발행)과 호출 시점(409) 양쪽에서 막는다.

## 근거

1. **구 ADR 이 스스로 예비한 경로다.** 뒤집는 것이 아니라 그 ADR 이 적어 둔 조건이 충족돼 발동하는
   것이다. 다만 확장 폭은 그 ADR 이 예상한 `(from, to, label)` 보다 크다 — GLOBAL·INITIAL 이
   `from` 자체를 없애기 때문에 label 을 더하는 것으로는 부족하고, 대리 키가 필요하다.
2. **구 ADR 이 지키려던 이익은 그대로 남는다.** 「`name` 은 사람 친화 라벨이고 매칭에 안 쓴다」는
   여전히 참이다. 매칭은 `name` 이 아니라 `id` 로 한다 — 오히려 더 강하게 분리된다.
3. **하위호환으로 파급을 묶는다.** `toStatusKey` 를 살려 두면 agile-planning 보드 드래그앤드롭
   (`BoardApplicationService.kt:227`)과 slack-integration 완료 모달
   (`SlackInteractionService.kt:385`)이 **코드 변경 0** 이다. 한 PR = 한 BC 규칙 아래에서 이것이
   변경 범위를 결정적으로 줄인다.
4. **시작 상태 명시화가 편집의 전제다.** 순서 변경이 생성 동작을 바꾸는 결합은 편집기를 여는 순간
   버그가 된다. INITIAL 전환이 그 결합을 끊는다.

## 기각한 대안

**`(from, to, label)` 3튜플로만 확장** — 구 ADR 이 예상한 경로 그대로. 스키마 변경이 작고 대리 키를
안 들인다.

기각 사유. GLOBAL·INITIAL 을 표현하지 못한다. 또 label 이 identity 에 들어가면 **전환 이름을 바꾸는
순간 식별자가 바뀐다** — 사용자가 "승인"을 "최종 승인"으로 고치면 그 전환에 걸린 규칙과 post-action
이 어디로 붙는지 다시 정의해야 한다. 구 ADR 이 `name` 을 매칭에서 떼어 낸 이유와 정확히 같은 이유로
label 을 identity 에 넣을 수 없다.

**현행 2튜플 유지 + 전환 이름 편집만** — 파급이 가장 작다. 기각 사유는 요구를 못 채운다는 것이다.
Maxi 가 요구한 「전환 이름 추가」는 같은 상태쌍에 여러 전환을 두는 것을 포함한다.

## 영향

### 긍정

- 같은 상태쌍에 이름이 다른 전환을 여럿 둔다.
- 「모든 상태에서 → 완료」 전역 전환이 표현된다.
- 이슈 생성 진입 상태가 순서와 분리돼 명시된다.
- 전환 이름 변경이 규칙·post-action 결합을 건드리지 않는다.

### 부정 / 위험

- **보드에서 409 가 보일 수 있다.** 같은 상태쌍에 전환이 둘인데 보드는 `toStateKey` 로만 호출하므로
  모호해진다. 이번 범위에서는 409 로 정직하게 막고, 보드 드래그앤드롭에 「어떤 전환인가요」 선택
  다이얼로그를 붙이는 것은 후속 과제로 남긴다. **조용히 아무거나 고르지 않는다.**
- `Workflow.of()` 의 invariant 5개 중 하나(전이 중복 금지)가 바뀌므로 이를 검증하던 기존 테스트가
  함께 바뀐다. 삭제가 아니라 새 규칙(GLOBAL/INITIAL)으로 교체한다.
- `PostActionTransitionResolver` 가 (from, to) 로 transition 을 해석하고 있어 함께 바뀐다.
  기존 post-action 경로(`.../transitions/{transitionKey}/post-actions`)는 유지한다.
- **FR-WF-06(전환 규칙 편집)이 이 결정에 종속된다.** validator 와 post-action 은 `transition_id`
  에 매달리는데, 같은 상태쌍에 전환이 여럿이 되는 순간 `transitionKey`(`from__to`)로는 어느 전환의
  규칙인지 지목할 수 없다. 따라서 규칙 CRUD 경로는 `transitionId` 를 기준으로 새로 낸다 — 구 경로는
  단일 전환일 때만 유효한 축약으로 남긴다. 이 순서 때문에 FR-WF-06 은 FR-WF-05 를 선행으로 갖는다.

## 대안 채택 조건

- 보드·Slack 에서 모호 전환 409 가 실사용에 걸리기 시작하면 → 두 소비자를 `transitionId` 로 이전하고
  `toStatusKey` 폴백을 폐기한다. 폐기 시점은 별도 판단이며 이 ADR 은 폴백을 영구 계약으로 두지 않는다.

## 관련

- `docs/adr/2026-05-28-workflow-transition-identity-policy.md` — **이 ADR 이 대체한다**
- `docs/adr/2026-08-18-workflow-global-status-catalog.md` — 전환이 참조할 `workflow_statuses`
- `docs/adr/2026-05-26-workflow-transition-port-result-sealed.md` — sealed Result port (유효)
- `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/adapter/inbound/WorkflowKeyResolverImpl.kt:83` — 현행 시작 상태 도출
- `docs/sdd/07-workflow-engine.md` — 워크플로우 엔진 설계
