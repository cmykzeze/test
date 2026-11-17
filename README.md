# Lightning Strike + Rage + Frozen Heart + Bleed + Shadow Step (Fabric, Minecraft 26.3)

Five sword enchantments and two mace enchantments.

**Lightning Strike (I-V)** (yellow): left-click with an enchanted sword. Yellow beams hit hostile mobs and other
players in range (one beam per target, nearest first). Level N gives 2N beams and 10 + 5(N-1) blocks of range.
- Damage per beam: 3 / 3.25 / 3.5 / 3.75 / 4 hearts (I to V). With only one enemy in range the hit is 1.25x.
- Against players: 60% damage and a 20 block range cap.
- Normal cooldown: I = 10s, II = 8.75s, III = 7.5s, IV = 6.25s, V = 5s.
- **Melee buff:** every normal melee hit on a mob or player shortens the cooldown (8 hits reach the floor:
  I = 4s, II = 3.5s, III = 3s, IV = 2.5s, V = 2s). Beams and blocked hits don't count, and neither do swings
  faster than about 0.5s apart. A running cooldown shortens too.
- 8 seconds without a melee hit, or dying, loses the buff and locks you out of it for 30 seconds (the sword
  still works at its normal cooldown).
- Clicking while on cooldown shows the time left, buff progress (e.g. 5/8 hits), MAX or LOCKED in the action bar
  (once per second at most). A READY ping plays when it comes back.

**Rage (I-VII)** (orange): bonus melee damage while holding the sword, shown in the action bar.
- Flat bonus: +0.4 hearts at I, +0.8 at V, +1.0 at VII.
- With Lightning Strike on the same sword: every enemy a strike hits adds +0.25 (I) to +0.30 (VII) hearts on top
  of the flat bonus, up to a total cap of 1.4 (I) / 2.0 (V) / 2.2 (VII) hearts (about 4 enemy hits). The combo
  is lost after 5 seconds out of combat; the flat bonus stays.
- Levels I-V come from the enchanting table (VI and above are blocked there). Levels I-VI also appear in
  dungeon, mineshaft, desert pyramid and jungle temple chests (4%). Level VII only appears in
  ancient city chests (12%). Two level VI books can still be combined in an anvil to make VII (the maximum).

**Frozen Heart (I-II)** (ice blue): a normal sword hit has a chance to slow the target.
Level I = Slowness I for 3s with a 40% chance, level II = Slowness II for 2s with a 50% chance.
Lightning Strike beams do not trigger it. Found in the enchanting table and random chest loot.

**Bleed (I-IV)** (crimson): a normal sword hit has a 20% / 25% / 30% / 35% chance to make the target bleed for 4 seconds.
- Blood pulses once per second (4 pulses), ignoring armor: 1.5 / 2.5 / 3.5 / 4.5 hearts in total.
- Blinds the target when the bleed starts: 0.5s at I-II, 1s at III-IV.
- While your target bleeds, your own melee damage is reduced by 5% / 10% / 15% / 20%.
- A new proc on a bleeding target refreshes it (no stacking). Lightning Strike beams do not trigger it.

**Shadow Step (I-III)** (violet): right-click with the sword while aiming at a hostile mob or player. You teleport
right behind it, facing its back.
- Range: 8 / 11 / 14 blocks (players: 10 at most). The target must be near your crosshair and in line of sight.
- Cooldown: 12s / 10s / 8s. A READY ping plays when it comes back.
- **Backstab:** your next normal melee hit within 4 seconds deals +1.0 / +1.5 / +2.0 hearts. The bonus is spent by
  that hit, or lost if the 4 seconds run out or you put the sword away.
- Needs room behind the target, otherwise the step fails (no cooldown used). Found in the enchanting table and random chest loot.

**Thunder Clap (I-III)** (aqua, mace only): a normal mace hit has a chance to call down a real lightning bolt on the target.
- Chance: 25% / 35% / 45%. Stun: 1.0s / 1.25s / 1.5s.
- The bolt deals normal lightning damage (2.5 hearts) and stuns the target in place: mobs lose their AI (no moving or
  attacking), players can't move or jump (they can still look and swing).
- The bolt is visual-only for safety: no fire, no damage to you if you stand next to the target, and no mob transformations.
- A new proc on a stunned target refreshes the stun (no stacking). Found in the enchanting table and random chest loot.

**Aftershock (I-III)** (amber, mace only): after a smash attack (a mace hit after falling more than 1.5 blocks) the ground
cracks, and 1 second later a shockwave bursts out of the impact spot.
- Radius: 3 / 4 / 5 blocks. Damage: 30% / 40% / 50% of the smash damage (max 12 hearts), so a longer fall means a bigger blast.
- Hits hostile mobs and players (never you), and throws them up and away from the centre.
- Cooldown: 8s / 7s / 6s. The shockwave is magic damage, so it does not trigger Thunder Clap, Bleed or other on-hit enchantments.
- Found in the enchanting table and random chest loot.

Build: `./gradlew build` -> `build/libs/lightning-strike-1.0.0.jar`
Test in dev: `./gradlew runClient`
/give @s diamond_sword[enchantments={"lightningstrike:rage":7,"lightningstrike:lightning_strike":5,"lightningstrike:frozen_heart":2,"lightningstrike:bleed":4,"lightningstrike:shadow_step":3}]
