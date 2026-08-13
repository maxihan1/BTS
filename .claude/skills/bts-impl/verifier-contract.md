<!-- /bts-impl 2-C 의 spec-compliance-verifier 프롬프트 · 판정 기준 · 응답 형식 정본 -->
# spec-compliance-verifier 계약

`SKILL.md` Step 2-C 가 참조한다. **내용을 줄이지 말 것** — 증거 인용 요구를 한 줄이라도 빼면 verifier 가 추측으로 PASS 를 낸다.

## §1. controller 가 먼저 직접 수집한다 (task 당 1회, Bash)

verifier 에게 git 을 시키지 않는다. 시키면 「돌렸다고 말하고 안 돌린」 응답을 구분할 수 없다.

```
Bash({
  command: "cd .worktrees/<slug> && git log --reverse --pretty='%h %s' -- <plan 메타 files 공백 구분> && echo '---DIFF---' && git diff main...HEAD -- <plan 메타 files 공백 구분>"
})
```

수집 출력 예시 (verifier prompt 에 그대로 인라인).

```
a1b2c3d test: <slug> task-1 red
e4f5g6h feat: <slug> task-1 green
i7j8k9l refactor: <slug> task-1
---DIFF---
<unified diff>
```

## §2. verifier 병렬 호출 (한 응답에 여러 `Agent()`)

```
Agent({
  subagent_type: "general-purpose",
  description: "Task N — spec compliance",
  prompt: """
이 verifier는 **read-only 분석 전용**. 추가 git/Bash 명령 실행 금지. 아래 첨부 데이터로만 판정.

## git log + diff 출력 (controller가 수집, Task N 의 files 한정)
<여기에 §1의 출력 전체 inline 첨부>

## plan Task N 명세
<plan 파일의 Task N 섹션 전체 inline>

## 판정 기준 (셋 다 확인)

1. **TDD 순서**. 위 git log 출력에서 `test: <slug> task-N red` commit의 hash가
   `feat: <slug> task-N green` commit의 hash보다 먼저(위쪽 = 더 오래된)에 있는가?
   **응답에 두 commit hash를 직접 인용**하여 증거 제시.
2. **drift**. 첨부된 diff가 plan Task N의 RED/GREEN/REFACTOR 명세와 일치하는가? 불일치 항목 나열.
3. **선언 외 파일**. diff에 plan 메타 `files` 외 경로가 등장하는가? 있다면 정당한 사유 명시.

## 응답 형식 (필수, 증거 인용 없이는 PASS 무효)

PASS:
- TDD: test commit `<hash>` (<message>) → feat commit `<hash>` (<message>) 순서 확인됨.
- drift: 없음.
- 선언 외 파일: 없음.

DRIFT:
- TDD: (확인)
- drift 항목: 1. <항목>, 2. <항목>
- 선언 외 파일: <목록 또는 없음>

TDD_VIOLATION:
- 증거: git log 출력에 `test:` commit 없음 OR `feat:` commit이 `test:` commit보다 먼저 등장.
- 인용: <git log 해당 줄 그대로>.

**증거 commit hash 인용 없는 PASS 응답은 controller가 거절하고 verifier 재dispatch**.
"""
})
```

controller 는 응답을 받은 뒤 PASS 안에 **실제 hash 문자열**(`a1b2c3d` 형태)이 있는지 직접 확인한다. 누락이면 `TDD_VIOLATION` 으로 간주한다.

## §3. ui 시각 검증 트랙 변형

ui 트랙 task 는 판정 기준 1(TDD 순서)을 다음 셋으로 교체한다.

① `test:` 커밋이 **존재**하는가 (순서 무관)
② implementer 보고에 **기존 E2E 실행 로그**가 인용돼 있는가
③ **눈확인 관찰 요지**가 인용돼 있는가

셋 중 하나라도 없으면 PASS 무효 — `TDD_VIOLATION` 대신 `TRACK_VIOLATION` 으로 보고하되 처리 동작은 같다.

## §4. 응답 처리

| verifier 응답 | 동작 |
|---|---|
| `PASS` | plan 의 Task N 체크박스 `[x]` → 해당 task wave 졸업 |
| `DRIFT` | drift 항목을 implementer 에 전달 → 해당 task 단일 재dispatch |
| `TDD_VIOLATION` / `TRACK_VIOLATION` | BLOCKED 복구 규율 4항목을 채워 implementer 재dispatch |

wave 내 모든 task 가 `PASS` 면 다음 wave 로 진입한다.
