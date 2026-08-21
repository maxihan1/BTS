// vite 가 서빙하는 로컬 포트와 백엔드 CORS 허용 오리진의 정합을 강제하는 판별식
//
// 왜 이 테스트가 있나. `vite.config.ts` 의 포트는 **하드코딩 값**이고, 백엔드가 허용하는 오리진은
// `application.yml` 의 **다른 하드코딩 값**이다. 둘의 정합을 아무도 보지 않으면 조용히 갈라진다 —
// 이 저장소가 겪은 실패 양식 그대로다 (CORS allowedMethods ↔ 컨트롤러 매핑, BC_KEYWORDS ↔ 도메인
// 어휘, backend-ci 매트릭스 ↔ Gradle 모듈).
//
// 실제로 갈라졌다. #319 가 `vite preview` 를 「실 백엔드로 UI 를 손검증할 유일한 경로」로 열었으나
// preview 는 4173 에서 뜨고 CORS 허용 기본값은 5173 이었다. 브라우저는 **POST 에만** `Origin` 을
// 보내므로(GET 에는 안 보낸다) GET 은 전부 200 인데 로그인 POST 만 `403 Invalid CORS request` 가
// 됐다 — 손검증의 첫 단계부터 막혀 경로 자체가 못 쓰였고, GET 이 멀쩡해서 원인을 CORS 로
// 의심하기까지 시간이 걸렸다.
//
// 판별식. vite 가 서빙하는 **모든** 로컬 포트가 CORS 허용목록에 있는지 **차집합 0** 으로 본다.
// 포트를 한 번 맞추는 것으로는 부족하다 — 다시 갈라지는 것을 막는 눈이 있어야 한다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
/**
 * 이 판별식이 의존하는 입력 파일과, workflow-scripts-ci 트리거에서 그 파일을 덮는 경로 패턴.
 *
 * ## ★ 왜 파일 경로와 CI 트리거 요구를 **한 선언**에서 파생시키나
 * 따로 두면 갈라진다. 이 PR 의 코드리뷰에서 실제로 갈라졌다 — `playwright.config.ts` 를
 * 입력으로 추가하면서 트리거 요구 목록에는 넣지 않아, `reuseExistingServer` 를 되돌리는 PR 이
 * CI 를 띄우지 않고 그 단언을 **0회 실행**하는 상태가 됐다. 봉인 안에서 봉인이 막으려던
 * 결함 양식을 재생산한 것이다.
 *
 * 그래서 입력을 여기 한 곳에만 적고, 읽기 경로와 트리거 요구를 **둘 다 여기서 파생**시킨다.
 * 새 입력을 추가하면 CI 배선 단언이 자동으로 그 트리거를 요구한다.
 */
const INPUTS = {
  vite: { file: 'apps/web/vite.config.ts', coveredBy: 'apps/web/vite.config.ts' },
  playwright: { file: 'apps/web/playwright.config.ts', coveredBy: 'apps/web/playwright.config.ts' },
  // playwright 의 webServer.command 가 `node_modules/.bin/vite` 인 근거가 "apps/web 의 dev
  // 스크립트가 곧 vite" 라는 사실이다. 그 사실이 바뀌면 둘은 조용히 갈라진다 (FR-UX-12 F4).
  webPackageJson: { file: 'apps/web/package.json', coveredBy: 'apps/web/package.json' },
  appYml: {
    file: 'backend/modules/app/src/main/resources/application.yml',
    // 2026-08-13. 트리거가 `backend/**` 에서 이 파일 1개로 좁혀졌다 — 이 판별식이 backend 아래에서
    // 읽는 것은 이 파일뿐인데, 러너 1대에 backend PR 마다 판별식 잡이 함께 서서 대기를 늘렸다.
    // 짝 선언을 여기서 같이 좁히지 않으면 「엉뚱한 경로를 요구하며 통과」가 된다.
    coveredBy: 'backend/modules/app/src/main/resources/application.yml',
  },
  // 판별식 자신. 런타임에 읽지는 않지만, 이 파일을 고치는 PR 에서도 CI 가 돌아야 한다.
  self: {
    file: 'scripts/workflow/preview-cors-origin-alignment.test.ts',
    coveredBy: 'scripts/workflow/**',
  },
} as const;

/** 입력 파일 원문. 읽는 경로와 CI 트리거 요구가 같은 선언에서 나온다. */
function read(input: { readonly file: string }): string {
  return fs.readFileSync(path.join(REPO_ROOT, input.file), 'utf8');
}


/**
 * vite 가 로컬에서 브라우저에 서빙하는 블록.
 *
 * 둘 다 `/api` 를 백엔드로 프록시하고, 브라우저는 그 요청에 `Origin` 을 붙인다 —
 * 즉 **양쪽 모두** CORS 허용목록에 있어야 한다. dev 만 보면 preview 가 빠진다.
 */
const VITE_SERVE_BLOCKS = ['server', 'preview'] as const;



/** 파서가 고장났을 때 차집합이 공허하게 통과하는 것을 막는 하한. */
const MIN_CORS_ORIGINS = 1;

interface ViteServeBlock {
  /** 이 블록이 바인딩하는 로컬 포트 */
  port: number;
  /** 포트 점유 시 조용히 다른 포트로 옮기지 않고 즉시 실패하는가 */
  strictPort: boolean;
}

/**
 * `vite.config.ts` 의 `server` · `preview` 블록에서 포트와 `strictPort` 를 수집한다.
 *
 * 값을 이 파일에 복사해 두면 그것이 **또 하나의 어긋날 수 있는 목록**이 되므로 원본을 읽는다.
 * @returns 블록 이름 → 파싱된 설정. 파싱 실패한 블록은 누락되고 양성 대조군 테스트가 잡는다.
 */
function viteServeBlocks(): Map<string, ViteServeBlock> {
  const src = read(INPUTS.vite);
  const blocks = new Map<string, ViteServeBlock>();

  for (const name of VITE_SERVE_BLOCKS) {
    // 블록 본문에 중첩 중괄호가 없다는 전제. 생기면 여기서 못 찾고 양성 대조군이 red 가 된다.
    const body = new RegExp(`\\n {2}${name}: \\{([^}]*)\\}`).exec(src)?.[1];
    const port = body === undefined ? undefined : /port:\s*(\d+)/.exec(body)?.[1];
    if (body === undefined || port === undefined) continue;

    blocks.set(name, { port: Number(port), strictPort: /strictPort:\s*true/.test(body) });
  }

  return blocks;
}

/** `vite.config.ts` 전체에 등장하는 `port: <숫자>` 리터럴 수. 알려진 블록 밖의 포트를 탐지한다. */
function vitePortLiteralCount(): number {
  const src = read(INPUTS.vite);
  return [...src.matchAll(/port:\s*\d+/g)].length;
}

/**
 * `application.yml` 의 `BTS_CORS_ALLOWED_ORIGINS` **기본값**을 수집한다.
 *
 * 기본값을 보는 이유. 로컬 손검증과 CI 는 환경변수를 주지 않으므로 이 기본값이 곧 실제 허용목록이다.
 */
function corsAllowedOriginDefaults(): Set<string> {
  const src = read(INPUTS.appYml);
  const defaults = /allowed-origins:\s*\$\{BTS_CORS_ALLOWED_ORIGINS:([^}]*)\}/.exec(src)?.[1] ?? '';

  return new Set(
    defaults
      .split(',')
      .map((origin) => origin.trim())
      .filter((origin) => origin.length > 0),
  );
}


describe('vite 로컬 포트 ↔ 백엔드 CORS 허용 오리진 정합', () => {
  test('판별식이 비어 있지 않다 (양성 대조군)', () => {
    const blocks = viteServeBlocks();
    const origins = corsAllowedOriginDefaults();

    // 하한이 없으면 파서가 0건을 내도 아래 차집합이 전부 공허하게 통과한다.
    // 0 이라는 결과는 「없다」가 아니라 「내 판별식이 틀렸다」를 먼저 의심해야 한다.
    assert.deepEqual(
      [...VITE_SERVE_BLOCKS].filter((name) => !blocks.has(name)),
      [],
      `vite.config.ts 에서 파싱하지 못한 서빙 블록이 있다 — 파서가 고장났거나 블록이 개명됐다.`,
    );
    assert.ok(
      origins.size >= MIN_CORS_ORIGINS,
      `application.yml 에서 CORS 허용 오리진을 ${origins.size}건 찾았다 — 파서가 고장났다.`,
    );
  });

  test('vite 가 서빙하는 모든 로컬 포트가 CORS 허용목록에 있다', () => {
    const origins = corsAllowedOriginDefaults();

    const uncovered = [...viteServeBlocks()]
      .map(([name, block]) => ({ name, origin: `http://localhost:${block.port}` }))
      .filter(({ origin }) => !origins.has(origin));

    assert.deepEqual(
      uncovered.map(({ name, origin }) => `${name} → ${origin}`),
      [],
      `CORS 허용목록에 없는 vite 서빙 오리진이 있다.\n` +
        `허용됨=${[...origins].join(', ')}\n` +
        `브라우저는 POST 에만 Origin 을 보내므로 GET 은 통과하고 **POST 만** ` +
        `403 Invalid CORS request 가 된다 — 로그인부터 막혀 원인 추적이 어렵다.\n` +
        `vite 포트를 허용목록에 맞추거나, 허용목록을 넓혀야 한다(후자는 운영 기본값이 넓어진다).`,
    );
  });

  test('알려진 서빙 블록 밖에 포트 리터럴이 없다', () => {
    const known = viteServeBlocks().size;
    const total = vitePortLiteralCount();

    // 상한이 없으면 새 포트가 조용히 늘어도 위 차집합이 그것을 보지 못한다.
    // 「침묵하는 상한」을 두지 않는다 — 갭이 생기면 알려줘야 한다.
    assert.equal(
      total,
      known,
      `vite.config.ts 의 port 리터럴 ${total}건 중 ${known}건만 판별식이 보고 있다.\n` +
        `새 서빙 블록을 추가했다면 VITE_SERVE_BLOCKS 에 등록해야 이 판별식이 그 포트도 검사한다.`,
    );
  });

  test('서빙 블록이 strictPort 로 고정돼 있다', () => {
    const drifting = [...viteServeBlocks()]
      .filter(([, block]) => !block.strictPort)
      .map(([name]) => name);

    assert.deepEqual(
      drifting,
      [],
      `strictPort 가 없는 서빙 블록이 있다: ${drifting.join(', ')}\n` +
        `server 와 preview 가 같은 포트를 쓰므로, 점유 시 vite 는 **조용히 다음 포트로 옮겨** 붙는다. ` +
        `그러면 그 포트는 CORS 허용목록 밖이라 같은 403 이 다른 원인으로 재발한다.`,
    );
  });

  // ★2026-08-21 — 「입력이 CI 트리거 paths 에 있는가」 단언을 지웠다. CI 자동 실행을 껐고,
  //   판별식은 `.husky/pre-push` 가 조건 없이 전량 돌린다(discriminant-hook-wiring.test.ts 강제).


  test('E2E 가 이미 떠 있는 서버를 재사용하지 않는다', () => {
    const src = read(INPUTS.playwright);

    // preview(프로덕션 빌드)와 dev 가 같은 포트를 쓰게 됐으므로, 손검증용 preview 를 켜 둔 채
    // E2E 를 돌리면 Playwright 가 그것을 재사용한다. 프로덕션 빌드는 MSW 가 꺼져 있어
    // 전 테스트가 API 호출부터 실패한다 — 거짓 초록은 아니지만 원인이 전혀 안 보이는 실패다.
    assert.match(
      src,
      /reuseExistingServer:\s*false/,
      `playwright.config.ts 의 reuseExistingServer 가 false 로 고정돼 있지 않다.\n` +
        `preview 와 dev 가 같은 포트(5173)를 쓰므로 재사용을 허용하면 E2E 가 프로덕션 빌드를 ` +
        `잡고 돌 수 있다. false 면 Playwright 가 항상 자기 dev 서버를 띄우고, 포트가 점유돼 ` +
        `있으면 strictPort 때문에 즉시 명시적으로 실패한다.`,
    );

    // ★존재 단언만으로는 절반만 닫힌다. `false` 를 남겨 둔 채 조건부 형태를 **추가**하면
    // 위 match 는 통과하는데 실제 동작은 되돌아간다. 옛 형태의 **부재**까지 봐야 봉인이다.
    const conditional = [...src.matchAll(/reuseExistingServer:\s*([^,\n]+)/g)]
      .map((m) => (m[1] ?? '').trim())
      .filter((value) => value !== 'false');

    assert.deepEqual(
      conditional,
      [],
      `reuseExistingServer 에 false 가 아닌 값이 남아 있다: ${conditional.join(', ')}\n` +
        `조건부(!process.env['CI'] 등)로 되돌리면 로컬에서 preview 를 재사용한다.`,
    );
  });

  test('E2E webServer 가 pnpm 래퍼를 거치지 않는다 (worktree 에서 E2E 가 죽는 것을 막는다)', () => {
    const playwrightSrc = read(INPUTS.playwright);

    // ★worktree(`.worktrees/<slug>`)의 node_modules 는 main 트리에서 심볼릭 링크된다. pnpm
    // 래퍼는 실행 전 의존성 검사에서 그 경로 불일치를 감지해 `pnpm install` 을 자동 트리거하고
    // 무-TTY 에서 ERR_PNPM_ABORTED_REMOVE_MODULES_DIR_NO_TTY 로 죽는다. 위 단언이 고정한
    // `reuseExistingServer: false` 때문에 Playwright 는 **항상 자기 서버를 띄우므로**, 이 명령이
    // 죽으면 worktree 에서 E2E 를 돌릴 방법이 사라진다. CLAUDE.md §핵심 패턴이 "worktree per
    // 작업"을 강제하니 그건 곧 **모든 작업의 E2E 가 막힌다**는 뜻이다.
    assert.doesNotMatch(
      playwrightSrc,
      /command:\s*['"`][^'"`]*\bpnpm\b/,
      `playwright.config.ts 의 webServer.command 가 pnpm 래퍼를 거친다.\n` +
        `worktree 의 심볼릭 node_modules 에서 pnpm 은 install 을 자동 트리거해 죽는다.\n` +
        `바이너리를 직접 호출할 것 — 예: command: 'node_modules/.bin/vite'`,
    );

    // ★존재 단언의 짝. "pnpm 이 아니다" 만으로는 절반이다 — 실제로 vite 를 띄우는지까지 봐야
    // 봉인이고, 그래야 명령을 엉뚱한 것으로 바꾸는 변경도 걸린다.
    assert.match(
      playwrightSrc,
      /command:\s*['"`]node_modules\/\.bin\/vite['"`]/,
      `playwright.config.ts 의 webServer.command 가 node_modules/.bin/vite 가 아니다.`,
    );

    // ★두 목록 차집합. 위 command 가 정당한 근거는 "apps/web 의 dev 스크립트가 곧 vite" 라는
    // 사실 하나다. 누가 dev 에 플래그(--host 등)를 더하면 E2E 서버만 조용히 다른 설정으로 뜨고,
    // 그 차이는 실패가 아니라 **설명 안 되는 통과/실패**로 나타난다.
    // 계열 — 메모리 `two-lists-never-check-each-other`.
    const devScript = (JSON.parse(read(INPUTS.webPackageJson)) as { scripts?: Record<string, string> })
      .scripts?.['dev'];

    assert.equal(
      devScript,
      'vite',
      `apps/web 의 dev 스크립트가 'vite' 가 아니다(현재: ${String(devScript)}).\n` +
        `playwright.config.ts 의 webServer.command='node_modules/.bin/vite' 는 이 둘이 같다는 ` +
        `전제 위에 서 있다. dev 에 옵션을 더했다면 command 에도 같은 옵션을 반영할 것.`,
    );
  });
});
