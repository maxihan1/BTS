# FR-PJ-04 프로젝트 아카이브 구현 (PR-4)

> slug: fr-pj-pr-4-archive
> type: backend (classify 원본 migration → Maxi 확인 후 정정)
> agent: backend-engineer (리드) + db-engineer(V037) + security-engineer(PROJECT_ADMIN 게이트 검토)
> primary_bc: issue-tracking
> 생성: 2026-07-18

## Brief

**원문**: FR-PJ-04 프로젝트 아카이브 구현 (project-management-crud 스펙 §9.2 6분할 중 PR-4).

**범위**:
- `projects.archived_at` 컬럼 마이그레이션 **V037** (issue-tracking 최신 V036 확인, V037 free)
- `POST /archive` · `POST /unarchive` 엔드포인트 (PROJECT_ADMIN 게이트 = `ComponentPermission.UPDATE` 재사용, PR-3 선례)
- PR-3에서 이관받은 **PJ2-2** (아카이브 프로젝트 목록 기본 제외 + `?archived` 필터)
- PR-3에서 이관받은 **PJ3-2** (아카이브된 프로젝트 설정변경 시 409)
- issue-tracking 프로젝트 스코프 쓰기 **17곳 잠금** (Version 5·Component 4·CustomField 3·IssueTemplate 3·ProjectLead 1·ProjectRequire2fa 1)
- 이슈 쓰기 초크포인트 1곳
- `Clock` 주입 필수 (archived_at 타임스탬프)

**정본 스펙**: `docs/specs/2026-07-17-project-management-crud.md` §9.2
**직전 PR-3 plan**: `docs/plans/2026-07-18-fr-pj-pr-3.md`

**함정 (체크포인트/메모리)**:
- 아카이브 예외 동명충돌 주의 — PR-3에서 `ProjectQueryNotFoundException` 별도명명 선례
- 리포지토리 신설 시 **IssueBcArchTest 검증 포함** (--tests 스코프 좁히다 놓친 회귀)
- cross-BC 빈 부재 시 `CrossBcPortTestConfig` mock 추가
- V번호는 머지 직전 재확인 (동시 브랜치 충돌)
- FR 총수 129 불변 (FR-PJ-01~04 완료마킹은 PR-5 몫)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
