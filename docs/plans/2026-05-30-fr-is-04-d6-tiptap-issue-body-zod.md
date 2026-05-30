# FR-IS-04 D6 프론트 — TipTap issue-body 에디터 + 우선순위/라벨/환경/영향도 셀렉터/칩 + Zod 스키마 동기

> slug: fr-is-04-d6-tiptap-issue-body-zod
> type: ui
> agent: frontend-engineer
> 생성: 2026-05-30
> 분류 정정: classify migration/db-engineer 오판 → Maxi 확정 ui/frontend-engineer

## Brief

FR-IS-04(이슈 본문 Markdown + 우선순위/라벨/환경/영향도)의 프론트엔드 짝(D6). FR 분할 3PR(백엔드 D1~D5 / 프론트 D6 / E2E D7) 중 2/3.

**백엔드는 PR #43(squash be8780e) 머지 완료.** 이번 PR은 그 백엔드 계약을 프론트에 노출:
- TipTap issue-body 에디터 (Markdown 직렬화, HTML 아님)
- 우선순위(1~5)/영향도(1~3) 셀렉터, 라벨 칩 입력, 환경 텍스트
- issueResponseSchema에 description/descriptionHtml/priority/priorityName/labels/environment/impact/impactName 추가
- UpdateIssueInput에 5필드 추가

**계약 주의(stored XSS 방지)**: 이슈 목록/표시는 raw `description`이 아니라 정화본 `descriptionHtml`을 써야 함. raw description은 편집 폼에서만 사용.

**선례 패턴**: PR #39 D6(타입 셀렉터)·PR #41(전이 UI)이 같은 화면(IssueMetaPanel / issues.$key) 작업. 계약갭(frontend-zod-backend-dto-contract-gap) 회피 위해 spec 단계에서 백엔드 DTO grep 검증 필수.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
