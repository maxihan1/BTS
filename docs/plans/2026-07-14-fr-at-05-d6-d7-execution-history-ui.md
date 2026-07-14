# FR-AT-05 D6/D7 — 자동화 규칙 실행 이력 화면 + 단계별 trace + 재실행 버튼

> slug: fr-at-05-d6-d7-execution-history-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-14

## Brief

Maxi 원문. "FR-AT-05 D6/D7 실행 이력 화면 + 단계별 trace + 재실행 버튼 + Playwright E2E"

FR-AT-05 백엔드(D1~D5)는 PR #270로 머지 완료. 이번 작업은 D6/D7 = 프론트엔드 UI 마감 단계.
자동화 규칙 실행 이력을 조회하는 화면 + 실행 단건의 액션별 결과(trace) 펼쳐보기 + 동기 재실행(replay) 버튼 + Playwright E2E.

classify. classifier가 E2E 키워드로 qa 오분류 → Maxi 확인 후 ui/frontend-engineer 정정.
선례. automation BC D6/D7 UI PR — AT-01(#251→#254), AT-04(#268→#269).

백엔드 API(#270 제공, D1~D5).
- 실행 이력 조회 3종 (목록/단건/replay 관련) — 구체 엔드포인트는 /bts-spec에서 backend 소스 grep으로 확정
- 동기 replay API — MANAGE_AUTOMATION 가드, 소프트삭제 룰 409
- 응답 DTO nullable 필드: issueKey · replayedFrom · outcomes.error → 프론트 Zod .nullable() 필수

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
