# FR-IM-01 PR4 — 첨부/이력 Import

> slug: fr-im-01-pr4-attachments-history
> type: backend (feature-equivalent, full workflow)
> agent: backend-engineer
> primary_bc: search-export-import (논리) + issue-tracking (물리)
> 생성: 2026-07-03

## Brief

FR-IM-01 Jira 마이그레이션 Import 에픽 4단계(마지막 백엔드 PR). PR1(코어)·PR2(컴포넌트/버전/상태)·PR3(댓글/Worklog) 완료 이후 후속.

SDD 10.6.3 마이그레이션 보존 대상 중 남은 두 가지 — **첨부파일**과 **이력(history)** 을 Import.

- 첨부. **zip 업로드 방식**(서버가 Jira URL을 fetch하지 않음 = SSRF 없음, Maxi 결정). Jira export 시 함께 받은 첨부 바이너리를 zip으로 업로드 → 생성된 이슈에 매핑.
- 이력. Jira changelog/history를 생성된 이슈 이력으로 보존(세부 정책 spec에서 확정).

에픽이므로 D박스 마킹은 FR-IM-01 전체 완료 시. FR 카운트(123) 불변, 신규 FR 0.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
