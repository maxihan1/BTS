# `node` 로 `.ts` 를 부르는 호출문의 타입 스트리핑 플래그를 전수 봉인한다

> 티어: T2
> slug: node-ts-invocation-flag-guard
> type: chore
> agent: backend-engineer
> 생성: 2026-08-14

## Brief

**FR 없음 — FR수 불변 139.** 프로덕션 Kotlin 0줄 · `apps/web` 0파일 · 마이그레이션 0 · 신규 의존성 0.
하네스·가드 표면 전용 작업이다.

**사용자 원문.** 저장된 컨텍스트의 「남은 일 7 — 등재 대기 1건」에서 출발했다. 원 서술은
`bts-start/SKILL.md:34` 의 `classify-task.ts` 호출에 `--experimental-strip-types` 가 빠져
`ERR_UNKNOWN_FILE_EXTENSION` 으로 죽는다는 **한 줄짜리 결함**이었다. 착수 전 실측에서
**훨씬 넓고, 성격이 다른 문제**로 드러났다.

### 실측 — 증상

| 확인한 것 | 결과 |
|---|---|
| `node scripts/workflow/classify-task.ts …` (문서 그대로) | `ERR_UNKNOWN_FILE_EXTENSION` 즉사. 원 주장 **참** |
| `pnpm test:workflow` — `CLAUDE.md` 가 「CI 와 같은 목록」이라 적은 명령 | **62 pass / 20 fail** |
| 실패 20건의 성격 | 전량 `ERR_UNKNOWN_FILE_EXTENSION`. **테스트 실패가 아니라 파일이 안 열린 것** — `.ts` 판별식 전부 |
| 같은 목록에 `--experimental-strip-types` 부착 | **296 pass / 0 fail** |
| 로컬 node | `v22.14.0` |
| 러너 toolcache node (`verify-runner-health.sh` 실측) | **`22.23.2`** — 22.18+ 는 타입 스트리핑 **기본 활성** |
| CI 선언 | `actions/setup-node@v4` · `node-version: 22` → 최신 22.x 로 부유 |
| 버전 핀 (`.nvmrc` · `.node-version` · `mise`) | **전부 부재** |
| `engines` | `node >=22` — 22.14 도 22.18 도 만족한다. **이 경계를 못 잡는다** |

즉 **CI 초록 / 로컬 빨강이 구조적으로 고정**돼 있었다. 로컬에서 `pnpm test:workflow` 를 돌린
사람은 「62개 통과」를 보고 넘어가는데, 실제로는 `.ts` 판별식 20개가 **한 줄도 실행되지 않았다.**

### 실측 — 플래그가 빠진 살아 있는 호출문 (전수)

```
package.json:9                             "classify"       → 즉사
package.json:10                            "test:workflow"  → .ts 20건 전량 미실행   ★
.claude/skills/bts-start/SKILL.md:34       classify-task    → 원 항목이 지목한 곳
scripts/workflow/README.md:16,19           classify-task    → 문서대로 치면 죽는다
scripts/workflow/README.md:82,85           detect-tier      → 파이프 형태도 동일
scripts/workflow/README.md:94              node --test      → 판별식 실행 안내 자체
docs/plans/2026-08-12-debt24-master.md:38  debt-ledger      → 부채 장부 정본
```

플래그가 **붙어 있는** 대조군 — `.claude/skills/bts-codereview/SKILL.md:25` ·
`.github/workflows/backend-ci.yml:197,200` · `scripts/doc-index/mutation-probe.sh:23`.

### 결함 양식

**「`node` 로 `.ts` 를 부르는 호출문 목록」과 「플래그를 든 호출문 목록」이 서로를 안 본다.**
★메모리 `two-lists-never-check-each-other` 의 재발. 처방도 그 항목이 이미 정해 두었다 —
**차집합 판별식 + 비-공허 짝 + CI**.

**가장 뼈아픈 부분.** `scripts/doc-index/mutation-probe.sh:19` 에 이미 이렇게 적혀 있다.

> `★--experimental-strip-types` 없이는 Node 22.14 에서 `.ts` 가 `ERR_UNKNOWN_FILE_EXTENSION` 으로

**한 곳에 적어두고 나머지에 전파하지 않았다.** 사실을 몰라서 생긴 결함이 아니라,
**아는 사실을 강제로 바꾸지 않아서** 생긴 결함이다.

### 선례

`learnings.md` 2026-07-15 (PR #274) — 「검증 장치 자체가 고장 나 있었다」.
그 항목의 예방 ④ 가 이 작업의 한 줄 요약이다.

> **검증 장치가 고장 나면 검증했다는 착각이 검증 부재보다 나쁘다.**

### 착수 시 분류 — classify 정정 4건

`classify-task.ts` 원출력을 그대로 쓰지 않았다. 정정 근거를 남긴다.

| 항목 | 원출력 | 정정 | 근거 |
|---|---|---|---|
| `type` | `backend` | **`chore`** | 프로덕션 Kotlin 0줄 · 하네스/가드 표면 |
| `tier` | `T1` | **`T2`** | `detect-tier.ts` 실측 `TIER: T2`. `package.json` 이 `DEPS` 표면 → 판정 5문 ① 최고 티어 지배 |
| `primary_bc` | `issue-tracking` | **`null`** | `backend/` 0파일. 어느 BC 도 안 건드린다 — 키워드 오판 (같은 오분류 상습 재발) |
| `slug` | `node-ts-experimental-strip-types-nvmrc` | **`node-ts-invocation-flag-guard`** | 원출력이 3번째 범위(판별식 신설)를 빠뜨렸다. 그것이 이 작업의 하중 부재다 |

`detect-tier.ts` 실측 원문 — `TIER: T2` · `SURFACES: TEST, DEPS, HARNESS` ·
**`UNMAPPED: scripts/workflow/README.md`** (판정 5문 ③ — 게이트 2 요약에 그대로 싣는다).

**절차 기록.** 이 체인의 `bts-start` Step 1 자체가 위 결함으로 죽었다. 실패가 아니라 **재현**이며,
플래그를 붙인 형태로 우회해 진행했다.

### 승인된 범위 (Maxi, 게이트 1 이전)

1. **플래그 전수 부착** — 위 목록 전부.
2. **`.nvmrc` 버전 핀** — 로컬과 CI 를 같은 node 로 정렬.
3. **판별식 신설 + 비-공허 확인** — 플래그 없는 호출문을 red 로 만든다.

## 도메인 정리

**BC. `null` — 미탐지가 아니라 의도된 값이다.** 이 작업은 `backend/modules/**` 를 0파일 건드린다.
도메인 엔티티·유비쿼터스 언어와 접점이 없는 **하네스·가드 표면 전용** 작업이라 BC 축에 놓이지 않는다.
선례 — `docs/plans/2026-08-06-fr-ux-13-f16-…md:132` 이 같은 판정을 기록했다.

**영향 엔티티.** 없음. **새 용어.** 없음 (`glossary.md` 무변경).

**관련 ADR: 없음.** `docs/decisions/` 를 `strip-types` · `타입 스트리핑` · `node 버전` ·
`node-version` · `nvmrc` 로 훑어 **0건**. node 실행 환경에 대한 결정이 이 저장소에 명문화된 적이 없고,
그 부재가 이 결함이 생긴 자리다.

### ★이 작업은 신규 발견이 아니다 — 부채 매핑 `27` 을 닫는다

착수 후 조사에서 확인했다. `TODOS.md` 에 이미 등재돼 있다.

> `## ⬜ 인프라 — pnpm test:workflow 가 **로컬에서만 15건 죽는다** (CI 는 초록 · 신규 · 미착수 · T2)`

마스터 장부 `docs/plans/2026-08-12-debt24-master.md` 의 **매핑 27** · 담당 `미배정` · 분류 `인프라`.
2026-08-12 PR #367 2파 최종 검증에서 발견돼 **main 대조군으로 선재 확증**된 항목이다.

장부는 처방 후보 3개를 이미 열거해 두었고, **승인된 범위와 일치한다.**

| 장부의 처방 후보 | 이번 범위 |
|---|---|
| ① `test:workflow` 에 플래그를 붙인다 | 범위 ① (전 호출문으로 확대) |
| ② `.nvmrc`/`engines` 로 하한을 못 박고 **CI 도 그 값을 쓴다** | 범위 ② |
| ③ CI `node-version` 을 정확한 값으로 고정하고 로컬도 맞춘다 | 범위 ② 에 흡수 (`node-version-file`) |
| **「어느 쪽이든 판별식이 필요하다」** | 범위 ③ |

장부의 마지막 문장이 이 작업의 설계 근거다.

> 「로컬과 CI 의 Node 가 같은가」를 재는 것이 없으면 이 문제는 버전이 또 갈리는 순간 조용히 재발한다.

**⇒ 판별식은 두 축이다.** 플래그 유무(축 A)만 재면 장부의 요구를 절반만 만족한다.

### 장부 수치와 오늘 실측의 차이 — 오류가 아니다

| | 장부 (2026-08-12) | 오늘 (2026-08-14) |
|---|---|---|
| `pnpm test:workflow` | 77 tests / 62 pass / **15 fail** | 82 / 62 pass / **20 fail** |
| 플래그 부착 시 | 213 pass | **296 pass** |

**두 수치 다 그 시점에 참이다.** 사이에 #379·#382 가 판별식을 추가했다.
**선재 오류로 적지 않는다** — 「갱신 안 된 수치」와 「틀린 수치」는 다르다.

**항목 제목은 바꾸지 않는다.** 제목(`…15건 죽는다`)이 `debt-ledger-mapping.test.ts` 의
**조인 키**다. 한쪽만 바꾸면 두 파일이 갈려 판별식이 red 가 되고, 양쪽을 바꾸면 이력 추적이 끊긴다.
재측정치는 본문에 적는다.

## 스펙

### 사용자 시나리오 (Given-When-Then)

```
S1  Given  Maxi 의 로컬 node 가 22.14.0 이고
    When   `pnpm test:workflow` 를 돌리면
    Then   현재는 62 pass / 20 fail (.ts 판별식 전량 미실행) 이다.
           이후에는 296 pass / 0 fail · EXIT=0 이어야 한다.

S2  Given  누군가 새 스크립트를 만들고 `node scripts/새것.ts` 형태의 호출문을 문서·설정에 적었다
    When   CI 가 돌면
    Then   판별식이 **red** 로 그 줄을 파일:라인과 함께 지목해야 한다.

S3  Given  누군가 워크플로우에 `node-version: 24` 를 직접 적었다
    When   CI 가 돌면
    Then   판별식이 red 여야 한다 — 버전 정본이 `.nvmrc` 하나여야 하기 때문이다.

S4  Given  `/bts` 를 새로 시작한다
    When   `bts-start` Step 1 의 classify 호출이 실행되면
    Then   ERR_UNKNOWN_FILE_EXTENSION 없이 분류 JSON 이 나와야 한다.
```

### Jira 대조

**해당 없음** — UI 표면 0파일.

### 요구사항 (R)

**R1. 플래그 전수 부착.** 운영 표면의 모든 `node … <파일>.ts` 호출문이
`--experimental-strip-types` 를 갖는다. 실측 대상 **13건**(아래 §엣지 케이스의 예외 3건 제외 시 10건).

**R2. 버전 정본 단일화.** `.nvmrc` 를 신설하고 값은 **`22.23.2`** — 러너 toolcache 실측값이다.
`setup-node` 를 쓰는 워크플로우 **5곳 전부**(`workflow-scripts-ci:180` ·
`frontend-ci:58,79,102` · `backend-ci:127`)를 `node-version: …` → `node-version-file: .nvmrc` 로 바꾼다.

**R3. 판별식 신설 — 축 A.** 플래그 없는 호출문을 red 로 만든다.
**★R3-a (eng review 교정).** 스캔은 **논리 줄** 단위다 — 줄끝 `\` 를 먼저 이어붙인다.
줄 단위로 보면 `node \` ⏎ `  scripts/x.ts` 를 **양쪽 다 놓친다**(1행에 `.ts` 없음 · 2행에 `node` 없음).
저장소에 이미 이어지는 호출문이 둘 있다(`bts-start/SKILL.md:34` · `backend-ci.yml:197`) —
지금은 `node` 와 `.ts` 가 우연히 같은 줄이라 잡혔을 뿐이다. YAML `run: |` 블록도 이걸로 함께 덮인다.

**R4. 판별식 신설 — 축 B.** node 버전 정본이 `.nvmrc` 하나임을 강제한다.
**★R4-a (eng review 교정 — 부재 기준 → 존재 기준).** 「하드코딩하지 않음」이 아니라
**「`setup-node` 스텝은 전부 `node-version-file: .nvmrc` 를 갖는다」**로 단언한다.
전자는 버전 키를 **아예 안 적은** 잡을 통과시키는데, 그 잡은 러너의 시스템 node 를 써서
**이번 결함이 그대로 재발한다.** 부재를 금지하는 것과 존재를 요구하는 것은 다르다.
**★R4-b.** `.nvmrc` 값이 **22.18 이상**임을 단언한다. 값을 `20` 으로 바꿔도 R4-a 는
초록이다(구체 버전이고 워크플로우가 읽으니까) — 그런데 전부 깨진다. 22.18 은 타입 스트리핑
기본 활성 하한이고, 그 근거를 상수 주석에 남긴다.
**R5. 비-공허 짝.** 스캐너가 0건을 뽑으면 그 자체가 실패다.
**R6. 양성 대조군.** 합성 위반을 실제로 잡고, 합성 정상을 오탐하지 않음을 같은 테스트에서 보인다.
**R7. CI 트리거 짝맞춤.** 판별식의 입력 경로가 `workflow-scripts-ci.yml` 의
`pull_request`·`push` **양쪽** `paths` 에 걸린다. `.nvmrc` 는 **신규 입력이라 현재 없다.**
목록은 손으로 유지하지 않고 판별식의 `INPUTS.coveredBy` 에서 파생시킨다.

**R8. 부채 장부 동기화 (같은 PR).** 매핑 `27` 을 닫는다.
`TODOS.md` 항목 ⬜→✅ · 마스터 §전수 매핑 행 ⬜→✅ + PR 열 `미배정`→`#383` ·
§PR 별 집계 행의 담당 `미배정`→`#383`. **건수는 어디에도 적지 않는다**(장부 ★규칙).

### 비기능 요구사항 (NFR)

**N1. 층이지 중복이 아니다.** R1 과 R2 는 겹쳐 보이지만 서로를 대신하지 못한다.

| | 막는 것 | 못 막는 것 |
|---|---|---|
| R2 버전 핀 | 로컬·CI 가 다른 node 를 쓰는 것 | **Maxi 가 nvm/mise 를 안 쓰면 무효.** `.nvmrc` 는 선언일 뿐 강제가 아니다 |
| R1 플래그 | 22.6~22.17 어디서든 명령이 도는 것 | 새 호출문이 플래그 없이 들어오는 것 |
| R3·R4 판별식 | 위 둘의 되돌아감 | 자기 자신이 지워지는 것 (→ 뮤테이션으로 잰다) |

**★R2 만으로 끝내지 않는 이유가 여기 있다.** `.nvmrc` 를 넣어도 Maxi 의 셸이 그 파일을 읽지 않으면
로컬은 여전히 22.14 다. 그때 R1 이 명령을 살려두고 R3 이 되돌아감을 막는다.

**N2. 판별식은 `pnpm test:workflow` 에 자동 편입된다.** 글롭 `scripts/**/*.test.ts` 실측 확인 —
`scripts/workflow/` 에 두면 CI 잡을 새로 만들 필요가 없다.

**N3. 실패 메시지가 처방을 담는다.** 막힌 사람이 메시지만 보고 고칠 수 있어야 한다 (형제 판별식 관례).

### API 인터페이스 · 데이터 모델 변경

**둘 다 해당 없음** — REST 0건 · 마이그레이션 0건 · 스키마 무변경.

### 엣지 케이스

**E1. 산문·픽스처를 실행문으로 오인하는 것.** 실측에서 3건 나왔다. **고치면 안 된다.**

| 위치 | 정체 | 고치면 |
|---|---|---|
| `script-test-coverage.test.ts:5` | 「봉합 전에는 …였고」 — **과거 서술** | 사실이 바뀐다 |
| `script-test-coverage.test.ts:81` | 파서 입력 형태 **예시** (블록 주석) | 설명이 입력과 어긋난다 |
| `script-test-coverage.test.ts:213` | **양성 대조군 픽스처** — 좁은 훑기를 일부러 넣는다 | 그 테스트가 깨진다 |

**처방 — 예외는 (파일 · 정확한 명령 문자열 · 사유) 3요소 허용목록으로 둔다.**
라인 번호로 잡지 않는다(밀리면 조용히 어긋난다). 그리고 **쓰이지 않는 허용목록 항목을 red 로 만든다** —
줄이 바뀌면 매칭이 끊겨 사람이 다시 판단하게 된다. 죽은 예외가 남는 것을 막는 장치다.

**E2. 정규식이 조용히 놓치는 것.** 프로토타입 1차가 **`package.json:9,10` 을 통째로 놓쳤다.**
선행 문자 클래스에 따옴표가 없어서였다 — JSON 은 명령을 `"…"` 에, 마크다운은 `` `…` `` 에 담는다.
**가장 중요한 호출문(`test:workflow`)이 정확히 그 구멍에 있었다.**
⇒ 선행 클래스는 `[\s;|&("'`+백틱`]` 을 전부 포함하고, 이 구멍 자체를 양성 대조군에 못박는다.

**E3. `.tsx` 오탐.** `apps/web/**/*.tsx` 는 대상이 아니다. `\.[cm]?ts(?![a-zA-Z0-9])` 로 배제.
**E4. `node_modules` 오탐.** `node` 뒤에 공백을 요구하면 `node_modules/` 는 걸리지 않는다.
**E5. `.mjs` 는 대상이 아니다.** 타입 스트리핑과 무관 — `build-doc-index.mjs` 등은 플래그가 불필요하다.

**E6. 과거 기록을 고치는 것.** `docs/plans/**` 에만 플래그 없는 매칭이 **32건** 있다.
전부 그 시점에 실제로 친 명령의 기록이다. `docs/{plans,specs,decisions}/**` 를 스캔에서 제외한다.

**E7. ★그 제외가 만드는 사각 — 명시한다.** `docs/plans/2026-08-12-debt24-master.md:38` 은
과거 기록이 아니라 **살아 있는 부채 장부 정본**인데 제외 규칙에 함께 걸린다.
**이번 PR 에서 손으로 고치되, 판별식이 그것을 지키지 못한다는 사실을 테스트 주석과 게이트 2 요약에 남긴다.**
디렉터리 규칙에 파일 하나짜리 구멍을 내지 않는 쪽을 택했다 — 구멍은 유지 대상이 되고, 다음 사람이
그 예외의 이유를 모른다.

### 제약 조건

- **C1.** `docs/plans` · `docs/specs` · `docs/decisions` · `.claude/_archive` · `Maxi_wiki` 의
  호출문을 고치지 않는다 (E6·E7 예외 1건 제외).
- **C2.** `CLAUDE.md` 명령어 표는 `pnpm test:workflow` 줄만 본다. **다른 줄을 함께 손보지 않는다.**
- **C3.** 뮤테이션 검증은 **GREEN 선커밋 뒤**에 한다 (`CLAUDE.md` 함정).
- **C4.** 검사 대상은 **디렉터리 스캔 + 제외**다. 열거형 화이트리스트로 두지 않는다 —
  이 결함의 재발 경로가 **「새 파일이 새 호출문을 들고 들어오는 것」**이라 열거는 원리적으로 못 잡는다.

### 측정 가능한 완료 기준

| # | 기준 | 재는 법 |
|---|---|---|
| 1 | `pnpm test:workflow` 가 **EXIT=0** | 파이프 없이 `echo "EXIT=$?"` (통과 건수로 판정 금지) |
| 2 | 같은 명령이 `.ts` 판별식을 실제로 돈다 | 296+ tests · fail 0 |
| 3 | 문서 그대로 친 `node … classify-task.ts` 가 산다 | 분류 JSON 출력 |
| 4 | 축 A 가 합성 위반을 잡는다 | 양성 대조군 초록 |
| 5 | 축 A·B 를 **일부러 끊으면 red** | 뮤테이션 1회 (GREEN 선커밋 뒤) |
| 6 | `.nvmrc` 가 CI 트리거 양쪽 `paths` 에 걸린다 | R7 판별식 |
| 7 | 매핑 `27` 이 두 파일에서 함께 ✅ | `debt-ledger-mapping.test.ts` |
| 8 | 인덱스 drift 0 | `build-doc-index.mjs --check` EXIT=0 |

## Sanity Check

**❓ 발견 3건 — 스스로 보강했다 (1회).**

**① 「호출문 전수」의 경계가 스펙에 없었다.** 초안은 착수 시 실측한 목록(7건)을 그대로 요구사항으로
옮기려 했다. 프로토타입을 돌려 **13건**으로 늘었고 그중 **3건이 고치면 안 되는 것**임이 드러났다.
개수를 요구사항에 박지 않고 **규칙(스캔 + 제외 + 허용목록)** 으로 바꿨다 —
`CLAUDE.md` 함정 「지시문에 개수를 쓰지 마라」.

**② 판별식이 한 축뿐이었다.** 플래그 유무만 재면 `.nvmrc` 가 지워지거나 워크플로우가 버전을
다시 하드코딩해도 초록이다. 장부 매핑 `27` 이 요구한 것은 **「로컬과 CI 의 Node 가 같은가」**이므로
축 B(R4)를 신설했다.

**③ 부채 장부 동기화가 빠져 있었다.** 착수 시점엔 이 작업이 매핑 `27` 인 줄 몰랐다.
R8 로 추가 — 체크포인트의 남은 위험 4번(「PR 이 부채를 닫고도 장부를 안 옮기면 조용히 통과」)이
정확히 이 자리다.

**Maxi 결정이 필요한 것 — 1건.** `.nvmrc` 값을 **정확한 패치(`22.23.2`)** 로 박을지
**메이저(`22`)** 로 느슨히 둘지. 전자는 두 환경을 진짜로 같게 만드는 대신 node 패치가 나올 때마다
커밋을 요구한다. 후자는 `.nvmrc: 22` 가 로컬에서 **이미 깔린 22.14 를 고르므로 갈림이 그대로 남는다** —
즉 후자는 이 부채를 안 닫는다. **스펙은 전자를 채택했고, 게이트 1 에서 확인받는다.**

## Plan

> **뼈대.** `scripts/workflow/worktree-hook-wiring.test.ts` 의 구조를 그대로 따른다 —
> `INPUTS`+`coveredBy` 파생 · 비-공허 짝 · 양성 대조군 · CI 트리거 짝맞춤 · 실패 메시지에 처방.
> **새 양식을 발명하지 않는다.**
>
> **CLAUDE.md 는 바꾸지 않는다.** 명령어 표의 `pnpm test:workflow  # 판별식 — CI 와 같은 목록`
> 은 문구가 이미 옳다 — 이번 PR 이 그 문장을 **처음으로 참으로 만든다.** 고칠 것이 없다.

### Task 1. 축 A 판별식 신설 — 플래그 없는 호출문을 red 로 만든다

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/node-ts-invocation.test.ts`]
- depends-on: []

**RED**:
- 파일: `scripts/workflow/node-ts-invocation.test.ts` (신규)
- **작성 즉시 red 다** — 저장소에 위반이 실재하기 때문이다. 이것이 재현 테스트다.
- 테스트 구성 (4종):
  ```
  ① 비-공허 짝    스캔 파일 수 > 0 · 매칭(플래그 유무 무관) > 0 · 대조군(플래그 있음) > 0
  ② 축 A          플래그 없는 호출문 = []  ← 여기가 red
  ③ 양성 대조군    합성 위반을 잡고 합성 정상을 오탐하지 않는다
  ④ 허용목록 무결   쓰이지 않는 허용목록 항목 = []
  ```
- 실패 메시지 (예상): 축 A 가 **10건**을 파일:라인·명령 원문과 함께 열거

**GREEN**: 이 task 에서는 없다. 위반을 고치는 것은 Task 2 다 — **red 를 커밋으로 남긴다**(`test:`).

**REFACTOR**: 정규식·제외 규칙·허용목록을 상수로 올리고 각각에 「왜 이 모양인가」 주석.

**★양성 대조군에 반드시 넣을 항목** (프로토타입이 실제로 뚫린 자리):
```
잡아야 함:  node scripts/x.ts                       (줄머리)
           "classify": "node scripts/x.ts"          ← ★따옴표. 1차 프로토타입이 이걸 놓쳤다
           `node --test 'scripts/**/*.test.ts'`     ← ★백틱 + 따옴표 글롭
           foo | node scripts/x.ts                  (파이프)
           $(node scripts/x.ts --cache)             (명령 치환)
           node \⏎  scripts/x.ts                    ← ★★eng review 발견. 줄 단위면 양쪽 다 빠진다
잡으면 안 됨: node --experimental-strip-types scripts/x.ts   (이미 봉인됨)
           node scripts/build-doc-index.mjs                 (.mjs 는 대상 아님)
           node_modules/.bin/lint-staged                    (node_ 오탐)
           apps/web/src/routes/x.tsx                        (.tsx 오탐)
           classify-task.ts 는 분류를 한다                    (산문 — 앞에 node 없음)
```

**검증**: `node --experimental-strip-types --test scripts/workflow/node-ts-invocation.test.ts`
→ 축 A **fail 1** · 나머지 pass (비-공허 짝이 초록이어야 축 A 의 red 가 의미를 갖는다)

---

### Task 2. 호출문 전수에 플래그를 붙인다 (축 A green)

**메타**.
- agent: `backend-engineer`
- files: [`package.json`, `.claude/skills/bts-start/SKILL.md`, `scripts/workflow/README.md`, `scripts/workflow/doc-index-coverage.test.ts`, `docs/plans/2026-08-12-debt24-master.md`]
- depends-on: [1]

**RED**: Task 1 이 남긴 red 를 그대로 승계한다. 새로 쓰지 않는다.

**GREEN**: 아래 **10곳**에 `--experimental-strip-types` 부착.

| 파일 | 위치 |
|---|---|
| `package.json` | `9` classify · `10` test:workflow |
| `.claude/skills/bts-start/SKILL.md` | `34` |
| `scripts/workflow/README.md` | `16` `19` `82` `85` `94` |
| `scripts/workflow/doc-index-coverage.test.ts` | `2` (`// 실행.` 주석 — 복사하면 죽는다) |
| `docs/plans/2026-08-12-debt24-master.md` | `38` — **판별식 사각. 손으로 고친다** (스펙 E7) |

**REFACTOR**: `scripts/workflow/README.md` 머리(`> Node 24+ …`)를 `.nvmrc` 를 가리키게 갱신.

**⚠️ 하지 말 것 (스펙 C1)**: `docs/plans` 의 나머지 · `docs/specs` · `docs/decisions` ·
`.claude/_archive` · `Maxi_wiki`. 거기 **32건**은 그 시점에 실제로 친 명령의 기록이다.

**검증**:
- `node --experimental-strip-types --test scripts/workflow/node-ts-invocation.test.ts` → 전량 pass
- `pnpm test:workflow` → **EXIT=0** (파이프 없이. 통과 건수로 판정 금지)
- `node scripts/workflow/classify-task.ts --title "x"` → 플래그 없이도 **문서 그대로 산다**

---

### Task 3. 축 B 판별식 — 워크플로우가 node 버전을 하드코딩하지 않는다

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/node-ts-invocation.test.ts`]
- depends-on: [1]

**근거.** 장부 매핑 `27` 이 명시적으로 요구한 축이다 — 「로컬과 CI 의 Node 가 같은가를 재는 것이
없으면 이 문제는 버전이 또 갈리는 순간 조용히 재발한다」. 축 A 만으로는 `.nvmrc` 가 지워지거나
워크플로우가 버전을 다시 박아도 **초록**이다.

**RED**:
- 테스트 ⑤ **모든** `setup-node` 스텝이 `node-version-file: .nvmrc` 를 갖는다 (R4-a · **존재 기준**)
  → 현재 **5곳 전부 위반**
- 테스트 ⑥ `.nvmrc` 가 실재하고 **값이 22.18 이상**이다 (R4-b) → 현재 **파일 부재**
- 테스트 ⑦ `INPUTS.coveredBy` 가 `workflow-scripts-ci.yml` 의 `pull_request`·`push`
  **양쪽** `paths` 에 걸린다 → `.nvmrc` 가 양쪽에 **없다**
- 실패 메시지 (예상): 버전 파일을 안 읽는 `setup-node` 스텝 5곳 열거 + `.nvmrc` 부재 + paths 누락 2건

**★존재 기준인 이유 (eng review 교정).** 「`node-version:` 하드코딩 금지」로 적으면
버전 키를 **아예 안 적은** 잡이 통과한다. 그 잡은 러너의 시스템 node 를 쓰므로
**이번 결함이 그대로 재발**한다. 없는 것을 금지하지 말고 있어야 할 것을 요구한다.

**GREEN**: 없다 (Task 4). **red 를 커밋으로 남긴다.**

**REFACTOR**: `setup-node` 잡 탐색을 앵커 정규식으로 — **부분 문자열 매칭 금지**
(체크포인트 ★교훈: `:modules:app` 을 파일 전체에서 찾다가 머리말 주석이 대신 만족시켜
조립 부팅 잡을 통째로 지워도 초록이었다).

**검증**: `node --experimental-strip-types --test scripts/workflow/node-ts-invocation.test.ts`
→ ⑤⑥⑦ fail · ①②③④ pass

---

### Task 4. `.nvmrc` 신설 + setup-node 5곳 전환 + CI paths 배선 (축 B green)

**메타**.
- agent: `backend-engineer`
- files: [`.nvmrc`, `.github/workflows/workflow-scripts-ci.yml`, `.github/workflows/frontend-ci.yml`, `.github/workflows/backend-ci.yml`]
- depends-on: [3]

**RED**: Task 3 의 red 승계.

**GREEN**:
1. `.nvmrc` 신설 · 값 **`22.23.2`** (러너 toolcache 실측값. 게이트 1 확인 대상)
2. `node-version:` → `node-version-file: .nvmrc` — `workflow-scripts-ci:180` ·
   `frontend-ci:58,79,102` · `backend-ci:127` **5곳 전부**
3. `workflow-scripts-ci.yml` 의 `pull_request`·`push` `paths` **양쪽**에 `.nvmrc` 추가

**REFACTOR**: `backend-ci.yml:121` 의 「22.6 미만이 되면…」 주석을 `.nvmrc` 기준으로 갱신 —
그 주석이 서술하는 전제가 이번에 바뀐다.

**주의 (eng review · 확신도 6/10).** 정확한 패치를 박으면 러너 toolcache 에 그 버전이 없을 때
`setup-node` 가 매 실행마다 내려받는다. 지금은 `verify-runner-health.sh` 가 `toolcache node
(22.23.2)` 를 찍어 적중하지만, **값을 올릴 때는 그 출력과 맞춰야 한다.** `.nvmrc` 옆 주석에 남긴다.

**검증**:
- 판별식 전량 pass · `pnpm test:workflow` **EXIT=0**
- `cat .nvmrc` 가 러너 toolcache 값과 일치
- ★**CI 초록 확인은 push 후 실물로** — 워크플로우 문법은 로컬에서 못 잰다

---

### Task 5. 부채 장부 동기화 — 매핑 `27` 을 닫는다

**메타**.
- agent: `backend-engineer`
- files: [`TODOS.md`, `docs/plans/2026-08-12-debt24-master.md`]
- depends-on: [2, 4]

**왜 마지막인가.** 고쳐지지 않은 것을 ✅ 로 옮기면 그것이 바로 이 장부가 막으려는 것이다.

**RED**: `debt-ledger-mapping.test.ts` 는 **이미 있다.** 한쪽만 옮겨 red 를 1회 본다 —
`TODOS.md` 만 ✅ 로 옮기고 마스터를 그대로 두면 「마스터가 ✅ 라 적은 항목은 장부에서도 ✅」의
역방향(장부⬜↔마스터⬜ 집합 일치)이 깨져 red. **이 red 를 실제로 본 뒤 마스터를 옮긴다.**

**GREEN**:
- `TODOS.md` 항목 `⬜` → `✅` (resolved 섹션 규칙은 `todos-resolved-section-purity.test.ts` 준수)
- 마스터 §전수 매핑 `27` 행 `⬜`→`✅` · PR 열 `미배정`→`#383`
- 마스터 §PR 별 집계 행 담당 `미배정`→`#383`
- 본문에 **재측정치**(오늘 82/62/20 · 플래그 시 296) 기재 + 장부 원수치가 2026-08-12 기준임을 명시

**⚠️ 절대 금지**:
- **항목 제목 변경.** 제목이 `debt-ledger-mapping.test.ts` 의 **조인 키**다. `15건` 을
  `20건` 으로 고치면 두 파일이 갈려 red 가 되고, 양쪽을 고치면 이력 추적이 끊긴다.
- **건수 기재.** 장부 ★규칙 — 「수를 적으면 그것이 세 번째 목록이 되어 또 갈린다」.
- `TODOS.md` 가 `package.json` 의 깨진 값을 인용한 자리는 **선행 `node ` 없이** 재서술한다
  (`` `--test 'scripts/**/*.test.ts'` (플래그 없음)``). 허용목록을 키우지 않기 위함이다.

**검증**: `node --experimental-strip-types --test scripts/workflow/debt-ledger-mapping.test.ts`
→ 전량 pass. `todos-resolved-section-purity` 도 pass.

---

### Task 6. 비-공허 확인 — 뮤테이션 축 A·B 각 1회

**메타**.
- agent: `backend-engineer`
- files: []  (원복 대상. **커밋되는 변경 0**)
- depends-on: [2, 4, 5]

**★선행 조건 — GREEN 을 먼저 커밋하고 push 한다.** `CLAUDE.md` 함정:
「뮤테이션 검증은 GREEN 선커밋 뒤. 미커밋 원복은 소실이다.」

**절차**:
| # | 끊는 곳 | 기대 |
|---|---|---|
| M1 | `package.json:10` 에서 플래그를 뺀다 | 축 A **red** · 그 줄을 지목 |
| M2 | `frontend-ci.yml` 한 곳을 `node-version: 22` 로 되돌린다 | 축 B **red** · 그 잡을 지목 |
| M3 | `.nvmrc` 를 지운다 | 축 B **red** |
| M4 | 허용목록 항목 1개의 문자열을 한 글자 바꾼다 | 허용목록 무결 **red** (죽은 예외 탐지) |
| M5 | 호출문 하나를 `node \`⏎`  경로.ts` 로 쪼갠다 | 축 A **red** (R3-a 논리 줄 이어붙이기가 실제로 도는가) |
| M6 | `setup-node` 스텝 하나에서 버전 키를 **통째로 지운다** | 축 B **red** (R4-a 존재 기준이 실제로 도는가) |
| M7 | `.nvmrc` 를 `20.11.0` 으로 바꾼다 | 축 B **red** (R4-b 하한이 실제로 도는가) |

각 뮤테이션은 **red 를 눈으로 본 뒤 `git checkout --` 으로 원복**한다.
**red 가 안 나오면 그 판정은 공허하다** — 그 자리에서 판별식을 고친다.

**검증**: 위 표의 뮤테이션이 **전부** red 확인 후 `git status --porcelain` 이 비어 있을 것 (원복 완결).
개수를 세지 않는다 — 표의 행을 하나씩 지워가며 전수 확인한다(`CLAUDE.md` 함정: 「N건」은 눈가리개).

## Plan 메타

- **task 수**: 6 · **예상 wave**: 4 (1 → 2·3 → 4 → 5 → 6. 2와 3은 `files` 교집합 0 이라 병렬 가능)
- **구현 규율**: TDD red-first. T2 이므로 `test:` → `fix:` **커밋 순서가 리뷰 대상**이다.
  Task 1·3 은 **red 를 커밋으로 남긴다** — green 과 같은 커밋에 넣으면 red 를 본 증거가 사라진다.
- **추가 검증**: `pnpm test:workflow` **EXIT=0**(파이프 금지) ·
  `node scripts/build-doc-index.mjs --check` EXIT=0 · `bash scripts/verify-master-plan.sh` ·
  push 후 **CI 실물 초록** (워크플로우 문법은 로컬에서 못 잰다)
- **BC 격리**: 해당 없음 (BC 0곳)
- **마이그레이션**: 없음 · **신규 의존성**: 없음 · **프로덕션 코드**: 0줄

## 리뷰 결과

### 라우팅 이탈 1건 (게이트 1 에서 확인받을 것)

`bts-review-plan` 렌즈 표는 `TYPE ∈ {bugfix, chore, qa}` → **skip** 이다.
그런데 `type` 을 `chore` 로 정한 것은 **내 정정**이고(classify 원출력은 `backend` → `plan-eng-review`
라우팅), 그 정정이 **리뷰 게이트 하나를 조용히 없앴다.** 표면이 `GUARD_CI`/T2 이고 스스로 꼽은
설계 의문이 7건이라, 형식 토큰으로 리뷰를 건너뛰지 않고 **`/plan-eng-review` 를 태웠다.**

### `/plan-eng-review` — BLOCKER 0 · 교정 3 · 주의 2

**★Prior learning applied: `bts-node-strip-types` (confidence 9/10, 2026-08-09).**
이 결함은 **이미 세 곳에 기록돼 있었다** — learnings DB · `mutation-probe.sh:19` ★주석 ·
`TODOS.md` 매핑 27. 그런데도 안 고쳐졌다. **몰라서가 아니라 강제하지 않아서**라는 이 plan 의
논지가 실측으로 재확인된다. (그 learning 에 오류 1건 — 「CI 는 Node 24」라 적혔으나 실측은
toolcache **22.23.2**. `externals/node24` 는 러너 자체 엔진이지 `setup-node` 가 쓰는 값이 아니다.
머지 후 정정 등재.)

| # | 판정 | 내용 | 반영 |
|---|---|---|---|
| A1 | **P1** (9/10) | 스캐너가 줄 단위라 `node \`⏎`  경로.ts` 를 **양쪽 다 놓친다** | **R3-a** 신설 · 양성 대조군 + **M5** |
| A2 | **P1** (8/10) | 축 B 가 부재 기준이라 **버전 키를 아예 안 적은** 잡을 통과시킨다 | **R4-a** 존재 기준으로 전환 + **M6** |
| T1 | **P2** (8/10) | `.nvmrc` 값의 하한을 아무도 안 잰다 (`20` 으로 바꿔도 초록) | **R4-b** 신설 + **M7** |
| A3 | 주의 (6/10) | 정확한 패치 핀은 toolcache 미적중 시 매 실행 다운로드 | Task 4 주의 + `.nvmrc` 주석 |
| P1 | 주의 (7/10) | 스캐너가 저장소를 훑는다 | 프로토타입 즉시 완료 — 조치 없음 |

**이슈 2A (DRY · Maxi 결정).** 디렉터리 훑기를 공유 헬퍼로 뽑지 않고 **판별식 안에 직접 쓴다.**
근거 — 형제 판별식이 전부 자기 훑기를 갖는 기존 관례이고, **강제 장치끼리 결합하면 공유 모듈
버그 1개가 전장을 눈멀게 한다.** 중복 약 15줄은 그 대가로 받아들인다.

**Architecture 2 · Code Quality 1 · Test 3 gaps · Performance 0.**

### 외부 목소리 — 실행 못 함 (명시)

`CODEX_MODE: not_installed` 이고 이 세션은 서브에이전트 임의 기동이 금지돼 있다.
**0종으로 조용히 통과시키지 않는다.** 체인 [6] `bts-codereview` 가 프로젝트 자체 독립 리뷰
2종(T2)을 별도로 돌린다 — 그것이 이 저장소의 정본 리뷰 요건이다.

### NOT in scope (고려했고 명시적으로 미룬 것)

| 항목 | 사유 |
|---|---|
| 부채 매핑 `25` (worktree `node_modules` 절차) 닫기 | **이미 해소돼 있는데 장부가 ⬜ 다**(#377 에서 들어옴 · `bts-start/SKILL.md:66-67` + `worktree-hook-wiring.test.ts:464` 가 강제). Maxi 결정 — **기록만 하고 다음 PR.** 25 가 전부 해소됐는지는 별도 확인이 필요하다 |
| `package.json engines` 를 `>=22.18` 로 상향 | 지금 로컬이 22.14 라 `pnpm install` 자체가 막힐 수 있다. node 를 먼저 올린 뒤 별건으로 |
| `docs/plans/**` 의 나머지 플래그 없는 호출문 | 그 시점에 실제로 친 명령의 **기록**이다. 고치면 사실이 바뀐다 |
| 공유 훑기 헬퍼 추출 | 이슈 2A 에서 Maxi 가 기각 |
| `CLAUDE.md` 수정 | 명령어 표 문구가 이미 옳다. 이 PR 이 그 문장을 **처음으로 참으로 만든다** |

### What already exists (재사용 / 재발명 여부)

| 기존 자산 | 이 plan 의 처리 |
|---|---|
| `worktree-hook-wiring.test.ts` | **구조 정본으로 그대로 승계** — INPUTS+coveredBy 파생 · 비-공허 짝 · 양성 대조군 · CI 트리거 짝맞춤. 새 양식 발명 0 |
| `script-test-coverage.test.ts` 의 `collectTestFiles` | 재귀 훑기 **선례로 참조**하되 공유하지 않는다 (이슈 2A) |
| `debt-ledger-mapping.test.ts` | **이미 있다.** Task 5 가 그걸 red 로 한 번 보고 쓴다 — 새로 만들지 않는다 |
| `.nvmrc` + `setup-node` `node-version-file` | **[Layer 1] 런타임·액션 내장 기능.** 커스텀 발명 0 |
| `verify-runner-health.sh` 의 toolcache 출력 | `.nvmrc` 값의 실측 근거로 인용 |

### 실패 양식 (새 코드경로별 · 프로덕션에서 어떻게 죽나)

| 경로 | 현실적 실패 | 테스트 | 에러 처리 | 사용자가 보는 것 |
|---|---|---|---|---|
| 스캐너 파일 훑기 | 권한 없는 디렉터리 · 심볼릭 순환 | 비-공허 짝이 0건을 실패로 만든다 | 심볼릭 건너뜀 | 판별식 red (침묵 아님) |
| 정규식 매칭 | 새 호출 형태를 놓침 (A1 계열) | 양성 대조군 + M5 | — | **★침묵할 수 있는 유일한 자리** — 그래서 뮤테이션이 필수 |
| 허용목록 | 죽은 예외가 남아 실제 위반을 가림 | 무결 단언 + M4 | — | 판별식 red |
| `.nvmrc` 읽기 | 파일 부재 · 값 파손 | R4-b + M3·M7 | — | 판별식 red |
| CI `node-version-file` | 러너 toolcache 미적중 → 다운로드 | 없음 (원리적으로 로컬 불가) | setup-node 자체 재시도 | CI 느려짐. **A3 주의** |

**critical gap 0건** — 「테스트 없음 + 에러 처리 없음 + 침묵」 3조건을 동시에 만족하는 경로가 없다.
정규식 누락만이 침묵 후보이고, 그 자리는 양성 대조군 + 뮤테이션 2중으로 덮었다.

### TODO 제안 1건 (게이트 1 에서 함께 판단)

**무엇.** 살아 있는 정본이 `docs/plans/` 안에 있어 판별식 사각이 된다 (스펙 E7).
**왜.** `docs/plans/2026-08-12-debt24-master.md` 는 과거 기록이 아니라 **운영 중인 부채 장부**인데,
과거 plan 들과 같은 디렉터리에 있어 스캔 제외에 함께 걸린다. 이번엔 손으로 고치지만 다음엔 또 샌다.
**선택지.** ① 부채 장부를 `docs/plans/` 밖(예: `docs/ledger/`)으로 옮긴다 ②
frontmatter 로 「살아 있음/기록」을 표시하고 스캐너가 그걸 읽는다 ③ 그대로 두고 사각을 감수한다.
**의존.** 없음. **비용.** ①은 링크·인덱스 전수 갱신을 부른다.

### 병렬화 전략

| 단계 | 건드리는 모듈 | 의존 |
|---|---|---|
| Task 1 축 A | `scripts/workflow/` | — |
| Task 2 플래그 부착 | 루트 · `.claude/` · `scripts/` · `docs/plans/` | 1 |
| Task 3 축 B | `scripts/workflow/` | 1 |
| Task 4 `.nvmrc` + CI | 루트 · `.github/workflows/` | 3 |
| Task 5 장부 | `TODOS.md` · `docs/plans/` | 2, 4 |
| Task 6 뮤테이션 | — | 2, 4, 5 |

```
Lane A: Task1 → Task3 (순차 · 같은 파일)
Lane B: Task2        (Task1 이후 · 독립)
        Task1 ─┬→ Task3 → Task4 ─┬→ Task5 → Task6
               └→ Task2 ─────────┘
```

**충돌 주의.** Task 2 와 Task 5 가 **둘 다 `docs/plans/2026-08-12-debt24-master.md`** 를 만진다
(전자는 `:38` 호출문 · 후자는 매핑 행). `depends-on` 으로 직렬화돼 있어 안전하지만 **worktree 를
쪼개면 충돌한다** — 이 PR 은 단일 worktree 로 간다.

**실질 병렬 이득이 작다.** task 가 6개고 대부분 5분 미만이라 순차로 간다.

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — (chore · 제품 변경 0) |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | — | — |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR | 6 issues, 0 critical gaps |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | — (UI 0파일) |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

- **OUTSIDE VOICE:** 실행 못 함 — `CODEX_MODE: not_installed` · 서브에이전트 임의 기동 금지.
  대체가 아니라 **부재로 기록**한다. 체인 [6] `bts-codereview` 가 T2 독립 리뷰 2종을 별도 수행.
- **VERDICT:** ENG CLEARED — 교정 3건(R3-a · R4-a · R4-b) 전부 plan 에 반영됨. 구현 착수 가능.
  단 **게이트 1(Maxi 승인)이 남아 있다** — 이 저장소의 T2 절차는 gstack 판정과 별개다.

NO UNRESOLVED DECISIONS
