package com.example.lightningstrike;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Shadow Step (levels I to III): right-click with the sword while looking at an enemy.
 *  - You vanish and reappear right behind the enemy you are aiming at, facing its back.
 *  - Your next normal melee hit within 4 seconds of the step is a "backstab" with bonus damage.
 *  - The bonus is spent by that hit, or lost when the 4 seconds run out or you put the sword away.
 * Targets: hostile mobs and other players, in a cone in front of you, in line of sight.
 */
public class ShadowStepAbility {
	public static final ResourceKey<Enchantment> SHADOW_STEP = ResourceKey.create(
			Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(LightningStrikeMod.MOD_ID, "shadow_step"));

	private static final Identifier MODIFIER_ID =
			Identifier.fromNamespaceAndPath(LightningStrikeMod.MOD_ID, "shadow_step_bonus");

	// ---- Tweak these numbers if you want ----
	/** Range in blocks: I = 8, II = 11, III = 14 */
	private static final double[] RANGE = {8.0, 11.0, 14.0};
	/** Cooldown in ticks: I = 12s, II = 10s, III = 8s */
	private static final int[] COOLDOWN_TICKS = {240, 200, 160};
	/** Backstab bonus damage in hearts: I = 1.0, II = 1.5, III = 2.0 */
	private static final double[] BACKSTAB_HEARTS = {1.0, 1.5, 2.0};
	private static final double AIM_CONE = 0.93;        // how close to the crosshair the target must be (~21 degrees)
	private static final double PLAYER_RANGE_CAP = 10.0; // other players can only be stepped to within this range
	private static final double BEHIND_DISTANCE = 1.6;   // how far behind the target you appear (falls back to 1.1)
	private static final int BACKSTAB_WINDOW_TICKS = 80; // 4 seconds to land the backstab
	private static final int MESSAGE_INTERVAL_TICKS = 20;

	private static final DustParticleOptions SHADOW = new DustParticleOptions(EnchantColors.SHADOW_STEP, 1.3f);

	private static class State {
		int cooldownEnd = 0;
		int bonusEnd = 0;          // server tick when the backstab window closes (0 = no bonus active)
		double bonusValue = 0;     // damage points of the active bonus
		int lastMessageTick = -1000;
		boolean readyPingPending = false;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	private static State state(UUID id) {
		return STATES.computeIfAbsent(id, k -> new State());
	}

	public static void register() {
		// Right-click with a sword (in the air). Runs on the server only.
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (hand == InteractionHand.MAIN_HAND && !world.isClientSide()
					&& player instanceof ServerPlayer serverPlayer) {
				activate(serverPlayer);
			}
			return InteractionResult.PASS;
		});

		// The backstab is spent by the next normal melee hit
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			if (!source.is(DamageTypes.PLAYER_ATTACK) || !(source.getEntity() instanceof ServerPlayer player)) {
				return;
			}
			State s = STATES.get(player.getUUID());
			if (s == null || s.bonusEnd == 0) {
				return;
			}
			if (!blocked) {
				ServerLevel level = (ServerLevel) player.level();
				level.sendParticles(ParticleTypes.CRIT, entity.getX(), entity.getY() + entity.getBbHeight() * 0.6,
						entity.getZ(), 14, 0.25, 0.3, 0.25, 0.1);
				level.playSound(null, entity.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT,
						SoundSource.PLAYERS, 0.8f, 0.7f);
				endBonus(player, s);
			}
		});

		// Dying clears everything
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				State s = state(player.getUUID());
				s.cooldownEnd = 0;
				s.readyPingPending = false;
				endBonus(player, s);
			}
		});
	}

	// ---------------------------------------------------------------- casting

	private static void activate(ServerPlayer player) {
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

		double range = pick(RANGE, level);
		LivingEntity target = findTarget(server, player, range);
		if (target == null) {
			message(player, s, now, EnchantColors.text(" no target in sight", EnchantColors.GRAY));
			return;
		}
		Vec3 destination = findSpotBehind(server, player, target);
		if (destination == null) {
			message(player, s, now, EnchantColors.text(" no room behind the target", EnchantColors.GRAY));
			return;
		}

		// Vanish...
		poof(server, player.position().add(0, player.getBbHeight() * 0.5, 0));
		server.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.7f, 1.4f);

		// ...and reappear behind the target, looking at its back
		double dx = target.getX() - destination.x;
		double dz = target.getZ() - destination.z;
		float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
		player.connection.teleport(destination.x, destination.y, destination.z, yaw, 0f);
		poof(server, destination.add(0, player.getBbHeight() * 0.5, 0));
		server.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.7f, 0.8f);

		s.cooldownEnd = now + (int) pick(COOLDOWN_TICKS, level);
		s.readyPingPending = true;
		s.bonusEnd = now + BACKSTAB_WINDOW_TICKS;
		s.bonusValue = pick(BACKSTAB_HEARTS, level) * 2;
		applyBonus(player, s.bonusValue);
		player.sendSystemMessage(name().append(EnchantColors.text(" backstab ready", EnchantColors.GRAY)), true);
	}

	/** The enemy closest to your crosshair direction (nearest wins ties), in line of sight. */
	private static LivingEntity findTarget(ServerLevel server, ServerPlayer player, double range) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle().normalize();
		AABB box = player.getBoundingBox().inflate(range);

		List<LivingEntity> candidates = server.getEntitiesOfClass(LivingEntity.class, box, t -> {
			if (t == player || !t.isAlive() || t.isSpectator() || !(t instanceof Enemy || t instanceof Player)) {
				return false;
			}
			double max = t instanceof Player ? Math.min(range, PLAYER_RANGE_CAP) : range;
			if (t.distanceTo(player) > max) {
				return false;
			}
			Vec3 center = t.position().add(0, t.getBbHeight() * 0.5, 0);
			if (center.subtract(eye).normalize().dot(look) < AIM_CONE) {
				return false;
			}
			var los = server.clip(new ClipContext(eye, center,
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			return los.getType() == HitResult.Type.MISS;
		});
		return candidates.stream().min(Comparator.comparingDouble(t -> t.distanceToSqr(player))).orElse(null);
	}

	/** A free spot directly behind the target (opposite to where it is looking), or null if there is none. */
	private static Vec3 findSpotBehind(ServerLevel server, ServerPlayer player, LivingEntity target) {
		Vec3 facing = new Vec3(target.getLookAngle().x, 0, target.getLookAngle().z);
		if (facing.lengthSqr() < 1.0E-4) {
			// looking straight up or down: use the line from the player to the target instead
			facing = new Vec3(target.getX() - player.getX(), 0, target.getZ() - player.getZ());
		}
		facing = facing.normalize();

		for (double dist : new double[] {BEHIND_DISTANCE, 1.1}) {
			Vec3 spot = new Vec3(target.getX() - facing.x * dist, target.getY(), target.getZ() - facing.z * dist);
			AABB moved = player.getBoundingBox().move(spot.subtract(player.position()));
			if (server.noCollision(player, moved)) {
				return spot;
			}
		}
		return null;
	}

	private static void poof(ServerLevel server, Vec3 at) {
		server.sendParticles(SHADOW, at.x, at.y, at.z, 25, 0.3, 0.5, 0.3, 0.0);
		server.sendParticles(ParticleTypes.PORTAL, at.x, at.y, at.z, 30, 0.3, 0.5, 0.3, 0.6);
	}

	// ---------------------------------------------------------------- bonus damage + cooldown upkeep

	private static void applyBonus(ServerPlayer player, double value) {
		AttributeInstance attr = player.getAttribute(Attributes.ATTACK_DAMAGE);
		if (attr == null) {
			return;
		}
		attr.removeModifier(MODIFIER_ID);
		if (value > 0) {
			attr.addTransientModifier(new AttributeModifier(MODIFIER_ID, value, AttributeModifier.Operation.ADD_VALUE));
		}
	}

	private static void endBonus(ServerPlayer player, State s) {
		s.bonusEnd = 0;
		s.bonusValue = 0;
		applyBonus(player, 0);
	}

	/** Runs every server tick: expires the backstab window and pings when the step is ready again. */
	public static void tick(MinecraftServer server) {
		int now = server.getTickCount();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			State s = STATES.get(player.getUUID());
			if (s == null) {
				continue;
			}
			ServerLevel level = (ServerLevel) player.level();
			ItemStack stack = player.getMainHandItem();
			boolean holding = stack.is(ItemTags.SWORDS) && getLevel(level, stack) > 0;

			if (s.bonusEnd != 0 && (now >= s.bonusEnd || !holding)) {
				endBonus(player, s);
			}
			if (s.readyPingPending && now >= s.cooldownEnd) {
				s.readyPingPending = false;
				if (holding) {
					player.sendSystemMessage(name().append(EnchantColors.text(" READY", EnchantColors.SHADOW_STEP)), true);
					level.playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP,
							SoundSource.PLAYERS, 0.4f, 0.8f);
				}
			}
		}
	}

	// ---------------------------------------------------------------- helpers

	private static net.minecraft.network.chat.MutableComponent name() {
		return EnchantColors.text("Shadow Step", EnchantColors.SHADOW_STEP);
	}

	private static void message(ServerPlayer player, State s, int now, net.minecraft.network.chat.Component extra) {
		if (now - s.lastMessageTick < MESSAGE_INTERVAL_TICKS) {
			return;
		}
		s.lastMessageTick = now;
		player.sendSystemMessage(name().append(extra), true);
	}

	private static void showCooldown(ServerPlayer player, State s, int now) {
		message(player, s, now, EnchantColors.text(
				String.format(Locale.ROOT, " %.1fs", (s.cooldownEnd - now) / 20.0), EnchantColors.GRAY));
	}

	private static double pick(double[] table, int level) {
		return table[Math.max(1, Math.min(table.length, level)) - 1];
	}

	private static double pick(int[] table, int level) {
		return table[Math.max(1, Math.min(table.length, level)) - 1];
	}

	private static int getLevel(ServerLevel server, ItemStack stack) {
		Optional<? extends Holder<Enchantment>> holder =
				server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(SHADOW_STEP);
		if (holder.isEmpty()) {
			return 0;
		}
		return EnchantmentHelper.getItemEnchantmentLevel(holder.get(), stack);
	}
}
