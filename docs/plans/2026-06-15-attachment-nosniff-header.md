# 첨부 다운로드 응답 X-Content-Type-Options: nosniff 헤더 추가

> slug: attachment-nosniff-header
> type: bugfix (보안 경화)
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-15

## Brief

FR-AC-02(#147) 코드리뷰 P3 후속. 첨부 다운로드 엔드포인트 `GET /api/v1/issues/{key}/attachments/{id}`(IssueAttachmentController.download) 응답에 `X-Content-Type-Options: nosniff` 헤더가 없다. 브라우저 MIME 스니핑을 막아 미리보기(FR-AC-02)·다운로드의 콘텐츠 타입 신뢰를 굳힌다. 백엔드 1줄 + 컨트롤러 테스트.

fast-track(bugfix) — domain/spec/review-plan 스킵.

## Plan

### Task 1. download 응답에 X-Content-Type-Options: nosniff 추가

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/attachment/web/IssueAttachmentController.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/attachment/IssueAttachmentControllerTest.kt`]
- depends-on: []

**RED**: `IssueAttachmentControllerTest.kt` C-3 테스트에 단언 추가
- `.andExpect(header().string("X-Content-Type-Options", "nosniff"))`
- 실패: 컨트롤러가 아직 헤더 미설정 → 단언 실패

**GREEN**: `IssueAttachmentController.download` ResponseEntity에 `.header(HttpHeaders.X_CONTENT_TYPE_OPTIONS 또는 "X-Content-Type-Options", "nosniff")` 추가(기존 CONTENT_TYPE/DISPOSITION/LENGTH 옆). KDoc 보안 주석 1줄.

**REFACTOR**: 상수/주석 정리(필요 시).

**검증**: `cd backend && ./gradlew :issue-tracking:test --tests '*IssueAttachmentControllerTest*'` + ktlint.

## Plan 메타
- task 수: 1 (bugfix fast-track)
- TDD 강제: yes (test red → green)
- 백엔드만, 마이그레이션/스키마 변경 0

## 리뷰 결과 (fast-track — bts-review-plan 스킵)
