# FR-UX-06 Phase 5 PR18 — 이슈 목록 카드 → ui/table 네비게이터 — 스펙

> slug: fr-ux-06-phase-5-pr18-ui-table-split-view
> type: ui · BC: issue-tracking (프론트 뷰 계층 + 같은 BC 백엔드 정렬 확장)
> 상위 FR: FR-UX-06 (신규 FR 없음, 총수 129 불변)
> 작성: 2026-07-24

## 배경

이슈 목록(`routes/issues.index.tsx`)이 현재 카드 리스트(`<ul>/<li>` + `IssueCard` `<div>` flex 행)로 렌더되며, **키·요약·상태**만 표시한다. 정렬 불가, 컬럼 고정. Jira Cloud 방식 테이블 네비게이터로 전환해 (1) 컬럼형 밀도 높은 목록 (2) 서버 정렬 (3) 사용자 컬럼 선택을 제공한다.

## 확정 스코프 (Maxi, 2026-07-24)

- ✅ 카드 → `ui/table`(PR2 #286 프리미티브) 전환
- ✅ **서버 전체 정렬** — 프론트가 `?sort=필드,방향` 전송 + 백엔드 `listWithType`가 정렬 허용목록 적용
- ✅ **컬럼 선택 → localStorage** 저장(기기별, 백엔드 preference 미신설)
- ⛔ **split view 제외** — PR19(이슈 상세 탭화)로 미룸

## 사용자 시나리오 (Given-When-Then)

### S1 — 테이블 렌더 (카드 대체)
- **Given** 프로젝트에 이슈가 여러 건 있고
- **When** 이슈 목록 페이지에 진입하면
- **Then** 이슈가 컬럼형 테이블(`<table>`)로 표시된다. 기본 컬럼 = 체크박스 · 키 · 타입 · 요약 · 상태 · 담당자 · 우선순위 · 수정일. 행 클릭 = 상세 이동, 체크박스 클릭은 이벤트 전파 차단(디자인 스펙 §3 계약).

### S2 — 서버 정렬
- **Given** 테이블이 렌더된 상태에서
- **When** 정렬 가능한 컬럼 헤더(예: 우선순위)를 클릭하면
- **Then** `?sort=priority,desc`가 서버로 전송돼 **전체 결과**가 정렬된 첫 페이지로 갱신된다. 다시 클릭하면 asc↔desc 토글, 세 번째 클릭 시 정렬 해제(기본 정렬 = createdAt desc 복귀). 정렬 상태는 URL 쿼리에 반영돼 새로고침·공유 시 보존.

### S3 — 정렬 + 페이지네이션 상호작용
- **Given** 우선순위 내림차순 정렬 상태에서
- **When** 2페이지로 이동하면
- **Then** 정렬이 유지된 채 2페이지가 표시된다(정렬은 전체 결과 기준이므로 페이지 경계가 정렬 순서를 깨지 않음).

### S4 — 컬럼 선택
- **Given** 테이블이 렌더된 상태에서
- **When** 컬럼 선택 드롭다운에서 "라벨"을 켜고 "우선순위"를 끄면
- **Then** 테이블에 라벨 컬럼이 나타나고 우선순위 컬럼이 사라진다. 이 선택은 localStorage에 저장돼 같은 브라우저로 재방문 시 유지된다. 필수 컬럼(체크박스·키·요약)은 숨길 수 없다.

### S5 — 필터 + 정렬 동시
- **Given** IssueFilterBar로 상태 필터가 걸린 상태에서
- **When** 요약 컬럼으로 정렬하면
- **Then** 필터된 결과 집합이 정렬돼 표시된다. queryKey에 filter와 sort가 모두 포함돼 캐시가 올바르게 분기된다.

### S6 — 빈 상태 / 로딩 / 에러 (기존 보존)
- **Given** 필터 결과 0건 / 로딩 중 / API 에러
- **When** 각 상태에 진입하면
- **Then** 기존 `FilteredEmptyState`(필터 초기화 CTA) · `IssueEmptyState` · 로딩 · 에러 UI가 테이블 컨텍스트에서도 동일하게 동작한다.

## 기능 요구사항 (FR — FR-UX-06 하위, 신규 FR 아님)

- **F1** 이슈 목록을 `ui/table` 프리미티브로 렌더한다. `<table>` 시맨틱 보존, 컨테이너 `overflow-x: auto`(body 가로 스크롤 금지).
- **F2** 정렬 가능 컬럼 헤더 클릭 → 서버 `?sort=<field>,<dir>` 전송. asc↔desc↔해제 3-state 토글. 정렬 상태 URL 쿼리 반영.
- **F3** 백엔드 `IssueRepository.listWithType`가 `pageable.sort`를 **정렬 허용목록**으로 적용. 허용 필드 외 요청은 무시(기본 정렬 fallback). 정렬 무지정 시 기존 `created_at desc` 유지(**무회귀**).
- **F4** 컬럼 선택 드롭다운. 표시 컬럼 토글, localStorage persist. 필수 컬럼(체크박스·키·요약) 숨김 불가.
- **F5** 기존 기능 전량 보존 — 체크박스 선택(페이지 교차 누적)·전체선택·페이지네이션·IssueFilterBar 결선·행 클릭 네비게이션·담당자 이름 해석(useUsersByIds).

## 비기능 요구사항 (NFR)

- **N1 접근성** — `<h1>` 단일 유지(e2e 34건)·행 클릭과 체크박스 전파 분리·정렬 헤더 `aria-sort` 부여·컬럼 선택 드롭다운 키보드 접근. `role="navigation"` 라벨(페이지 탐색 등) 계약 불변([[frontend-nav-aria-label-e2e-contract]]).
- **N2 무회귀** — 정렬 미지정 API 호출은 기존 응답과 동일(기존 프론트/e2e 무영향). 페이지네이션 계약 불변.
- **N3 성능** — 정렬은 인덱스된 컬럼 우선(created_at·priority·key). 정렬 허용목록으로 임의 컬럼 정렬 차단(전체 테이블 스캔 방지).
- **N4 보안** — BROWSE 권한 게이팅·필드 마스킹(maskFieldsForPage) 유지. 정렬 필드 허용목록으로 SQL injection·의도치 않은 컬럼 노출 차단.

## API 인터페이스 (REST)

`GET /api/v1/issues` — **변경: `sort` 쿼리 파라미터 실제 적용**

- 요청: 기존 `projectKey · page · size · status[] · assignee[] · label[] · component[]` + `sort=<field>,<dir>`(예: `sort=priority,desc`). Spring `Pageable`이 이미 바인딩하나 현재 `listWithType`가 무시 → **적용하도록 보강**.
- 정렬 허용목록(초안, impl에서 실제 ISSUES 컬럼 대조 확정): `key · summary · priority · createdAt · updatedAt`. `currentStateKey`는 G1(워크플로우 순서 불일치)로 초기 제외. 목록 외 필드 → 기본 정렬(createdAt desc).
- 응답: 기존 Spring Page 형태 불변(content/pageable/totalElements). cursor 모드(FR-API-01)와 충돌 없음(offset 모드 한정).

## 데이터 모델 변경

**없음.** 도메인 엔티티·마이그레이션·DTO 필드 추가 0. `listWithType`의 `ORDER BY` 절만 고정 → 동적(허용목록).

## 엣지 케이스

- **EC1** 정렬 필드가 허용목록에 없음 → 400 아닌 기본 정렬 fallback(관대 처리, 깨진 URL 방어).
- **EC2** localStorage에 저장된 컬럼 설정이 손상/구버전(없는 컬럼 키) → 안전하게 기본 컬럼으로 복구(파싱 실패 무시).
- **EC3** 정렬 변경 시 현재 page 리셋(0페이지로) — 정렬 바뀌면 현재 페이지 번호 의미 없음.
- **EC4** 필터 변경 시 기존 clearAll·page 리셋 동작 보존(PR17 FilterBar 결선).
- **EC5** 좁은 화면 — 테이블 가로 스크롤(overflow-x auto). 컬럼 선택으로 컬럼 줄이기 가능.
- **EC6** 담당자 UUID → 이름 해석 실패(useUsersByIds 로딩/미해결) → UUID·placeholder 안전 표시(기존 동작 보존).
- **EC7** 선택(체크박스) 상태가 정렬/페이지 이동에도 페이지 교차 누적 보존(useIssueSelection).

## 제약 조건

- 한 PR = 한 BC 원칙 하에서 백엔드 변경은 **issue-tracking 같은 BC 뷰 계층 확장**만(PR#13 옵션 C 선례). 다른 BC 무접촉.
- split view·상세 재구성은 이 PR에서 금지(PR19).
- 컬럼 선택 백엔드 저장 금지(localStorage만).
- E2E 러너 함정: vitest·playwright 모두 `pnpm test -- <파일>` 인자 삼킴 → 바이너리 직접 호출([[e2e-playwright-filter-arg-drop]]). CI e2e 잡 없음 → 로컬 e2e 필수.

## 측정 가능한 완료 기준

1. 이슈 목록이 `<table>`로 렌더되고 기존 e2e(issue list·selection·pagination·filter) green.
2. 정렬 헤더 클릭 → `?sort=` 전송 → 서버 정렬된 결과 표시(단위 + e2e 검증).
3. 백엔드 `listWithType` sort 허용목록 단위 테스트(허용 필드 정렬·비허용 fallback·무지정 무회귀) green.
4. 컬럼 선택 토글 → 표시 변경 + localStorage persist(재마운트 후 유지) 단위 검증.
5. typecheck 0 · lint 0 · 전수 유닛 green · build 0 · 관련 e2e 로컬 green.
6. FR 총수 129 불변, 도메인/마이그레이션 무변경.

## Brainstorming Sanity Check (Phase B — 자체 적대적 리뷰)

office-hours 스킵(제품 결정 3건 확정)에 대응해 자체 sanity check 수행. 발견 gap 3건(모두 수정 가능·Maxi 결정 불필요) 반영.

- **G1 상태 정렬 의미** — `currentStateKey` 알파벳 정렬은 워크플로우 표시순(할일→진행→완료)과 불일치. **처방**: 정렬 허용목록에서 status 제외(초기), 또는 상태 표시순 정렬은 후속. → 허용목록 초안에서 `currentStateKey`는 **보류**, 실제 정렬 컬럼 = `key · summary · priority · createdAt · updatedAt`로 축소. plan에서 최종 확정.
- **G2 ★e2e 셀렉터 보존(load-bearing)** — 현재 `IssueCard`의 계약: 체크박스 `data-testid={select-${key}}`·`aria-label="이슈 선택"`, 요약 `data-testid={issue-summary-${key}}`, 링크 `aria-label={key}`(getByLabel(key) strict), 상태 `role="status"`. **테이블 전환 시 이 셀렉터를 verbatim 보존**하거나 의존 e2e/유닛을 **같은 PR에서** 갱신. 보존이 blast radius 최소. `<td>` 안에 동일 요소 배치로 대부분 보존 가능.
- **G3 정렬 UI 명시** — 정렬 헤더는 `<TableHead>` 안 버튼 + 방향 아이콘(▲/▼) + `aria-sort={ascending|descending|none}`. 스크린리더·시각 둘 다 방향 인지.

**결론**: gap 3건 스펙 반영 완료. Maxi 결정 필요 gap 0. Phase A↔B loop 불필요(1회 통과).
