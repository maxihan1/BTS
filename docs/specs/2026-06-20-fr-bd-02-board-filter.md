# FR-BD-02 — 보드 필터 (담당자/라벨/컴포넌트) 백엔드 D1~D5 — 스펙

> slug: fr-bd-02-board-filter · BC: agile-planning (+ issue-tracking via port) · 2026-06-20
> 선행: FR-BD-01 백엔드(#165). 범위: 백엔드 D1~D5. D6(UI)/D7(E2E)는 후속 PR.

## 개요

칸반 보드 조회 API(`GET /api/v1/boards/{boardId}`)에 **담당자/라벨/컴포넌트 필터**를 추가한다.
필터 조건에 맞는 카드만 컬럼에 배치해 반환한다. 필터는 issue-tracking이 소유한 이슈 필드이므로
`BoardIssueLookupPort`를 확장해 **SQL WHERE 술어로 푸시다운**한다(메모리 필터 기각 — 도메인 섹션 참조).

## 사용자 시나리오 (Given-When-Then)

- **S1 (담당자 필터)**. Given 보드에 여러 담당자의 카드가 있을 때, When `?assignee={uuid}`로 조회하면,
  Then 해당 담당자의 카드만 컬럼에 배치돼 반환된다.
- **S2 (미할당 필터)**. Given 미배정 카드가 섞여 있을 때, When `?assignee=unassigned`로 조회하면,
  Then 담당자가 없는(assignee_id IS NULL) 카드만 반환된다.
- **S3 (라벨 필터)**. Given `bug` 라벨이 달린 카드가 있을 때, When `?label=bug`로 조회하면,
  Then `bug`를 포함한 카드만 반환된다(라벨은 배열이라 여러 라벨 중 하나라도 일치).
- **S4 (컴포넌트 필터)**. Given 카드가 컴포넌트에 연결돼 있을 때, When `?component={componentId}`로 조회하면,
  Then 해당 컴포넌트에 연결된 카드만 반환된다.
- **S5 (필드내 다중값 = OR)**. When `?assignee=u1&assignee=u2`로 조회하면, Then u1 **또는** u2 담당 카드가 반환된다.
- **S6 (필드간 = AND)**. When `?assignee=u1&label=bug`로 조회하면, Then u1 담당 **이면서** bug 라벨인 카드만 반환된다.
- **S7 (필터 없음 = 회귀)**. When 필터 파라미터 없이 조회하면, Then FR-BD-01과 동일하게 가시 카드 전부가 반환된다.

## 기능 요구사항 (FR)

- **FR1**. `GET /api/v1/boards/{boardId}`가 `assignee`, `label`, `component` 쿼리파라미터(각 0..N, 반복 허용)를 받는다.
  컨트롤러는 assignee/component를 **`List<String>`으로 받아 직접 UUID/센티널 파싱**한다(Spring 자동 `List<UUID>` 바인딩 금지).
  형식 오류 시 `ResponseStatusException(400)`을 명시적으로 던져, catch-all 핸들러가 타입 미스매치를 500으로 변질시키는 회귀를 차단한다(fr-wt-01 교훈).
- **FR2**. `assignee` 값은 UUID 또는 센티널 문자열 `unassigned`. UUID는 `issues.assignee_id` 일치, `unassigned`는
  `assignee_id IS NULL`을 의미한다. 한 요청에 둘을 섞을 수 있다(`?assignee=u1&assignee=unassigned`).
- **FR3**. `label` 값은 문자열. `issues.labels`(TEXT[]) 배열 원소 포함(`@>`)으로 매칭. 대소문자 보존 정확 일치
  (라벨은 저장 시 대소문자 보존 — `Issue.normalizeLabels`).
- **FR4**. `component` 값은 컴포넌트 UUID(`components.id`). `issue_components` 연결 존재(EXISTS)로 매칭.
- **FR5 (결합 의미)**. 같은 필드의 여러 값은 **OR**, 서로 다른 필드 간은 **AND**. (Maxi 확정 2026-06-20)
- **FR6**. 필터는 **visibility 보안 필터 이후에 추가로** 좁힌다. 보안 등급으로 가려진 이슈는 필터와 무관하게 항상 제외.
- **FR7**. 필터는 `BoardIssueLookupPort`를 통해 issue-tracking adapter의 `IssueRepository.listVisibleForBoard`
  **SQL WHERE 술어로 푸시다운**한다. `BOARD_CARD_FETCH_LIMIT+1` 기반 `truncated` 계산은 **필터 적용 후** 수행한다.
- **FR8**. 필터 인자를 담는 VO(`BoardCardFilter`)는 shared-kernel(`com.bts.shared.board`)에 둔다.
  빈 필터(모든 리스트 empty + includeUnassigned=false)는 **현재 무필터 동작과 동일**(FR-BD-01 회귀 보존).

## 비기능 요구사항 (NFR)

- **NFR1 (성능)**. 200건 규모 보드 + 필터 조회 p95 < 1.5s (FR-BD-01 NFR 동일 기준).
  `labels`는 GIN 인덱스(`ix_issues_labels_gin`) 활용, `issue_components`는 PK(issue_id 선두)로 EXISTS 커버.
- **NFR2 (격리)**. agile-planning은 issue-tracking을 직접 import하지 않는다(ArchUnit 강제). 필터 전달은 shared-kernel 포트만.
- **NFR3 (카테시안 금지)**. component 필터는 **EXISTS 서브쿼리**로 구현. `issue_components` JOIN 금지 —
  이슈×컴포넌트 행 증식이 `LIMIT+1` truncated 로직과 카드 중복을 깨뜨린다 (cartesian-product-jooq-leftjoin-count).

## API 인터페이스 (REST)

```
GET /api/v1/boards/{boardId}?assignee={uuid|unassigned}&label={string}&component={uuid}
  - 모든 필터 파라미터는 선택, 반복 가능 (예: ?assignee=u1&assignee=u2&label=bug)
  - 인증: 기존 BROWSE 권한 게이트 유지 (FR-BD-01과 동일, 변경 없음)
  - 200 OK: BoardDetailResponse (FR-BD-01과 동일 스키마 — 필터된 카드만 포함)
  - 400 Bad Request: assignee 값이 UUID도 'unassigned'도 아님 / component 값이 UUID 아님
  - 404 Not Found: 보드 미존재 또는 soft-deleted (기존 동작)
```

- 응답 스키마는 **변경 없음**. 필터는 어떤 카드가 포함되는지만 좁힌다. `truncated`/`unplacedCount` 의미 동일.
- 빈 문자열 파라미터(`?label=`)는 무시(미지정과 동일 취급).

## 데이터 모델 변경

- **신규 스키마 0**. 기존 `issues.assignee_id` / `issues.labels`(TEXT[], GIN) / `issue_components`(조인) 활용.
- 마이그레이션 파일 없음. init_codegen.sql 변경 없음(컬럼 추가 없음).

## 엣지 케이스

- **EC1 (필터 0매칭)**. 매칭 카드 0건 → 모든 컬럼 빈 배열, `truncated=false`. 200.
- **EC2 (필터 없음)**. 파라미터 전무 → FR-BD-01과 바이트 동일 동작(빈 BoardCardFilter 경로). 회귀 테스트로 고정.
- **EC3 (assignee 형식 오류)**. `unassigned`도 UUID도 아닌 값 → 400, 다른 필터 무시.
- **EC4 (component 형식 오류)**. UUID 아님 → 400.
- **EC5 (미존재 식별자)**. 존재하지 않는 assignee/component UUID, 미등록 label → 에러 아님, 0매칭으로 자연 처리.
- **EC6 (unassigned + UUID 혼합)**. `?assignee=u1&assignee=unassigned` → `(assignee_id = u1 OR assignee_id IS NULL)`.
- **EC7 (truncated + 필터)**. 필터 결과가 LIMIT 초과 → 필터 적용 후 LIMIT+1로 truncated 판정. 무필터보다 truncated 발생 빈도 낮아짐.
- **EC8 (visibility 우선)**. viewer가 못 보는 보안 등급 이슈는 필터가 그것을 명시 지정해도 제외(보안 필터 AND 필터).
- **EC9 (중복 라벨 값)**. `?label=bug&label=bug` → OR 중복은 무해(동일 술어).

## 제약 조건

- BC 격리: shared-kernel 포트 확장만으로 cross-BC. issue-tracking 직접 의존 금지(#165 동일 패턴).
- `BoardIssueLookupPort` 확장은 **기존 fake/구현 보호** — 무필터 fail-safe default 유지
  (interface-extension-default-method). 구체 메커니즘(오버로드 default vs 시그니처 변경+empty 기본)은 plan에서 확정.
- visibility 보안 필터(`buildActiveSecureWhere`) 경로·우선순위 변경 금지. 필터는 그 위에 AND로만 추가.
- 절대 규칙(DEVELOPMENT.md §1) 준수 — non-null, 도메인 불변식, 에러 처리, PoC 코드 금지.

## 측정 가능한 완료 기준

1. `BoardIssueLookupPort`/`IssueRepository.listVisibleForBoard`가 `BoardCardFilter`를 받아 assignee(+unassigned)/label/component
   술어를 SQL에 푸시다운한다. component는 EXISTS 서브쿼리.
2. `BoardController` GET이 assignee/label/component 반복 파라미터를 파싱해 `BoardCardFilter`로 조립.
   assignee `unassigned` 센티널 + UUID 혼합 처리. 형식 오류 400.
3. 필드내 OR / 필드간 AND 동작.
4. 무필터 경로가 FR-BD-01과 동일 동작(EC2 회귀 테스트 통과).
5. 테스트: repository SQL 필터(각 필드·조합·unassigned·truncated+필터), adapter, service, controller 통합(파라미터 바인딩·400·조합),
   포트 contract 테스트 갱신. 기존 FR-BD-01 보드 테스트 전부 그린.
6. ArchUnit BC 격리 그린. ktlint/detekt 그린. NFR1 충족.

## Brainstorming Check

✅ 통과 (1회, 집중 갭 스캔). 발견·보강: 형식 오류 400 보장(FR1 — assignee/component를 List<String>으로 받아
직접 파싱, catch-all 500 변질 회귀 차단). 무갭 확인: 정렬(필터 무관, placeCards priority 유지)·visibility 우선(EC8)·
빈 필터 회귀(EC2)·sentinel은 assignee 전용이라 label "unassigned"와 무충돌·jOOQ bind value로 배열 리터럴 주입 안전·
unplacedCount 필터 후 집합 기준 자연 일관.
