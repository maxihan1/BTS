<!-- FR-UX-01 퀵 필터의 BC 경계 + query 저장 형식 + 소유 범위 결정 ADR -->

# ADR — FR-UX-01 퀵 필터 (보드 상단 즉시 필터): BC 경계 + 저장 형식 + 소유 범위

- 날짜: 2026-07-04
- 상태: 채택 (Accepted)
- 관련 FR: FR-UX-01
- 관련 PR: #232

## 맥락 (Context)

product 문서(`docs/plan/product/personalization.md §4.1`)는 FR-UX-01(퀵 필터, 보드 상단 즉시 필터)을
`personalization` 그룹에 분류하고, 데이터 모델을 `board_quick_filters(board_id, name, query)`로 명세한다.

코드베이스 조사 결과:

1. **보드 필터 인프라가 이미 agile-planning BC에 존재.** `GET /api/v1/boards/{id}`가
   `assignee/label/component` 쿼리 파라미터를 받아 `BoardFilterQueryParser.parse()`로
   `BoardCardFilter` VO를 조립하고, `IssueRepository.listVisibleForBoard`가 동적 WHERE + 보안필터를 적용한다
   (FR-BD-02). 단, 이 필터는 **저장되지 않는 즉석(ad-hoc) 필터**다.
2. **공유 VO 존재.** `BoardCardFilter`(`shared-kernel/.../board/BoardCardFilter.kt`) — 같은 필드 OR, 다른 필드 AND.
3. **"personalization"은 백엔드 물리 모듈이 아니다.** product 문서상 논리 그룹. FR-UX-02(즐겨찾기)는 favorites,
   FR-UX-03(Inbox)은 notification-dashboard에 각각 구현됐다.
4. **선례 존재.** FR-SR-01(이슈 필터)이 논리 소속(search-export-import)과 물리 구현(issue-tracking)을 분리했다
   (`docs/decisions/2026-06-23-fr-sr-01-issue-filter-bc.md`).

## 결정 (Decision)

### D1. 물리 구현 BC — agile-planning 모듈

FR-UX-01의 코드 구현은 **agile-planning 모듈에 둔다.** 데이터가 `board_id`에 종속되고(보드 1:N 퀵필터),
칩 적용 대상이 보드 조회 경로(`GET /boards/{id}`)이므로 보드를 소유한 BC에 응집하는 것이 자연스럽다.
새 BC를 신설하지 않는다.

### D2. 논리적 FR 소속은 personalization 유지 (논리 ≠ 물리)

`docs/plan/fr-index.md`의 FR-UX-01 BC 매핑(`personalization`)은 **변경하지 않는다.**
FR의 논리적 소속(개인화 영역)과 물리적 구현 위치(agile-planning 모듈)를 분리한다.
선례: FR-SR-01 issue-filter-bc ADR, IssueType cross-BC 사전 도입.
→ fr-index의 카운트/BC 합계 변경 없음(전수 동기화 카운트 영향 0).

### D3. query 저장 형식 — BoardCardFilter 파라미터 재사용 (AQL 미채택)

`board_quick_filters.query`는 기존 보드 필터의 쿼리 파라미터 형식(`assignee/label/component`)을 저장한다.
퀵필터 칩 클릭 시 저장된 query를 `GET /boards/{id}` 파라미터로 그대로 적용한다.
`BoardFilterQueryParser` / `BoardCardFilter` / `IssueRepository.buildFilterCondition`을 100% 재사용한다.

근거: 보드 조회 경로가 현재 `BoardCardFilter`만 받는다. AQL(FR-SR-02) 표현식을 저장하면 board 조회에
AQL 실행 통합이 필요해 범위가 폭발한다. Maxi 게이트 확정.

### D4. 소유 범위 — 보드 공유 (사용자 개인 아님)

`board_quick_filters`에 `user_id`를 두지 않는다. 퀵필터는 그 보드를 보는 모든 사용자가 공유하는
팀 차원 사전정의 필터다(예: "버그만", "이번 스프린트"). product 데이터 모델 그대로.
Maxi 게이트 확정.

### D5. 재사용 대상

- `BoardCardFilter`(shared-kernel) — 필터 VO. 변경 없이 재사용.
- `BoardFilterQueryParser.parse()` — query 문자열 → VO 파싱. 저장/재적용 양방향 활용.
- `IssueRepository.listVisibleForBoard` — 보드 카드 조회 + 필터 + 보안. 변경 없음.
- `BoardController` 권한 게이트 순서(actor→메타조회(404)→권한(403)).

## 결과 (Consequences)

- agile-planning BC에 `QuickFilter` 도메인 + `board_quick_filters` 테이블 + CRUD API가 추가된다.
- 보드 조회 응답(또는 별도 조회)에 보드의 퀵필터 목록이 포함된다(spec에서 형태 확정).
- BoardCardFilter 재사용으로 필터 의미론이 보드 필터(FR-BD-02)와 완전 일치한다.
- fr-index/SDD의 FR 카운트·BC 매핑 변경 없음.
- 향후 FR-SR-01의 status 필터 확장이 BoardCardFilter에 반영되면 퀵필터도 자동 혜택(의도된 공유).

## 대안 (Rejected)

- **AQL 표현식 저장.** 기각 — 보드 조회 경로가 AQL 미지원, 통합 범위 폭발.
- **사용자별 개인 퀵필터(user_id 추가).** 기각 — product 데이터 모델 deviation, 팀 공유 사전정의 필터가 §4.1 의도.
- **personalization BC 신설.** 기각 — board_id 종속 데이터를 타 모듈에 두면 cross-BC 보드 재조회 오버헤드.
