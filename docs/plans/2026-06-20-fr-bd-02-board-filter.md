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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
