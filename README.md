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
| `guard/SettingsGuard` | Backs you out of Settings pages naming this app; opt-in |
| `debug/NodeTreeDumper` | Dumps the live view tree so real view ids can be found |
| `ui/RuleEditorScreen` | Every rule field as a form control; raw JSON kept as a fallback |
| `ui/Controls` | The form widgets - durations, counts, screen fractions, tag lists |

### Design notes worth knowing before you change anything

- **`flagReportViewIds` is mandatory.** Without it every `viewIdResourceName` is null and
  all view-id matching silently fails while looking like a detection bug.
- **The overlay must not take focus.** `FLAG_NOT_FOCUSABLE` is set for a reason: a
  focusable overlay grabs input focus, and the Back the actuator injects a moment later
  lands on the block screen instead of the feed. Touches are unaffected by focus, so the
  dismiss button still works.
- **The block screen outlives the scan that raised it.** Backing out of the feed means
  the very next scan matches nothing, so tearing the overlay down there would flash it
  away in about 200ms. It goes when dismissed, or on its own timer.
- **`visibleTarget` and `currentRuleId` are different questions.** The second is reset by
  a block so the next entry logs as fresh; only the first can answer "is the feed still
  up?", which is what the Back loop keeps asking.
- **`minAreaFraction` is not decoration.** The word "Shorts" appears on YouTube's bottom-nav
  tab, which is on the home feed. Matching it without an area floor blocks all of YouTube.
  There is a test for exactly this.
- **The tree walk is capped.** It runs on every content-change event in a scrolling video
  feed. Uncapped recursion is visible jank and real battery cost.
- **The VPN routes one address.** Only the fake resolver `10.7.7.3` enters the tunnel;
  everything else takes its normal path. Routing `0.0.0.0/0` would cost far more for no
  extra blocking power.
- **Rules are data, not code.** They are edited on the phone, because YouTube's internal
  ids are undocumented and change between builds. The form is the front door; the raw
  JSON editor stays because selectors are sometimes pasted wholesale out of a dump.
- **User mode and developer mode split by what a field costs to get wrong.** Budgets,
  cooldowns and the protection switches are choices about how you want to be treated.
  View ids and area fractions are a debugging surface, and a wrong value there produces
  a rule that silently matches nothing. User mode hides the second group — it does not
  disable it. A rule edited in user mode keeps every matcher it had, and the card that
  would have held them summarises what is there, so it never reads as an empty rule.
- **The master switch goes through the cooldown.** A one-tap "off" that applied instantly
  would make every other cooldown in the app decorative. Turning protection back on is
  immediate; turning it off is queued like any other weakening, so the switch does not
  follow the finger - which is why the Guard card shows the queued change next to it.
- **`SettingsSnapshot.blockingActiveAt` is the single question.** The master switch and
  maintenance mode both answer it, so a new blocking path can only get this wrong by not
  asking at all.

## Setup

### Toolchain

- **Windows:** install Android Studio (brings JDK, SDK, `adb`, USB deploy). Enable Developer
  options and USB debugging on the S24.
- **WSL** (for command-line builds): JDK 17 and Android SDK with `platforms;android-36` and
  `build-tools;36.0.0`.

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/root/android-sdk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest
```

> `local.properties` holds `sdk.dir` and is machine-specific — it is gitignored. Android
> Studio on Windows will rewrite it with a Windows path; if you then build from WSL, point
> it back at the Linux SDK (or delete it and rely on `ANDROID_HOME`).

### Signing

`keystore.properties` and `doomguard-release.jks` sit in the project root and are both
gitignored. **Back them up off this machine.** The phone only accepts an update signed by
the same key, so losing them means uninstalling to upgrade — which takes the usage history
and every pending cooldown with it.

Missing credentials leave the release unsigned rather than quietly falling back to the
debug key, because a silent fallback produces exactly that uninstall.

Debug builds carry an `.debug` application id suffix, so a debug and a release install can
sit side by side instead of blocking each other over a signature mismatch.

### Installing on the phone

Copy `app-release.apk` across and open it. Android will ask to allow installs from
whatever app you copied it with; that prompt is normal for a sideload. If an older build
is already installed under `am.onex.stopdoom` and the install fails, uninstall it first —
it was signed with a different key.

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
4. **Rules** tab → *Edit* → add the real ids under **View ids** → Save. For a whole list
   pasted out of a dump, the *JSON* button on the same screen is faster.

No rebuild needed. Saved dumps are the same shape as the test fixtures in
`RuleEngineTest`, so a dump can be pasted straight into a regression test.

**Facebook is expected to be hard.** It is Litho-rendered and ships almost no resource ids.
Its section rule starts disabled; if a dump confirms nothing usable, enable `fb-whole-app`
and take a whole-app budget instead.

## Anti-bypass, honestly

- **The change cooldown does the real work.** Weakening any limit waits (default 2 hours);
  tightening applies at once. Enforced in `CooldownGate`, which is the only write path.
- **The master switch is not an exception to that.** Switching everything off is queued
  like any other weakening; switching it back on is instant. While a switch-off is
  pending, the Guard card says so and offers to cancel it.
- **The cooldown switch is a real hole, and the only one the app opens on purpose.**
  Developer mode reveals a switch that turns the cooldown off outright, which makes
  every weakening instant and releases whatever was already queued. It cannot be
  protected by the gate it disables — waiting two hours to disable the two hour wait
  is the exact problem it exists to solve — so the honesty is carried by visibility
  instead: a red strip on every tab while it is off, a warning on the Guard card, and
  an automatic switch-back-on when you leave developer mode. It is for testing a rule
  change in the same minute you make it. Anything longer than that and the app is not
  doing its job.
- **Device admin is friction, not a lock.** Since Android 6 you can always deactivate an
  admin. It forces a deliberate extra step, which is enough to outlast an impulse.
- **The accessibility toggle is not protected.** The system owns that switch. Turning it off
  disables everything instantly and no cooldown applies. The watchdog notices and nags.
- **The aggressive guard stands down on purpose.** With it on, Settings pages that name
  DoomGuard get backed out of — but after three attempts it steps aside for a minute. A
  guard with no way out gets escaped by a factory reset instead, which is strictly worse.
  It is off by default.
- **Private DNS defeats the DNS filter** completely — queries go over TLS and never enter the
  tunnel. The Guard tab surfaces this; browser URL checking still covers it.
- **Maintenance mode exists on purpose.** You are the developer of the thing blocking you.
  Without an escape hatch you would uninstall it to get work done. It goes through the
  cooldown like anything else.

## Tests

104 unit tests, no device needed:

```bash
./gradlew testDebugUnitTest
```

- `RuleEngineTest` — matching, including the nav-tab false-positive case
- `DnsPacketTest` — IPv4/UDP/DNS round-trip, checksum, NXDOMAIN, malformed input
- `LooseningTest` — every way a rule can be weakened (the cooldown depends on this)
- `UrlBarDetectorTest` — host extraction, allowlist precedence, keyword matching
- `UsageTrackerTest` — budgets against real SQLite under Robolectric
- `RuleJsonTest` — including a check that the bundled asset actually parses
- `BlockActuatorTest` — the Back retry sequence, both ways it can fail silently
- `SettingsGuardTest` — screen recognition and the stand-down bargain
- `SettingsSnapshotTest` — the master switch and maintenance, through one predicate
- `RuleIdTest` — id slugs, including the collision the form must not create
- `PendingChangeStoreTest` — the queue, including releasing it when the cooldown goes off

## Device checklist

Do 1–6 with **Guard → Mode → Developer** and the **change cooldown switched off**, or every
step that weakens something costs you a two hour wait. Switch the cooldown back on before
step 7 — that step is the one that tests it.

1. Setup tab all green.
2. Open YouTube Shorts → overlay + back-out within ~1s. If not, dump and fix selectors.
3. Repeat for Instagram Reels, Facebook Reels, TikTok.
4. Set a budget to 30s → confirm allow, then block at expiry, then instant re-block.
5. Visit a blocklisted domain → NXDOMAIN. Enable Chrome Secure DNS, retry → URL check catches it.
6. Reboot → filter and service come back.
7. Cooldown on. Raise a limit → countdown appears, nothing changes. Try to uninstall →
   admin blocks it.
8. Switch to **User** mode → Debug tab and the selector fields go, the rules keep firing.
