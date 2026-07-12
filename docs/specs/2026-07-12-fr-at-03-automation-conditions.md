<!-- FR-AT-03 조건 분기 스펙 — 구조화 조건 모델(JSONLogic 부분집합) + IssueSnapshotPort + ActionExecutor 조건 게이트 -->

# FR-AT-03 조건 분기 (if-else, 표현식) — 스펙

- 날짜: 2026-07-12
- BC: automation (§2.3)
- 범위: 백엔드 D1~D5 (프론트 D6 조건 빌더 UI·D7 E2E는 후속 PR)
- 선행: FR-AT-01(트리거, #251/#254), FR-AT-02(액션, #256/#260)
- 도메인 결정: plan `## 도메인 정리` (구조화 조건 모델 · IssueSnapshotPort · 백엔드 우선)

## 개요

TCA(Trigger-Condition-Action) 엔진에서 **조건(Condition)** 은 트리거 발화 후 액션 실행 전에
평가되는 분기 게이트다. 조건이 참이면 액션을 실행하고, 거짓이면 액션을 하나도 실행하지 않고
건너뛴다(SKIPPED). 조건은 **구조화된 데이터 트리**(JSONLogic 호환 부분집합)로 표현되며,
BTS 자체 평가기가 순수 in-memory 로 평가한다 — **코드 실행 경로가 구조적으로 존재하지 않아**
관리자가 런타임 API 로 입력해도 RCE 표면이 0 이다.

## 사용자 시나리오 (Given-When-Then)

### S1. 조건 충족 → 액션 실행
- **Given** 프로젝트 관리자가 "이슈가 Bug 타입이고 우선순위 3 이상이면 리드에게 할당" 룰을 만들고
  조건 `{"and": [{"==": [{"var": "issue.type"}, "Bug"]}, {">=": [{"var": "issue.priority"}, 3]}]}` 을 설정
- **When** priority=4 인 Bug 이슈가 생성되어 트리거가 발화
- **Then** 조건이 참으로 평가되어 룰의 액션(할당)이 실행된다

### S2. 조건 불충족 → 액션 건너뜀
- **Given** 같은 룰
- **When** priority=1 인 Bug 이슈가 생성
- **Then** 조건이 거짓으로 평가되어 **액션을 하나도 실행하지 않고** 실행 결과가 `SKIPPED` 로 기록된다

### S3. 조건 없는 룰 → 항상 실행 (하위호환)
- **Given** 조건이 설정되지 않은 기존 룰(FR-AT-01/02 룰)
- **When** 트리거 발화
- **Then** 조건 게이트를 통과(항상 참)해 액션이 그대로 실행된다 — 기존 동작 불변

### S4. 관리자가 조건 표현식을 룰에 저장
- **Given** 프로젝트 관리자(`MANAGE_AUTOMATION` 권한)
- **When** 룰 생성/수정 API 에 조건 표현식을 포함해 전송
- **Then** 표현식 형식이 검증되고(연산자·필드·깊이/크기), 유효하면 `automation_conditions` 에 저장,
  무효하면 `400 INVALID_CONDITION_EXPRESSION` 으로 거부된다

### S5. 잘못된 조건 표현식 → 안전 실패 (실행 시점)
- **Given** 어떤 이유로 평가 시점에 조건 평가가 실패(예: 예상치 못한 타입 불일치)
- **When** 트리거 발화 후 조건 평가
- **Then** **fail-safe** — 액션을 실행하지 않고(의도치 않은 이슈 변경 방지) 실행 결과에 오류를 기록한다

## 기능 요구사항 (FR)

### FR-AT-03-1. 조건 도메인 모델 (D1)
- `Condition` sealed 타입 — `Action`(FR-AT-02)의 `sealed class` + `fromJson(json)` 팩토리 패턴 미러링.
  - `Condition.And(conditions: List<Condition>)`
  - `Condition.Or(conditions: List<Condition>)`
  - `Condition.Not(condition: Condition)`
  - `Condition.Comparison(field: String, operator: ComparisonOperator, value: ConditionValue)`
- `AutomationRule` 애그리거트에 `condition: Condition?` 필드 신규 추가(null=조건 없음). FR-AT-02 가
  `actions` 를 추가한 방식과 동일(현재 예약 슬롯 없음). `create` 팩토리 파라미터 + `updateCondition`
  불변 동작 메서드(`updateActions` 대칭) 추가.

### FR-AT-03-2. 표현식 문법 — JSONLogic 호환 부분집합 (D2)
평가기가 지원하는 **연산자 집합(고정, 확장 시 스펙 개정)**:

| 분류 | JSON 키 | 형태 | 의미 |
|---|---|---|---|
| 조합 | `and` | `{"and": [c1, c2, ...]}` | 전부 참(빈 배열=참) |
| 조합 | `or` | `{"or": [c1, c2, ...]}` | 하나 이상 참(빈 배열=거짓) |
| 조합 | `not` | `{"not": c}` | 부정 |
| 비교 | `==` | `{"==": [{"var": F}, V]}` | 같음(스칼라 deep equal, 타입 다르면 false) |
| 비교 | `!=` | `{"!=": [{"var": F}, V]}` | 다름 |
| 비교 | `>` `>=` `<` `<=` | `{">=": [{"var": F}, V]}` | 서수 비교(**양쪽 모두 숫자일 때만**, 아니면 false) |
| 멤버십 | `in` | `{"in": [A, B]}` | B가 배열이면 A∈B, B가 문자열이면 A는 B의 부분문자열 |
| 존재 | `!` | `{"!": {"var": F}}` | 비어있음/null/falsy |
| 존재 | `!!` | `{"!!": {"var": F}}` | 존재/truthy |
| 필드참조 | `var` | `{"var": "issue.status"}` | 아래 화이트리스트 필드만 |

- **필드 화이트리스트**(`var` 로 참조 가능): `issue.key` · `issue.type` · `issue.status` ·
  `issue.priority` · `issue.assignee` · `issue.reporter` · `issue.labels` · `issue.summary` ·
  `issue.projectKey`. 목록 밖 필드는 **저장 시 400 거부**, 평가 시 null 취급(방어).
- **리터럴 값**: 문자열 / 숫자 / boolean / null / 배열(문자열·숫자만). 중첩 객체 리터럴 불가.
- **크기 제한**(DoS 방지): 트리 최대 깊이 10, 최대 노드 100. 초과 시 저장 400 거부.

### FR-AT-03-3. 데이터 모델 (D3)
- `V304__automation_conditions.sql` — `automation_conditions(rule_id UUID PK/FK ON DELETE CASCADE,
  expression JSONB NOT NULL, created_at, updated_at)`. **룰당 0..1 행**(rule_id UNIQUE/PK) — 조건은
  하나의 트리이므로 `automation_actions`(N행 position)와 달리 단일 행. product `automation_conditions(expression)` 정본과 일치.
- 리포지토리 `AutomationConditionRepository` — `findByRuleId(ruleId): Condition?` · `replace(ruleId, condition?)`
  (upsert/delete). `AutomationActionRepository` replace-all 패턴 참조.
- `SchemaMigrationTest` 에 V304 컬럼/제약 단언 블록 추가.

### FR-AT-03-4. 표현식 평가 엔진 (D4)
- `ConditionEvaluator`(순수 함수, IO 없음) — `evaluate(condition: Condition, ctx: ConditionContext): Boolean`.
  트리를 재귀 평가. `TriggerMatcher` 처럼 DB/IO 없는 순수 판정 컴포넌트.
- `ConditionContext` — 이슈 스냅샷을 필드 화이트리스트 맵으로 감싼 읽기 전용 뷰.
- **실행 hook**: `ActionExecutor.execute` 내부, 액션 로드 후·dispatch 전. (dry-run 미리보기 경로와
  실제 실행 경로가 모두 이 메서드를 지나므로 조건이 양쪽에서 일관 적용됨 — 워커 레벨 게이트는 dry-run
  우회 위험이 있어 기각.)
  - 조건이 null(없음) → 통과, 기존 액션 실행 흐름 유지.
  - 조건이 있으면 트리거 이벤트에서 issueKey 추출(`extractIssueKey` 재사용) → `IssueSnapshotPort.fetch(issueKey)`
    → `ConditionContext` 구성 → `ConditionEvaluator.evaluate`.
  - 결과 false → 액션 0개 실행, `ActionExecutionResult(status=SKIPPED, outcomes=emptyList())` 반환.
  - 평가 중 예외/스냅샷 부재(issueKey 없음 등) → **fail-safe**: 액션 실행 안 함, `SKIPPED`(사유 로그).
- `ActionExecutionStatus` 에 `SKIPPED` 값 추가(기존 SUCCESS/PARTIAL/FAILED 하위호환 — 기존 코드는
  SKIPPED 를 생성하지 않음).

### FR-AT-03-5. cross-BC 읽기 포트 IssueSnapshotPort (D4 의존)
- shared-kernel `com.bts.shared.issue.IssueSnapshotPort` — **`fetch(actorUserId: UUID, issueKey: String): IssueSnapshot?`**
  (actor 미가시/이슈 부재 시 null). `IssueMutationPort` 방향·fail-closed·actor 신뢰 모델·shared-kernel 배치
  원칙 미러 (`automation ──port──▶ shared-kernel ◀──impl── issue-tracking`).
- **actor 가시성 강제 (게이트1 BLOCKER 해소)**: 어댑터가 룰 actor 권한으로 issue-tracking **기존 가시성 강제
  read 경로**를 재사용한다. 보안 수준(FR-PM-06) 제한 이슈는 actor 가 그룹 멤버가 아니면 null 반환 →
  조건 게이트 fail-safe SKIPPED. SDD §12.4 "관리자 우회 없음" 준수(무필터 읽기 금지). FR-AT-02 쓰기 경로가
  actor 로 가시성을 강제하는 것과 대칭 — 읽기만 무게이트인 비대칭 제거.
- `IssueSnapshot`(shared-kernel VO, Jackson 비의존 순수 계약) — `key, projectKey, type(String?, 타입 이름),
  status(String, 워크플로우 stateKey), priority(Int?, 1-5), assigneeId(UUID?), reporterId(UUID?),
  labels(List<String>), summary(String)`. 필드 화이트리스트와 1:1. **type=이름·status=stateKey·priority=숫자**
  표현 확정(어댑터 매핑, D6 조건 빌더 UI 가 유효값 드롭다운 제공).
- issue-tracking `@Profile("prod")` 어댑터가 이슈 애그리거트→스냅샷 매핑 구현. non-prod: automation
  test 가 `StubIssueSnapshotPort` 소유(consumer-owns-stub, `StubIssueMutationPort` 선례).
- fail-closed: `ActionExecutor` 가 non-null 로 주입 요구(adapter 미결선 시 부팅 실패, silent no-op 금지,
  nullable `?:` return 금지). prod 조립(`:modules:app`)에 issue-tracking 어댑터 배선 + 부팅 재검증 필요.

### FR-AT-03-6. 표현식 형식 검증 (D2/D4)
- 룰 생성/수정 시 조건 표현식 검증(`TriggerConfig.validate`/`ActionConfig` 대칭). 검증 항목: JSON 파싱,
  연산자 화이트리스트, 필드 화이트리스트, 서수 비교 피연산자 형태, 깊이/노드 상한.
- 무효 → `400 INVALID_CONDITION_EXPRESSION`. **형식 검증만**(cross-BC 존재 검증 안 함 — 트리거/액션 선례).

### FR-AT-03-7. API — 룰 CRUD payload 확장
- 조건은 **룰 CRUD payload 의 필드**(액션과 동형). 기존 룰 생성/수정 엔드포인트의 요청/응답 DTO 에
  `condition`(nullable 표현식 객체) 추가 + `MANAGE_AUTOMATION` 가드(기존). 신규 엔드포인트 없음.
- 응답에 저장된 조건 표현식 반환(D6 UI 가 렌더링).

## 비기능 요구사항 (NFR)

- **보안**: 조건 평가는 코드 실행 경로 부재(구조적 보장). `var` 필드 화이트리스트로 임의 데이터 접근 차단.
  스냅샷 조회는 **룰 actor 권한으로 가시성 강제**(§12.4 관리자 우회 없음 준수) — actor 가 못 보는 보안수준
  제한 이슈는 null→SKIPPED. 조건은 룰 소유 프로젝트 이슈만 대상(트리거가 프로젝트 스코프로 이미 차단).
- **성능**: 조건 평가는 순수 in-memory 트리 워크(<1ms). 스냅샷 조회 1회(issueKey 당). 트리거→액션 5s 예산 내.
- **DoS**: 트리 깊이/노드 상한으로 악의적 대형 표현식 차단(저장 시점).
- **하위호환**: 조건 없는 기존 룰 동작 불변. `ActionExecutionStatus.SKIPPED` 추가는 기존 값 비파괴.

## API 인터페이스 (REST)

기존 automation 룰 CRUD(`.../automation/rules` 계열) 요청/응답 DTO 확장.

```
POST/PATCH  (기존 룰 생성/수정 엔드포인트)
  request  += { "condition": <표현식 JSON | null> }
  response += { "condition": <표현식 JSON | null> }
  가드: MANAGE_AUTOMATION (기존)
  400 INVALID_CONDITION_EXPRESSION — 형식/화이트리스트/크기 위반
```

## 데이터 모델 변경

- 신규 `V304__automation_conditions.sql`:
  ```
  automation_conditions(
    rule_id     UUID PRIMARY KEY REFERENCES automation_rules(id) ON DELETE CASCADE,
    expression  JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
  )
  ```
- automation BC 예약 범위 V300~V399. 최신=V303 → V304.

## 엣지 케이스

- **EC1. 조건 없음(null)** → 항상 통과, 액션 실행. (S3)
- **EC2. issueKey 없는 트리거**(SCHEDULED/WEBHOOK 빈 payload)에 issue.* 조건 → 스냅샷 조회 불가 →
  fail-safe SKIPPED. (FR-AT-02 액션도 issueKey 없으면 실패하는 기존 현실과 정합)
- **EC3. 스냅샷 조회 결과 이슈 부재**(null) → fail-safe SKIPPED.
- **EC4. 화이트리스트 밖 필드** → 저장 400 거부. (평가 시 우연히 도달하면 null 취급)
- **EC5. 서수 비교(`>=`)의 비숫자 피연산자**(예: `issue.status >= 3`) → 저장 시 형태 검증으로 거부
  가능한 범위는 거부, 런타임 도달 시 false(fail-safe, throw 없음).
- **EC6. `==`/`!=` 타입 불일치**(문자열 vs 숫자) → 같지 않음(== false, != true). null==null → true.
- **EC7. 빈 `and`** → true, 빈 `or` → false. (수학적 항등)
- **EC8. labels 멤버십** — `{"in": ["urgent", {"var": "issue.labels"}]}` → labels 에 "urgent" 포함 여부.
- **EC9. assignee 존재** — `{"!!": {"var": "issue.assignee"}}`(담당자 있음) / `{"!": {"var": "issue.assignee"}}`(미배정).
- **EC10. 깊이/노드 상한 초과** → 저장 400 거부.
- **EC11. 잘못된 JSON / 미지원 연산자** → 저장 400 거부.
- **EC12. dry-run** → 게이트가 `execute` 내부에 있어 미래 dry-run caller(D6 미리보기)가 생기면 자동 커버.
  **현재 production 에 `execute(dryRun=true)` caller 는 없음**(FR-AT-02 dry-run 은 포트 커맨드 레벨 플래그로만
  존재) — 본 PR 은 `execute(dryRun=true)` 단위 테스트로만 게이트 일관성 검증.
- **EC13. 보안수준 제한 이슈**(actor 미가시) → 스냅샷 null → fail-safe SKIPPED(§12.4 준수).
- **EC14. 이중 이슈 뷰** → 조건은 최신 DB 스냅샷(IssueSnapshotPort) 기준으로 판정, AddComment 템플릿
  `{{ issue.* }}`는 트리거 이벤트 payload 기준(FR-AT-02 현 한계 — status 등 공란 가능). 본 PR 은 템플릿
  enrich 하지 않음(후속). "조건 통과했는데 댓글 status 공란" 놀람 방지 위해 KDoc 명시.

## 제약 조건

- **BC 격리**: automation 은 issue-tracking 직접 import 금지. 스냅샷은 shared-kernel 포트로만.
- **PoC 금지**: 완제품 품질. 연산자 집합은 고정 명세(임의 확장 금지).
- **도메인 우회 금지**: 스냅샷 포트 어댑터는 issue-tracking 도메인/조회 경로 재사용.
- **fail-closed 포트 주입**: nullable `?:` return 금지([[crossbc-resolver-nullable-fail-open]]).

## 테스트 케이스 50개 (D5) — 카테고리 배분

`ConditionEvaluator` 는 **fixture ConditionContext**(합성 스냅샷)로 순수 평가 검증 → issue-tracking
실제 모델과 독립. 어댑터 매핑 정확성은 별도(소수) 테스트.

1. **비교 연산자**(12): `==`/`!=`/`>`/`>=`/`<`/`<=` × (문자열, 숫자) 조합. 참·거짓 각각.
2. **멤버십 in**(8): 배열 멤버십(status∈[Done,Closed] 참/거짓), 문자열 부분문자열(참/거짓),
   labels 멤버십(EC8 참/거짓), 빈 배열/미포함.
3. **존재 `!`/`!!`**(6): assignee 있음/없음, labels 빈/비어있지않음, summary 빈/비어있지않음.
4. **조합 and/or/not**(8): 단순 and(전부참/일부거짓), or(하나참/전부거짓), not, 2단 중첩.
5. **null/누락 필드 의미**(6): 누락 필드 ==null, !=값, 서수비교 null(false), in null.
6. **타입 불일치 fail-safe**(5): 문자열vs숫자 ==(EC6), 서수비교 비숫자(EC5), in 스칼라 haystack.
7. **항등**(2): 빈 and=참, 빈 or=거짓(EC7).
8. **검증 거부**(D2/D4, 저장 경로)(3): 미지원 연산자·화이트리스트 밖 필드·깊이 초과 → 400.

**통합/E2E 시드**(별도, 위 50 외):
- `ActionExecutor.execute` 조건 게이트: 조건 충족→액션 실행 / 불충족→SKIPPED·액션 0건.
- `AutomationConditionRepository` upsert/delete round-trip(Testcontainers).
- `SchemaMigrationTest` V304 컬럼/제약.
- `IssueSnapshotPort` fail-closed 부팅 회귀 가드(어댑터 부재→NoSuchBean).

## 측정 가능한 완료 기준

- [ ] `Condition` sealed + `fromJson`/`toJson` round-trip.
- [ ] `ConditionEvaluator` 50 케이스 전부 통과.
- [ ] V304 마이그레이션 + `SchemaMigrationTest` 통과.
- [ ] `ActionExecutor` 조건 게이트: 충족 실행 / 불충족 SKIPPED, dry-run 동일.
- [ ] `IssueSnapshotPort`(actor 시그니처) + issue-tracking prod 어댑터(가시성 강제) + StubIssueSnapshotPort + fail-closed 회귀 가드.
- [ ] 보안수준 제한 이슈 actor 미가시→null→SKIPPED 검증(§12.4 준수) + 실이슈 관통 통합 테스트(S1 매치).
- [ ] 룰 CRUD DTO 에 condition 필드 + 형식 검증 400.
- [ ] automation BC ArchUnit(cross-BC import 0) 유지.
- [ ] prod 조립(`:modules:app`) 부팅 재검증 통과.
- [ ] 조건 없는 기존 룰 회귀 0(FR-AT-01/02 테스트 그린).

## Brainstorming Check

✅ 통과 (1회, 적대적 self-review — well-defined FR이라 대화형 brainstorming 대신 집중 검토).
gap 8건 발견, 전부 **구현 레벨 리스크**(스펙 재작성/Maxi 결정 불필요) → plan `## 리뷰 결과`/리스크로 이관.

1. ActionExecutor 생성자 변경 → 기존 ActionExecutorTest 갱신 ([[plan-files-constructor-injection-existing-tests]]).
2. 새 cross-BC 의존(IssueSnapshotPort) → full-boot NoSuchBean, test-boot 슬라이스 전수 Stub 동반 ([[new-crossbc-dep-openapi-mockbean-regression]]).
3. ActionExecutionStatus.SKIPPED 추가 → 모듈 내 exhaustive `when` 전수 갱신.
4. V304 동시 브랜치 충돌 + SchemaMigrationTest 카운트 → 머지 직전 최신 V번호 재확인.
5. prod 조립(`:modules:app`) IssueSnapshotPort 어댑터 배선 + 머지 전 부팅 재검증 ([[prod-assembly-boot-verification-required]]).
6. priority 타입(숫자 1-5 vs 이름) — IssueSnapshot 계약에서 D3/D4 impl 시 확정.
7. 룰 수정 ↔ 조건 upsert 동일 트랜잭션.
8. 스냅샷 포트 가시성 필터 부재 — 안전 근거 성립(PROJECT_ADMIN·프로젝트 스코프)하나 security-engineer 리뷰 체크포인트.
