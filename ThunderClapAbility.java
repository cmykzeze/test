package com.example.lightningstrike;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Thunder Clap (levels I to III), mace only: a normal mace hit has a chance to call down a real lightning bolt
 * on the target. The bolt hurts it (vanilla lightning damage) and STUNS it in place:
 *  - mobs lose their AI (no moving, no attacking) for the stun time
 *  - players cannot move or jump for the stun time (they can still look around)
 * Level I = 25% chance / 1.0s stun, II = 35% / 1.25s, III = 45% / 1.5s.
 *
 * The bolt is "visual only" (loud, bright, hurts through our own damage call) so it never sets YOU on fire,
 * never hurts you when you stand next to the target, and doesn't turn pigs, villagers or creepers into other things.
 */
public class ThunderClapAbility {
	public static final ResourceKey<Enchantment> THUNDER_CLAP = ResourceKey.create(
			Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(LightningStrikeMod.MOD_ID, "thunder_clap"));

	// ---- Tweak these numbers if you want ----
	/** Chance per mace hit: I = 25%, II = 35%, III = 45% */
	private static final float[] CHANCE = {0.25f, 0.35f, 0.45f};
	/** Stun time in ticks: I = 1.0s, II = 1.25s, III = 1.5s */
	private static final int[] STUN_TICKS = {20, 25, 30};
	/** Vanilla lightning damage is 5 (2.5 hearts) */
	private static final float LIGHTNING_DAMAGE = 5.0f;

	private static class Stun {
		final LivingEntity target;
		int endTick;
		final boolean restoreAi; // true if we switched the mob's AI off, so we must switch it back on

		Stun(LivingEntity target, int endTick, boolean restoreAi) {
			this.target = target;
			this.endTick = endTick;
			this.restoreAi = restoreAi;
		}
	}

	private static final Map<UUID, Stun> STUNS = new HashMap<>();

	public static void register() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			// Only normal player melee hits (the bolt's own damage is lightning damage, so it can't re-trigger)
			if (blocked || !source.is(DamageTypes.PLAYER_ATTACK)
					|| !(source.getEntity() instanceof ServerPlayer player)
					|| entity == player || !entity.isAlive()
					|| !(entity instanceof Mob || entity instanceof Player)) {
				return;
			}
			ItemStack stack = player.getMainHandItem();
			if (!stack.is(Items.MACE)) {
				return;
			}
			ServerLevel level = (ServerLevel) player.level();
			int lvl = getLevel(level, stack);
			if (lvl <= 0 || player.getRandom().nextFloat() >= chance(lvl)) {
				return;
			}
			clap(level, player, entity, lvl);
		});
	}

	private static void clap(ServerLevel level, ServerPlayer player, LivingEntity target, int lvl) {
		// The lightning itself: bright flash + thunder, no fire and no side effects
		LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
		if (bolt != null) {
			bolt.setPos(target.getX(), target.getY(), target.getZ());
			bolt.setVisualOnly(true);
			level.addFreshEntity(bolt);
		}
		level.sendParticles(ParticleTypes.ELECTRIC_SPARK, target.getX(), target.getY() + target.getBbHeight() * 0.5,
				target.getZ(), 25, 0.4, 0.6, 0.4, 0.2);

		// Lightning damage (resetting the hit cooldown so it isn't swallowed by the mace hit that just landed)
		resetHurtCooldown(target);
		target.hurtServer(level, level.damageSources().lightningBolt(), LIGHTNING_DAMAGE);
		if (!target.isAlive()) {
			return;
		}

		// The stun
		int now = level.getServer().getTickCount();
		int ticks = (int) pick(STUN_TICKS, lvl);
		Stun existing = STUNS.get(target.getUUID());
		if (existing != null && existing.target == target) {
			existing.endTick = Math.max(existing.endTick, now + ticks); // refresh, no stacking
		} else {
			boolean switchAiOff = target instanceof Mob mob && !mob.isNoAi();
			if (switchAiOff) {
				((Mob) target).setNoAi(true);
			}
			STUNS.put(target.getUUID(), new Stun(target, now + ticks, switchAiOff));
		}
		// Rooted: Slowness 7 stops all walking, the big negative jump stops jumping (also covers players)
		target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, ticks, 6, false, false));
		target.addEffect(new MobEffectInstance(MobEffects.JUMP_BOOST, ticks, 200, false, false));

		player.sendSystemMessage(EnchantColors.text(
				"Thunder Clap!", EnchantColors.THUNDER_CLAP), true);
	}

	/** Runs every server tick: ends stuns and gives mobs their AI back. */
	public static void tick(MinecraftServer server) {
		int now = server.getTickCount();
		Iterator<Stun> it = STUNS.values().iterator();
		while (it.hasNext()) {
			Stun s = it.next();
			if (s.target.isRemoved() || !s.target.isAlive() || now >= s.endTick) {
				if (s.restoreAi && s.target instanceof Mob mob && !mob.isRemoved()) {
					mob.setNoAi(false);
				}
				it.remove();
			}
		}
	}

	// invulnerableTime is private in Entity on this Minecraft version, so it is reached through reflection
	// (same trick Bleed uses).
	private static Field invulnerableTimeField;
	private static boolean invulnerableTimeLookedUp;

	private static void resetHurtCooldown(LivingEntity target) {
		if (!invulnerableTimeLookedUp) {
			invulnerableTimeLookedUp = true;
			try {
				invulnerableTimeField = net.minecraft.world.entity.Entity.class.getDeclaredField("invulnerableTime");
				invulnerableTimeField.setAccessible(true);
			} catch (ReflectiveOperationException | RuntimeException e) {
				invulnerableTimeField = null;
			}
		}
		if (invulnerableTimeField != null) {
			try {
				invulnerableTimeField.setInt(target, 0);
			} catch (IllegalAccessException | RuntimeException ignored) {
				// If this fails the stun still works, the bolt damage may just be skipped by the hit cooldown.
			}
		}
	}

	private static float chance(int level) {
		return CHANCE[Math.max(1, Math.min(CHANCE.length, level)) - 1];
	}

	private static double pick(int[] table, int level) {
		return table[Math.max(1, Math.min(table.length, level)) - 1];
	}

	private static int getLevel(ServerLevel server, ItemStack stack) {
		Optional<? extends Holder<Enchantment>> holder =
				server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(THUNDER_CLAP);
		if (holder.isEmpty()) {
			return 0;
		}
		return EnchantmentHelper.getItemEnchantmentLevel(holder.get(), stack);
	}
}
