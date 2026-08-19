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

## 도메인 정리

**BC.** `project-workflow` 단일. `classify.primary_bc` 는 `null` 이지만 이는 설계상 `migration` 타입이
BC 무관을 반환하기 때문이다(`classify-task.ts:480`) — 오분류가 아니다. 변경 경로 전량이
`backend/modules/project-workflow/**` 안에 있다.

**영향 엔티티.**

| 엔티티 | 변화 |
|---|---|
| `statuses` (신규) | 사이트 전역 상태 카탈로그. `key` 전역 UNIQUE · `lower(name)` 부분 UNIQUE |
| `workflow_statuses` (신규) | 워크플로우 ↔ 상태 N:M. 워크플로우마다 다른 값(`display_order` · 다이어그램 좌표)을 담는다 |
| `workflows` | 컬럼 4종 추가 — `version` · `origin` · `deleted_at` · `is_locked` |
| `workflow_states` | **유지.** 백필 원본이자 `workflow_transitions` FK 의 참조 대상. DROP 은 로드맵 마지막 PR |
| `YamlSeedService` | 「변경 감지 시 삭제 후 재삽입」 → 「없을 때만 삽입」 |

**새 용어.** 없다. 「상태(Status)」·「워크플로우」·「전환」은 `glossary.md §워크플로우 / 자동화` 에 이미
있고, 이 PR 은 그 용어의 **저장 위치**만 바꾼다. `glossary.md` 갱신 불필요.

**기존 결정 충돌.** 없다. 이 PR 은 2026-08-18 ADR 2건을 **구현**한다.

- `docs/adr/2026-08-18-workflow-global-status-catalog.md` — D1(`statuses` 신설) · D2(`workflow_statuses` N:M) ·
  D3(키 불변) · D4(백필 유일성 가드 + red 1회) · D5(`workflow_states` 즉시 DROP 금지)
- `docs/adr/2026-08-18-workflow-db-as-source-of-truth.md` — D2(YAML 은 빈 DB 부트스트랩 전용) ·
  D3(표준 4종도 편집 허용 · `origin='SEED'` 표기)
- 참조. `docs/adr/2026-05-26-bc-migration-prefix-policy.md`(V200~V299 대역) · `DATA.md §4`(3단 분할) · `§7`(FK 인덱스)

**ADR 정정 1건.** `-global-status-catalog.md` §영향이 「`src/generated/jooq/` 는 git 커밋 대상」이라고
적었으나 `.gitignore:21` 이 `**/src/generated/jooq/` 를 제외한다(실측 — `git ls-files '*/src/generated/*'`
= 1파일, issue-tracking `.editorconfig` 뿐). 이 PR 에서 해당 문단을 1줄 정정한다.

**신규 도메인 개념 없음** → `grill-with-docs` 미호출.

## 스펙

정본. [`docs/specs/2026-08-19-migration-project-workflow-global-status-catalog.md`](../specs/2026-08-19-migration-project-workflow-global-status-catalog.md)

핵심 시나리오 3줄.

1. 운영자가 DB 에서 워크플로우를 고치고 **재기동해도 고친 값이 살아 있다** (종전에는 시드가 되돌렸다).
2. 배포된 DB 는 V204 백필로, 빈 DB 는 시드의 이중 기록으로 **같은 카탈로그**(`statuses` 12행 ·
   `workflow_statuses` 17행)에 도달한다.
3. 상태 키가 이름·카테고리와 1:1 이 아니면 V204 가 `RAISE EXCEPTION` 으로 **배포를 멈춘다**.

**Maxi 확정 D1 (2026-08-19).** 읽기 경로 전환은 이 PR 에서 뺀다(A안). 근거는 실측 — 워크플로우 상태를
원시 SQL 로 직접 심는 테스트가 **32파일**(issue-tracking 21 · project-workflow 10 · 그 외 1)이라, 읽기를
바꾸면 그 픽스처들이 만든 워크플로우가 상태 0개로 읽혀 2개 BC 를 가로지르는 테스트 이주가 「가장
위험한 PR」에 겹친다. 대신 새 테이블이 write-only 가 되는 사각을 막는 **대조 판별식**(스펙 C4)을 필수
산출물로 넣는다. 읽기 전환은 PR 3 에서 픽스처 헬퍼 1개와 함께 한다.

## Sanity Check

**✅ 통과** — 보강 3건(빈 DB 최초 부팅 시 시드 이중 기록 · 전역 키 재사용 시 이름 보존 · write-only
사각을 막는 대조 판별식)을 1회 반영했고, 도달 불가 케이스 1건(`lower(name)` 충돌)은 가짜 그린을 피해
**의도적으로 테스트를 만들지 않고** PR 3 로 넘겼다. Maxi 결정 필요 항목은 D1 하나였고 확정됐다.
상세는 스펙 §Sanity Check.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
