package dev.customdiscs.server;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.customdiscs.config.ServerConfig;
import dev.customdiscs.net.ModPayloads;

public final class ServerHandlers {
	private ServerHandlers() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(ModPayloads.UploadBegin.TYPE, (payload, context) -> UploadManager.handleBegin(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ModPayloads.UploadChunk.TYPE, (payload, context) -> UploadManager.handleChunk(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ModPayloads.UploadCancel.TYPE, (payload, context) -> UploadManager.handleCancel(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ModPayloads.BurnDisc.TYPE, (payload, context) -> UploadManager.handleBurn(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ModPayloads.RequestTrack.TYPE, (payload, context) -> DownloadManager.handleRequest(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(ModPayloads.TrackChunkAck.TYPE, (payload, context) -> DownloadManager.handleAck(context.player(), payload));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerConfig config = ServerConfig.get();
			sender.sendPacket(new ModPayloads.ServerSettings(
					config.maxDurationSeconds,
					config.maxDiscDurationSeconds,
					config.maxTracksPerDisc,
					(int) Math.min(Integer.MAX_VALUE, config.maxFileBytes()),
					config.maxBitrateKbps,
					UploadManager.canBurn(handler.player)));
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			UploadManager.onDisconnect(handler.player.getUUID());
			DownloadManager.onDisconnect(handler.player.getUUID());
		});

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TrackStore.open(server);
			TrackStore.deleteUnused(ServerConfig.get().deleteUnusedTracksAfterDays);
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			UploadManager.clear();
			DownloadManager.clear();
			JukeboxTracker.clear();
			TrackStore.close();
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			UploadManager.tick();
			DownloadManager.tick(server);
			JukeboxTracker.tick(server);
		});
	}
}
