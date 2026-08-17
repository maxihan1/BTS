---
name: bts-review-plan
description: Called by /bts step 4 — routes the finished plan to review lenses. T2/T3 only. Never invoke directly.
---

# /bts-review-plan

체인 [4]. 작성된 plan 을 **타입별 리뷰 렌즈**로 검증하고 게이트 1 로 넘긴다.
**T2/T3 만 진입한다** — T0/T1 은 이 단계도 게이트 1 도 없다.

## 선행 읽기

- `docs/plans/<date>-<slug>.md` — 작성된 plan 전체 (`## 도메인 정리` 의 ADR 링크로 충분 · ADR 재검색 금지)

## Step 1. classify 로드

```bash
TYPE=$(jq -r '.type' .bts-cache/classify.json)
TIER=$(jq -r '.tier' .bts-cache/classify.json)
```

## Step 2. 타입별 리뷰 렌즈 분기

**이 표는 티어 무조건이다.** 진입 티어(T2/T3)는 이 스킬 머리에서 한 번 선언하고 행은 티어를 다시 적지 않는다 — `type` 은 제목에서, `tier` 는 변경 경로에서 나오는 **독립 축**이라 「이 조합은 안 온다」는 주장이 성립하지 않는다. 종전 표는 `ui` 행과 `{bugfix, chore, qa}` 행을 「이 단계에 안 온다」는 전제로 비워 뒀는데, PR #387 이 `chore`@T2 로 실제 도달해 그 전제가 거짓임이 실측됐다(장부 항목 34).

**리뷰 종수는 이 표의 행이 정한다.** `/bts` 티어별 절차 표의 「독립 리뷰 종수」는 체인 [6] `bts-codereview` 의 값이지 이 단계의 값이 아니다 — 이 단계를 돌지 않는 T0/T1 에도 그 행에 값이 있는 것이 근거다.

| 조건 | 리뷰 렌즈 |
|---|---|
| `TYPE == "auth"` 또는 `TYPE == "migration"` | `/plan-eng-review` + `/plan-ceo-review` (**한 응답에 병렬 발행**) |
| `TYPE == "ui"` | `/plan-design-review` + `/plan-eng-review` (**한 응답에 병렬 발행**) |
| `TYPE == "api"` | `/plan-eng-review` |
| `TYPE == "design"` | `/plan-design-review` |
| `TYPE == "backend"` | `/plan-eng-review` (UI 포함 시 `/plan-design-review` 추가) |
| `TYPE == "feature"` | `/plan-eng-review` |
| `TYPE ∈ {bugfix, chore, qa}` | `/plan-eng-review` |
| **그 외 (표에 없는 타입)** | `/plan-eng-review` + **Maxi 확인** — 분기 미정의 상태로 조용히 지나가지 않는다 |

> **행을 지우지도, 비우지도 말 것.** `scripts/workflow/skill-type-coverage.test.ts` 가 세 가지를 CI 에서 강제한다. ① 이 표의 타입 토큰과 `types.ts` 의 `TaskType` 유니온의 **차집합 0** ② 모든 타입 행이 **리뷰 렌즈를 최소 1종** 지시할 것 ③ 행이 **티어·도달 가능성 주장을 담지 않을 것**. 티어별로 렌즈를 가르고 싶으면 그 판별식을 먼저 의도적으로 고쳐라 — 표만 고치면 빨간불이 된다. 행 구성 근거(backend 행 누락 사고)는 그 판별식 헤더 주석 참조.

## Step 3. 리뷰 호출 (병렬)

**두 렌즈가 걸리면 한 응답에 함께 발행한다.** 순차로 나누면 왕복이 2배가 되고 얻는 것이 없다.

```
Skill({ skill: "plan-eng-review", args: "docs/plans/<date>-<slug>.md" })
Skill({ skill: "plan-ceo-review", args: "docs/plans/<date>-<slug>.md" })
```

각 리뷰는 plan 파일의 `## 리뷰 결과` 에 append 한다. BLOCKER 는 **합산한 뒤 한 번에** 판정한다.

## Step 4. BLOCKER 처리

BLOCKER 가 하나라도 있으면 **중단**하고 Maxi 개입을 요청한다 — ① plan 수정 후 `/bts-plan` 재호출 ② 위험 인정하고 진행 ③ 작업 중단.
**`auth`/`migration` 의 BLOCKER 는 무시 옵션이 없다**(절대 규칙).

## Step 5. plan 갱신 + 게이트 1 진입

`## 리뷰 결과` 에 렌즈별 결과(통과 / 주의 / BLOCKER)를 기록하고, `/bts` 컨트롤러에 게이트 1 요약을 반환한다.

```
🔄 [4/7] /bts-review-plan
   ├─ 타입: feature (T2) → /plan-eng-review 1종
   ├─ 결과: ✅ 통과 (주의 1건 — pgmq 멱등성 검증 권장) · BLOCKER 0
   └─ 다음. 🛑 게이트 1 (Maxi 검토)
```

## 실패 / 엣지 케이스

- **여러 렌즈가 모두 BLOCKER**. 작업 자체를 재검토 — `/bts-spec` loop back 또는 분할 제안
- **리뷰 스킬 호출 실패**. 렌즈 부재를 게이트 1 요약에 명시하고 진행 여부를 Maxi 에게 묻는다 (조용히 0종으로 통과시키지 않는다)
