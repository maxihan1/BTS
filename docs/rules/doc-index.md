<!-- 문서 인덱싱 규칙 상세 — 생성기 구조·판별식 4개·새 원천 추가 절차 -->

# 문서 인덱싱 규칙

> 정본. 이 파일. `CLAUDE.md §문서 인덱싱 규칙` 은 요약만 갖는다.
> 설계 배경. [`docs/specs/2026-08-01-doc-indexing-system.md`](../specs/2026-08-01-doc-indexing-system.md)

## 왜 있나

에이전트가 매 세션 고정 비용으로 40.7KB 를 읽으면서도, 메모리 378건 중 **178건(47%)이
인덱스에 없어 찾을 수 없었다.** 결손은 무작위가 아니라 FR 완료 이력에 집중돼 있었다
(`fr-*` 191건 중 172건 미등록). 손으로 유지하는 목록은 반드시 갈라진다 —
이 저장소의 지배 결함 양식이다.

## 생성물 — 전부 자동 생성

| 파일 | 내용 | 검증 |
|---|---|---|
| `MEMORY.md` | ★ 전량 + 카테고리 라우팅 표 | 생성기 자가진단 |
| `memory/index/{workflow,backend,cross-bc,frontend,build}.md` | 카테고리별 목록 | 〃 |
| `docs/INDEX.md` | docs 라우터 + grep 사용법 | CI 판별식 |
| `docs/INDEX-fr.md` | FR 축 — FR 하나에 spec·plan·adr·memory 한 행 | 〃 |
| `docs/INDEX-recent.md` | 시간 축 — 전량, 최신순 | 〃 |

**상단에 `자동 생성` 주석이 있는 파일은 직접 수정하지 않는다.** 원본을 고치고 재생성한다.

```bash
node scripts/build-doc-index.mjs           # 생성
node scripts/build-doc-index.mjs --check   # 비교만 (파일 안 씀). 다르면 exit 1
```

## 쓰기 규칙

**새 메모리.** frontmatter 필수.

```yaml
---
name: <kebab-case-slug>
description: <한 줄 요약>
metadata:
  type: user | feedback | project | reference
  hook: "<인덱스에 표시할 초압축 한 줄, ≤80자>"
  priority: critical | normal      # critical 이면 MEMORY.md ★ 구획에 상주
  category: workflow | backend | cross-bc | frontend | build | fr-history
---
```

`category: fr-history` 는 FR 완료 이력이라는 뜻이다 — `docs/INDEX-fr.md` 의 memory 열로 간다.
파일명이 `fr-<bc>-<번호>-*` 형식이면 FR ID 가 자동 추출된다. 비표준 형식은
`scripts/doc-index/config.mjs` 의 `MANUAL_FR_OVERRIDE` 에 등록한다.

**새 문서.** 파일명 `YYYY-MM-DD-slug.md` · `# H1` 필수 · 본문에 FR ID 명시.
spec 과 plan 은 **같은 slug** 를 쓴다 (짝 매칭 키).

FR ID 는 `docs/plan/{fr-index.md,product/*.md}` 의 정본 집합과 대조된다. 정본에 없는 ID 는
인덱스에서 제외되고 경고로 노출된다 — 조용히 버리면 오타를 영영 못 잡기 때문이다.

**가짜 FR ID 를 문서에 쓰지 않는다.** 예시·테스트 목적이어도 마찬가지다. 그 문서 자체가
스캔 대상이라 생성기가 매번 경고를 뱉는다.

### FR ID 추출의 두 함정 (2026-08-01 실측)

**① `NFR-XX-NN` 을 잘라 먹지 않는다.** `FR-[A-Z]{2,3}-\d{2}` 는 `NFR-SEC-01`
(Non-Functional Requirement)의 뒷부분을 `FR-SEC-01` 로 잡는다. 실제로 이 오탐 5건이
"정본에 없는 FR" 경고로 떴고, **문서가 아니라 판별식이 틀린 것**이었다.
lookbehind `(?<![A-Z])` 로 앞 대문자를 배제한다. 파일명 쪽도 `(?<![a-z])` 로 같이 막는다.

**② 흡수·착지한 역사적 ID 는 오타가 아니다.** 계획 단계에서 쓰였다가 다른 FR 로 흡수된
ID 가 문서에 역사적 참조로 남는 것은 정상이다. `config.mjs` 의 `KNOWN_HISTORICAL_FR_IDS`
에 근거와 함께 등록해 경고에서 뺀다 (인덱스에는 여전히 안 들어간다).
매번 뜨는 경고는 피로를 만들어 **진짜 오타를 놓치게 한다.**

현재 등록. `FR-AU-12`(≡ FR-PM-02 로 흡수) · `FR-IS-12`(FR-MV-02 로 착지).
근거는 `docs/decisions/2026-07-28-fr-ux-07-active-project-context.md` §선점 검증.

화이트리스트를 넓힐 때는 **진짜 오타를 여전히 잡는지** 확인한다 — 가짜 ID 를 문서에 넣어
경고가 뜨는 것을 보고 지운다.

## 판별식 4개 (`scripts/workflow/doc-index-coverage.test.ts`)

| 룰 | 검사 | 막는 사고 |
|---|---|---|
| I | `--check` 가 exit 0 | 인덱스 drift |
| J | 문서 → 인덱스 차집합 = 0 | 미등록 항목 |
| J-역 | 인덱스 → 문서 차집합 = 0 | 유령 항목 |
| K | 인덱스 링크가 전부 실재 | 깨진 링크 |

### ★「룰 L」은 없다 (2026-09-09 정정)

이 표에는 오래도록 다섯 번째 행 `L / L-2 — 생성기 SOURCES ⟺ CI paths` 가 있었고
「**룰 L 이 가장 중요하다**」고 적혀 있었다. 그런 테스트는 **한 번도 존재하지 않았다.**
판별식 파일에 있던 것은 CI 파일 경로를 담은 상수 하나뿐이었다.

이 저장소가 이름 붙인 지배 결함 양식이 문서 자신에게 일어난 자리다 —
**가드를 설명하는 목록과 가드를 구현한 목록이 서로를 검사하지 않았다.**
게다가 `mutation-probe.sh` 는 그 없는 룰에 위반을 주입하고 red 를 기대했으므로,
비-공허를 재는 하네스마저 그 자리에서는 공허했다.

룰 L 이 막으려던 사각은 **젠킨스 이후 성립하지 않는다.** `bts-ci` 는 경로 필터 없이
모든 푸시에서 판별식 전량을 돌리므로 「그 파일만 고치면 판별식이 0회 실행」이 불가능하다.

### 새 문서 디렉토리를 추가할 때

`scripts/doc-index/config.mjs` 의 `SOURCES` 에 추가한다. **그게 전부다** —
젠킨스에는 대조할 `paths` 목록이 없다(경로 필터를 안 쓴다).

### 판별식을 고쳤다면 비-공허 확인

```bash
bash scripts/doc-index/mutation-probe.sh   # 클린 상태에서
```

위반 5종을 주입해 red 를 보고 원복한다. **위반을 넣어보지 않은 판별식은 장식일 수 있다** —
2026-08-01 1차 주입에서 6종 중 5종이 green 이었다 (룰 I 이 쓰기 모드로 재생성해 검사 대상을
덮어썼고, 그 부작용이 룰 J-삭제·K 까지 연쇄로 무력화했다).

## 검증이 두 층인 이유

메모리(`~/.claude/projects/…/memory/`)와 Obsidian 은 **저장소 밖**이라 CI 러너가 체크아웃하지
않는다. 그래서 층을 나눈다.

| 원천 | 검증 |
|---|---|
| `docs/` | CI 판별식 (룰 I·J·K·L) |
| 메모리 | 생성기 자가진단 + husky pre-commit |
| Obsidian | 생성만. 인덱스에 생성 시각을 박아 낡음을 노출 |

## 관련

- FR 변경 시 전수 동기화. [`fr-sync-checklist.md`](fr-sync-checklist.md)
- 명령어·코드 지도. `CLAUDE.md` 본문
