# FR-MV-02 — 이동 시 히스토리 보존 + 링크 유지

> slug: fr-mv-02-move-preserve
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-17

## Brief

프로젝트 간 이슈 이동(FR-MV-01, 완료)이 이슈 `id`(UUID)를 보존하고 key만 변경하는 구조 위에서,
이슈에 연결된 모든 부속 데이터(히스토리·링크·Watcher·첨부 등 FK가 issue_id를 참조하는 것)가
이동 후에도 빠짐없이 보존됨을 보장·검증한다.

- 선행 완료: FR-MV-01(§6.1.1, 단건+서브태스크 동반 이동), FR-HS-01(§5.1.1, 이력), FR-LK-01(§5.3.1, 링크)
- D3(데이터 모델)은 이미 [x] — id 보존 + key만 변경 구조 기존재
- 남은 D단계: D1(도메인), D2(명세), D4(백엔드 FK 보존 검증), D5(invariant 비교 테스트), D6(프론트 갱신), D7(E2E)

원문(classify): backend / backend-engineer / issue-tracking BC

## 도메인 정리

- **BC**: issue-tracking (단일). cross-BC 직접 호출 없음 — 이동 자체는 FR-MV-01이 이미 구현, 본 FR은 그 위의 보존 검증 + 이력 기록.
- **영향 엔티티**: Issue(이동 대상), IssueHistory(`issue_change_group`/`issue_change_item`), Link(`issue_links`), Watcher(`issue_watchers`), Attachment(`issue_attachments`). **신규 엔티티 0**.
- **신규 용어**: 없음 (모두 glossary 기존재 — 이슈/이슈 키/IssueKeyRedirect/링크/워처/어테처).
- **기존 결정 충돌**: 없음. 오히려 ADR `2026-06-16-issue-move-semantics`가 본 FR을 직접 사전 범위 지정.

### 현재 이동 구현 실측 (Explore 조사, 코드 라인 인용)

- **(A) in-place UPDATE** — `IssueRepository.moveIssue()` (`backend/modules/issue-tracking/.../repository/IssueRepository.kt:1159-1191`)가 `project_id`/`key`/`current_state_key`/`resolution_id`/`custom_fields`/`parent_id`/`version`/`updated_at`만 UPDATE. **`id`(UUID PK) 불변, delete+insert 없음**.
- **(B) id 참조 부속 테이블 보존**:
  - `issue_links`(source/target UUID FK, V021)·`issue_attachments`(V023)·`issue_watchers`(V024) → id 불변이라 **자동 보존** (이동 영향 0).
  - `issue_change_group`/`issue_change_item`(V018) → issues에 **FK 없음**(append-only, 이력 보존 우선) + `issue_id` 컬럼 보존 + `issue_key` 컬럼에 **기록 시점 키 박제** → **자동 보존**.
  - `issue_components`/`issue_affects_versions`/`issue_fix_versions` → 이동 서비스가 **의도적 매핑 교체**(프로젝트 종속, FR-MV-01 설계). 미매핑은 제거.
- **(C) stale 위험**: 이력의 `issue_key`는 박제라 안전. project_id는 이력에 미저장이나, issue_key 박제 + (도입 예정) 이동 이벤트로 추적성 확보 가능. **신규 컬럼 불요 판단**.
- **(D) 갭**: 이동 자체가 이력에 **명시 기록 안 됨**. `IssueChangeDetector`의 SCALAR_FIELD_EXTRACTORS에 project/key 없음 → changelog에 "프로젝트 이동"이 안 보임(컴포넌트/상태 변경만 보임). 이동은 현재 앱 로그(`IssueMoveService.kt:323`)에만 존재.

### ADR가 FR-MV-02로 위임한 책임 (2026-06-16-issue-move-semantics)

- §18: "id 보존 → 히스토리·링크·워처·첨부 자동 보존. 이 점이 FR-MV-02를 **구조적으로 충족**."
- §128: "미매핑 컴포넌트/버전은 제거됨. **이력에 '이동 + 제거된 연결' 기록으로 추적성 확보 (FR-MV-02 / 히스토리).**"

→ FR-MV-02 = (1) 보존 invariant 명시 검증(D5) + (2) 이동 이벤트를 이력에 기록(D2/D4). "제거된 연결"은 detector가 이미 컴포넌트/버전 필드변경으로 포착 중 — **이동(project/key) 추적만 추가** 필요.

- **관련 ADR**: [docs/adr/2026-06-16-issue-move-semantics.md](../adr/2026-06-16-issue-move-semantics.md) (FR-MV-01, 본 FR 사전 범위 지정). 신규 ADR 후보 — 이동 이력 기록 방식(아래 범위 결정에 따라).

## 스펙

전체 스펙. [docs/specs/2026-06-17-fr-mv-02-move-preserve.md](../specs/2026-06-17-fr-mv-02-move-preserve.md)

핵심 3줄 요약.
- 이동은 id 보존 in-place UPDATE라 히스토리/링크/워처/첨부가 자동 보존 — invariant 통합 테스트로 명시 고정(D5).
- 갭은 단 하나: 이동 자체가 이력에 미기록(detector에 key 없음, 순수 이동은 no-op). → `IssueChangeDetector`에 `key` 추적 추가 → 이미 흐르는 `record(before, after)`가 이동을 기록.
- 프론트는 changelog i18n 라벨 1줄 + 이동 후 새 키 navigate(FR-MV-01 기존) + E2E.

범위(Maxi 확정): 검증 + 이동 이벤트 기록. 신규 DB 컬럼 0, 신규 엔드포인트 0, cross-BC 0.

## Brainstorming Check

✅ 통과 (1회, Maxi 결정 갭 0). G1(changelog 폴백 렌더 확인—라벨 1줄 추가)·G2(기존 detector 테스트 전수 실행)·G3(첨부는 DB행 보존으로 검증, MinIO 왕복 생략) 모두 구현 단계 처리.

## Plan

> 단일 PR (백엔드 detector 1줄 + 통합테스트 + 프론트 i18n 1줄 + E2E — 소규모 FR이라 D1~D7 한 PR).
> 모든 작업 worktree `.worktrees/fr-mv-02-move-preserve` 내부. 경로는 repo 루트 기준.

### Task 1. IssueChangeDetector에 `key` 추적 추가 → 이동 이벤트 감지

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/history/IssueChangeDetector.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/history/IssueChangeDetectorTest.kt`]
- depends-on: []

**RED** (`IssueChangeDetectorTest.kt`):
- `detect()` 가 `before.key ≠ after.key` 일 때 `field="key"`, fromValue=옛 키, toValue=새 키 항목을 정확히 1건 포함한다.
- `before.key == after.key` 면 `key` 항목을 만들지 않는다(이동 외 경로 회귀 가드, EC6).
- 키 변경이 다른 필드 변경(예: status)과 공존 시 둘 다 기록(이동의 부수효과 status/resolution도 함께).
- 실패 예상: 현재 SCALAR_FIELD_EXTRACTORS에 `key` 없음 → key 항목 0건으로 단언 실패.

**GREEN** (`IssueChangeDetector.kt`):
- `SCALAR_FIELD_EXTRACTORS` 에 `"key" to { it.key.value }` 1줄 추가(라벨 불요 — resolver passthrough, status/summary와 동일 정책).

**REFACTOR**:
- KDoc 한 줄 — "key 는 이동(FR-MV-02)에서만 변경되므로 이동 이벤트 마커 역할". detekt baseline 동결(메모리), 신규 위반 시 헬퍼 추출.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*IssueChangeDetectorTest"` + 기존 detector 8진입점 테스트 전수 green(NFR2). `IssueChangeLabelResolverTest`도 회귀(`key`는 라벨 미해석 passthrough 확인).

### Task 2. 이동 보존 invariant 통합 테스트 (D5) — 행 보존 + recorder 새 키 호출

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/IssueMoveIntegrationTest.kt`]
- depends-on: []

**RED/GREEN** (테스트 추가 — 기존 mockk recorder 재사용, 신규 production 코드 0):
- TestConfig에 `IssueLinkRepository`, `AttachmentRepository` 빈 추가(워처는 이미 wiring). 단순 jOOQ repo.
- **단건 이동 보존**: 이슈 BTS-x에 링크(out: blocks, in: relates)·워처 2·첨부 2 시드 → 이동 → 이슈 id로 재조회해 `issue_links`/`issue_watchers`/`issue_attachments` 행 집합이 **이동 전과 동일**함을 단언(첨부는 DB 행만, MinIO 왕복 생략 — G3).
- **이동 이벤트 호출 검증**: `verify { historyRecorder.record(before = any(), after = match { it.key == newKey && it.projectId == targetProjectId }, ...) }` — 이동이 새 키 after-스냅샷으로 recorder를 호출함을 mockk capture로 단언. Task1(detector key 감지)과 합쳐 "이동→이력 기록" end-to-end 증명(무거운 real recorder wiring 불요).
- **서브태스크 동반 보존**: 부모+자식 각자 링크/워처/첨부 시드 → 동반 이동 → 노드별 행 보존 + 각 노드 recorder 새 키 호출 단언.
- 실패 예상: 신규 테스트 메서드 부재(컴파일 후 단언). 보존은 현 구현이 이미 충족하므로 즉시 green — invariant를 회귀 가드로 고정(FR2).

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*IssueMoveIntegrationTest"` green. 기존 FR-MV-01 케이스 회귀 보존.

### Task 3. 프론트 changelog "이동" i18n 라벨

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/ko.ts`, `apps/web/src/lib/changelog-labels.test.ts`]
- depends-on: []

**RED** (`changelog-labels.test.ts`):
- `resolveFieldLabel('key', refs)` === `'이동'`(또는 ko.ts에 정의한 값).
- `resolveValueLabel({field:'key', fromValue:'BTS-1', toValue:'PROJ-42', ...}, 'from'/'to', refs)` 가 각각 `'BTS-1'`/`'PROJ-42'` raw 반환(폴백 경로 회귀).
- 실패 예상: `changelogFieldLabels.key` 미정의 → `resolveFieldLabel`이 `'key'` 폴백 반환 → 단언 실패.

**GREEN** (`i18n/ko.ts`):
- `issueDetailStrings.changelogFieldLabels` 에 `key: '이동'` 1줄 추가(콜론 종결 금지 — ko.test 자동검증, 라벨 값은 명사라 무관).

**REFACTOR**:
- `IssueChangelog.tsx`가 전 항목을 매핑·필드 화이트리스트 필터 없음 재확인(폴백 렌더 — G1). 변경 필요 시 최소.

**검증**: `pnpm --filter web test -- changelog-labels` + `pnpm --filter web test -- ko` (i18n 콜론 가드) green.

### Task 4. E2E — 이동 후 이력에 "이동" 표시 + 보존 (D7)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-move.spec.ts`, `apps/web/src/mocks/changelog-handlers.ts`, `apps/web/src/mocks/changelog-fixtures.ts`]
- depends-on: [3]

**시나리오**:
- 기존 `issue-move.spec.ts` 이동 happy-path 위에서, 이동 성공 후 새 키 페이지의 변경 이력 패널에 **"이동" 항목(BTS-1 → PROJ-42)** 표시 단언.
- 이동 후 링크/워처 패널이 보존 데이터를 그대로 표시(MSW가 새 키로 링크/워처 반환).
- MSW: move 핸들러(기존) + changelog 핸들러에 `key` 변경 항목 추가(stateful — 이동 후 changelog에 이동 이벤트 등장). 메모리 교훈: MSW mutation stateful, 토스터/항목 컨테이너 한정 셀렉터, strict-mode within.

**검증**: `pnpm --filter web test:e2e -- issue-move` green. 기존 이동 E2E 회귀 보존.

## Plan 메타

- task 수: 4 (T1 detector / T2 보존 통합테스트 / T3 프론트 라벨 / T4 E2E)
- 예상 wave: 2 (Wave1 = T1·T2·T3 병렬 [deps 없음, 파일 무겹침], Wave2 = T4 [deps 3])
- TDD 강제: yes (T1 RED→GREEN production 변경, T2/T3/T4는 테스트 우선)
- 신규: DB 컬럼 0 · 엔드포인트 0 · cross-BC 0 · production 변경 = detector 1줄 + i18n 1줄
- 추가 검증: ktlint/detekt(baseline 동결), pnpm typecheck/lint, vitest, playwright(qa)
- 주의(메모리): 동시 브랜치 Flyway 충돌 무관(마이그레이션 0) · detekt 캐시 false-green→`--rerun-tasks` · 에이전트 lint 보고 불신 controller 직접검증 · 단일 Gradle 모듈 test 컴파일 직렬화 요인

## 리뷰 결과 (← /bts-review-plan 채움)
