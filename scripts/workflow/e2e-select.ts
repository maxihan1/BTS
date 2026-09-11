// 변경 도메인에서 돌릴 E2E 스펙을 고른다 — 파이프라인이 셸 글로브로 하던 일의 정본
//
// ## 왜 TS 로 옮겼나 — 2026-09-11 적발 2건
//
// ### ① 하이픈 없는 이름을 통째로 놓쳤다
//
// `Jenkinsfile.e2e` 가 셸에서 `apps/web/e2e/${d}-*.spec.ts` 하나로 골랐다. 그런데 이
// 저장소의 스펙 이름은 두 가지다.
//
//     issue-create.spec.ts    ← `<도메인>-<시나리오>`
//     backlog.spec.ts         ← `<도메인>` 단독
//
// 후자가 글로브에 안 걸린다. 실측(2026-09-11) — 컴포넌트 폴더 38개 기준
//
//     `<d>-*.spec.ts` 만        → 매칭 0건 폴더 **25개**
//     `<d>-*` 또는 `<d>.`       → 매칭 0건 폴더 **13개**
//
// `backlog` `inbox` `search` `status` `preferences` 등 12개 도메인의 E2E 가 이름 때문에
// 1단에서 통째로 빠져 있었다. 「변경 도메인만 돈다」가 그 도메인들에서는 「0건」이었다.
//
// ### ② 셸 글로브는 판별식이 못 본다
//
// 파이프라인 안의 `for f in ...` 는 저장소 어느 판별식도 읽지 않는다. 규칙을 여기로
// 옮겨야 대조가 가능해진다.
//
// ## ★남은 13개는 매핑이 없는 것이 사실이다
//
// `issues`(스펙은 `issue-`) · `labels`(스펙은 `label-`) 처럼 단복수가 갈린 것과,
// `auth` `editor` `filters` `burndown` `cfd` `cycle-time` `velocity` 처럼 E2E 가 없는
// 것이 섞여 있다. **그 사실을 숨기지 않는다** — [unmappedDomains] 가 그대로 돌려주고,
// 호출자는 「매핑 없음」을 전량으로 넓히는 근거로 쓴다.
import { readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const E2E_DIR = join(REPO_ROOT, 'apps/web/e2e');

/** `apps/web` 기준 상대경로. `Jenkinsfile.e2e` 가 그 경로로 playwright 를 부른다. */
const REL_PREFIX = 'e2e/';

/** 저장소에 실재하는 스펙 파일명(`*.spec.ts`). 디렉터리 실물이 정본이다. */
export function allSpecNames(dir: string = E2E_DIR): string[] {
  return readdirSync(dir)
    .filter((f) => f.endsWith('.spec.ts'))
    .sort();
}

/**
 * 도메인 하나가 무는 스펙.
 *
 * 두 서식을 **모두** 본다 — `<도메인>-*.spec.ts` 와 `<도메인>.spec.ts`.
 * 하나만 보면 그 도메인의 E2E 가 조용히 0건이 된다.
 */
export function specsForDomain(domain: string, specs: string[]): string[] {
  return specs.filter((s) => s.startsWith(`${domain}-`) || s === `${domain}.spec.ts`);
}

/** 도메인 여러 개가 무는 스펙 — `apps/web` 상대경로로. */
export function specsForDomains(domains: string[], specs: string[]): string[] {
  const out = new Set<string>();
  for (const d of domains) {
    for (const s of specsForDomain(d, specs)) out.add(`${REL_PREFIX}${s}`);
  }
  return [...out].sort();
}

/** 스펙이 하나도 안 걸린 도메인 — 「매핑 없음」을 숨기지 않는다. */
export function unmappedDomains(domains: string[], specs: string[]): string[] {
  return domains.filter((d) => specsForDomain(d, specs).length === 0);
}

export function main(argv: string[] = process.argv.slice(2)): number {
  const domains = argv.filter((a) => a.trim() !== '');
  if (domains.length === 0) return 0;
  const specs = allSpecNames();
  const picked = specsForDomains(domains, specs);
  const missing = unmappedDomains(domains, specs);
  if (missing.length > 0) {
    // stderr 로 낸다 — stdout 은 셸이 그대로 인자로 쓴다.
    process.stderr.write(`매핑 없는 도메인: ${missing.join(' ')}\n`);
  }
  if (picked.length > 0) process.stdout.write(`${picked.join('\n')}\n`);
  return 0;
}

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop() ?? ' ');
if (isMain) process.exit(main());
