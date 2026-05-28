# FR-WF-02 D7 E2E — 워크플로우 스킴 Playwright 시나리오

> slug. fr-wf-02-d7-e2e-crud-playwright
> type. qa
> agent. qa-engineer
> 생성. 2026-05-29

## Brief

PR #31 ([ui] FR-WF-02 D6) 가 머지하면서 워크플로우 스킴(Workflow Scheme) 관리 UI 의 단위/통합 테스트는 완료. 그러나 spec §2.2 의 D7 (E2E) 항목은 미완료 — 유일한 미완 deliverable. 본 PR 가 그 마지막 항목을 채워서 FR-WF-02 BC 완료에 도달하는 것이 목표.

### 사용자 원문

`FR-WF-02 D7 E2E 작업하자 — 스킴 CRUD + 매핑 편집 + 표준 보호 + 사용 중 삭제 차단 모달 + 프로젝트 할당 Playwright 시나리오`

### classify-task 결과

- type. qa
- agent. qa-engineer
- slug. fr-wf-02-d7-e2e-crud-playwright
- primary_bc. null (frontend E2E 영역)

### 시나리오 후보 (사용자 명시 5건)

1. **스킴 CRUD** — 목록 → 생성 → 상세 → 수정 → 삭제 happy path
2. **매핑 편집** — 이슈 타입 ↔ 워크플로우 매핑 추가/변경/제거
3. **표준 보호** — `isDefault: true` 스킴 삭제/수정 제약 검증
4. **사용 중 삭제 차단 모달** — `SchemeInUseException` 발생 시 `SchemeInUseModal` 노출 + `usedByProjects` 리스트
5. **프로젝트 할당** — 프로젝트 ↔ 스킴 assign/reassign/unassign

## 도메인 정리

### BC

- **project-workflow** — `backend/modules/project-workflow/` 의 워크플로우 스킴 (Workflow Scheme — 이슈 타입 → 워크플로우 매핑을 묶은 단위) 관리 영역. PR #31 도입.
- 본 PR 은 frontend E2E 만 추가. backend / 도메인 모델 변경 0.

### 영향 엔티티 (모두 기존, 신규 0)

| 엔티티 | 위치 | 도입 PR |
|---|---|---|
| `WorkflowScheme` | `backend/modules/project-workflow/.../scheme/` | #31 |
| `SchemeIssueTypeMapping` | 같은 영역 | #31 |
| `ProjectWorkflowSchemeAssignment` | 같은 영역 | #31 |
| `SchemeInUseException` + `SchemeInUseModal` | backend + `apps/web/src/components/admin/` | #31 |

### grill-with-docs 스킵 사유 (qa fast-track 변형)

bts-domain SKILL.md §Fast-track 스킵 조건은 명시적으로 `chore/bugfix` 만 허용. 본 task 는 type=qa 라 원칙적으로 grill-with-docs 호출 대상. **그러나 다음 사유로 변형 스킵 적용**.
1. 신규 도메인 개념 도입 0 (위 표 4개 엔티티 모두 PR #31 기존)
2. 본 task scope = "기존 UI 의 Playwright 행위 검증" — DDD 유비쿼터스 언어 정련의 영역 아님
3. grill-with-docs 의 대화형 비용 (~10분, 3-5 round) > 신규 통찰 기대값 (0)
- Maxi 가 본 변형에 동의하지 않으면 재호출 가능 — plan §변형 사유 명시로 가시화.

### 발견된 drift (본 PR scope 외, 후속 chore PR 후보)

#### Drift-1. glossary.md 누락 4 용어 (PR #31 도입, glossary 미반영)

- 워크플로우 스킴 (Workflow Scheme) — 이슈 타입 → 워크플로우 매핑을 묶은 단위
- 스킴 매핑 (Scheme Mapping) — 한 스킴 안에서 이슈 타입과 워크플로우 1:1 연결
- 표준 스킴 (Default Scheme / `isDefault: true`) — 신규 프로젝트의 기본 + 일부 수정 제약 (전체 삭제 차단, 매핑은 수정 가능)
- 사용 중 스킴 (Scheme In Use) — 어느 프로젝트에라도 할당된 스킴 (삭제 차단, `SchemeInUseException`)

**후속 후보**. `/bts FR-WF-02 glossary drift cleanup` 또는 본 PR 머지 후 별 chore PR.

#### Drift-2. ADR 동기화 방향 의심 (repo ↔ Maxi_wiki)

- `Maxi_wiki/BTS/decisions/` 가 2026-05-28 까지 누적 (10건)
- repo `docs/decisions/` 는 2026-05-27 까지 (15건 — 일부는 2026-05-22 후 누락)
- repo 누락 ADR. `2026-05-22-issue-key-prefix-policy`, `2026-05-22-issue-permission-resolver-port`, `2026-05-22-pgmq-postgres-image`, `2026-05-26-jooq-execute-advisory-lock-exception`, `2026-05-26-workflow-transition-port-result-sealed`, `2026-05-27-bts-workflow-token-hardening`, `2026-05-28-workflow-scheme-frontend-view-layer-cross-bc-lookup`, `2026-05-28-workflow-transition-identity-policy`
- Obsidian `_index.md` §동기화 규칙은 "Repo → Obsidian 단방향" 명시. 그러나 현 상태는 Obsidian 이 repo 보다 신선 — 단방향 sync hook 의 미동작 또는 누락 commit 의심.

**후속 후보**. sync hook 점검 + 누락 ADR repo 반영 chore PR. 본 PR 영향 0 — D7 시나리오 작성에 ADR 본문 직접 인용 무 (기존 UI 행위 검증).

### 본 PR 도메인 정리 결론

- 신규 ADR. 없음 (BTS 첫 ADR-free qa task 후보)
- 신규 용어. 없음 (위 4 용어는 후속 cleanup PR)
- 기존 결정 충돌. 없음
- glossary 갱신. 본 PR 영역 아님 (Drift-1 의 후속 PR 대상)

## 스펙

전체 스펙. [docs/specs/2026-05-29-fr-wf-02-d7-e2e-crud-playwright.md](../specs/2026-05-29-fr-wf-02-d7-e2e-crud-playwright.md)

핵심 5 시나리오 (D6 spec S1~S10 매핑).

- E2E-1 **스킴 CRUD** (D6 S1+S2+S3+PUT). 목록 → 생성 → 상세 진입 → name 수정 → 좌 네비 카운트 +1 갱신
- E2E-2 **매핑 편집** (D6 S4+S5+S6). 매핑 추가 (낙관적) → 삭제 → default mapping sentinel `__default__` → POST body `null` 변환
- E2E-3 **표준 스킴 보호** (D6 S7). 삭제 disabled + tooltip / key·is_default read-only / name·description 편집 자유 (D11 결정 반영)
- E2E-4 **사용 중 삭제 차단 모달** (D6 S8). 409 SCHEME_IN_USE → SchemeInUseModal `usedByProjects` link → 프로젝트 스킴 할당 화면 navigate
- E2E-5 **프로젝트 스킴 할당** (D6 S9+S10). PUT UPSERT 첫 할당 / GET 404 → 자동 할당 안내 카드

**모드**. MSW-based (`pnpm dev` + scheme-handlers 9개 + scheme-fixtures). 백엔드 기동 무관.
**fixture 1 신규**. `e2e/fixtures/workflow-scheme-fixtures.ts` (loginAsAdmin + navigate helpers + i18nLabels 재노출).

## Brainstorming Check

controller inline brainstorming (D6 spec line 156 패턴 따름).

### 🚨 BLOCKER 1건 — Maxi 결정 완료 (2026-05-29)

- **G-BLOCKER-1. 셀렉터 정본화 정책 (FR3)**. **옵션 (C) 채택** — 신규 `apps/web/src/i18n/workflow-scheme-labels.ts` 에 E2E 셀렉터가 의존하는 라벨/텍스트만 const export. 기존 components 의 hardcoded 중 셀렉터 참조 영역만 import 로 대체. 전면 i18n migration 은 별 후속 PR scope. plan §Task 단계에서 추가 task (라벨 file 신규 + 컴포넌트 부분 마이그레이션) 흡수.

### gap 6건 (BLOCKER 아님, plan 단계 흡수)

- G1. `/api/v1/issue-types` MSW handler 위치 확인 (scheme-handlers 외)
- G2. scheme-fixtures 의 현재 카운트 (표준 4 + 커스텀 2) 가 시나리오 가정과 일치 확인
- G3. SchemeInUseModal 의 `usedByProjects` link (체크포인트 C4) — fixture 빈 list 검증
- G4. TDD 변형 — UI 사전 존재로 "RED 자연 발생" 가설, plan §Task 본문에 사유 명시 (learnings 2026-05-28 Flyway recursive 사례 패턴)
- G5. loginAsAlice 의 admin 권한 가정 확인 (AlwaysAllow stub 단계 추정)
- G6. `pnpm test:e2e` 전체 duration baseline 측정 (PR #32 머지 후)

### Final

✅ 통과 (1 iteration, BLOCKER 1 + gap 6 plan 흡수).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
