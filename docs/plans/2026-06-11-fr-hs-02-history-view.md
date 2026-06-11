# FR-HS-02 — 이슈 변경 이력 조회 UI

> slug: fr-hs-02-history-view
> type: ui
> agent: frontend-engineer
> primary BC: issue-tracking
> 생성: 2026-06-11

## Brief

FR-HS-02 이슈 변경 이력 조회 UI 구현. FR-HS-01(백엔드 이슈 변경 이력 기록 #115 + cross-BC 라벨 박제 보강 #120)의 후속 PR2.
FR-HS-01에서 백엔드가 기록한 이슈 변경 이력을 사용자가 이슈 상세 화면에서 조회할 수 있는 프론트엔드 UI를 구현한다.

- classify: type=ui, agent=frontend-engineer, primary_bc=issue-tracking
- FR-NT-01(worktree/PR #118), FR-MF-02(worktree) 등 병행 작업은 유지하고 본 작업은 독립 worktree에서 진행.

## 도메인 정리

- **BC**: issue-tracking (단일 BC, cross-BC 직접 호출 없음)
- **영향 엔티티**: `IssueChangeGroup`, `IssueChangeItem` (FR-HS-01 도입, **읽기만** — 변경 없음)
  - `IssueChangeGroup { issueId, issueKey, actorId?, items[], createdAt? }`
  - `IssueChangeItem { field, fromValue?, toValue?, fromLabel?, toLabel? }` (라벨은 #120에서 assignee/securityLevel만 박제, status·기타는 null)
- **새 용어**: 없음 — ADR `2026-06-11-issue-change-history-model`에서 change group / change item / 박제 label 모두 정의됨
- **기존 결정 충돌**: 없음. ADR §단계 분할이 "PR2(FR-HS-02 조회 UI) — 박제된 label을 타임라인에 표시"를 명시적으로 예견
- **관련 ADR**: [docs/adr/2026-06-11-issue-change-history-model.md](../adr/2026-06-11-issue-change-history-model.md) (특히 §단계 분할 PR2, §보강 cross-BC 라벨 박제)
- grill-with-docs: 도메인이 FR-HS-01 ADR로 완전 확립 + 신규 용어·충돌 0 → 대화형 grilling 생략, 직접 기록 (learning `bts-review-plan-autoplan-overkill` 패턴)

### ⚠️ 범위 발견 — backend read 엔드포인트 부재 (중요)

FR-HS-01은 `IssueChangeHistoryRepository.findByIssue(issueId): List<IssueChangeGroup>` **조회 메서드까지만** 구현했고, 이력을 외부로 노출하는 **REST 조회 엔드포인트는 없다**(web 컨트롤러 목록·GetMapping grep 모두 빈 결과).

→ FR-HS-02 실제 범위 = **backend read 엔드포인트 + DTO (backend-engineer)** + **프론트 타임라인 UI (frontend-engineer)** + **E2E (qa)** 혼합. classify의 `type=ui`는 부분만 맞음.

- **BC 격리**: 이력 조회 엔드포인트는 **같은 issue-tracking BC**의 view-layer 확장 → 프론트 PR에 same-BC backend 추가는 BC 위반 아님 (learning `2026-05-22 same-BC view layer 예외` 패턴). cross-BC 직접 import 없음.
- **권한**: 단건 조회 `service.findByKey(actor, key)`가 view 권한(보안등급 포함) 강제. 새 changelog 엔드포인트도 **동일 가드 재사용** — 이슈를 못 보면 이력도 404 (데이터 누출 방지). learning `auth-extraction-before-resource-lookup` 정합.
- **엔드포인트 위치**: `IssueController` (`@RequestMapping("/api/v1/issues")`, `adapter/inbound/rest/`) — `/{key}/transitions`, `/{key}/pdf` 패턴 옆에 `/{key}/changelog` 추가 후보.
- **프론트 위치**: `apps/web/src/routes/issues.$key.tsx` (`IssueDetailPage`) — 상세 페이지에 활동/이력 섹션 형태로 부착 후보.

### spec에서 확정할 결정 (→ /bts-spec)

1. 엔드포인트 경로/이름 — `/{key}/changelog` vs `/history` vs `/activity`
2. actor 표시명 해석 — `group.actorId`(nullable)를 display_name으로 backend resolve (UserLookupPort, #120 선례) vs ID만 노출
3. `field` 키 → 사람이 읽는 필드명 매핑 위치 — backend DTO vs frontend 라벨 맵
4. UI 형태 — 상세 페이지 탭 vs 인라인 섹션, 그룹 묶음 표시 방식
5. 정렬/페이징 — 최신순 + 페이징 필요 여부
6. 생명주기 이벤트(created/soft_deleted) 표시 방식

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
