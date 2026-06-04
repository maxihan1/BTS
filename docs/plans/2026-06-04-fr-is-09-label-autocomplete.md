# FR-IS-09 — 라벨 자동완성

> slug: fr-is-09-label-autocomplete
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-04

## Brief

FR-IS-09 라벨 자동완성 (issue-tracking BC). spec 위치: `docs/plan/product/issue-tracking.md §2.2.2`.
이슈에 라벨을 부여할 때 입력값에 맞춰 후보 라벨을 자동 제안한다.

- classify: type=backend, agent=backend-engineer, primary_bc=issue-tracking, slug=fr-is-09

## 도메인 정리

- **BC**: issue-tracking
- **영향 엔티티**: Issue 애그리거트 (기존). 신규 엔티티 없음.
- **데이터 모델**: 기존 `issues.labels TEXT[]` 배열 활용 (GIN 인덱스 `ix_issues_labels_gin` 존재).
  정규화 테이블(`labels`, `issue_labels`) **미도입** — 옵션 A 확정.
- **새 용어**: 없음 (라벨은 이미 존재하는 도메인 개념)
- **기존 결정 충돌**: plan §2.2.2 D3 "labels, issue_labels 신규" 표기가 SDD 정본
  (`05-data-model.md:18` labels TEXT[])과 drift. 본 작업에서 plan D3 표기를 정정.
- **선행 상태 (ground-truth)**:
  - 라벨 도메인 검증(`Issue.normalizeLabels()` — 최대 50자/20개, 공백 거부, 중복 제거): 구현됨
  - PATCH 라벨 교체(`UpdateIssueRequest.labels`), 클론 시 복사(`IssueApplicationService:214`): 구현됨
  - **미구현 = 본 작업 범위**: 자동완성 조회 엔드포인트 `GET /api/v1/labels?q=<prefix>` + 프론트 콤보박스 UI
- **Jira 정합**: Jira Label = 마스터 테이블 없는 글로벌 free-form 텍스트 태그. 현 구현이 이미 Jira 모델.
- **관련 ADR**: [docs/adr/2026-06-04-issue-label-freeform-tag-model.md](../adr/2026-06-04-issue-label-freeform-tag-model.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-04-fr-is-09-label-autocomplete.md](../specs/2026-06-04-fr-is-09-label-autocomplete.md)

핵심 요약.
- `GET /api/v1/labels?q=<prefix>` — 활성 이슈 라벨에서 prefix(대소문자 무시) 매칭, 사용 빈도순(동률 알파벳 tiebreak) 최대 10개, `DataResponse<List<String>>`.
- `q` 빈값 → 전체 인기 라벨 top-10. 매칭 0건 → 빈 배열 200. 스코프=글로벌, 권한=Global VIEW.
- ILIKE 와일드카드(`%`/`_`/`\`) 이스케이프 필수. 삭제 이슈 제외. 데이터 모델 변경 0.
- 프론트 cmdk 콤보박스(신규 라벨 입력 허용) + 기존 PATCH로 라벨 저장 + E2E.

## Brainstorming Check

✅ 통과 (자체 적대적 sanity check, gap 5건 발견·반영: ILIKE 이스케이프 / q 공백 처리 / 케이스 보존 / 삭제이슈 제외 / 빈도동률 tiebreak)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
