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

> **판별식의 기계 강제 지점은 `pre-push` 다.** `workflow-scripts-ci.yml` 은 2026-08-21 부터
> `workflow_dispatch` 전용이라 자동 실행이 없다. `.husky/pre-push` 가 `scripts/**/*.test.{ts,mjs}`
> **전량을 무조건** 돌리므로 새 판별식을 그 아래 두는 것만으로 배선이 끝난다 — CI `paths` 를
> 고칠 자리가 없다. (2026-08-25 실측 판별식 412건 · 21.0초)
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

## Plan

경로 접두. `BE_MAIN = backend/modules/project-workflow/src/main/kotlin/com/bts/workflow`
· `BE_TEST = backend/modules/project-workflow/src/test/kotlin/com/bts/workflow`

### Task 1. `PostActionTransitionResolver` 를 `TransitionKeyResolver` 로 개명·이동

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/PostActionTransitionResolver.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/transition/TransitionKeyResolver.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/PostActionAdminService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/repository/WorkflowWriteRepository.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/PostActionAdminServiceTest.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/postaction/PostActionE2EIntegrationTest.kt`]
- depends-on: []

**RED**: 없음 — **순수 리팩터**다. 동작을 바꾸지 않으므로 새 실패 테스트를 만들지 않는다.
안전망은 **기존 post-action 테스트 전량**이다. 개명 전 초록을 먼저 확인하고, 개명 후 같은 명령이
같은 결과를 내야 한다.

**GREEN**:
- `postaction/PostActionTransitionResolver.kt` → `transition/TransitionKeyResolver.kt` (`git mv`)
- 클래스명·패키지 선언·L1 주석 갱신. **본문 로직은 한 줄도 바꾸지 않는다**
- 참조 4파일의 import·타입만 치환

**REFACTOR**: KDoc 의 「post-action 경로가 받은」을 「규칙 경로가 받은」으로. 내용은 그대로.

**검증**: `./gradlew :modules:project-workflow:test --tests '*PostAction*'` — **개명 전후 같은 결과**.
`grep -rn 'PostActionTransitionResolver' backend/` 가 0건.

> ⚠ **이 task 는 분리 가능하다.** 게이트 1 에서 Maxi 가 빼면 Task 3 이
> `PostActionTransitionResolver` 를 그대로 주입받는 것으로 대체하고 나머지 task 는 무영향이다.

### Task 2. `ValidatorRepository` — `workflow_validators` jOOQ CRUD

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/ValidatorRepository.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/ValidatorRepositoryIntegrationTest.kt`]
- depends-on: []

**RED**:
- 파일. `BE_TEST/validator/ValidatorRepositoryIntegrationTest.kt` (Testcontainers)
- 테스트 4건.
  ```kotlin
  @Test fun `findByTransitionId 는 display_order ASC 로 돌려준다`()
  @Test fun `insert 한 행을 config JSONB 그대로 읽는다`()
  @Test fun `update 가 type · config · displayOrder 를 바꾼다`()
  @Test fun `delete 후 findByTransitionId 가 그 행을 빼고 돌려준다`()
  ```
- 실패 메시지 (예상). `ValidatorRepository` 클래스 없음

> `transition_id` 는 `workflow_transitions` 로 향하는 FK(ON DELETE CASCADE)다. **전환 행 픽스처를
> 먼저 넣어야** INSERT 가 성립한다(리뷰 C3).

**GREEN**: `PostActionRepository` 와 같은 형태로 `WORKFLOW_VALIDATORS` 를 친다.
`ValidatorRow(id, transitionId, type, config, displayOrder)` 를 같은 파일 하단에 둔다.

**REFACTOR**: `parseJsonb` 헬퍼 · KDoc.

**검증**: `./gradlew :modules:project-workflow:test --tests '*ValidatorRepositoryIntegrationTest'`

### Task 3. `ValidatorAdminService` — 전환 해석 + type/config 검증 + 편집 불가 타입

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/ValidatorAdminService.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/ValidatorAdminExceptions.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/ValidatorAdminServiceTest.kt`]
- depends-on: [1, 2]

**RED**:
- 파일. `BE_TEST/validator/ValidatorAdminServiceTest.kt`
- 테스트 8건 — 스펙 §엣지 케이스와 1:1.
  ```kotlin
  @Test fun `미지원 type 은 ValidatorValidationException`()               // E: FR-5
  @Test fun `RequiredField 에 field 키가 없으면 ValidatorValidationException`()
  @Test fun `not-status-category 의 category 가 알 수 없는 값이면 400`()   // E6
  @Test fun `permission-check 는 scope 를 생략해도 통과한다`()             // E7
  @Test fun `CustomExpression 생성은 ValidatorTypeNotEditableException`()  // FR-6
  @Test fun `기존 행의 type 을 CustomExpression 으로 수정해도 400`()        // FR-6 · 리뷰 C1
  @Test fun `CustomExpression 행도 목록에 나오고 삭제된다`()               // E8
  @Test fun `합성 키가 2건에 걸리면 ValidatorNotFoundException`()          // E1
  @Test fun `남의 전환에 속한 id 로 수정하면 ValidatorNotFoundException`() // E4 · IDOR
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
- **`evaluate` 를 부르지 않는다** (스펙 §제약 4). 생성까지가 dry-run 이다

**REFACTOR**: 예외 3종을 `ValidatorAdminExceptions.kt` 로 분리 · KDoc 에 검증 순서 명시.

**검증**: `./gradlew :modules:project-workflow:test --tests '*ValidatorAdminServiceTest'`

### Task 4. `ValidatorController` + DTO + 예외 핸들러

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/web/ValidatorController.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/web/ValidatorDtos.kt`, `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/validator/web/ValidatorExceptionHandler.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/web/ValidatorControllerTest.kt`]
- depends-on: [3]

**RED**:
- 파일. `BE_TEST/validator/web/ValidatorControllerTest.kt`
- 테스트 7건.
  ```kotlin
  @Test fun `실재하는 전환에 권한 없이 GET 하면 403`()                          // S5
  @Test fun `실재하지 않는 전환에 권한 없이 GET 해도 같은 403 본문`()           // S5 · 리뷰 C2
  @Test fun `POST 는 권한 없으면 403`()
  @Test fun `PUT 은 권한 없으면 403`()
  @Test fun `DELETE 는 권한 없으면 403`()
  @Test fun `403 본문에 actorId · permission · scope 가 없다`()                // NFR
  @Test fun `POST 성공은 201 과 DataEnvelope 를 준다`()
  @Test fun `DELETE 성공은 204 무본문`()
  ```
- 실패 메시지 (예상). `ValidatorController` 클래스 없음

**GREEN**:
- `PostActionController` 와 같은 배치. **`requireManageScheme()` 을 리소스 조회보다 먼저** 부른다
  (근거. 메모리 `permission-assert-before-existence-makes-403-lie` — 순서가 뒤집히면 403 이
  거짓말을 하고 존재 probe 가 열린다)
- ★**비공개는 한 케이스로 증명되지 않는다**(리뷰 C2). 실재 전환과 미실재 전환이 **둘 다 403 이고
  본문이 같아야** 성립한다 — 한쪽만 재면 순서가 뒤집혀도 초록이다
- ★**`@Order` 를 붙이지 않는다**(리뷰 A1). `AmbiguousTransitionExceptionHandler.kt:51` 이
  `@Order(HIGHEST_PRECEDENCE)` 를 쓰는 이유는 **issue-tracking 의 catch-all** 때문이고, 이 경로에는
  해당하지 않는다. `TransitionConflictExceptionHandler.kt:29-33` 이 같은 판단으로 일부러 뺐다 —
  「필요 없는 전역 우선권은 다른 advice 의 매핑을 빼앗아 응답 형식을 조용히 바꾼다」
- `@RestControllerAdvice(basePackages = ["com.bts.workflow.validator"])` — post-action 핸들러가
  이 예외를 잡지 않도록 범위를 좁힌다
- 에러 코드 4종은 스펙 §에러 계약 표 그대로

**REFACTOR**: KDoc 에 경로·가드·누출 방지 근거.

**검증**: `./gradlew :modules:project-workflow:test --tests '*ValidatorControllerTest'`
+ `grep -c '@\(Get\|Post\|Put\|Delete\)Mapping' .../ValidatorController.kt` 가 **4** (기준 C1 — 파일
존재가 아니라 매핑 수를 센다. 근거. learnings 2026-07-17 「REST 노출이 없으면 기능이 없다」)

### Task 5. 엔진 통합 — 규칙을 걸면 막히고, 풀면 통과한다

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/validator/ValidatorEngineIntegrationTest.kt`]
- depends-on: [3]

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

**GREEN**: 구현 없음 — Task 3·4 가 만든 경로를 태운다. red 가 나면 그것이 배선 결함이다.

**REFACTOR**: 픽스처를 `@BeforeEach` 로 정리 · 규칙 생성은 서비스 경유(리포지토리 직접 INSERT 금지 —
그러면 CRUD 경로를 안 태운다).

**검증**: `./gradlew :modules:project-workflow:test --tests '*ValidatorEngineIntegrationTest'`

### Task 6. SDD §7.3·§7.4 표 정정 + 표↔팩토리 대조 판별식

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
- 실패 메시지 (예상). 현재 SDD 표가 `Permission` · `NotStatusCategory` · `SetField` 외 4종을 적어
  차집합 **7건** → red. **이 red 가 이 task 의 출발점이다**

**GREEN**: `docs/sdd/07-workflow-engine.md` §7.3·§7.4 표를 **런타임 `type` 식별자** 정본으로 고치고
구현 클래스명을 병기한다 (스펙 §제약 5 — ADR 이 정한 **클래스 명명은 안 건드린다**).

**REFACTOR**: 파서를 `readFileSync` + 정규식으로 두고 **손으로 유지하는 목록을 0개**로 유지.

**검증**:
- `node --experimental-strip-types --test scripts/workflow/validator-type-catalog.test.ts`
- **비-공허 짝 확인 1회.** SDD 표의 한 행을 일부러 틀리게 고쳐 **red 를 눈으로 본 뒤** 되돌린다
  (근거. 저장소 함정 「가드 수정 시 표면을 없애면 판별자도 사라진다」 · 메모리
  `invariant-satisfied-by-helptext-not-logic`). **GREEN 선커밋 뒤에 한다** — 미커밋 원복은 소실이다
- 배선. `.husky/pre-push` 가 `scripts/**/*.test.ts` 전량을 무조건 돌리므로 파일을 두는 것으로 끝난다

### Task 7. `PostActionAdminService` 의 캐시 오기 1줄 정정

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/postaction/PostActionAdminService.kt`]
- depends-on: [1]

**RED**: 없음 — 주석 1줄이다. 검증은 grep.

**GREEN**: `PostActionAdminService.kt:25` 의 「WorkflowCache 는 states/transitions/**validator** 만
캐싱」에서 validator 를 뺀다. `Workflow` aggregate 는 `states` · `transitions` 만 담는다
(`domain/Workflow.kt:18-23` · `repository/WorkflowRepository.kt:28`).

**REFACTOR**: 없음.

**검증**: `grep -n 'validator' .../PostActionAdminService.kt` 에 캐시 문장이 없다 (기준 C8).
`./gradlew :modules:project-workflow:test --tests '*PostActionAdminServiceTest'` 초록 유지.

## Plan 메타

- **task 수**. 7
- **예상 wave**. 3
  - wave 1 — Task 1 · 2 · 6 (`depends-on: []`, `files` 교집합 0)
  - wave 2 — Task 3 (1,2) · Task 7 (1 · `PostActionAdminService.kt` 파일 겹침으로 자동 직렬)
  - wave 3 — Task 4 (3) · Task 5 (3)
- **구현 규율**. TDD red-first. 예외 2건을 명시한다 — Task 1(순수 리팩터, 기존 테스트가 안전망) ·
  Task 7(주석 1줄, grep 검증). 나머지 5건은 red 를 먼저 본다
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
