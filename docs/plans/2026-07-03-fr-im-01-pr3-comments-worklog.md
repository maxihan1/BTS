# FR-IM-01 PR3 — 댓글/Worklog Import

> slug: fr-im-01-pr3-comments-worklog
> type: feature
> agent: backend-engineer
> primary_bc: search-export-import (논리) + issue-tracking (물리)
> 생성: 2026-07-03

## Brief

FR-IM-01 Jira 마이그레이션 에픽 3단계 (PR3). PR1(코어 이슈 생성)·PR2(컴포넌트/버전 자동생성 + 소스 상태 전이) 완료 이후 후속.

Jira 소스 이슈의 **댓글(comments)** 과 **Worklog(작업 로그)** 항목을 CSV/JSON에서 파싱해, 생성된 이슈에 함께 가져온다.

PR2와 동형 패턴 예상.
- 파서(`ImportRowParser`)·`IssueImportCommand`(shared-kernel) 확장
- `IssueImportAdapter`가 댓글/Worklog 생성 유스케이스를 actor=requester로 위임 (권한 게이트 재사용)
- best-effort 경고 (권한 없음/검증 실패 → 경고, 이슈는 생성)
- dry-run 미러 (실경로 유효성-예측 경고 미리보기)
- G1 경고 노출 (결과 로그 CSV) 계승

에픽이므로 D박스 마킹은 FR-IM-01 전체 완료 시. FR 카운트(123) 불변, 신규 FR 0.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
