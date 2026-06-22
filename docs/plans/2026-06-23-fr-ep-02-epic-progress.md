# FR-EP-02 — Epic 진행률 자동 집계

> slug: fr-ep-02-epic-progress
> type: feature
> agent: backend-engineer
> 생성: 2026-06-23

## Brief

사용자 원문: "fr-ep-02 진행하자"

FR-EP-02 — Epic 진행률 자동 집계 (agile-planning BC §7.2).
- 선행: FR-EP-01 (Epic 이슈 타입 + 자식 연결, PR #175 완료) — `issues.epic_id` 자기참조 FK 기반
- 핵심: 에픽에 연결된 자식 이슈들의 상태(WorkflowState) 비율을 집계해 진행률 자동 계산
- 엔드포인트: GET /api/v1/epics/{key}/progress
- 데이터 모델: 신규 테이블 없이 활용 (epic_id 기반 집계 쿼리)
- 프론트: 진행률 막대 UI

classify 보정: project-workflow/api → agile-planning/feature (수동)

## 도메인 정리

- **BC: issue-tracking** (명세 product/agile-planning.md §7.2 분류이나 실제 코드 BC는 issue-tracking. FR-EP-01 ADR 2026-06-22 확정 + 전체 코드가 `com.bts.issue.epic` 패키지). FR-EP-02도 동일 패키지.
- **영향 코드 (모두 issue-tracking 모듈)**.
  - `epic/web/IssueEpicController.kt` — GET `/api/v1/epics/{key}/progress` 메서드 추가 (현재 `/issues/{key}/epic-children` 3종 보유)
  - `epic/application/IssueEpicService.kt` — 진행률 집계 메서드 추가
  - `epic/web/dto/EpicProgressResponse.kt` (신규 DTO)
  - `epic/web/EpicChildExceptionHandler.kt` — `assignableTypes=[IssueEpicController]`라 새 엔드포인트 자동 커버 (입력예외/404/403 재사용)
- **재사용 자산**.
  - `IssueRepository.findEpicChildren(epicId, actor, access, projectKey)` (IssueRepository.kt:1495) — epic_id FK 필터 + buildActiveSecureWhere(deleted_at·accessibleLevels·동일프로젝트 푸시다운). 진행률 집계의 자식 모수.
  - 권한 게이트 패턴 — BROWSE(Project scope) + accessibleLevels visibility 필터 (listChildren 동형, IssueEpicService.kt:194)
- **cross-BC 경계 (핵심)**. 자식 이슈는 `Issue.currentStateKey`(String)만 보유. category/isDone 없음. "완료" 판정은 shared-kernel SPI `WorkflowStateCatalog.listStates(projectKey, issueTypeKey)` → `WorkflowStateView.isDone`(=category==DONE). 이미 board adapter가 사용 중 → issue-tracking → shared-kernel 의존 정상.
- **새 용어 후보**. "Epic 진행률 (Epic Progress)" — glossary 추가 검토 (Maxi 승인 대기).
- **기존 결정 충돌**. 없음. ADR 2026-06-22 §영향이 `GET /api/v1/epics/{key}/progress`를 "epic_id 직속 자식 기준"으로 이미 예약.
- **관련 ADR**. [docs/decisions/2026-06-22-fr-ep-01-epic-child-link.md](../decisions/2026-06-22-fr-ep-01-epic-child-link.md) (기존, FR-EP-02 미리 커버). 진행률 정의 방식 확정 시 작은 ADR 추가 검토.
- **확정 결정 (Maxi 2026-06-23)**.
  1. **진행률 정의** = done/total 백분율 + 3카테고리(TODO/IN_PROGRESS/DONE) 카운트 분해. 응답 `{total, done, donePercentage, byCategory:{TODO, IN_PROGRESS, DONE}}`. Jira 에픽 진행률 바 parity, 막대 3색 구간.
  2. **상태 판정** = 자식 **타입별** `listStates(projectKey, issueTypeKey)` 정확 판정. 타입별 1회 캐싱(타입 2~3종 → 조회 2~3회, N+1 아님). currentStateKey → 해당 타입 WorkflowStateView.category 매핑.
  3. **빈 에픽(자식 0)** = total=0, donePercentage=0, byCategory 전부 0 (에러 아님).
  4. **함정** — listStates N+1 회피(타입별 캐싱 맵), 미할당 워크플로우/매핑 안 되는 상태키 → 비-DONE(category 미상)으로 처리(spec에서 분류 규칙 확정).

## 스펙

전체 스펙. [docs/specs/2026-06-23-fr-ep-02-epic-progress.md](../specs/2026-06-23-fr-ep-02-epic-progress.md)

핵심 시나리오 3줄 요약.
- `GET /api/v1/epics/{key}/progress` → 직속 자식(epic_id)들을 카테고리(TODO/IN_PROGRESS/DONE)로 집계해 `{total, done, donePercentage, byCategory}` 반환
- 카테고리 판정은 자식 **타입별** `WorkflowStateCatalog.listStates(projectKey, typeKey)` 기준 (타입별 1회 캐싱, N+1 차단)
- BROWSE(Project) 게이트 + accessibleLevels 푸시다운 → **보이는 자식만** 모수(누출 0). 빈 에픽 0%, 매핑 실패 상태키는 TODO 분류

## Brainstorming Check

✅ 통과 (직접 self-review, 1회). visibility 모수 기준·손자 포함 여부·매핑 실패 처리·반올림 규칙을 스펙에 명문화. Maxi 결정 필요 gap 없음(진행률 정의는 도메인 단계 확정).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
