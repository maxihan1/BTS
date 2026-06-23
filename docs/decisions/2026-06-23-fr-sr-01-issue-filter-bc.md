<!-- FR-SR-01 이슈 필터의 BC 경계 + 필터 의미론 결정 ADR -->

# ADR — FR-SR-01 이슈 필터 (다중 필드 조합): BC 경계 + 필터 의미론

- 날짜: 2026-06-23
- 상태: 채택 (Accepted)
- 관련 FR: FR-SR-01
- 관련 PR: #180

## 맥락 (Context)

product 문서(`docs/plan/product/search-export-import.md §2.1`)는 FR-SR-01(이슈 필터, 다중 필드 조합)을
`search-export-import` BC의 첫 FR로 분류한다. D4 명세는 `GET /api/v1/issues?filter=...` jOOQ 동적 쿼리다.

그러나 코드베이스 조사 결과:

1. **이슈 목록 API가 이미 issue-tracking BC에 존재.** `IssueController.GET /api/v1/issues`
   (`backend/modules/issue-tracking/.../adapter/inbound/rest/IssueController.kt:158`)는 현재 `projectKey` +
   페이지네이션만 받지만, 이슈 목록의 정본 엔드포인트다.
2. **필터링 jOOQ 동적 쿼리가 이미 구현됨.** `IssueRepository.listVisibleForBoard`가
   `BoardCardFilter`(assignee/label/component)를 받아 `buildFilterCondition`으로 동적 WHERE를 조립한다
   (`IssueRepository.kt:700, 804`). cartesian product 차단(EXISTS 서브쿼리), NULL 안전, GIN 배열 overlap(`&&`)까지 검증됨.
3. **visibility(보안 수준) 필터도 분리·재사용 가능.** `buildSecurityCondition`(`IssueRepository.kt:886`) +
   `IssueSecurityDirectory.accessibleLevels`(shared-kernel) 패턴.
4. **공유 VO 존재.** `BoardCardFilter`(`shared-kernel/.../board/BoardCardFilter.kt`) — 같은 필드 OR, 다른 필드 AND.

또한 product 문서 §0/§8 자체가 "다른 모든 BC의 REST API 엔드포인트는 각 BC에서 정의하되, 본 BC는 그 API의
표준 규약(페이지네이션/벌크/에러 응답 포맷)을 책임"이라고 명시한다. 즉 이슈 검색 API의 물리적 구현 위치는
issue-tracking이 될 수 있음을 product 문서도 이미 허용한다.

## 결정 (Decision)

### D1. 구현 BC — issue-tracking 모듈 (새 BC 신설 안 함)

FR-SR-01의 코드 구현은 **issue-tracking 모듈에 둔다.** 기존 `GET /api/v1/issues`를 확장해
다중 필드 필터 파라미터를 추가한다. `search-export-import` 백엔드 모듈은 이 FR에서 **부트스트랩하지 않는다.**
그 모듈은 FR-SR-02(AQL 파서) 시점에 본격 신설한다.

근거: 기존 필터/보안/jOOQ 로직 90% 이상 재사용. 새 BC 신설 시 cross-BC 이슈 재조회 오버헤드 + 필터 로직 중복.

### D2. 논리적 FR 소속은 search-export-import 유지 (논리 ≠ 물리)

`docs/plan/fr-index.md`의 FR-SR-01 BC 매핑(`search-export-import`)은 **변경하지 않는다.**
FR의 논리적 소속(검색 영역)과 물리적 구현 위치(issue-tracking 모듈)를 분리한다.
선례: IssueType cross-BC 사전 도입(`docs/adr/2026-05-29-issue-type-cross-bc-introduction.md`).
→ fr-index의 카운트/BC 합계 변경 없음(전수 동기화 카운트 영향 0).

### D3. 필터 의미론 — 필드 내 OR + 필드 간 AND (BoardCardFilter 동형)

같은 필드의 다중 값은 OR, 다른 필드 간은 AND로 결합한다.
예: `status ∈ {open, in_progress} AND assignee ∈ {me}`. 괄호/중첩 표현식은 지원하지 않는다.
임의 AND/OR 표현식 트리(괄호 중첩)는 **FR-SR-02(AQL 파서)의 영역**으로 명확히 분리한다.

### D4. 재사용 대상

- `BoardCardFilter`(shared-kernel) — assignee/label/component 필터 VO. 그대로 재사용 또는 status 확장.
- `IssueRepository.buildFilterCondition` / `buildSecurityCondition` — 동적 WHERE 조립.
- `IssueRepository.listVisibleForBoard`의 SQL 푸시다운 패턴(EXISTS 서브쿼리, 배열 overlap).
- `listWithType`(페이지네이션 경로)에 `filter` 파라미터 주입.

### D5. 신규 필요 — status(워크플로우 상태) 필터

product 명세 필드는 status/assignee/project/label. 이 중 **status(`current_state_key`) 필터는 기존
BoardCardFilter에 없음** → 신규 추가. project는 기존 `projectKey`로 충족. assignee/label/component는 기존 재사용.
(component는 product §2.1 명세엔 없으나 기존 필터에 존재 — 포함 여부는 spec에서 확정.)

## 결과 (Consequences)

- issue-tracking BC의 목록 API가 필터 가능한 정본 엔드포인트가 된다.
- search-export-import BC는 FR-SR-02부터 신설 → §1 AQL 파서 PoC가 그 모듈 부트스트랩의 첫 작업.
- BoardCardFilter를 status까지 확장하면 보드 필터(FR-BD-02)도 향후 status 필터 혜택(의도된 공유). 단, 본 PR은 이슈 목록 경로만 변경하고 보드 동작은 보존.
- fr-index/SDD의 FR 카운트·BC 매핑 변경 없음.

## 대안 (Rejected)

- **새 search-export-import BC 신설 후 거기서 필터 API.** 기각 — 기존 필터/보안 로직 재사용 불가, cross-BC 이슈 재조회 오버헤드, listVisibleForBoard와 일관성 붕괴.
- **임의 AND/OR 표현식 트리 지원.** 기각 — AQL 파서(FR-SR-02)와 중복, 본 FR 범위 초과.
