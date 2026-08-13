<!-- /bts-impl 2-A 가 implementer 에게 던지는 dispatch 프롬프트 템플릿 2종 -->
# implementer dispatch 프롬프트

`SKILL.md` Step 2-A 가 참조한다. wave 의 모든 task 를 **한 응답에** 발행한다.

## §1. TDD 트랙 (기본)

```
Agent({
  subagent_type: "<task.agent ?? plan_header.agent ?? 'backend-engineer'>",
  description: "Task N — <task 제목>",
  prompt: """
plan 파일의 Task N을 구현. 작업 디렉토리: .worktrees/<slug>.

**TDD 강제. 다음 순서 절대 지킬 것.**

1. RED. plan의 RED phase 테스트를 작성 → 즉시 커밋 (`test: <slug> task-N red`)
2. 테스트 실행해서 실패 확인. 실패 출력 전체를 보고에 첨부
3. GREEN. plan의 GREEN phase 최소 구현 → 커밋 (`feat: <slug> task-N green`)
4. 테스트 실행해서 통과 확인. 통과 출력 첨부
5. REFACTOR. plan의 REFACTOR phase 정리 → 커밋 (`refactor: <slug> task-N`)
6. 모든 단계에서 절대 규칙 (DEVELOPMENT.md, DATA.md) 준수

**RED 단계 건너뛰면 BLOCKED 처리됨.**

**파일 범위 제약 (병렬 dispatch 안전성).**
이 task가 건드릴 파일은 plan 메타의 `files`에 선언된 것에 한정.
선언 외 파일 수정 시 BLOCKED. 같은 wave의 다른 task와 worktree를 공유하므로
선언 외 파일 수정은 race / drift 위험.

작업 위치: .worktrees/<slug> 절대 경로 안에서만 Edit/Write.
허용 파일: <plan 메타 files 인라인 주입>.
참조 파일: DEVELOPMENT.md, DATA.md, Maxi_wiki/BTS/domain/<bc>.md.

**병렬 wave 환경 규약.**
<docs/rules/wave-protocol.md 본문 인라인 주입 — 공통 6조 + 이 task 역할의 보고 형식 행>

상태 보고. DONE / DONE_WITH_CONCERNS / NEEDS_CONTEXT / BLOCKED.
DONE/DONE_WITH_CONCERNS 보고 시 RED/GREEN 각 commit hash 명시, REFACTOR는 있으면 함께
(controller가 git log와 대조).
"""
})
```

## §2. ui 시각 검증 트랙 (red-first 면제)

§1 의 「TDD 강제 6단계」 블록만 아래로 **교체**한다. 나머지(파일 범위 제약 · wave 규약 · 상태 보고)는 그대로 둔다.

```
**시각 검증 트랙 (red-first 면제).**
1. jira-parity-contract.md §5 사전 grep — 수정 표면의 기존 E2E/유닛 어서션 식별, 결과를 보고에 첨부
2. 구현 → 커밋 (`feat: <slug> task-N`)
3. 동반 테스트 작성/갱신 → 커밋 (`test: <slug> task-N`) — 순서 무관이나 test 커밋 필수
4. 1에서 식별한 기존 E2E 동반 실행 — 통과 로그 첨부. 즉사 계약(§2) 문자열 훼손 여부 확인
5. 브라우저 눈확인 (§6) — 라이트/다크 양쪽 관찰 요지를 보고에 포함
DONE 보고: feat/test 각 commit hash + E2E 실행 로그 + 눈확인 요지.
```

## §3. BLOCKED 재dispatch 시 추가 블록

`SKILL.md` 2-B 의 복구 규율을 프롬프트 머리에 붙인다 — 네 항목을 비운 채 다시 던지지 않는다.

```
**직전 시도의 진단.**
- 시도한 것: <무엇을 했나>
- 관찰한 것: <에러·로그 원문 인용>
- 가설 2개: <①> / <②>
- 필요한 정보: <무엇을 확인하면 갈림이 닫히나>
```
