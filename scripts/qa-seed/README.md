# QA 시드 — 프로덕션 QA 용 데이터 적재

`bts.maxihan.com` 같은 **실서버**에 QA 용 프로젝트·이슈·스프린트·보드·대시보드를 REST API 로 적재한다.

## 왜 psql 직접 주입이 아닌가

앱 부팅 경로의 dev 시드는 prod 에서 **의도적으로 꺼져 있다**
(`NonProdDevSeedRunner` 허용목록 `default|dev|local` · ADR `2026-07-29-assembly-nonprod-dev-seed` D4).
`ProdDevSeedAbsenceBootTest` 가 그 부재를 단언하므로 우회 대상이 아니다.

DB 직접 INSERT 도 쓰지 않는다 — pgmq 이벤트·FTS 인덱스·changelog·알림이 생기지 않아
대시보드와 검색이 빈 채로 남고, 그러면 QA 목적 자체가 무너진다. 그래서 **실제 생성 경로**인 REST 만 쓴다.

## 사전 조건

- **`CREATE_PROJECT` 전역 권한**을 가진 계정 (`SYSTEM_ADMIN` 없이도 성립한다).
- Node 22+ (`fetch` · `FormData` · `File` 전역).

### MFA 가 켜진 계정

`bts.maxihan.com` 의 `admin` 은 TOTP 가 켜져 있다. 이때 로그인은 **401 이 아니라 200 + `mfa_required`**
로 응답한다 (`AuthController.completeLogin`) — `res.ok` 만 보고 `access_token` 을 꺼내면 `undefined` 가
담겨 이후 전 요청이 401 로 죽는다. 이 스크립트는 그 분기를 명시적으로 처리한다.

통과 경로는 둘이다.

```bash
# (a) TOTP — 1회만 입력한다. trust_device 로 신뢰 디바이스 쿠키를 받아 두므로
#     실행 도중 access token(15분)이 만료돼 재로그인해도 코드를 다시 묻지 않는다.
node scripts/qa-seed/seed.mjs --totp=123456 --yes

# (b) PAT — 설정 > 개인 액세스 토큰에서 발급. 비밀번호 로그인 자체를 건너뛴다.
node scripts/qa-seed/seed.mjs --pat=<토큰> --yes      # BTS_QA_USERNAME/PASSWORD 불필요
```

긴 적재에는 **(b) PAT 가 낫다** — 비밀번호가 셸 히스토리에 남지 않고, TOTP 창(30초)에 쫓기지 않는다.

## 실행

```bash
export BTS_QA_USERNAME='<계정>'
export BTS_QA_PASSWORD='<비밀번호>'

# 1) 쓰기 없이 접속·권한·카탈로그만 확인
node scripts/qa-seed/seed.mjs --base-url=https://bts.maxihan.com --dry-run

# 2) 실제 적재
node scripts/qa-seed/seed.mjs --base-url=https://bts.maxihan.com --yes

# 3) 회수
node scripts/qa-seed/rollback.mjs --base-url=https://bts.maxihan.com --yes
```

| 옵션 | 기본값 | 뜻 |
|---|---|---|
| `--base-url` | `https://bts.maxihan.com` | 대상 서버 |
| `--yes` | (없음) | 이게 없으면 계획만 찍고 종료한다 |
| `--dry-run` | (없음) | 로그인 + 읽기만 하고 종료 |
| `--issues=N` | `150` | 프로젝트당 이슈 수. 상태 비율은 유지된다 |
| `--concurrency=N` | `4` | 동시 요청 수. prod 백엔드는 메모리 1.5GB 라 올리지 마라 |
| `--seed=N` | `20260825` | 난수 시드. 같으면 같은 데이터가 나온다 |
| `--provider` | `local` | 인증 provider |
| `--totp=NNNNNN` | (없음) | MFA 계정용 1회 코드. 신뢰 디바이스로 등록돼 재로그인은 코드가 필요 없다 |
| `--pat=<토큰>` | (없음) | 개인 액세스 토큰. 주면 비밀번호 로그인을 아예 하지 않는다 |

## 만들어지는 것

| 대상 | 수량 | 비고 |
|---|---|---|
| 프로젝트 | 3 | `QAWEB` · `QAAPI` · `QAOPS` — 전부 `QA` 접두사 |
| 이슈 | 450 | open 135 · in_progress 90 · in_review 66 · done 114 · closed 45 |
| 댓글 | ~400 | 이슈의 40% 에 1~3건 |
| 첨부 | ~78 | 저장소의 **실제 앱 스크린샷** PNG |
| 스프린트 | 9 | 프로젝트당 완료 1 · 진행 1 · 예정 1 |
| 보드 | 3 | 프로젝트당 1 |
| 대시보드 | 2 | 활성 가젯 6종을 모두 쓴다 |

모든 이슈에 라벨 **`qa-seed`** 가 붙는다. 실 데이터와 섞였을 때 이 라벨이 유일한 판별자다.

## 매니페스트와 회수

`manifest.<호스트>.json` 에 만든 것을 전부 기록한다 (gitignored). 롤백은 이 파일만 본다.

- 매니페스트의 `baseUrl` 과 `--base-url` 이 다르면 **거부한다** — 엉뚱한 서버를 지우지 않기 위해서다.
- 중단 후 다시 돌리면 매니페스트에 있는 이슈는 건너뛴다 (이어받기).
- 회수 순서는 대시보드 → 스프린트 → 이슈(소프트 삭제) → 프로젝트(아카이브).
  **프로젝트 하드 삭제 API 는 없다.** 아카이브가 사실상의 회수 경계다.
- 보드는 삭제 API 가 없어 프로젝트 아카이브에 함께 가려진다.

## 검증

`scripts/qa-seed/` 는 목 서버로 전 경로를 실증한 뒤 커밋했다 — 이슈 450건 · 전환 699회 ·
댓글 395건 · 첨부 78건 · 스프린트 상태 전이 · 대시보드 가젯 검증 · 롤백 461건 삭제까지
실패 0. 목은 실제 컨트롤러의 응답 봉투(특히 `GET /users` 가 **맨 배열**인 점)와
`software-default` 전환 그래프, 가젯 config 규칙을 그대로 흉내낸다.
