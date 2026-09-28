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
- Storage is self-contained SQLite (`plugins/VipManager/vipmanager.db`) - no shared
  credentials to manage, matches how LuckPerms (H2) and ClanForge (SQLite) already do
  it on this server.

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
this plugin now, not PlayerKits2 - PlayerKits2 stays installed only for the 3 free
kits (novato/claim/fazendeiro), which are unrelated to VIP tiers and out of scope here.

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
  `potion-effect`/`extended`/`upgraded`) or a Nexo `nexo-id`. The bundled defaults are
  an exact copy of what the replaced PlayerKits2 kits already gave.
- **Nexo items are resolved via Nexo's real Java API**, not a console command:
  `com.nexomc.nexo.api.NexoItems.optionalItemFromId(id)` → `ItemBuilder.build()` →
  `ItemStack` (see `kit/KitItemResolver`). Confirmed by decompiling `nexo-1.28.jar` -
  Nexo isn't published to any Maven repository, so the jar is vendored at `libs/` and
  referenced as a `system`-scope, compile-only dependency (never shaded into the
  output jar - Nexo is already on the server's own classpath at runtime).
- **Access is by the player's active tier rank**, not a LuckPerms permission node -
  `kit/KitService#hasAccess` asks the existing `VipService` "what's this player's
  active tier" and compares rank, so a Rei can still claim a Barão kit (matching how
  LuckPerms group inheritance already worked) without needing the old
  `playerkits.kit.KIT*` permissions kept in sync by hand.
- **Cooldowns** live in a new `kit_claims(uuid, kit_id, last_claimed_at)` table in the
  same SQLite database.
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
├── storage/                     - VipStorage interface + SqliteVipStorage
├── integration/                 - LuckPermsBridge, ClaimBlockBridge, PlaceholderAPI expansion
├── listener/                    - join-time reconciliation
├── task/                        - periodic sweep (expires tiers even for offline players)
├── kit/                         - VIP kit delivery: KitRegistry/KitDefinition/KitItemSpec
│                                   (config), KitItemResolver (Nexo API + vanilla items),
│                                   KitService (access/cooldown/delivery), KitPreviewMenu
│                                   + KitPreviewListener (read-only preview GUI)
└── command/                     - /vip (incl. kits/kitclaim/kitpreview), /vipadmin, duration parsing

src/main/resources/kits/*.yml    - bundled default contents for all 12 VIP kits
libs/nexo-1.28.jar               - vendored, not published anywhere - compile-only, never shaded
```

`VipTimeMath` and `DurationParser` have no Bukkit/LuckPerms dependency and are unit
tested directly (`src/test/java/...`) - everything else needs a running server to
exercise meaningfully.
