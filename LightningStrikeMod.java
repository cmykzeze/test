package com.example.lightningstrike;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class LightningStrikeMod implements ModInitializer {
	public static final String MOD_ID = "lightningstrike";

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(LightningStrikePayload.TYPE, LightningStrikePayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(LightningStrikePayload.TYPE,
				(payload, context) -> LightningStrikeAbility.activate(context.player()));

		ServerTickEvents.END_SERVER_TICK.register(RageAbility::tick);
		ServerTickEvents.END_SERVER_TICK.register(LightningStrikeAbility::tick);
		ServerTickEvents.END_SERVER_TICK.register(ShadowStepAbility::tick);
		ServerTickEvents.END_SERVER_TICK.register(ThunderClapAbility::tick);
		ServerTickEvents.END_SERVER_TICK.register(AftershockAbility::tick);
		LightningStrikeAbility.register();
		RageLoot.register();
		FrozenHeartAbility.register();
		BleedAbility.register();
		ShadowStepAbility.register();
		ThunderClapAbility.register();
		AftershockAbility.register();
	}
}
