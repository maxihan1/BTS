# ADR — FR-AT-03 조건 분기 (if-else, 표현식)

> 날짜. 2026-07-12 | PR. #262 | BC. automation | 상태. 채택

## 맥락

FR-AT-03는 TCA(Trigger-Condition-Action) 자동화 엔진의 중간 조각이다. 트리거(FR-AT-01)와 액션(FR-AT-02)은 완료됐고, 그 사이에서 "조건이 참일 때만 액션을 실행"하는 게이트가 필요했다. 조건 표현식은 **런타임에 관리자 API로 유입**된다(자동화 룰 CRUD). 조건 평가에는 대상 이슈의 필드 값(status·priority·assignee 등)이 필요한데, automation BC는 issue-tracking을 직접 import할 수 없다(BC 격리).

## 결정

### 1. 구조화 조건 모델(JSONLogic류 데이터 트리) — SpEL 대신

조건을 sealed `Condition`(And/Or/Not/Comparison) 트리로 표현하고 JSON(JSONB)으로 저장·직렬화한다. 코드 실행 경로가 **구조적으로 부재**하다(데이터 트리 순회만).

- **SpEL을 배제한 이유**. project-workflow의 SpEL 평가기는 표현식이 워크플로우 YAML·seed 등 **관리자만 편집하는 소스**에서만 유입된다는 전제로 샌드박스를 건다("사용자 입력 표현식 직접 평가 금지", SpelEvaluator KDoc). FR-AT-03의 조건은 런타임 관리자 API로 들어오므로 이 전제를 만족하지 못한다 — 임의 표현식 평가 경로를 열지 않기 위해 코드 실행이 불가능한 데이터 모델을 택했다.
- **안전장치**. `var` 필드 화이트리스트(issue.key/type/status/priority/assignee/reporter/labels/summary/projectKey)로 접근 범위 제한, `MAX_DEPTH=10`·`MAX_NODES=100` DoS 상한(저장 시점 검증), 리터럴 배열 원소 100개 상한.
- `ConditionEvaluator`는 순수 트리워크이며 평가 중 예외를 던지지 않는다(fail-safe).

### 2. 신규 cross-BC 읽기 포트 `IssueSnapshotPort` (shared-kernel)

조건 평가에 필요한 이슈 필드를 읽기 위해 shared-kernel에 `IssueSnapshotPort.fetch(actorUserId, issueKey): IssueSnapshot?`를 신설하고, issue-tracking이 `@Profile("prod")` 어댑터로 구현한다. 어댑터는 issue-tracking의 기존 가시성 강제 read(`findByKey` → VIEW 권한·보안등급 게이트)를 재사용한다.

- **fail-closed 주입**. default 구현 없음 — 어댑터 미결선 시 부팅 실패(silent-drop 방지). `IssueMutationPort`(FR-AT-02)와 동형.
- **의존 방향**. `automation ──port──▶ shared-kernel ◀──impl── issue-tracking`.

### 3. 조건 평가 주체 = `createdBy`(위조 불가), `actorUserId` 아님 — §12.4 관리자 우회 없음

조건 게이트는 이슈 스냅샷을 **룰 작성자(`createdBy`)의 가시성**으로 조회한다.

- **이유**. `actorUserId`는 `changeActor`로 임의 사용자 교체가 가능한 필드다(FR-AT-02 지라 Actor 모델). 이를 조회 주체로 쓰면, MANAGE_AUTOMATION 관리자가 제한 이슈를 볼 수 있는 피해자를 actor로 지정하고 조건 참/거짓을 관측 가능한 부수효과(웹훅 등)로 흘려 §12.4("PROJECT_ADMIN/SYSTEM_ADMIN도 지정 그룹원이 아니면 보안등급 제한 이슈를 볼 수 없다") 기밀성 불변식을 우회하는 **read 오라클**이 된다.
- `createdBy`는 생성 시 요청자로 고정되고 `changeActor`로도 바뀌지 않는 위조 불가 필드다. 조건은 "무엇을 관측하는가"이므로 작성자의 알 권리로 제한한다(작성자는 자기가 이미 볼 수 있는 데이터로만 조건을 걸 수 있다).
- **액션 실행 권한은 별개**로 여전히 `actorUserId`를 쓴다 — 조건=관측 권한(createdBy), 액션=실행 권한(actorUserId), 각각 올바른 principal.
- 이 결정은 게이트2 adversarial 코드리뷰에서 P1으로 발견돼 확정됐다.

## 결과

- `ActionExecutor` 조건 게이트는 조회·평가 전체가 fail-safe(어떤 예외도 "불충족" → `ActionExecutionStatus.SKIPPED`)이며, 조건 미설정은 게이트 통과(액션 진행)로 시맨틱을 구분한다.
- 마이그레이션 `V304__automation_conditions.sql`(rule_id PK·expression JSONB·rule ON DELETE CASCADE).
- 이번 PR은 백엔드 D1~D5. 조건 빌더 UI(D6)/E2E(D7)는 별개 후속이며, automation BC는 2/7 유지(FR-AT-03 미완).

## 관련

- 스펙. [docs/specs/2026-07-12-fr-at-03-automation-conditions.md](../specs/2026-07-12-fr-at-03-automation-conditions.md)
- Plan. [docs/plans/2026-07-12-fr-at-03-automation-conditions.md](../plans/2026-07-12-fr-at-03-automation-conditions.md)
- 선행. [2026-07-10-fr-at-01-automation-triggers](2026-07-10-fr-at-01-automation-triggers.md)
