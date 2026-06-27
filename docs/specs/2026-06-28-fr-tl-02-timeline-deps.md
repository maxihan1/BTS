# FR-TL-02 이슈 간 의존성 라인 (blocks 관계) — 스펙

> slug: fr-tl-02-timeline-deps · BC: agile-planning(엔드포인트)+issue-tracking(어댑터)+shared-kernel(포트) · SDD §13.3.2
> 작성: 2026-06-28 · type: api
> 선행(완료): FR-TL-01(Gantt #192/#194) · FR-LK-01(issue_links #135) · FR-LK-02(그래프 #138)
> ADR: [2026-06-28-timeline-deps-blocks-overlay](../decisions/2026-06-28-timeline-deps-blocks-overlay.md)

## 범위 (Scope)

**이번 PR (D1~D7, 풀스택 수직 슬라이스 권장)**.
- 백엔드 — `GET /api/v1/timeline/deps?project={key}` (agile-planning) + `TimelineLookupPort` 확장 + `TimelineLookupAdapter` BLOCKS 조회 + 단위/통합 테스트.
- 프론트 — 기존 자체 SVG 간트(`GanttChart.tsx`) 위 의존 라인 SVG 오버레이 + `/timeline/deps` 클라이언트 + 클릭 강조 + E2E.

> **PR 분할은 게이트 1에서 Maxi 결정.** 기본 권장 = 풀스택 1 PR(엔드포인트+소비자 동반, 댕글링 엔드포인트 회피, 렌더 방식은 ADR로 선결돼 블로커 없음). 대안 = FR-TL-01처럼 백엔드(D1~D5)/프론트(D6~D7) 2 PR.

**이번 PR 제외 (후속)**.
- FR-TL-03 — 타임라인 줌(주/월/분기).
- BLOCKS 외 링크 타입(relates/duplicates/clones) 라인 — 범위 밖(SDD §13.3.2 "blocks 관계만").
- parent-child 계층 라인 — 간트 Epic 그룹(FR-TL-01/FR-EP-01)이 이미 표현, 의존 라인 아님.

## 새 용어

**없음.** glossary에 "타임라인 아이템(TimelineItem)"·"링크(Link — blocks 포함)" 이미 정의됨.
"의존성 라인"은 기존 BLOCKS 링크의 시각화 개념(UI)이지 신규 도메인 엔티티가 아니다.

## 사용자 시나리오 (Given-When-Then)

### S1. 타임라인 의존성 조회 (happy path)
- **Given** 프로젝트 ATLAS에 BROWSE 권한이 있는 사용자
- **And** ATLAS-1(blocker)이 ATLAS-2(blocked)를 차단하는 BLOCKS 링크가 있고, 두 이슈 모두 날짜가 있어 타임라인에 보임
- **When** `GET /api/v1/timeline/deps?project=ATLAS` 호출
- **Then** 200 + `deps: [{ blockerKey: "ATLAS-1", blockedKey: "ATLAS-2" }]` 반환.

### S2. BLOCKS 외 링크 타입 제외
- **Given** ATLAS-1 relates ATLAS-3, ATLAS-1 duplicates ATLAS-4 (두 링크 모두 BLOCKS 아님)
- **When** 의존성 조회
- **Then** relates/duplicates/clones 링크는 결과에 포함되지 않는다(BLOCKS만).

### S3. 한쪽이 타임라인에 없으면(날짜 0개) 엣지 제외
- **Given** ATLAS-1(날짜 있음) blocks ATLAS-9(start/due 둘 다 NULL → 타임라인 미표시)
- **When** 의존성 조회
- **Then** 해당 엣지는 제외된다(연결할 막대가 없는 댕글링 라인 방지).

### S4. 한쪽이 비가시면 엣지 제외 (누출 차단)
- **Given** ATLAS-1(가시) blocks ATLAS-7(viewer가 볼 수 없는 보안 등급, 날짜 있음)
- **When** 의존성 조회
- **Then** 해당 엣지는 SQL 수준에서 제외된다. 비가시 이슈 키(ATLAS-7)는 응답에 절대 노출되지 않는다.

### S5. cross-project 엣지 제외
- **Given** ATLAS-1 blocks BETA-2 (다른 프로젝트 이슈)
- **When** `GET /api/v1/timeline/deps?project=ATLAS` 호출
- **Then** 해당 엣지는 제외된다(타임라인은 단일 프로젝트, BETA-2 노드가 ATLAS 타임라인에 없음).

### S6. 양방향 차단(상호 blocks)은 두 엣지로 반환
- **Given** ATLAS-1 blocks ATLAS-2 **and** ATLAS-2 blocks ATLAS-1 (서로 다른 두 링크 row)
- **When** 의존성 조회
- **Then** `[{blocker:1,blocked:2},{blocker:2,blocked:1}]` 두 엣지 모두 반환(중복 제거 대상 아님, 방향이 다름).

### S7. 권한 없는 접근 거부
- **Given** ATLAS에 BROWSE 권한이 없는 인증 사용자
- **When** 의존성 조회
- **Then** 403 Forbidden.

### S8. 미인증 접근 거부
- **Given** 미인증/익명 요청
- **When** 의존성 조회
- **Then** 401 Unauthorized (리소스 조회 이전 차단 — 존재 probe 방지).

### S9. project 파라미터 누락
- **When** `GET /api/v1/timeline/deps` (project 없음)
- **Then** 400 Bad Request.

### S10. 의존 라인 클릭 강조 (프론트)
- **Given** 간트에 의존 라인이 렌더된 상태
- **When** 사용자가 의존 라인(또는 막대)을 클릭
- **Then** 관련 라인이 강조되고, 재클릭/외부 클릭 시 강조 해제.

### S11. 결과 상한 초과 (truncated)
- **Given** 가시·동일프로젝트·양끝 타임라인 BLOCKS 엣지가 DEPS_FETCH_LIMIT(1000)을 초과
- **When** 의존성 조회
- **Then** 최대 1000건 + `truncated: true`.

## 기능 요구사항 (FR)

- **FR1**. `GET /api/v1/timeline/deps?project={projectKey}`는 프로젝트의 의존 엣지 목록을 반환한다.
- **FR2**. 엣지 = `link_type='blocks'`인 `issue_links` row 중, source·target **두 이슈가 모두**
  (a) 동일 `projectKey`, (b) `deleted_at IS NULL`, (c) `start_date` 또는 `due_date` 중 1개+ NOT NULL(타임라인 아이템),
  (d) viewer 가시 보안 등급 — 네 조건을 충족하는 것.
- **FR3**. 엣지 형식 = `{ blockerKey, blockedKey }`. `blockerKey`=source(차단하는 쪽), `blockedKey`=target(차단당하는 쪽).
- **FR4**. BLOCKS 외 링크 타입(relates/duplicates/clones)·parent-child는 제외.
- **FR5**. 양끝 중 한쪽이라도 위 4조건 미충족이면 엣지 전체 제외(비가시 이슈 키 누출 차단·댕글링 라인 방지).
- **FR6**. 정렬 — `blockerKey ASC → blockedKey ASC`(결정적).
- **FR7**. BROWSE 권한 없으면 403, 미인증이면 401, project 누락이면 400. 타임라인 엔드포인트와 동일 게이트.
- **FR8**. 결과는 DEPS_FETCH_LIMIT(1000) 상한. 초과 시 `truncated: true`.
- **FR9**. cross-BC 통신은 shared-kernel 포트(`TimelineLookupPort`)만 사용. agile-planning은 issue-tracking 직접 의존 금지(BC 격리).
- **FR10 (프론트)**. 기존 자체 SVG 간트 위에 의존 라인 SVG 오버레이를 렌더. 각 엣지를 blocker 막대 → blocked 막대 화살표로 연결. 클릭 강조.

## 비기능 요구사항 (NFR)

- **NFR1 (성능)**. ≤500 타임라인 아이템 기준 deps 조회 p95 < 1s. cross-BC 호출 1회 + 권한 판정 1회, N+1 없음. 단일 SQL.
- **NFR2 (보안)**. 보안 등급 필터는 adapter가 SQL 수준에서 source·target 양쪽에 적용. agile-planning은 필터 여부를 알지 못한다. 비가시 이슈 키 0 노출.
- **NFR3 (격리)**. ArchUnit BC 격리 룰 유지(agile-planning → issue-tracking 직접 import 0).
- **NFR4 (프론트 성능)**. 오버레이 렌더가 간트 스크롤/리렌더를 눈에 띄게 저하시키지 않는다. 좌표 계산은 순수함수(jsdom 안전, FR-TL-01 `timeline-layout.ts` 패턴 재사용).

## API 인터페이스 (REST)

```
GET /api/v1/timeline/deps?project={projectKey}
권한: BROWSE (해당 프로젝트)
```

성공 200.
```json
{
  "data": {
    "deps": [
      { "blockerKey": "ATLAS-1", "blockedKey": "ATLAS-2" },
      { "blockerKey": "ATLAS-3", "blockedKey": "ATLAS-5" }
    ],
    "truncated": false
  }
}
```

에러.
- 400 — `project` 쿼리 파라미터 누락.
- 401 — 미인증/익명.
- 403 — BROWSE 권한 미충족.

## 데이터 모델 변경

**신규 테이블 0.** FR-LK-01의 `issue_links`(V021: `source_id`, `target_id`, `link_type`, FK→issues, source/target 인덱스) 활용.
FR-PL-01의 `issues.start_date/due_date`(V025)로 타임라인 아이템 판정.

**인덱스.** 프로젝트 단위 BLOCKS 엣지 조회 SQL을 plan 단계에서 production 렌더 SQL로 `EXPLAIN` 검증한다
(memory `jooq-likeignorecase-expression-trgm-index`: EXPLAIN은 손수 SQL 아닌 실제 렌더 SQL로).
기존 `idx_issue_links_source_id/target_id` + issues 프로젝트 인덱스로 충분하면 마이그레이션 0,
부족하면 `link_type='blocks'` 부분 인덱스(잠재 V033) 추가 — db-engineer 담당. **기본 가정: 마이그레이션 0.**

## cross-BC 설계 (핵심 결정 — ADR 본문 참조)

### D1 — `TimelineLookupPort` 확장 (FR-TL-01 패턴 재사용)
- shared-kernel `TimelineLookupPort`에 `listBlocksDepsByProject(projectKey, viewerUserId): TimelineDepsPage` **default 빈 구현** 추가
  (인터페이스 확장은 default 메서드로 인라인 fake 보호 — memory `interface-extension-default-method`, default=fail-safe 빈 페이지).
- 신규 VO — `TimelineDepEdge(blockerKey, blockedKey)` · `TimelineDepsPage(edges, truncated)`.
- issue-tracking `TimelineLookupAdapter`가 실 구현.

### D2 — visibility 보안 경로 재사용 (양끝 적용)
- adapter는 `IssueSecurityDirectory.accessibleLevels(viewer, project)` 1회 조회 후, 단일 SQL에서 source·target **양쪽** 이슈에
  보안등급 IN access 술어를 푸시다운. 타임라인 adapter와 동일 2단 게이트 재사용(새 보안 판정 경로 신설 금지, FR-NT-03 BLOCKER 정신).
- WHERE 골자(개념). `il.link_type='blocks' AND s.project=? AND t.project=? AND s.deleted_at IS NULL AND t.deleted_at IS NULL
  AND (s.start_date IS NOT NULL OR s.due_date IS NOT NULL) AND (t.start_date IS NOT NULL OR t.due_date IS NOT NULL)
  AND <s 보안등급 IN access> AND <t 보안등급 IN access>`. LIMIT 1001로 truncated 판정.

### D3 — agile-planning이 엔드포인트 소유
- `TimelineController`에 `GET /api/v1/timeline/deps`, `TimelineApplicationService.getDeps(actorId, projectKey)`가 BROWSE 게이트(403) 후 포트 호출.
- FR-LK-02 `/issues/{key}/graph`(issue-tracking, 단일중심·전체타입·mermaid)는 재사용 안 함(ADR 폐기 대안 B/C/D).

## 엣지 케이스

- **EC1**. BLOCKS 외 타입 → 제외 (S2).
- **EC2**. 한쪽 날짜 0개(타임라인 미표시) → 엣지 제외 (S3).
- **EC3**. 한쪽 비가시 → 엣지 제외, 키 미노출 (S4, NFR2).
- **EC4**. cross-project 엣지 → 제외 (S5).
- **EC5**. 상호 blocks(A↔B 두 row) → 두 엣지 모두 반환 (S6).
- **EC6**. 자기 자신 blocks → DB `chk_issue_links_no_self` CHECK로 애초에 존재 불가(데이터 무결성). 방어적으로 source≠target도 자명 충족.
- **EC7**. soft-deleted 이슈가 끝점 → 제외 (FR2-b).
- **EC8**. 엣지 0건 → `deps: [], truncated: false` (빈 200).
- **EC9**. 존재하지 않는 projectKey → BROWSE 판정에서 권한 없음 → 403(존재 여부 미노출, 타임라인과 동일).
- **EC10**. 정확히 1000건 → truncated false. 1001건째 존재 → 1000건 + truncated true (S11).
- **EC11 (프론트)**. deps 엣지의 blocker/blocked 키가 현재 렌더된 간트 막대에 모두 매칭될 때만 라인 렌더(백엔드가 양끝 타임라인 보장하므로 정상 경로에선 항상 매칭, 방어적 처리).

## 제약 조건

- BC 격리 — agile-planning은 shared-kernel 포트만 의존. issue-tracking 직접 import 금지(ArchUnit).
- 읽기 전용 — `@Transactional(readOnly = true)`. 부수 효과 0.
- actor 추출 → 권한 판정 → 조회 순서(미인증 존재 probe 차단, memory `auth-extraction-before-resource-lookup`).
- 응답에 비가시 이슈 키·cross-project 키 0 노출(NFR2).

## 측정 가능한 완료 기준

- [ ] `GET /api/v1/timeline/deps?project=ATLAS` 200 + BLOCKS 엣지 목록 (S1)
- [ ] BLOCKS 외 타입 / 한쪽 날짜0 / 한쪽 비가시 / cross-project 제외 (S2~S5, EC1~EC4)
- [ ] 상호 blocks 두 엣지 반환 (S6)
- [ ] 401/403/400 분기 (S7~S9)
- [ ] truncated 동작 (S11/EC10)
- [ ] adapter SQL을 production 렌더 SQL로 EXPLAIN 검증, 인덱스 사용 확인 (마이그레이션 0 또는 V033 인덱스)
- [ ] ArchUnit BC 격리 통과 (agile-planning → issue-tracking import 0)
- [ ] 프론트 — 의존 라인 SVG 오버레이 렌더 + 클릭 강조 (S10), 좌표 순수함수 단위 + E2E 실렌더
- [ ] 단위(서비스·좌표 순수함수) + 통합(adapter SQL + 컨트롤러 HTTP) + E2E 그린

## Brainstorming Check

✅ 통과 (자체 sanity 점검 — 완료 FR-TL-01/FR-LK-01 패턴 파생, office-hours 부적합 learning `bts-spec-office-hours-mismatch` 적용).
점검 항목 — (1) **누출 경로**: 양끝 가시성 SQL 푸시다운으로 비가시 이슈 키 노출 0 + cross-project 제외(S4/S5, FR-NT-03 BLOCKER 정신) — 가장 큰 리스크, D2에서 차단.
(2) **댕글링 라인**: 양끝 타임라인 아이템(날짜 1개+) 조건으로 연결 막대 없는 엣지 제외(S3/EC2).
(3) **방향·중복**: 상호 blocks를 별개 엣지로(S6), self-block은 DB CHECK로 불가(EC6), 정렬 결정적(FR6).
(4) **truncated 상한**: 타임라인과 일관(EC10).
(5) **BC 소유·재사용 경계**: FR-LK-02 그래프 재사용 폐기 근거 ADR 명시.
(6) **PR 분할**: 게이트 1 Maxi 결정 항목으로 명시(풀스택 1 PR 권장 vs 백/프론트 2 PR).
