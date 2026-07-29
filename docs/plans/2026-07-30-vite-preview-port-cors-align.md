# vite preview 포트 ⟺ 백엔드 CORS 허용 오리진 정합

> slug: vite-preview-port-cors-align
> type: ui (fast-track — 빌드 설정 1줄 + 회귀 판별식)
> agent: frontend-engineer
> 생성: 2026-07-30

## Brief

**원문 (Maxi).** #319 손검증 경로 CORS 포트 불일치 수정 — `apps/web/vite.config.ts` 의
`preview.port` 를 4173 에서 5173 으로 고정한다. 백엔드 CORS 허용목록
(`application.yml` 기본값 `http://localhost:5173`)은 건드리지 않는다.
회귀 방지 장치로 preview 포트와 CORS 허용 오리진의 정합을 강제하는 테스트를 넣는다.

**Maxi 확정 (D1 = A).** 프론트 한 줄만 바꾼다. 백엔드 CORS 기본 허용목록을 넓히는 안(B)은
그 기본값이 운영 배포에 딸려가 `BTS_CORS_ALLOWED_ORIGINS` 미설정 시 `localhost:4173` 이
허용된 채로 뜨므로 기각. 절차만 문서화하는 안(C)은 이미 한 번 밟은 함정이라 기각.

**분류 결과.** `type=ui` / `agent=frontend-engineer` — 경로 실측(`apps/web/**`)과 일치.
`slug` 는 classify 자동 생성값(`319-cors-apps-web-vite-config-ts-preview-port-4173`) 대신
가독 가능한 이름으로 교체.

**fast-track 사유.** 설계 갈림길(D1)이 착수 전 Maxi 확정으로 닫혔고 변경 대상이 빌드 설정
1줄이라 `/bts-domain`(신규 도메인 용어 0건) · `/bts-spec`(사용자 시나리오 없음) ·
`/bts-review-plan` 을 생략한다. #319 자체도 `[chore]` 였다. plan · impl · codereview ·
게이트 2종은 그대로 밟는다.

## 결함 (실측)

| 항목 | 실측값 | 위치 |
|---|---|---|
| vite dev 서버 포트 | 5173 | `apps/web/vite.config.ts:33` |
| vite preview 포트 | **4173** | `apps/web/vite.config.ts:53` |
| 백엔드 CORS 허용 기본값 | `http://localhost:5173` | `backend/modules/app/src/main/resources/application.yml:85` |

**증상.** `pnpm build && pnpm preview` 로 실 백엔드 손검증을 하면 GET 은 통과하고 POST 만
`403 Invalid CORS request` 가 된다. 브라우저가 POST 에만 `Origin` 헤더를 보내고 vite 프록시가
그대로 전달하기 때문. 손검증의 첫 단계인 로그인부터 막혀 #319 가 연 경로 자체가 못 쓰인다.

**왜 이 저장소의 지배 결함 양식인가.** 「하드코딩 목록 두 개가 서로를 안 본다」의
9번째 사례다 (2026-07-27 감사에서 8건 확인, #1·#2가 같은 `CorsConfig`). vite 가 서빙하는
로컬 포트 집합과 백엔드가 허용하는 오리진 집합을 각각 검증하는 눈은 있으나 **갈라짐 자체를
보는 눈이 없다.**

## 도메인 정리 (← /bts-domain 채움)

_fast-track 생략. 신규 도메인 용어 0건, ADR 0건._

## 스펙 (← /bts-spec Phase A 채움)

_fast-track 생략. 사용자 시나리오 없는 빌드 설정 변경._

## Brainstorming Check (← /bts-spec Phase B 채움)

_fast-track 생략._

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

_fast-track 생략. `/bts-codereview` 는 정상 수행._
