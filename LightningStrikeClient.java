package com.example.lightningstrike;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.tags.ItemTags;

/** Detects a left click with a sword and tells the server. The server checks the enchantment and cooldown. */
public class LightningStrikeClient implements ClientModInitializer {
	private static boolean wasDown = false;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean down = client.options.keyAttack.isDown();
			if (down && !wasDown
					&& client.player != null
					&& client.player.getMainHandItem().is(ItemTags.SWORDS)) {
				ClientPlayNetworking.send(new LightningStrikePayload());
			}
			wasDown = down;
		});
	}
}
