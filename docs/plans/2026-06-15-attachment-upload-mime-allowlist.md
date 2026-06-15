# 첨부 업로드 MIME 화이트리스트 검증

> slug: attachment-upload-mime-allowlist
> type: backend (보안 — 입력 검증)
> agent: backend-engineer (codereview 보안 관점)
> primary_bc: issue-tracking
> 생성: 2026-06-15

## Brief

FR-AC-01 D2 미완분 처리(Maxi 확정). D2는 크기 제한(100MB, 완료) / MIME 화이트리스트(미완) / ClamAV(미완·범위 밖)로 구성. 이 작업은 **업로드 MIME 화이트리스트**만 — 허용 타입만 업로드 허용, 나머지 거부(415 등). ClamAV는 별도 후속.

업로드 경로. `IssueAttachmentController.upload` / `IssueAttachmentService.upload` (`POST /api/v1/issues/{key}/attachments`).

**spec에서 확정할 정책 결정(핵심)**.
1. 화이트리스트 vs 차단리스트(denylist), 허용 타입 집합(협업 도구라 광범위 vs 보안 우선 협소).
2. 클라이언트 Content-Type 신뢰(위조 가능, 의존성 0) vs 내용 기반 탐지(magic bytes/Tika, 의존성 필요).
3. 거부 HTTP 상태(415 Unsupported Media Type 유력).
4. 확장자 병행 검증 여부.

## 도메인 정리 (← /bts-domain, domain-light 예상)

## 스펙 (← /bts-spec — 정책 결정 Maxi 제시)

## Brainstorming Check (← /bts-spec Phase B)

## Plan (← /bts-plan)

## 리뷰 결과 (← /bts-review-plan)
