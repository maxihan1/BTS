---
name: bts-codereview
description: Called by /bts step 6 — independent review of the pushed PR before 게이트 2. Never invoke directly.
---

# /bts-codereview

체인 [6]. PR 단위 1회 독립 리뷰 → 게이트 2 요약. **전 티어 진입**한다.
**게이트 2 를 막는 기계 장치는 없다.** 무료 플랜이라 브랜치 보호 설정이 403 으로 거부되므로 CI 는 돌되 머지를 차단하지 못한다 — 차단자는 Maxi 한 사람뿐이다. "CI 가 알아서 막겠지" 로 넘기지 않는다.

## 선행 읽기

**전부 컨텍스트에 이미 있다. 재로드 금지.** `/bts` 가 learnings 발췌를, `/bts-impl` controller 가 `DEVELOPMENT.md §1` · `DATA.md §1` 을 이미 실었다(T0/T1 은 구현이 인라인이라 `/bts` 가 직접 싣는다). Step 2 프롬프트에는 그 사본을 붙이고 파일을 다시 Read 하지 않는다.

## Step 1. PR diff 추출

```bash
cd .worktrees/<slug>
gh pr ready                    # Draft → Ready (리뷰 대상)
PR_DIFF=$(gh pr diff)

# 실측 티어 — 눈대중으로 적지 않는다. 선언과 나란히 게이트 2 요약에 싣는다(/bts §판정 ⑤).
# PROMOTION_NEEDED 가 나와도 자동 승격하지 않는다 — 사람이 결정한다.
git diff --name-only origin/main...HEAD \
  | node --experimental-strip-types scripts/workflow/detect-tier.ts --declared "$DECLARED_TIER"
```

CI 상태는 게이트 2 요약에 싣되 **대기는 여기서 하지 않는다** — 초록 확인의 정본 지점은 `/bts-merge` Step 1 이다.

**★젠킨스 빌드 결과를 요약에 필수로 싣는다 (2026-09-09~).**
`.husky/pre-push` 에서 백엔드·프론트 테스트를 걷어내 젠킨스로 옮겼으므로(P5), **로컬 훅은
백엔드를 검증하지 않는다.** 푸시가 막히지 않아 깨진 커밋이 원격에 올라갈 수 있고, 그 사각을
받는 자리가 게이트 2 다 — Maxi 가 그 정보를 못 보면 사각이 그대로 남는다.

```bash
node --experimental-strip-types scripts/workflow/jenkins-build-status.ts
echo "EXIT=$?"   # 0 초록 · 1 빨강 · 2 판정 불가
```

**종료 코드 2(판정 불가)를 「빨간불이 아니니 괜찮다」로 요약하지 않는다.** 빌드 없음 · 도는 중 ·
**다른 브랜치의 빌드** · 젠킨스 무응답이 전부 2 다. 그 경우 요약에 「젠킨스 판정 불가 — 사유」와
로컬 전량 실행 여부를 나란히 적는다.

## Step 2. 리뷰 2종 병렬 발행 (한 응답에)

```
Agent({
  subagent_type: "code-reviewer",
  description: "<slug> PR 코드 리뷰",
  prompt: """
BTS PR 리뷰. 아래 주입본이 판정 근거이며 그 밖의 기준을 임의로 만들지 않는다.

작업 영역. <type> · plan 파일. docs/plans/<date>-<slug>.md · PR diff. <PR_DIFF>

**주입 범위(controller 가 인라인 첨부).** `DEVELOPMENT.md §1` 절대 규칙 전문 + `DATA.md §1` 데이터 무결성
원칙. auth/migration 이면 §1.1 보안 · §1.2 데이터 무결성 추가 강조. learnings 는 선별 발췌 + 헤딩 인덱스만 —
**본문 전량 인라인 금지**, 그 재복제가 이 하네스의 최대 컨텍스트 낭비였다.

**ui 면제 각주.** 절대 규칙의 TDD red-first 조항은 **ui 시각 검증 트랙에 면제**된다(`DEVELOPMENT.md §1` 의
티어별 단서). `type == "ui"` 표면 수정에 red-first 미준수를 BLOCKER 로 매기지 않는다 — 그 트랙의 기준은
「동반 `test:` 커밋 + 기존 E2E 동반 실행 + 브라우저 눈확인」이고, 로직이 섞인 부분만 TDD 로 본다.

**하네스 diff 고정 점검 2줄**(어느 판별식도 안 보는 단독 배선 — 사라져도 red 가 안 난다).
`bts-merge` 의 `verify-master-plan.sh` 호출문 · `bts-start` 의 `classify-task.ts --cache` 호출문이 그대로 있는가.

보고. PASS / CONCERNS(수정 권장) / BLOCKER(수정 필수).
"""
})

Skill({
  skill: "review",
  args: "PR pre-landing 리뷰. 작업 디렉토리. .worktrees/<slug> (이 디렉토리에서 실행 — 프로젝트 체크리스트 .claude/skills/review/checklist.md가 worktree 루트 기준으로 존재). base는 origin/main. 글로벌 카테고리(SQL 안전성, race, LLM 신뢰 경계 등)에 더해 체크리스트의 BTS 고유 항목(Pass 0 PRE_EXISTING 판별, init_codegen 미러, 도메인 예외 핸들러 스코프, @JsonInclude↔Zod 정합) 적용."
})
```

두 렌즈는 관점이 다르다 — 에이전트는 **절대 규칙 정합**, `/review` 는 **구조·안전성**. 순차로 나누면 왕복만 2배가 된다.

## Step 3. ceo 추가 리뷰 (**티어 축 또는 타입 축**)

다음 **둘 중 하나라도** 참이면 `/plan-ceo-review` 를 PR 단위로 한 번 더 부른다.

- **티어 축** — 실측 티어가 `T3` 다 (`MIGRATION` · `SHARED_KERNEL` · `TOPOLOGY` 표면).
- **타입 축** — `classify.type ∈ {auth, migration}` 이다.

데이터·보안 폭발 반경이 커서 plan 통과만으로 정책 약속이 담보되지 않는 자리다.

**★두 축을 모두 본다.** 종전에는 타입 축만 봤다. 그런데 `CLAUDE.md` 티어표와 `/bts` 절차표는
「T3 = 리뷰 2 + ceo」를 **티어**로 약속한다. 티어와 타입은 서로 독립이므로
(`scripts/workflow/types.ts` 가 명시), `shared-kernel` 이나 `settings.gradle.kts` 를 고쳐 T3 가
됐는데 타입이 `backend` 로 분류되면 **가장 위험한 변경에 가장 높은 리뷰가 안 붙었다**
(2026-09-04 진단). 축이 하나면 약속과 실행이 갈린다.

실측 티어는 Step 1 의 `detect-tier.ts` 출력을 쓴다 — 선언 티어가 아니다.

```
Skill({ skill: "plan-ceo-review", args: "이 PR 이 plan 단계 ceo 리뷰의 제약을 지키는지 검증. plan. docs/plans/<date>-<slug>.md · PR diff. <PR_DIFF>" })
```

## Step 4. 결과 통합

렌즈별 결과를 PR 본문 `## 리뷰 결과 (PR 단위)` 에 append 한다 — 렌즈 이름 · PASS/CONCERNS/BLOCKER · 항목별 한 줄. **BLOCKER 는 합산한 뒤 한 번에** 판정한다.

## Step 5. 티어별 리뷰 종수

**종수 정본은 `/bts` 의 티어별 절차 표**다. 사본을 여기 두지 않는다 — 이 스킬은 「어느 렌즈를 그 자리에 쓰는가」만 정한다. 렌즈 호출이 실패해도 **조용히 종수를 줄이지 않는다.** 부재를 요약에 적고 진행 여부를 `AskUserQuestion` 으로 묻는다.

| 티어 | 이 스킬이 발행하는 렌즈 |
|---|---|
| T0 · T1 | `code-reviewer` 1종 — **하한이다. 0종으로 내려가지 않는다** |
| T2 | `code-reviewer` + `/review` 2종 (+ auth/migration 이면 Step 3 ceo) |
| **T3** | `code-reviewer` + `/review` 2종 + **ceo 항상** (티어 축) |

## Step 6. 게이트 2 요약 → `/bts` 반환

**보고 서식은 [`docs/rules/output-format.md`](../../../docs/rules/output-format.md) 를 따른다.** Maxi 가 승인을 판단하는 지점이므로 첫 줄은 판정(승인 가능 / 차단 사유)이고, 지적은 심각도 순 최대 5건까지 번호를 매긴다. 처음 나온 약어(ReDoS·OCC·nullable 등)는 괄호 풀이 필수. 마지막 줄은 Maxi 가 2분 안에 할 수 있는 다음 행동 하나.

```
🛑 게이트 2 — Maxi 검토 부탁드립니다.

✅ 한 줄  <검토 결과를 비전문가 한 문장으로>
💡 의미  <그대로 머지해도 되는지 / 고칠지 판단 재료>
🔧 기술 상세 (안 봐도 됨)
   PR. <url> · <files changed>, +<add>/-<del>
   젠킨스. <초록 #N / 빨강 #N / 판정 불가 — 사유> · <빌드 URL>
   선언 티어 <T?> / 실측 티어 <T?> · UNMAPPED. <경로 또는 없음> · 건너뛴 단계. <목록>
   렌즈별 결과 각 1줄 · CONCERNS·BLOCKER 는 쉬운 문장 + (용어 괄호 풀이)

1. 승인 → [7] /bts-merge   2. 수정 후 재리뷰 → [5] 재호출   3. 보류
```

`AskUserQuestion` 으로 응답을 받아 `/bts` 에 돌려준다. **머지 절차는 이 스킬이 하지 않는다** — 컨트롤러가 `/bts-merge` 를 부른다.

## 실패 / 엣지 케이스

- **두 렌즈 결과 충돌**(에이전트 PASS · `/review` BLOCKER). BLOCKER 가 항상 우선. 충돌 사실을 요약에 그대로 적는다
- **CONCERNS 3회 반복**. 작업 자체에 근본 문제 — `/bts-spec` loop back 제안
- **"더 작게 쪼개야 한다" 보고**. `AskUserQuestion` 으로 분할 옵션 제시(이 PR 머지 + 후속 PR vs 이 PR 분할)
- **CI 가 빨강인데 리뷰는 PASS**. 승인 옵션을 내지 않는다. CI 를 먼저 초록으로 만든다
