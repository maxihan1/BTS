# [migration] project-workflow — 전역 상태 카탈로그 + 시드 부트스트랩 전환

> 티어: T3
> slug: migration-project-workflow-global-status-catalog
> type: migration
> agent: db-engineer
> 생성: 2026-08-19

## Brief

워크플로우 편집기 로드맵(정본 `~/.claude/plans/cozy-hatching-otter.md` §PR 분해) 10 PR 중 **2번**.
선행 PR 1(#391 — ADR 4건 + FR-WF-04~07 등재)은 머지 완료.

FR — FR-WF-04(워크플로우 CRUD) · FR-WF-05(전환 ID·다중·전역 전환) · FR-WF-06(전환 규칙 편집) ·
FR-WF-07(Draft/Publish + 상태 이관)의 **DB 토대**. 이 PR 자체는 API 를 추가하지 않는다.

산출물 4종.
- `V203__add_global_status_catalog.sql` — `statuses` · `workflow_statuses` 신설
- `V204__backfill_status_catalog.sql` — `workflow_states` → `statuses` 승격 + `workflow_statuses` 채움.
  키당 `(name, category)` 유일성 가드, 위반 시 `RAISE EXCEPTION`
- `V205__workflows_add_version_origin.sql` — `workflows` 에 `version`·`origin`·`deleted_at`·`is_locked` 추가
- `YamlSeedService.kt` 개편 — `ApplicationReadyEvent` 유지, **해당 key 의 workflow 행이 없을 때만** 삽입.
  `isDirty()` 비교·`deleteWorkflow`→재삽입 경로 제거. 삽입 시 `origin='SEED'`.
  YAML 원본은 「기본값 복원」 소스로 **유지**(삭제 금지)

불변 계약 — `GET /api/v1/workflows/{key}` 응답 형태를 바꾸지 않는다(`{key,name,description,states[],transitions[]}`).
내부만 `statuses` + `workflow_statuses` 2단 join 으로 교체하고, 새 필드(`transitions[].id`·`kind`)는 **추가**만 한다.
`apps/web` 무손상이 이 PR 의 조건이다.

classify 결과 — type=migration · agent=db-engineer · tier=T3(Maxi 지정 · 판정 규칙 ②) · primary_bc=null(설계상
migration 타입은 BC 무관 반환) · 실제 BC 는 **project-workflow** 단일.

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
