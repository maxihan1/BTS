# FR-PM-05 — 이슈 접근 권한 (Browse, View)

> slug: fr-pm-05-browse-view
> type: auth
> agent: security-engineer
> 생성: 2026-06-05

## Brief

FR-PM-05 — 이슈 접근 권한(Browse/View 분리). 권한 없는 이슈를 조회 결과에서
자동 필터링하고 단건 조회 시 차단. identity-access BC 중심 + 이슈 쿼리 필터
자동 첨부(jOOQ Condition 빌더) + 프론트 권한 없는 이슈 404 처리.

- 선행: §4.2 FR-PM-02 권한 스킴 모델 활용
- 병렬 작업: FR-CM-03(issue-tracking, draft PR #84) 존재 — 공유 자원 충돌 주의
- classify: type=auth, agent=security-engineer, primary_bc=identity-access

plan 문서(docs/plan/product/identity-access.md §4.5) D1~D7:
- D1. 도메인 — BrowsePermission vs ViewPermission 분리 (security-engineer)
- D2. 명세 (security-engineer)
- D3. 데이터 모델 — (FR-PM-02 활용) (db-engineer)
- D4. 백엔드 — 이슈 쿼리에 필터 자동 첨부 (jOOQ Condition 빌더) (security + backend)
- D5. 백엔드 테스트 — 비공개 이슈 조회 차단 (security-engineer)
- D6. 프론트 UI — 권한 없는 이슈 404 처리 (frontend-engineer)
- D7. E2E (qa-engineer)

## 도메인 정리

- BC: identity-access (소유, 판정기) + issue-tracking (포트 호출/스코프)
- 영향 타입: `IssuePermission`(enum, BROWSE 추가), `IdentityAccessIssuePermissionResolver`(toCodeOrNull 매핑), `IssueApplicationService.listIssues/findByKey`, `role_permissions` 시드(신규 마이그레이션)
- 핵심 도메인 결정 (Maxi 확정 2026-06-05):
  - **D1. BROWSE/VIEW 분리** (SDD 12.3 충실) — `IssuePermission.BROWSE` 신규.
    목록(listIssues)=`BROWSE_PROJECT`, 단건(findByKey)=`VIEW_ISSUE`. prod resolver "멤버면 통과" 임시 정책 → 매트릭스 이관.
  - **D2. 미인가 단건 = 404** (존재 숨김, Jira 방식) — 미인증→401, 비멤버/미인가→404, 인가→200.
  - **D3. 시드** — 기본 스킴에 BROWSE_PROJECT + VIEW_ISSUE(역할 2종 모두). `PermissionSchemaMigrationTest` 카운트 갱신 동반.
  - **D4. per-issue 보안 수준(비공개 이슈)은 FR-PM-06으로 분리** — 본 FR은 프로젝트 단위 매트릭스까지.
- 기존 결정 충돌: 없음 (FR-PM-02 resolver KDoc이 VIEW 이관을 FR-PM-05로 예약 → 그 예약 실행)
- 관련 ADR: [docs/decisions/2026-06-05-issue-browse-view-permission.md](../decisions/2026-06-05-issue-browse-view-permission.md) (생성됨)
- 글로서리: "이슈 데이터 접근 권한"에 Browse(목록 가시성)/View(단건 상세) 구분 추가 후보 — Maxi 승인 대기

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
