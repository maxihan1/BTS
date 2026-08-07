# FR-UX-14 B2 — 보드/백로그 카드 응답 필드 확장 (타입·라벨·추정)

> slug: fr-ux-14-b2-card-fields
> type: backend
> agent: backend-engineer
> primary_bc: agile-planning (+ shared-kernel 포트 · issue-tracking 어댑터)
> 생성: 2026-08-07

## Brief

**사용자 원문.** `/bts fr-ux-14`

**범위 확정 (Maxi 2026-08-07).** FR-UX-14 는 정본상 승계 PR 2건(`B2` 백엔드 → `F14` 프론트)이고,
이번 PR 은 **B2 백엔드 단독**이다. F14(카드 화면 밀도)는 후속 PR.
근거 — 정본 `docs/plan/product/personalization.md §4.12` 의 "승계 PR 2건",
선례 FR-UX-09 의 `B1` 이 별도 PR #328 로 처리된 전례.

**정본 정의.** `docs/plan/product/personalization.md §4.12 FR-UX-14 — 이슈 카드 밀도`
- **B2 = 이 FR 의 D4/D5** (2026-07-29 지위 정정 — 원래 "chore" 였던 것을 FR 의 D 단계로 승격)
- **B2 는 F14 의 유일한 차단점** — `BoardCardResponse` 에 타입·라벨·추정이 없다
- 정본 지목 대상. `shared-kernel .../board/BoardIssueLookupPort.kt` 의 `BoardIssueView` 에
  `typeKey`·`typeIconName`·`labels`·`originalEstimateSeconds` 추가
  + `issue-tracking .../repository/IssueRepository.kt` 보드 카드 SELECT·adapter 확장
  + `agile-planning .../web/dto/BoardResponses.kt`·`BacklogResponses`
- 정본 지정 성공 판정식. **N+1 회귀 가드** (라벨은 `TEXT[]` 컬럼이라 조인 불필요,
  타입은 `listWithType` 이 이미 조인 중)
- 정본 지정 템플릿. **선례 커밋 `dcbf130e6`** (FR-BD-02 보드 필터 — 같은 3모듈 조합 16파일)

## 작업 분류

| 항목 | classify 자동 | 확정값 | 정정 근거 |
|---|---|---|---|
| type | `ui` | **`backend`** | 정본 §4.12 D4/D5 책임 = backend-engineer · 이번 PR `apps/web` 0파일 |
| agent | `frontend-engineer` | **`backend-engineer`** | 위와 동일 |
| slug | `fr-ux-14-b2-shared-kernel-issue-tracking-agile-pla` | **`fr-ux-14-b2-card-fields`** | B1 선례가 동일 증상을 "관례 맞춰 정정" (`2026-07-31-fr-ux-09-b1-create-issue-fields.md:29`) |
| primary_bc | `agile-planning` | `agile-planning` | 유지 (진입점 컨트롤러 소유 BC) |

**오분류 원인.** 제목의 "카드 · 보드/백로그" 를 UI 신호로 읽었다.
**정정하지 않았을 때의 위험.** `type=ui` 는 `/bts` §Phase C 의 「ui 소규모 게이트 정책」을
발동시켜 **게이트 1(Maxi 정지)을 생략**시킨다. 안전 방향으로 정정했다.

## 착수 시 선행 확인 (learnings 반영)

이 작업에 직결되는 것으로 골라둔 항목 — 각 단계가 이 목록을 승계한다.

1. **`learnings.md:754` 파일 존재 ≠ 기능 존재.** "백엔드 완비" 판정은 컨트롤러 HTTP 매핑을
   세어서 한다. **정본 줄번호(`:157-166`·`:155-162`)를 믿지 말고 실측한다** —
   FR-UX-13 에서 정본 줄번호·행수가 **세 번** 틀렸다.
2. **`learnings.md:600` Zod 응답 스키마 강화가 산재한 인라인 mock 을 깬다.** 백엔드가 응답 필드를
   늘리면 프론트 Zod 스키마·MSW 핸들러·인라인 mock 이 동시에 깨질 수 있다.
   **B2 단독 PR 이라도 `apps/web` 영향면 전수 grep 이 필요하다** (이번 PR 에서 깨지는지,
   F14 로 미뤄도 되는지를 판정해야 한다).
3. **`learnings.md:647` ktlintFormat 모듈 전체 실행 금지.** 3모듈 Kotlin 작업이라 직결.
   신규 코드 lint 위반은 **파일 단위 수동 수정**. 검증은 `ktlintCheck`/`ktlintMainSourceSetCheck`
   (`runKtlintCheckOverMainSourceSet` 신뢰 금지).
4. **`learnings.md:663` worktree 산출물(docs) 커밋 누락.** 각 단계가 자기 docs 를 그 단계에서
   커밋한다. 머지 직전 `git status --porcelain` 전수 확인.
5. **`learnings.md:235` BC 격리 예외.** 이번은 프론트 없는 백엔드 3모듈이고,
   `shared-kernel` 포트 → `issue-tracking` 어댑터 → `agile-planning` 소비 방향이라
   선례 `dcbf130e6` 와 동형이다. plan §리스크에 사유를 명시한다.
6. **`learnings.md:776` 동시 PR 선점 확인.** 착수 시점 `git worktree list` 0건 ·
   `gh pr list --draft` 0건 실측 완료.

## 도메인 정리

- **BC**. agile-planning (응답 DTO 소유) — 포트는 `shared-kernel`, 어댑터는 `issue-tracking`.
  방향은 `shared-kernel` 정의 → `issue-tracking` 구현 → `agile-planning` 소비 **단방향**이라
  선례 커밋 `dcbf130e6`(FR-BD-02) 와 동형이다. BC 격리 예외 아님 — 세 모듈이 한 포트 계약의
  정의·구현·소비로 묶인 **하나의 논리 변경**이다.
- **영향 VO/DTO**. `BoardIssueView`(shared-kernel) · `BoardCardResponse` · `BacklogIssueResponse`(agile-planning)
- **신규 용어**. **0건.** glossary 의 「이슈 타입」·라벨·추정이 전부 기존 개념 → `glossary.md` 갱신 불필요
- **신규 엔티티/관계**. **0건** → `domain/agile-planning.md` 갱신 불필요
- **마이그레이션**. **0건** — 필요한 컬럼이 이미 전부 존재 (정본 D3 「없음 예상」 적중)
- **기존 결정 충돌**. **1건 — 의도적으로 뒤집는다.** `IssueRepository.kt:745` 의
  *"type 요약은 보드 카드에 불필요하므로 ISSUE_TYPES JOIN 생략"*. 근거는 같은 파일
  `listVisibleForTimeline:886` 이 그 조인을 이미 하고 있다는 것 (ADR §D-3). 주석은 이 PR 에서 정정한다.
- **관련 ADR**. [decisions/2026-08-07-fr-ux-14-b2-card-fields.md](../decisions/2026-08-07-fr-ux-14-b2-card-fields.md) (생성됨)

### 확정된 노출 필드 3개 (Maxi 확정 2026-08-07)

| 필드 | 출처 | 보드 조회에 이미 포함? | 필요 작업 |
|---|---|---|---|
| `typeKey` | `issue_types.key` | ❌ JOIN 의도적 생략 | **ISSUE_TYPES INNER JOIN 신규** |
| `labels` | `issues.labels TEXT[]` (V006) | ✅ `ISSUES.fields()` | 매핑만 (VO + 응답 2곳) |
| `originalEstimateSeconds` | `issues.original_estimate_seconds` (V027) | ✅ 동일 | 매핑만 |

`typeIconName`·`typeName` 은 **싣지 않는다** — 프론트가 기존 타입 목록 API(`api/issue-types.ts`)에서
얻는다. 이슈 상세 화면이 이미 그 방식이고, 자매 포트 `TimelineItemView` 도 식별자만 담는다 (ADR §D-1).

### 정본 전복 3건 (착수 전 실측)

1. 「타입은 `listWithType` 이 이미 조인 중」 → **경로 혼동**. 목록 경로와 보드 경로는 다르고,
   보드는 조인을 명문으로 생략해 뒀다.
2. 「보드 카드 SELECT 확장」 → **라벨·추정은 SELECT 무변경**. 이미 조회되는데 매핑에서만 버려진다.
   실제 쿼리 변경은 type JOIN 하나뿐.
3. 지정 필드 `typeKey`·`typeIconName` → **소비자 계약 불일치**. `IssueTypeIcon` 은 `typeKey` 를
   안 쓰고, 정본 조합엔 접근성 레이블이 없어 WCAG 임계를 스스로 깬다.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
