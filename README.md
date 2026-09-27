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
└── command/                     - /vip, /vipadmin, duration parsing
```

`VipTimeMath` and `DurationParser` have no Bukkit/LuckPerms dependency and are unit
tested directly (`src/test/java/...`) - everything else needs a running server to
exercise meaningfully.
