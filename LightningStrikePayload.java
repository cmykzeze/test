package com.example.lightningstrike;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Empty message the client sends to the server when you left-click with a sword. */
public record LightningStrikePayload() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<LightningStrikePayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath(LightningStrikeMod.MOD_ID, "activate"));

	public static final StreamCodec<RegistryFriendlyByteBuf, LightningStrikePayload> CODEC =
			StreamCodec.unit(new LightningStrikePayload());

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
