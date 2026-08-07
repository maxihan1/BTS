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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
