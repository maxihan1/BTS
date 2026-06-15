# FR-AC-02 첨부 미리보기 (이미지/PDF/동영상)

> slug: fr-ac-02-preview
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-15

## Brief

FR-AC-02 첨부파일 미리보기 기능 구현 (이미지/PDF/동영상 인라인 미리보기).
선행 FR-AC-01(#145 백엔드 + #146 프론트/E2E) — 서버 경유 멀티파트 스트리밍 업로드/다운로드/목록/삭제 완료.

classify 결과. type=backend, agent=backend-engineer, primary_bc=issue-tracking.

**핵심 설계 긴장점 (spec에서 해소 필요)**.
- product 문서 D4는 "Presigned GET URL"로 표기되어 있으나, FR-AC-01(#145)이 이미 presigned URL을 폐기하고 "서버 경유 스트리밍"으로 deviation(권한 일원화·고아 객체 회피). FR-AC-02도 같은 deviation 일관성 필요 가능성.
- FR-AC-01 다운로드는 `Content-Disposition: attachment`로 인라인 실행을 의도적으로 차단. 미리보기는 정반대로 브라우저 인라인 렌더링 필요 → 보안(특히 SVG/HTML XSS) 재검토 필수.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
