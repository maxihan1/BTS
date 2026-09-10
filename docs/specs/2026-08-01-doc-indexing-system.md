<!-- 에이전트 문서 인덱싱 체계 스펙 — 메모리 378 + docs 716 자동 인덱스, 2계층 라우터, CI 차집합 차단 -->

# 에이전트 문서 인덱싱 체계 — 스펙

> ---
> ⚠️ **2026-09-09 부기 — 이 스펙의 「룰 L」은 구현되지 않았다.**
> 본문은 룰 L(생성기 `SOURCES` ⟺ CI `paths` 정합)을 5곳에서 참조하고, §7 은
> 「룰 L은 가설이 아니라 이 저장소가 이미 겪은 사고다」라고 **단정**한다.
> 그 테스트는 `doc-index-coverage.test.ts` 에 **한 번도 존재한 적이 없다** —
> 실재하는 룰은 I · J · J-역 · K 넷뿐이고, 판별식 파일에 있던 것은 CI 경로 상수 하나였다.
> 넉 달간 아무도 실재를 확인하지 않은 이유가 저 단정문이다.
> **본문은 그때의 계획으로 그대로 둔다.** 현재 사실은 `docs/rules/doc-index.md` 를 본다.
> 젠킨스 이후 룰 L 이 막으려던 사각(경로 필터 누락)은 성립하지 않는다 — 경로 필터를 안 쓴다.
> ---

> slug: `doc-indexing-system` · type: `chore` · 논리 BC: 없음 (저장소 인프라)
> 생성: 2026-08-01 · 기저: `1aab9ce81` (main) · 브랜치: `chore/doc-indexing-system`
> 사용자 원문. "claude.md나 메모리, history 등 에이전트가 읽고쓰는 문서들의 크기가 큰데
> 인덱싱 해주고 인덱싱 규칙등도 적용해서 에이전트가 적은 리소스로 읽고 쓰기 쉽도록 해줘"

## 배경 한 줄

에이전트가 **매 세션 40.7KB를 무조건 로드**하면서도, 정작 축적된 메모리 378개 중 **178개(47%)는
인덱스에 없어 찾을 수 없고**, `docs/` 716개 문서에는 **인덱스가 아예 없다**.

## 실측 (2026-08-01, 이 스펙의 근거)

### 상시 로드 — 매 세션 컨텍스트 고정 비용

| 파일 | 크기 | 자동 로드 |
|---|---|---|
| `~/.claude/CLAUDE.md` (글로벌) | 8.3KB | ✅ |
| `CLAUDE.md` (프로젝트) | 10.2KB | ✅ |
| `MEMORY.md` | 22.2KB (140줄 · 116링크) | ✅ |
| **소계** | **40.7KB (≈12~15K 토큰)** | |
| `DEVELOPMENT.md` | 6.9KB | ❌ 온디맨드 |
| `DATA.md` | 14.2KB | ❌ 온디맨드 |

`MEMORY.md` 하나가 상시 비용의 **54%**.

### 인덱스 결손 — 차집합 실측

```
MEMORY.md 등록 링크 : 200
실제 메모리 파일    : 378
→ 고아(인덱스 미등록): 178   ← 47%가 사실상 검색 불가
→ 깨진 링크          : 0
```

이 저장소 메모리 `two-lists-never-check-each-other`가 **지배 결함 양식**으로 등록한
"두 목록이 서로를 안 본다"의 교과서적 실례다. 그 메모리가 명시한 처방을 그대로 적용한다 —
**차집합 판별식 + 비-공허 짝 + CI 차단**.

**고아의 정체 — 결손이 무작위가 아니다.**

```
fr-* 파일명 메모리 : 191
  ├ MEMORY.md 등록 :  19
  └ 고아           : 172   ← 전체 고아 178개의 97%
```

즉 빠진 것은 **FR 완료 이력이 통째로**다 (`fr-ac-01-attachment-backend-done`,
`fr-api-02-aql-envelope-done` …). 손으로 인덱스를 쓸 때 교훈·함정은 등록하고
완료 요약은 "나중에" 미룬 결과가 누적된 것으로 보인다. 이 편중이 설계에 직접 반영된다 (§2·§4).

### 온디맨드 — 인덱스 없는 문서

| 디렉토리 | 파일 수 | 총량 | 인덱스 |
|---|---|---|---|
| `docs/plans/` | 301 | 6.9MB | ❌ |
| `docs/specs/` | 256 | 3.7MB | ❌ |
| `docs/decisions/` | 125 | 1.1MB | ❌ |
| `docs/adr/` | 34 | 244KB | ❌ |
| `docs/plan/` | 11 | 424KB | ✅ `README.md`·`fr-index.md` |
| `docs/sdd/` | 25 | 208KB | ✅ `README.md` |
| `Maxi_wiki/BTS/` | — | 9.0MB | 부분 |
| **인덱스 없음 소계** | **716** | **12MB** | |

개별 파일도 크다 — `docs/plans/2026-07-17-project-management-crud.md` **162KB**,
`docs/plan/product/issue-tracking.md` **88KB**. 한 파일이 컨텍스트의 상당 부분을 삼킨다.

### 자동 생성 원천의 신뢰도

| 원천 | 준수율 | 채택 |
|---|---|---|
| 메모리 frontmatter `description` | 378/378 (100%) | ✅ 주 원천 |
| 메모리 frontmatter `metadata.type` | 377/378 (99.7%) | ✅ (1건 `feature` = 규격 외, 교정 대상) |
| 문서 파일명 `YYYY-MM-DD-slug.md` | 557/557 (100%) | ✅ 주 원천 |
| 문서 `# H1` | 716/716 (100%) | ✅ 주 원천 |
| 본문 FR ID (`FR-XX-NN`) | plans 292/301 · specs 251/256 · decisions 119/125 · adr 34/34 · memory 326/378 | ✅ 묶음 축 |
| plan `> slug:` 메타 | 285/301 (95%) | △ 보조 |
| 첫 줄 `<!-- 요약 -->` | plans 23/301 · specs 66/256 | ❌ **신뢰 불가** |

**결론.** 메모리는 `frontmatter`, 문서는 `파일명 + H1 + 본문 FR grep`으로 100% 자동 생성 가능.
첫 줄 주석은 준수율 8~26%라 원천으로 쓰지 않는다.

### 보조 축 실측

- `spec` ↔ `plan` 파일명 짝 일치 **246쌍** (spec 단독 10 · plan 단독 55) → slug가 작업 단위 키
- PR 번호(`#NNN`) 언급 — plans 218/301 · specs 167/256 → 보조 축으로만

## 결정 (Maxi 확정, 2026-08-01)

| # | 갈림길 | 결정 | 근거 |
|---|---|---|---|
| D1 | 인덱스 유지 방식 | **자동 생성 + CI 검증** | 손 유지는 이미 178개 drift를 냈다 |
| D2 | `MEMORY.md` 구조 | **2계층 라우터 분할** | 상시 22KB→5KB, 필요한 카테고리만 추가 로드 |
| D3 | `docs/` 묶음 축 | **FR 축 + 시간축 둘 다** | 질문이 다르다 — "뭘 읽지" vs "최근에 뭐 했지" |
| D4 | 범위 | **메모리 + docs + 헌법 3종 + Obsidian** | 전 범위 |

### D4의 합산 결과 — 쪼갠 질문의 조합 점검

메모리 `split-questions-hide-their-combination`의 처방에 따라 답을 합산해 되짚은 결과,
**두 지점에서 D1이 완전히 적용되지 않는다.** 이를 명시적 한계로 기록한다.

- **한계 ①  저장소 밖 원천은 CI 검증이 불가능하다.** 계획 수립 중 확대 확인됨 —
  Obsidian(`~/Maxi_wiki/BTS`)뿐 아니라 **메모리(`~/.claude/projects/…/memory/`)도 저장소 밖**이다.
  CI 러너는 이 경로를 체크아웃하지 않으므로 "재생성 후 diff 클린"(룰 I)을 판정할 수 없다.
  → **검증을 두 층으로 나눈다.**

  | 원천 | 위치 | 검증 |
  |---|---|---|
  | `docs/` 716개 | 저장소 안 | **CI 판별식** (룰 I·J·K·L) |
  | 메모리 378개 | 저장소 밖 | **pre-commit 훅**(husky) + 생성기 자가진단 |
  | Obsidian | 저장소 밖 | 생성만. 인덱스에 생성 시각을 박아 낡음을 노출 |

  메모리 인덱스는 CI가 못 지키므로, **생성기가 스스로 고아·깨진 링크를 세어 0이 아니면
  비정상 종료**한다. 즉 인덱스를 만드는 행위 자체에 검증이 붙어 있어 우회할 수 없다.
- **한계 ②  헌법 3종 중 둘은 상시 로드가 아니다.** 자동 로드는 `CLAUDE.md`뿐.
  `DEVELOPMENT.md`·`DATA.md` 슬림화는 **매 세션 절감이 아니라 "읽을 때 절감"** 이다.
  효과의 성격이 다르므로 처리 강도를 달리한다 (아래 §5).

## 설계

### 1. 단일 생성기

```
                    scripts/build-doc-index.mjs
                                 │
        ┌────────────────────────┼────────────────────────┐
        ↓ 읽기                    ↓ 읽기                    ↓ 읽기
  memory/*.md              docs/{plans,specs,          Maxi_wiki/BTS
  (frontmatter)             decisions,adr}/*.md         (--obsidian 플래그)
                            (파일명 + H1 + FR grep)
        │                        │                        │
        ↓ 생성                    ↓ 생성                    ↓ 생성
  MEMORY.md (라우터)        docs/INDEX.md (라우터)      Maxi_wiki/BTS/INDEX.md
  memory/index/*.md ×6      docs/INDEX-fr.md            (CI 검증 ✗ — 한계 ①)
                            docs/INDEX-recent.md
        │                        │
        └──────────┬─────────────┘
                   ↓
      scripts/verify-doc-index.sh → CI 차단 (exit 4)
```

선례를 따른다 — `scripts/build-dashboard.mjs`(919줄, `docs/progress.html` 생성)와
동일한 「스캔 → 생성 → CI 검증」 패턴. Node 22+.

**생성 파일은 전부 상단에 다음 마커를 갖는다.**

```
<!-- 자동 생성 — 직접 수정 금지. 원본을 고치고 `node scripts/build-doc-index.mjs` 재실행 -->
```

### 2. 손 큐레이션 이주 — 이 설계의 핵심

자동 생성의 함정은 현재 `MEMORY.md`에 손으로 쌓인 가치(`★` 우선순위 · 6개 그룹 · 압축 hook 문구)가
소실되는 것이다. 이 정보는 `description` 필드에 없다.

**해결. 큐레이션을 버리지 않고 각 메모리 파일의 frontmatter로 이주시킨다.**

```
[1단계] 현재 MEMORY.md 파싱 (일회성 backfill)
        200개 항목 → hook 문구 / ★ 여부 / 소속 그룹 추출

[2단계] 각 메모리 frontmatter에 역채움
        metadata:
          hook: "지배 결함 양식. 처방=차집합 판별식+비-공허 짝+CI"
          priority: critical      # ★ 였던 것
          category: workflow

[3단계] 이후 frontmatter가 진실의 출처
        인덱스 렌더: hook 우선 → 없으면 description 앞 N자
```

**분류 우선순위.** 세 단계로 내려간다. 위 단계가 잡으면 아래는 보지 않는다.

```
1. 수동 오버라이드 테이블  (생성기 상수 — 아래 예외 5건)
2. 현재 MEMORY.md 승계     (200건 — 사람이 이미 분류한 것)
3. 파일명 패턴 폴백        (나머지)
4. 미매칭 → uncategorized  (추측하지 않는다)
```

**FR 완료 이력은 카테고리에서 분리한다.** 이 분리가 자체 검토에서 나온 수정이다 (§검토 이력 R1).
`fr-XX-NN` 형식이 잡히는 174건은 `memory/index/`가 아니라 **`docs/INDEX-fr.md`의 memory 열**로 간다.
FR 축 인덱스가 이미 그 정보를 담을 자리를 갖고 있어 중복이고, 한 카테고리가 180건으로 비대해져
라우터 분할의 의미가 사라지기 때문이다.

| 패턴 | 분류 | 실측 |
|---|---|---|
| `^fr-[a-z]{2,3}-\d{2}` + (미승계 또는 승계=`fr-done`) · 수동 오버라이드 5건 | → **`docs/INDEX-fr.md`** | 179 |
| `bts-*` `worktree-*` `parallel-*` `subagent-*` `two-lists-*` `seal-*` `mutation-*` … | `workflow` | 55 |
| `jooq-*` `*-transaction-*` `*-exception-*` `pg-*` `path-token-*` `dev-seed-*` … | `backend` | 52 |
| `crossbc-*` `shared-*` `*-permission-*` `*-resolver-*` `nonprod-*` … | `cross-bc` | 33 |
| `*e2e*` `msw-*` `zod-*` `react-*` `vitest-*` `playwright-*` `jsdom-*` … | `frontend` | 32 |
| `detekt-*` `ktlint-*` `migration-*` `archunit-*` `gradle-*` `flaky-*` `saml/oidc-*` … | `build` | 27 |
| 미매칭 | **`uncategorized`** | **0** |
| | **합계** | **378** |

> **수치 출처.** 이 표는 구현 후 실행한 실측이다 (`node scripts/doc-index/classify.mjs` 경유).
> 스펙 초안의 시뮬레이션 값(FR축 174 · 카테고리 204 · uncategorized 5)은 수동 오버라이드를
> 적용하기 **전** 수치였다 — 오버라이드가 그 5건을 FR축으로 보내므로 179/199/0 이 맞다.
> 초안 표와 오버라이드 설명이 서로 어긋나 있던 것을 구현이 드러냈다 (§검토 이력 R3).

**`/^fr-/` 로 뭉뚱그리면 오분류한다.** `fr-scope-change-full-sync-rule`(FR 변경 동기화 규칙)과
`fr-sizing-d-stage-must-be-completion-unit`(FR 크기 판정식)은 FR 완료 이력이 아니라
**워크플로우 규칙**이다. 그래서 `\d{2}`(FR 번호)까지 요구하는 엄격한 패턴을 쓴다.

**수동 오버라이드 5건** — FR ID 형식이 비표준이라 자동 추출이 안 되는 완료 이력.
생성기 상수 테이블에 명시한다.

```
fr-bl-d6-d7-backlog-sprint-done                   → FR-BL (번호 없음)
fr-pj-pr-2-r6-backfill-reseed-project-create-done → FR-PJ (PR 번호 형식)
fr-pj-pr-3-list-query-settings-done               → FR-PJ
fr-pj-pr-4-archive-done                           → FR-PJ
fr-pj-pr-5-project-crud-ui-done                   → FR-PJ
```

**추측 분류는 금지한다.** 잘못 분류하면 라우팅이 어긋나 영영 못 찾는다.
미매칭은 `uncategorized`로 몰아 라우터에 별도 행으로 노출한다.
이는 `zero-measurement-means-wrong-discriminant`(0이 나오면 판별식을 의심)의 역방향 적용 —
분류기가 폴백 없이 100% 매칭을 주장하면 그게 오히려 의심 신호다.

### 3. 새 `MEMORY.md` (라우터, 목표 ≈5KB)

```markdown
<!-- 자동 생성 — 직접 수정 금지 -->

## ★ 항상 지킬 것 (34건)
(metadata.priority: critical 인 메모리 전량. 현재 MEMORY.md 의 ★ 표기에서 승계)
- 두 목록이 서로를 안 본다 → 차집합 판별식+비-공허 짝+CI  [[two-lists-never-check-each-other]]
- 뮤테이션은 커밋 후에만 (2회 재발) → 하네스 dirty 검사  [[mutation-test-requires-committed-baseline]]
  … 43건

## 상황별 인덱스 — 필요한 것만 열어라
| 지금 하는 일 | 열 파일 | 건수 |
|---|---|---|
| 워크플로우·도구·소통 | memory/index/workflow.md | 55 |
| 백엔드 (Kotlin/jOOQ/트랜잭션/예외) | memory/index/backend.md | 52 |
| cross-BC·권한·shared-kernel | memory/index/cross-bc.md | 33 |
| 프론트 (React/Zod/MSW/E2E/vitest) | memory/index/frontend.md | 32 |
| 빌드·린트·마이그레이션·SSO | memory/index/build.md | 27 |
| **FR 완료 이력** | **docs/INDEX-fr.md** (memory 열) | 179 |
```

카테고리 5개는 **현재 `MEMORY.md`의 `## ` 그룹을 그대로 승계**한다. 새 분류 체계를 발명하지 않는다.
(현재 6번째 그룹 "FR 스코프 / BC 완료 요약"은 FR 축 인덱스로 흡수되어 사라진다.)
`uncategorized` 행은 건수가 0이면 렌더되지 않는다 — 지금은 0이라 표에 없다.

**크기 추산.**

| 구획 | 산출 근거 | 크기 |
|---|---|---|
| ★ 34건 | 실측 평균 55자 × 34 | ≈1.9KB |
| 라우팅 표 + 헤더 | 6행 | ≈1.0KB |
| **`MEMORY.md` 합계** | | **≈2.9KB** (현재 22.2KB) |
| 작업 시 추가 1개 | 최대 카테고리 55건 × ≈100자 | ≈5.5KB |

카테고리가 27~55건으로 고르게 나뉘어, 어느 작업을 하든 **라우터 3.4KB + 카테고리 1개**만 읽으면 된다.

### 4. `docs/` 인덱스 3종

**`docs/INDEX.md`** — 라우터. 어느 인덱스를 열지만 안내.

**`docs/INDEX-fr.md`** — FR 축. 139개 FR 각각에 대해 관련 문서를 한 행으로 묶는다.
문서는 본문 `FR-XX-NN` grep(적중률 95~100%), 메모리는 파일명 `fr-XX-NN-*`(174건)으로 수집한다.

```markdown
| FR | 상태 | spec | plan | decision/adr | memory |
|---|---|---|---|---|---|
| FR-UX-09 | 미완 (D4/D5만) | 2026-07-31-…↗ | 2026-07-31-…↗ | — | fr-ux-09-b1-…↗ |
| FR-CO-01 | ✅ #315 | 2026-07-27-…↗ | 2026-07-27-…↗ | 2026-07-27-…↗ | fr-co-01-…↗ |
```

상태(`✅`/미완)는 **`docs/plan/product/*.md`의 D 마커를 재사용**한다 —
`build-dashboard.mjs`가 이미 같은 원천을 읽는다. 새 진실 출처를 만들지 않는다.

**이 열이 178개 고아 문제를 실제로 푸는 지점이다.** 고아의 97%가 FR 완료 이력이었으므로
(§실측), memory 열이 채워지는 순간 결손의 대부분이 해소된다.

**`docs/INDEX-recent.md`** — 시간 축. 날짜 내림차순, slug로 spec/plan 짝을 한 행에.

```markdown
| 날짜 | slug | spec | plan | decision |
|---|---|---|---|---|
| 2026-07-31 | fr-ux-09-b1-create-issue-fields | ✔ | ✔ | ✔ |
| 2026-07-31 | fr-ux-08-pr-b-sidebar-nav | ✔ | ✔ | — |
```

FR 없는 문서(plans 9 · specs 5 · decisions 6)는 FR 인덱스에서 빠지지만
**시간 인덱스가 받는다** — 두 축을 다 두는 이유가 이것이다.

### 5. 헌법 3종 처리 (한계 ②에 따라 강도 차등)

| 파일 | 상시 로드 | 처리 |
|---|---|---|
| `CLAUDE.md` (10.2KB) | ✅ | **분리 + 라우터화** → 목표 ≈5KB. §명세 동기화 9종 체크리스트 등 상세 절차를 `docs/rules/`로 분리하고 링크만 남긴다 |
| `DEVELOPMENT.md` (6.9KB) | ❌ | **목차만 추가** (절대 규칙 19개 인덱스) |
| `DATA.md` (14.2KB) | ❌ | **목차만 추가** |

`CLAUDE.md`에서 분리 대상. §명세/범위 변경 시 전수 동기화(9종 체크리스트 + verify 강제 문단),
§디렉토리 트리, §자주 쓰는 명령어. 진입 트리·워크플로우·핵심 패턴은 상시 필요하므로 **남긴다**.

**절대 규칙 19개(`DEVELOPMENT.md §1`)는 건드리지 않는다.** 분리도 요약도 하지 않는다.

### 6. 쓰기 규칙 — `CLAUDE.md` 신설 §

```
## 문서 인덱싱 규칙

· 상단에 "자동 생성" 주석이 있는 파일은 직접 수정 금지.
  원본을 고치고 `node scripts/build-doc-index.mjs` 재실행.
· 새 메모리 — frontmatter 필수: name · description · metadata.type ·
  metadata.hook(≤80자) · metadata.priority(critical|normal) · metadata.category
· 새 문서 — 파일명 `YYYY-MM-DD-slug.md` · `# H1` 필수 · 본문에 FR ID 명시
· spec ↔ plan 은 같은 slug 를 쓴다 (짝 매칭 키)
· 새 문서 디렉토리를 추가하면 생성기 SOURCES 와 CI paths 를 같은 PR 에서 함께 고친다 (룰 L)
```

### 7. 검증 — 판별식 4종 (룰 I~L)

**배치는 기존 관례를 따른다.** 이 저장소에는 이미 「판별식」 6종이
`scripts/workflow/*.test.ts`에 있고 `pnpm test:workflow`로 `workflow-scripts-ci.yml`에서 돈다
(bc-keyword-coverage · ci-module-coverage · preview-cors-origin-alignment ·
skill-type-coverage · todos-resolved-section-purity · ci-runner-label-alignment).
새 판별식은 **7번째로 그 자리에 붙인다.** `verify-master-plan.sh`는 FR/카운트 정합 전용이므로
건드리지 않는다.

| 룰 | 판별식 | 적용 대상 | 막는 사고 |
|---|---|---|---|
| **I** | 재생성 후 `git diff --exit-code` 클린 | `docs/` (CI) | 인덱스 drift |
| **J** | 고아 = 0 (`실제 − 인덱스`) | `docs/`(CI) + 메모리(생성기 자가진단) | 지금의 178개 사고 재발 |
| **K** | 깨진 링크 = 0 (`인덱스 − 실제`) | 〃 | 인덱스가 없는 파일 지목 |
| **L** | 생성기 `SOURCES` ⟺ CI `paths` 차집합 = 0 | CI | **봉인의 자기 결함 재생산** |

**룰 L은 가설이 아니라 이 저장소가 이미 겪은 사고다.** `workflow-scripts-ci.yml` 헤더 주석이
직접 기록한다 — "이 트리거 목록은 판별식 6종의 **입력 합집합**이다. 하나라도 빠지면 그 입력만
바꾸는 PR에서 해당 판별식이 **0회 실행**되고 통과한다." 그런데 그 목록은 **지금 사람이 손으로
맞추고 있다** — 즉 이 주석 자체가 "두 목록이 서로를 안 본다"의 미봉합 지점이다.
룰 L이 이 손 정합을 기계 정합으로 바꾼다.

**현재 트리거에 `docs/**`가 없다.** `docs/plan/product/**`만 있어, 새 판별식을 추가하면
트리거 경로도 같은 PR에서 확장해야 한다 — 룰 L이 이를 강제한다.

**룰 J·K는 양방향이다.** 메모리 `seal-closes-only-half-by-default`(봉인은 절반만 닫힌다)에 따라
삭제·추가 **양쪽 다** 주입해 red를 확인한다.

**메모리 층의 검증은 생성기 안에 있다.** CI가 저장소 밖을 못 보므로(한계 ①),
생성기가 메모리 고아·깨진 링크를 세어 0이 아니면 `process.exit(1)`로 끝낸다.
인덱스를 만드는 행위와 검증이 같은 실행에 묶여 있어 따로 우회할 수 없다.
추가로 husky pre-commit 훅이 `MEMORY.md` 변경을 감지하면 생성기를 재실행해 diff를 확인한다.

### 8. 검증 절차 — 비-공허 확인

메모리 `archunit-vacuous-rule-silent-pass`(위반을 넣어 확인)와
`mutation-test-requires-committed-baseline`(뮤테이션은 커밋 후에만) 에 따른다.

각 룰마다 **커밋된 클린 상태에서** 일부러 위반을 주입해 red를 확인한 뒤 원복한다.

| 룰 | 주입 | 기대 |
|---|---|---|
| I | 인덱스 한 줄 손으로 수정 | exit 4 |
| J-추가 | 메모리 파일 1개 신규 생성 (인덱스 미갱신) | exit 4 |
| J-삭제 | 인덱스에서 한 줄 삭제 | exit 4 |
| K | 인덱스에 없는 파일 링크 추가 | exit 4 |
| L | 생성기 SOURCES 에 경로 추가 (CI paths 미갱신) | exit 4 |

서명은 **「원복 후 클린인데 red 였다」** — 원복 후에도 red면 하네스가 고장난 것이다.

## 검토 이력

스펙 작성 후 분류기를 실제로 돌려 검증했다 (378개 전량 시뮬레이션). 두 건을 고쳤다.

**R1. `fr-done` 카테고리가 180건으로 비대해 라우터 분할이 무의미해짐.**
전체 메모리의 48%가 한 파일로 몰려, "필요한 카테고리만 읽는다"는 설계 목적이 깨졌다.
→ FR 완료 이력 174건을 `docs/INDEX-fr.md`의 memory 열로 이관. 카테고리는 6개→5개,
분포가 27~55건으로 균형을 찾았다. 중복 인덱스도 사라졌다.

**R2. 패턴 `/^fr-/` 가 워크플로우 규칙을 완료 이력으로 오분류.**
`fr-scope-change-full-sync-rule`·`fr-sizing-d-stage-must-be-completion-unit`은 FR 완료 이력이 아니다.
→ `\d{2}`(FR 번호)까지 요구하는 엄격 패턴 + 승계 우선순위로 교정. 부작용으로 FR ID 비표준
5건이 미분류로 남아, 수동 오버라이드 테이블에 명시했다.

**R3. 구현 후 실측이 시뮬레이션 두 값을 뒤집었다.** (2026-08-01, Task 1-2 완료 시점)

| 값 | 초안(시뮬레이션) | 실측 | 무엇이 틀렸나 |
|---|---|---|---|
| FR축 / 카테고리 / 미분류 | 174 / 204 / 5 | **179 / 199 / 0** | 초안 표가 **수동 오버라이드 적용 전** 수치였다. 오버라이드가 그 5건을 FR축으로 보내므로 초안 표와 오버라이드 설명이 자기모순이었다 |
| ★ (critical) | 45 | **34** | 시뮬레이션이 `★`를 **줄 전체**로 판정해 묶음 행 하나가 링크 5개를 전부 ★로 만들었다. `★`는 **직전 링크**에 귀속된다 |

`★` 검산 — 원본 등장 39회 − 링크 없는 줄 1건 − 같은 항목 중복 4건 = **34**.
귀속 규칙은 테스트 2건(`묶음 행의 ★는 직전 링크에만` · `★ 앞 링크는 ★가 아니다`)으로 고정했다.

이는 메모리 `spec-stated-count-becomes-blindfold`(스펙이 적은 개수가 눈가리개가 된다)의 실례다.
**스펙의 숫자를 기대값으로 삼지 않고 실측을 정본으로 삼는다.**

시뮬레이션 최종 결과. **378 = FR인덱스 179 + workflow 55 + backend 52 + cross-bc 33 +
frontend 32 + build 27 + uncategorized 0**, ★ 34건 ≈1.9KB.

## 성공 기준

| 항목 | 현재 | 목표 |
|---|---|---|
| 매 세션 고정 비용 | 40.7KB | **≤20KB** (실측 추산 ≈16.2KB) |
| 인덱스 등록 메모리 | 200/378 (53%) | **378/378 (100%)** |
| 인덱스 없는 문서 | 716 | **0** |
| `uncategorized` 메모리 | 5 (초안 시뮬레이션) | **0** — 오버라이드로 달성 (R3) |
| 최대 카테고리 인덱스 | — | **≤60건** (분할 실효성 유지) |
| FR 관련 문서 찾기 | glob+grep 반복 | 인덱스 **1행** |
| 룰 I~L 비-공허 확인 | — | **5/5 red 확인** |

## 범위 밖 (하지 않는 것)

- `docs/plans`·`docs/specs` 파일 **이동/삭제/병합** — 인덱스만 신설한다
- 개별 문서 내용 요약·압축 — 162KB 파일을 줄이지 않는다
- `DEVELOPMENT.md §1` 절대 규칙 19개 수정
- 글로벌 `~/.claude/CLAUDE.md`(8.3KB) — 전 프로젝트 공용이라 이번 범위 밖
- `docs/plan/`·`docs/sdd/` 기존 인덱스 재작성 — 이미 있고 `verify-master-plan.sh`가 지킨다
- FR 카운트·진척 표기 변경 — `CLAUDE.md §명세 동기화` 대상이 아니다 (기능 변경 없음)

## 위험과 완화

| 위험 | 완화 |
|---|---|
| backfill이 hook·★를 잘못 매핑 | 일회성 backfill 결과를 커밋 전 diff 전수 확인. 200개 중 매칭 실패는 `uncategorized` |
| 분류기가 178개를 엉뚱하게 배치 | 미매칭을 `uncategorized`로 강제 노출. 추측 금지 |
| 인덱스 파일 10개가 새 "두 목록" 문제를 만듦 | 룰 L이 생성기 입력 ⟺ CI 트리거 정합을 강제 |
| `CLAUDE.md` 분리로 규칙이 실종 | 분리한 절 각각에 링크를 남기고, `verify-master-plan.sh` 룰 E(카운트 표기)가 계속 통과하는지 확인 |
| 다른 세션이 동시에 메모리를 쓰는 중 backfill | 메모리 `bts-cache-multisession-collision` 참조. backfill 직전 `git status` 확인 |
| Obsidian 인덱스가 조용히 낡음 | 한계 ①로 명시. 생성 시각을 인덱스에 박아 낡음을 눈에 보이게 |
