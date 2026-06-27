# FR-MN-01 D6/D7 — 본문 @멘션 시각 강조 렌더링 + 멘션→Inbox 도착 E2E

> slug: fr-mn-01-d6-d7-mention-ui
> type: ui
> agent: frontend-engineer (D6) + qa-engineer (D7 E2E)
> 생성: 2026-06-27

## Brief

**원문**. "fr-mn-01 d6 d7 진행하자"

FR-MN-01(본문/댓글 @멘션 + 즉시 알림)의 D1~D5(백엔드 발행)는 PR #114로 완료. D6(멘션 렌더링)·D7(Inbox 도착 E2E)은 당시 "알림 전달(FR-NT)·Inbox(FR-UX-03) 인프라 부재"로 deferred됐다. 그 선행 FR이 모두 완료되어 이제 진행 가능.

**핵심 발견 (코드 확인, 2026-06-27)**. 백엔드 멘션→알림→Inbox 파이프라인은 **이미 완성**.
- `NotificationWorker.kt:357` — `ISSUE_MENTIONED` → "…에서 멘션되었습니다" 알림 생성
- `EventRecipientResolver.kt:147` — `RecipientRole.MENTIONED` → `mentionedUserIds`를 수신자로 해석
- `V403__seed_mention_policy.sql` — `issue.mentioned` MENTIONED×IN_APP 정책 시드 (FR-NT-02에서 추가)
- Inbox는 notifications 테이블 확장 (FR-UX-03, #186/#187)

→ **순수 프론트엔드 작업**. FR-MN-02 자동완성(#158)과 동일하게 백엔드 신규 0.

**범위**.
- D6. 본문(description) 렌더링 시 `@username` 시각 강조 (frontend-engineer)
- D7. "본문에 나를 @멘션 → Inbox에 알림 도착" E2E (qa-engineer)

**classify**. 원래 type=qa·agent=qa-engineer로 오판(E2E/Inbox 키워드) → FR-MN-02 선례대로 type=ui·agent=frontend-engineer 수동 조정.

## 도메인 정리

- **BC**: issue-tracking (프론트 표현 계층)
- **영향 엔티티**: 신규 0. `Issue.description`(기존) 읽기 표현만.
- **새 용어**: 0. "멘션"은 기존 통용 용어 (glossary "그룹 멘션" 항목에 이미 등장). 새 용어 도입 없음.
- **기존 결정 충돌**: 없음.
- **관련 ADR**: 멘션 직접 ADR 없음. notification 관련 ADR 2개(`2026-06-11-notification-policy-bc-bootstrap`, `2026-06-12-notification-inapp-channel-delivery`)는 백엔드 전달 결정이라 D6/D7 프론트와 무충돌.
- **작업 대상 파일**:
  - D6 — `apps/web/src/components/issue/IssueDescription.tsx` (본문 렌더링 컴포넌트, FR-IS-04 Write/Preview 마크다운)
  - D7 — `apps/web/e2e/` 신규 spec (멘션→Inbox 도착)
- **참고 선례**: FR-MN-02 자동완성(#158, `useMentionAutocomplete`/`MentionDropdown`) — 동일 textarea 멘션 영역, 입력측. D6은 출력(렌더링)측.
- grill-with-docs 스킵 사유: 새 도메인 개념 0, 기존 멘션 개념의 프론트 표현 계층 추가에 한정.

## 스펙

전체 스펙. [docs/specs/2026-06-27-fr-mn-01-d6-d7-mention-ui.md](../specs/2026-06-27-fr-mn-01-d6-d7-mention-ui.md)

핵심 결정 요약.
- **강조 위치 = 백엔드 MarkdownRenderer (Maxi 확정 옵션 A)**. `@username` → `<span class="mention">`, 정화 allowlist에 `span[class=mention]` 추가. same-BC view layer라 이 PR 처리.
- 마크업 규칙 = MentionParser 추출 규칙 일치(코드/링크/이메일/`@@` 제외). flexmark 인라인 확장 우선 검토(코드/링크 자동 제외).
- **⚠️ 결정3 (게이트1 재검토)**. 실존 검증 안 함 — 형식 기반 강조(renderSafe 무상태 유지). @typo도 강조됨(알림은 발행측 실존검증). 대안=renderSafe에 사용자 조회 결합(복잡, 미채택).
- 프론트 = `.mention` CSS만(IssueDescription dangerouslySetInnerHTML 불변). PDF 자동 반영.
- D7 = 멘션→Inbox 도착 E2E. ground-truth는 백엔드 통합테스트, E2E는 프론트 도착 UI.

## Brainstorming Check

✅ 통과 (직접 sanity check 1회). gap 4건 보강(NFR2 부분집합·D7 분담·클릭없음·self멘션). plan 이관 실증 — flexmark 확장 구현 가능성 / 멘션 정규식 단일 출처.

## Plan 실증 결과 (flexmark 확장 가능성)

- flexmark **0.64.8 full 아티팩트** — `InlineParserExtension` API 포함, 추가 의존성 0.
- jsoup은 클래스패스에 **없음** → 렌더 후 텍스트노드 처리(대안)는 신규 의존성 필요 → DEVELOPMENT.md §17 위반 → **기각**.
- **확정**. flexmark 인라인 확장(`@` 트리거 → Mention 노드 → NodeRenderer span). 코드스팬/코드블록/링크는 flexmark가 별도 노드로 분리 → 인라인 파서 미작동 → **코드/링크 자동 제외**(EC1/EC2/EC6 구조적 보장).
- 멘션 정규식 단일 출처 = `MentionParser.MENTION_PATTERN`(또는 username 부분 패턴)을 공유 노출(drift 차단).

## Plan

### Task 1. (backend) flexmark 멘션 인라인 확장 + MarkdownRenderer 마크업 + 정화 허용

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/markdown/MentionExtension.kt`(신규), `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/markdown/MarkdownRenderer.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/mention/MentionParser.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/markdown/MarkdownRendererMentionTest.kt`(신규)]
- depends-on: []

**RED** (`MarkdownRendererMentionTest.kt` — renderSafe 레벨 통합):
```kotlin
@Test fun `marks up @username as span mention`()        // S1: @alice → <span class="mention">@alice</span>
@Test fun `does not mark up mention inside code span`()  // EC1
@Test fun `does not mark up mention inside fenced block`()// EC2
@Test fun `does not mark up email at sign`()             // EC3: user@example.com
@Test fun `does not mark up double at`()                 // EC4: @@bob
@Test fun `stops at punctuation boundary`()              // EC5: @alice. → @alice + .
@Test fun `sanitizes raw span class injection`()         // EC7: raw <span> 입력 → 텍스트화
@Test fun `rejects compound class value`()               // EC8: class="mention evil" 거부
@Test fun `rendered mentions are subset of MentionParser extract`() // NFR2
```
실패 메시지(예상): renderSafe 결과에 `<span class="mention">` 없음.

**GREEN**:
- `Mention` 노드 — `com.vladsch.flexmark.util.ast.Node` 상속, username(`BasedSequence`) 보관.
- `MentionInlineParserExtension` (`InlineParserExtension`) — `getCharacters()="@"`, `parse(LightInlineParser)`에서 `@` 뒤 username을 공유 정규식으로 매칭 + 앞 문자 경계 확인(이메일/`@@` 차단). 매칭 실패 시 `false`(일반 텍스트로 흘림).
- `MentionNodeRenderer` (`NodeRenderer`) — `Mention` → `<span class="mention">@${username}</span>`(username HTML escape).
- `MentionExtension` (`Parser.ParserExtension` + `HtmlRenderer.HtmlRendererExtension`) — `customInlineParserExtensionFactory` + `nodeRendererFactory` 등록.
- `MarkdownRenderer` — `PARSER`/`RENDERER` builder에 `MentionExtension` 등록(`.extensions(listOf(MentionExtension()))`).
- `SANITIZE_POLICY` — `.allowElements("span").allowAttributes("class").matching { it == "mention" }.onElements("span")` 추가(정확 일치, 복합 class 거부).
- `MentionParser` — `MENTION_PATTERN`(또는 username 부분 패턴)을 `internal`/공용 상수로 노출, 확장이 재사용(중복 정의 금지).

**REFACTOR**: KDoc(보안 계층 설계에 span allowlist 사유 추가), 정규식 단일 출처 정리.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*MarkdownRendererMention*"` (모듈 경로는 impl에서 확정).

### Task 2. (frontend) `.mention` 강조 CSS + IssueDescription 컴포넌트 테스트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/index.css`, `apps/web/src/components/issue/IssueDescription.test.tsx`, `apps/web/src/components/issue/IssueDescription.tsx`(필요시 최소)]
- depends-on: []   # 백엔드와 독립 — span.mention HTML 형식만 가정

**RED** (`IssueDescription.test.tsx`):
- `descriptionHtml='<p><span class="mention">@alice</span> 확인</p>'` 렌더 시 `.mention` 요소가 존재하고 강조 클래스/속성이 적용됨(jsdom computed style 한계 → 클래스 존재 + 텍스트 검증).
- ReadMode + EditMode Preview 탭 둘 다 적용(dangerouslySetInnerHTML 경로 불변).

**GREEN**:
- `index.css`에 `.mention` 추가 — DESIGN.md 토큰 조합(별도 유채색 0): `color: var(--primary)` 계열 텍스트 + `background: var(--accent)` 약한 배경 + `font-weight: 500` + 작은 padding/rounded. `prose` 컨테이너 안에서 보이도록 스코프.

**REFACTOR**: 토큰 변수화, 주석(멘션 강조는 시각만·비대화형 — EC11).

**검증**: `pnpm test`(IssueDescription) · `pnpm typecheck` · `pnpm lint`.

### Task 3. (qa) D7 E2E — 멘션 강조 + 멘션→Inbox 도착 + MSW 멘션 파생

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-mention-render.spec.ts`(신규), `apps/web/src/mocks/inbox-handlers.ts`/`apps/web/src/mocks/issue-handlers.ts`(멘션 저장→inbox 파생, 필요시), 관련 fixtures]
- depends-on: [1, 2]   # 백엔드 마크업 형식(span.mention) + 프론트 CSS를 MSW가 미러

**내용** (TDD = E2E 시나리오 우선 작성 → MSW/배선으로 green):
- S1/S2 — 멘션 본문 이슈 상세 진입 → `@alice` 강조(`.mention`) 표시 + 코드스팬/이메일 비강조.
- S3 — 사용자 A가 본문에 `@bob` 추가 저장 → bob의 Inbox에 "…에서 멘션되었습니다" 도착. MSW가 멘션 저장(PATCH)→Inbox 공유 store에 알림 추가(브라우저 시드 가능, 메모리 `msw-derived-behavior-shared-store-e2e`).
- 분별 시드(가짜그린 방지) — 멘션 없는 본문 저장 → Inbox 무변화.
- **ground-truth 분담**: 백엔드 멘션→알림 파이프라인 진실은 기존 백엔드 통합테스트. E2E는 프론트 도착 UI만.
- 기존 issue/inbox E2E 회귀 0.

**검증**: `pnpm test:e2e`(issue-mention-render + 회귀).

## Plan 메타

- task 수: 3
- 예상 wave: 2 (wave1 — Task1 backend + Task2 frontend 병렬[파일 무겹침] / wave2 — Task3 qa depends[1,2])
- TDD 강제: yes
- 추가 검증: ktlint·detekt(backend) / typecheck·lint·vitest(frontend) / playwright(qa)
- 동기화: product 문서 D6/D7 [x] 마킹 + `verify-master-plan.sh` 통과(머지 게이트).

## 리뷰 결과 (← /bts-review-plan 채움)
