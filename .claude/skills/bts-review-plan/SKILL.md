---
name: bts-review-plan
description: 작업 타입에 따라 plan 리뷰 체인 자동 실행. auth/migration은 plan-eng+plan-ceo, ui는 plan-design, api는 plan-eng+plan-devex, feature(task≥3)는 /autoplan, 그 외 feature는 plan-eng. /bts-plan 이후 자동 호출.
---

# /bts-review-plan

작성된 plan을 타입별 리뷰 체인으로 검증. 마지막 사용자 게이트 직전 단계.

## 선행 읽기

- `docs/plans/<date>-<slug>.md` — 작성된 plan 전체
- `Maxi_wiki/BTS/decisions/` — 관련 ADR

## 절차

### Step 1. classify + task_count 로드

```bash
TYPE=$(jq -r '.type' .bts-cache/classify.json)
TASK_COUNT=$(jq -r '.task_count' .bts-cache/classify.json)
```

### Step 2. 타입별 리뷰 체인 분기

| 조건 | 리뷰 체인 |
|---|---|
| `TYPE == "auth"` 또는 `TYPE == "migration"` | `/plan-eng-review` → `/plan-ceo-review` |
| `TYPE == "ui"` | `/plan-design-review` |
| `TYPE == "api"` | `/plan-eng-review` → `/plan-devex-review` |
| `TYPE == "feature"` AND `TASK_COUNT >= 3` | `/autoplan` |
| `TYPE == "feature"` AND `TASK_COUNT < 3` | `/plan-eng-review` |
| `TYPE == "design"` | `/plan-design-review` |
| `TYPE ∈ {bugfix, chore, qa}` | **skip** (fast-track) |

### Step 3. 리뷰 호출 (순차)

각 리뷰 스킬은 plan 파일을 읽고 결과를 동일 파일의 `## 리뷰 결과` 섹션에 append.

```
Skill({ skill: "plan-eng-review", args: "docs/plans/<date>-<slug>.md" })
# 결과 확인 → BLOCKER 있으면 중단
Skill({ skill: "plan-ceo-review", args: "docs/plans/<date>-<slug>.md" })
```

### Step 4. BLOCKER 처리

각 리뷰가 BLOCKER를 표시하면 **중단**. 사용자 개입 필수.

```
🛑 plan-eng-review BLOCKER:
- 트랜잭션 경계 누락 (DEVELOPMENT.md §1.4 위반)

다음 옵션:
1. plan을 수정해서 트랜잭션 명시 → /bts-plan 재호출
2. BLOCKER를 무시하고 진행 (위험, Maxi 확인 필수)
3. 작업 중단
```

`auth`/`migration` 작업의 BLOCKER는 **무시 옵션 없음** (절대 규칙).

### Step 5. /autoplan 사용 시 특별 처리

`/autoplan`은 4종 리뷰를 자동 실행 + 6개 결정 원칙으로 자동 판정. 결과가 "taste decision"이면 Maxi에게 위임.

```
Skill({ skill: "autoplan", args: "docs/plans/<date>-<slug>.md" })
# 결과:
# - 자동 결정: N건
# - taste decision (Maxi 결정 필요): M건
```

taste decision은 사용자 게이트 1 직전에 AskUserQuestion으로 표시.

### Step 6. plan 파일 갱신

```markdown
## 리뷰 결과

### plan-eng-review (2026-05-19)
- ✅ 통과: 트랜잭션 경계 명시됨
- ⚠️ 주의: pgmq 큐 발사 부분의 멱등성 검증 추가 권장
- BLOCKER: 없음

### plan-ceo-review (해당 시)
- (생략)
```

### Step 7. 사용자 게이트 1 진입

게이트 1을 위한 요약 출력.

```
🛑 게이트 1 — Maxi 검토 부탁드립니다.

산출물:
- 도메인 정리: docs/plans/.../#도메인-정리
- 스펙: docs/specs/2026-05-19-issue-mention-notify.md
- Plan: docs/plans/2026-05-19-issue-mention-notify.md (4 tasks)
- 리뷰 결과:
  - plan-eng-review ✅ (주의 1건)
  - plan-ceo-review ✅
  - autoplan: 자동 결정 8건, taste decision 0건

다음 옵션:
1. 승인 → /bts-impl 진입
2. plan 수정 요청 → 어느 섹션?
3. 작업 중단
```

AskUserQuestion으로 응답 수집.

## 출력 형식

```
🔄 [5/7] /bts-review-plan
   ├─ 타입: feature (task=4) → /autoplan
   ├─ /autoplan: 자동 결정 8건, taste 0건
   └─ 다음. 게이트 1 (Maxi 검토)
```

## 실패 / 엣지 케이스

- **여러 리뷰가 모두 BLOCKER**. 작업 자체를 재검토. office-hours 재호출 또는 작업 분할 제안
- **autoplan이 작업을 "분할 권장"으로 판정**. AskUserQuestion. yes → `/bts-plan` 재호출 (분할 prompt), no → 그대로 진행 (위험 인정)
- **`/plan-devex-review`가 API 호환성 깨짐 발견**. v1/v2 분리 패턴 권장 → plan에 반영
