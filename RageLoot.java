package com.example.lightningstrike;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetEnchantmentsFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;

/**
 * Adds Rage books to chests:
 *  - Ancient City chests: level VII only (12% chance per chest)
 *  - a few common chests: levels I to VI (4% chance per chest)
 */
public class RageLoot {
	private static final float ANCIENT_CITY_CHANCE = 0.12f;
	private static final float COMMON_CHEST_CHANCE = 0.04f;

	public static void register() {
		LootTableEvents.MODIFY.register((key, tableBuilder, source, registries) -> {
			boolean ancientCity = key.equals(BuiltInLootTables.ANCIENT_CITY);
			boolean common = key.equals(BuiltInLootTables.SIMPLE_DUNGEON)
					|| key.equals(BuiltInLootTables.ABANDONED_MINESHAFT)
					|| key.equals(BuiltInLootTables.DESERT_PYRAMID)
					|| key.equals(BuiltInLootTables.JUNGLE_TEMPLE);
			if (!ancientCity && !common) {
				return;
			}

			Holder<Enchantment> rage = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(RageAbility.RAGE);
			LootPool.Builder pool = LootPool.lootPool().setRolls(ContextIntProviders.exactly(1));

			if (ancientCity) {
				pool.when(LootItemRandomChanceCondition.randomChance(ANCIENT_CITY_CHANCE));
				pool.add(book(rage, 7, 1));
			} else {
				pool.when(LootItemRandomChanceCondition.randomChance(COMMON_CHEST_CHANCE));
				pool.add(book(rage, 1, 30));
				pool.add(book(rage, 2, 25));
				pool.add(book(rage, 3, 20));
				pool.add(book(rage, 4, 12));
				pool.add(book(rage, 5, 8));
				pool.add(book(rage, 6, 5));
			}
			tableBuilder.withPool(pool);
		});
	}

	private static LootItem.Builder<?> book(Holder<Enchantment> rage, int level, int weight) {
		return LootItem.lootTableItem(Items.ENCHANTED_BOOK)
				.setWeight(weight)
				.apply(new SetEnchantmentsFunction.Builder().withEnchantment(rage, ContextIntProviders.exactly(level)));
	}
}
