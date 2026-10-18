package dev.customdiscs.client;

import net.minecraft.client.gui.screens.MenuScreens;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import dev.customdiscs.ModRegistry;
import dev.customdiscs.client.net.TrackCache;
import dev.customdiscs.client.screen.DiscBurnerScreen;
import dev.customdiscs.client.screen.SettingsScreen;
import dev.customdiscs.client.sound.JukeboxSounds;
import dev.customdiscs.net.ModPayloads;

public class CustomDiscsClient implements ClientModInitializer {
	/** Set by the /customdiscs command; the screen opens on the next tick, after the chat screen has closed. */
	private static boolean openSettings;

	@Override
	public void onInitializeClient() {
		ClientConfig.load();
		MenuScreens.register(ModRegistry.DISC_BURNER_MENU, DiscBurnerScreen::new);

		ClientPlayNetworking.registerGlobalReceiver(ModPayloads.ServerSettings.TYPE, (payload, context) -> ServerLimits.set(payload));
		ClientPlayNetworking.registerGlobalReceiver(ModPayloads.UploadStatus.TYPE, (payload, context) -> BurnController.handleStatus(payload));
		ClientPlayNetworking.registerGlobalReceiver(ModPayloads.BurnResult.TYPE, (payload, context) -> BurnController.handleBurnResult(payload));
		ClientPlayNetworking.registerGlobalReceiver(ModPayloads.TrackChunk.TYPE, (payload, context) -> TrackCache.handleChunk(payload));
		ClientPlayNetworking.registerGlobalReceiver(ModPayloads.TrackUnavailable.TYPE, (payload, context) -> TrackCache.handleUnavailable(payload));
		ClientPlayNetworking.registerGlobalReceiver(ModPayloads.PlayCustomDisc.TYPE, (payload, context) -> JukeboxSounds.handlePlay(payload));
		ClientPlayNetworking.registerGlobalReceiver(ModPayloads.StopCustomDisc.TYPE, (payload, context) -> JukeboxSounds.handleStop(payload));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			JukeboxSounds.clear();
			TrackCache.clear();
			BurnController.reset();
			ServerLimits.reset();
		}));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
				ClientCommands.literal("customdiscs").executes(context -> {
					openSettings = true;
					return 1;
				})));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			TrackCache.tick();
			JukeboxSounds.tick(client);

			if (openSettings) {
				openSettings = false;
				client.gui.setScreen(new SettingsScreen(null));
			}
		});
	}
}
