# FR-IM-01 PR2 — Import 컴포넌트/버전 자동생성 + 소스 상태 전이

> slug: fr-im-01-pr2-import
> type: feature
> agent: backend-engineer
> primary_bc: search-export-import (issue-tracking로 cross-BC 쓰기 확장)
> 생성: 2026-07-02

## Brief

FR-IM-01 (CSV/JSON Import, Jira 마이그레이션) 에픽의 **PR2**.
에픽 구조 (Maxi 결정, SDD 10.6.3 풀 마이그레이션):
PR1 코어(완료, #218) → **PR2 컴포넌트/버전 자동생성 + 소스 상태 전이** → PR3 댓글/Worklog → PR4 첨부(zip)/이력.

PR2 범위 (초안, spec/domain에서 확정):
- Jira 데이터의 **컴포넌트** → 대상 프로젝트에 자동 생성 (없으면 생성, 있으면 재사용) + 이슈 연결
- Jira 데이터의 **버전** (affects/fix version) → 자동 생성 + 이슈 affects/fix 연결
- Jira **소스 이슈 상태** → BTS 워크플로우 상태로 전이 (생성 후 목표 상태로 이동)

PR1 유산:
- `com.bts.search.imports` 모듈, `ImportJobWorker`, CSV/JSON 스트리밍 파서
- `IssueImportPort` (shared-kernel, issue-tracking `IssueImportAdapter` 구현) — BTS 최초 cross-BC 쓰기 포트, default fail-closed
- 행별 best-effort = create+update 1 @Transactional 원자성

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
