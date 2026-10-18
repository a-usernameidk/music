package dev.customdiscs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

import net.fabricmc.api.ModInitializer;

import dev.customdiscs.config.ServerConfig;
import dev.customdiscs.net.ModPayloads;
import dev.customdiscs.server.ServerHandlers;

public class CustomDiscs implements ModInitializer {
	public static final String MOD_ID = "custom_discs";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerConfig.load();
		ModRegistry.register();
		ModPayloads.register();
		ServerHandlers.register();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
