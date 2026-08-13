<!-- /bts-plan 이 만드는 Task 블록의 서식 정본 (예시 포함) -->
# plan Task 서식

`SKILL.md` §1-6 이 참조한다. 메타 3키 규칙 자체는 `SKILL.md` §2 가 정본이고, 여기엔 **모양**만 둔다.

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
```

## ui 시각 검증 트랙 변형

`type == ui` 인 task 는 `**RED**` 를 「동반 테스트」 명세로 읽고, `**검증**:` 에 두 줄을 더 적는다.

```markdown
**검증**:
- 기존 E2E: `apps/web/e2e/issue-detail.spec.ts` · `apps/web/e2e/backlog.spec.ts` (계약 §5 사전 grep 결과)
- 눈확인: 이슈 상세 헤더 정렬 — 라이트/다크 양쪽
```

## 헤딩 규칙

`### Task N.` 또는 `### Task N:` 만 쓴다. `SKILL.md` §3 의 `grep -cE '^### Task [0-9]+[.:]'` 가
이 서식으로 task 수를 세므로, 다른 헤딩 깊이나 번호 서식을 쓰면 **0건이 나와 그 자리에서 실패**한다.
