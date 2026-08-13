---
name: bts-plan
description: Called by /bts step 3 — decomposes the written spec into TDD tasks. Runs on T2/T3 only. Never invoke directly.
---

# /bts-plan

체인 [3]. 스펙을 bite-sized task 로 분해한다. 각 task 가 TDD 사이클 1개.

## 선행 읽기

1. `docs/plans/<date>-<slug>.md` — `## 도메인 정리` · `## 스펙` (직전 단계 결과)
2. (T3) `docs/specs/<date>-<slug>.md` — 분리된 스펙 본문

**learnings 는 `/bts` 진입 시 이미 발췌 로드됐다. 재로드 금지** — 그 발췌를 task 근거로 인용한다.

## §1. 분해 규칙 (내재화 — 외부 스킬 호출 없음)

1. **task 1개 = RED(실패 테스트) → GREEN(최소 구현) → REFACTOR(정리)** 한 사이클.
2. **task 단위는 2~5분**. 그보다 크면 쪼개고, 작으면 합친다.
3. **메타 블록 3키(`agent` / `files` / `depends-on`)는 필수.** `bts-impl` wave 계산의 **유일한 입력**이라 하나라도 빠지면 병렬 dispatch 가 성립하지 않는다.
4. 관련 learnings 발췌를 각 task 의 **근거로 첨부**한다 — 회귀 사고가 있던 표면은 그 사고를 재현하는 테스트가 RED 가 된다.
5. **형식이 어긋나면 재호출하지 않고 그 자리에서 교정한다.** 재검증 루프를 돌리면 왕복만 늘고 결과가 같다.
6. Task 예시·서식 정본. [plan-format.md](plan-format.md)

## §2. 메타 계약

| 키 | 규칙 |
|---|---|
| `agent` | 생략 가능. 생략 시 plan 헤더의 `agent:` → classify agent 순으로 승계 |
| `files` | 이 task 가 **신규 작성 + 수정**하는 모든 파일(RED/GREEN/REFACTOR 합본). repo 루트 기준 상대 경로 |
| `depends-on` | 선행 task 번호 배열. **코드 의존성만**(커밋 순서 의존성은 아니다). 없으면 `[]` |
| 파일 겹침 | 두 task 의 `files` 교집합이 있으면 `depends-on` 미선언이어도 **자동 직렬화**된다 |
| ui 시각 트랙 | `**검증**:` 필드에 ① 관련 기존 E2E spec 목록(계약 §5 사전 grep 결과) ② 브라우저 눈확인 항목(라이트/다크)을 필수 기재. RED 라벨은 "동반 테스트" 명세로 읽는다 — red-first 순서 강제 없음 |

## §3. task 수 기록

```bash
TASK_COUNT=$(grep -cE '^### Task [0-9]+[.:]' "docs/plans/<date>-<slug>.md")

# JSON 머지 (>> append 금지 — invalid JSON 됨)
# Node 단독. jq·mv 를 쓰지 않는다 — settings.json deny 가 `Bash(mv:*)` 를 차단해
# 이 절차가 64일간 조용히 실패했다. renameSync 로 원자성은 그대로 유지된다.
node -e '
  const fs = require("fs");
  const n = Number(process.argv[1]);
  // 빈 값이 0 으로 조용히 통과하는 것을 막는다 — grep 이 0건이면 plan 형식이 깨진 것이다
  if (!Number.isInteger(n) || n < 1) throw new Error("task_count 가 비었거나 1 미만: " + process.argv[1]);
  const f = ".bts-cache/classify.json";
  const j = JSON.parse(fs.readFileSync(f, "utf8"));
  j.task_count = n;
  fs.writeFileSync(f + ".tmp", JSON.stringify(j, null, 2));
  fs.renameSync(f + ".tmp", f);
' "$TASK_COUNT"
```

**이 기록을 빼지 말 것.** `/bts` 의 소규모 게이트 정책이 이 값을 읽는다 — 지우면 그 정책이 조용히 무효가 된다.

## §4. plan 파일 갱신

`## Plan` 을 위 서식으로 채우고, 이어서 `## Plan 메타` 에 task 수 · 예상 wave 수 · 구현 규율(TDD | ui 시각 검증 트랙) · 추가 검증(typecheck · ktlint · detekt · vitest · playwright)을 적는다.

## 체이닝 · 출력 · 엣지

`/bts` 컨트롤러에 반환 → 컨트롤러가 `/bts-review-plan` 호출.

```
🔄 [3/7] /bts-plan
   ├─ task 수: 4 (각 TDD 사이클) · 예상 wave: 2
   └─ 다음. /bts-review-plan
```

- **task 수 0 또는 1**. 스펙이 모호하거나 분해가 과했다 → `/bts-spec` 으로 loop back
- **task 수 10 초과**. 작업이 너무 크다 → "N개 PR 로 쪼갤까요?" `AskUserQuestion`
- **메타 블록 누락 · RED/GREEN/REFACTOR 라벨 누락**. 그 자리에서 교정(§1-5)
- **`depends-on` 순환 참조**. cycle 그래프를 출력하고 그 자리에서 끊는다
