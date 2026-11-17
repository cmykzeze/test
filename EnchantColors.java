package com.example.lightningstrike;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** One easy-to-read colour per enchantment. Keep these in sync with the "color" in the enchantment JSON files. */
public final class EnchantColors {
	public static final int LIGHTNING_STRIKE = 0xFFE600; // bright yellow
	public static final int RAGE = 0xFF7A1A;             // orange
	public static final int FROZEN_HEART = 0x66CCFF;     // ice blue
	public static final int BLEED = 0xFF3355;            // crimson
	public static final int SHADOW_STEP = 0x9B4DFF;       // violet
	public static final int THUNDER_CLAP = 0x5CFFD9;      // electric aqua
	public static final int AFTERSHOCK = 0xD98A3D;        // amber
	public static final int GRAY = 0xAAAAAA;
	public static final int LOCKED = 0xFF5555;           // the only plain red, used for the lockout

	private EnchantColors() {
	}

	public static MutableComponent text(String text, int rgb) {
		return Component.literal(text).withStyle(Style.EMPTY.withColor(rgb));
	}
}
