# @bts/web — 프론트엔드 앱

BTS (Project Atlas) 프론트엔드. React 19 + TypeScript 5 + Vite 6 + TanStack Router.

## 로컬 셋업

1. 의존성 설치. `pnpm install` (프로젝트 루트에서).
2. 환경 변수 준비. `.env.example`을 참고해 `.env.development.local` 파일 생성. 로컬 개발에서는 값을 비워도 Vite proxy가 자동으로 처리함.
3. 백엔드 + 프론트엔드를 아래 명령으로 기동.

## 기동 명령

### 백엔드 (Spring Boot — 포트 8080)

```bash
./gradlew :backend:bootRun --args='--spring.profiles.active=dev'
```

`dev` 프로파일을 사용하면 테스트 계정(alice)이 자동으로 로드됨.

### 프론트엔드 (Vite dev server — 포트 5173)

```bash
pnpm --filter @bts/web dev
```

Vite dev server가 `/api/*` 요청을 백엔드(8080)로 자동 프록시하므로 CORS 설정 없이 동작함.

## 테스트 로그인 정보

| 항목 | 값 |
|------|-----|
| 사용자명 | `alice` |
| 비밀번호 | `password` |
| 인증 방식 | Local Provider |

## 테스트 명령

```bash
# 단위 + 컴포넌트 테스트
pnpm --filter @bts/web test

# E2E 테스트 (백엔드 기동 필요)
pnpm --filter @bts/web test:e2e
```
