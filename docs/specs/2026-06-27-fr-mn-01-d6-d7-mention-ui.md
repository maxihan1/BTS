# FR-MN-01 D6/D7 — 본문 @멘션 시각 강조 렌더링 + 멘션→Inbox 도착 E2E — 스펙

> slug: fr-mn-01-d6-d7-mention-ui
> 작성: 2026-06-27
> BC: issue-tracking
> 작성 방식: 직접 기술 스펙 (office-hours 스킵 — 확정 FR의 세부 구현, 메모리 선례 `bts-spec-office-hours-mismatch`)

## 배경

FR-MN-01(본문 @멘션 + 즉시 알림)의 D1~D5(백엔드 발행)는 PR #114 완료. D6(멘션 렌더링)·D7(Inbox 도착 E2E)은 알림 전달(FR-NT)·Inbox(FR-UX-03) 인프라 부재로 deferred됐고, 그 선행 FR이 모두 완료되어 진행 가능.

**백엔드 파이프라인은 이미 완성** — `IssueMentioned` 발행(D4) → `NotificationWorker`(ISSUE_MENTIONED) 소비 → `EventRecipientResolver`(MENTIONED) → 인앱 알림 → Inbox(notifications 확장).

**Maxi 결정 (2026-06-27)**. D6 멘션 강조는 **옵션 A — 백엔드 마크업**. 본문은 백엔드 정화 HTML을 `dangerouslySetInnerHTML`로 렌더하는 구조라(`IssueDescription.tsx`), `MarkdownRenderer`가 멘션을 마크업하고 정화 allowlist에 허용 태그를 추가한다. same-BC view layer라 이 PR 안에서 처리(메모리 `bc-격리-예외-frontend-same-bc-view-layer`).

## 핵심 설계 결정

### 결정 1 — 강조 위치 = 백엔드 MarkdownRenderer (Maxi 확정 옵션 A)

`MarkdownRenderer.renderSafe`가 `@username`을 `<span class="mention">@username</span>`으로 마크업한다. 프론트는 `.mention` CSS만 추가.

### 결정 2 — 마크업 규칙 = MentionParser 추출 규칙과 일치 (단일 진실출처 근접)

발행(`IssueMentioned`)과 표시(span)가 어긋나면 "강조됐는데 알림 안 옴" 같은 혼란이 생긴다. 따라서 마크업 대상은 `MentionParser.extract`가 추출하는 것과 같은 집합이어야 한다.
- 펜스 코드 블록(` ``` `) 내부 `@` 제외
- 인라인 코드 스팬(`` ` ``) 내부 `@` 제외
- 마크다운 링크/autolink(`<a>`) 내부 `@` 제외 (이메일 `mailto:` 포함)
- 이메일 `user@host`의 `@` 제외 (lookbehind: 앞 문자가 영숫자/`.`/`_`/`-`/`@`이면 비매칭)
- `@@bob` 제외
- username 형식 `[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?`

**구현 방향**. flexmark 인라인 확장(custom inline parser/`@` 토큰)을 우선 검토 — flexmark가 코드 스팬·코드 블록·링크를 별도 노드로 분리하므로 그 내부에서는 인라인 파서가 작동하지 않아 코드/링크 제외가 **자동 보장**된다. (대안. 렌더 후 텍스트 노드 처리 — 복잡도·정밀도 plan에서 비교.) 멘션 정규식은 백엔드 `MentionParser.MENTION_PATTERN`을 단일 출처로 공유/일치.

### 결정 3 — 실존 검증 안 함 (형식 기반 강조, 1차 기본값) ⚠️ 게이트1 재검토 항목

`renderSafe(md: String)`는 무상태 object를 유지하고, 사용자 조회(UserLookupPort)를 결합하지 않는다. `@username` 형식이면 실존 여부와 무관하게 강조한다.

- **근거**. ① renderSafe 무상태/단순 유지(설계 일관성·추가 DB 쿼리 회피) ② 역할 분리 — 발행측(IssueApplicationService)이 실존 검증 담당, 표시측(MarkdownRenderer)은 형식 강조 ③ FR-MN-02 자동완성(#158)이 실존 사용자 드롭다운을 제공하므로 대부분 멘션은 실존(입력 시점 보장) ④ D6 범위 = "강조"(시각 표시).
- **trade-off**. 존재하지 않는 `@typo`도 강조됨(알림은 안 가지만 강조는 됨). 경미한 UX 이슈로 허용.
- **대안(미채택)**. `renderSafe(md, mentionableUsernames: Set<String>)` 시그니처 변경 + `IssueResponse.from`에서 멘션 추출·조회 → 실존만 강조. 정확하나 복잡도 큼. **게이트1에서 Maxi가 뒤집을 수 있도록 명시.**

### 결정 4 — 정화 정책 최소 확장 (보안)

`SANITIZE_POLICY`에 `span` 태그 + `class` 속성을 추가하되, **`class` 값은 정확히 `mention`만 허용**(임의 class 거부). 기존 `code[class=language-*]` 패턴 매칭 선례를 따른다. 그 외 태그·속성·이벤트 핸들러 차단은 불변 — XSS 회귀 0.

## 사용자 시나리오 (Given-When-Then)

- **S1 (멘션 강조 표시)**. Given 본문에 `@alice 확인 바랍니다`가 있는 이슈. When 이슈 상세를 연다. Then 본문에서 `@alice`가 시각적으로 강조(`.mention` 스타일)되어 보인다.
- **S2 (코드/이메일 제외)**. Given 본문에 `` `@code` `` 코드 스팬과 `user@example.com` 이메일이 있다. When 본문을 본다. Then 둘 다 강조되지 않는다(일반 텍스트).
- **S3 (멘션 → Inbox 도착)**. Given 사용자 A가 본문에 `@bob`을 추가해 저장한다. When bob이 자신의 Inbox를 연다. Then "…에서 멘션되었습니다" 알림이 도착해 있다.
- **S4 (다중 멘션)**. Given 본문에 `@alice @bob` 둘. When 본문을 본다. Then 둘 다 각각 강조된다.
- **S5 (본문 없음)**. Given description=null. When 본문 영역을 본다. Then placeholder만 표시, 강조 없음(회귀 0).

## 기능 요구사항 (FR)

- **F1**. `MarkdownRenderer.renderSafe`는 멘션 형식 `@username`을 `<span class="mention">@username</span>`으로 마크업한다.
- **F2**. 코드 블록·코드 스팬·링크(`<a>`)·이메일·`@@` 내부/형식의 `@`는 마크업하지 않는다(MentionParser 규칙 일치).
- **F3**. `SANITIZE_POLICY`는 `span[class=mention]`만 허용하고 그 외는 차단한다.
- **F4**. 프론트 `IssueDescription` ReadMode/Preview는 `.mention` 강조 스타일을 적용한다(DESIGN.md 토큰 활용). dangerouslySetInnerHTML 경로 불변(NFR1/NFR2 보존).
- **F5**. 멘션 강조는 PDF 출력(`IssuePdfTemplate`, descriptionHtml 경로)에도 자동 반영된다(별도 작업 없이 검증만).

## 비기능 요구사항 (NFR)

- **NFR1 (보안)**. 정화 정책 확장은 `span[class=mention]` 단일 추가에 한정. XSS 회귀 테스트 통과. `<span onclick=...>`, `<span class="evil">` 등 주입 시도는 정화로 제거됨을 단위 테스트로 검증. security-engineer 검토.
- **NFR2 (일관성)**. 표시(강조) username 집합 ⊆ 발행(`MentionParser.extract`) 집합. 정상 케이스(S1/S2/EC1~8)에서는 동일하되, 불균형 백틱 등 비정상 입력에서 MentionParser의 과대추출 bias로 발행이 더 넓을 수 있다(발행 측 미존재 username은 UserLookupPort에서 드롭됨). 표시가 발행의 **부분집합**이면 "강조됐는데 알림 안 옴"은 발생하지 않으므로 안전. 대표 케이스 일치를 단위 테스트로 검증.
- **NFR3 (의존성 0)**. flexmark·OWASP sanitizer 기존 의존성만 사용. 신규 라이브러리 0(DEVELOPMENT.md §17).
- **NFR4 (성능)**. renderSafe 무상태 유지, 추가 DB 쿼리 0.

## 구현 인터페이스

### 백엔드 (issue-tracking)

- `MarkdownRenderer.kt` — flexmark 멘션 확장 추가 또는 렌더 파이프라인에 멘션 마크업 단계. `MENTION_PATTERN`을 `MentionParser`와 공유(중복 정의 금지 — drift 차단).
- `SANITIZE_POLICY` — `.allowElements("span").allowAttributes("class").matching { it == "mention" }.onElements("span")`.
- 멘션 정규식 단일 출처 — `MentionParser.MENTION_PATTERN`을 internal 노출하거나 공용 상수로 추출(plan에서 결정).

### 프론트 (apps/web)

- `IssueDescription.tsx` — 변경 최소. `.mention` 스타일을 prose 컨테이너에 적용(전역 CSS 또는 컴포넌트 className). dangerouslySetInnerHTML 로직 불변.
- CSS — `.mention` 강조(예: 색상 + 약한 배경 + 약간 볼드). DESIGN.md 토큰.

### E2E (apps/web/e2e)

- 신규 `issue-mention-render.spec.ts` 또는 기존 spec 확장 — S1/S2(강조·제외) + S3(멘션→Inbox 도착, 기존 `inbox.spec.ts`/`inbox-handlers.ts` 결합).
- MSW — 멘션 저장 시 Inbox 알림 추가를 시뮬레이션(멘션 발행→알림 fanout을 mock store에 반영). 기존 inbox-handlers/fixtures 재사용.

## 엣지 케이스

- **EC1**. 코드 스팬 `` `@code` `` → 강조 안 함.
- **EC2**. 펜스 코드 블록 내 `@x` → 강조 안 함.
- **EC3**. 이메일 `user@example.com` → 강조 안 함.
- **EC4**. `@@bob` → 강조 안 함.
- **EC5**. 문장부호 경계 `@alice.` → `@alice`만 강조(마침표 제외).
- **EC6**. 마크다운 링크 `[text](http://x@y)` 내부 `@` → 강조 안 함(`<a>` 내부).
- **EC7**. 정화 우회 시도 — 사용자가 raw `<span class="mention">evil</span>` 입력 → flexmark escape + 정화로 안전 처리(임의 텍스트로). 멘션 마크업은 렌더러가 부여하는 것만.
- **EC8**. `class="mention extra"` 같은 복합 class 주입 시도 → `matching { it == "mention" }` 정확 일치라 거부.
- **EC9**. 본문 없음(null) → placeholder, 회귀 0.
- **EC10 (self 멘션)**. 본인이 본문에 자기를 `@self`로 멘션 → 형식 기반이라 **강조는 됨**. 단 발행측이 자기제외(`-actor`)라 본인에게 알림은 안 감. 강조≠알림의 경미한 불일치는 허용(본인이 자기 멘션을 보는 건 무해).
- **EC11 (클릭 동작)**. 멘션 강조는 **시각 표시만**. 클릭/링크/프로필 이동 없음(사용자 프로필 페이지 부재). `<span>`은 비대화형 요소.

## D7 테스트 분담 (가짜 그린 방지, 메모리 `msw-derived-behavior-shared-store-e2e`)

- **백엔드 멘션→알림→Inbox 파이프라인의 진실(ground-truth)**은 기존 백엔드 통합 테스트(`NotificationDeliveryEndToEndIntegrationTest`, `IssueMentionPublishIntegrationTest`)가 검증. D7 E2E가 이를 대체하지 않는다.
- **D7 E2E의 책임**은 "프론트 관점 — 멘션이 포함된 본문 저장 후, 멘션된 사용자의 Inbox UI에 알림이 도착해 보인다". MSW는 멘션 저장(PATCH) → Inbox 공유 store에 알림 추가를 브라우저 시드 가능한 store로 재현(파생 동작은 공유 store에서 읽기). MSW가 관대하게 항상 통과하지 않도록 시드 분별(멘션 없는 저장 → Inbox 무변화) 케이스 포함.

## 측정 가능한 완료 기준

- [ ] 백엔드 — renderSafe가 S1/S2/EC1~EC8 케이스를 단위 테스트로 통과(멘션 마크업 + 코드/이메일 제외 + 정화).
- [ ] 백엔드 — 마크업 username 집합 = MentionParser.extract 집합 일치 테스트(NFR2).
- [ ] 백엔드 — XSS/정화 회귀 테스트 통과(NFR1).
- [ ] 프론트 — IssueDescription에 span.mention 포함 HTML 렌더 시 강조 스타일 적용 컴포넌트 테스트.
- [ ] E2E — S1/S2 강조·제외 + S3 멘션→Inbox 도착. 기존 issue/inbox E2E 회귀 0.
- [ ] product 문서 D6/D7 [x] 마킹 + 카운트 동기화(verify-master-plan 통과).

## Brainstorming Check

✅ 통과 (1회 직접 sanity check). gap 4건 보강 — NFR2 부분집합 재정의(gap A) · D7 ground-truth 분담(gap B) · 클릭 없음 EC11(gap C) · self 멘션 EC10(gap D). Maxi 결정 필요 신규 gap 0(실존 검증은 결정3에서 이미 게이트1 항목으로 표기). plan 이관 실증 항목 — flexmark 인라인 확장 구현 가능성 / 멘션 정규식 단일 출처 추출 방식.
