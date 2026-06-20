# FR-BD-02 — 보드 필터 (담당자/라벨/컴포넌트) 백엔드 D1~D5

> slug: fr-bd-02-board-filter
> type: api
> agent: backend-engineer
> BC: agile-planning
> 생성: 2026-06-20

## Brief

FR-BD-02 보드 필터. 칸반 보드 조회 API(`GET /api/v1/boards/{id}`)에 필터
쿼리파라미터(assignee / label / component)를 추가해, 조건에 맞는 카드만
컬럼에 배치하여 반환한다.

- **범위**. 백엔드 D1~D5만 (도메인·명세·데이터모델·백엔드·백엔드 테스트).
- **범위 외**. D6(필터 칩 UI) / D7(E2E) — FR-BD-01 보드 프론트(D6)가 아직
  미존재하므로, 보드 UI 생성 후 후속 PR로 분리 (Maxi 확정 2026-06-20).
- **선행**. §2.1 FR-BD-01 백엔드(#165) 완료 — `BoardController` /
  `BoardApplicationService` / `BoardRepository` 존재.
- **데이터 모델**. 신규 스키마 없음 (URL query 활용, product §2.2 D3).

## 도메인 정리

- **BC**: agile-planning (보드 소유) + issue-tracking (필터 대상 필드 소유, cross-BC 포트 경유).
- **영향 엔티티**: 신규 0. Board/BoardColumn(읽기), Issue(필터 술어 대상). 새 도메인 개념 없음.
- **새 용어**: 없음 — assignee/label/component/board 모두 glossary 기존 용어 (읽기측 필터).
- **기존 결정 충돌**: 없음. FR-BD-01 ADR(`2026-06-20-fr-bd-01-agile-planning-bootstrap`)의
  cross-BC 포트 격리 원칙을 그대로 확장.

### 핵심 설계 결정 — 필터 SQL 푸시다운 (포트 확장)

필터를 **어디서 적용하느냐**가 이 작업의 척추다.

- **결정**: `BoardIssueLookupPort.listVisibleIssuesByProject`에 필터 인자(assignee/label/component)를
  추가하고, issue-tracking adapter → `IssueRepository.listVisibleForBoard` **SQL WHERE 술어로 푸시다운**한다.
- **근거 (메모리 필터 기각)**:
  1. `BoardIssueView`에 **label/component 필드가 없다** (key/summary/currentStateKey/assigneeId/priority/version만).
     메모리 필터를 하려면 cross-BC로 label/component 데이터를 끌어와야 해 페이로드·결합도 증가.
  2. 포트는 `BOARD_CARD_FETCH_LIMIT + 1`로 상한을 두고 `truncated`를 계산한다. 필터를 **LIMIT 이후
     메모리에서** 적용하면 LIMIT 밖의 매칭 이슈가 누락돼 **결과가 틀린다**. 필터는 반드시 LIMIT 전(SQL)에 적용.
  3. 포트 KDoc이 이미 "visibility 필터는 구현체가 SQL 수준에서" 책임지도록 명시 — 같은 자리에 필터 술어를 더한다.
- **BC 격리**: shared-kernel 포트 + issue-tracking adapter + agile-planning 서비스 3곳을 건드리지만,
  이는 FR-BD-01(#165)이 포트를 신설하며 건드린 동일 집합이다. **포트가 인가된 cross-BC seam**이므로
  "한 PR = 보드 기능 cross-BC" 예외에 해당 (#165 선례).

### 필터 술어 SQL 형태 (저장 구조 실측)

| 필터 | 저장 | 술어 | 주의 |
|---|---|---|---|
| assignee | `issues.assignee_id` UUID | `ASSIGNEE_ID = ?` | 미할당 필터(`unassigned`) 지원 여부는 스펙 결정 |
| label | `issues.labels` TEXT[] (GIN 인덱스) | `LABELS @> ARRAY[?]` (배열 포함) | GIN 인덱스 활용 |
| component | `issue_components(issue_id, component_id)` 조인 테이블 | **EXISTS 서브쿼리** | JOIN 금지 — 이슈×컴포넌트 카테시안으로 행 증식 → LIMIT+1 truncated 로직 깨짐 (`cartesian-product-jooq-leftjoin-count`) |

### 스펙으로 넘길 결정 사항

- **다중 값 + 결합 의미**: product D6이 "다중 선택"이므로 필드별 다중 값 허용(`?assignee=u1,u2`).
  필드 내 OR / 필드 간 AND (Jira 보드 필터 표준) 채택 여부 → 스펙 확정.
- **미할당(unassigned) 필터**: assignee 없는 카드만 보기 지원 여부 → 스펙 확정.
- **식별자 형식**: assignee=UUID, component=UUID(component_id), label=문자열 값.
- **truncated 의미 갱신**: 필터 적용 후 결과가 LIMIT 초과 시 truncated=true (의미 자연 확장).

### ADR 후보

`docs/decisions/2026-06-20-fr-bd-02-board-filter-pushdown.md` — "보드 필터 SQL 푸시다운 (포트 확장 vs 메모리 필터)".
스펙에서 다중값/AND-OR 의미 확정 후 작성.

## 스펙

전체 스펙. [docs/specs/2026-06-20-fr-bd-02-board-filter.md](../specs/2026-06-20-fr-bd-02-board-filter.md)

핵심 결정 (Maxi 확정 2026-06-20).
- 다중값: 필드내 OR + 필드간 AND (반복 쿼리파라미터).
- 미할당: `assignee=unassigned` 센티널 포함 (`assignee_id IS NULL`).
- 필터 SQL 푸시다운 (포트 확장). component는 EXISTS 서브쿼리(JOIN 카테시안 금지).
- 형식 오류 400: 컨트롤러가 assignee/component를 List<String>으로 받아 직접 파싱(catch-all 500 변질 차단).
- 신규 스키마 0 (기존 assignee_id / labels TEXT[] GIN / issue_components 활용).

## Brainstorming Check

✅ 통과 (1회 iteration, 집중 갭 스캔). 형식 오류 400 보장 보강. 정렬/visibility 우선/빈 필터 회귀/sentinel 무충돌 확인.

## Plan

> 포트 확장 방식 = **비파괴 오버로드 default 메서드**. 기존 `listVisibleIssuesByProject(projectKey, viewerUserId)`는
> 그대로 두고, `(projectKey, viewerUserId, filter: BoardCardFilter)` 3-인자 default 메서드를 추가한다
> (default 본문은 무필터 2-인자로 위임 = fail-safe). prod adapter는 3-인자를 실제 SQL로 override.
> 근거: 모듈 컴파일 비파괴(wave 병렬) + 인라인 fake 보호(interface-extension-default-method).
> false-green 완화: prod adapter 실 SQL override(Testcontainers) + filter-aware fake가 filter 수신 단언.

### Task 1. shared-kernel — BoardCardFilter VO + 포트 3-인자 오버로드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/board/BoardCardFilter.kt`,
  `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/board/BoardIssueLookupPort.kt`,
  `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/board/BoardCardFilterTest.kt`,
  `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/board/BoardPortContractTest.kt`]
- depends-on: []

**RED**.
- `BoardCardFilterTest`: `BoardCardFilter.EMPTY.isEmpty()` == true; assigneeIds/labels/componentIds/includeUnassigned 중
  하나라도 있으면 `isEmpty()` == false.
- `BoardPortContractTest`: (1) 3-인자 default 메서드가 무필터 결과로 위임(기존 fake가 2-인자만 override해도 동작),
  (2) filter-aware fake에 3-인자 호출 시 전달한 `filter`가 그대로 수신됨(filter 캡처 단언).
- 예상 실패: `BoardCardFilter` 미존재 / 3-인자 메서드 미존재.

**GREEN**.
- `BoardCardFilter.kt`: `data class BoardCardFilter(assigneeIds: List<UUID>, includeUnassigned: Boolean, labels: List<String>, componentIds: List<UUID>)`
  + `companion EMPTY` + `fun isEmpty(): Boolean`.
- `BoardIssueLookupPort.kt`: 3-인자 default 메서드 추가 — `fun listVisibleIssuesByProject(projectKey, viewerUserId, filter): BoardIssuePage = listVisibleIssuesByProject(projectKey, viewerUserId)`.
  기존 2-인자 메서드/`BoardIssuePage`/`BoardIssueView` 불변. KDoc에 filter 의미·fail-safe·푸시다운 책임 명시.

**REFACTOR**. KDoc 정리, `isEmpty` 단일 표현. ktlint/detekt 그린.

**검증**. `cd backend && ./gradlew :backend:shared-kernel:test --tests '*BoardCardFilter*' --tests '*BoardPortContract*'`

---

### Task 2. issue-tracking — SQL 필터 푸시다운 + adapter override

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`,
  `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/board/BoardIssueLookupAdapter.kt`,
  `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/board/BoardIssueLookupAdapterTest.kt`]
- depends-on: [1]

**RED** (Testcontainers — 실 SQL).
- `BoardIssueLookupAdapterTest`에 필터 시나리오 추가 (시드 후 단언).
  - assignee 단일/다중(OR) / `unassigned` 센티널(assignee_id IS NULL) / unassigned+UUID 혼합(OR).
  - label 단일/다중(OR) — `labels @>` 배열 포함, 대소문자 보존 정확 일치.
  - component 단일/다중(OR) — `issue_components` EXISTS.
  - 필드간 AND (assignee+label).
  - **truncated+필터**: 필터 적용 후 LIMIT 초과 시 truncated=true (필터 전 LIMIT 금지 확인).
  - **EC2 회귀**: `BoardCardFilter.EMPTY` → 무필터와 동일 결과(기존 테스트 유지).
- 예상 실패: 3-인자 미구현 → 필터 무시되어 전체 반환.

**GREEN**.
- `IssueRepository.listVisibleForBoard`에 `filter: BoardCardFilter` 인자 추가. `buildActiveSecureWhere` 결과에
  필터 Condition을 **AND**로 결합.
  - assignee: `ASSIGNEE_ID IN (...)` OR `ASSIGNEE_ID IS NULL`(includeUnassigned) — 필드내 OR.
  - label: 각 라벨 `LABELS @> ARRAY[?]`를 OR. (jOOQ bind value — 배열 리터럴 주입 금지)
  - component: `DSL.exists(selectFrom(ISSUE_COMPONENTS).where(ISSUE_ID eq ISSUES.ID and COMPONENT_ID in (...)))` — **JOIN 금지**.
  - 빈 필터: Condition 미추가(무필터 동일). LIMIT+1 truncated 로직은 그대로(필터 WHERE 뒤).
- `BoardIssueLookupAdapter`: 3-인자 override, `filter`를 repository로 전달. 2-인자는 `EMPTY`로 위임.

**REFACTOR**. 필터 Condition 빌더를 private 함수로 추출(가독성). detekt NestedBlockDepth/MaxLineLength 그린.

**검증**. `cd backend && ./gradlew :backend:issue-tracking:test --tests '*BoardIssueLookupAdapter*'`

---

### Task 3. agile-planning — 쿼리파라미터 파싱 + 컨트롤러/서비스 위임

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BoardFilterQueryParser.kt`,
  `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BoardController.kt`,
  `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BoardApplicationService.kt`,
  `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardFilterQueryParserTest.kt`,
  `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardControllerIntegrationTest.kt`,
  `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/BoardApplicationServiceTest.kt`]
- depends-on: [1]

**RED**.
- `BoardFilterQueryParserTest` (순수 단위, 부트 없음):
  - assignee UUID 파싱 / `unassigned` 센티널 → includeUnassigned=true / 혼합.
  - component UUID 파싱. label 그대로 통과.
  - 빈/공백 문자열 무시 → `BoardCardFilter.EMPTY`(EC2).
  - 형식 오류(UUID도 unassigned도 아님 / component 비-UUID) → `ResponseStatusException(400)`.
- `BoardControllerIntegrationTest`에 추가 (filter-aware fake port로):
  - `?assignee=&label=&component=` 없음 → 기존 동작(EC2 회귀).
  - `?assignee={uuid}&label=bug&component={uuid}` → fake port가 수신한 filter 캡처 단언(파싱·전달 검증).
  - 형식 오류 → 400. 인증/404/403 기존 동작 유지.
- `BoardApplicationServiceTest`: fake port를 filter-aware로 갱신, `getBoard(id, actor, filter)`가 filter를 포트로 전달 단언.
- 예상 실패: `BoardFilterQueryParser` 미존재, `getBoard` 3-인자 미존재.

**GREEN**.
- `BoardFilterQueryParser.kt`: `fun parse(assignee: List<String>, label: List<String>, component: List<String>): BoardCardFilter`.
  trim/blank 제거, `unassigned` 센티널, UUID 파싱 실패 시 400.
- `BoardController.getBoard`: `@RequestParam(required=false) assignee/label/component: List<String> = emptyList()` 추가 →
  parser로 `BoardCardFilter` 조립 → `service.getBoard(id, actor, filter)`. 권한 게이트(loadBoardWithBrowse) 순서 불변.
- `BoardApplicationService.getBoard`: `filter: BoardCardFilter = BoardCardFilter.EMPTY` 인자 추가 →
  `boardIssueLookupPort.listVisibleIssuesByProject(projectKey, viewerUserId, filter)` 3-인자 호출.

**REFACTOR**. parser 책임 응집, 컨트롤러 슬림 유지. ktlint/detekt 그린.

**검증**. `cd backend && ./gradlew :backend:agile-planning:test`

## Plan 메타

- task 수: 3
- wave 예상: 2 — W1={T1}, W2={T2, T3 병렬} (T2 issue-tracking · T3 agile-planning, 파일 겹침 0, 둘 다 depends-on [1]).
- 모듈 컴파일 직렬화 요인: 포트 비파괴 오버로드로 Task 1 후에도 두 모듈 컴파일 유지 → W2 병렬 성립.
- TDD 강제: yes (각 task RED→GREEN→REFACTOR).
- 추가 검증: ktlint/detekt, ArchUnit BC 격리, 전체 `./gradlew test`(머지 게이트), NFR1(200건 p95<1.5s).
- 신규 마이그레이션/스키마: 0. init_codegen.sql 변경 없음.

## 리뷰 결과 (← /bts-review-plan 채움)
