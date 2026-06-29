# FR-TL-02 D6/D7 — 타임라인 의존 라인(blocks 오버레이) 프론트 + E2E — 스펙

> slug: fr-tl-02-d6-d7-blocks-ui-e2e
> 생성: 2026-06-29
> 백엔드 D1~D5: #200 (머지 완료)
> ADR: [2026-06-28-timeline-deps-blocks-overlay](../decisions/2026-06-28-timeline-deps-blocks-overlay.md)
> 백엔드 spec: [2026-06-28-fr-tl-02-timeline-deps](2026-06-28-fr-tl-02-timeline-deps.md) §S10/S11/FR10/NFR4/EC11

## 배경 / 범위

FR-TL-01이 만든 자체 div 기반 Gantt(`GanttChart.tsx` — `position:absolute` 막대, SVG 미사용)
위에 `blocks` 의존 관계를 **SVG 오버레이 레이어**로 그린다. 각 엣지는 blocker 막대(차단측, source)
오른쪽 끝 → blocked 막대(피차단측, target) 왼쪽 끝으로 향하는 화살표.

**소비만 하는 백엔드 계약(무변경)**.
- `GET /api/v1/timeline/deps?project={KEY}` → `{ data: { deps: [{ blockerKey, blockedKey }], truncated } }`
- 양끝 가시성·동일프로젝트·타임라인아이템(날짜≥1)·미삭제는 **백엔드가 이미 필터** → 프론트는 누출 판정 책임 0.
- DTO nullable 0 (blockerKey/blockedKey: string, truncated: boolean) → Zod drift 무관.

**범위 밖**. 백엔드 변경, 의존 라인 추가/삭제(읽기 전용 오버레이), blocks 외 링크 타입.

## 사용자 시나리오 (Given-When-Then)

### S1. 정상 의존 라인 렌더
- **Given** 프로젝트 타임라인에 (BTS-2 blocks BTS-3) 의존이 있고 두 이슈 모두 막대로 보임
- **When** 타임라인 페이지를 연다
- **Then** BTS-2 막대 오른쪽 끝에서 BTS-3 막대 왼쪽 끝으로 향하는 **직각 elbow** 화살표 라인이 SVG로 렌더된다.

### S2. 의존 라인 클릭 강조 (백엔드 spec S10)
- **When** 사용자가 의존 라인을 클릭
- **Then** 그 라인이 강조(굵기/색 변화)되고 나머지 라인은 흐려진다(de-emphasis).

### S3. 강조 해제
- **When** 강조된 라인을 재클릭하거나 오버레이 바깥(빈 영역)을 클릭
- **Then** 강조가 해제되어 모든 라인이 기본 스타일로 돌아온다.

### S4. 접힌 에픽 그룹의 라인 제외
- **Given** BTS-3이 접힌 에픽 그룹(BTS-1)의 자식이라 행이 DOM에서 제거됨
- **When** 에픽 그룹을 접는다
- **Then** BTS-3을 양끝 중 하나로 갖는 의존 라인은 그려지지 않는다(보이지 않는 행으로의 라인 금지). 다시 펼치면 복원된다.

### S5. 의존 없음
- **Given** 프로젝트에 blocks 의존이 0건
- **When** 타임라인을 연다
- **Then** 오버레이는 라인을 0개 렌더한다(빈 SVG 또는 미렌더). 간트 렌더에는 영향 없음.

### S6. deps 결과 상한 초과 (백엔드 spec S11/EC10)
- **Given** deps 응답의 `truncated === true`
- **When** 타임라인을 연다
- **Then** 의존 라인 일부가 누락됐다는 경고가 표시된다(기존 timeline truncated 배너와 구분되는 별도 안내).

### S7. 권한 없음 / 빈 타임라인에서 deps 비표시
- **Given** 타임라인이 403(접근거부) 또는 items 0건 상태
- **When** 페이지를 연다
- **Then** deps 오버레이는 렌더되지 않는다(간트 자체가 없으므로). deps 쿼리는 간트가 그려질 때만 의미.

## 기능 요구사항 (FR)

- **FR1**. `api/timeline.ts`에 `timelineDepsResponseSchema`(`{deps:[{blockerKey,blockedKey}],truncated}`) + `fetchTimelineDeps(projectKey)` 추가. 기존 `apiGet` + `dataResponseSchema` 래퍼 재사용.
- **FR2**. `hooks/use-timeline.ts`에 `useTimelineDeps(projectKey)` + `timelineKeys.deps(projectKey)` queryKey 추가. `enabled`로 빈 키 비활성화.
- **FR3**. `lib/timeline-layout.ts`에 좌표 순수함수 2개.
  - `flattenVisibleRows(groups, collapsedGroups)` → `{ key, rowIndex }[]` (접힌 자식 제외, 헤더 행 인덱스 점유). GanttChart의 행 배치와 동일 순서.
  - `computeDependencyLines(visibleRows, range, dayWidth, rowHeight, deps)` → 그릴 수 있는 엣지의 **elbow 좌표** 배열 `{ blockerKey, blockedKey, startX, startY, midX, endX, endY }`. 양끝이 모두 visibleRows에 있을 때만 포함(EC11/S4 방어). 좌표는 UTC 기준 barGeometry + 행 인덱스로 계산(jsdom 안전). `rowHeight`는 인자(lib→component 역의존 차단).
- **FR4**. `components/timeline/DependencyOverlay.tsx`(신규) — 우측 막대 영역에 absolute SVG 레이어. 각 엣지를 **직각 elbow `<path d="M startX,startY H midX V endY H endX">`**(Gantt 표준, design 결정) + 화살표 `<marker>`로 렌더하고, 그 위에 **투명 넓은 hit-path**(클릭 타겟 확대)를 겹친다. GanttChart가 lines/axisOffset/width/height를 주입.
- **FR5**. 클릭 강조 — 선택된 엣지 식별자(blockerKey+blockedKey) state. hit-path 클릭 시 선택, 재클릭/빈영역 클릭 시 해제. 선택 시 해당 라인 강조(stroke 굵기+`primary`) + 나머지 흐림. 호버 시 cursor+살짝 강조.
- **FR6**. `mocks/timeline-handlers.ts` + `timeline-fixtures.ts`에 `/api/v1/timeline/deps` 핸들러 + deps 픽스처. 기존 정적 반환 + localStorage 시나리오 토글 패턴 확장(deps용 truncated/empty 시나리오).
- **FR7**. `e2e/timeline.spec.ts`에 의존 라인 실렌더 + 클릭 강조 E2E 추가. 기존 timeline E2E 무회귀.

## 비기능 요구사항 (NFR)

- **NFR1 (성능)**. 좌표 계산은 순수함수(`computeDependencyLines`) — 렌더 외부에서 계산, 간트 스크롤/리렌더 저하 없음(백엔드 spec NFR4).
- **NFR2 (jsdom 안전)**. SVG 좌표를 순수함수로 직접 계산(getBBox 미사용). 단위 테스트는 좌표/구조 단언, 픽셀 위치 정밀 단언은 E2E.
- **NFR3 (접근성)**. 의존 라인에 `aria-label`(예: "BTS-2가 BTS-3을 차단") + role. 클릭 가능 요소는 키보드 접근 고려.
- **NFR4 (i18n)**. 모든 신규 문자열은 `i18n/timeline-labels.ts`에 추가, 콜론 종결 금지(ko 테스트 통과).

## API 인터페이스 (소비)

`GET /api/v1/timeline/deps?project={KEY}` — 백엔드 #200, 무변경. 응답 `{data:{deps:[{blockerKey:string,blockedKey:string}],truncated:boolean}}`.

## 데이터 모델 변경

없음(순수 프론트).

## 엣지 케이스

- **EC1**. blocker 또는 blocked가 현재 렌더된 막대에 없음 → 그 엣지 라인 미렌더(백엔드가 양끝 타임라인 보장하나 방어적, 백엔드 spec EC11).
- **EC2**. 접힌 에픽 그룹의 자식이 양끝 중 하나 → 라인 미렌더(S4). 펼치면 복원.
- **EC3**. 상호 blocks(A↔B 두 엣지) → 두 라인 모두 렌더(겹치지 않게 표현 허용, 최소 두 path).
- **EC4**. self-block(blockerKey===blockedKey) → 방어적으로 미렌더(백엔드 DB CHECK 불가, 정상경로 없음).
- **EC5**. deps `truncated===true` → 누락 경고(S6).
- **EC6**. deps 쿼리 실패(네트워크/403) → 간트는 정상 렌더, 오버레이만 조용히 미표시(best-effort, 간트 차단 금지).
- **EC7**. blocker 막대가 blocked보다 오른쪽(역방향 시간) → 라인은 그래도 그림(데이터 그대로, 일정 위반 시각화는 별도 범위 아님).

## 제약 조건

- 백엔드 계약 무변경. 기존 `/api/v1/timeline` 응답/동작 무회귀.
- 기존 timeline 단위/E2E 테스트 무회귀.
- 프론트 api 관례 = agile-planning(boards.ts/timeline.ts) 선례 따름(`apiGet` 사용, CSRF는 GET 무관).

## 측정 가능한 완료 기준

- [x] FR1~FR7 구현 + TDD(test 커밋 우선). (T1~T6 + P1 hot-fix)
- [x] `computeDependencyLines`/`flattenVisibleRows` 순수함수 단위 테스트(정상/접힘/미존재/상호/self).
- [x] DependencyOverlay 컴포넌트 테스트(라인 수, 클릭 강조 토글, pointer-events 조건부).
- [x] api/hook 테스트(MSW 정상/truncated).
- [x] E2E: 의존 라인 실렌더 + 클릭 강조 + 막대 클릭 통과(P1) + 기존 timeline 무회귀 (9/9).
- [x] lint+typecheck+test(4863)+build 통과.

## Brainstorming Check

✅ 통과 (자체 sanity, office-hours 부적합 learning `bts-spec-office-hours-mismatch` 적용).
- 최대 리스크 = **세로 좌표 계산이 GanttChart 행 배치와 어긋남**(접기/펼치기·unclassified 헤더 행) → `flattenVisibleRows`를 GanttChart와 **동일 순회 로직**으로 추출하고 GanttChart가 그 함수를 실제로 사용(두 출처 통일, drift 차단). 단위 테스트로 접힘/미분류 케이스 고정.
- vacuous 방어 = `computeDependencyLines` 단위 테스트는 positive control(렌더되는 라인 존재 단언) + negative(접힘/미존재 시 제외 단언)를 같은 입력에 공존(FR-MV-01 EC8/EC9 교훈).
- deps truncated 안내는 timeline truncated 배너와 **구분**(둘 다 true일 수 있음).
- best-effort = deps 실패가 간트를 깨지 않음(EC6).
