package dev.customdiscs.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.customdiscs.CustomDiscs;
import dev.customdiscs.ModRegistry;
import dev.customdiscs.TrackInfo;
import dev.customdiscs.block.DiscBurnerBlockEntity;
import dev.customdiscs.config.ServerConfig;
import dev.customdiscs.menu.DiscBurnerMenu;
import dev.customdiscs.net.ModPayloads;
import dev.customdiscs.net.ModPayloads.UploadStatus;
import dev.customdiscs.util.Hashing;
import dev.customdiscs.util.OggInfo;

/** Receives chunked uploads from the Disc Burner screen, validates them and burns the disc. */
public final class UploadManager {
	private static final long TIMEOUT_MS = 60_000;

	private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
	private static final Map<UUID, Long> LAST_BURN = new HashMap<>();

	private UploadManager() {
	}

	private static final class Upload {
		final int id;
		final ModPayloads.UploadBegin request;
		final byte[] data;
		int received;
		int nextSeq;
		long lastActivity = System.currentTimeMillis();

		Upload(ModPayloads.UploadBegin request) {
			this.id = request.uploadId();
			this.request = request;
			this.data = new byte[request.size()];
		}
	}

	public static boolean canBurn(ServerPlayer player) {
		if (ServerConfig.get().opsOnly) {
			return player.checkPermission(CustomDiscs.id("burn"), PermissionLevel.GAMEMASTERS);
		}

		return player.checkPermission(CustomDiscs.id("burn"), true);
	}

	private static void send(ServerPlayer player, int uploadId, int state, int received, Component message) {
		ServerPlayNetworking.send(player, new UploadStatus(uploadId, state, received, message));
	}

	private static void error(ServerPlayer player, int uploadId, String key, Object... args) {
		send(player, uploadId, UploadStatus.ERROR, 0, Component.translatable(key, args));
	}

	/** Removes control characters and formatting codes, trims, and limits the length. */
	static String cleanText(String text) {
		StringBuilder out = new StringBuilder();
		text.codePoints()
				.filter(c -> c >= 0x20 && c != 0x7F && c != 0xA7)
				.forEach(out::appendCodePoint);
		String cleaned = out.toString().strip();
		return cleaned.length() > TrackInfo.MAX_TEXT_LENGTH ? cleaned.substring(0, TrackInfo.MAX_TEXT_LENGTH) : cleaned;
	}

	/** The open Disc Burner menu for this upload, or null if the player closed it or walked away. */
	private static @Nullable DiscBurnerMenu openBurner(ServerPlayer player, ModPayloads.UploadBegin request) {
		if (player.containerMenu instanceof DiscBurnerMenu menu && menu.getPos().equals(request.pos()) && menu.stillValid(player)) {
			return menu;
		}

		return null;
	}

	private static @Nullable String checkSlots(DiscBurnerMenu menu) {
		Container container = menu.getContainer();

		if (!container.getItem(DiscBurnerBlockEntity.SLOT_INPUT).is(ModRegistry.BLANK_MUSIC_DISC)) {
			return "message.custom_discs.error.no_blank_disc";
		}

		if (!container.getItem(DiscBurnerBlockEntity.SLOT_OUTPUT).isEmpty()) {
			return "message.custom_discs.error.output_full";
		}

		return null;
	}

	public static void handleBegin(ServerPlayer player, ModPayloads.UploadBegin request) {
		ServerConfig config = ServerConfig.get();
		int id = request.uploadId();
		UPLOADS.remove(player.getUUID());

		if (!canBurn(player)) {
			error(player, id, "message.custom_discs.error.no_permission");
			return;
		}

		DiscBurnerMenu menu = openBurner(player, request);

		if (menu == null) {
			error(player, id, "message.custom_discs.error.burner_closed");
			return;
		}

		String slotError = checkSlots(menu);

		if (slotError != null) {
			error(player, id, slotError);
			return;
		}

		if (cleanText(request.title()).isEmpty()) {
			error(player, id, "message.custom_discs.error.no_title");
			return;
		}

		if (!Hashing.isValidHash(request.hash())) {
			error(player, id, "message.custom_discs.error.corrupt", "bad hash");
			return;
		}

		if (request.size() <= 0 || request.size() > config.maxFileBytes()) {
			error(player, id, "message.custom_discs.error.too_large", config.maxFileSizeMb);
			return;
		}

		Long last = LAST_BURN.get(player.getUUID());
		long waitMs = last == null ? 0 : last + config.burnCooldownSeconds * 1000L - System.currentTimeMillis();

		if (waitMs > 0) {
			error(player, id, "message.custom_discs.error.cooldown", (waitMs + 999) / 1000);
			return;
		}

		// Content addressed: if anyone already uploaded this exact file, burn straight away.
		byte[] existing = TrackStore.read(request.hash());

		if (existing != null) {
			finish(player, request, existing);
			return;
		}

		UPLOADS.put(player.getUUID(), new Upload(request));
		send(player, id, UploadStatus.READY, 0, Component.empty());
	}

	public static void handleChunk(ServerPlayer player, ModPayloads.UploadChunk chunk) {
		Upload upload = UPLOADS.get(player.getUUID());

		if (upload == null || upload.id != chunk.uploadId()) {
			return;
		}

		if (chunk.seq() != upload.nextSeq || upload.received + chunk.data().length > upload.data.length) {
			UPLOADS.remove(player.getUUID());
			error(player, upload.id, "message.custom_discs.error.corrupt", "unexpected chunk");
			return;
		}

		System.arraycopy(chunk.data(), 0, upload.data, upload.received, chunk.data().length);
		upload.received += chunk.data().length;
		upload.nextSeq++;
		upload.lastActivity = System.currentTimeMillis();

		if (upload.received < upload.data.length) {
			send(player, upload.id, UploadStatus.PROGRESS, upload.received, Component.empty());
			return;
		}

		UPLOADS.remove(player.getUUID());

		if (!Hashing.sha256(upload.data).equals(upload.request.hash())) {
			error(player, upload.id, "message.custom_discs.error.corrupt", "checksum mismatch");
			return;
		}

		finish(player, upload.request, upload.data);
	}

	public static void handleCancel(ServerPlayer player, ModPayloads.UploadCancel cancel) {
		Upload upload = UPLOADS.get(player.getUUID());

		if (upload != null && upload.id == cancel.uploadId()) {
			UPLOADS.remove(player.getUUID());
		}
	}

	private static void finish(ServerPlayer player, ModPayloads.UploadBegin request, byte[] data) {
		ServerConfig config = ServerConfig.get();
		int id = request.uploadId();
		OggInfo info;

		try {
			info = OggInfo.parse(data);
		} catch (OggInfo.InvalidOggException e) {
			error(player, id, "message.custom_discs.error.corrupt", e.getMessage());
			return;
		}

		if (info.channels() != 1) {
			// Minecraft only places mono sounds in the world; stereo would be heard at full volume everywhere.
			error(player, id, "message.custom_discs.error.not_mono");
			return;
		}

		if (info.durationSeconds() > config.maxDurationSeconds + 1) {
			error(player, id, "message.custom_discs.error.too_long", TrackInfo.formatDuration(config.maxDurationSeconds * 20));
			return;
		}

		if (!TrackStore.has(request.hash()) && !TrackStore.write(request.hash(), data)) {
			error(player, id, "message.custom_discs.error.server_storage");
			return;
		}

		// The player might have moved items around during a long upload, so check again.
		DiscBurnerMenu menu = openBurner(player, request);
		String slotError = menu == null ? "message.custom_discs.error.burner_closed" : checkSlots(menu);

		if (slotError != null) {
			error(player, id, slotError);
			return;
		}

		TrackInfo track = new TrackInfo(request.hash(), cleanText(request.title()), cleanText(request.artist()), info.durationTicks());
		ItemStack disc = new ItemStack(ModRegistry.CUSTOM_MUSIC_DISC);
		disc.set(ModRegistry.TRACK, track);

		Container container = menu.getContainer();
		container.removeItem(DiscBurnerBlockEntity.SLOT_INPUT, 1);
		container.setItem(DiscBurnerBlockEntity.SLOT_OUTPUT, disc);
		container.setChanged();
		menu.broadcastChanges();

		LAST_BURN.put(player.getUUID(), System.currentTimeMillis());
		CustomDiscs.LOGGER.info("{} burned \"{}\" ({}, {} bytes)", player.getName().getString(), track.displayName().getString(), request.hash(), data.length);
		send(player, id, UploadStatus.DONE, data.length, track.displayName());
	}

	public static void tick() {
		long now = System.currentTimeMillis();
		UPLOADS.values().removeIf(upload -> now - upload.lastActivity > TIMEOUT_MS);
	}

	public static void onDisconnect(UUID player) {
		UPLOADS.remove(player);
	}

	public static void clear() {
		UPLOADS.clear();
		LAST_BURN.clear();
	}
}
