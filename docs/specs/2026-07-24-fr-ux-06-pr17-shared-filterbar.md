# FR-UX-06 PR17 — 공유 FilterBar 통합 스펙

> slug: fr-ux-06-pr17-shared-filterbar · type: ui(refactor) · 생성: 2026-07-24
> 상위: FR-UX-06 ADR D1~D8 · 마스터 플랜 PR17(“IssueFilterBar/BoardFilterBar 중 하나 거의 클론”)
> **동작 보존 리팩터. FR 총수 불변 129. 백엔드/마이그레이션 0.**

## 배경 — 무엇이 중복인가 (실측)

| | IssueFilterBar (391 LOC) | BoardFilterBar (211 LOC) |
|---|---|---|
| 위치 | `components/issues/IssueFilterBar.tsx` | `components/board/BoardFilterBar.tsx` |
| value 타입 | `IssueFilterParams`(=Board + `statusKeys`) | `BoardCardFilterParams` |
| 상태 섹션 | `StatusMultiSelect`(useWorkflows·EC7 fail-safe) | **없음** (보드 컬럼이 곧 상태) |
| 담당자 typeahead | ✅ 동일 | ✅ 동일 |
| 라벨 자동완성 | ✅ 동일 | ✅ 동일 |
| 컴포넌트 멀티셀렉트 | ✅ 동일 | ✅ 동일 |
| 활성 칩 | 상태+담당자+라벨 | 담당자+라벨 |
| 초기화·activeCount·wrapper | ✅ 동일 | ✅ 동일(activeCount에 statusKeys 없음) |
| i18n | `issueFilterLabels` | `boardFilterLabels` |

**중복 실체**: `AssigneeSection`·`ActiveFilterChips`·`Chip`·`handleAssigneeSelect/LabelCommit/Reset`·wrapper·count 표시가 **라벨/id 접두사만 다른 근-verbatim 복제**. i18n 라벨은 `statusLabel: '상태'` 한 키 빼고 **완전 동일**.

## 설계 결정 (핵심)

**D-1. 공유 `FilterBar` 코어 추출 + 얇은 래퍼 유지.**
- 신설 `components/filters/FilterBar.tsx` = 담당자/라벨/컴포넌트/칩/초기화/count/wrapper 공용 코어.
- `IssueFilterBar`·`BoardFilterBar`는 **이름·경로·props 시그니처 그대로 유지하되 내부가 `FilterBar`로 위임**하는 얇은 래퍼로 축소. → **소비처(issues.index·board 라우트) 및 그 테스트 0 변경**(최소 폭발 반경).

**D-2. 상태 섹션은 옵션 슬롯.**
- `FilterBar`는 제네릭 `<T extends BoardCardFilterParams>`. 상태 관련은 옵션 prop으로 주입:
  - `statusSection?: ReactNode` — 담당자 섹션 앞에 렌더(이슈: `<StatusMultiSelect>`, 보드: undefined).
  - `statusChips?: ReactNode` — 활성 칩 목록 맨 앞에 렌더(이슈 상태 칩).
  - `extraActiveCount?: number` — activeCount에 더함(이슈 `statusKeys.length`).
- `useWorkflows`·`StatusMultiSelect`·상태 nameMap은 **`IssueFilterBar` 래퍼에 캡슐화**(보드에 불필요한 쿼리 유발 금지, 조건부 훅 회피).

**D-3. i18n 라벨 단일화.**
- 신설 `i18n/filter-bar-labels.ts` = 병합 단일 출처(`statusLabel` 포함). `issueFilterLabels`/`boardFilterLabels`는 여기서 재export하거나 소비처 직접 교체. **표시 문자열 byte 불변**(전부 동일 문자열이라 시각 회귀 0).

**D-4. id 접두사 = prop.**
- `idPrefix: string`(`'issue-filter'` | `'board-filter'`). 기존 element id(`{prefix}-assignee-input`·`{prefix}-label-input`·`{prefix}-status-{key}`) **verbatim 보존** → 기존 유닛/e2e 셀렉터 계약 불변.

## 사용자 시나리오 (Given-When-Then) — 전부 “현행 동작 그대로”

1. **이슈 필터** — Given 이슈 목록 페이지, When 상태/담당자/라벨/컴포넌트 선택, Then 칩 표시·activeCount 갱신·`onFilterChange`로 부모에 전달(page=0 리셋). 초기화 시 전 필드 비움. **EC7**: useWorkflows 로딩/에러면 상태 섹션 미렌더(throw 금지).
2. **보드 필터** — Given 보드 상세 존재, When 담당자/라벨/컴포넌트 선택, Then 칩·activeCount·`onChange`→navigate URL 갱신. 수동 변경 시 소비처가 `activeQuickFilterId` 해제(C3-b, **소비처 책임·불변**).
3. **담당자 이름 안정 표시** — 검색어를 비워도 `useUsersByIds`로 선택 담당자 이름 유지(양쪽).

## 보존 계약 (load-bearing — 깨지면 회귀)

- 렌더 결과·DOM 구조·클래스·aria(`role="list" aria-label="적용된 필터"`, 칩 `{name} 제거`, 44px 터치 타깃, label 연결) **양쪽 다 불변**.
- element id 접두사 `issue-filter-*`/`board-filter-*` verbatim.
- `StatusMultiSelect` 옵션 0이면 null 렌더 / `ActiveFilterChips` 칩 0이면 null.
- activeCount 산식: 공통(assignee+label+component+unassigned) + 이슈만 statusKeys.
- 보드: `BoardFilterBar`는 소비처가 보드 상세 있을 때만 렌더(EC7) — 래퍼 유지로 자동 보존.
- 퀵필터(`QuickFilterChips`·`activeQuickFilterId`·`BoardFilterQueryParser`)는 **이 컴포넌트 밖**(board 라우트) → 무변경.

## 엣지 케이스

- useWorkflows 에러(이슈): 상태 섹션 없이 나머지 정상.
- 선택 담당자가 검색 결과에 없음: id fallback 표시(`assigneeNameMap.get(id) ?? id`).
- 컴포넌트는 칩으로 안 뜸(양쪽 현행 그대로 — 컴포넌트 칩 미표시 유지).
- 제네릭 T 확장 필드(statusKeys)가 `onChange({...value, ...})` 스프레드로 보존되는지(타입 가드).

## 측정 가능한 완료 기준

- [ ] `IssueFilterBar`/`BoardFilterBar` 기존 유닛 테스트 **무수정 green**(래퍼 시그니처 불변).
- [ ] 공유 `FilterBar` 유닛 테스트 신설(공통 동작 커버) + 이슈/보드 래퍼 차이(상태 유무) 테스트.
- [ ] typecheck 0 / lint 0 / 관련 e2e(issues 필터·board 필터) 로컬 green.
- [ ] 순 LOC 감소(중복 서브컴포넌트+i18n 단일화). 목표 −350 근처(엄밀 목표 아님·[[spec-stated-count-becomes-blindfold]]).
- [ ] radix Dialog/기타 봉인 룰 위반 0. FR 총수 **129 불변**.
- [ ] 소비처(issues.index·board 라우트) diff 0 또는 import 경로만.

## Brainstorming Check

✅ 통과(self, 리팩터). 발견 gap 2건 반영: (1) 상태 섹션 조건부 훅 회피 위해 useWorkflows를 래퍼에 캡슐화(D-2) (2) i18n 병합 시 표시 문자열 byte 불변 명시(D-3, 시각 회귀 방지). Maxi 결정 필요 항목 없음.
