# FR-VR-01 D6/D7 — 버전 관리 프론트엔드 UI + E2E

> slug: fr-vr-01-version-frontend
> type: ui (classify qa 오판 정정 — D6 프론트 주 + D7 E2E. FR-CM-01 D6/D7도 ui)
> agent: frontend-engineer (+ qa-engineer: D7 E2E)
> primary_bc: issue-tracking
> 생성: 2026-06-03

## Brief

FR-VR-01 D6/D7 — 버전(Version) 관리 프론트엔드 UI + E2E. 백엔드 PR #67 머지됨(버전 CRUD API
`/api/v1/projects/{projectKey}/versions` + `/dates` 서브리소스).

- 범위: D6 프론트 UI + D7 E2E. 백엔드 D1~D5는 PR #67 완료.
- 선례 동형: 컴포넌트 프론트(FR-CM-01 D6/D7, PR #64) — `/projects/$projectKey/settings/components` 패턴.
  - api/versions.ts(+types) ← components.ts 동형
  - components/version/{VersionFormDialog, VersionList, VersionRow} ← component/* 동형
  - hooks/use-versions.ts ← use-components 동형
  - mocks/version-handlers.ts ← component-handlers 동형
  - i18n/version-labels.ts ← component-labels 동형
  - routes/projects.$projectKey.settings.versions.tsx ← settings.components 동형
- 핵심 차이: ComponentLeadSelect(리드 셀렉터) 자리에 **날짜 입력 2개**(startDate/releaseDate, 순서 미강제).
  날짜 수정은 백엔드 `/dates` 전용 서브리소스. 사용자 참조 없음(useUsers 등 불필요).
- 메모리 선반영 후보: frontend-api-convention-per-bc(issues.ts 관례: DataResponse+공유ApiError+body.errorCode 헬퍼, X-XSRF-TOKEN 수동), msw-mutation-stateful-refetch, e2e-msw-serviceworker-block, playwright-getbyrole-exact-strict-mode, ui-pr-defer-e2e-regression-latent(D6+기존E2E 함께), ci-typecheck-tsconfig-app-vs-local.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
