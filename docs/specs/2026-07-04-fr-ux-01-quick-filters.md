<!-- FR-UX-01 퀵 필터 (보드 상단 즉시 필터) 스펙 -->

# FR-UX-01 — 퀵 필터 (보드 상단 즉시 필터) 스펙

- 날짜: 2026-07-04
- BC: agile-planning (물리) / personalization (논리)
- 관련 ADR: docs/decisions/2026-07-04-fr-ux-01-quick-filter-bc.md
- 관련 PR: #232

## 개요

보드 상단에서 자주 쓰는 필터 조합을 **이름 붙여 저장**하고, **칩 클릭 한 번**으로 즉시 적용하는 기능.
저장 형식은 기존 보드 필터(`BoardCardFilter`)의 쿼리 파라미터를 재사용한다. 퀵필터는 **보드 공유**(그 보드를
보는 모든 사용자가 함께 봄).

## 사용자 시나리오 (Given-When-Then)

- **S1 (저장)**. Given 보드 담당자가 담당자=me + 라벨=bug 필터를 적용한 상태. When "필터 저장"에 이름 "내 버그"를
  입력하고 저장. Then 보드 상단에 "내 버그" 칩이 생기고, 이후 누구나 그 칩을 볼 수 있다.
- **S2 (적용)**. Given "내 버그" 퀵필터가 있는 보드. When 사용자가 "내 버그" 칩을 클릭. Then 보드 카드가
  담당자=me + 라벨=bug 로 즉시 필터링된다(현재 필터를 교체). 다시 클릭하면 필터 해제.
- **S3 (수정)**. Given "내 버그" 퀵필터. When 편집 권한자가 이름을 "긴급 버그"로 바꾸거나 필터 조건을 갱신.
  Then 칩 라벨/동작이 갱신된다.
- **S4 (삭제)**. Given "내 버그" 퀵필터. When 편집 권한자가 삭제. Then 칩이 사라진다.
- **S5 (권한 없는 조회자)**. Given BROWSE 권한만 있는 사용자. When 보드를 열람. Then 퀵필터 칩을 보고
  클릭·적용할 수 있으나, 저장/수정/삭제 버튼은 보이지 않는다(있어도 403).

## 기능 요구사항 (FR)

- **FR1**. 보드에 퀵필터를 생성한다. 필드: `name`(표시 이름), `query`(필터 조건). CREATE 권한.
- **FR2**. 보드 상세 조회 응답에 그 보드의 퀵필터 목록을 포함한다. BROWSE 권한.
- **FR3**. 퀵필터를 수정한다(name, query). CREATE 권한.
- **FR4**. 퀵필터를 삭제한다. CREATE 권한.
- **FR5**. `query`는 `BoardCardFilter` 쿼리 파라미터 형식(`assignee`/`label`/`component`/unassigned 센티널)이며,
  저장 시 `BoardFilterQueryParser`로 파싱 검증한다. 칩 적용은 저장된 query를 `GET /boards/{id}` 파라미터로 재사용.
- **FR6 (활성 칩 표시)**. 프론트는 마지막으로 적용한 퀵필터 id(`activeQuickFilterId`)를 상태로 추적한다.
  칩 클릭 = 그 필터 적용 + 활성 표시. 사용자가 필터를 수동 변경/reset 하거나 같은 칩을 재클릭하면 활성 해제
  (필터 해제). 문자열 동등 비교로 판정하지 않는다(정규화 취약성 회피).
- **FR7 (필터 범위)**. 현재 퀵필터가 담을 수 있는 조건은 assignee/label/component/unassigned 4종뿐이다
  (`BoardFilterQueryParser` 지원 범위). status 등은 범위 밖(ADR D5 — 향후 BoardCardFilter 확장 시 자동 포함).

## 비기능 요구사항 (NFR)

- **NFR1**. 보드 상세 응답에 퀵필터 포함 시 추가 지연 p95 < 50ms (보드당 최대 20건, 단순 조회).
- **NFR2**. 퀵필터 CRUD 응답 p95 < 200ms.
- **NFR3**. WCAG 2.1 AA — 칩은 키보드 접근/포커스 가능, 활성 상태 aria 표기.

## API 인터페이스 (REST)

기존 `BoardController` (`/api/v1/boards`) 하위에 nested 리소스로 추가.

| 메서드 | 경로 | 권한 | 응답 |
|---|---|---|---|
| POST | `/api/v1/boards/{boardId}/quick-filters` | CREATE | 201 + `{filterId, name, query}` + Location |
| PATCH | `/api/v1/boards/{boardId}/quick-filters/{filterId}` | CREATE | 200 + `{filterId, name, query}` |
| DELETE | `/api/v1/boards/{boardId}/quick-filters/{filterId}` | CREATE | 204 |
| GET | `/api/v1/boards/{boardId}` (기존 확장) | BROWSE | `BoardDetailResponse.quickFilters: [{filterId, name, query}]` |

- 권한 게이트 순서는 기존 `BoardController` 패턴 재사용: actor 추출(401) → 보드 메타 조회(404) → 권한(403) → 동작.
- 요청 바디: `{ "name": String, "query": String }`. `name` `@NotBlank` 최대 50자. `query` `@NotBlank`.
- `query`는 쿼리스트링 형식(`assignee=<uuid>&label=<name>&component=<uuid>`, prefix `?` 없음). 프론트
  `buildBoardFilterQuery` 산출물에서 `?`만 떼어낸 형태. 백엔드는 문자열을 파싱해 유효성 검증 후
  **정규화(재직렬화)** 하여 저장한다.
- **인코딩 계약 (고정)**. `application/x-www-form-urlencoded` 규칙 — 공백은 `+`, 그 외는 percent-encoding.
  프론트 `buildBoardFilterQuery`(`URLSearchParams`)·board GET `@RequestParam`(Spring 디코드)·백엔드 저장/재직렬화가
  모두 이 규칙을 따라야 왕복 일치한다(리뷰 B1 — `+`를 리터럴로 오처리하면 `"my bug"`가 `"my+bug"`로 어긋남).
- **양방향 (de)serialize 부품 (신규, 재사용 아님)**. 저장·검증·적용에 문자열↔VO 양방향이 필요하다.
  - 백엔드: `BoardFilterQueryParser`에 (a) `deserialize(query: String)` — 쿼리스트링을 split + `URLDecoder`(UTF-8,
    `+`→공백)로 assignee/label/component 리스트 추출 → 기존 `parse(리스트)` 호출 → `BoardCardFilter`, (b)
    `serialize(BoardCardFilter)` — VO → 정규 쿼리스트링(`URLEncoder`, 필드 정렬 + trim + 중복 제거). 4종만
    (statusKeys는 board GET 미지원 범위 → 직렬화 제외, KDoc 명시. 향후 board GET status 지원 시 parser/serializer 동반 확장).
  - 프론트: 저장 query 문자열을 `URLSearchParams`로 파싱(`+`→공백 자동) → `BoardFilterSearch` 객체(`assignee`/`label`/
    `component` 배열) → `navigate({search})`. 기존 `@/lib/board-filter` `searchToFilter`가 이후 `BoardCardFilterParams`
    복원을 담당하므로 프론트 신규는 `queryStringToSearch(query): BoardFilterSearch` 소형 함수 하나뿐.
- 정규화 목적은 **표시 안정**(같은 필터의 표기 흔들림 방지). query 자체엔 UNIQUE 없음(중복 저장 방지 효과는 없음 —
  이름만 UNIQUE).
- 응답 `query`는 저장된 정규화 문자열 그대로 반환.
- 수정(PATCH)에 OCC(낙관적 락) 미적용 — 단순 메타, last-write-wins. 보드/카드와 달리 version 컬럼 없음.
- 목록 정렬: `created_at ASC`(생성 순). 칩 표시 순서 결정적.

## 데이터 모델 변경

신규 마이그레이션 `V504__board_quick_filters.sql` (agile-planning 모듈).

```sql
create table board_quick_filters (
    id         uuid        primary key default gen_random_uuid(),
    board_id   uuid        not null references boards(id) on delete cascade,
    name       text        not null,
    query      text        not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_board_quick_filters_board_name unique (board_id, name)
);
create index idx_board_quick_filters_board on board_quick_filters(board_id);
```

- `board_id` FK `on delete cascade`: 보드 물리 삭제 시 동반 삭제. 보드 soft-delete(deleted_at) 시엔
  BoardController가 이미 404 처리하므로 접근 불가(조회 경로 보존).
- `unique(board_id, name)`: 같은 보드 내 이름 중복 금지.
- jOOQ codegen 미러(`init_codegen.sql`)도 동반 갱신 (memory: jooq-init-codegen-mirror).

## 엣지 케이스

- **EC1 (빈 query)**. 파싱 결과 `BoardCardFilter.EMPTY`(조건 0개)이면 400 거부. "전체 보기"는 필터 해제(reset)로
  충족되므로 빈 퀵필터 저장은 무의미.
- **EC2 (중복 이름)**. 같은 보드에 동일 `name` 존재 시 409 Conflict. jOOQ UNIQUE 위반 → 409 변환
  (memory: jooq-exception-translator-409-dependency 두 경로 대비).
- **EC3 (최대 개수)**. 보드당 퀵필터 20건 상한. 초과 생성 시 409(또는 422). UI 칩 공간 + 남용 방지.
- **EC4 (잘못된 query)**. `query`에 유효하지 않은 UUID 등 → `BoardFilterQueryParser`가 400.
- **EC5 (타 보드 소속)**. `{filterId}`가 `{boardId}`에 속하지 않으면 404 (교차 참조 차단).
- **EC6 (soft-deleted 보드)**. 보드가 soft-deleted면 기존 BoardController 패턴대로 404.
- **EC7 (name 길이)**. blank 400(@NotBlank), 50자 초과 400.
- **EC8 (미인증)**. 모든 엔드포인트 미인증 401 (actor 추출 선행).

## 제약 조건

- BC 격리: agile-planning 단독. 이슈 조회는 기존 cross-BC `BoardIssueLookupPort`/필터 인프라 재사용(신규 포트 0).
- query 저장 형식은 쿼리 파라미터 문자열(정규화). JSON 미채택 — BoardFilterQueryParser 재사용 극대화.
- 새 권한 코드 추가 없음. 기존 IssuePermission.BROWSE/CREATE 재사용.

## 측정 가능한 완료 기준

- [ ] D1~D5 백엔드: QuickFilter 도메인 + V504 마이그레이션 + CRUD API + 보드 응답 포함 + 단위/통합 테스트
- [ ] D6 프론트: 보드 상단 퀵필터 칩(적용/토글) + 저장 다이얼로그 + 편집/삭제(권한 게이팅)
- [ ] D7 E2E: 저장→칩 표시→클릭 적용→삭제 happy path
- [ ] EC1~EC8 전부 테스트로 커버
- [ ] `pnpm verify` + `./gradlew :backend:modules:agile-planning:test` 통과

## Brainstorming Check

✅ 통과 (1회 iteration). gap 5건 발견 후 spec 직접 보강 (Maxi 결정 불필요, 재사용 극대화 방향).
1. query 저장 형식 = 쿼리스트링(프론트 buildBoardFilterQuery 재사용, board GET 계약 일치) — 확정.
2. 활성 칩 판정 = 프론트 activeQuickFilterId 추적(문자열 비교 아님, 정규화 취약성 회피) — FR6.
3. 수정 OCC = 미적용(단순 메타 last-write-wins) — API §.
4. status 필터 = 현재 범위 밖(BoardFilterQueryParser 4종 한정) — FR7.
5. 목록 정렬 = created_at ASC — API §.

핵심 재사용: BoardCardFilter VO · BoardFilterQueryParser.parse · buildBoardFilterQuery(프론트) · BoardController 권한 게이트.
신규 최소: QuickFilter 도메인 · V504 마이그레이션 · CRUD 서비스/컨트롤러 · BoardCardFilter→쿼리스트링 serialize · 프론트 칩 UI.
