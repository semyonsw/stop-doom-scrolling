# DoomGuard

A personal Android blocker for a Samsung S24. Two jobs:

1. **Kick you out of short-form feeds** — YouTube Shorts, Instagram Reels, Facebook Reels,
   TikTok — immediately or after a time budget you set.
2. **Block adult websites** across every app, using a local DNS filter plus a browser
   address-bar check.

Built to be sideloaded onto one phone. Not a Play Store app — it uses device admin and
`QUERY_ALL_PACKAGES`, both of which Play restricts.

## How it works

One `AccessibilityService` sees every foreground screen and drives everything else:

| Piece | Job |
|---|---|
| `service/DoomAccessibilityService` | Single scan loop; owns detection, budgets, blocking |
| `service/NodeScanner` | Bounded tree walk (depth 12, 400 nodes) → `ScreenSnapshot` |
| `rules/RuleEngine` | Pure matcher over snapshots; no Android types, fully unit-tested |
| `time/UsageTracker` | Per-rule session and daily budgets, flushed every 10s |
| `overlay/OverlayManager` | Block screen as a window overlay, not an Activity |
| `vpn/DnsFilterVpnService` | Local DNS filter; routes only its own fake resolver |
| `web/UrlBarDetector` | Reads the browser omnibox; catches DoH and typed searches |
| `guard/CooldownGate` | Single write path for anything that weakens protection |
| `debug/NodeTreeDumper` | Dumps the live view tree so real view ids can be found |

### Design notes worth knowing before you change anything

- **`flagReportViewIds` is mandatory.** Without it every `viewIdResourceName` is null and
  all view-id matching silently fails while looking like a detection bug.
- **`minAreaFraction` is not decoration.** The word "Shorts" appears on YouTube's bottom-nav
  tab, which is on the home feed. Matching it without an area floor blocks all of YouTube.
  There is a test for exactly this.
- **The tree walk is capped.** It runs on every content-change event in a scrolling video
  feed. Uncapped recursion is visible jank and real battery cost.
- **The VPN routes one address.** Only the fake resolver `10.7.7.3` enters the tunnel;
  everything else takes its normal path. Routing `0.0.0.0/0` would cost far more for no
  extra blocking power.
- **Rules are data, not code.** Selectors are JSON you edit on the phone, because YouTube's
  internal ids are undocumented and change between builds.

## Setup

### Toolchain

- **Windows:** install Android Studio (brings JDK, SDK, `adb`, USB deploy). Enable Developer
  options and USB debugging on the S24.
- **WSL** (for command-line builds): JDK 17 and Android SDK with `platforms;android-36` and
  `build-tools;36.0.0`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/root/android-sdk
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

> `local.properties` holds `sdk.dir` and is machine-specific — it is gitignored. Android
> Studio on Windows will rewrite it with a Windows path; if you then build from WSL, point
> it back at the Linux SDK (or delete it and rely on `ANDROID_HOME`).

### On the phone

Install, open, and work down the **Setup** tab. Every item deep-links to the right system
screen and re-checks when you come back. Three things have no shortcut and are listed at the
bottom of that tab — battery sleep exemptions and always-on VPN. **Battery sleep is the most
common reason a blocker quietly stops working on One UI.**

## Fixing the selectors (do this first)

The bundled view ids in `app/src/main/assets/default_rules.json` are **best-effort guesses**.
They cannot be verified from a development machine. Correct them like this:

1. **Debug** tab → *Dump in 5s*, then open YouTube Shorts.
2. Read the ids — in the app, or `adb logcat -s DoomGuard/Dump`.
3. Also dump a screen you do **not** want blocked (the YouTube home feed) and confirm the
   ids do not overlap.
4. **Rules** tab → *Edit* → paste the real ids into `anyViewIdContains` → Save.

No rebuild needed. Saved dumps are the same shape as the test fixtures in
`RuleEngineTest`, so a dump can be pasted straight into a regression test.

**Facebook is expected to be hard.** It is Litho-rendered and ships almost no resource ids.
Its section rule starts disabled; if a dump confirms nothing usable, enable `fb-whole-app`
and take a whole-app budget instead.

## Anti-bypass, honestly

- **The change cooldown does the real work.** Weakening any limit waits (default 2 hours);
  tightening applies at once. Enforced in `CooldownGate`, which is the only write path.
- **Device admin is friction, not a lock.** Since Android 6 you can always deactivate an
  admin. It forces a deliberate extra step, which is enough to outlast an impulse.
- **The accessibility toggle is not protected.** The system owns that switch. Turning it off
  disables everything instantly and no cooldown applies. The watchdog notices and nags.
- **Private DNS defeats the DNS filter** completely — queries go over TLS and never enter the
  tunnel. The Guard tab surfaces this; browser URL checking still covers it.
- **Maintenance mode exists on purpose.** You are the developer of the thing blocking you.
  Without an escape hatch you would uninstall it to get work done. It goes through the
  cooldown like anything else.

## Tests

69 unit tests, no device needed:

```bash
./gradlew testDebugUnitTest
```

- `RuleEngineTest` — matching, including the nav-tab false-positive case
- `DnsPacketTest` — IPv4/UDP/DNS round-trip, checksum, NXDOMAIN, malformed input
- `LooseningTest` — every way a rule can be weakened (the cooldown depends on this)
- `UrlBarDetectorTest` — host extraction, allowlist precedence, keyword matching
- `UsageTrackerTest` — budgets against real SQLite under Robolectric
- `RuleJsonTest` — including a check that the bundled asset actually parses

## Device checklist

1. Setup tab all green.
2. Open YouTube Shorts → overlay + back-out within ~1s. If not, dump and fix selectors.
3. Repeat for Instagram Reels, Facebook Reels, TikTok.
4. Set a budget to 30s → confirm allow, then block at expiry, then instant re-block.
5. Visit a blocklisted domain → NXDOMAIN. Enable Chrome Secure DNS, retry → URL check catches it.
6. Reboot → filter and service come back.
7. Raise a limit → countdown appears, nothing changes. Try to uninstall → admin blocks it.
