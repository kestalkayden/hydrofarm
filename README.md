# Hydrofarm

Water-tech and farm automation for Minecraft 26.1.x — Fabric + NeoForge from one codebase.

A focused set of machines for moving fluids, growing crops and trees, keeping animals, and
turning the results into items, XP, and energy — without dragging in a whole tech-tree.

## Fluids & logistics

- **Liquid Tank** — stackable storage; adjacent tanks merge into one pool with a visible,
  glowing fluid level (water, lava, milk, Liquid XP)
- **Liquid Pipe / Item Pipe** — auto-connecting networks that grow a port wherever they touch
  an inventory; right-click a port to make it a **terminal** with per-face extract/insert modes
  and whitelist/blacklist filters
- **Liquid Siphon** — slow AoE extraction from vanilla water sources (source blocks regenerate)
- **Sprinkler** — gentle crop growth boost (~2.5×) and auto-hydration across a 9×9 — the same
  area a water block serves — fed by pipes or tanks
- **Cauldrons** — pipe-connectable as fluid sources/sinks in whole-level increments, on both
  loaders

## Growing

- **Hydroponics Bed** — 4 planter quadrants per bed, each growing a configured crop from the
  cluster's shared water pool; harvests collect automatically and trickle Liquid XP
- **Tree Farm Bed** — the same system for saplings: logs and saplings without the chopping
- Adjacent beds of one type form a **cluster** — shared water, items, and XP, one GUI with
  cluster-wide rates

## Animals

- **Capture Net** — scoop up an animal, item and all
- **Husbandry Bed** — housed animals produce wool, eggs, and milk on feed + water cycles
- **Butcher Bed** — a breeding pair sustains a steady drip of that species' slaughter loot and
  Liquid XP; the stock itself is never consumed

## Energy & utility

- **Hydroelectric Generator** — water in, energy out (Team Reborn Energy on Fabric, FE on
  NeoForge — works with the usual tech-mod cables and machines)
- **Energy Pipe / Energy Cell** — simple distribution and cluster-aware banking
- **Autocrafter** — energy-powered template crafting from piped-in ingredients, with the recipe
  lit up on its sides and the result floating above
- **Mending Station** — repairs gear with Liquid XP + energy; pipe-automatable in and out
- **Monster Repulser** — energy-upkeep field that stops hostile spawns inside it
- **XP Drain** — sneak on it to bank your XP as Liquid XP (20 mB/XP) into the attached tank;
  right-click to take it back. Liquid XP also bottles into XP bottles at any bed or tank

## Decoration

- **Glowcubes** — dye-colored light blocks with a slow internal swirl

## Integrations

- **Jade** — cluster/bed overlays out of the box
- **ModMenu** (Fabric) / **Mods screen** (NeoForge) — in-game config button
- **Capture Net** — when the standalone `capturenet` mod is installed, Hydrofarm defers to it

## Configuration

`config/hydrofarm.properties` (hand-editable, self-healing) or the in-game screen:

- client render levers: bed-crop and pen-animal rendering, view distances, animals shown per pen
- server performance: sprinkler particle count/interval
- gameplay: per-bed-type Liquid XP output toggles (disabled = gauge hidden everywhere)

## Requirements

- Minecraft 26.1.x, Java 25
- **Fabric**: Fabric Loader ≥ 0.18.4 + Fabric API (Team Reborn Energy is bundled)
- **NeoForge**: 26.1.2+

## Building

```
./gradlew buildAll
```

Outputs land in `fabric/build/libs/` and `neoforge/build/libs/`.

Layout: gameplay logic lives loader-agnostic in `common/` (compiled against vanilla as a purity
check) behind tiny platform seams; `fabric/` and `neoforge/` implement the seams; assets and
data ship from `shared-resources/`.

## License

MIT.
