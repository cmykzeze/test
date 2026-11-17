package com.example.lightningstrike;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * Rage (levels I to VII):
 *  - alone: bonus melee damage while holding the sword
 *  - with Lightning Strike: every enemy a Lightning Strike hits adds bonus damage on top of the flat bonus (up to a cap).
 *    The bonus lasts while you are in combat and is lost after 5 seconds without combat.
 *  Numbers per level are in the three small methods below (hearts are converted to damage points).
 */
public class RageAbility {
	public static final ResourceKey<Enchantment> RAGE = ResourceKey.create(
			Registries.ENCHANTMENT, Identifier.fromNamespaceAndPath(LightningStrikeMod.MOD_ID, "rage"));

	private static final Identifier MODIFIER_ID =
			Identifier.fromNamespaceAndPath(LightningStrikeMod.MOD_ID, "rage_bonus");

	// ---- Tweak these numbers if you want ----
	private static final int COMBAT_TIMEOUT_TICKS = 100; // 5 seconds

	// Index 0 = level I ... index 6 = level VII.
	/** Flat bonus, always active while holding the sword, in hearts: I = 0.40 ... V = 0.80, VII = 1.00 */
	private static final double[] ALONE = {0.40, 0.50, 0.60, 0.70, 0.80, 0.90, 1.00};
	/** Extra bonus per enemy hit by Lightning Strike, in hearts: I = 0.25 ... VII = 0.30 */
	private static final double[] PER_ENEMY = {0.25, 0.26, 0.27, 0.28, 0.29, 0.30, 0.30};
	/** Maximum total bonus (flat + combo), in hearts: I = 1.4 ... V = 2.0, VII = 2.2 */
	private static final double[] CAP = {1.4, 1.55, 1.7, 1.85, 2.0, 2.1, 2.2};

	private static double pick(double[] table, int level) {
		return table[Math.max(1, Math.min(table.length, level)) - 1];
	}

	private static double aloneHearts(int level) {
		return pick(ALONE, level);
	}

	private static double perEnemyHearts(int level) {
		return pick(PER_ENEMY, level);
	}

	private static double capHearts(int level) {
		return pick(CAP, level);
	}

	private static class State {
		double comboBonus = 0;
		int lastActiveTick = 0;
	}

	private static final Map<UUID, State> STATES = new HashMap<>();

	private static int getLevel(ServerLevel server, ItemStack stack, ResourceKey<Enchantment> key) {
		Optional<? extends Holder<Enchantment>> holder =
				server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(key);
		if (holder.isEmpty()) {
			return 0;
		}
		return EnchantmentHelper.getItemEnchantmentLevel(holder.get(), stack);
	}

	/** Called by Lightning Strike after a cast, with the number of enemies it hit. */
	public static void onLightningStrike(ServerLevel server, ServerPlayer player, ItemStack stack, int hits) {
		int rageLevel = getLevel(server, stack, RAGE);
		if (hits <= 0 || rageLevel <= 0) {
			return;
		}
		State s = STATES.computeIfAbsent(player.getUUID(), id -> new State());
		// The combo stacks on top of the flat bonus; flat + combo never goes above the cap
		double maxStacks = (capHearts(rageLevel) - aloneHearts(rageLevel)) * 2;
		s.comboBonus = Math.min(maxStacks, s.comboBonus + hits * perEnemyHearts(rageLevel) * 2);
		s.lastActiveTick = player.tickCount;
	}

	/** Tells the player (action bar, orange) whenever their Rage bonus changes (hearts of extra damage). */
	private static void announce(ServerPlayer player, int rageLevel, boolean comboMode, double desired) {
		String text;
		if (desired > 0 && comboMode) {
			text = String.format(Locale.ROOT, "Rage combo: +%.2f hearts damage (max +%.2f)",
					desired / 2, capHearts(rageLevel));
		} else if (desired > 0) {
			text = String.format(Locale.ROOT, "Rage: +%.2f hearts damage", desired / 2);
		} else {
			text = "Rage bonus gone";
		}
		// Sent only to this player, as an action bar line so fast combos don't flood the chat
		player.sendSystemMessage(EnchantColors.text(text, EnchantColors.RAGE), true);
	}

	/** Runs every server tick. */
	public static void tick(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ServerLevel level = (ServerLevel) player.level();
			State s = STATES.computeIfAbsent(player.getUUID(), id -> new State());

			// Lose the combo bonus after 5 seconds out of combat
			if (s.comboBonus > 0) {
				int lastCombat = Math.max(s.lastActiveTick,
						Math.max(player.getLastHurtMobTimestamp(), player.getLastHurtByMobTimestamp()));
				if (s.lastActiveTick > player.tickCount
						|| player.tickCount - lastCombat > COMBAT_TIMEOUT_TICKS) {
					s.comboBonus = 0;
				}
			}

			// How much bonus should apply right now
			double desired = 0;
			boolean comboMode = false;
			ItemStack stack = player.getMainHandItem();
			int rageLevel = stack.is(ItemTags.SWORDS) ? getLevel(level, stack, RAGE) : 0;
			if (rageLevel > 0) {
				if (getLevel(level, stack, LightningStrikeAbility.LIGHTNING_STRIKE) > 0) {
					comboMode = true;
					desired = Math.min(capHearts(rageLevel) * 2, aloneHearts(rageLevel) * 2 + s.comboBonus);
				} else {
					desired = aloneHearts(rageLevel) * 2;
				}
			}

			AttributeInstance attr = player.getAttribute(Attributes.ATTACK_DAMAGE);
			if (attr == null) {
				continue;
			}
			AttributeModifier current = attr.getModifier(MODIFIER_ID);
			double currentValue = current == null ? 0 : current.amount();
			if (currentValue != desired) {
				attr.removeModifier(MODIFIER_ID);
				if (desired > 0) {
					attr.addTransientModifier(new AttributeModifier(
							MODIFIER_ID, desired, AttributeModifier.Operation.ADD_VALUE));
				}
				announce(player, rageLevel, comboMode, desired);
			}
		}
	}
}
