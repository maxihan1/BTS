# context-notes — 이슈 상세 Jira 패리티 캠페인

작업 중 내린 결정과 그 이유. 계속 덧붙인다. 계획 정본 `../2026-09-04-issue-detail-jira-parity.md`.

## 2026-09-04 · 착수 전 조사

### 실측한 현재 상태

`issues.$key.tsx` 는 1,242줄이고 `IssueDetailPage` 가 이미 `variant: 'page' | 'pane'` 을 갖는다
(FR-UX-06 PR20 split view). 레이아웃은 `:1021` 의 `grid grid-cols-1 lg:grid-cols-[1fr_340px]` 2단이고,
좌측 `<section aria-label="이슈 상세">` 에 제목 · `IssueDescription`(`:1081`) · `AttachmentSection`(`:1093`),
우측에 `IssueMetaPanel` · 일정 · 추정이 있다. **`IssueActivityTabs`(`:1173`) 는 이 grid 바깥**이라
우측 메타패널 아래까지 가로로 걸친다. 사용자가 「오른쪽 사이드바 아래로 댓글이 나온다」고 한 것이
정확히 이 구조다.

### 왜 「댓글 기본탭」이 이미 알려진 결손인가

`jira-parity-roadmap.md:43` 에 `F7 댓글 기본탭(CO 결손)` 이 chore 갈래로 적혀 있고 미착수다. 즉 이번
요구사항 2번의 절반은 로드맵이 이미 알고 있던 빚이다. PR② 가 이걸 닫는다.

`IssueActivityTabs.tsx:112` 의 현재 주석은 기본탭이 「이력」인 이유를 「변경 이력은 생성 이벤트가 항상
있어 빈 첫인상을 피한다(Maxi 게이트 D1)」로 적고 있다. 이번 변경은 그 판단을 **번복**한다 — Jira 가
"By default the activity feed shows comments"(J3)이기 때문이다. **주석을 지우지 않고 번복 사실과 날짜를
남긴다.** 근거 없이 뒤집힌 것처럼 보이면 다음 사람이 다시 되돌린다.

### 길이 제약이 3중으로 어긋나 있었다

| 층 | summary | 위치 |
|---|---|---|
| DB | 255 | `V001__issues_initial.sql:39` |
| 도메인 | 255 | `Issue.kt:329` `require(summary.length <= 255)` |
| API DTO | **200** | `CreateIssueRequest.kt:49` · `UpdateIssueRequest.kt:70` |
| 프론트 | **200** | `issue-create-schema.ts:14` |
| 클론 DTO | 255 | `CloneIssueRequest.kt:17` — 혼자 다르다 |

`issue-create-schema.ts` 의 주석이 경위를 남기고 있다. 프론트가 500 을 허용하던 동안 201~500자가
프론트를 통과해 백엔드 400 을 맞았고, FR-UX-09 F2 가 **프론트를 백엔드(200)에 맞춰** 봉합했다.
그런데 200 자체의 근거가 없다 — DB 도 도메인도 255다. Jira Cloud 는 255(J4)다. 이번엔 **반대 방향으로
백엔드를 255 에 맞춘다.** DB·도메인이 이미 255라 마이그레이션이 없다.

그리고 **상세 화면 제목 편집 `Input`(`issues.$key.tsx:1027`)에는 `maxLength` 가 아예 없다.** 생성 폼에만
zod 검증이 있었다. 사용자가 「입력 필드에 최대 글자수 적용」이라 한 것의 실체다.

### `<img>` 가 sanitize 에서 막혀 있었다

`MarkdownRenderer.kt` 의 OWASP allowlist 에 `img` 가 없다. 마크다운 `![](url)` 을 써도 flexmark 가 만든
`<img>` 를 sanitizer 가 지운다. 즉 「본문에 이미지」는 프론트만으로 절대 안 되고 백엔드 보안 표면
변경이 선행돼야 한다. 이것이 PR① 을 먼저 두는 이유다.

`u` `del` `s` `hr` `table` 계열도 없다. 에디터 툴바를 붙여도 서버가 지운다.

### 첨부는 Bearer 헤더 인증이라 `<img src>` 가 안 된다

`api/client.ts:123` 이 `Authorization: Bearer` 를 싣는다. `<img src="/api/v1/issues/K/attachments/ID">`
는 헤더가 안 붙어 401 이다. 그래서 썸네일도 본문 이미지도 **fetch → `URL.createObjectURL`** 경로가
강제된다. `AttachmentPreviewModal.tsx:95` 가 이미 그 패턴(생성 · cleanup revoke)을 정확히 구현해 뒀으니
그대로 본을 삼는다.

### 저장 포맷 — HTML 선택의 파급

Maxi 가 HTML 저장을 택했다(X1). 이게 PR① 을 T3 으로 만든다.

- `search_vector` 는 `summary || description` 의 STORED generated tsvector 다(`V032:20`). HTML 로 바뀌면
  `p` · `strong` 같은 태그명이 검색 토큰이 된다
- `idx_issues_description_trgm` 은 `lower(description)` 표현식 인덱스다(`V032:37`). AQL `text ~` 가
  `DESCRIPTION.likeIgnoreCase` 로 같은 표현식을 만들어야 플래너가 인덱스를 쓴다 — `V032` 주석의
  「죽은 인덱스」 경고가 그것이다. 컬럼을 바꾸면 **양쪽을 같이** 바꿔야 하고, 안 그러면 seq scan 으로
  전락하면서 **테스트는 통과한다**
- 그래서 `description_plain` 을 **생성 컬럼**으로 둔다. 앱이 두 번 쓰지 않으니 두 값이 어긋날 수 없다
  (`two-lists-never-check-each-other` 회피)

### PR① 이 프론트를 깨지 않는 법

PR① 시점에 프론트는 아직 markdown textarea 다. 백엔드를 HTML 전용으로 바꾸면 즉시 깨진다. 그래서
`UpdateIssueRequest` 가 `description`(markdown) 또는 `descriptionHtml`(HTML) **둘 중 하나**를 받고,
둘 다 오면 400 을 낸다. PR③ 이 후자로 옮겨탄다.

flexmark 는 제거하지 않는다 — CSV import(`ParsedImportRow.description`)가 평문/markdown 을 계속 넣고,
마이그레이션 백필 자체가 flexmark 를 쓴다.

### 이미 있어서 안 만들어도 되는 것

- `isEditableTarget`(`shortcuts.ts:218`)이 **이미 `isContentEditable` 을 본다.** TipTap 도입 시
  단축키가 샐 거라 걱정했는데 사전 해소돼 있다. 회귀 가드 테스트만 세운다
- `Dialog` 프리미티브(`components/ui/dialog.tsx`)에 `overlayClassName` · `showCloseButton` 가산 prop 이
  이미 있다 (로그인 모달 #436 이 넣었다). 모달을 새로 만들 필요가 없다
- `useReportModalOpen` 레지스트리 — `issue-detail-modal-gate.test.ts` 가 상세 서브트리의 모달 전수를
  import 그래프로 훑어 보고 누락을 잡는다. **새 모달을 넣으면 이 가드가 자동으로 요구한다.** 보고를
  빠뜨리면 red 가 된다
- `AttachmentPreviewModal` 의 blob 생명주기 처리

### 선행 실측 (계약 §5)

| 무엇 | 값 | 함의 |
|---|---|---|
| 상세 진입 의존 e2e | 37 spec | 전량 회귀 대상 |
| `goto('/issues/KEY')` | 46건 / 21파일 | `/issues/$key` 라우트를 살려 두므로 **무영향** |
| `getByRole('dialog')` | 224건 | 계약 §2 — 모달에 이슈 키 포함 고유 `aria-label` 필수 |
| 본문·댓글 유닛 | 8파일 | `IssueDescription.test.tsx` 1,005줄이 PR③ 에서 대부분 재작성 |

`goto` 형태가 46건이나 되는 것이 다행이다. 모달 전환의 e2e 파손 위험을 크게 줄인다 — 계획 단계에서
가장 컸던 리스크가 실측으로 내려갔다.

## 미해결 · 착수 중 판단할 것

- `IssueDescription.test.tsx` 1,005줄이 지키던 계약 중 무엇을 새 파일로 옮길지는 PR③ 착수 시 훑고 정한다
- FR 귀속(신규 FR 인가 기존 FR 의 D 단계 추가인가)은 `docs/plan/README.md` 대조 후 확정
- 시각 회귀 기준선 갱신 범위는 PR② 이후 실측

---

## 2026-09-04 · PR① 구현 중 결정

### 백필을 버렸다 — 등록 지점이 둘이라서

계획은 Flyway Java migration 으로 `issues.description` 을 HTML 로 in-place 변환하는 것이었다.
버린 이유는 「SQL 로 flexmark 를 못 부른다」가 아니라 **등록 지점이 둘**이라는 점이다.

| 어디 | 무엇이 Flyway 를 돌리나 |
|---|---|
| 조립 앱 | `FlywayAssemblyConfig` 가 `Flyway.configure()` 로 직접 |
| issue-tracking 단독 테스트 | `application-test.yml` 의 Spring auto-config |

한쪽에만 Java migration 을 등록하면 **단독 테스트는 초록인데 조립 앱에서 백필이 조용히
건너뛰어진다**. 두 목록이 서로를 검사하지 않는 지배 결함 양식 그대로다.

대신 기존 컬럼을 그대로 두고 HTML 컬럼을 새로 뒀다. 읽기 지점 한 곳이
`description_html ?: renderSafe(description)` 으로 옛 행을 흡수하고, 그 이슈가 편집되는 순간
이행이 끝난다. 부수 효과로 **마이그레이션이 순수 SQL 이 되고 마크다운 원문이 영구 보존된다** —
계획에 있던 `description_md_backup` 백업 컬럼도 그래서 필요 없어졌다.

### `issue_body_plain()` 이 함수인 이유 — generated → generated 금지

`description_plain` 과 `search_vector` 는 **같은 평문**을 봐야 한다. 그런데 PostgreSQL 은
generated column 이 다른 generated column 을 참조하는 것을 금지한다(실측:
"cannot use generated column in column generation expression"). 식을 복사하면 한쪽만 고쳤을 때
FTS 와 trigram 이 서로 다른 텍스트를 색인한다. 함수로 묶어 두 컬럼이 정의 **하나**를 호출한다.

### `img[src]` — 스킴 등록과 술어가 둘 다 필요하다

OWASP 는 `src` 를 URL 속성으로 특별 취급해 `allowUrlProtocols` 에 없는 스킴을 `matching` 술어보다
**먼저** 잘라낸다(실측). 스킴만 등록하면 `attachment:../../etc/passwd` 가 통과하고, 술어만 두면
`src` 자체가 스킴 단계에서 사라져 `<img alt="…" />` 만 남는다.

### 리뷰 적발 — 그 `allowUrlProtocols` 가 `a[href]` 까지 열었다

위 항목의 대가가 있었다. `allowUrlProtocols` 는 **요소별이 아니라 정책 전역**이다. `img` 를 위해
연 `attachment` 스킴이 `a[href]` 에도 그대로 열렸고, UUID 술어는 `img[src]` 에만 걸려 있었다.

```
<a href="attachment:../../etc/passwd">x</a>   =>  그대로 통과   (수정 전, 실측)
<img src="attachment:../../etc/passwd">       =>  제거          (술어가 막는다)
```

`javascript:` 는 여전히 차단되니 XSS 는 아니다. 그러나 PR④ 가 프론트에서 `attachment:` 참조를
API 경로로 치환하므로, 두면 경로 조작 표면이 된다. `href` 에 스킴 술어를 걸어 막았다 —
상대 경로·앵커·`http(s)`·`mailto` 는 종전대로 통과하는 것을 실측으로 대조했다.

**교훈.** 스킴 하나를 한 요소를 위해 열면 **정책 전체가 열린다**. 여는 쪽 요소에만 술어를 달고
끝내면, 같은 스킴을 쓸 수 있는 다른 속성이 무방비로 남는다.

### 파일 개명은 보류했다

계획의 `MarkdownRenderer.kt` → `HtmlSanitizer` 개명은 호출 지점 전수 변경이라 이 PR 의 표면을
넓힌다. 두 진입점(`renderSafe` · `sanitizeHtml`)만 추가하고 파일명은 그대로 뒀다.

### main 리베이스 후 재검증 — 「1초 만에 초록」을 믿지 않는다

PR① 을 main(#444 · #445 머지본) 위로 리베이스한 뒤 테스트를 다시 돌렸더니
`BUILD SUCCESSFUL in 1s`, 전 태스크 UP-TO-DATE 였다. 초록으로 보이지만 **테스트가 돌지 않았다**.
XML 타임스탬프가 리베이스 이전 시각 그대로인 것으로 확인했다. `--no-build-cache --rerun-tasks`
로 실제 실행(38 tasks executed · 19분)해 issue-tracking 3487 + 조립 앱 79 초록을 받았다.
