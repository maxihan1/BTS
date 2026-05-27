---
name: bts-plan
description: Use when a written spec is ready and needs to be decomposed into bite-sized TDD tasks before implementation.
---

# /bts-plan

스펙을 bite-sized task 목록으로 분해. 각 task는 TDD 사이클 단위.

## 선행 읽기 (필수)

1. `docs/plans/<date>-<slug>.md` — 직전 단계 결과 (`## 도메인 정리`, `## 스펙`)
2. `docs/specs/<date>-<slug>.md` — 세부 스펙 본문

**`learnings.md`는 `/bts` 진입 시 이미 로드됨 (Phase B). 컨텍스트 재사용, 재로드 금지.**

## 절차

### Step 1. writing-plans 호출

```
Skill({
  skill: "superpowers:writing-plans",
  args: "다음 스펙을 TDD 기반 task로 분해. 각 task는 (1) RED phase 실패 테스트, (2) GREEN phase 최소 구현, (3) REFACTOR phase 정리로 세분. 각 task 2-5분 단위. 각 task 상단에 메타 블록(agent / files / depends-on) 필수 기재 — bts-impl이 wave 계산에 사용. 스펙: docs/specs/<date>-<slug>.md. learnings에서 관련 사고: <grep 결과>."
})
```

### Step 2. plan 형식 검증

writing-plans 산출물이 BTS의 다음 형식을 따르는지 확인.

```markdown
## Plan

### Task 1. <한 줄 제목>

**메타**.
- agent: `backend-engineer` (생략 시 plan 파일 상단 `agent:` 기본값 사용)
- files: [`backend/modules/issue-tracking/src/main/.../MentionParser.kt`, `backend/modules/issue-tracking/src/test/.../MentionParserTest.kt`]
- depends-on: []

**RED**:
- 파일: `backend/modules/issue-tracking/src/test/.../MentionParserTest.kt`
- 테스트:
  ```kotlin
  @Test fun `parses @username from comment text`() { ... }
  ```
- 실패 메시지 (예상): `MentionParser` 클래스 없음

**GREEN**:
- 파일: `backend/modules/issue-tracking/src/main/.../MentionParser.kt`
- 최소 구현 (정규식 1줄)

**REFACTOR**:
- 정규식을 상수로 추출 + KDoc

**검증**: `./gradlew :backend:issue-tracking:test --tests MentionParserTest`

### Task 2. <제목>

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/.../MentionNotificationService.kt`, ...]
- depends-on: [1]   # Task 1의 MentionParser API를 호출하므로 직렬

...
```

**메타 블록 규칙**.
- `files`. 이 task가 **신규 작성 + 수정**하는 모든 파일 (RED/GREEN/REFACTOR 합본). 절대 경로 또는 repo 루트 기준 상대 경로
- `depends-on`. 이 task가 시작하기 전에 완료돼야 하는 선행 task 번호 배열. 코드 의존성만 (커밋 순서 의존성 X). 비어 있으면 `[]`
- `agent`. 생략 가능. 생략 시 plan 파일 헤더의 `agent:` 또는 classify-task agent 사용
- **파일 겹침은 자동 직렬화**. 두 task의 `files` 교집합이 있으면 `depends-on` 미선언이어도 bts-impl이 같은 wave에 두지 않음

형식 미준수 시 writing-plans 재호출 (메타 블록 강제 가이드 prompt 주입).

### Step 3. task 수 카운트 (이후 분기에 사용)

```bash
TASK_COUNT=$(grep -cE '^### Task [0-9]+[.:]' "docs/plans/<date>-<slug>.md")

# JSON 머지 (>> append 금지 — invalid JSON 됨)
# jq로 task_count 필드만 교체. `jq` 없으면 jq 설치 또는 Node oneliner 대안.
jq --argjson tc "$TASK_COUNT" '.task_count = $tc' \
  .bts-cache/classify.json > .bts-cache/classify.json.tmp \
  && mv .bts-cache/classify.json.tmp .bts-cache/classify.json
```

### Step 4. plan 파일 갱신

worktree plan 파일의 `## Plan` 섹션이 위 형식으로 채워짐.

추가로 다음 메타 정보 함께 기록.

```markdown
## Plan 메타

- task 수: 4
- 예상 시간: task × 3분 = 약 12분 (직렬 기준), 병렬 wave 적용 시 약 6분 (예상 wave 수: 2)
- TDD 강제: yes
- 병렬 dispatch: bts-impl이 task 메타(depends-on + files)로 wave 계산
- 추가 검증: typecheck, ktlint, detekt, vitest, playwright (qa-engineer)
```

### Step 5. 다음 스킬 체이닝

자동으로 `/bts-review-plan` 호출.

## 출력 형식

```
🔄 [4/7] /bts-plan
   ├─ writing-plans → docs/plans/2026-05-19-issue-mention-notify.md
   ├─ task 수: 4 (각 TDD 사이클)
   ├─ 예상 시간: 약 12분
   └─ 다음. /bts-review-plan
```

## Fast-track 스킵 조건

`classify.type == "chore"`이고 변경이 명확히 작음 (제목에서 추정) 시 스킵. classify-task는 `chore`/`docs`/`style`/`perf`/`test` 접두사를 모두 `chore` 타입으로 통합하므로 별도 `docs` 분기 불필요.

이유. config 1줄 수정 / 오타 / 문서 한 줄 변경은 plan 분해 자체가 비용. 단, `bugfix`는 TDD 강제하므로 plan 작성 필요 (스킵 안 함).

## 실패 / 엣지 케이스

- **task 수 0 또는 1**. writing-plans가 너무 단순하게 잡았거나, 스펙이 모호. `/bts-spec`으로 loop back (Phase A 재호출)
- **task 수 10 초과**. 작업 너무 큼. Maxi에게 "이 작업을 N개 PR로 쪼갤까요?" AskUserQuestion
- **TDD 형식 미준수**. writing-plans가 RED/GREEN/REFACTOR 라벨을 빠뜨림. 재호출 (강제 가이드 prompt 주입)
- **메타 블록 누락**. task 상단 `agent / files / depends-on` 한 줄이라도 빠지면 bts-impl wave 계산 불가 → 재호출. depends-on이 0이면 빈 배열 `[]` 명시
- **depends-on 순환 참조**. T1 → T2 → T1 같은 cycle 감지 시 writing-plans 재호출 (cycle 그래프 출력 첨부)
