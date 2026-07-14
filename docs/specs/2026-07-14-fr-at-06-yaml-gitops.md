<!-- FR-AT-06 YAML GitOps 백엔드 스펙 — 자동화 규칙 import/export -->
# FR-AT-06 YAML 가져오기/내보내기 (GitOps) — 백엔드 스펙 (D1~D5)

> BC: automation | slug: fr-at-06-yaml-gitops | 작성: 2026-07-14
> 범위: 백엔드 D1~D5 (도메인·YAML 스키마·엔드포인트·round-trip 테스트). UI(D6/D7)는 후속 PR.

## 확정된 행위 결정 (Maxi)

1. **import 시맨틱 = UUID id 기준 upsert** — YAML의 규칙 `id`(UUID)로 식별. 프로젝트에 있으면 갱신, 없으면 생성.
2. **부분 실패 = atomic fail-closed** — 한 규칙이라도 실패하면 import 전체 롤백. 단일 `@Transactional`.
3. **경로 = 프로젝트 스코프 하위** — 기존 automation 컨트롤러(`/api/v1/projects/{projectKey}/automation/rules`)와 정렬. product doc의 flat 경로(`/api/v1/automation/import`)에서 벗어남 → **deviation, 문서 동기화 필요**.

## 사용자 시나리오 (Given-When-Then)

### S1. 규칙 내보내기 (export)
- **Given** 프로젝트 PROJ에 자동화 규칙 3개(활성 2 + 비활성 1)가 있고, 나는 MANAGE_AUTOMATION 권한이 있다.
- **When** `GET /api/v1/projects/PROJ/automation/rules/export` 를 호출한다.
- **Then** 3개 규칙 전부를 담은 YAML 문서를 `application/yaml` + `Content-Disposition: attachment; filename="automation-rules-PROJ.yaml"` 로 받는다. webhook 토큰/해시는 포함되지 않는다.

### S2. 규칙 가져오기 — 신규 프로젝트 (round-trip)
- **Given** PROJ에서 export 한 YAML이 있고, 빈 프로젝트 PROJ2에 MANAGE_AUTOMATION 권한이 있다.
- **When** 그 YAML의 `projectKey`를 PROJ2로 바꿔 `POST /api/v1/projects/PROJ2/automation/rules/import` 로 보낸다.
- **Then** PROJ2에 동등한 규칙 3개가 생성되고, 응답에 `created=3, updated=0`, 결과 ruleId 목록, (있으면) conflicts 가 담긴다.

### S3. 멱등 재적용 (GitOps apply)
- **Given** S2로 PROJ2에 규칙이 생성됐다(각자 YAML의 id 보존).
- **When** **동일 YAML**(projectKey=PROJ2)을 다시 import 한다.
- **Then** `created=0, updated=3`. 중복 규칙이 생기지 않는다(멱등). 규칙 개수 불변.

### S4. 부분 실패 → 전량 롤백
- **Given** 5개 규칙 중 3번째의 조건 트리 깊이가 MAX_DEPTH(10)를 초과한다.
- **When** import 한다.
- **Then** `400` + 어느 규칙(인덱스)이 왜 실패했는지 problem detail. **아무 규칙도 저장되지 않는다**(원자성).

### S5. 권한 없음
- **Given** 나는 PROJ에 MANAGE_AUTOMATION 권한이 없다.
- **When** export 또는 import 를 호출한다.
- **Then** `403 AUTOMATION_ACCESS_DENIED`. (인가 순서 = actor 추출(401) → 권한(403) → 리소스, 기존 컨트롤러 승계.)

## 기능 요구사항 (FR)

- **FR1 (export)** `GET .../rules/export` — 프로젝트의 소프트삭제되지 않은 전 규칙(활성+비활성)을 YAML로 직렬화. webhook 토큰/해시·OCC version·파생 필드(nextFireAt)는 제외. 규칙별 id·name·enabled·actorUserId·trigger(type+config)·condition(있으면)·actions 포함. **결정적 순서**(createdAt→id 정렬)로 방출 — GitOps git diff 안정성. **하이드레이션 필요**: finder는 actions/condition을 로드하지 않으므로(actions=emptyList) export 서비스가 규칙별로 `actionRepository.findByRuleId`·`conditionRepository.findByRuleId` 명시 로드.
- **FR2 (import)** `POST .../rules/import` — YAML 본문을 파싱해 규칙 목록을 upsert. 규칙 식별은 id(UUID) 기준.
- **FR3 (upsert 판정)** 규칙별로.
  - id 존재 + **이 프로젝트**에 미삭제 규칙으로 존재 → **UPDATE**.
  - id 존재 + 다른 프로젝트 소유 또는 소프트삭제됨 → **400** (id가 다른 곳에 귀속). PK 전역 유일성 보호.
  - id 존재 + 어디에도 없음 → **CREATE (id 보존)**. ← 멱등성의 핵심.
  - id 부재(사람이 손으로 새 규칙 작성) → **CREATE (새 UUID 생성)**.
- **FR4 (원자성)** import 전체가 단일 트랜잭션. 임의 규칙 검증/저장 실패 → 전량 롤백, 아무것도 반영 안 됨.
- **FR5 (검증 재사용)** import 경로가 기존 도메인 검증을 그대로 통과해야 함. name≤200·blank 불가, triggerType enum, `TriggerConfig.validate`(SCHEDULED cron 파싱·ISSUE_UPDATED fields), `Condition.fromJson`(MAX_DEPTH=10·MAX_NODES=100·FIELD_WHITELIST 9종·리터럴 타입), `Action.fromJson`(4종 config·url http(s)). 위반 시 도메인 예외 → 400.
- **FR6 (권한)** export·import 모두 `MANAGE_AUTOMATION` fail-closed 게이트를 리소스 접근 이전에 통과. import는 루프 시작 전 1회 검증.
- **FR7 (충돌 분석)** import 커밋 **후** `analyzeProjectConflicts(projectKey)` 1회 호출 → 응답 `conflicts`에 담음(기존 create/patch 패턴 승계, 참여 트랜잭션 오염 방지).
- **FR8 (webhook 토큰)** import가 **생성**한 WEBHOOK 규칙은 새 토큰이 mint됨 → 응답 `webhookTokens`(ruleId·name·token)로 **1회** 노출(create 엔드포인트의 1회 노출 시맨틱 승계). **갱신**된 WEBHOOK 규칙은 기존 토큰 보존(재mint 안 함).

## 비기능 요구사항 (NFR)

- **NFR1 (성능)** 100개 규칙 import < **10s**(product doc §NFR). export 100 규칙 < 3s. 단일 트랜잭션·actions는 기존 `replaceForRule` batch INSERT 재사용.
- **NFR2 (크기 상한)** import 본문 규칙 수 상한 `MAX_IMPORT_RULES=500`·본문 바이트 상한(~1MB) 초과 시 413/400(DoS 방어). 조건 트리 상한은 도메인이 이미 강제.
- **NFR3 (비밀 미노출)** export YAML에 webhook 토큰/해시 절대 미포함(응답 DTO가 필드 자체를 제거하는 선례 승계).
- **NFR4 (안전 직렬화)** YAML 방출은 Jackson `YAMLFactory`가 특수문자 자동 quoting → CSV formula injection류 방어(ExportCellSanitizer)는 YAML엔 부적용(스프레드시트 대상 아님). 근거를 코드 주석에 명시.

## API 인터페이스 (REST)

### export
```
GET /api/v1/projects/{projectKey}/automation/rules/export
  200 application/yaml
      Content-Disposition: attachment; filename="automation-rules-{projectKey}.yaml"
      <YAML 본문>
  403 AUTOMATION_ACCESS_DENIED
```

### import
```
POST /api/v1/projects/{projectKey}/automation/rules/import
  consumes: application/yaml | application/x-yaml | text/yaml | text/plain
  body: <YAML 텍스트>  (@RequestBody String)
  200 application/json  AutomationImportResponse
  400 AUTOMATION_IMPORT_INVALID   (YAML 파싱 실패 | 규칙 검증 실패 | projectKey 불일치 | id 귀속 충돌 | triggerType 변경)
  403 AUTOMATION_ACCESS_DENIED
  413 AUTOMATION_IMPORT_TOO_LARGE (규칙 수/본문 크기 초과)
```

`AutomationImportResponse`
```
{
  "created": 3,
  "updated": 0,
  "total": 3,
  "ruleIds": ["<uuid>", ...],                 // 입력 순서
  "webhookTokens": [                           // @JsonInclude(NON_NULL), 생성된 WEBHOOK 규칙만
    { "ruleId": "<uuid>", "name": "...", "token": "<one-time>" }
  ],
  "conflicts": [ { "type":..., "severity":..., "ruleIds":[...], "detail":... } ]  // NON_NULL, 기존 RuleConflictResponse 재사용
}
```

## YAML 스키마 (v1)

```yaml
version: 1                    # YAML 포맷 스키마 버전 (미래 호환용, 현재 1 고정)
projectKey: PROJ              # 경로의 {projectKey}와 반드시 일치(불일치 400). export가 채움.
rules:
  - id: 550e8400-e29b-41d4-a716-446655440000   # 선택. export는 항상 채움. 손 작성 시 생략 가능(→새 규칙)
    name: "버그를 리드에게 자동 할당"
    enabled: true
    actorUserId: 123e4567-e89b-12d3-a456-426614174000   # 선택. 생략 시 import 호출자(createdBy)로 기본
    trigger:
      type: ISSUE_CREATED     # ISSUE_CREATED|ISSUE_UPDATED|ISSUE_COMMENTED|SCHEDULED|WEBHOOK
      config: {}              # YAML 객체. 내부적으로 JSON 문자열로 직렬화해 TriggerConfig.validate 통과
    condition:                # 선택(생략=조건 없음). YAML 객체(JSONLogic 부분집합). 내부 Condition.fromJson
      and:
        - {"==": [{"var": "issue.type"}, "Bug"]}
    actions:                  # 순차. 빈 리스트 허용
      - type: SET_FIELD       # SET_FIELD|ASSIGN|ADD_COMMENT|CALL_WEBHOOK
        config:               # YAML 객체 → JSON 문자열 → Action.fromJson
          field: priority
          value: 1
      - type: ADD_COMMENT
        config:
          body: "자동 처리됨"
```

### wire 변환 (비대칭 흡수)
- **import**: YAML의 `trigger.config`/`action.config`/`condition` 은 자연스러운 YAML **객체** → 각각 **JSON 문자열**로 직렬화해 기존 서비스 입력(`triggerConfig: String`, `AutomationActionInput.config: String`, `condition: String?`)에 전달. 검증은 도메인 파서가 담당.
- **export**: 저장된 `triggerConfig`(JSON 문자열)·`condition`(도메인 `toJson()` 문자열)·action config(`actionConfigOf`의 Map)를 **YAML 객체**로 방출. 내부 전용 `ObjectMapper(YAMLFactory())`(YamlSeedService 선례) 사용, 기본 JSON ObjectMapper 오염 금지.

## 데이터 모델 변경

**없음** (D3 = 활용). 기존 `automation_rules`(V300)·`automation_actions`(V302)·`automation_conditions`(V304) 재사용. 신규 마이그레이션 0.

**도메인 최소 확장**: 기존 `AutomationRule.create(...)`는 `id=UUID.randomUUID()` + `enabled=true` + `version=0`을 강제. import-create는 (a) **id 보존**(멱등성 S3), (b) **enabled 보존**(비활성 규칙 round-trip, export한 `enabled=false`가 import 시 활성으로 되살아나면 안 됨)을 위해 `id`·`enabled`를 받는 팩토리 변형 필요(`restore(id, ..., enabled)` 또는 `create(..., id: UUID? = null, enabled: Boolean = true)`). 기존 호출부는 기본값으로 무영향. SCHEDULED·WEBHOOK 부수효과(nextFireAt 계산·토큰 mint)는 기존 create 로직 재사용. 이 확장이 FR3 멱등성(S3)·비활성 round-trip의 전제.

## 엣지 케이스

- **EC1 malformed YAML** — 파싱 불가 → 400, 원본 값 echo 금지.
- **EC2 projectKey 불일치** — YAML `projectKey` ≠ 경로 → 400. YAML에 projectKey 생략 시 경로를 권위로 사용.
- **EC3 triggerType 변경(update)** — 기존 규칙과 YAML의 triggerType이 다름 → 400(트리거 타입 불변, 도메인 제약). "타입 바꾸려면 삭제 후 재생성" 안내.
- **EC4 id 귀속 충돌** — YAML id가 다른 프로젝트/소프트삭제 규칙 소유 → 400.
- **EC5 빈 rules** — `rules: []` → 200, created=0/updated=0(무해).
- **EC6 조건 제거 시맨틱** — YAML에서 `condition` 생략 = **기존 조건 유지**(기존 PATCH 3-state `null=미변경` 시맨틱 승계). 조건을 제거하려면 UI/PATCH 사용 또는 빈-AND(`{and: []}`) 명시. **비파괴적 upsert**(선언적 full-sync 아님)임을 명시. → round-trip 시 null 조건은 생략·유지되어 안정.
- **EC7 생성된 WEBHOOK 토큰 유실 방지** — FR8로 응답에 1회 노출.
- **EC8 상한 초과** — 규칙 수 > MAX_IMPORT_RULES 또는 본문 과대 → 413.
- **EC9 actorUserId nil/미연결** — nil UUID → 도메인 거부(400). 존재하지 않는 user id의 실재 검증은 기존 create/patch와 동일 수준(런타임 권한 게이트가 방어).
- **EC10 액션 config 빈값/누락** — 기존 `Action.fromJson` 검증에 위임(field blank, url 형식 등 400).

## 제약 조건

- BC 격리 — automation 단독. cross-BC 직접 import 금지(권한은 기존 shared-kernel resolver 재사용).
- 절대 규칙 19개(`DEVELOPMENT.md §1`) 준수. YAML ObjectMapper는 내부 전용(전역 빈 오염 금지).
- SQL 바인딩만(문자열 결합 금지). 기존 repository 재사용.
- 완제품 품질 — import 경로가 기존 도메인 검증을 **우회하지 않음**(직접 repository INSERT라도 도메인 파서로 사전 검증).

## 측정 가능한 완료 기준

1. `GET .../export` 가 유효·round-trippable YAML 반환(활성+비활성 포함, 토큰 미포함).
2. `POST .../import` 가 UUID upsert(FR3 4분기) + atomic(S4) 동작.
3. **round-trip 테스트(D5)** — 규칙 생성 → export → 다른 프로젝트 import → 규칙 동등(id 보존).
4. **멱등 테스트(S3)** — 동일 YAML 2회 import → 2회차 전부 update, 규칙 수 불변.
5. 검증 재사용 테스트 — MAX_DEPTH 초과·비화이트리스트 var·잘못된 cron·잘못된 url·name 초과 각각 400 + 전량 롤백.
6. 권한 테스트 — MANAGE_AUTOMATION 없음 → export·import 403.
7. webhook 규칙 생성 시 토큰 1회 노출·갱신 시 토큰 보존.
8. `:modules:automation:test` + ktlint + detekt green. `:modules:app:test` prod 조립 부팅 green(신규 컨트롤러/서비스 빈 배선).
9. 문서 동기화 — product §2.6 D1~D5 [x], SDD §8.5 YAML 스키마 반영, 경로 deviation 기록, verify-master-plan 123/123.

## Brainstorming Check

✅ 통과 (1회 self-adversarial iteration). 발견·반영한 gap.
- **비활성 규칙 round-trip** — 기존 `create()`의 `enabled=true` 강제가 export한 비활성 규칙을 되살릴 위험 → import-create가 enabled 보존(도메인 팩토리 확장에 반영).
- **export 결정성** — GitOps git diff 안정성 위해 규칙 정렬(createdAt→id) 명시(FR1).
- **export 하이드레이션** — finder가 actions/condition 미로드 → 명시 로드 요구(FR1).
- **id 보존** — UUID upsert 멱등성은 import-create의 id 보존이 전제(FR3·데이터 모델 확장).

테스트 함정(→ /bts-plan·/bts-impl 인계).
- **크기 상한 가짜그린** — `@RequestBody String`/본문 크기 검증은 MockMvc가 서블릿 우회 → 가짜그린([[multipart-default-limit-app-policy-false-green]]). NFR2 상한 테스트는 실서블릿(TestRestTemplate·RANDOM_PORT)로 검증.
- **신규 authz 표면 없음** — import의 `actorUserId`는 기존 create/patch와 동일 수준(새 특권 부여 아님). createdBy는 import 호출자로 고정 → FR-AT-03 조건 read-oracle 방어(createdBy 게이팅)와 정합.
- **YAML mapper 격리** — 내부 전용 `ObjectMapper(YAMLFactory())`([[custom-objectmapper-bean-yaml-response-regression]] 회귀 방지 — 전역 빈으로 노출 금지).
- **prod 조립 부팅** — 신규 컨트롤러/서비스 빈 배선 후 `:modules:app:test` 재검증([[prod-assembly-boot-verification-required]]).
