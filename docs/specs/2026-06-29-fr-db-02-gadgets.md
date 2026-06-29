# FR-DB-02 가젯 시스템 — 스펙 (PR1: 백엔드 저장·검증)

> slug: fr-db-02-gadgets · BC: notification-dashboard · type: feature(backend D1~D5)
> 관련 ADR: docs/decisions/2026-06-29-fr-db-02-gadget-system.md
> 선행: FR-DB-01 (#176/#178)

## 0. 범위 (이 PR = PR1)

대시보드(FR-DB-01)에 배치하는 **가젯**의 백엔드 저장·검증을 구현한다. 가젯은 별도 테이블이 아니라 기존 `dashboards.layout` JSONB 배열의 각 항목으로 임베드된다(ADR D1). notification-dashboard BC는 가젯 **설정**(type+position+config)만 저장·검증하고, 가젯 **데이터**는 PR2 프론트가 기존 BC API로 직접 fetch한다(ADR D2).

**PR1 포함**: 가젯 카탈로그 enum + layout 항목 가젯-aware 검증 + per-type config 형식 검증 + 카탈로그 조회 API + 테스트 + 문서 deviation 동기화.
**PR1 제외**: 프론트 가젯 컴포넌트/카탈로그 모달/E2E(PR2). pie/bar용 필드별 집계 엔드포인트(issue-tracking BC, 별도 PR). sprint_burndown(FR-RP-01 의존).

## 1. 사용자 시나리오 (Given-When-Then)

- **S1 (가젯 배치 저장)**. Given 소유자가 대시보드를 가진 상태에서, When `PATCH /api/v1/dashboards/{id}` 의 layout 에 `{i,x,y,w,h,gadgetType,config}` 항목을 담아 보내면, Then 검증 통과 시 200 으로 저장되고 version+1.
- **S2 (알 수 없는 gadgetType 거부)**. Given layout 항목의 gadgetType 이 카탈로그에 없으면, When 저장 시도, Then 400 NOTIF_DASHBOARD_INVALID("알 수 없는 gadgetType").
- **S3 (config 형식 위반 거부)**. Given gadgetType 은 유효하나 config 가 타입 스키마를 위반하면(필수 키 누락/타입 불일치/길이 초과), When 저장, Then 400 NOTIF_DASHBOARD_INVALID(어떤 필드가 왜 위반인지 메시지).
- **S4 (그리드 위치 위반 거부)**. Given 항목에 i 누락/중복, 또는 x·y 음수, 또는 w·h < 1, When 저장, Then 400.
- **S5 (정적 가젯)**. Given gadgetType=text_widget(config.markdown) 또는 link_list(config.links), When 저장, Then 데이터 fetch 없이 config 자체가 콘텐츠로 보존.
- **S6 (카탈로그 조회)**. Given 인증 사용자가, When `GET /api/v1/dashboards/gadget-catalog`, Then 가젯 타입별 메타(key·category·label·enablement·config 필드 디스크립터) 목록을 200 으로 받는다(프론트 카탈로그 모달·검증 동기화 단일 진실원천).
- **S7 (빈 대시보드 호환)**. Given 기존 layout=`[]` 또는 가젯 없는 항목, When 조회/저장, Then 회귀 없음(FR-DB-01 호환).

## 2. 가젯 카탈로그 (표준 타입)

SDD 14.2 기준 12종을 enum 으로 정의. enablement 3-tier 로 점진 노출.

| key | category | enablement | 데이터 소스(PR2 프론트) | config 형식 |
|---|---|---|---|---|
| assigned_to_me | ISSUE | MVP | POST /search/aql | maxItems?(1~50) |
| recently_created | ISSUE | MVP | POST /search/aql | projectKey?(string), maxItems?(1~50) |
| filter_result | ISSUE | MVP | POST /search/aql or 저장필터 | filterId?(UUID) \| aql?(string,≤2000) (택1 필수), maxItems?(1~50) |
| issue_count | ISSUE | MVP | POST /search/aql(total) | filterId?(UUID) \| aql?(string,≤2000) (택1 필수) |
| text_widget | STATIC | MVP | 없음(정적) | markdown(string, 1~10000) 필수 |
| link_list | STATIC | MVP | 없음(정적) | links(array 1~20 of {label:1~100, url:http/https ≤2000}) 필수 |
| pie_chart | CHART | AGG(집계 PR 후) | POST 집계(issue-tracking) | field(enum: status\|assignee\|priority\|issueType) 필수, filterId?\|aql? |
| bar_chart | CHART | AGG | POST 집계 | field(동상) 필수, filterId?\|aql? |
| created_vs_resolved | CHART | AGG | POST 집계(시계열) | projectKey?(string), days?(int 7~90) |
| sprint_burndown | CHART | DEFERRED(FR-RP-01) | — | sprintId?(UUID) |
| activity_stream | ACTIVITY | DEFERRED(전역 피드) | — | projectKey?(string), maxItems? |
| comments_recent | ACTIVITY | DEFERRED | — | projectKey?(string), maxItems? |

- **enablement 의미 (enabled = 단일 진실원천)**. `enabled` 플래그 하나가 (1) 카탈로그 API 노출과 (2) **쓰기 수용**을 동시에 제어한다. MVP=enabled true(저장+노출). AGG=enabled false(집계 PR 머지 후 true). DEFERRED=enabled false(선행 FR 완료 시 true). **gadgetType 이 enabled=false 인 항목은 저장 시 400 거부**(broken 가젯 저장 차단 — Gap B). enabled 는 false→true 단방향으로만 진화하므로(AGG/DEFERRED 가 활성화될 뿐 역행 없음) 기존 저장 가젯이 사후 거부될 위험 없음.
- **"10종+" 요건 충족 경로**. MVP 6 + AGG 3(집계 PR) = 9 가시 + DEFERRED 3 정의 = 카탈로그 12종 정의. 가시 10종 도달은 집계 PR + (DEFERRED 중 선행 완료분) 으로 단계 달성. 본 PR1 은 12종 enum 정의 + MVP 6 검증을 완료한다.

## 3. config 검증 원칙 (형식만, cross-BC 존재 미확인)

FR-UX-02 favorites 선례 — **형식(format)만 검증**한다. filterId 는 UUID 형식만, projectKey 는 문자열 형식만, url 은 http/https 스킴+길이만 확인한다. **대상 존재/권한은 검증하지 않는다**(BC 격리 — 실제 데이터·권한은 PR2 프론트가 호출하는 기존 BC API 가 보장). injection 은 저장 시점엔 무해(jOOQ JSONB 바인드), 실행은 프론트가 기존 API 로 위임.

- 알 수 없는 config 키는 거부하지 않고 무시(forward-compat) — 단, 필수 키 누락/타입 불일치/길이·범위 초과는 거부.
- config 누락(없음)도 허용되는 타입(assigned_to_me 등 모든 config optional)은 빈 객체/생략 허용.

## 4. layout 항목 스키마 + 검증 규칙

layout = JSON 배열. 각 항목(가젯)의 형식.

```
{ "i": "g1", "x": 0, "y": 0, "w": 4, "h": 3, "gadgetType": "issue_count", "config": { "aql": "status = Open" } }
```

검증(도메인 `Dashboard.validateLayout` 확장):
1. layout 은 JSON 배열이어야 한다(객체/스칼라면 400).
2. 각 항목: `i`(비어있지 않은 문자열, 배열 내 유일), `x`·`y`(정수 ≥ 0), `w`·`h`(정수 ≥ 1) 필수.
3. `gadgetType` 은 **선택**(Gap A — 하위호환). 없으면 legacy 타일(위치만 검증, FR-DB-01 `{i,x,y,w,h,title}` 호환). 있으면: 카탈로그 enum 에 속하고 `enabled=true` 여야 함(아니면 400). PR2 프론트는 모든 타일에 gadgetType 을 채워 보낸다.
4. `config`: 객체(또는 생략=빈 객체). gadgetType 존재 시 해당 타입의 config 스키마(§2)로 형식 검증. gadgetType 없으면 config 검증 스킵.
5. 알 수 없는 항목 키(`title` 등 FR-DB-01 확장, 미지 키)는 거부하지 않고 보존/무시한다(forward·backward compat).
6. 전체 layout 64KB 상한(기존 MAX_LAYOUT_BYTES 유지). 항목 수 상한 50(가젯 폭주 방지, 신규 MAX_GADGETS).
7. 위반 시 `DashboardDomainException` → 400 NOTIF_DASHBOARD_INVALID(위반 항목 i + 사유 메시지).

> **FR-DB-01 호환 (Gap A — 회귀 차단)**. FR-DB-01 프론트(#178)는 layout 항목을 `{i,x,y,w,h,title}`(gadgetType 없음)으로 저장한다. PR1 이 gadgetType 을 필수화하면 PR1 머지~PR2 머지 사이 기존 프론트의 모든 저장이 400 으로 깨진다. 따라서 **gadgetType 은 선택**으로 두고(규칙 3), 검증은 **쓰기 경로(create/applyPatch)에서만** 수행하며 읽기(조회)는 검증 안 한다. 마이그레이션 불요. PR2 가 프론트 타일을 gadget 타일로 전환한다.

## 5. API 인터페이스 (REST)

- **기존 재사용**. `POST /api/v1/dashboards`, `PATCH /api/v1/dashboards/{id}` — layout 에 가젯 항목 전달. 검증 강화만, 시그니처 불변.
- **신규**. `GET /api/v1/dashboards/gadget-catalog` (200) — 인증 사용자. 카탈로그 메타 반환.
  ```
  { "data": { "gadgets": [
    { "type": "issue_count", "category": "ISSUE", "label": "이슈 건수",
      "enabled": true, "configFields": [
        { "key": "aql", "type": "STRING", "required": false, "maxLength": 2000 },
        { "key": "filterId", "type": "UUID", "required": false } ] }, ... ] } }
  ```
  - DataResponse 래퍼(기존 컨벤션). 정렬=category→type 안정 정렬. 신규 컨트롤러는 `dashboard.web` 형제라 `DashboardExceptionHandler`(basePackages/assignableTypes) 적용 범위 확인(미적용 시 별도 핸들러 — memory: domain-exception-http-handler-basepackage-scope).
  - **Gap C — 라우팅 충돌**. `GET /dashboards/gadget-catalog` 는 기존 `GET /dashboards/{id}`(id=UUID)와 같은 prefix 다. Spring PathPattern 은 literal segment(`gadget-catalog`)를 path-variable(`{id}`)보다 우선 매칭하므로 정상 라우팅되나, **회귀 테스트 필수** — "gadget-catalog" 요청이 카탈로그 핸들러로 가고 `{id}` UUID 파싱 400 으로 새지 않음을 통합 테스트로 못박는다. (catalog 핸들러를 같은 `DashboardController` 가 아닌 별도 컨트롤러로 둘 경우에도 동일 검증.)
  - config 필드 디스크립터는 **GadgetType enum 의 per-type 선언과 단일 출처**여야 한다 — 검증 로직과 카탈로그 노출이 같은 선언에서 파생(drift 차단, memory: frontend-zod-backend-dto-contract-gap).

## 6. NFR / 제약

- BC 격리: cross-BC import 0. 카탈로그·검증은 notification 모듈 내 자족.
- 검증은 도메인 레이어(`Dashboard`)에 집중(기존 패턴). config 스키마는 GadgetType enum 에 선언적으로 부착(per-type validator).
- 에러 메시지에 PII/내부 경로 노출 금지(기존 NOTIF_DASHBOARD_INVALID 일반 메시지 + 필드명/사유만).
- 절대 규칙(DEVELOPMENT.md §1) 준수: non-null, 명시 예외, 테스트, ktlint/detekt clean.

## 7. 엣지 케이스

- EC1. layout 이 빈 문자열/공백 → 기존대로 400(유효 JSON 아님).
- EC2. layout=`[]` (가젯 0개) → 허용(빈 대시보드).
- EC3. 항목 i 중복 → 400.
- EC4. gadgetType 대소문자(`Issue_Count`) → 카탈로그는 소문자 snake_case 정확 일치, 불일치 400(enum valueOf 엄격).
- EC5. config 에 알 수 없는 키만 추가 → 허용(무시), 필수 키는 별도 검사.
- EC6. links url 이 `javascript:`/`data:` 스킴 → 400(http/https 화이트리스트).
- EC7. text_widget markdown 10000자 초과 → 400.
- EC8. 항목 51개 → 400(MAX_GADGETS).
- EC9. pie_chart field 가 enum 밖(`labels`) → 400.
- EC10. **AGG/DEFERRED(enabled=false) 타입을 저장 → 400**(Gap B, broken 가젯 차단). 집계/선행 FR 머지로 enabled=true 가 되면 그때부터 저장 허용.
- EC11. **legacy 타일(gadgetType 없음, `{i,x,y,w,h,title}`) 저장 → 허용**(Gap A, FR-DB-01 호환). 위치만 검증.
- EC12. **`GET /dashboards/gadget-catalog` 라우팅** → 카탈로그 핸들러로 매칭(Gap C), `{id}` UUID 파싱 400 으로 새지 않음.
- EC13. filter_result/issue_count 에 filterId·aql 둘 다 없음 → 400(적어도 하나 필수). 둘 다 있으면 허용(프론트가 우선순위 결정).

## 8. 측정 가능한 완료 기준

1. GadgetType enum 12종 + per-type config validator(enum 선언과 카탈로그 디스크립터 단일 출처) 구현, 단위 테스트로 각 타입 정상/위반 케이스 커버.
2. `Dashboard.validateLayout` 가젯-aware 확장 — §4 규칙 1~7 전부 테스트(정상/EC1~EC13). 특히 EC10(enabled=false 거부)·EC11(legacy 타일 허용)·EC13(filter 택1).
3. `GET /api/v1/dashboards/gadget-catalog` 200 + 카탈로그 메타(enabled 플래그 포함) 컨트롤러·통합 테스트 + EC12 라우팅 회귀 테스트.
4. 기존 FR-DB-01 dashboard 테스트 전부 green(회귀 0). 빈 layout/가젯 없는 조회 + legacy title-타일 저장 호환(Gap A).
5. notification 모듈 `./gradlew :backend:modules:notification:test ktlintCheck detekt` clean.
6. 문서 deviation 동기화: SDD 05.11/14.2/14.3 + product notification-dashboard.md §3.2(D1~D5 체크 + 데이터모델/데이터API 정정) + fr-index 주석. `bash scripts/verify-master-plan.sh` 통과.

## 9. 문서 동기화 대상 (CLAUDE.md §명세/범위 변경)

- SDD 05.11 `Gadget` 엔티티/`dashboard_gadgets` → "layout JSON 임베드" 모델로 정정(별도 테이블 미채택 명시).
- SDD 14.3 `data class Gadget`(별도 엔티티) → layout 항목 임베드 표기. 14.4 데이터 fetch=프론트 직접(유지).
- product `notification-dashboard.md §3.2` — D3(데이터모델: layout 임베드), D4(백엔드: 저장·검증+카탈로그 API, 데이터 API 아님) 정정. PR1 해당 D 체크.
- ADR 본문 + 본 spec 링크. glossary "가젯(Gadget)" 독립 항목(머지 단계).
