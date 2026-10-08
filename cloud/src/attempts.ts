/**
 * The recovery-code throttle's per-address windows, kept apart from the Durable Object that stores
 * them so the bookkeeping can be checked without one (#383).
 */

/**
 * Addresses the code throttle keeps a window for. At most eleven timestamps and an IPv6 address
 * each, about 200 bytes even written out as JSON, three hundred stay well inside the 128 KiB one
 * storage value may hold.
 */
export const MAX_TRACKED_ADDRESSES = 300;

/**
 * Adds an attempt from [key] to the windows in [stored] and returns the windows to store.
 *
 * The attempt counts even when it is refused: somebody hammering the endpoint keeps their own window
 * full and gains nothing by carrying on. More than one past the limit says nothing more, so no more
 * than that is kept.
 *
 * Addresses nobody has used inside the window are dropped, and beyond [MAX_TRACKED_ADDRESSES] the
 * least recently seen go too. The windows are one storage value, and a burst from a few thousand
 * addresses used to outgrow it: the write threw, and the throttle failed open for everyone for as
 * long as the burst lasted. Evicting an address forgets its window, which costs a guesser at most a
 * fresh ten attempts; the address-free failure breaker still counts every one of them.
 */
export function recordAttempt(
  stored: Record<string, number[]>,
  key: string,
  now: number,
  windowMs: number,
  limit: number,
): { next: Record<string, number[]>; recent: number } {
  const cutoff = now - windowMs;
  const recent = [...(stored[key] ?? []).filter((t) => t > cutoff), now].slice(-(limit + 1));

  const others: Array<[string, number[]]> = [];
  for (const [address, times] of Object.entries(stored)) {
    if (address === key) continue;
    const kept = times.filter((t) => t > cutoff);
    if (kept.length > 0) others.push([address, kept]);
  }
  const lastSeen = (times: number[]) => times[times.length - 1] ?? 0;
  others.sort((a, b) => lastSeen(b[1]) - lastSeen(a[1]));

  const next: Record<string, number[]> = { [key]: recent };
  for (const [address, times] of others.slice(0, MAX_TRACKED_ADDRESSES - 1)) next[address] = times;
  return { next, recent: recent.length };
}
