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
| `PostActionRepository` | **공통 기반으로 이행** | 게이트 1 결정 ⓑ. 두 테이블 컬럼이 같아 `TransitionRuleRepository` 를 뽑고 둘 다 얹는다 |
| `web/ErrorResponse`·`ErrorBody` | **재사용** | 게이트 1 결정 ⓐ. 사본을 3벌로 늘리지 않는다 |

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
| FR-9 | SDD §7.3·§7.4 표를 코드 실측 `type` 에 맞추고, **표↔팩토리 대조 판별식**을 건다 |

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

`ValidatorResponse` 필드.

| 필드 | 타입 | 설명 |
|---|---|---|
| `id` | UUID | validator 식별자 |
| `type` | string | validator 타입 식별자. 정본은 팩토리의 `when` 분기 |
| `config` | object | 타입별 설정 Map |
| `displayOrder` | int | UI 표시 순서 |
| `phase` | string \| null | 평가 시점 — `"AVAILABILITY"` / `"EXECUTION"` / `null` (아래) |

> **`phase` 는 노출한다** (Task 14 · 게이트 2 반영). 이 값은 **규칙이 전환 버튼을 감추는지
> (AVAILABILITY) 눌렀을 때 막는지(EXECUTION)** 를 가른다. 4종 중 `RequiredFieldValidator` 만
> EXECUTION 을 명시 override 하고 나머지 3종은 SPI 기본값 AVAILABILITY 를 상속하므로
> **`type` 문자열만으로는 판별할 수 없다.** 값의 출처는 팩토리가 만든 **인스턴스의 속성** 하나다.
>
> **`null` 이 언제 나오나.** 그 행으로 인스턴스를 만들 수 없을 때다 — 손으로 넣은 깨진 config ·
> 팩토리에서 사라진 type · config 키 변경. 「phase 가 없다」가 아니라 **「알 수 없다」**는 뜻이다.
> 실패는 `ValidatorController.phaseOf` 의 행 단위 `catch (IllegalArgumentException)` 안에 갇힌다 —
> **그 행만 `null` 이고 목록 전체는 200** 이다. (`runCatching` 은 `Error` 까지 삼켜 쓰지 않는다.)
>
> ★**프론트(D6)에 `type → phase` 표를 만들지 마라 — 응답이 준다.** 표를 만들면 팩토리의 `when`
> 분기와 각 구현체의 `override val phase` 에 이은 **세 번째 사본**이 되고, 세 목록은 서로를
> 검사하지 않으므로 갈린 사실이 드러나지 않는다.
>
> (초판이 적은 제외 근거 「깨진 config 행 하나가 목록 전체를 500 으로 만든다」는 **틀렸다** —
> `engine/WorkflowEngine.kt` 의 availableTransitions 가 같은 행들에 대해 같은 `create` 를
> try/catch 없이 이미 부른다. 그 근거는 두 경로를 가르지 못한다.)

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

> **판별식의 기계 강제 지점은 `pre-push` 다.** `workflow-scripts-ci.yml` 은 2026-08-21 부터
> `workflow_dispatch` 전용이라 자동 실행이 없다. `.husky/pre-push` 가 `scripts/**/*.test.{ts,mjs}`
> **전량을 무조건** 돌리므로 새 판별식을 그 아래 두는 것만으로 배선이 끝난다 — CI `paths` 를
> 고칠 자리가 없다. (2026-08-25 실측 판별식 412건 · 21.0초)
| C8 | 낡은 주석 2건 정정 — `PostActionAdminService.kt:25` 캐시 오기 · `DefaultWorkflowValidatorFactory.kt:26-31` prod 경고 | 문자열 grep 각 0건 |
| C9 | **post-action 회귀 0** — 공통 기반 이행 전후 `*PostAction*` 테스트 같은 결과 | 이행 전 초록 확인 → 이행 → 재실행 대조 |

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

## Plan

경로 접두. `BE_MAIN = backend/modules/project-workflow/src/main/kotlin/com/bts/workflow`
· `BE_TEST = backend/modules/project-workflow/src/test/kotlin/com/bts/workflow`

### Task 1. `PostActionTransitionResolver` 를 `TransitionKeyResolver` 로 개명·이동

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/PostActionTransitionResolver.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/transition/TransitionKeyResolver.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/PostActionAdminService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/WorkflowWriteRepository.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/PostActionAdminServiceTest.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/PostActionE2EIntegrationTest.kt`]
- depends-on: []

**RED**: 없음 — **순수 리팩터**다. 안전망은 **기존 post-action 테스트 전량**이고, 개명 **전** 초록을 먼저 본 뒤 개명 **후** 같은 결과여야 한다.

**GREEN**:
- `postaction/PostActionTransitionResolver.kt` → `transition/TransitionKeyResolver.kt` (`git mv`)
- 클래스명·패키지 선언·L1 주석만 갱신. **본문 로직은 한 줄도 바꾸지 않는다**
- 참조 4파일의 import·타입만 치환

**REFACTOR**: KDoc 의 「post-action 경로가 받은」을 「규칙 경로가 받은」으로. 내용 동일.

**검증**: `./gradlew :modules:project-workflow:test --tests '*PostAction*'` — **개명 전후 같은 결과**.
`grep -rn 'PostActionTransitionResolver' backend/` 가 0건.

### Task 2. `TransitionRuleRepository` 공통 기반 추출 + `PostActionRepository` 이행

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/transition/TransitionRuleRepository.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/PostActionRepository.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/PostActionRepositoryIntegrationTest.kt`]
- depends-on: []

> **게이트 1 결정 ⓑ 로 들어온 task 다.** 이미 운영에 나가 있는 코드를 옮기므로 **회귀 표면**이다.

**RED**: 없음 — **순수 리팩터**다. 안전망은 기존 `PostActionRepositoryIntegrationTest` 이고,
이행 **전** 초록을 먼저 본 뒤 이행 **후** 같은 결과여야 한다. red 가 나면 그것이 회귀 신호다.

**GREEN**:
- `transition/TransitionRuleRepository.kt` 신규. 두 테이블의 컬럼이 **완전히 같다**는 실측이 근거다 —
  `id · transition_id · type · config JSONB · display_order · created_at · updated_at`
  ```kotlin
  data class TransitionRuleRow(
      val id: UUID, val transitionId: UUID, val type: String,
      val config: Map<String, Any?>, val displayOrder: Int,
  )

  abstract class TransitionRuleRepository(
      protected val dsl: DSLContext,
      private val objectMapper: ObjectMapper,
      private val table: Table<*>,
      private val idField: TableField<*, UUID?>,
      private val transitionIdField: TableField<*, UUID?>,
      private val typeField: TableField<*, String?>,
      private val configField: TableField<*, JSONB?>,
      private val displayOrderField: TableField<*, Int?>,
  ) {
      fun findByTransitionId(transitionId: UUID): List<TransitionRuleRow>
      fun insert(transitionId: UUID, type: String, config: Map<String, Any?>, displayOrder: Int): TransitionRuleRow
      fun update(id: UUID, type: String, config: Map<String, Any?>, displayOrder: Int): TransitionRuleRow
      fun deleteById(id: UUID)
  }
  ```
- `PostActionRepository` 는 그 위에 얹는다.
  ```kotlin
  typealias PostActionRow = TransitionRuleRow   // ★ 호출부 무변경 방어

  @Repository
  class PostActionRepository(dsl: DSLContext, objectMapper: ObjectMapper) :
      TransitionRuleRepository(dsl, objectMapper, WORKFLOW_POST_ACTIONS, ID, TRANSITION_ID, TYPE, CONFIG, DISPLAY_ORDER)
  ```
- ★**`typealias` 가 이 task 의 핵심 방어다.** `PostActionRow` 는 서비스 KDoc 1곳과 **테스트 3파일**에서
  같은 5인자 생성자로 호출된다(실측 — `PostActionAdminServiceTest` 5회 · `PostActionControllerTest` 3회).
  필드 이름·순서·타입이 그대로라 typealias 만으로 **호출부가 한 줄도 안 바뀐다.**
  이름을 바꾸거나 필드를 재배치하면 그 순간 회귀 표면이 8곳으로 벌어진다

**REFACTOR**: 기반 클래스에 KDoc — 두 테이블이 같은 모양인 이유(전환 규칙 2종)와 `typealias` 근거.

**검증**:
- `./gradlew :modules:project-workflow:test --tests '*PostAction*'` — **이행 전후 같은 결과** (회귀 대조)
- `git diff --stat` 에서 `PostActionAdminService.kt`·`PostActionController*`·테스트 3파일이 **무변경**

### Task 3. `ValidatorRepository` — 공통 기반 위에 얹는다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/ValidatorRepository.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/ValidatorRepositoryIntegrationTest.kt`]
- depends-on: [2]

**RED**:
- 파일. `BE_TEST/validator/ValidatorRepositoryIntegrationTest.kt` (Testcontainers)
- 테스트 4건.
  ```kotlin
  @Test fun `findByTransitionId 는 display_order ASC 로 돌려준다`()
  @Test fun `insert 한 행을 config JSONB 그대로 읽는다`()
  @Test fun `update 가 type · config · displayOrder 를 바꾼다`()
  @Test fun `deleteById 후 findByTransitionId 가 그 행을 빼고 돌려준다`()
  ```
- 실패 메시지 (예상). `ValidatorRepository` 클래스 없음

> `transition_id` 는 `workflow_transitions` 로 향하는 FK(ON DELETE CASCADE)다. **전환 행 픽스처를
> 먼저 넣어야** INSERT 가 성립한다(리뷰 C3).

**GREEN**: `PostActionRepository` 와 **같은 모양**으로 만든다 — 기반 클래스 + `override` 4종.

```kotlin
typealias ValidatorRow = TransitionRuleRow

@Repository
class ValidatorRepository(dsl: DSLContext, objectMapper: ObjectMapper) :
    TransitionRuleRepository(dsl, objectMapper, WORKFLOW_VALIDATORS, ID, TRANSITION_ID, TYPE, CONFIG, DISPLAY_ORDER) {

    @Transactional(readOnly = true)
    override fun findByTransitionId(transitionId: UUID) = super.findByTransitionId(transitionId)
    // insert · update · deleteById 도 같은 형태로 @Transactional + override + super 위임
}
```

> ★**`@Transactional` 은 반드시 이 구체 클래스에 둔다. 기반 클래스에 두면 안 된다** (Task 2 실측).
> 근거 2겹. ① `ProjectWorkflowArchitectureTest` 룰 1 이 `@Transactional` 메서드를 가진 **비-interface
> 클래스**에 stereotype 을 요구한다 — 추상 기반에 두면 즉시 red 다(Task 2 가 실제로 밟았다)
> ② kotlin-spring(all-open)은 `@Repository` 가 붙은 **그 클래스**의 멤버만 열고 상위를 거슬러 열지
> 않는다. 기반 메서드가 `final` 이면 CGLIB 가 재정의를 못 해 트랜잭션이 **무음 실패**한다 —
> 그래서 기반은 `open`, 경계는 구체 클래스다
> (메모리 `kotlin-allopen-skips-superclass-transactional`)

**REFACTOR**: L1 주석 · KDoc.

**검증**.
- `./gradlew :modules:project-workflow:test --tests '*ValidatorRepositoryIntegrationTest'` → EXIT 0
- ★**모듈 전량 1회** `./gradlew :modules:project-workflow:test` → EXIT 0.
  `--tests '<패턴>'` 필터는 **ArchUnit·의존성 가드를 패턴 밖으로 흘려보낸다** — 코드 전체를 스캔하는
  테스트라 기능 패턴에 절대 안 걸린다. Task 2 가 정확히 이것 때문에 DRIFT 판정을 받았다.
  순수 리팩터라도 모듈 전량이 마지막 관문이다

### Task 4. `ValidatorAdminService` — 전환 해석 + type/config 검증 + 편집 불가 타입

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/ValidatorAdminService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/ValidatorAdminExceptions.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/transition/TransitionKeyResolver.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/ValidatorAdminServiceTest.kt`]
- depends-on: [1, 3]

**RED**:
- 파일. `BE_TEST/validator/ValidatorAdminServiceTest.kt`
- 테스트 9건 — 스펙 §엣지 케이스와 1:1.
  ```kotlin
  @Test fun `미지원 type 은 ValidatorValidationException`()                  // FR-5
  @Test fun `RequiredField 에 field 키가 없으면 ValidatorValidationException`()
  @Test fun `not-status-category 의 category 가 알 수 없는 값이면 400`()      // E6
  @Test fun `permission-check 는 scope 를 생략해도 통과한다`()                // E7
  @Test fun `CustomExpression 생성은 ValidatorTypeNotEditableException`()     // FR-6
  @Test fun `기존 행의 type 을 CustomExpression 으로 수정해도 400`()          // FR-6 · 리뷰 C1
  @Test fun `CustomExpression 행도 목록에 나오고 삭제된다`()                  // E8
  @Test fun `합성 키가 2건에 걸리면 ValidatorNotFoundException`()             // E1
  @Test fun `남의 전환에 속한 id 로 수정하면 ValidatorNotFoundException`()    // E4 · IDOR
  ```
- 실패 메시지 (예상). `ValidatorAdminService` 클래스 없음

**GREEN**:
- `PostActionAdminService` 의 구조를 따른다 — `resolveOrThrow` → `validateConfig` → repository
- **`validateConfig` 는 목록을 들지 않는다.** `DefaultWorkflowValidatorFactory.create(type, config)`
  dry-run 이 미지원 type·필수키 누락을 `IllegalArgumentException` 으로 던지고, 그것을
  `ValidatorValidationException` 으로 감싼다 (스펙 §제약 2)
- **편집 불가 판정은 문자열이 아니라 타입으로 한다** (스펙 §제약 3).
  ```kotlin
  val instance = factory.create(type, config)          // 미지원 type · config 오류 → 400
  if (instance is CustomExpressionValidator) throw ValidatorTypeNotEditableException(type)
  ```
  문자열 `"CustomExpression"` 을 이 파일에 적지 않는다 — 적는 순간 팩토리 분기와
  `CustomExpressionValidator.type` 에 이은 **세 번째 사본**이 된다
  (근거. 메모리 `two-lists-never-check-each-other`)
- **`create` 와 `update` 가 같은 `validateConfig` 를 탄다.** 한쪽만 태우면 리뷰 C1 이 다시 열린다
- **`evaluate` 를 부르지 않는다** (스펙 §제약 4). 생성까지가 dry-run 이다

**REFACTOR**: 예외 3종을 `ValidatorAdminExceptions.kt` 로 분리 · KDoc 에 검증 순서 명시.

> **Task 1 이 여기로 미뤄 둔 KDoc 1건을 함께 닫는다.**
> `transition/TransitionKeyResolver.kt:94` 가 「몇 건인지를 보고 404 로 바꿀지 결정하는 것은
> 호출자([PostActionAdminService])의 몫이다」라고 적는다. Task 1 의 개명으로 패키지가 갈려 KDoc
> 링크가 이미 끊겼고, **이 task 가 `ValidatorAdminService` 를 두 번째 호출자로 만들면 문장 자체가
> 부정확해진다.** 호출자를 하나로 지목하지 말고 「호출자」로 일반화한다. 이 1줄이 `files` 에
> `TransitionKeyResolver.kt` 가 들어간 유일한 이유다 — 그 파일의 로직은 건드리지 않는다.

**검증**: `./gradlew :modules:project-workflow:test --tests '*ValidatorAdminServiceTest'`

### Task 5. `ValidatorController` + DTO + 예외 핸들러

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/web/ValidatorController.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/web/ValidatorDtos.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/web/ValidatorExceptionHandler.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/web/ValidatorControllerTest.kt`]
- depends-on: [4]

**RED**:
- 파일. `BE_TEST/validator/web/ValidatorControllerTest.kt`
- 테스트 8건.
  ```kotlin
  @Test fun `실재하는 전환에 권한 없이 GET 하면 403`()                        // S5
  @Test fun `실재하지 않는 전환에 권한 없이 GET 해도 같은 403 본문`()         // S5 · 리뷰 C2
  @Test fun `POST 는 권한 없으면 403`()
  @Test fun `PUT 은 권한 없으면 403`()
  @Test fun `DELETE 는 권한 없으면 403`()
  @Test fun `403 본문에 actorId · permission · scope 가 없다`()               // NFR
  @Test fun `POST 성공은 201 과 DataEnvelope 를 준다`()
  @Test fun `DELETE 성공은 204 무본문`()
  ```
- 실패 메시지 (예상). `ValidatorController` 클래스 없음

**GREEN**:
- `PostActionController` 와 같은 배치. **`requireManageScheme()` 을 리소스 조회보다 먼저** 부른다
  (근거. 메모리 `permission-assert-before-existence-makes-403-lie` — 순서가 뒤집히면 403 이
  거짓말을 하고 존재 probe 가 열린다)
- ★**에러 응답은 기존 `com.bts.workflow.web.ErrorResponse` / `ErrorBody` 를 재사용한다**
  (게이트 1 결정 ⓐ). `ValidatorErrorResponse` 를 새로 만들지 않는다 — 같은
  `{error:{code,message}}` 가 이미 `WorkflowExceptionHandler.kt:247` 에 있고
  `PostActionErrorResponse` 가 이미 사본 1벌이다
- ★**비공개는 한 케이스로 증명되지 않는다**(리뷰 C2). 실재 전환과 미실재 전환이 **둘 다 403 이고
  본문이 같아야** 성립한다 — 한쪽만 재면 순서가 뒤집혀도 초록이다
- ★**`@Order` 를 붙이지 않는다**(리뷰 A1). `AmbiguousTransitionExceptionHandler.kt:51` 이
  `@Order(HIGHEST_PRECEDENCE)` 를 쓰는 이유는 **issue-tracking 의 catch-all** 때문이고, 이 경로에는
  해당하지 않는다. `TransitionConflictExceptionHandler.kt:29-33` 이 같은 판단으로 일부러 뺐다 —
  「필요 없는 전역 우선권은 다른 advice 의 매핑을 빼앗아 응답 형식을 조용히 바꾼다」
- `@RestControllerAdvice(basePackages = ["com.bts.workflow.validator"])` 로 범위를 좁힌다
- 에러 코드 4종은 스펙 §에러 계약 표 그대로

**REFACTOR**: KDoc 에 경로·가드 순서·누출 방지 근거.

**검증**: `./gradlew :modules:project-workflow:test --tests '*ValidatorControllerTest'`
+ `grep -c '@\(Get\|Post\|Put\|Delete\)Mapping' .../ValidatorController.kt` 가 **4** (기준 C1 — 파일
존재가 아니라 매핑 수를 센다. 근거. learnings 2026-07-17 「REST 노출이 없으면 기능이 없다」)

### Task 6. 엔진 통합 — 규칙을 걸면 막히고, 풀면 통과한다

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/ValidatorEngineIntegrationTest.kt`]
- depends-on: [4]

**RED**:
- 파일. `BE_TEST/validator/ValidatorEngineIntegrationTest.kt` (Testcontainers)
- 테스트 4건 — **두 phase 를 각각 덮는다**.
  ```kotlin
  // EXECUTION — RequiredField 는 목록을 막지 않는다
  @Test fun `RequiredField 를 걸면 전환 실행이 차단된다`()
  @Test fun `그 규칙을 지우면 같은 전환 실행이 통과한다`()
  // AVAILABILITY — not-status-category
  @Test fun `not-status-category 를 걸면 전환 목록에서 사라진다`()
  @Test fun `그 규칙을 지우면 목록에 다시 나온다`()
  ```
- 실패 메시지 (예상). 규칙을 걸어도 전환이 통과 (CRUD 미배선)

> ★**이 task 의 가짜 그린 함정.** `RequiredFieldValidator.phase = EXECUTION` 이라
> `availableTransitions` 조회에서는 **평가되지 않는다**. RequiredField 를 걸고 목록 조회로
> 검증하면 규칙이 없어도 초록이다 — 도달 불가 조합을 지키는 테스트다
> (근거. 메모리 `unreachable-state-fixture-is-fake-green`).
> **RequiredField 는 실행 경로로, not-status-category 는 목록 경로로** 각각 검증한다.

**GREEN**: 구현 없음 — Task 4·5 가 만든 경로를 태운다. red 가 나면 그것이 배선 결함이다.

**REFACTOR**: 픽스처를 `@BeforeEach` 로 정리 · 규칙 생성은 **서비스 경유**(리포지토리 직접 INSERT 금지 —
그러면 CRUD 경로를 안 태우고 통과한다).

**검증**: `./gradlew :modules:project-workflow:test --tests '*ValidatorEngineIntegrationTest'`

### Task 7. SDD §7.3·§7.4 표 정정 + 표↔팩토리 대조 판별식

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/validator-type-catalog.test.ts`, `docs/sdd/07-workflow-engine.md`]
- depends-on: []

**RED**:
- 파일. `scripts/workflow/validator-type-catalog.test.ts`
- 테스트 3건.
  ```ts
  test('SDD §7.3 표의 type 집합 = DefaultWorkflowValidatorFactory when 분기 집합', …)
  test('SDD §7.4 표의 type 집합 = DefaultWorkflowPostActionFactory when 분기 집합', …)
  test('두 집합이 비어 있지 않다', …)   // 비-공허 — 파서가 0건을 뱉으면 차집합은 공허하게 0
  ```
- 실패 메시지 (예상). 현재 SDD 표가 `Permission` · `NotStatusCategory` 외 5종을 다르게 적어
  차집합 **7건** → red. **이 red 가 이 task 의 출발점이다**

> 파서가 읽을 실물은 실측했다. 두 팩토리 모두 `return when (type) {` + `"리터럴" -> createX(config)`
> 형태이고(`DefaultWorkflowPostActionFactory.kt:46-51`), SDD 표는 `| \`type\` | 용도 |` 서식이다.

**GREEN**: `docs/sdd/07-workflow-engine.md` §7.3·§7.4 표를 **런타임 `type` 식별자** 정본으로 고치고
구현 클래스명을 병기한다 (스펙 §제약 5 — ADR 이 정한 **클래스 명명은 안 건드린다**).

**REFACTOR**: 파서를 `readFileSync` + 정규식으로 두고 **손으로 유지하는 목록을 0개**로 유지.

**검증**:
- `node --experimental-strip-types --test scripts/workflow/validator-type-catalog.test.ts`
- **비-공허 짝 확인 1회.** SDD 표의 한 행을 일부러 틀리게 고쳐 **red 를 눈으로 본 뒤** 되돌린다
  (근거. 저장소 함정 「가드 수정 시 표면을 없애면 판별자도 사라진다」 · 메모리
  `invariant-satisfied-by-helptext-not-logic`). **GREEN 선커밋 뒤에 한다** — 미커밋 원복은 소실이다
- 배선. `.husky/pre-push` 가 `scripts/**/*.test.ts` 전량을 무조건 돌리므로 파일을 두는 것으로 끝난다

### Task 8. 낡은 주석 2건 정정 — 캐시 오기 + prod 권한 경고

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/PostActionAdminService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/engine/DefaultWorkflowValidatorFactory.kt`]
- depends-on: [1]

**RED**: 없음 — 주석이다. 검증은 grep.

**GREEN**. 둘 다 「이 PR 의 구현자가 읽고 잘못 판단할 자리」라 같은 PR 에서 고친다.
1. `PostActionAdminService.kt:25` — 「WorkflowCache 는 states/transitions/**validator** 만 캐싱」에서
   validator 를 뺀다. `Workflow` aggregate 는 `states`·`transitions` 만 담는다
   (`domain/Workflow.kt:18-23` · `repository/WorkflowRepository.kt:28`)
2. `DefaultWorkflowValidatorFactory.kt:26-31` — 「prod 프로파일은 `PermissionResolver` 빈 부재」를
   정정한다. `adapter/DelegatingPermissionResolver.kt:1` 이 「prod 권한 어댑터 — fail-closed」로
   실재한다 (게이트 1 결정 ⓐ · 리뷰 A3)

**REFACTOR**: 없음.

**검증**: `grep -n 'validator' .../PostActionAdminService.kt` 에 캐시 문장이 없다 ·
`grep -n '빈 부재' .../DefaultWorkflowValidatorFactory.kt` 가 0건 (기준 C8) ·
`./gradlew :modules:project-workflow:test --tests '*PostActionAdminServiceTest'` 초록 유지.

### Task 9. 이번 PR 이 만든 사본 정리 + 거짓 config 키 주석

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/transition/TransitionKeyResolver.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/PostActionAdminService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/ValidatorAdminService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/NotStatusCategoryValidator.kt`]
- depends-on: [4, 5, 6]

> **구현 중 발견으로 추가된 task 다.** 게이트 1 이후 열렸고, 둘 다 **이번 PR 이 만들었거나 이번 PR 의
> 다음 독자(D6 프론트)를 속이는** 결함이라 머지 전에 닫는다.

**RED**: 없음 — 사본 제거는 순수 리팩터, 주석은 문서다. 안전망은 기존 두 서비스 테스트 전량이다.

**GREEN**.

1. **`TRANSITION_ID_PATTERN` + `String.toTransitionIdOrNull()` 사본 2벌을 하나로.**
   Task 4 가 보고했다 — `postaction/PostActionAdminService.kt` 와 `validator/ValidatorAdminService.kt` 에
   각 15줄로 같은 짝이 있다. **이번 PR 이 두 번째 사본을 만들었다.**
   `transition/TransitionKeyResolver.kt` 로 옮겨 `internal` 로 공유한다 — 그 파일이 이미 전환 지목값
   해석의 공용 자리이고 두 서비스가 모두 그것을 주입받는다.
   > 근거. 메모리 `two-lists-never-check-each-other` — 이 저장소의 **지배 결함 양식**이다.
   > 사본은 「한쪽만 고쳐지는」 미래를 만든다. 만든 PR 에서 닫는 것이 가장 싸다.

2. **`NotStatusCategoryValidator.kt:14-15` KDoc 의 config 키가 거짓이다.**
   Task 6 이 발견했다 — KDoc 은 `config.forbidden` 이라 적는데 팩토리
   (`DefaultWorkflowValidatorFactory.kt:80`)는 `config["category"]` 를 읽는다. **코드가 정본**이다.
   > 왜 이번에 닫나. **FR-WF-06 D6 이 이 config 스키마로 설정 폼을 만든다.** 거짓 키를 읽으면 화면이
   > 저장 불가능한 config 를 만들어 400 을 맞는다. 이 PR 이 그 문서의 다음 독자를 만든 장본인이다.

**REFACTOR**: 없음.

**검증**.
- `grep -rc 'TRANSITION_ID_PATTERN' <두 서비스>` 가 각 **0**, `TransitionKeyResolver.kt` 가 **1**
- `./gradlew :modules:project-workflow:test --rerun` 모듈 전량 → EXIT 0.
  기준선 **106 클래스 · tests 756 · failures 0** 유지 (증감 0 이어야 한다 — 순수 리팩터다)
- 린트 3종 각각 `--rerun` → EXIT 0

### Task 10~17 — 게이트 2 지적 전량 반영 (Maxi 결정 2026-08-25)

> 리뷰 5종이 **26건**을 냈고 Maxi 가 「지적 전부 고치고 재리뷰」를 선택했다. 아래 8 task 가 그 전량이다.
> 렌즈별 원문은 `## 리뷰 결과 (PR 단위)` 참조.

**★ 한 건은 권고와 다르게 간다.** api-contract 는 `phase`·config 키를 **카탈로그 엔드포인트 신설**로
풀라고 했으나, 그것은 FR-WF-06 D4 의 선언 범위를 넘는 **새 API 표면**이라 스펙 deviation 전수 동기화가
다시 붙는다. 대신 **① `ValidatorResponse` 에 `phase` 추가(행 단위 `runCatching` 방어) ② SDD 표에
`필수 config 키` 열 추가 ③ 판별식이 그 열까지 읽게 확장** 으로 간다. 같은 두 문제를 닫으면서
**기계 강제**가 붙고, 판별식이 자기 결함을 옆 열에서 재생산했다는 지적(maintainability I14)도 함께
사라진다. 카탈로그 엔드포인트는 D6 착수 시 재검토 대상으로 남긴다.

### Task 10. 하드 삭제 ADR + `DATA.md §3` 등재  [BLOCKER 해소]

- files: [`docs/adr/2026-08-25-workflow-transition-rule-hard-delete.md`, `DATA.md`]
- depends-on: []

`workflow_validators`·`workflow_post_actions` 는 `deleted_at` 이 없고 `deleteById` 가 물리 삭제다.
`DATA.md §3` 허용 목록 9줄에 없고 인가 ADR 0건이다. `DATA.md §1` 이 「위반 시 즉시 PR BLOCKER」로 못박았다.
**형제 post-action 도 `origin/main` 에서 이미 미등재** — 같은 줄로 함께 정리해 사본을 안 만든다.

### Task 11. FR 전수 동기화  [필수 누락 해소]

- files: [`docs/plan/product/project-workflow.md`, `docs/plan/README.md`]
- depends-on: []

D1·D2·D4·D5 `[x]` · D3 「비해당 **확정**」 · §2 진척에 `FR-WF-06 D1·D2·D4·D5 ✅ #404 / D6~D7 ⬜` ·
README §1 진척 열 동반 갱신(룰 H 양방향 정합 유지).
> `verify-master-plan.sh` 는 EXIT 0 이었다 — **양쪽이 똑같이 낡으면 공허하게 통과**한다. 사람만 잡는다.

### Task 12. main 코드 소소한 수정 5건 + 낡은 주석 4건

- files: [`.../validator/ValidatorAdminService.kt`, `.../transition/TransitionRuleRepository.kt`, `.../transition/TransitionKeyResolver.kt`, `.../validator/web/ValidatorExceptionHandler.kt`, `.../validator/ValidatorAdminExceptions.kt`, `.../domain/spi/WorkflowValidator.kt`, `.../engine/DefaultWorkflowValidatorFactory.kt`, `.../engine/DefaultWorkflowPostActionFactory.kt`, `.../postaction/web/PostActionController.kt`]
- depends-on: []

① **편집 불가 판정을 denylist → allowlist** (security I1). 새 validator 가 코드 변경 0 으로 편집
가능해지는 경로를 막는다 — 「불명은 거부」 ② `orderBy(displayOrder, id)` 동률 확정(api I3) ③
`protected val dsl` → `private`(maint I15) ④ `TRANSITION_ID_PATTERN` → `private`(maint I16) ⑤ 에러
코드 3종을 예외 companion 상수로(maint I7) ⑥ `WorkflowValidator.kt:41` 의 `field-required`(실재 0건)
정정 ⑦ 두 팩토리 KDoc 의 type 나열 사본 2벌 제거 ⑧ `PostActionController` `@param transitionKey` 4곳
동기화.

### Task 13. 판별식 축 3개 추가 + ArchUnit 룰 1개

- files: [`scripts/workflow/validator-type-catalog.test.ts`, `docs/sdd/07-workflow-engine.md`, `.../test/.../archunit/ProjectWorkflowArchitectureTest.kt`]
- depends-on: []

① SDD 표에 **`필수 config 키` 열** 추가 + 판별식이 그 열 ↔ 팩토리 `requireConfigString` 호출 대조
② **`구현 클래스` 열** ↔ 실제 클래스 파일 집합 대조(I14 — 안 읽는 열은 썩는다)
③ 팩토리 `when` 분기 ↔ 구현체 `override val type` 양방향 차집합(I12 — 갈리면 엔진이 보고한 type 을
API 에 되돌려 쓸 때 400)
④ ArchUnit — `TransitionRuleRepository` 상속 클래스는 CRUD 4종을 `override` + `@Transactional` 해야
한다(I13 — 지금은 빠뜨려도 전부 초록)
**넷 다 비-공허 짝 필수. 일부러 끊어 red 를 눈으로 본 뒤 되돌린다.**

### Task 14. `phase` 노출 + 계약 문서화

- files: [`.../validator/web/ValidatorDtos.kt`, `.../validator/web/ValidatorController.kt`, `.../validator/ValidatorAdminService.kt`, `.../test/.../validator/web/ValidatorControllerTest.kt`]
- depends-on: [12]

`ValidatorResponse` 에 `phase` 추가. **행 단위 `runCatching`** 으로 인스턴스화 실패 행은 `phase=null`.
> 내가 스펙에 적은 제외 근거는 **틀렸다**. 「깨진 config 행이 목록 전체를 500」이라 했으나
> `WorkflowEngine.kt:511` 이 **이미 같은 행으로 같은 호출을 try/catch 없이** 한다. 두 경로를 가르지 못한다.

### Task 15. 남은 사본 3종 공통화

- files: [`.../transition/TransitionKeyResolver.kt`, `.../validator/ValidatorAdminService.kt`, `.../postaction/PostActionAdminService.kt`, `.../validator/web/ValidatorController.kt`, `.../postaction/web/PostActionController.kt`, `.../postaction/web/PostActionExceptionHandler.kt`]
- depends-on: [12, 14]

① `resolveOrThrow`/`resolveByCompositeKey`/`ensureBelongsToTransition` 3함수(maint I9 — **어려운 절반**이
아직 두 벌) ② `requireManageScheme()`(I18 — 보안 계약이 사본 위에 서 있다) ③ `PostActionErrorResponse`
/`Body` 를 공용 `ErrorResponse` 로(I10 — wire 계약 무변).

### Task 16. 컨트롤러 테스트 보강

- files: [`.../test/.../validator/web/ValidatorControllerTest.kt`]
- depends-on: [14, 15]

에러 계약 **4행 중 3행이 무검증**이다(testing T2 · maint I7 교차확인). `400 INVALID` · `400
TYPE_NOT_EDITABLE` · `404 NOT_FOUND` 각각 status + `$.error.code` 단언 · GET 200 목록 봉투 ·
PUT 200 · POST 요청→서비스 인자 `verify(exactly = 1)`(현재 `any()` 가 삼킨다).

### Task 17. 서비스·엔진 테스트 보강

- files: [`.../test/.../validator/ValidatorAdminServiceTest.kt`, `.../test/.../validator/ValidatorEngineIntegrationTest.kt`]
- depends-on: [14, 15]

**보안 가드 2개가 지워져도 초록인 상태를 닫는다.** ① E3 — `resolveById` null 분기(cross-workflow
IDOR) ② E4 — `delete` 의 소속 확인 ③ `update` 성공 경로(FR-3 이 통째로 무검증) ④ 합성 키 형식 오류
⑤ E5 여분 키 ⑥ 엔진 테스트를 **한 테스트 안에서 create → 차단 단언 → delete → 통과 단언** 왕복으로
접는다(현재는 삭제 **전** 상태를 안 재서 create 가 엉뚱한 전환에 붙어도 초록).

## Plan 메타

- **task 수**. 17 (Task 9 는 구현 중 발견 · Task 10~17 은 게이트 2 지적 반영)
- **예상 wave**. 9
  - wave 1 — Task 1 · 2 · 7 (`depends-on: []`, `files` 교집합 0)
  - wave 2 — Task 3 (2) · Task 8 (1 · `PostActionAdminService.kt` 파일 겹침으로 자동 직렬)
  - wave 3 — Task 4 (1, 3)
  - wave 4 — Task 5 (4) · Task 6 (4)
  - wave 5 — Task 9 (4, 5, 6) · 구현 중 발견분 정리
- **구현 규율**. TDD red-first. 예외 3건을 명시한다 — Task 1·2(순수 리팩터, **기존 테스트가 안전망이자
  회귀 판정자**) · Task 8(주석, grep 검증). 나머지 5건은 red 를 먼저 본다
- **회귀 표면 1곳**. Task 2 가 운영 중인 `PostActionRepository` 를 옮긴다. 방어는 `typealias` 로
  호출부 무변경 + 이행 전후 post-action 테스트 전량 대조
- **추가 검증**. `./gradlew :modules:project-workflow:test ktlintCheck detekt` ·
  `node --experimental-strip-types --test 'scripts/**/*.test.ts'` (pre-push 가 전량 무조건 실행) ·
  `node scripts/build-doc-index.mjs --check` (pre-commit)
- **프론트 무변경**. `apps/web` 을 건드리지 않는다 — D6 는 후속 PR

## 리뷰 결과

**렌즈**. `plan-eng-review` 1종 (`type == api` 분기). 2026-08-25.
**BLOCKER 0 · P1 1건(반영 완료) · P2 4건 · P3 2건 · INFO 1건.**

### 그 자리에서 교정한 것 4건 — 결정이 필요 없는 누락

| # | 지적 | 반영 |
|---|---|---|
| C1 [P1] | FR-6 은 생성·**수정** 둘 다 400 인데 Task 3 테스트가 생성만 덮었다. PUT 은 기존 `RequiredField` 행의 type 을 `CustomExpression` 으로 **바꿔 넣는** 경로라 더 놓치기 쉽다 | Task 3 에 `기존 행의 type 을 CustomExpression 으로 수정해도 400` 추가 |
| C2 [P2] | 403 비공개를 **한 케이스로 증명할 수 없다**. 실재/미실재 전환이 둘 다 403 이고 본문이 같아야 성립한다 — 한쪽만 재면 검사 순서가 뒤집혀도 초록이다 | Task 4 테스트를 2건으로 쪼개고 GREEN 절에 근거 명시 |
| C3 [P3] | `ValidatorRepositoryIntegrationTest` 가 `transition_id` FK 를 만족할 전환 픽스처를 안 적었다 | Task 2 에 픽스처 선행 조건 명시 |
| A1 [P2] | `ValidatorExceptionHandler` 에 `@Order` 를 붙이면 **다른 advice 의 매핑을 빼앗는다** | Task 4 에 금지 + 근거(`TransitionConflictExceptionHandler.kt:29-33`) 명시 |

> **A1 은 내가 처음 세운 가설을 접은 자리다.** 「전역 catch-all 이 403 을 삼켜 500 이 된다」를 의심했으나
> `WorkflowExceptionHandler` 에 `Exception::class` 핸들러는 **없다** — grep 에 걸린 건 KDoc 문장이었다.
> 실물을 열어 확인하고 지적을 철회했다.

### 게이트 1 에서 Maxi 가 정할 것 3건

**주의 1 — `ErrorResponse` 세 번째 사본 [P2 · 신뢰 9/10]**
`web/WorkflowExceptionHandler.kt:247` 에 표준 `ErrorResponse(error: ErrorBody)` 가 있고
`PostActionErrorResponse`/`PostActionErrorBody` 가 이미 사본 1개다. 셋 다 `{error:{code,message}}` 로 같다.
- ⓐ **기존 `com.bts.workflow.web.ErrorResponse` 를 재사용** — 사본이 안 늘어난다. 같은 모듈 안이라 결합 비용 0
- ⓑ post-action 관례대로 `ValidatorErrorResponse` 를 새로 만든다 — 형제 두 패키지가 대칭이 된다
- **추천 ⓐ.** 「DRY · 사본을 늘리지 않는다」가 이 저장소의 지배 결함 양식 대응과 같은 방향이다

**주의 2 — `ValidatorRepository` 가 `PostActionRepository` 195줄의 거의 완전 복제 [P2 · 9/10]**
두 테이블 컬럼이 완전히 같다(`id · transition_id · type · config · display_order · created_at · updated_at`).
- ⓐ **복제한다 + 부채로 등재** — 위험 0. post-action 을 안 건드린다. 195줄이 두 벌
- ⓑ 공통 `TransitionRuleRepository` 로 뽑고 둘 다 갈아탄다 — 사본 0. 대신 **이미 배포된 post-action 편집기**를 건드린다
- ⓒ validator 만 새 기반을 쓴다 — **비대칭이 남는다.** 반쪽 이행은 둘 중 어느 쪽보다도 나쁘다
- **추천 ⓐ.** 이 PR 의 선언 범위는 CRUD 추가지 기존 리포지토리 이행이 아니다. ⓑ 는 D6 이 끝난 뒤 별도 PR 이 맞다

**주의 3 — `DefaultWorkflowValidatorFactory.kt:26-31` 의 prod 경고가 낡았다 [INFO · 8/10]**
「prod 프로파일은 `PermissionResolver` 빈 부재」라고 적혀 있으나 `adapter/DelegatingPermissionResolver.kt:1`
이 「prod 권한 어댑터 — fail-closed」로 실재한다. **Task 3 이 이 팩토리를 주입받으므로** 구현자가
이 문장을 읽고 「prod 에서 안 뜬다」고 잘못 판단할 자리다.
- ⓐ **Task 7 에 합친다** — Task 7 이 이미 「낡은 주석 정정」이고 같은 종류다. 한 문장으로 설명되는 커밋이 유지된다
- ⓑ 부채로 등재하고 안 건드린다
- **추천 ⓐ**

### 게이트 1 결정 (Maxi · 2026-08-25)

| 건 | 결정 | 추천과 |
|---|---|---|
| 주의 1 `ErrorResponse` | **ⓐ 기존 `com.bts.workflow.web.ErrorResponse` 재사용.** 사본을 3벌로 늘리지 않는다 | 일치 |
| 주의 2 리포지토리 복제 | **ⓑ 공통 기반 `TransitionRuleRepository` 로 뽑고 post-action·validator 둘 다 갈아탄다** | **다름** — 나는 ⓐ(복제+부채)를 추천했다 |
| 주의 3 팩토리 낡은 주석 | **ⓐ Task 8 에 합친다** (구 Task 7) | 일치 |
| Task 1 해석기 개명 | **한다** | 일치 |

> **주의 2 가 범위를 넓혔다 — 무엇이 달라지나.**
> 이미 운영에 나가 있는 `PostActionRepository` 를 새 기반 위로 옮긴다. 순수 리팩터지만 **회귀 표면**이
> 생겼다. 방어 2겹을 건다. ① `PostActionRow` 를 `typealias PostActionRow = TransitionRuleRow` 로 두어
> **호출부 3개 테스트 파일과 서비스가 한 줄도 안 바뀐다** ② 이행 전후로 post-action 테스트 전량을
> 돌려 **같은 결과**임을 대조한다. 이행이 red 를 내면 그것이 곧 회귀 신호다.
> task 는 7건에서 **8건**이 됐고 wave 는 3에서 **4**가 됐다.

### NOT in scope — 고려했고 명시적으로 미룬 것

| 항목 | 근거 |
|---|---|
| D6 전환 규칙 편집 UI · D7 E2E | Maxi 결정(2026-08-25) — FR-WF-04·05 분할 관례. 후속 PR |
| `CustomExpression` 편집 허용 | Jira Cloud 내장 9종에 자유 표현식 입력칸이 없다. `SpelEvaluator` 절대 계약도 유지된다 |
| Jira `Regular Expression Check` 대응 규칙 신설 | 후속 FR 후보. 자유 표현식 대신 목적 특화 규칙이 Atlassian 의 처방 |
| validator 종류 확충 (Jira 9종 ↔ BTS 4종) | 별도 FR |
| `TransitionRuleRepository` 공통화 | 주의 2 ⓑ. D6 이후 별도 PR |
| Jira 새 편집기의 사이드 패널 · Rules 4그룹 | D6 PR 에서 Maxi 결정 — post-action UI 도 함께 옮겨야 한다 |
| `workflow_validators` 스키마 변경 | 불필요. V200 기존 테이블 그대로 |

### What already exists — 다시 만들지 않는 것

| 자산 | 이 PR 에서 |
|---|---|
| `DefaultWorkflowValidatorFactory` | **재사용.** 지원 type 의 정본이자 config 검증기 |
| `PostActionTransitionResolver` (240줄) | **개명 후 재사용**(Task 1). 사본 금지 |
| `PostActionController`/`Service`/`Repository` | **형태만 차용.** 배치·가드 순서·KDoc 관례를 그대로 따른다 |
| `WorkflowSchemePermissionResolver` + `MANAGE_SCHEME`/Global | 그대로 사용. 새 권한 코드 없음 |
| `idx_workflow_validators_transition` | 그대로 사용. 신규 인덱스 없음 |
| `DelegatingPermissionResolver` (prod) | 이미 있다. 새로 만들 필요 없음 |
| `.husky/pre-push` 판별식 전량 무조건 실행 | 새 판별식 배선 끝. CI `paths` 고칠 자리 없음 |

### 실패 모드 — 새 경로별 1건씩

| 경로 | 프로덕션 실패 | 테스트 | 에러 처리 | 사용자에게 보이나 |
|---|---|---|---|---|
| `POST/PUT` config 검증 | 팩토리가 새 type 을 알지만 SDD 표는 모르는 상태로 갈림 | Task 6 판별식 | — | ✅ 판별식이 push 를 막는다 |
| `PUT` type 교체 | `RequiredField` → `CustomExpression` 로 몰래 바뀜 | **C1 로 보강** | 400 | ✅ |
| 권한 거부 | 검사 순서가 뒤집혀 404/403 이 존재를 누설 | **C2 로 보강** | 403 고정 본문 | ✅ |
| 규칙 저장 후 미반영 | 엔진이 낡은 값을 본다 | Task 5 | — | ✅ 통합 테스트가 실행 경로를 태운다 |
| `RequiredField` 목록 미차단 | phase 를 착각해 가짜 그린 | Task 5 (두 phase 분리) | — | ⚠ **테스트 설계로만 막힌다** — Task 5 경고문이 그 방어다 |

**침묵 실패(무테스트 + 무처리) 0건.**

### 병렬화 — worktree 전략

같은 모듈·같은 BC 안이고 wave 2·3 이 wave 1 산출물에 직접 의존한다. **순차 구현. 병렬 worktree 이득 없음.**
wave 안의 동시성은 `bts-impl` 의 dispatch 로 충분하다.
