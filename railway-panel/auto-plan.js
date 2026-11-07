const countryCode = value => String(value || '').trim().toUpperCase();

export function buildAutoPlan(candidates, input, now = Date.now()) {
  const country = countryCode(input?.country);
  if (!/^[A-Z]{2}$/.test(country)) return { ok: false, error: 'invalid_country' };
  const maxAgeMs = Math.min(30 * 60_000, Math.max(30_000, Number(input?.maxAgeMs) || 5 * 60_000));
  const ipVersion = [4, 6].includes(Number(input?.ipVersion)) ? Number(input.ipVersion) : 0;
  const eligible = candidates.filter(candidate => {
    const evidence = candidate.evidence;
    return candidate.country === country && evidence?.ok === true && evidence.tls === true && evidence.httpStatus >= 200 && evidence.httpStatus < 500 && evidence.checkedAt <= now && evidence.checkedAt >= now - maxAgeMs && (!ipVersion || candidate.ipVersion === ipVersion);
  }).sort((a, b) => a.evidence.latencyMs - b.evidence.latencyMs || b.evidence.checkedAt - a.evidence.checkedAt);
  if (!eligible.length) return { ok: false, error: 'no_fresh_healthy_candidate', country };
  const [primary, ...rest] = eligible;
  return {
    ok: true,
    country,
    generatedAt: now,
    primary,
    fallbacks: rest.slice(0, 2),
    evidence: 'tls_and_http_same_connection',
  };
}
