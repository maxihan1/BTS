// 변경 경로 목록 → 표면 판정 → 최고 티어 + 미분류 경로를 내는 판정기 (CLI 겸용)
// 글로브는 여기 적지 않는다 — 정본은 surfaces.ts 뿐이고 이 파일은 그것을 읽기만 한다.
import { readFileSync } from 'node:fs';
import { SURFACES, SURFACE_PRECEDENCE, type SurfaceName } from './surfaces.ts';
import type { Tier } from './types.ts';

/** 낮은 순 → 높은 순. 「혼합이면 최고 티어」(판정 규칙 ①) 를 이 순서로 판단한다 */
export const TIER_ORDER: readonly Tier[] = ['T0', 'T1', 'T2', 'T3'];

/**
 * 신호가 없을 때의 티어(판정 규칙 ②).
 *
 * T0 가 아니라 T1 인 이유. 판정 실패를 「가장 가벼운 절차」로 떨어뜨리면 실패가 이득처럼 보인다.
 */
export const DEFAULT_TIER: Tier = 'T1';

/**
 * 티어 판정 입력에서 **먼저 빼는** 생성물.
 *
 * 사람이 쓰지 않고 생성기가 쓰는 파일이다. 이것들이 변경 목록에 섞여 있으면 「무엇을 고쳤나」를
 * 왜곡한다 — 폐기된 「변경 파일 5개」 규칙이 83% 를 T2 로 밀어 올린 원인이 바로 이 혼입이었다.
 * 제외한 경로는 미분류(UNMAPPED)로도 세지 않는다. 「빠졌다」가 아니라 「대상이 아니다」이다.
 */
export const GENERATED_GLOBS: readonly string[] = [
  'docs/INDEX*.md',
  'docs/progress.html',
  'MEMORY.md',
  'memory/index/**',
];

const REGEX_SPECIALS = new Set(['.', '+', '^', '$', '(', ')', '|', '[', ']', '\\']);

/**
 * 글로브를 정규식으로 옮긴다. 지원 문법은 `*` · `**` · `?` · `{a,b}` 뿐이다.
 *
 * `*` 는 경로 구분자를 넘지 않고 `**` 는 넘는다. `**\/` 는 **0개 이상**의 디렉터리로 읽는다 —
 * `a/**\/b` 가 `a/b` 를 놓치면 「중간 디렉터리가 없을 때만」 조용히 빠지는 구멍이 된다.
 */
export const globToRegExp = (glob: string): RegExp => {
  let out = '';
  let braceDepth = 0;
  for (let i = 0; i < glob.length; i++) {
    const ch = glob[i] as string;
    if (ch === '*') {
      if (glob[i + 1] === '*') {
        if (glob[i + 2] === '/') {
          out += '(?:.*/)?';
          i += 2;
        } else {
          out += '.*';
          i += 1;
        }
      } else {
        out += '[^/]*';
      }
      continue;
    }
    if (ch === '?') {
      out += '[^/]';
      continue;
    }
    if (ch === '{') {
      braceDepth++;
      out += '(?:';
      continue;
    }
    if (ch === '}' && braceDepth > 0) {
      braceDepth--;
      out += ')';
      continue;
    }
    if (ch === ',' && braceDepth > 0) {
      out += '|';
      continue;
    }
    out += REGEX_SPECIALS.has(ch) ? `\\${ch}` : ch;
  }
  return new RegExp(`^${out}$`);
};

/** 경로 1개가 글로브 1개에 걸리는가 */
export const matchesGlob = (glob: string, filePath: string): boolean =>
  globToRegExp(glob).test(filePath);

/** 생성물인가 — 티어 판정 입력에서 제외 대상 */
export const isGenerated = (filePath: string): boolean =>
  GENERATED_GLOBS.some((g) => matchesGlob(g, filePath));

/** 경로 1개에 걸리는 표면 **전부**를 우선순위 순으로 */
export const surfacesOf = (filePath: string): SurfaceName[] =>
  SURFACE_PRECEDENCE.filter((name) =>
    SURFACES[name].globs.some((g) => matchesGlob(g, filePath)),
  );

/** 경로 1개가 세어지는 표면 — 우선순위 1위. 없으면 null(미분류) */
export const surfaceOf = (filePath: string): SurfaceName | null => surfacesOf(filePath)[0] ?? null;

/** 두 티어 중 높은 쪽 */
export const maxTier = (a: Tier, b: Tier): Tier =>
  TIER_ORDER.indexOf(a) >= TIER_ORDER.indexOf(b) ? a : b;

export interface PathVerdict {
  path: string;
  /** 세어진 표면 — 미분류면 null */
  surface: SurfaceName | null;
  /** 걸린 표면 전부. 보안 렌즈 판정에 쓴다 */
  matched: SurfaceName[];
}

export interface TierVerdict {
  /** 실측 티어 — 혼합이면 최고 티어 */
  tier: Tier;
  /** 실제로 세어진 표면 이름 (중복 제거) */
  surfaces: SurfaceName[];
  /** 어느 표면 글로브에도 안 걸린 경로. 조용히 통과시키지 않는다(판정 규칙 ③) */
  unmapped: string[];
  /** 판정 입력에서 뺀 생성물 */
  generated: string[];
  /** 보안 렌즈가 붙는가 — T2+ 에서 생략 불가 */
  securityLens: boolean;
  paths: PathVerdict[];
}

/**
 * 변경 경로 목록에서 티어를 판정한다.
 *
 * 판정 규칙 5조.
 * ① 혼합이면 **최고 티어**를 쓴다 — 티어는 절차 강도라 가장 위험한 표면이 지배한다.
 * ② 신호가 없으면 T1. 사용자 지정 티어가 항상 우선한다(이 함수 밖의 일이다).
 * ③ 미분류 경로는 T1 로 세되 `unmapped` 에 담아 **게이트 2 요약에 그대로 싣는다.**
 * ④ `TEST` 표면은 티어를 올리지 않는다 — 우선순위에서 TEST 가 맨 앞이라 자동으로 성립한다.
 * ⑤ 여기서는 **승격하지 않는다.** 선언 티어와의 비교는 호출자(게이트 2)가 사람에게 보여줄 뿐이다.
 */
export const detectTier = (paths: readonly string[]): TierVerdict => {
  const generated: string[] = [];
  const unmapped: string[] = [];
  const verdicts: PathVerdict[] = [];
  const seen = new Set<SurfaceName>();
  let tier: Tier | null = null;
  let securityLens = false;

  for (const raw of paths) {
    const path = raw.trim();
    if (path.length === 0) continue;
    if (isGenerated(path)) {
      generated.push(path);
      continue;
    }

    const matched = surfacesOf(path);
    const surface = matched[0] ?? null;
    verdicts.push({ path, surface, matched });

    if (surface === null) {
      unmapped.push(path);
      tier = tier === null ? DEFAULT_TIER : maxTier(tier, DEFAULT_TIER);
      continue;
    }

    seen.add(surface);
    tier = tier === null ? SURFACES[surface].tier : maxTier(tier, SURFACES[surface].tier);
    // 렌즈는 **걸린 표면 전부**에서 본다. shared-kernel 의 permission/ 은 티어가 T3(SHARED_KERNEL)로
    // 세어지지만 보안 렌즈는 그대로 붙어야 한다. 단 TEST 로 세어진 경로는 렌즈를 붙이지 않는다(규칙 ④).
    if (surface !== 'TEST' && matched.some((name) => SURFACES[name].lens === 'security')) {
      securityLens = true;
    }
  }

  return {
    tier: tier ?? DEFAULT_TIER,
    surfaces: SURFACE_PRECEDENCE.filter((name) => seen.has(name)),
    unmapped,
    generated,
    securityLens,
    paths: verdicts,
  };
};

// ─────────────────────────────────────────────────────────
// CLI 진입점
// ─────────────────────────────────────────────────────────

const USAGE =
  'Usage: detect-tier.ts [--declared T0|T1|T2|T3] [--json] [<경로> ...]\n' +
  '       경로를 안 주면 stdin 을 한 줄에 하나씩 읽는다 (git diff --name-only 파이프).';

const isTier = (v: string): v is Tier => (TIER_ORDER as readonly string[]).includes(v);

const parseArgs = (
  argv: string[],
): { paths: string[]; declared: Tier | null; json: boolean } | { error: string } => {
  const paths: string[] = [];
  let declared: Tier | null = null;
  let json = false;
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i] as string;
    if (arg === '--declared') {
      const value = argv[i + 1];
      if (value === undefined || !isTier(value)) return { error: `--declared 값이 티어가 아니다: ${value ?? '(없음)'}` };
      declared = value;
      i++;
    } else if (arg === '--json') {
      json = true;
    } else if (arg.startsWith('--')) {
      return { error: `모르는 옵션: ${arg}` };
    } else {
      paths.push(arg);
    }
  }
  return { paths, declared, json };
};

const readStdin = (): string[] => {
  if (process.stdin.isTTY) return [];
  try {
    return readFileSync(0, 'utf-8').split('\n');
  } catch (cause) {
    // 부재를 0건으로 바꾸지 않는다. 빈 목록은 「변경이 없다」가 아니라 「못 읽었다」이고,
    // 그대로 두면 기본값 T1 로 조용히 떨어져 판정 실패가 가장 가벼운 절차처럼 보인다.
    // changed-paths.ts 가 같은 이유로 throw 하는 것과 같은 규율이다.
    throw new Error('stdin 을 읽지 못했다. 경로를 인자로 넘기거나 파이프를 확인하라.', { cause });
  }
};

const isMain = import.meta.url === `file://${process.argv[1]}`;
if (isMain) {
  const parsed = parseArgs(process.argv.slice(2));
  if ('error' in parsed) {
    console.error(`${parsed.error}\n${USAGE}`);
    process.exit(2);
  }

  const paths = parsed.paths.length > 0 ? parsed.paths : readStdin();
  // 「입력 0건」과 「변경 0건」은 다르다. 전자를 기본 티어로 흘려보내면
  // 판정을 안 한 것이 판정처럼 보인다 — 게이트 2 요약에 그대로 실려 사람을 속인다.
  if (paths.every((path) => path.trim() === '')) {
    console.error(`입력 경로가 0건이다. 판정하지 않는다.\n${USAGE}`);
    process.exit(3);
  }
  const verdict = detectTier(paths);

  if (parsed.json) {
    console.log(JSON.stringify({ ...verdict, declared: parsed.declared }, null, 2));
  } else {
    console.log(`TIER: ${verdict.tier}`);
    console.log(`SURFACES: ${verdict.surfaces.join(', ') || '(없음)'}`);
    if (verdict.securityLens) console.log('SECURITY_LENS: 필수 (생략 불가)');
    for (const path of verdict.unmapped) console.log(`UNMAPPED: ${path}`);
    if (parsed.declared !== null) {
      console.log(`DECLARED: ${parsed.declared}`);
      if (TIER_ORDER.indexOf(parsed.declared) < TIER_ORDER.indexOf(verdict.tier)) {
        // ★자동 승격하지 않는다. 구현이 끝난 뒤 계획 승인을 받는 절차 역전을 만들기 때문이다.
        // 종료 코드도 올리지 않는다 — 이 줄을 게이트 2 요약에 실어 **사람이** 결정한다.
        console.log(
          `PROMOTION_NEEDED: 선언 ${parsed.declared} < 실측 ${verdict.tier} — 게이트 2 에서 사람이 결정한다.`,
        );
      }
    }
  }
}
