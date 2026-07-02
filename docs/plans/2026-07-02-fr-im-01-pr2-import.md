# FR-IM-01 PR2 — Import 컴포넌트/버전 자동생성 + 소스 상태 전이

> slug: fr-im-01-pr2-import
> type: feature
> agent: backend-engineer
> primary_bc: search-export-import (issue-tracking로 cross-BC 쓰기 확장)
> 생성: 2026-07-02

## Brief

FR-IM-01 (CSV/JSON Import, Jira 마이그레이션) 에픽의 **PR2**.
에픽 구조 (Maxi 결정, SDD 10.6.3 풀 마이그레이션):
PR1 코어(완료, #218) → **PR2 컴포넌트/버전 자동생성 + 소스 상태 전이** → PR3 댓글/Worklog → PR4 첨부(zip)/이력.

PR2 범위 (초안, spec/domain에서 확정):
- Jira 데이터의 **컴포넌트** → 대상 프로젝트에 자동 생성 (없으면 생성, 있으면 재사용) + 이슈 연결
- Jira 데이터의 **버전** (affects/fix version) → 자동 생성 + 이슈 affects/fix 연결
- Jira **소스 이슈 상태** → BTS 워크플로우 상태로 전이 (생성 후 목표 상태로 이동)

PR1 유산:
- `com.bts.search.imports` 모듈, `ImportJobWorker`, CSV/JSON 스트리밍 파서
- `IssueImportPort` (shared-kernel, issue-tracking `IssueImportAdapter` 구현) — BTS 최초 cross-BC 쓰기 포트, default fail-closed
- 행별 best-effort = create+update 1 @Transactional 원자성

## 도메인 정리

- **BC**: search-export-import (import 오케스트레이션) + issue-tracking (cross-BC 쓰기 어댑터 확장). 신규 BC 없음.
- **확장 지점 3곳** (모두 기존 파일 확장, 신규 서비스 최소):
  1. `ParsedImportRow` (search) + `ImportRowParser` — 버전(affects/fix)·소스 상태 파싱 추가
  2. `IssueImportCommand` (shared-kernel) — 필드 추가 (주석이 "포트 시그니처 불변, 필드 추가" 명시 허용)
  3. `IssueImportAdapter` (issue-tracking) — 컴포넌트 auto-create, 버전 auto-create+링크, 상태 전이
- **영향 엔티티**: Component, Version, `issue_affects_versions`/`issue_fix_versions`(V017 기존), WorkflowState. **신규 마이그레이션 불필요**(모든 테이블 기존).
- **새 용어**: 없음 (Component/Version/전이 모두 glossary 기존 용어).

### 핵심 메커니즘 (Explore 조사, 파일:라인)
- 컴포넌트 생성: `ComponentApplicationService.create(actorId, projectIdOrKey, name, desc?, leadUserId?)` — `ComponentPermission.CREATE`→`MANAGE_COMPONENTS`(PROJECT_ADMIN). name 프로젝트 내 UNIQUE(부분, 활성). 중복→`DuplicateComponentNameException`(23505).
- 버전 생성: `VersionApplicationService.create(...)` — `VersionPermission.CREATE`→`MANAGE_VERSIONS`(PROJECT_ADMIN). 기본 status=UNRELEASED. name UNIQUE.
- 이슈↔버전: `IssueApplicationService.changeAffectsVersions`/`changeFixVersions` — `EDIT_ISSUE`(UPDATE)·IssueScope.Issue·replace-all. 분리 테이블 2개.
- 상태 전이 직접 set 선례: `IssueMoveService`(FR-MV-01)가 `workflowPort.plan()` 우회, `IssueRepository.moveIssue(... targetStateKey)`로 `current_state_key` 직접 set. 유효성=대상 워크플로우 상태집합 포함 여부(FSM edge 아님).
- 상태 name→key: `WorkflowStateCatalog.listStates(projectKey, issueTypeKey)` → `WorkflowStateView(key, name, isDone, category, displayOrder)`.
- 초기 상태: `createIssue`가 항상 워크플로우 시작 상태로 강제(status 파라미터 없음). 소스 상태 반영은 생성 후 별도 전이 필요.

### 기존 결정과의 관계 / 결정 갈림길 (→ 스펙에서 확정, 게이트1 제시)
- **D-A. 컴포넌트/버전 auto-create 권한 모델**. importer는 CREATE_ISSUE 보유. 자동생성은 MANAGE_COMPONENTS/MANAGE_VERSIONS(PROJECT_ADMIN) 필요. PR1 불변("search BC는 권한 우회 불가")을 지키려면 `ComponentApplicationService.create(actor=requester)` 위임 → 권한 없으면 예외. 폴백: 권한 없거나 생성 실패 시 **경고+이슈는 생성**(best-effort, PR1 컴포넌트 스킵 동형) vs **행 실패(FORBIDDEN)**. (추천: best-effort 경고)
- **D-B. 상태 전이 메커니즘**. FSM `transitionIssue`(유효 edge만, Jira 상태 대부분 도달불가) vs **직접 set(IssueMoveService 선례)**. (추천: 직접 set + 대상 상태집합 포함 검증 + IssuePermission.TRANSITION 게이트. 미매칭/미도달 상태 name은 경고+시작 상태 유지)
- **D-C. 트랜잭션 원자성**. 행 1건 = create+컴포넌트/버전 auto-create+링크+전이 한 tx. auto-create 실패의 best-effort 경고화는 tx poison(23505) 회피 위해 사전 find(findByProject+name) 후 없을 때만 insert 필요. 동시 행 race는 실 UNIQUE가 최종 방어.
- **관련 ADR**: PR1 `2026-07-02-fr-im-01-csv-json-import.md`(D8 에픽 분할). PR2 결정은 신규 ADR 또는 PR1 ADR 확장(스펙에서 결정).

## 스펙

전체 스펙. [docs/specs/2026-07-02-fr-im-01-pr2-import.md](../specs/2026-07-02-fr-im-01-pr2-import.md)

핵심 요약 (R1~R9).
- 파서/커맨드 확장(status·fix·affects) → 어댑터가 컴포넌트/버전 **find-or-create**(actor=requester 위임, admin 권한 부재=경고) + affects/fix 링크 + 소스 상태 **직접 set**(FSM 우회, name 매칭, TRANSITION).
- best-effort 경고를 **결과 로그 CSV에 severity로 노출**(G1, 스키마 무변경) — PR1이 경고를 폐기하던 구조 보정.
- 권한 3축(생성=admin 경고 / 편집=basic 행실패 / 전이=경고), OCC 버전 6단계 스레딩, dry-run 실경로 미러 확장.
- API/데이터 모델/마이그레이션 변경 0.

## Brainstorming Check

✅ 통과 (1회 iteration). G1(경고 폐기)·G2(권한 3축)·G3/G5(순서·OCC)·G4(dry-run 확장) 발견 후 스펙 보강. office-hours 스킵(에픽 연속).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
