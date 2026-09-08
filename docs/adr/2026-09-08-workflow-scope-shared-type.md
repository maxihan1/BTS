# ADR — 워크플로우 권한 스코프를 shared-kernel 공용 타입 하나로 둔다

> 날짜 2026-09-08 · BC shared-kernel + identity-access · PR ③ · 티어 T3
> spec [`2026-09-08-project-owned-workflows`](../specs/2026-09-08-project-owned-workflows.md) ·
> plan [`2026-09-08-project-owned-workflows`](../plans/2026-09-08-project-owned-workflows.md)

FR-WF-08.

## 맥락

FR-WF-08 이전에 워크플로우 권한은 **두 판정기**로 갈려 있었다.

| 판정기 | 대상 | 스코프 |
|---|---|---|
| `WorkflowSchemePermissionResolver` | 스킴(이슈타입↔워크플로우 매핑 묶음) | `WorkflowSchemeScope` — `Global` · `Project(key)` |
| `WorkflowDefinitionPermissionResolver` | 워크플로우 정의(상태 편성·전환·초안) | **없음** |

정의 판정기에 스코프가 없던 근거는 그 파일 KDoc 에 적혀 있었다 — 「워크플로우 정의는 사이트
전역 자원이다. 프로젝트에 종속되지 않는다」.

**PR ① 이 그 전제를 무너뜨렸다.** `workflows.project_id` 가 생기면서 워크플로우 정의는
「전역 공유」와 「그 프로젝트 전용」으로 갈렸다. 정의 판정기도 스코프를 받아야 한다.

그래서 결정할 것이 생겼다 — **그 스코프 타입을 어디서 가져오는가.**

## 결정

### D1. 중립 이름의 공용 `WorkflowScope` 를 shared-kernel 에 두고 두 판정기가 함께 쓴다

후보는 셋이었다.

**후보 ① `WorkflowSchemeScope` 를 그대로 재사용한다.**
가장 적은 변경이다. 그러나 이름이 **스킴**을 뜻하는데 워크플로우 **정의** 편집을 담게 된다.
이 저장소는 이미 같은 함정을 이름으로 경고해 두었다 — `WorkflowDefinitionPermission` 의 KDoc.

> 그쪽은 **스킴**(이슈 타입별 워크플로우 매핑 묶음)의 관리·배정 권한이다. 이름이 스킴을 뜻하는데
> 워크플로우 정의 편집을 담게 되면 다음 사람이 두 개념을 같은 것으로 읽는다.
> 도메인당 enum 1개 + resolver 1개가 이 저장소의 관례다.

그 경고 때문에 enum 은 **이미 둘로 갈라져 있다.** 스코프 타입만 이름을 섞으면 그 결정을 되돌리는
것이고, 되돌린 자리에 근거가 남지 않는다.

**후보 ② 정의 전용 스코프 타입을 따로 만든다.**
이름은 정확해지지만 **서로를 검사하지 않는 두 목록**이 생긴다. 스코프를 하나 더 늘리는 날
(예. `Organization`) 한쪽만 늘어나고 다른 쪽은 조용히 통과한다. 두 판정기 테스트가 각자 자기
타입만 보므로 red 도 나지 않는다. 이 저장소가 이름 붙인 지배 결함 양식 그대로다
(`two-lists-never-check-each-other`).

**후보 ③(채택) 중립 이름 하나를 두고 둘이 함께 쓴다.**
`com.bts.shared.permission.WorkflowScope` — `Global` · `Project(key)`.
`WorkflowSchemeScope` 사용처를 1회 치환한다.

⇒ 스코프가 늘면 두 판정기가 **같은 컴파일 에러**로 멈춘다. 이름은 스킴에도 정의에도 맞다.

### D2. 프로젝트 축 판정 절차는 `ProjectWorkflowPermissionGate` 한 구현이다

D1 을 정하고 나니 두 번째 복사본이 드러났다. 「키 해석 → 멤버 게이트 → `role_permissions`
매트릭스」 세 단계는 스킴 판정기의 `private fun hasProjectPermission` 안에 있었고, 정의 판정기가
같은 절차를 필요로 했다.

각자 복사본을 들면 멤버 게이트를 강화하는 날 한쪽만 고쳐진다 — 그리고 두 판정기 테스트가 각자
자기 사본만 지키므로 red 가 나지 않는다. `ManageSchemeGuard` 가 **이미 같은 이유로** 단일 구현을
요구하고 있었다.

⇒ 세 단계를 `ProjectWorkflowPermissionGate` 로 뽑고 두 판정기가 그것을 부른다.
스킴 판정기의 사본은 지운다.

### D3. `DELETE` 는 대상 소유와 무관하게 전역 축이다 — 규칙은 판정기 안에 둔다

스펙 §4 D6 이 「DELETE 는 SYSTEM_ADMIN 유지」로 정했다. 구현 자리는 둘이었다.

**후보 ① 호출부가 DELETE 에만 `Global` 을 넘긴다.**
판정기는 단순해지지만 그 규칙이 **호출부 수만큼** 생긴다. 새 삭제 경로가 생길 때 조용히 빠지고,
빠지면 프로젝트 관리자가 통과한다 — 실패 방향이 권한 확대다.

**후보 ②(채택) 호출부는 늘 사실대로의 소유를 넘기고, 예외 규칙은 판정기가 안다.**
`isProjectDelegable()` 이 enum 을 `when` 으로 전수 분기하고 **else 를 두지 않는다.** 권한이
하나 늘면 컴파일 에러로 결정을 강요한다.

★ 이 조항에서 `Project` + `DELETE` 를 전역 축으로 보내는 것은 완화가 아니라 **강화**다.
그 조합이 게이트를 타면 프로젝트 관리자가 통과하게 된다. 또한 시스템 관리자는 프로젝트 소유
워크플로우도 지울 수 있어야 한다 — 아니면 그 워크플로우를 **아무도** 못 지운다(프로젝트 관리자는
D6 로 막히고, 시스템 관리자는 그 프로젝트의 비멤버라 게이트에서 막힌다).

### D4. D6 표를 기계가 읽어 판정 코드와 대조한다

D3 의 `when` 은 enum→코드 누락을 컴파일로 막는다. 그러나 **스펙 표는 문서라 컴파일이 없다.**
표만 고치고 코드가 안 따라오면 문서와 운영이 갈리고, 코드만 고치면 권한이 조용히 넓어진 채
아무도 그 결정을 승인하지 않는다.

⇒ `scripts/workflow/workflow-definition-permission-matrix.test.ts` 가 세 목록(표 · 코드 분기 ·
enum)을 차집합으로 대조한다. 세 목록이 **비어 있지 않은지부터** 단언한다 — 표가 옮겨 가거나
함수 이름이 바뀌면 빈 집합끼리 같다고 판정해 조용히 초록이 되기 때문이다.

## 결과

- 스코프 타입 1개. `WorkflowSchemeScope` 잔존 0.
- 프로젝트 축 판정 구현 1개. 두 판정기가 공유한다.
- 삭제 위임 여부 결정 지점 1개. 호출부는 소유만 말한다.
- D6 표 ↔ 코드 drift 는 판별식이 막는다.

## 대가

- 백엔드 4모듈 32파일의 타입 이름이 바뀐다. 순수 치환이지만 diff 가 넓다.
- `ProjectWorkflowPermissionGate` 가 `@Profile("prod")` 부품으로 하나 늘었다. 비-prod 는 판정기
  자체가 AlwaysAllow stub 으로 대체되므로 이 부품에 도달하지 않는다.
- 정의 판정기 호출부 18곳이 스코프를 넘겨야 한다. 그중 3곳(전역 상태 카탈로그)은 소유가 없어
  `WorkflowScope.Global` 을 쓰며, 판별식 규칙 A 가 요구하는 `SCOPE-GLOBAL` 표식을 단다.
