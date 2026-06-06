# FR-PM-06 PR-B — 이슈 보안 수준 판정 결선

> slug: fr-pm-06-pr-b-security-decision
> type: api
> agent: backend-engineer (+ security-engineer 검토: 권한 판정/resolver)
> primary_bc: issue-tracking (+ identity-access resolver 확장 — cross-BC 포트)
> 생성: 2026-06-06
> 선행: PR-A(#86, 관리 인프라) 머지 완료. 최신 main(FR-CM-04 #89) 기준.

## Brief

FR-PM-06(이슈 보안 수준)의 후속 PR-B. PR-A에서 Jira Cloud 방식 스킴→등급→멤버(5타입) **관리 인프라**까지 머지됐고, 이번 PR-B는 실제 **이슈 차단 판정 결선**을 구현한다.

범위:
- `issues.security_level_id` 컬럼 추가 (issue-tracking 마이그레이션 + init_codegen.sql 미러 필수 — jOOQ 상수 생성, jooq-init-codegen-mirror 교훈).
- 이슈 생성/편집 시 등급 지정 API (SET_ISSUE_SECURITY 가드). 적용 스킴 미소속 등급이면 422.
- cross-BC `IssueSecurityLookup` 포트 (identity-access가 issues의 security_level_id/reporter_id/assignee_id read, ProjectDirectory 패턴).
- `IdentityAccessIssuePermissionResolver` 판정 확장: VIEW 매트릭스 통과 AND 등급 멤버(5타입) 충족, 미통과 시 404 (FR-PM-05 assertViewIssueOrNotFound 일관).
- listIssues 목록 필터 (등급 멤버 아닌 이슈 제외, N+1 회피).
- prod Testcontainers 통합으로 판정 ground-truth (S10~S13, non-prod AlwaysAllow 마스킹 주의).

(선택 후속) D6/D7 — 이슈 생성/편집 등급 선택 UI + E2E.

## 도메인 정리

- **BC**: issue-tracking(주) + identity-access(판정 확장, cross-BC read). 단일 PR-B로 두 BC를 가로지르며, 이는 ADR `2026-06-06-issue-security-level-scheme-model.md` §결과의 "2 PR 분할(PR-A=관리 인프라 / PR-B=결선)"로 이미 확정된 의도. cross-BC 읽기는 `ProjectDirectory` 선례(아래)를 따르므로 BC 격리 위반 아님.
- **도메인 언어**: PR-A에서 확정·머지됨(신규 용어 0). 이슈 보안 스킴(IssueSecurityScheme) → 보안 등급(IssueSecurityLevel) → 등급 멤버(SecurityLevelMember, 5타입). 본 PR은 판정/지정 동작만 추가.
- **영향 엔티티/컬럼**:
  - (issue-tracking) `issues.security_level_id UUID NULL` 신규 — 등급 미지정 = NULL(모든 VIEW 통과자에게 공개). FK 미적용(BC 격리, 등급은 identity-access 소유). 마이그레이션 + `init_codegen.sql` 미러 필수(jooq-init-codegen-mirror 교훈, V006/V012 선례).
  - (identity-access) 신규 테이블 없음 — PR-A 테이블 4종 read.
- **판정 규칙(ADR §결정 4 — 본 PR이 코드로 구현)**:
  - `VIEW_ISSUE` 매트릭스 통과 **AND** (등급 NULL **OR** actor가 등급 멤버 5타입 중 하나 충족) → 통과. 아니면 `false` → 컨트롤러가 404(`assertViewIssueOrNotFound` 일관, FR-PM-05).
  - 멤버 5타입 충족: REPORTER=actor==issue.reporter_id / ASSIGNEE=actor==issue.assignee_id / USER=actor==member_value / GROUP=actor가 그룹(FR-PM-09) 소속 / PROJECT_ROLE=actor의 ProjectMembership.role==member_value.
  - **관리자 우회 없음**(ADR §결정 5).
- **cross-BC 포트 — `IssueSecurityLookup`(신규, identity-access)**: `ProjectDirectory` 동형. 인터페이스 + `Jdbc*` 구현. issue-tracking `issues` 테이블의 `security_level_id`/`reporter_id`/`assignee_id`를 raw SQL `NamedParameterJdbcTemplate` + `@Transactional(readOnly=true)`로만 read(직접 import 금지, ADR D2 deployment invariant). 의존 컬럼 KDoc 명시 + DDL drift 경고.
- **resolver 확장 지점**: `IdentityAccessIssuePermissionResolver.hasPermission` — VIEW/BROWSE 매트릭스 통과 직후 보안등급 게이트 추가. 멤버 충족 판정은 별도 순수 함수/도메인 서비스(`IssueSecurityDecider`류)로 분리. **issue-tracking `AlwaysAllowIssuePermissionResolver`(@Profile("!prod"))가 non-prod에서 마스킹** → ground-truth는 prod Testcontainers 통합만(issue-scope-global-prod-hard-deny / best-effort-loop-permission-exception-nonprod-mask 교훈).
- **이슈 등급 지정(issue-tracking)**: 생성/편집 시 `security_level_id` 설정. `SET_ISSUE_SECURITY` 가드(PR-A 시드 완료, count=13). 지정 등급이 프로젝트 **적용 스킴 소속** 아니면 422.
- **기존 결정 충돌**: 없음. ADR 2026-06-06 정본, 본 PR은 §결과 PR-B 항목 구현. 신규 ADR 불요.
- **관련 ADR**: [docs/decisions/2026-06-06-issue-security-level-scheme-model.md](../decisions/2026-06-06-issue-security-level-scheme-model.md) (PR-A).
- **미해결 설계 갈림길(→ /bts-spec 옵션 제시)**:
  1. **목록 필터 N+1 전략** — listIssues 등급 멤버 아닌 이슈 제외. 후처리 배치 필터 vs 쿼리 술어 푸시다운. cross-BC·성능 stakes.
  2. **등급 지정 "적용 스킴 소속" 422 검증 경로** — issue-tracking cross-BC read 포트 직접 검증 vs identity-access 위임.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
