# ForjaCraft VIP Manager

Multi-tier VIP subscription manager for the ForjaCraft Paper/Purpur server. A player
can own more than one paid tier at once (e.g. Duque with 20 days banked, Rei with 30
days banked) but only one is ever *active* - the one whose LuckPerms group is actually
applied. Switching pauses the old tier's clock and resumes the new one's; the active
tier expires and reverts automatically (even while the player, or the whole server, is
offline) when its banked time runs out.

This exists because nothing else in the server's plugin set could express "own two,
pick one active, the other paused" - see the server repo's
`docs/vip_grant_runbook.md` for the "why" and the pre-existing (permanent, no-expiry)
`/lp parent set` process this replaces.

## How it works

- Every tier balance is stored as `bankedSeconds` (a checkpointed remaining balance)
  plus `activeSince` (when it was last activated/checkpointed). The true remaining
  time is always `bankedSeconds - (now - activeSince)` while active, and frozen while
  inactive - see `VipTimeMath` for the (heavily unit-tested) pure logic.
- The applied LuckPerms group is a **permanent** node - this plugin is the sole timing
  authority, not LuckPerms' own temporary-node expiry. See `LuckPermsBridge` and the
  class-level docs on `VipService` for why.
- GriefPrevention has no permission-based claim-block bonus, so every tier
  activation/switch/expiry also dispatches `adjustclaimblocks` via `ClaimBlockBridge`
  to keep that benefit in sync.
- Storage defaults to self-contained SQLite (`plugins/VipManager/vipmanager.db`) - no
  shared credentials to manage, matches how LuckPerms (H2) and ClanForge (SQLite)
  already do it on this server. A shared MySQL backend (`storage.type: mysql`) is also
  available for a future multi-server deployment - see "Multi-server / MySQL storage"
  below.

## Multi-server / MySQL storage

If the network ever splits into separate backend Paper servers per map (e.g. lobby /
world / recursos, behind a proxy), a per-host SQLite file stops working - each host
would have its own isolated VIP balances/kit cooldowns instead of one shared state.
Set `storage.type: mysql` in `config.yml` (plus `storage.mysql.{host,port,database,
username,password}`) to point every host at the same database instead; `MysqlVipStorage`
implements the exact same `VipStorage` interface and schema as `SqliteVipStorage`, so
nothing else in the plugin needs to know which one is active. Pooled via HikariCP since
multiple hosts open connections concurrently. `sqlite` stays the default - nothing
changes for a single-server setup.

The time-accounting model (`VipTimeMath`) is checkpoint-based, not delta-accumulating,
so multiple hosts reading/writing the same shared row stay correct without any
double-counting risk.

**Known limitation, out of scope for this plugin**: the LuckPerms group
`LuckPermsBridge` applies is local to whichever host's JVM made the change. For
tier-gated LuckPerms permissions (shop discounts, Jobs boosts, etc.) to be consistent
across every host too, the network's LuckPerms installation would *also* need to move
off its current per-host local H2 storage to a shared database plus a messaging
service (LuckPerms has native Redis support for exactly this). That's a separate,
network-wide infrastructure decision to make before multi-server actually happens -
this plugin's MySQL option only solves the VIP-balance half of the problem.

## Commands

| Command | Who | Does |
|---|---|---|
| `/vip` | Player | Shows owned tiers, time remaining, which is active |
| `/vip switch <tier>` | Player | Switches active tier (pauses the other) |
| `/vipadmin add <player> <tier> <duration>` | Admin / Azuriom / future Cash purchase | Adds banked time (e.g. `30d`, `1d12h`) |
| `/vipadmin remove <player> <tier> <duration\|all>` | Admin | Removes time or wipes a tier |
| `/vipadmin set <player> <tier> <duration>` | Admin | Absolute override |
| `/vipadmin activate <player> <tier>` | Admin | Force-activates an owned tier |
| `/vipadmin info <player>` | Admin | Full balance dump, works offline |
| `/vipadmin migrate` | Admin, once | Strips the old permanent tier groups from everyone (no time granted - see below) |
| `/vipadmin reload` | Admin | Reloads config.yml |
| `/vip kits` | Player | Opens the DeluxeMenus VIP kit screen (`dm open kitsvip`) |
| `/vip kitclaim <tier> <periodo>` | Player (wired from `kitsvip.yml`'s left-click) | Checks active-tier rank + cooldown, delivers the kit |
| `/vip kitpreview <tier> <periodo>` | Player (wired from `kitsvip.yml`'s right-click) | Opens a read-only inventory with the kit's real items |
| `/kit` or `/kits` | Player | Opens the general kits menu (`dm open kits`) |
| `/kit vip` | Player | Opens the VIP kit screen directly (`dm open kitsvip`) |
| `/kit <novato\|claim\|fazendeiro\|comida>` | Player | Claims that free kit (see "Free kits" below) |
| `/kit preview <novato\|claim\|fazendeiro\|comida>` | Player | Opens a read-only preview of that free kit |

## Placeholders (PlaceholderAPI)

`%vipmanager_active_tier%`, `%vipmanager_active_remaining%`,
`%vipmanager_active_remaining_seconds%`, `%vipmanager_owned_<tier>_remaining%`,
`%vipmanager_owned_<tier>_remaining_seconds%`, `%vipmanager_owned_<tier>_active%`,
`%vipmanager_has_any%`.

## Migration from the old permanent-grant system

`/vipadmin migrate` does **not** convert existing permanent grants into banked time.
It was decided (server hadn't publicly launched yet, so every existing VIP grant was
test data) to just strip the old LuckPerms tier groups back to `default` for everyone
found holding one, and let every real VIP going forward be granted through
`/vipadmin add`. If a future migration ever needs to convert real paying holders
instead of wiping them, that's a deliberate, separate decision - don't repurpose this
command for that without changing its logic first.

After this plugin ships, `docs/vip_grant_runbook.md` in the server-config repo needs
its grant step changed from `lp user <player> parent set <tier>` to
`vipadmin add <player> <tier> <duration>` - a raw `/lp` command bypasses this plugin
entirely and will reintroduce the exact bug (no expiry, no multi-tier support) this
project fixes.

## VIP kit delivery (replaces PlayerKits2 for the 12 tier kits)

The 4 tiers x 3 periods (diário/semanal/mensal) of VIP kits are handled entirely by
this plugin now, not PlayerKits2. See "Free kits" below for the other kits this plugin
also took over, and what's deliberately still left on PlayerKits2.

Why: every Aurorite armor piece used to be delivered via
`console_command: nexo give <item> <amount> <player>` from a PlayerKits2 action - a
plain string command with no compile-time safety, and it silently broke all 4 monthly
kits at once when the argument order turned out to be wrong. `NexoItems` (see below)
gives the same result with actual type checking. PlayerKits2's own kit-preview screen
was also a dead end - opening it from the DeluxeMenus `kitsvip` menu left no way back
without closing everything and re-running `/kitsvip`.

- **Items**: `kit/KitRegistry` loads 12 YAML files (`src/main/resources/kits/*.yml`,
  copied out to `plugins/VipManager/kits/` on first run, editable afterwards) - each
  entry is either a vanilla `material` (with optional `enchants`, `firework-power`,
  `potion-effect`/`extended`/`upgraded`, `display-name`) or a Nexo `nexo-id`. The
  bundled defaults reflect the post-Aurorite kit rebalance (see `docs/vips.md` in the
  server-config repo for the item tables) - the netherite/trim-template crafting
  materials that used to help players build their own armor were removed once the
  armor itself started being delivered pre-built.
- **Nexo items are resolved via Nexo's real Java API**, not a console command:
  `com.nexomc.nexo.api.NexoItems.optionalItemFromId(id)` → `ItemBuilder.build()` →
  `ItemStack` (see `kit/KitItemResolver`). Confirmed by decompiling `nexo-1.28.jar` -
  Nexo isn't published to any Maven repository, so the jar is vendored at `libs/` and
  referenced as a `system`-scope, compile-only dependency (never shaded into the
  output jar - Nexo is already on the server's own classpath at runtime).
- **Access requires an EXACT active-tier match**, not a LuckPerms permission node and
  deliberately not "at or above" either - `kit/KitService#hasAccess` asks the existing
  `VipService` "what's this player's active tier" and requires it to equal the kit's
  own tier. A Rei can NOT also claim Barão's kit: each tier's kits carry real,
  differentiated rewards (a distinct Aurorite color, escalating consumables) on their
  own independent cooldowns, so "at or above" would let a higher tier stack every
  lower tier's rewards on top of its own - caught live after the kit rebalance shipped
  (a Rei activation was claiming the Barão kit too).
- **Cooldowns only tick while the kit's owning tier is active** - a `kit_cooldowns`
  table, modeled exactly like a tier's own VIP balance (`TierState` - "seconds
  remaining" ticking down only while `active`, frozen otherwise), instead of plain
  wall-clock time since the last claim. This closes a real exploit found after the kit
  rebalance shipped: with wall-clock cooldowns, a player owning two tiers could switch
  to the lower one for a few seconds once a month just to harvest its kit, then switch
  straight back, barely spending that tier's own banked time - one purchase could fund
  an indefinite recurring kit. `service/VipService.TierActivationListener` fires
  whenever a tier's active flag flips (first activation, `/vip switch`, and
  expiry-with-fallback in `reconcilePlayer`); `kit/KitCooldownSync` reacts by
  freezing/resuming the cooldowns of that tier's 3 kits via the same
  `VipTimeMath.activate`/`deactivate`/`reconcile` already used (and unit-tested) for
  VIP balances - no new math, just wiring it to a second use. `KitService.claim()`
  also self-heals defensively: since reaching the cooldown check already proves the
  tier is active (`hasAccess` requires it), a cooldown row that's stuck `inactive` from
  some missed transition gets resumed on the spot rather than freezing forever.
  Free kits (novato/claim/fazendeiro/comida) are unaffected - they have no tier/active
  concept, so their cooldown stays plain wall-clock time in the separate
  `kit_claims(uuid, kit_id, last_claimed_at)` table, which is already correct for them.
  The "still on cooldown" message (`kit-on-cooldown` in config.yml) reports the
  remaining wait as days/hours only (`DurationParser.formatDaysHours`) for VIP kits
  (which can run up to 30 days) - `/kit`'s free-kit cooldowns use the finer-grained
  `DurationParser.format()` instead, since those run minutes to an hour. The "you don't
  own this tier" message (`kit-tier-too-low`) and every other kit message are
  configurable the same way.
- **Preview** (`kit/KitPreviewMenu` + `KitPreviewListener`) opens a real, read-only
  Bukkit inventory filled with the kit's actual resolved `ItemStack`s - Aurorite pieces
  show their real skin, since these are genuine Nexo items, not placeholder icons. A
  "Voltar" button in the last slot closes the preview and re-runs `dm open kitsvip` for
  the player, returning them to the DeluxeMenus menu - no DeluxeMenus API dependency,
  just dispatching the same command it uses to open itself
  (`kits.menu-open-command` in config.yml).
- `plugins/DeluxeMenus/gui_menus/kits/kitsvip.yml` in the server-config repo points its
  12 buttons at `/vip kitclaim <tier> <periodo>` and `/vip kitpreview <tier> <periodo>`
  instead of `playerkits claim/preview KIT<TIER><PERIODO>`.

## Free kits (novato/claim/fazendeiro/comida - replaces PlayerKits2 for these 4)

A separate, parallel system from the VIP kits above - no tier, no rank check, just
"can everyone claim this, and how often." Reuses the same item-resolving machinery
(`KitItemResolver`, the shared `kit_claims` table) through `kit/FreeKitRegistry` +
`kit/FreeKitService` instead of `KitRegistry`/`KitService`, since those two are built
entirely around the tier+period model these kits don't have.

- **Items**: `src/main/resources/freekits/{novato,claim,fazendeiro,comida}.yml`,
  copied to `plugins/VipManager/freekits/` on first run - migrated 1:1 from the
  PlayerKits2 kit files they replace, including features those needed that the VIP
  kits never did: per-item `lore` (multi-line), `leather-color` (dyed leather armor),
  and `offhand` (place in the off-hand slot instead of the main inventory).
- **Two claim models**: `one-time: true` (novato, fazendeiro) can be claimed exactly
  once, ever - `kit-already-claimed` is shown forever after, regardless of elapsed
  time. `one-time: false` (claim, comida) is a plain recurring cooldown, same as the
  VIP kits, but its cooldown message uses the fine-grained `DurationParser.format()`
  (minutes/seconds included) rather than `formatDaysHours()` - these cooldowns are
  minutes/an hour long, not weeks, so day/hour-only precision would be actively wrong
  (a 4-minute wait would misleadingly show "1h").
- **Command**: unified `/kit` (alias `/kits`) - see the Commands table above. This
  plugin's `plugin.yml` lists `PlayerKits2` as a `softdepend` specifically so it loads
  *after* it: both declare a `kit` command, and Bukkit lets whichever plugin registers
  second win the unprefixed name. PlayerKits2's own `/playerkits` alias (which it
  declares independently) is untouched by this and keeps working.
- **Deliberately NOT migrated**: `booster` (a Discord-boost reward - not designed yet,
  needs a Cash-store price decision first) and `voto` (a vote-reward crate key,
  apparently triggered by an external voting plugin) both tag their item with NBT for
  ItemsAdder/PhoenixCrates, which this plugin has never touched. `basico.yml` looks
  like an unused example kit from PlayerKits2's own default install - not referenced
  by any menu button. All three stay on PlayerKits2, which stays installed because of
  them.

## Bedrock menus (native forms for mobile players)

Most of the server's DeluxeMenus chests draw their background as a font glyph in the
title and use invisible `PAPER` buttons - Bedrock can't render either, so mobile players
saw an empty chest. `bedrock/BedrockMenuService` opens a native Bedrock **form** instead
(Floodgate/Cumulus API, `provided`, soft dependency - the plugin still starts without
Floodgate and Bedrock players simply keep getting the chest).

- Forms live in `plugins/VipManager/bedrock-menus/<id>.yml` (`title`, optional `content`,
  `buttons: [{text, image?, commands: [...]}]`; commands run as the player). No bundled
  defaults: the server repo generates them from its DeluxeMenus menus
  (`tools/gen_bedrock_forms.py`, see `docs/bedrock_menus.md` there).
- `/bedrockmenu <id> [player]` (admin permission) opens a form. DeluxeMenus calls it from
  the console in a `deny_commands` entry, which is how a Bedrock player who opens a chest
  menu gets bounced to its form.
- `%vipmanager_is_bedrock%` (`true`/`false`) is what that DeluxeMenus requirement checks.
  It asks Floodgate directly and is answered before any database access.
- `/vipadmin reload` reloads the form files too.
- Purchase menus (anything whose buttons run console commands guarded by DeluxeMenus
  requirements) are deliberately NOT given forms: a form can't enforce those guards.

## Building

Requires JDK 21+ and Maven (not bundled - install it, e.g. via your OS package
manager, then run):

```bash
mvn package
```

Produces `target/vipmanager-<version>.jar`. Copy it into the server's `plugins/`
folder. Before the first real build, confirm `paper.api.version` in `pom.xml`
actually matches a published Paper API artifact for the server's live Minecraft
version (`version.json` on the server reports `"26.2"`, which is not itself a Maven
coordinate) - bump it if needed.

## Project layout

```
src/main/java/com/forjacraft/vipmanager/
├── VipManagerPlugin.java        - main class, wires everything in onEnable
├── config/                      - typed config.yml wrapper (reloads in place)
├── service/                     - VipTimeMath (pure logic) + VipService (business logic)
├── storage/                     - VipStorage interface + SqliteVipStorage/MysqlVipStorage
├── integration/                 - LuckPermsBridge, ClaimBlockBridge, PlaceholderAPI expansion
├── listener/                    - join-time reconciliation
├── task/                        - periodic sweep (expires tiers even for offline players)
├── kit/                         - VIP kits: KitRegistry/KitDefinition/KitItemSpec (config),
│                                   KitItemSpecParser (shared YAML item parsing),
│                                   KitItemResolver (Nexo API + vanilla items), KitService
│                                   (tier access/cooldown/delivery), KitDelivery (shared
│                                   resolve+space-check+give, used by KitService AND
│                                   FreeKitService), FreeKitRegistry/FreeKitDefinition/
│                                   FreeKitService (novato/claim/fazendeiro/comida - no
│                                   tier, one-time-or-cooldown), KitCooldownSync (freezes/
│                                   resumes VIP kit cooldowns in lockstep with tier
│                                   activation - see VipService.TierActivationListener),
│                                   KitPreviewMenu + KitPreviewListener (read-only preview
│                                   GUI, shared by both kit systems)
├── bedrock/                     - native Bedrock forms (registry, service, lazy Floodgate bridge)
└── command/                     - /vip (incl. kits/kitclaim/kitpreview), /vipadmin,
                                    /kit (incl. preview) + alias /kits, duration parsing

src/main/resources/kits/*.yml     - bundled default contents for all 12 VIP kits
src/main/resources/freekits/*.yml - bundled default contents for the 4 free kits
libs/nexo-1.28.jar               - vendored, not published anywhere - compile-only, never shaded
```

`VipTimeMath` and `DurationParser` have no Bukkit/LuckPerms dependency and are unit
tested directly (`src/test/java/...`) - everything else needs a running server to
exercise meaningfully.
