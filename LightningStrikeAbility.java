package com.example.lightningstrike;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Lightning Strike (levels I to V): left-click with the sword to fire yellow beams at nearby enemies.
 *
 * Melee buff: every normal melee hit on an enemy shortens the cooldown a little, down to a floor
 * (I = 4s ... V = 2s). Eight hits reach the floor. If you go 8 seconds without a melee hit, or you die,
 * the buff is lost and you cannot build it again for 30 seconds (the sword still works at its normal cooldown).
 */
public class LightningStrikeAbility {
	public static final ResourceKey<Enchantment> LIGHTNING_STRIKE = ResourceKey.create(
			Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(LightningStrikeMod.MOD_ID, "lightning_strike"));

	// ---- Tweak these numbers if you want ----
	private static final int COOLDOWN_TICKS_LEVEL_1 = 200; // 10 seconds at level I
	private static final int COOLDOWN_TICKS_LEVEL_5 = 100; // 5 seconds at level V (levels in between are evenly spaced)
	/** Cooldown floor in ticks with the full melee buff: I = 4s, II = 3.5s, III = 3s, IV = 2.5s, V = 2s */
	private static final int[] FLOOR_TICKS = {80, 70, 60, 50, 40};
	/** Damage per beam in hearts: I = 3, II = 3.25, III = 3.5, IV = 3.75, V = 4 */
	private static final double[] DAMAGE_HEARTS = {3.0, 3.25, 3.5, 3.75, 4.0};
	private static final float FOCUS_MULTIPLIER = 1.25f; // damage x1.25 when there is only one enemy in range
	private static final float PLAYER_DAMAGE_MULTIPLIER = 0.6f; // beams do 60% damage to other players
	private static final double PLAYER_RANGE_CAP = 20.0;         // and cannot reach players further than this
	private static final int BRANCHES_PER_LEVEL = 2;     // level I = 2 branches, V = 10
	private static final double BASE_RANGE = 10.0;       // level I = 10 blocks
	private static final double RANGE_PER_LEVEL = 5.0;   // +5 blocks per extra level (V = 30)
	private static final double STEP = 0.35;             // particle spacing

	private static final int BUFF_MAX_HITS = 8;          // melee hits needed to reach the cooldown floor
	private static final int MIN_TICKS_BETWEEN_HITS = 10; // swings faster than this don't count (anti spam-click)
	private static final int COMBAT_TIMEOUT_TICKS = 160; // 8 seconds without a melee hit loses the buff
	private static final int LOCKOUT_TICKS = 600;        // 30 seconds without the buff after losing it
	private static final int MESSAGE_INTERVAL_TICKS = 20; // at most one cooldown message per second

	private static class State {
		int hits = 0;            // melee buff progress, 0 to BUFF_MAX_HITS
		int lastHitTick = 0;     // server tick of the last counted melee hit
		int lockoutEnd = 0;      // server tick when the lockout ends
		int cooldownEnd = 0;     // server tick when Lightning Strike is ready again
		int reducedTicks = 0;    // how much the melee buff has shaved off the cooldown that is running now
		int lastMessageTick = -1000;
		boolean readyPingPending = false;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	private static State state(UUID id) {
		return STATES.computeIfAbsent(id, k -> new State());
	}

	public static void register() {
		// Count normal melee hits (Lightning Strike beams are indirect magic damage, so they are excluded)
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			if (blocked || !source.is(DamageTypes.PLAYER_ATTACK)
					|| !(source.getEntity() instanceof ServerPlayer player)
					|| entity == player || !(entity instanceof Enemy || entity instanceof Player)) {
				return;
			}
			ItemStack stack = player.getMainHandItem();
			if (!stack.is(ItemTags.SWORDS)) {
				return;
			}
			ServerLevel level = (ServerLevel) player.level();
			int lsLevel = getLevel(level, stack);
			if (lsLevel > 0) {
				onMeleeHit(level, player, stack, lsLevel);
			}
		});
		// Dying loses the buff
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				int now = ((ServerLevel) player.level()).getServer().getTickCount();
				State s = state(player.getUUID());
				loseBuff(player, s, now, false);
				s.cooldownEnd = 0;
				s.reducedTicks = 0;
				s.readyPingPending = false;
			}
		});
	}

	// ---------------------------------------------------------------- melee buff

	private static void onMeleeHit(ServerLevel level, ServerPlayer player, ItemStack stack, int lsLevel) {
		int now = level.getServer().getTickCount();
		State s = state(player.getUUID());
		if (now < s.lockoutEnd) {
			return; // punished: no buff during the lockout
		}
		if (now - s.lastHitTick < MIN_TICKS_BETWEEN_HITS && s.hits > 0) {
			return; // spam clicking
		}
		s.lastHitTick = now;
		if (s.hits >= BUFF_MAX_HITS) {
			return; // already at the floor, the hit only keeps you in combat
		}
		s.hits++;

		// A cooldown that is already running gets shorter too, so hits during the cooldown are not wasted
		if (now < s.cooldownEnd) {
			int step = stepTicks(lsLevel);
			int cut = Math.min(step, s.cooldownEnd - now);
			s.cooldownEnd -= cut;
			s.reducedTicks += cut;
			syncVanillaCooldown(player, stack, s.cooldownEnd - now);
		}
	}

	/** Cooldown shaved off per melee hit at this level. */
	private static int stepTicks(int level) {
		return (baseTicks(level) - floorTicks(level)) / BUFF_MAX_HITS;
	}

	/** The buff is lost: hits go back to 0 and (normally) the 30 second lockout starts. */
	private static void loseBuff(ServerPlayer player, State s, int now, boolean announce) {
		if (s.hits <= 0) {
			return;
		}
		s.hits = 0;
		s.lockoutEnd = now + LOCKOUT_TICKS;
		// A cooldown that is still running goes back to what it would have been without the buff
		if (now < s.cooldownEnd) {
			s.cooldownEnd += s.reducedTicks;
			ItemStack stack = player.getMainHandItem();
			if (stack.is(ItemTags.SWORDS)) {
				syncVanillaCooldown(player, stack, s.cooldownEnd - now);
			}
		}
		s.reducedTicks = 0;
		if (announce) {
			player.sendSystemMessage(name().append(EnchantColors.text(
					" melee buff lost! LOCKED for 30s", EnchantColors.LOCKED)), true);
			((ServerLevel) player.level()).playSound(null, player.blockPosition(),
					SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.6f, 1.2f);
		}
	}

	/** Runs every server tick: loses the buff after 8s out of combat and pings when the sword is ready. */
	public static void tick(MinecraftServer server) {
		int now = server.getTickCount();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			State s = STATES.get(player.getUUID());
			if (s == null) {
				continue;
			}
			if (s.hits > 0 && now - s.lastHitTick > COMBAT_TIMEOUT_TICKS) {
				loseBuff(player, s, now, true);
			}
			if (s.readyPingPending && now >= s.cooldownEnd) {
				s.readyPingPending = false;
				ItemStack stack = player.getMainHandItem();
				if (stack.is(ItemTags.SWORDS) && getLevel((ServerLevel) player.level(), stack) > 0) {
					player.sendSystemMessage(name().append(EnchantColors.text(" READY", EnchantColors.LIGHTNING_STRIKE)), true);
					((ServerLevel) player.level()).playSound(null, player.blockPosition(),
							SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.4f, 1.5f);
				}
			}
		}
	}

	private static MutableComponent name() {
		return EnchantColors.text("Lightning Strike", EnchantColors.LIGHTNING_STRIKE);
	}

	/** Mirrors our own timer onto the vanilla item cooldown, which only draws the sweep on the hotbar slot. */
	private static void syncVanillaCooldown(ServerPlayer player, ItemStack stack, int remainingTicks) {
		if (remainingTicks > 0) {
			player.getCooldowns().addCooldown(stack, remainingTicks);
		}
	}

	// ---------------------------------------------------------------- casting

	/** Called on the server when a player left-clicks with a sword. */
	public static void activate(ServerPlayer player) {
		ServerLevel server = (ServerLevel) player.level();
		ItemStack stack = player.getMainHandItem();
		if (!stack.is(ItemTags.SWORDS)) {
			return;
		}
		int level = getLevel(server, stack);
		if (level <= 0) {
			return;
		}
		int now = server.getServer().getTickCount();
		State s = state(player.getUUID());

		if (now < s.cooldownEnd) {
			showCooldown(player, s, now);
			return;
		}

		int hits = fire(server, player, level);
		RageAbility.onLightningStrike(server, player, stack, hits);

		int cooldown = baseTicks(level) - s.hits * stepTicks(level);
		s.cooldownEnd = now + cooldown;
		s.reducedTicks = s.hits * stepTicks(level);
		s.readyPingPending = true;
		syncVanillaCooldown(player, stack, cooldown);
	}

	/** "Lightning Strike 3.4s [5/8 hits]" in the action bar, at most once per second. */
	private static void showCooldown(ServerPlayer player, State s, int now) {
		if (now - s.lastMessageTick < MESSAGE_INTERVAL_TICKS) {
			return;
		}
		s.lastMessageTick = now;
		MutableComponent msg = name().append(EnchantColors.text(
				String.format(Locale.ROOT, " %.1fs", (s.cooldownEnd - now) / 20.0), EnchantColors.GRAY));
		if (now < s.lockoutEnd) {
			msg.append(EnchantColors.text(
					String.format(Locale.ROOT, "  LOCKED %ds", (s.lockoutEnd - now + 19) / 20), EnchantColors.LOCKED));
		} else if (s.hits >= BUFF_MAX_HITS) {
			msg.append(EnchantColors.text("  MAX", EnchantColors.LIGHTNING_STRIKE));
		} else {
			msg.append(EnchantColors.text(
					String.format(Locale.ROOT, "  [%d/%d hits]", s.hits, BUFF_MAX_HITS), EnchantColors.GRAY));
		}
		player.sendSystemMessage(msg, true);
	}

	/** Normal cooldown: I = 10s, II = 8.75s, III = 7.5s, IV = 6.25s, V = 5s. */
	private static int baseTicks(int level) {
		int l = Math.max(1, Math.min(5, level));
		return COOLDOWN_TICKS_LEVEL_1
				- (COOLDOWN_TICKS_LEVEL_1 - COOLDOWN_TICKS_LEVEL_5) * (l - 1) / 4;
	}

	private static int floorTicks(int level) {
		return FLOOR_TICKS[Math.max(1, Math.min(5, level)) - 1];
	}

	private static float damage(int level) {
		return (float) (DAMAGE_HEARTS[Math.max(1, Math.min(5, level)) - 1] * 2);
	}

	private static int getLevel(ServerLevel server, ItemStack stack) {
		Optional<? extends Holder<Enchantment>> holder =
				server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(LIGHTNING_STRIKE);
		if (holder.isEmpty()) {
			return 0;
		}
		return EnchantmentHelper.getItemEnchantmentLevel(holder.get(), stack);
	}

	/** Fires the strike and returns how many enemies it damaged. */
	private static int fire(ServerLevel server, ServerPlayer player, int level) {
		int branches = level * BRANCHES_PER_LEVEL;
		double range = BASE_RANGE + (level - 1) * RANGE_PER_LEVEL;
		float baseDamage = damage(level);

		Vec3 origin = player.getEyePosition().add(0, -0.15, 0);
		Vec3 look = player.getLookAngle().normalize();

		// Hostile mobs and other players inside the radius, nearest first (players only up to 20 blocks)
		List<LivingEntity> targets = server.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate(range),
				t -> t != player && t.isAlive() && !t.isSpectator()
						&& (t instanceof Enemy || t instanceof Player)
						&& t.distanceTo(player) <= (t instanceof Player ? Math.min(range, PLAYER_RANGE_CAP) : range));
		targets.sort(Comparator.comparingDouble(t -> t.distanceToSqr(player)));

		DustParticleOptions yellow = new DustParticleOptions(EnchantColors.LIGHTNING_STRIKE, 1.0f);
		RandomSource rnd = player.getRandom();
		int used = 0;
		int hits = 0;

		// Only one enemy in range: every branch focuses on it and the hit is stronger
		boolean focus = targets.size() == 1;

		// One branch per target, nearest first (skip targets behind walls)
		for (LivingEntity t : targets) {
			if (used >= branches) {
				break;
			}
			Vec3 center = t.position().add(0, t.getBbHeight() * 0.5, 0);
			var los = server.clip(new ClipContext(origin, center,
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			if (los.getType() != HitResult.Type.MISS) {
				continue;
			}
			float dmg = focus ? baseDamage * FOCUS_MULTIPLIER : baseDamage;
			if (t instanceof Player) {
				dmg *= PLAYER_DAMAGE_MULTIPLIER;
			}
			// One branch = one target = one hit (x1.25 damage when it is the only target)
			if (beam(server, player, origin, center.subtract(origin).normalize(),
					Math.min(range, origin.distanceTo(center)), yellow, t, dmg)) {
				hits++;
			}
			used++;

			// The other branches all converge on that same enemy, just for show (the damage is dealt once)
			if (focus) {
				for (; used < branches; used++) {
					Vec3 aim = center.add(
							(rnd.nextDouble() - 0.5) * 0.8,
							(rnd.nextDouble() - 0.5) * 0.8,
							(rnd.nextDouble() - 0.5) * 0.8);
					beam(server, player, origin, aim.subtract(origin).normalize(),
							Math.min(range, origin.distanceTo(aim)), yellow, null, 0f);
				}
			}
		}

		// Leftover branches (no target left) fan out in front of you, just for show
		for (; used < branches; used++) {
			Vec3 dir = (used == 0 ? look : look.add(
					(rnd.nextDouble() - 0.5) * 0.9,
					(rnd.nextDouble() - 0.5) * 0.5,
					(rnd.nextDouble() - 0.5) * 0.9)).normalize();
			beam(server, player, origin, dir, range, yellow, null, 0f);
		}

		server.playSound(null, player.blockPosition(),
				SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.5f, 1.6f);
		return hits;
	}

	/** Draws one straight yellow line. If it has a target, that target takes damage exactly once.
	 *  Returns true if a target was damaged. */
	private static boolean beam(ServerLevel server, ServerPlayer player, Vec3 start, Vec3 dir,
			double maxLength, DustParticleOptions particle, LivingEntity target, float damage) {
		double length = maxLength;
		var blockHit = server.clip(new ClipContext(start, start.add(dir.scale(maxLength)),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if (blockHit.getType() != HitResult.Type.MISS) {
			length = Math.min(maxLength, blockHit.getLocation().distanceTo(start));
		}

		for (double d = 0.8; d <= length; d += STEP) {
			Vec3 p = start.add(dir.scale(d));
			server.sendParticles(particle, p.x, p.y, p.z, 1, 0, 0, 0, 0);
		}

		if (target != null && target.isAlive()) {
			return target.hurtServer(server, server.damageSources().indirectMagic(player, player), damage);
		}
		return false;
	}
}
