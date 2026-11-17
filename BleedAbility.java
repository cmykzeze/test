package com.example.lightningstrike;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Bleed (levels I to IV): a normal sword hit has a chance to make the target bleed for 4 seconds.
 *  - The bleed hurts in 4 pulses, one per second, with blood particles. It ignores armor.
 *  - The target is blinded when the bleed starts.
 *  - While one of your targets is bleeding, your own melee damage is reduced (see penalty()).
 *  - Hitting a bleeding target again with a successful proc refreshes the bleed (it does not stack).
 * Lightning Strike beams do not trigger it and are not reduced by the penalty.
 */
public class BleedAbility {
	public static final ResourceKey<Enchantment> BLEED = ResourceKey.create(
			Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(LightningStrikeMod.MOD_ID, "bleed"));

	private static final Identifier PENALTY_ID =
			Identifier.fromNamespaceAndPath(LightningStrikeMod.MOD_ID, "bleed_penalty");

	// ---- Tweak these numbers if you want ----
	private static final int PULSES = 4;               // 4 pulses...
	private static final int PULSE_INTERVAL_TICKS = 20; // ...one per second = 4 seconds of bleeding
	private static final DustParticleOptions BLOOD = new DustParticleOptions(EnchantColors.BLEED, 1.2f);

	/** Chance per hit: I = 20%, II = 25%, III = 30%, IV = 35% */
	private static float chance(int level) {
		return 0.15f + 0.05f * level;
	}

	/** Total bleed damage in hearts over the whole bleed: I = 1.5, II = 2.5, III = 3.5, IV = 4.5 */
	private static float totalHearts(int level) {
		return clampLevel(level) + 0.5f;
	}

	/** Blindness in ticks: I and II = 0.5 seconds, III and IV = 1 second */
	private static int blindnessTicks(int level) {
		return clampLevel(level) >= 3 ? 20 : 10;
	}

	/** How much of your own melee damage you lose while a target bleeds: I = 5%, II = 10%, III = 15%, IV = 20% */
	private static double penalty(int level) {
		return switch (clampLevel(level)) {
			case 1 -> 0.05;
			case 2 -> 0.10;
			case 3 -> 0.15;
			default -> 0.20;
		};
	}

	private static int clampLevel(int level) {
		return Math.max(1, Math.min(4, level));
	}

	private static class Bleed {
		final LivingEntity target;
		UUID attacker;
		int level;
		int pulsesLeft;
		int nextPulseTick;

		Bleed(LivingEntity target) {
			this.target = target;
		}
	}

	private static final Map<UUID, Bleed> BLEEDS = new HashMap<>();

	public static void register() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			// Only normal player melee hits (Lightning Strike is magic damage, the bleed itself too)
			if (blocked || !source.is(DamageTypes.PLAYER_ATTACK)
					|| !(source.getEntity() instanceof ServerPlayer player)
					|| entity == player || !entity.isAlive()
					|| !(entity instanceof Mob || entity instanceof Player)) {
				return;
			}
			ItemStack stack = player.getMainHandItem();
			if (!stack.is(ItemTags.SWORDS)) {
				return;
			}
			ServerLevel level = (ServerLevel) player.level();
			int bleedLevel = getLevel(level, stack);
			if (bleedLevel <= 0 || player.getRandom().nextFloat() >= chance(bleedLevel)) {
				return;
			}
			startOrRefresh(level, player, entity, bleedLevel);
		});
		ServerTickEvents.END_SERVER_TICK.register(BleedAbility::tick);
	}

	private static void startOrRefresh(ServerLevel level, ServerPlayer player, LivingEntity target, int bleedLevel) {
		int now = level.getServer().getTickCount();
		Bleed b = BLEEDS.get(target.getUUID());
		boolean fresh = b == null || b.target != target || !target.isAlive();
		if (fresh) {
			b = new Bleed(target);
			BLEEDS.put(target.getUUID(), b);
			// Blindness only when a new bleed starts, so fast hitting can't keep the target permanently blind
			target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, blindnessTicks(bleedLevel), 0));
		}
		b.attacker = player.getUUID();
		b.level = fresh ? bleedLevel : Math.max(b.level, bleedLevel);
		b.pulsesLeft = PULSES;
		b.nextPulseTick = now + PULSE_INTERVAL_TICKS;
		blood(level, target, 12);
	}

	private static void blood(ServerLevel level, LivingEntity target, int count) {
		level.sendParticles(BLOOD, target.getX(), target.getY() + target.getBbHeight() * 0.6, target.getZ(),
				count, 0.2, 0.25, 0.2, 0.0);
	}

	/** Runs every server tick: deals the bleed pulses and applies the damage penalty to the attackers. */
	private static void tick(MinecraftServer server) {
		int now = server.getTickCount();
		Map<UUID, Double> penalties = new HashMap<>();

		Iterator<Bleed> it = BLEEDS.values().iterator();
		while (it.hasNext()) {
			Bleed b = it.next();
			LivingEntity target = b.target;
			if (target.isRemoved() || !target.isAlive() || b.pulsesLeft <= 0) {
				it.remove();
				continue;
			}
			if (now >= b.nextPulseTick) {
				ServerLevel level = (ServerLevel) target.level();
				// Magic damage: ignores armor and gives no knockback. Reset the hit cooldown so the pulse always lands.
				resetHurtCooldown(target);
				target.hurtServer(level, level.damageSources().magic(), totalHearts(b.level) * 2f / PULSES);
				blood(level, target, 8);
				b.pulsesLeft--;
				b.nextPulseTick += PULSE_INTERVAL_TICKS;
				if (!target.isAlive() || b.pulsesLeft <= 0) {
					it.remove();
					continue;
				}
			}
			penalties.merge(b.attacker, penalty(b.level), Math::max);
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			double desired = -penalties.getOrDefault(player.getUUID(), 0.0);
			AttributeInstance attr = player.getAttribute(Attributes.ATTACK_DAMAGE);
			if (attr == null) {
				continue;
			}
			AttributeModifier current = attr.getModifier(PENALTY_ID);
			double currentValue = current == null ? 0 : current.amount();
			if (currentValue != desired) {
				attr.removeModifier(PENALTY_ID);
				if (desired < 0) {
					attr.addTransientModifier(new AttributeModifier(
							PENALTY_ID, desired, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
				}
			}
		}
	}

	// invulnerableTime is private in Entity on this Minecraft version, so it is reached through reflection.
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
				// If this fails the bleed still works, it just can't skip the hit cooldown.
			}
		}
	}

	private static int getLevel(ServerLevel server, ItemStack stack) {
		Optional<? extends Holder<Enchantment>> holder =
				server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(BLEED);
		if (holder.isEmpty()) {
			return 0;
		}
		return EnchantmentHelper.getItemEnchantmentLevel(holder.get(), stack);
	}
}
