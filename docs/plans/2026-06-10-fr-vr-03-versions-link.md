# FR-VR-03 — Affects/Fix Version 연결

> slug: fr-vr-03-versions-link
> type: feature (backend + frontend + e2e 풀스택)
> agent: backend-engineer (+ db-engineer, frontend-engineer, qa-engineer)
> primary_bc: issue-tracking
> 생성: 2026-06-10

## Brief

이슈를 버전에 다대다로 연결한다 (Jira 정석).
- Affects Version (영향 버전) — 이 이슈/버그가 발견된·영향받는 버전
- Fix Version (수정 버전) — 이 이슈가 수정될·수정된 목표 버전
- 둘 다 다대다 — 조인 테이블 2개: issue_affects_versions, issue_fix_versions

product 정본: docs/plan/product/issue-tracking.md §3.2.3
선행: FR-VR-01 (버전 생성, PR #67), FR-VR-02 (버전 상태, PR #105)
Plan slug(정본): issue/versions-link

### D단계 (product 정본)
- [ ] D1. 도메인 — Affects vs Fix 의미 (backend-engineer)
- [ ] D2. 명세 (backend-engineer)
- [ ] D3. 데이터 모델 — issue_affects_versions, issue_fix_versions (db-engineer)
- [ ] D4. 백엔드 (backend-engineer)
- [ ] D5. 백엔드 테스트 (backend-engineer)
- [ ] D6. 프론트 UI — 버전 셀렉터 2종 (designer → frontend-engineer)
- [ ] D7. E2E (qa-engineer)

## 도메인 정리 (/bts-domain)

- **BC**: issue-tracking (단일 BC, 격리 위반 없음 — versions·issues 모두 같은 모듈)
- **새 용어**: 없음. glossary.md 이미 정의 — "버전 (Version) — 릴리스 단위. fix/affects 관계로 이슈에 연결"
- **영향 엔티티**:
  - `Issue` (애그리거트) — `affectsVersionIds: List<UUID>`, `fixVersionIds: List<UUID>` 신규 필드 (기존 `componentIds` 패턴 동형)
  - `Version` (읽기 참조만 — 검증 시 프로젝트·존재 확인)
  - 신규 조인 테이블 2개: `issue_affects_versions`, `issue_fix_versions`
- **선례 (정확한 동형 패턴)**: 이슈↔컴포넌트 연결 (FR-CM-02/03)
  - 조인 테이블: `V012__issue_components.sql` (issue_id, component_id PK, ON DELETE CASCADE, FK 인덱스는 후미 컬럼만)
  - 도메인: `Issue.componentIds` + `assignComponents()`/`clearComponents()` + `create(componentIds=)`
  - API: `PATCH /api/v1/issues/{key}/components` + `ChangeComponentsRequest{componentIds, expectedVersion}` (전체 교체, OCC 낙관적 잠금)
  - 검증: `IssueApplicationService.validateComponents` — 프로젝트 불일치/비활성 시 422
  - 응답: `IssueResponse.componentIds` (단건 경로에서만 채움)
  - repo: `IssueRepository.insertComponents` (배치 INSERT)
- **기존 결정 충돌**: 없음
- **관련 ADR**:
  - `docs/adr/2026-06-03-version-model-and-permission-deferral.md` (Version 모델 + 권한 이연 — VersionPermissionResolver 포트)
  - `docs/adr/2026-06-10-version-status-and-transitions.md` (버전 상태 전이)
  - 신규 ADR 후보: affects/fix 연결 정책 (ARCHIVED 허용 + 권한 재사용) — bts-spec/plan에서 판단

### Maxi 확정 결정 (2026-06-10)
1. **생성 시점 미지원** — PATCH 교체만 (`/affects-versions`, `/fix-versions`). 생성 시 지정은 후속 이연. FR-VR-02와 동일 결의 범위.
2. **ARCHIVED 정책** — API는 삭제 안 된 모든 버전(UNRELEASED/RELEASED/ARCHIVED) 연결 허용. 프론트 셀렉터에서만 ARCHIVED 기본 숨김 (Jira 정석). 이미 연결된 ARCHIVED 링크는 보존.

### 기본값 (선례 따름, 미질의)
- **두 엔드포인트 분리** — affects/fix는 독립 관계이므로 `/affects-versions`, `/fix-versions` 별도 PATCH (컴포넌트 1관계 1엔드포인트 패턴 확장).
- **전체 교체 + OCC** — `{versionIds, expectedVersion}`, changeComponents와 동일하게 expectedVersion 필수 + version bump.
- **타 프로젝트 버전 422** — 이슈 프로젝트 ≠ 버전 프로젝트면 422 (컴포넌트 validateComponents 동형).
- **읽기 측 노출** — `IssueResponse`에 `affectsVersionIds`/`fixVersionIds` 단건 경로 채움 (D6 프론트 표시용).

## 스펙 (/bts-spec)

전체 스펙. [docs/specs/2026-06-10-fr-vr-03-versions-link.md](../specs/2026-06-10-fr-vr-03-versions-link.md)

핵심 시나리오 요약.
- `PATCH /api/v1/issues/{key}/affects-versions`, `.../fix-versions` 2종 — `{versionIds, expectedVersion}` 전체 교체 + OCC.
- 검증: 이슈 프로젝트 소속 + 미삭제 버전만 (422 `ISSUE_VERSION_NOT_FOUND`). ARCHIVED 허용.
- 단건 조회에 `affectsVersionIds`/`fixVersionIds` 노출. 권한은 `IssuePermission.UPDATE` 재사용.
- 신규 V017 (issue_affects_versions, issue_fix_versions) + init_codegen 미러. Issue 도메인 2필드 추가 (componentIds 동형).
- 프론트 셀렉터 2종 (ARCHIVED 기본 숨김) + E2E.

## Brainstorming Check (/bts-spec)

✅ 통과 (1회, gap 없음). 히스토리/PDF/클론 모두 컴포넌트도 미처리 → 동형 불필요. Maxi 결정 2건 도메인 단계 확정.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
