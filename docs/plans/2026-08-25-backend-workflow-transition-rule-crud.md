# FR-WF-06 D1·D2·D4·D5 — 워크플로우 전환 규칙(validator) CRUD API

> 티어: T2
> slug: backend-workflow-transition-rule-crud
> type: api
> agent: backend-engineer
> 생성: 2026-08-25

## Brief

Maxi 원문 — 「fr-wf-06 진행하자」.

FR-WF-06 은 전환 규칙(조건/검증기/후처리)을 화면에서 편집하게 하는 FR 이다. 엔진은 이미 규칙을
`workflow_validators` / `workflow_post_actions` (`type` + `config` JSONB) 에서 읽고, Jira 의 조건/검증기
구분도 `ValidatorPhase` 로 표현돼 있다. **없는 것은 편집 수단뿐**이라 CRUD API 와 화면만 얹는다.

**이번 PR 범위 = D1 · D2 · D4 · D5 (백엔드).** D6(편집 다이얼로그)·D7(E2E)은 후속 PR —
FR-WF-04·FR-WF-05 가 쓴 분할 관례를 따른다 (Maxi 결정, 2026-08-25).
**D3 비해당** — 두 테이블 모두 V200 기존 테이블이고 스키마 변경이 없다 (착수 시 실측 재확인 완료).

### classify 결과

`{ type: api, agent: backend-engineer, primary_bc: project-workflow, tier(title-only): T1 }`
→ **선언 티어 T2**. `detect-tier.ts` 로 예상 변경 경로를 실측하면 `API` · `BE_MAIN` · `TEST` · `DOC`
표면이 잡히고 tier=T2, `unmapped` 0 건이다. classify 의 T1 은 제목만 보는 추정이라 채택하지 않는다.

### 착수 시점 실측 (파일 존재 ≠ 기능 존재)

learnings 2026-07-17 「REST 노출이 없으면 기능이 없는 것이다」에 따라 컨트롤러의 HTTP 매핑을 직접 셌다.

- project-workflow 컨트롤러 **6종** — `WorkflowController` · `WorkflowStatusCompositionController` ·
  `StatusController` · `WorkflowSchemeController` · `ProjectWorkflowSchemeController` ·
  `PostActionController`. **validator 컨트롤러는 0종**이다.
- validator 구현체 4종(`RequiredFieldValidator` · `PermissionValidator` · `NotStatusCategoryValidator` ·
  `CustomExpressionValidator`)과 팩토리(`DefaultWorkflowValidatorFactory`)는 있다. **읽기 경로만 있고
  쓰기 경로가 없다.**
- post-action 쪽은 `PostActionAdminService` · `PostActionRepository` · `PostActionController` 가 이미 있고,
  `PostActionTransitionResolver.resolveById` 가 **UUID 세그먼트를 이미 받는다** → D2 의
  「post-action 경로를 transitionId 로 정렬」은 상당 부분 선반영 상태다. 스펙 단계에서 잔여분을 확정한다.

### 부수 결정 — type 표기는 코드가 정본 (Maxi 결정, 2026-08-25)

SDD §7.3·§7.4 표와 코드 실측이 **9행 중 7행** 어긋나 있다.

| 코드 실측 (`override val type`) | SDD 표 | 일치 |
|---|---|---|
| `RequiredField` | `RequiredField` | ✅ |
| `permission-check` | `Permission` | ❌ |
| `not-status-category` | `NotStatusCategory` | ❌ |
| `CustomExpression` | `CustomExpression` | ✅ |
| `SET_FIELD` `ADD_WATCHER` `NOTIFY` `CALL_WEBHOOK` `RUN_AUTOMATION` | `SetField` `AddWatcher` `Notify` `CallWebhook` `RunAutomation` | ❌ ×5 |

**코드를 정본으로 두고 SDD 표를 실측에 맞춘다.** 코드 표기를 바꾸면 별칭 경로·기존 DB 행 확인·씨앗
재검증이 붙어 범위가 늘고, FR-WF-06 이 선언한 「CRUD 와 화면만 얹는다」를 벗어난다.
대신 **표 ↔ 팩토리 `when` 분기를 대조하는 판별식**을 새로 넣어 재발을 막는다 —
메모리 `two-lists-never-check-each-other` 의 지배 결함 양식이고, 처방은 차집합 판별식 + 비-공허 짝이다.

## 도메인 정리

**BC**. `project-workflow` 단일. 다른 BC 를 건드리지 않는다 — issue-tracking 은 전환 실행 시
`WorkflowEngine` 을 통해 규칙을 **소비**할 뿐이고 이 PR 이 만드는 것은 **쓰기 경로**다.

**영향 테이블**. `workflow_validators` (V200 기존). `id · transition_id(FK CASCADE) · type · config JSONB ·
display_order · created_at · updated_at`. **스키마 변경 0 → D3 비해당 확정.**

**영향 컴포넌트**.

| 컴포넌트 | 이 PR 에서 | 이유 |
|---|---|---|
| `DefaultWorkflowValidatorFactory` | **재사용**(dry-run 검증) | 지원 type 목록의 정본이다. 서비스가 목록을 사본으로 들면 두 목록이 갈린다 |
| `DefaultWorkflowDefinitionRepository.findValidators` | 무변경 | 읽기 경로. 전환 실행 시 DB 직접 조회 |
| `WorkflowEngine` | 무변경 | 규칙 평가 주체 |
| `WorkflowCache` | **무접촉** | 아래 「캐시」 참조 |
| `PostActionTransitionResolver` | **개명 후 재사용** | 아래 「전환 해석기」 참조 |

**캐시 — 무효화 불필요, 근거는 실측이다.**
`Workflow` aggregate 는 `key · name · description · states · transitions` 5필드뿐이고 validator 를 담지
않는다(`domain/Workflow.kt:18-23`). `WorkflowRepository.kt:28` 이 「validator / post_action 컬렉션은
Workflow aggregate 책임 외」라고 직접 적었고, 전환 실행 시 `findValidators` 가 **DB 를 직접** 친다.
따라서 validator CRUD 는 `WorkflowCache.invalidate` 를 부르지 않는다.

> ⚠ **기존 주석 1줄이 틀렸다.** `PostActionAdminService.kt:25` 가 「WorkflowCache 는
> states/transitions/**validator** 만 캐싱」이라고 적어 두었다. validator 는 캐싱되지 않는다.
> 이 문장을 그대로 믿으면 validator CRUD 구현자가 **없어도 되는 캐시 무효화를 넣거나**, 반대로
> 「캐시된다」를 근거로 잘못된 결론을 낸다. 바로 그 다음 독자가 이 PR 이므로 **같은 PR 에서 정정**한다.

**전환 해석기 — 개명 후 공용화.**
`PostActionTransitionResolver` 는 이름만 post-action 이고 내용은 전환 지목값 해석 전용이다
(UUID 우선 → 합성 키 폴백 · 신/구 컬럼 우선순위 · GLOBAL/INITIAL 의 `KIND__to` 처리).
validator 도 같은 경로 계약을 쓰므로 **`TransitionKeyResolver` 로 개명**해 `com.bts.workflow.transition`
패키지로 옮긴다. 참조 5파일(main 3 · test 2)의 기계적 치환이고 동작 변경 0 이다.
사본을 뜨면 240줄이 두 벌이 되고 V207 후속 변경이 한쪽만 반영되는 결함이 열린다.

> 이 개명은 **분리 가능한 task** 로 둔다. 게이트 1 에서 Maxi 가 빼기로 하면
> `ValidatorAdminService` 가 `PostActionTransitionResolver` 를 그대로 주입받는 것으로 대체한다.

**새 용어**. 없음. `glossary.md` 무변경.

**관련 ADR 4건**.

| ADR | 이 PR 과의 관계 |
|---|---|
| [validator-terminology](../adr/2026-05-21-workflow-validator-terminology.md) | Validator 용어 채택. **클래스 명명**만 정했고 런타임 `type` 문자열은 안 정했다 — SDD 표를 코드에 맞추는 이번 결정이 이 ADR 과 충돌하지 않는 근거다 |
| [expression-parser-spel](../adr/2026-05-21-workflow-expression-parser-spel.md) | `CustomExpression` 의 평가 엔진 |
| [transition-id-identity](../adr/2026-08-18-workflow-transition-id-identity.md) | 전환 1급 식별자 = `transitionId`. 경로 설계의 근거 |
| [workflow-bc-cross-bc-port](../adr/2026-05-21-workflow-bc-cross-bc-port.md) | `permission-check` 가 쓰는 outbound port |

**기존 결정과의 충돌 1건 — 해소됨.**
`expression/SpelEvaluator.kt:32-36` 이 「일반 사용자가 API 를 통해 임의 표현식을 전달하는 경로를
**절대로** 만들지 않는다」고 적었다. validator CRUD API 는 그 경로를 정확히 만든다.
Maxi 판단(2026-08-25)으로 **`CustomExpression` 을 편집 대상에서 제외**해 계약을 그대로 지킨다.
ADR 신설 불필요 · 티어 T2 유지.

## Jira 대조

FR type 은 `api` 라 이 절이 필수는 아니나, **Maxi 가 「지라 클라우드와 동일 스펙」을 요구**해
리서치를 선행했고 그 결과가 `CustomExpression` 제외 결정의 근거다. 그래서 남긴다.

### Jira Cloud 내장 validator 9종 (company-managed)

| 이름 | 검사 | 관리자 입력 | BTS 대응 |
|---|---|---|---|
| Field Required | 필드 비었으면 차단 | 필드 | **`RequiredField`** |
| Field has been modified | 전환 중 그 필드가 바뀌어야 함 | 필드 | 없음 |
| Field has single value | 다중선택 값 1개 이하 | 필드 | 없음 |
| Permission | 권한 보유 | 권한 | **`permission-check`** |
| Parent Status | 부모가 특정 상태 | 상태 | 없음 |
| Previous State | 과거에 특정 상태 경유 | 상태 | 없음 |
| Date Compare · Date Window | 날짜 비교 | 필드 2 (+일수) | 없음 |
| Regular Expression Check | 필드가 정규식과 일치 | **정규식 문자열** | 없음 |

BTS 의 `not-status-category`(출발 상태 카테고리 차단)는 Jira 내장 9종에 정확한 대응이 없다 —
Jira 는 그 성격을 condition(`Value Field` · `Previous Status`) 쪽에 둔다.

### 자유 표현식은 Jira 코어에 없다

- **관리자용 입력칸이 있는 건 정규식 하나뿐**이다. 조건식·스크립트를 타이핑하는 칸은 9종 어디에도 없다.
- Jira expression 기반 condition/validator 는 **Forge/Connect 앱 개발자**용 모듈이다 — 앱이
  manifest/descriptor 에 표현식을 **미리 선언**해 넣는다. 관리자가 쓰는 물건이 아니다.
- 관리자가 자유 표현식을 쓰려면 마켓플레이스 앱(ScriptRunner · JMWE · Jira Workflow Toolbox 등)을
  따로 산다.

→ **`CustomExpression` 을 화면에 노출하지 않는 것이 Jira 와 같은 스펙이다.** Atlassian 은 커스텀
요구를 자유 표현식이 아니라 **목적 특화 규칙**(정규식 검사 · 날짜 비교 · 부모 상태)으로 푼다.

### 새 편집기 구조 — D6(후속 PR) 에 넘길 관찰

- Jira 는 **2026-07-26 에 구 워크플로우 편집기를 완전히 제거**한다.
- 새 편집기는 conditions · validators · post functions 를 **「Rules」 한 이름**으로 묶고,
  전환을 다이어그램에서 고르면 **오른쪽 details 사이드 패널**에서 편집한다(다이얼로그 아님).
- 패널 안 4그룹 — **Restrict transition** / **Request input** / **Validate details** / **Perform actions**.
- BTS 는 이미 post-action 편집을 **다이얼로그**로 구현했다(`PostActionFormDialog.tsx` ·
  `routes/workflows.$key.tsx`). `product/project-workflow.md` §2.6 D6 도 「다이얼로그」로 적혀 있다.
  **패널로 옮기려면 post-action UI 도 함께 옮겨야 하므로 D6 PR 에서 Maxi 가 결정한다** — 이 PR 은
  백엔드만이라 영향 없다.

> 후속 FR 후보 2건을 장부에 남긴다. ① Jira `Regular Expression Check` 대응(자유 표현식 대신 목적
> 특화 규칙) ② Jira 9종 대비 BTS 4종 — validator 종류 확충.

## 스펙

### 사용자 시나리오 (Given-When-Then)

**S1. 규칙을 건다.**
Given 시스템 관리자가 `software-default` 워크플로우의 `in-progress → done` 전환을 보고 있다
When `type=RequiredField`, `config={"field":"resolution"}` 로 규칙을 만든다
Then 201 과 생성된 규칙이 돌아오고, 그 전환을 **실행**하면 `resolution` 이 비어 있을 때 차단된다.

**S2. 규칙을 푼다.**
Given S1 의 규칙이 걸려 있다
When 그 규칙을 삭제한다
Then 204 가 돌아오고, 같은 전환이 `resolution` 없이도 통과한다.

**S3. 잘못된 설정은 저장되지 않는다.**
Given 시스템 관리자
When `type=RequiredField` 인데 `config` 가 `{}` 다
Then 400 `WORKFLOW_VALIDATOR_INVALID` 이고 DB 에 행이 생기지 않는다.

**S4. 조건식은 화면에서 만들 수 없다.**
Given 시스템 관리자
When `type=CustomExpression` 으로 생성을 시도한다
Then 400 `WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE` 이다.
그러나 **YAML seed 로 들어간 기존 CustomExpression 행은 목록에 보이고 삭제도 된다.**

**S5. 권한 없는 사용자.**
Given `MANAGE_SCHEME`/Global 이 없는 사용자
When 목록 조회를 포함한 **어떤 요청이든** 보낸다
Then 403 이고, 워크플로우·전환이 실재하는지 **알아낼 수 없다**(존재 probe 차단).

### 기능 요구사항 (FR)

| ID | 요구 |
|---|---|
| FR-1 | 전환 단위 validator 목록 조회 (`display_order ASC`) |
| FR-2 | validator 생성 — `type` · `config` · `displayOrder` |
| FR-3 | validator 수정 — 같은 3필드 |
| FR-4 | validator 삭제 |
| FR-5 | 지원하지 않는 `type` · 필수 config 키 누락은 **400**, 저장 없음 |
| FR-6 | `CustomExpression` 은 생성·수정 **400**, 목록·삭제는 허용 |
| FR-7 | 전환 지목은 `transitionId`(UUID) 1급 · 구 합성 키 `from__to` 하위호환 |
| FR-8 | 저장한 규칙이 **실제 전환에서 동작**한다 (엔진 통합) |
| FR-9 | SDD §7.3·§7.4 표를 코드 실측 `type` 에 맞추고, **표↔팩토리 대조 판별식**을 CI 에 건다 |

### 비기능 요구사항 (NFR)

- **권한**. 4개 엔드포인트 전부 `MANAGE_SCHEME` + `Global`. **리소스 조회보다 먼저** 검증한다
  (메모리 `permission-assert-before-existence-makes-403-lie` · `auth-extraction-before-resource-lookup`).
- **예외 메시지**. 403 응답 본문에 actorId·permission·scope 를 싣지 않는다
  (메모리 `fr-pm-04-guard-exception-message-http-leak`). 상세는 로그에만.
- **IDOR**. `{id}` 가 그 `transitionId` 소속인지 확인한 뒤에만 수정·삭제한다.
- **트랜잭션**. 쓰기는 `@Transactional`, 읽기는 `readOnly = true`. 다중 BC 트랜잭션 없음.
- **성능**. `idx_workflow_validators_transition` 기존 인덱스로 충분. 신규 인덱스 없음.

### API 인터페이스 (REST)

기준 경로 `/api/v1/workflows/{workflowKey}/transitions/{transitionKey}/validators`.
**post-action 경로와 형제 관계**이며 세그먼트 규칙이 동일하다.

| 조작 | 메서드 · 경로 | 요청 | 응답 |
|---|---|---|---|
| 목록 | `GET .../validators` | — | 200 `{data: ValidatorResponse[]}` |
| 생성 | `POST .../validators` | `{type, config, displayOrder}` | 201 `{data: ValidatorResponse}` |
| 수정 | `PUT .../validators/{id}` | `{type, config, displayOrder}` | 200 `{data: ValidatorResponse}` |
| 삭제 | `DELETE .../validators/{id}` | — | 204 무본문 |

`transitionKey` — UUID 로 파싱되면 `workflow_transitions.id`(1급). 아니면 `fromStateKey__toStateKey`
합성 키로 해석하고 **2건 이상 걸리면 404**(어느 전환인지 특정 불가 · V207 이 UNIQUE 를 풀었다).

`ValidatorResponse` = `{id, type, config, displayOrder}`.

> **`phase` 는 이번에 노출하지 않는다.** `ValidatorPhase`(AVAILABILITY/EXECUTION)는 구현체 속성이라
> 응답에 실으려면 읽기 시점에 팩토리로 인스턴스를 만들어야 하고, DB 에 손으로 넣은 깨진 config 행
> 하나가 **목록 전체를 500 으로 만든다**. 필드 추가는 하위호환이므로 D6 가 실제로 필요할 때 넣는다.
> ★**D6 는 프론트에 `type → phase` 표를 만들지 말 것** — 그 순간 팩토리와 갈리는 두 번째 목록이 된다.

**에러 계약.**

| 코드 | HTTP | 조건 |
|---|---|---|
| `WORKFLOW_SCHEME_ACCESS_DENIED` | 403 | `MANAGE_SCHEME`/Global 없음 |
| `WORKFLOW_VALIDATOR_INVALID` | 400 | 미지원 type · 필수 config 키 누락 · config 타입 불일치 |
| `WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE` | 400 | `type=CustomExpression` 으로 생성·수정 시도 |
| `WORKFLOW_VALIDATOR_NOT_FOUND` | 404 | 전환 미존재 · 키 형식 오류 · 키가 유일하지 않음 · id 가 그 전환 소속 아님 |

### 데이터 모델 변경

**없다.** `workflow_validators` 는 V200 기존 테이블이고 컬럼·인덱스·제약 모두 그대로다.
마이그레이션 파일을 만들지 않는다 → **D3 비해당**.

### 엣지 케이스

| # | 상황 | 처리 |
|---|---|---|
| E1 | 같은 상태쌍에 전환이 2개인데 합성 키로 지목 | 404 (특정 불가). `transitionId` 로 다시 부르라는 사유를 로그에 |
| E2 | GLOBAL·INITIAL 전환 (출발 상태 없음) | 합성 키가 `GLOBAL__to` 형태. `TransitionKeyResolver` 의 `KIND_TOKENS` 경로가 처리 |
| E3 | 남의 워크플로우 전환 id 를 지목 | 404. 해석 질의가 `workflowKey` 소속을 같은 SQL 에서 확인 |
| E4 | 남의 전환에 속한 validator id 로 수정·삭제 | 404 (IDOR 차단) |
| E5 | `config` 에 여분 키가 섞임 | 통과. 팩토리가 필요한 키만 읽는다 — 여분 키 거부는 요구에 없다 |
| E6 | `not-status-category` 의 `category` 가 알 수 없는 값 | 400. 팩토리가 `StateCategory.valueOf` 실패를 던진다 |
| E7 | `permission-check` 의 `scope` 생략 | 통과. 기본값 `ISSUE` |
| E8 | 기존 `CustomExpression` 행이 있는 전환의 목록 조회 | 200. **그 행도 함께 보인다** — 반쪽 목록은 관리자를 속인다 |
| E9 | 전환이 삭제됨 | `ON DELETE CASCADE` 로 validator 행도 사라진다 (V200 기존 동작) |

### 제약 조건

1. **BC 격리**. project-workflow 밖 모듈을 import 하지 않는다. `shared-kernel` 의 권한 타입만 쓴다.
2. **지원 type 목록의 사본 금지**. 허용 여부는 `DefaultWorkflowValidatorFactory.create` dry-run 이
   정한다. 서비스에 `setOf("RequiredField", …)` 같은 목록을 두지 않는다.
3. **`CustomExpression` 거부는 문자열 비교로 하지 않는다.** 팩토리가 만든 인스턴스가
   `CustomExpressionValidator` 인지 **타입으로** 판정한다 — 문자열을 복사하면 세 번째 사본이 된다.
4. **SpEL 을 평가하지 않는다.** dry-run 은 인스턴스 생성까지고 `evaluate` 를 부르지 않는다.
5. `docs/sdd/07-workflow-engine.md` §7.3·§7.4 표는 **런타임 `type` 식별자**를 정본으로 적고
   구현 클래스명을 병기한다. ADR `validator-terminology` 가 정한 **클래스 명명은 건드리지 않는다**.

### 측정 가능한 완료 기준

| # | 기준 | 확인 방법 |
|---|---|---|
| C1 | 4개 엔드포인트가 실재한다 | `ValidatorController` 의 `@*Mapping` 4개를 센다 (파일 존재 아님) |
| C2 | 권한 없는 호출이 4개 전부 403 | 컨트롤러 테스트 4건 |
| C3 | 잘못된 config 400 · DB 행 0 | 서비스 테스트 + 리포지토리 카운트 |
| C4 | `CustomExpression` 생성·수정 400, 목록·삭제 200/204 | 서비스 테스트 4건 |
| C5 | **저장한 규칙이 실제 전환을 막고, 지우면 통과** | 엔진 통합 테스트 (Testcontainers) |
| C6 | 합성 키 2건 → 404 · `transitionId` → 200 | 서비스 테스트 2건 |
| C7 | SDD 표 ↔ 팩토리 분기 차집합 0 | 신규 판별식 + **비-공허 짝**(일부러 끊어 red 1회 확인) |
| C8 | `PostActionAdminService.kt:25` 의 오기 정정 | 문자열 grep |

## Sanity Check

작성한 스펙을 스스로 흔들었다. gap 4건 발견 → 3건은 스펙에 흡수, 1건은 Maxi 결정으로 이미 해소.

**❓ 발견 1 — `RequiredField` 는 전환 목록을 막지 않는다.**
`RequiredFieldValidator.phase = EXECUTION` 이라 `availableTransitions` 조회에서는 평가되지 않는다.
나머지 3종은 `AVAILABILITY` 기본값이다. C5 통합 테스트를 `RequiredField` 로 쓰면서 **목록 조회로
검증하면 규칙이 없어도 초록**이 된다 — 도달 불가 조합을 지키는 가짜 그린이다
(메모리 `unreachable-state-fixture-is-fake-green`).
→ **처방.** C5 는 **전환 실행 경로**로 검증한다. 목록 경로 검증이 필요하면 `not-status-category` 를 쓴다.
두 phase 를 각각 한 번씩 덮는다.

**❓ 발견 2 — 「저장한 규칙이 동작한다」가 캐시 때문에 거짓이 될 수 있었다.**
초안에서 캐시 무효화를 다루지 않았다. 실측으로 validator 는 비캐시임을 확인했으나, **그 근거를
스펙에 적지 않으면** 다음 사람이 다시 판단해야 하고 `PostActionAdminService.kt:25` 의 틀린 주석을
읽고 잘못 결론낸다.
→ **처방.** 「도메인 정리 §캐시」에 근거 3곳(`Workflow.kt:18-23` · `WorkflowRepository.kt:28` ·
`findValidators` DB 직접 조회)을 명시하고, 틀린 주석 정정을 C8 로 완료 기준에 올렸다.

**❓ 발견 3 — 400 을 「알 수 없는 type」과 「편집 불가 type」이 공유하면 화면이 구분을 못 한다.**
D6 는 「이 종류는 화면에서 못 만든다」와 「설정이 틀렸다」를 다르게 안내해야 한다.
→ **처방.** 에러 코드를 `WORKFLOW_VALIDATOR_INVALID` / `WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE` 로 갈랐다.

**✅ 해소 — SpelEvaluator 절대 계약 충돌.**
Maxi 결정(2026-08-25)으로 `CustomExpression` 을 편집 대상에서 제외. Jira Cloud 실측이 같은 결론을
지지한다(내장 9종에 자유 표현식 입력칸 없음).

**남은 gap 없음.** 2회째 흔들기에서 새 gap 이 나오지 않았다.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
