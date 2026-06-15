# 첨부 다운로드 응답 X-Content-Type-Options: nosniff 헤더 추가

> slug: attachment-nosniff-header
> type: bugfix (보안 경화)
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-15

## Brief

FR-AC-02(#147) 코드리뷰 P3 후속. 첨부 다운로드 엔드포인트 `GET /api/v1/issues/{key}/attachments/{id}`(IssueAttachmentController.download) 응답에 `X-Content-Type-Options: nosniff` 헤더가 없다. 브라우저 MIME 스니핑을 막아 미리보기(FR-AC-02)·다운로드의 콘텐츠 타입 신뢰를 굳힌다. 백엔드 1줄 + 컨트롤러 테스트.

fast-track(bugfix) — domain/spec/review-plan 스킵.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움 — fast-track 스킵)
