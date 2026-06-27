# FR-TL-02 — 이슈 간 의존성 라인 (blocks 관계)

> slug: fr-tl-02-timeline-deps
> type: api
> agent: backend-engineer
> primary BC: issue-tracking (논리 FR은 agile-planning §4.2)
> 생성: 2026-06-28

## Brief

FR-TL-01에서 구현한 자체 SVG/CSS Gantt 타임라인 위에, `blocks` 관계로 연결된 이슈들을
화살표 라인으로 오버레이한다. 데이터는 FR-LK-01의 `issue_links` 테이블(blocks 관계 포함)을 활용한다.

- 백엔드: `GET /api/v1/timeline/deps?project=...` 신규 엔드포인트 — 타임라인에 표시 중인 이슈들 사이의
  blocks 의존 엣지 목록 반환
- 프론트: 자체 SVG Gantt 위에 의존 라인 SVG 오버레이 (클릭 시 강조)

선행(완료): FR-TL-01(Gantt 뷰), FR-LK-01(이슈 링크 issue_links), FR-LK-02(링크 그래프 BFS)

classify: type=api, agent=backend-engineer, primary_bc=issue-tracking

## 도메인 정리

- **BC**: agile-planning(엔드포인트 소유) + issue-tracking(데이터 어댑터) + shared-kernel(cross-BC 포트).
  classify는 issue-tracking으로 잡았으나, 타임라인 엔드포인트는 FR-TL-01부터 agile-planning 소유.
- **영향 엔티티(전부 기존)**:
  - `TimelineItemView`(shared-kernel, FR-TL-01) — 타임라인에 보이는 이슈.
  - `IssueLink` / `LinkType.BLOCKS`(issue-tracking, FR-LK-01) — 단방향 저장(source가 target을 차단).
  - `TimelineLookupPort`(shared-kernel, FR-TL-01) — cross-BC 조회 포트, 메서드 1개 추가 예정.
- **새 용어**: 없음. glossary에 "타임라인 아이템(TimelineItem)"·"링크(Link — blocks 포함)" 이미 정의됨.
  "의존성 라인(dependency line)"은 신규 도메인 엔티티가 아니라 기존 BLOCKS 링크의 **시각화 개념**(UI).
- **신규 스키마**: 0(예상). `issue_links` 활용. 프로젝트 단위 blocks 조회 인덱스 필요 여부는 spec/plan에서 EXPLAIN 확정.
- **기존 결정 충돌**: 없음. FR-TL-01 cross-BC 포트 패턴 + FR-LK-01 blocks 링크의 자연스러운 확장.
- **핵심 결정(ADR 기록)**: FR-LK-02 그래프 엔드포인트(`/issues/{key}/graph`, 단일 이슈 중심·전체 링크 타입·mermaid)는
  목적·BC 소유·시각화가 모두 달라 **재사용하지 않고**, agile-planning에 타임라인 전용 project-scoped BLOCKS 엣지
  엔드포인트(`GET /api/v1/timeline/deps`)를 신설. 양끝 가시성 필터로 비가시 이슈 누출 차단.
- **관련 ADR**: [docs/decisions/2026-06-28-timeline-deps-blocks-overlay.md](../decisions/2026-06-28-timeline-deps-blocks-overlay.md) (생성됨)
- **grill-with-docs 스킵 사유**: 새 유비쿼터스 용어 0건 + 완료된 FR-TL-01/FR-LK-01 패턴의 파생 작업.
  대화형 도메인 검증보다 직접 정리가 적합(learnings `bts-review-plan-autoplan-overkill` 정신).

## 스펙

전체 스펙. [docs/specs/2026-06-28-fr-tl-02-timeline-deps.md](../specs/2026-06-28-fr-tl-02-timeline-deps.md)

핵심 시나리오 요약.
- `GET /api/v1/timeline/deps?project=KEY` → BLOCKS 링크 엣지 `{ blockerKey, blockedKey }` 목록 반환.
- 엣지 = 양끝 이슈가 모두 (동일 프로젝트 + 미삭제 + 타임라인 아이템(날짜 1개+) + viewer 가시) 인 blocks 링크만.
- 한쪽이라도 비가시/cross-project/날짜0 → 엣지 제외(누출·댕글링 차단). 양끝 가시성 SQL 푸시다운.
- 프론트 = 기존 자체 SVG 간트 위 의존 라인 오버레이 + 클릭 강조.

핵심 결정.
- agile-planning이 엔드포인트 소유, `TimelineLookupPort.listBlocksDepsByProject` default 메서드 확장, adapter가 실 구현.
- FR-LK-02 그래프(`/issues/{key}/graph`)는 목적·BC·시각화 달라 재사용 안 함(ADR).
- **PR 분할 = 게이트 1 Maxi 결정**: 풀스택 1 PR(권장) vs 백엔드/프론트 2 PR.

## Brainstorming Check

✅ 통과 (자체 sanity 점검, office-hours 부적합 learning `bts-spec-office-hours-mismatch` 적용).
최대 리스크 = 비가시/cross-project 이슈 키 누출 → 양끝 가시성 SQL 푸시다운으로 차단(D2). 댕글링 라인 = 양끝 타임라인 조건으로 차단. 상호 blocks 두 엣지, self-block DB CHECK 불가, truncated 상한 일관.

## Plan

> **이번 PR 권장 = 백엔드 D1~D5 (T1~T5).** 프론트 D6~D7 (T6~T11)은 후속 PR.
> 게이트 1에서 Maxi가 "풀스택 1 PR" 선택 시 T6~T11도 이번 PR에 포함(프론트 task는 그때 상세화).
> 구현 전략 핵심 — adapter가 기존 `listVisibleForTimeline` 가시 집합(id→key 맵)을 재사용하고
> 그 id 집합 안에서만 `issue_links` BLOCKS 조회 → **새 보안 판정 경로 0**(양끝 가시성 자동 보장) + **마이그레이션 0**(기존 `idx_issue_links_source_id` 활용).

### Task 1. shared-kernel — TimelineLookupPort에 deps 조회 default 메서드 + VO

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/timeline/TimelineLookupPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/timeline/TimelineLookupPortTest.kt`]
- depends-on: []

**RED**:
- `TimelineLookupPortTest`에 default 동작 테스트 추가.
  ```kotlin
  @Test fun `listBlocksDepsByProject default returns empty page`() {
      val port = object : TimelineLookupPort {}
      val page = port.listBlocksDepsByProject("ATLAS", UUID.randomUUID())
      assertThat(page.edges).isEmpty()
      assertThat(page.truncated).isFalse()
  }
  ```
- 실패: `listBlocksDepsByProject` / `TimelineDepsPage` / `TimelineDepEdge` 미존재.

**GREEN**:
- `TimelineDepEdge(blockerKey: String, blockedKey: String)` data class.
- `TimelineDepsPage(edges: List<TimelineDepEdge>, truncated: Boolean)` data class.
- `TimelineLookupPort.listBlocksDepsByProject(projectKey: String, viewerUserId: UUID): TimelineDepsPage = TimelineDepsPage(emptyList(), false)` default(fail-safe 빈 페이지, memory `interface-extension-default-method`).

**REFACTOR**: KDoc — blockerKey=source(차단측)/blockedKey=target(피차단측), 양끝 가시성·타임라인 조건은 구현체 책임 명시.

**검증**: `./gradlew :backend:shared-kernel:test --tests TimelineLookupPortTest`

### Task 2. issue-tracking — IssueLinkRepository.findBlocksEdgesAmong (BLOCKS 엣지 id-집합 조회)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/link/repository/IssueLinkRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/link/repository/BlocksEdgeRow.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/link/repository/IssueLinkRepositoryDepsTest.kt`]
- depends-on: []

**RED** (Testcontainers 통합):
- 시드 — 이슈 A,B,C,D + 링크 (A blocks B), (A relates C), (A blocks D).
- `findBlocksEdgesAmong(setOf(A.id, B.id, C.id))` →
  - (A,B) blocks 엣지만 반환. (A relates C)는 타입 제외. (A blocks D)는 D가 집합 밖이라 제외.
  ```kotlin
  @Test fun `returns only blocks edges with both endpoints in id set`() { ... }
  @Test fun `excludes non-blocks link types`() { ... }
  @Test fun `excludes edge when one endpoint outside id set`() { ... }
  ```
- 실패: `findBlocksEdgesAmong` / `BlocksEdgeRow` 미존재.

**GREEN**:
- `BlocksEdgeRow(sourceId: UUID, targetId: UUID)`.
- jOOQ — `SELECT source_id, target_id FROM issue_links WHERE link_type='blocks' AND source_id = ANY(?ids) AND target_id = ANY(?ids) ORDER BY source_id, target_id LIMIT DEPS_FETCH_LIMIT + 1`. 빈 집합이면 빈 리스트 즉시 반환(빈 ANY 회피).
- `DEPS_FETCH_LIMIT = 1000` 상수.

**REFACTOR**: KDoc + EXPLAIN — production 렌더 SQL을 `EXPLAIN`으로 확인해 `idx_issue_links_source_id` 사용 확정(memory `jooq-likeignorecase-expression-trgm-index`). seqscan이면 plan에 V033 부분 인덱스 추가 보고(기본 가정: 마이그레이션 0).

**검증**: `./gradlew :backend:issue-tracking:test --tests IssueLinkRepositoryDepsTest`

### Task 3. issue-tracking — TimelineLookupAdapter.listBlocksDepsByProject 구현

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/timeline/TimelineLookupAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/timeline/TimelineLookupAdapterDepsTest.kt`]
- depends-on: [1, 2]

**RED** (Testcontainers 통합 — 스펙 S1~S6/EC):
- S1 happy — (ATLAS-1 blocks ATLAS-2), 둘 다 날짜·가시 → `[{blocker:"ATLAS-1",blocked:"ATLAS-2"}]`.
- S2 — relates/duplicates 링크 제외.
- S3 — blocked 이슈가 날짜 0개(타임라인 미포함) → 엣지 제외.
- S4 — blocked 이슈가 viewer 비가시 보안등급 → 엣지 제외, 키 미노출.
- S5 — cross-project blocks(ATLAS-1 blocks BETA-2) → 제외.
- S6 — 상호 blocks(A↔B) → 두 엣지 반환.
- truncated 전파(timeline truncated 또는 blocks limit 초과).

**GREEN**:
- `listBlocksDepsByProject`:
  1. `securityDirectory.accessibleLevels(viewer, project)`.
  2. `issueRepository.listVisibleForTimeline(project, viewer, access)` → 가시 타임라인 엔트리(≤500). id→key 맵 구성.
  3. `linkRepository.findBlocksEdgesAmong(idMap.keys)` → BlocksEdgeRow 목록.
  4. 각 row의 sourceId/targetId를 idMap으로 key 변환 → `TimelineDepEdge`. (양끝 모두 가시 집합 ⇒ 매핑 항상 성공)
  5. `truncated = timelinePage.truncated || (edges.size > DEPS_FETCH_LIMIT)`. 상한 take.
- `linkRepository: IssueLinkRepository` 주입 추가.

**REFACTOR**: KDoc — 양끝 가시성이 "가시 집합 내 조회"로 자동 보장됨을 명시(새 보안 경로 0). 매핑 헬퍼 추출.

**검증**: `./gradlew :backend:issue-tracking:test --tests TimelineLookupAdapterDepsTest`

### Task 4. agile-planning — TimelineApplicationService.getDeps (BROWSE 게이트 + 정렬)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/TimelineApplicationService.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/TimelineApplicationServiceDepsTest.kt`]
- depends-on: [1]

**RED** (단위 — mock `IssuePermissionResolver` + `TimelineLookupPort`):
- BROWSE 거부 → `ResponseStatusException(403)`.
- BROWSE 허용 → 포트 결과를 `blockerKey ASC, blockedKey ASC` 정렬해 `TimelineDepsResult(edges, truncated)` 반환.
  ```kotlin
  @Test fun `getDeps denies without BROWSE`() { ... }   // 403
  @Test fun `getDeps returns sorted edges`() { ... }     // 정렬 결정성
  ```

**GREEN**:
- `TimelineDepsResult(edges: List<TimelineDepEdge>, truncated: Boolean)`.
- `getDeps(actorId, projectKey)` — BROWSE `hasPermission` fail-closed(403) → `listBlocksDepsByProject` → `sortedWith(compareBy(blockerKey, blockedKey))` → 결과.

**REFACTOR**: KDoc — 타임라인 조회와 동일 BROWSE 게이트 재사용 명시.

**검증**: `./gradlew :backend:agile-planning:test --tests TimelineApplicationServiceDepsTest`

### Task 5. agile-planning — TimelineController.getDeps + 응답 DTO

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/TimelineController.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/TimelineResponses.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/TimelineControllerDepsTest.kt`]
- depends-on: [4]

**RED** (MockMvc HTTP):
- `GET /api/v1/timeline/deps?project=ATLAS` → 200 + `{data:{deps:[{blockerKey,blockedKey}],truncated}}`.
- `project` 누락 → 400. 미인증 → 401. BROWSE 없음 → 403.

**GREEN**:
- `TimelineDepEdgeResponse(blockerKey, blockedKey)` + `TimelineDepsResponse(deps, truncated)` DTO + `from` 매퍼.
- `@GetMapping("/deps") fun getDeps(@RequestParam project)` — actor 추출(401, 기존 `currentActorId` 재사용) → `service.getDeps` → DataResponse 봉투.

**REFACTOR**: KDoc — 클래스 KDoc 엔드포인트 목록에 `/deps` 추가. `TimelineExceptionHandler` 스코프(assignableTypes) 그대로 적용 확인.

**검증**: `./gradlew :backend:agile-planning:test --tests TimelineControllerDepsTest` + 전체 `./gradlew :backend:agile-planning:test`

### (후속 PR) 프론트 D6~D7 — 개요

> 게이트 1에서 "풀스택 1 PR" 선택 시 이번 PR에 포함하며, 그때 RED/GREEN/REFACTOR 상세화.

- **T6** `api/timeline.ts` — `timelineDepsResponseSchema`(`{deps:[{blockerKey,blockedKey}],truncated}`) + `fetchTimelineDeps(projectKey)`. (frontend-engineer, depends []).
- **T7** `hooks/use-timeline.ts` — `useTimelineDeps(projectKey)` + `timelineKeys.deps(...)`. (frontend, depends T6).
- **T8** `lib/timeline-layout.ts` — `computeDependencyLines(groups/geometry, deps)` 순수함수(blocker 막대→blocked 막대 화살표 경로, jsdom 안전). (frontend, depends []).
- **T9** `mocks/timeline-handlers.ts` + `timeline-fixtures.ts` — `/timeline/deps` MSW 핸들러 + 픽스처(공유 store 시드, memory `msw-derived-behavior-shared-store-e2e`). (frontend, depends T6).
- **T10** `components/timeline/DependencyOverlay.tsx`(신규) + `GanttChart.tsx` — SVG 오버레이 레이어 + 클릭 강조(S10). (frontend, depends T7,T8,T9).
- **T11** `e2e/timeline.spec.ts` — 의존 라인 실렌더 + 클릭 강조 E2E + 기존 timeline E2E 무회귀. (qa-engineer, depends T10).

## Plan 메타

- task 수: **5 (이번 PR 백엔드 권장)** / 11 (풀스택 전체). 게이트 1 결정에 따라 활성 집합 확정.
- 백엔드 wave (depends-on + files 기준): Wave1=[T1,T2], Wave2=[T3(1,2),T4(1)], Wave3=[T5(4)] — 3 wave.
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저).
- 마이그레이션: 0 (기본 가정, T2 EXPLAIN으로 확정).
- 보안 리뷰 포커스(codereview): 양끝 가시성 누출(S4)·cross-project 누출(S5)·BROWSE 게이트(S7/S8). 새 보안 경로 없이 기존 가시 집합 재사용임을 검증.
- 추가 검증: ktlint·detekt·ArchUnit BC 격리(agile-planning→issue-tracking import 0).

## 리뷰 결과

### plan-eng-review (2026-06-28) — 백엔드 집중 독립 리뷰

- ✅ **누출 차단 전략 검증**. "가시 타임라인 집합(id→key) 내에서만 blocks 조회" → 양끝 모두 가시 집합 멤버 ⇒ 양끝 가시·동일프로젝트·날짜보유·미삭제 자동 보장. 보안 술어를 source/target 두 alias에 복제하는 SQL보다 단순·안전(새 보안 판정 경로 0, FR-NT-03 BLOCKER 정신). `listVisibleForTimeline` 재사용으로 검증된 경로 그대로.
- ✅ **BC 격리**. T4/T5(agile-planning)는 shared-kernel 포트 + IssuePermissionResolver만 의존. T3 adapter(issue-tracking)가 실 구현. ArchUnit 검증 T5에 포함.
- ✅ **테스트 매핑**. 스펙 S1~S11/EC1~EC11이 T1(default)·T2(repo)·T3(adapter S1~S6)·T4(service 403/정렬)·T5(HTTP 400/401/403) 테스트로 커버.
- ⚠️ **CONCERN-1 (impl 필수 준수) — vacuous 테스트 차단**. T3의 S4(비가시 끝점 제외)·S5(cross-project 제외)는 반드시 **positive control** 포함. 같은 시드에 (a) 가시·동일프로젝트 blocks 엣지 1건(반드시 결과에 **존재** 단언) + (b) 비가시/cross-project blocks 엣지 1건(결과에 **부재** 단언)을 함께 둬 "필터가 실제로 동작"함을 증명. control 없이 "결과에 X 없음"만 단언하면 X-X=∅ 가짜그린(FR-MV-01 EC8/EC9 vacuous BLOCKER 재발). bts-impl로 인계.
- ⚠️ **CONCERN-2 (마이너)**. `findBlocksEdgesAmong` 상한(LIMIT 1001)을 `source_id, target_id`(UUID) 순으로 자르는데 최종 노출 정렬은 `blockerKey, blockedKey`(key) 순. 입력 동일 시 출력 결정적이라 무해하나, 1000건 초과 truncation 시 "어떤 1000건"이 id순 컷이라 key순 기대와 다를 수 있음. ≤500 노드 규모에서 1000 초과는 거의 없어 실해 미미. 필요 시 key 정렬 후 컷으로 향후 교정 가능.
- BLOCKER: **없음**.

### plan-devex-review (2026-06-28)

- ✅ **계약 일관성**. `/api/v1/timeline/deps?project=X` + `{data:{deps:[...],truncated}}` 봉투는 기존 `/api/v1/timeline`과 동형. v1 additive, 기존 엔드포인트 무변경(FR-TL-01 회귀 0).
- ✅ **DTO nullable 0**. deps DTO(blockerKey/blockedKey: String, truncated: Boolean)는 nullable 필드 없음 → @JsonInclude(NON_NULL)↔Zod drift 함정 무관(프론트 T6 단순).
- ℹ️ **관찰 (결정 완료)**. deps를 `/timeline` 응답에 임베드(1 round trip) 대신 별도 엔드포인트(2 round trip)로 분리. ADR 근거 — 오버레이는 선택적/토글 가능 + /timeline 무변경(회귀 0) + 분리 캐싱. 1K 규모 내부도구에서 2콜 부담 미미. 별도 엔드포인트 유지 적절.
- BLOCKER: **없음**.

### 종합

- BLOCKER 0. CONCERN 2건(C1 vacuous 테스트 필수 준수 = bts-impl 인계, C2 마이너 truncation 순서).
- 게이트 1 Maxi 결정 항목: **PR 분할** (권장 = 백엔드 D1~D5 이번 PR / 프론트 후속 — 대안 풀스택 1 PR).
