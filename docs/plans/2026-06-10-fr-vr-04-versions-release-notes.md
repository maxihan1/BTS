# FR-VR-04 — 버전 릴리즈 노트 자동 생성

> slug: fr-vr-04-versions-release-notes
> type: api (풀스택 — 백엔드 GET API + 프론트 미리보기/복사 UI)
> agent: backend-engineer (+ frontend-engineer / qa-engineer)
> 생성: 2026-06-10
> FR: FR-VR-04 (issue-tracking BC, 중요도 중간)
> 선행: FR-VR-01(버전 생성) · FR-VR-03(Affects/Fix Version 연결) — 둘 다 완료

## Brief

특정 버전을 Fix Version으로 연결한 이슈들을 모아 Markdown 형식 릴리즈 노트를 자동 생성한다.

- D1. 도메인 (backend-engineer)
- D2. 명세 — Markdown 템플릿 (backend-engineer)
- D3. 데이터 모델 — (활용, 새 테이블 없음) (db-engineer)
- D4. 백엔드 — `GET /api/v1/versions/{id}/release-notes` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 미리보기 + 복사 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

classify: { type: api, agent: backend-engineer, primary_bc: issue-tracking }
product 정본: docs/plan/product/issue-tracking.md §3.2.4
SDD 참조: §3.2.4

## 도메인 정리

- **BC**: issue-tracking
- **영향 엔티티** (모두 기존, 읽기 전용으로 사용):
  - `Version` (`com.bts.issue.version.domain.Version`) — 이름·상태·releaseDate 등 릴리즈 노트 헤더 정보
  - `Issue` (`com.bts.issue.domain.Issue`) — `fixVersionIds`, `key`, `summary`, `typeId`, `resolutionId`
  - `IssueType` (`com.bts.issue.type`) — `typeId → 타입명` (그룹핑 라벨)
- **새 엔티티/테이블**: **없음** (D3 = 활용). 릴리즈 노트는 영속화하지 않고 요청 시 생성(조회 전용).
- **새 도메인 개념**: **릴리즈 노트(Release Notes)** — 한 버전을 Fix Version으로 가진 활성 이슈들을 모아 만든 Markdown 문서. glossary 추가 후보(게이트 1에서 Maxi 확인).
- **신규 repository 메서드 필요**: 버전 ID → 그 버전을 fix version으로 가진 활성 이슈 역방향 조회 (`IssueRepository`에 추가). 현재는 issue→version 방향(`findFixVersionIdsByIssue`)만 존재.
- **Issue에 워크플로우 상태 없음**: 상태 전이는 project-workflow BC 위임. issue-tracking에서는 `resolutionId`로 해결 여부 표현 → 릴리즈 노트 필터/그룹핑은 타입·resolution 축만 사용(cross-BC 회피).
- **권한**: 기존 `VersionPermissionResolver` READ 패턴 재사용. actorId는 `SYSTEM_ACTOR_UUID` placeholder(FR-PM-03 이연), Security 필터가 401 보장.
- **기존 결정 충돌**: 없음.
- **관련 ADR** (참조, 충돌 없음): docs/adr/2026-06-03-version-model-and-permission-deferral.md, 2026-06-10-version-status-and-transitions.md

### ⚠️ spec 단계로 넘길 핵심 결정 (옵션 논의 필요)

1. **엔드포인트 경로** — VersionController가 `/api/v1/projects/{projectIdOrKey}/versions` 하위이므로, 릴리즈 노트도 `GET /api/v1/projects/{projectIdOrKey}/versions/{id}/release-notes`로 정렬해야 함. product 문서의 `GET /api/v1/versions/{id}/release-notes`는 약식 표기.
2. **응답 형식** — JSON `{ data: { markdown, ... } }` vs `text/markdown` raw.
3. **Markdown 그룹핑 기준** — 이슈 타입별 섹션(Bug/Story/Task…).
4. **포함 이슈 범위** — fix version 연결 활성 이슈 전부 vs resolution 보유분만.
5. **정렬 순서** — 이슈 키 / 생성순 등.

## 스펙

전체 스펙: [docs/specs/2026-06-10-fr-vr-04-versions-release-notes.md](../specs/2026-06-10-fr-vr-04-versions-release-notes.md)

확정된 핵심 결정 5건.
1. 엔드포인트: `GET /api/v1/projects/{projectIdOrKey}/versions/{id}/release-notes` (VersionController 매핑 일관)
2. 응답: JSON `DataResponse<ReleaseNotesResponse>` — `{ projectKey, versionName, versionStatus, releaseDate, issueCount, generatedAt, markdown }` (Maxi 결정)
3. 그룹핑: 이슈 타입별 섹션 (hierarchy_level 순, 그룹 내 키 순)
4. 포함 범위: Fix Version 연결 활성 이슈 **전부** (상태 무관, resolution은 표시만) (Maxi 결정)
5. 데이터: 신규 테이블 없음. IssueRepository에 버전→이슈 역방향 조회 메서드 추가.

3줄 요약.
- 버전의 fix version 연결 활성 이슈를 타입별로 묶어 Markdown 릴리즈 노트를 요청 시 생성(영속 안 함)
- 응답은 메타데이터 + markdown 본문을 JSON으로 래핑, 권한/Clock/에러는 기존 Version 패턴 재사용
- 프론트는 버전 행에 "릴리즈 노트" 액션 → 미리보기 다이얼로그 + 클립보드 복사

## Brainstorming Check

✅ 통과 (1회 자가 점검). gap 1건(헤더 프로젝트 식별 누락) 발견 후 spec에 projectKey 보강. 구현 주의점 4건(resolution 다건 주입 / IssueType 표준 순서 / projectKey ProjectLookup / 클립보드 secure-context) plan에 인계.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
