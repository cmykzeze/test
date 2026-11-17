package com.example.lightningstrike;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;

/**
 * Aftershock (levels I to III), mace only: after a SMASH attack (a mace hit after falling more than 1.5 blocks)
 * the ground cracks and, one second later, a shockwave bursts out of the impact spot.
 *  - It hits every hostile mob and player in the radius (never you) for a share of the smash damage,
 *    and throws them up and away from the centre.
 *  - The bigger the fall, the bigger the smash damage, so a long drop gives a bigger blast.
 *  - Short cooldown so you can't chain it on every hit.
 * The shockwave counts as magic damage that comes from you, so it does NOT trigger Thunder Clap,
 * Bleed or any other on-hit enchantment.
 *
 * Fall height is tracked here (highest point since you last stood on something) instead of reading the
 * game's own fall counter, which isn't reachable on this Minecraft version.
 */
public class AftershockAbility {
	public static final ResourceKey<Enchantment> AFTERSHOCK = ResourceKey.create(
			Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(LightningStrikeMod.MOD_ID, "aftershock"));

	// ---- Tweak these numbers if you want ----
	/** Radius in blocks: I = 3, II = 4, III = 5 */
	private static final double[] RADIUS = {3.0, 4.0, 5.0};
	/** Share of the smash damage the shockwave deals: I = 30%, II = 40%, III = 50% */
	private static final float[] DAMAGE_SHARE = {0.30f, 0.40f, 0.50f};
	/** Knockback strength: I = 0.6, II = 0.8, III = 1.0 */
	private static final double[] KNOCKBACK = {0.6, 0.8, 1.0};
	/** Cooldown in ticks: I = 8s, II = 7s, III = 6s */
	private static final int[] COOLDOWN_TICKS = {160, 140, 120};
	private static final float MAX_DAMAGE = 24f;        // cap on one shockwave hit (12 hearts)
	private static final double SMASH_FALL = 1.5;       // same minimum fall as the vanilla smash attack
	private static final int DELAY_TICKS = 20;          // the shockwave comes 1 second after the smash
	private static final double VERTICAL_REACH = 2.5;   // how far above/below the impact point it still hits

	private static final DustParticleOptions CRACK = new DustParticleOptions(EnchantColors.AFTERSHOCK, 1.2f);

	private static class Pending {
		final ServerLevel level;
		final UUID owner;
		final double x, y, z;
		final int lvl;
		final float smashDamage;
		final int fireTick;

		Pending(ServerLevel level, UUID owner, double x, double y, double z, int lvl, float smashDamage, int fireTick) {
			this.level = level;
			this.owner = owner;
			this.x = x;
			this.y = y;
			this.z = z;
			this.lvl = lvl;
			this.smashDamage = smashDamage;
			this.fireTick = fireTick;
		}
	}

	private static final List<Pending> PENDING = new ArrayList<>();
	private static final Map<UUID, Double> PEAK_Y = new HashMap<>();      // highest point of the current fall
	private static final Map<UUID, Integer> COOLDOWN_END = new HashMap<>();

	public static void register() {
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamageTaken, damageTaken, blocked) -> {
			if (blocked || !source.is(DamageTypes.PLAYER_ATTACK)
					|| !(source.getEntity() instanceof ServerPlayer player) || entity == player) {
				return;
			}
			ItemStack stack = player.getMainHandItem();
			if (!stack.is(Items.MACE) || player.isFallFlying()) {
				return;
			}
			ServerLevel level = (ServerLevel) player.level();
			int lvl = getLevel(level, stack);
			if (lvl <= 0) {
				return;
			}
			// Was it a smash? (fell more than 1.5 blocks since last standing on something)
			double peak = PEAK_Y.getOrDefault(player.getUUID(), player.getY());
			if (peak - player.getY() <= SMASH_FALL) {
				return;
			}
			int now = level.getServer().getTickCount();
			if (now < COOLDOWN_END.getOrDefault(player.getUUID(), 0)) {
				return;
			}
			COOLDOWN_END.put(player.getUUID(), now + (int) pick(COOLDOWN_TICKS, lvl));

			PENDING.add(new Pending(level, player.getUUID(), entity.getX(), entity.getY(), entity.getZ(),
					lvl, damageTaken, now + DELAY_TICKS));
			player.sendSystemMessage(EnchantColors.text("Aftershock...", EnchantColors.AFTERSHOCK), true);
		});

		// Dying clears the cooldown
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (entity instanceof ServerPlayer player) {
				COOLDOWN_END.remove(player.getUUID());
				PEAK_Y.remove(player.getUUID());
			}
		});
	}

	/** Runs every server tick: tracks falls, draws the cracking ground and fires the shockwaves. */
	public static void tick(MinecraftServer server) {
		int now = server.getTickCount();

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			boolean resting = player.onGround() || player.isInWater() || player.isFallFlying()
					|| player.getAbilities().flying;
			double y = player.getY();
			PEAK_Y.merge(player.getUUID(), y, (old, cur) -> resting ? cur : Math.max(old, cur));
			if (resting) {
				PEAK_Y.put(player.getUUID(), y);
			}
		}

		Iterator<Pending> it = PENDING.iterator();
		while (it.hasNext()) {
			Pending p = it.next();
			if (p.level.getServer() != server) {
				it.remove();
				continue;
			}
			if (now >= p.fireTick) {
				it.remove();
				burst(server, p);
			} else if ((p.fireTick - now) % 4 == 0) {
				crack(p, (p.fireTick - now) / (double) DELAY_TICKS);
			}
		}
	}

	/** The warning: a ring of dust that shrinks onto the impact point while the ground "charges". */
	private static void crack(Pending p, double progressLeft) {
		double radius = pick(RADIUS, p.lvl) * (0.3 + 0.7 * progressLeft);
		for (int i = 0; i < 16; i++) {
			double a = i * (Math.PI * 2 / 16);
			p.level.sendParticles(CRACK, p.x + Math.cos(a) * radius, p.y + 0.1, p.z + Math.sin(a) * radius,
					1, 0, 0, 0, 0);
		}
	}

	private static void burst(MinecraftServer server, Pending p) {
		ServerLevel level = p.level;
		ServerPlayer owner = server.getPlayerList().getPlayer(p.owner);
		double radius = pick(RADIUS, p.lvl);
		float damage = Math.min(MAX_DAMAGE, p.smashDamage * (float) pick(DAMAGE_SHARE, p.lvl));
		double strength = pick(KNOCKBACK, p.lvl);

		level.sendParticles(ParticleTypes.EXPLOSION, p.x, p.y + 0.2, p.z, 3, radius * 0.3, 0.1, radius * 0.3, 0.0);
		for (int i = 0; i < 24; i++) {
			double a = i * (Math.PI * 2 / 24);
			level.sendParticles(CRACK, p.x + Math.cos(a) * radius, p.y + 0.2, p.z + Math.sin(a) * radius,
					3, 0.1, 0.3, 0.1, 0.05);
		}
		level.playSound(null, net.minecraft.core.BlockPos.containing(p.x, p.y, p.z),
				SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.0f, 0.5f);

		AABB box = new AABB(p.x - radius, p.y - VERTICAL_REACH, p.z - radius,
				p.x + radius, p.y + VERTICAL_REACH + 2, p.z + radius);
		for (LivingEntity t : level.getEntitiesOfClass(LivingEntity.class, box, e ->
				e.isAlive() && !e.isSpectator() && (e instanceof Enemy || e instanceof Player)
						&& (owner == null || e != owner))) {
			double dx = t.getX() - p.x;
			double dz = t.getZ() - p.z;
			if (dx * dx + dz * dz > radius * radius) {
				continue;
			}
			// Magic damage from the owner: not a melee hit, so no other on-hit enchantment reacts to it
			t.hurtServer(level, level.damageSources().indirectMagic(owner, owner), damage);
			if (!t.isAlive()) {
				continue;
			}
			// knockback() pushes away from the point it is given, so give it the centre-to-target direction
			t.knockback(strength, -dx, -dz);
			if (t instanceof ServerPlayer sp) {
				sp.connection.send(new ClientboundSetEntityMotionPacket(sp));
			}
		}
	}

	private static double pick(double[] table, int level) {
		return table[Math.max(1, Math.min(table.length, level)) - 1];
	}

	private static double pick(int[] table, int level) {
		return table[Math.max(1, Math.min(table.length, level)) - 1];
	}

	private static float pick(float[] table, int level) {
		return table[Math.max(1, Math.min(table.length, level)) - 1];
	}

	private static int getLevel(ServerLevel server, ItemStack stack) {
		Optional<? extends Holder<Enchantment>> holder =
				server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(AFTERSHOCK);
		if (holder.isEmpty()) {
			return 0;
		}
		return EnchantmentHelper.getItemEnchantmentLevel(holder.get(), stack);
	}
}
