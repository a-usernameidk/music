package dev.customdiscs.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import dev.customdiscs.CustomDiscs;
import dev.customdiscs.DiscInfo;
import dev.customdiscs.ModRegistry;
import dev.customdiscs.block.DiscBurnerBlockEntity;
import dev.customdiscs.config.ServerConfig;
import dev.customdiscs.menu.DiscBurnerMenu;
import dev.customdiscs.net.ModPayloads;
import dev.customdiscs.net.ModPayloads.UploadStatus;
import dev.customdiscs.util.Hashing;
import dev.customdiscs.util.OggInfo;

/**
 * Receives tracks from the Disc Burner screen one file at a time, validates and stores them, then burns the
 * disc when the client sends {@link ModPayloads.BurnDisc} with the full track list.
 */
public final class UploadManager {
	private static final long TIMEOUT_MS = 60_000;

	private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
	private static final Map<UUID, Long> LAST_BURN = new HashMap<>();
	/** Track lengths measured from stored files, so burning an album does not re-read every file. */
	private static final Map<String, Integer> DURATIONS = new HashMap<>();

	private UploadManager() {
	}

	private static final class Upload {
		final int id;
		final String hash;
		final byte[] data;
		int received;
		int nextSeq;
		long lastActivity = System.currentTimeMillis();

		Upload(ModPayloads.UploadBegin request) {
			this.id = request.uploadId();
			this.hash = request.hash();
			this.data = new byte[request.size()];
		}
	}

	public static boolean canBurn(ServerPlayer player) {
		if (ServerConfig.get().opsOnly) {
			return player.checkPermission(CustomDiscs.id("burn"), PermissionLevel.GAMEMASTERS);
		}

		return player.checkPermission(CustomDiscs.id("burn"), true);
	}

	private static void status(ServerPlayer player, int uploadId, int state, int received, Component message) {
		ServerPlayNetworking.send(player, new UploadStatus(uploadId, state, received, message));
	}

	private static void uploadError(ServerPlayer player, int uploadId, String key, Object... args) {
		status(player, uploadId, UploadStatus.ERROR, 0, Component.translatable(key, args));
	}

	private static void burnError(ServerPlayer player, String key, Object... args) {
		ServerPlayNetworking.send(player, new ModPayloads.BurnResult(false, Component.translatable(key, args)));
	}

	/** Removes control characters and formatting codes, trims, and limits the length. */
	static String cleanText(String text) {
		StringBuilder out = new StringBuilder();
		text.codePoints()
				.filter(c -> c >= 0x20 && c != 0x7F && c != 0xA7)
				.forEach(out::appendCodePoint);
		String cleaned = out.toString().strip();
		return cleaned.length() > DiscInfo.MAX_TEXT_LENGTH ? cleaned.substring(0, DiscInfo.MAX_TEXT_LENGTH) : cleaned;
	}

	/** The open Disc Burner menu at {@code pos}, or null if the player closed it or walked away. */
	private static @Nullable DiscBurnerMenu openBurner(ServerPlayer player, BlockPos pos) {
		if (player.containerMenu instanceof DiscBurnerMenu menu && menu.getPos().equals(pos) && menu.stillValid(player)) {
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

	/** Checks an Ogg file and returns its length in ticks, or an error translation key. */
	private static Object measure(byte[] data) {
		OggInfo info;

		try {
			info = OggInfo.parse(data);
		} catch (OggInfo.InvalidOggException e) {
			return "message.custom_discs.error.corrupt";
		}

		if (info.channels() != 1) {
			// Minecraft only places mono sounds in the world; stereo would be heard at full volume everywhere.
			return "message.custom_discs.error.not_mono";
		}

		if (info.durationSeconds() > ServerConfig.get().maxDurationSeconds + 1) {
			return "message.custom_discs.error.too_long";
		}

		return info.durationTicks();
	}

	private static @Nullable Integer storedDuration(String hash) {
		Integer cached = DURATIONS.get(hash);

		if (cached != null) {
			return cached;
		}

		byte[] data = TrackStore.read(hash);

		if (data == null || !(measure(data) instanceof Integer ticks)) {
			return null;
		}

		DURATIONS.put(hash, ticks);
		return ticks;
	}

	public static void handleBegin(ServerPlayer player, ModPayloads.UploadBegin request) {
		ServerConfig config = ServerConfig.get();
		int id = request.uploadId();
		UPLOADS.remove(player.getUUID());

		if (!canBurn(player)) {
			uploadError(player, id, "message.custom_discs.error.no_permission");
			return;
		}

		if (openBurner(player, request.pos()) == null) {
			uploadError(player, id, "message.custom_discs.error.burner_closed");
			return;
		}

		if (!Hashing.isValidHash(request.hash())) {
			uploadError(player, id, "message.custom_discs.error.corrupt");
			return;
		}

		if (request.size() <= 0 || request.size() > config.maxFileBytes()) {
			uploadError(player, id, "message.custom_discs.error.too_large", config.maxFileSizeMb);
			return;
		}

		// Content addressed: if anyone already uploaded this exact file, there is nothing to send.
		if (TrackStore.has(request.hash()) && storedDuration(request.hash()) != null) {
			status(player, id, UploadStatus.STORED, request.size(), Component.empty());
			return;
		}

		UPLOADS.put(player.getUUID(), new Upload(request));
		status(player, id, UploadStatus.READY, 0, Component.empty());
	}

	public static void handleChunk(ServerPlayer player, ModPayloads.UploadChunk chunk) {
		Upload upload = UPLOADS.get(player.getUUID());

		if (upload == null || upload.id != chunk.uploadId()) {
			return;
		}

		if (chunk.seq() != upload.nextSeq || upload.received + chunk.data().length > upload.data.length) {
			UPLOADS.remove(player.getUUID());
			uploadError(player, upload.id, "message.custom_discs.error.corrupt");
			return;
		}

		System.arraycopy(chunk.data(), 0, upload.data, upload.received, chunk.data().length);
		upload.received += chunk.data().length;
		upload.nextSeq++;
		upload.lastActivity = System.currentTimeMillis();

		if (upload.received < upload.data.length) {
			status(player, upload.id, UploadStatus.PROGRESS, upload.received, Component.empty());
			return;
		}

		UPLOADS.remove(player.getUUID());

		if (!Hashing.sha256(upload.data).equals(upload.hash)) {
			uploadError(player, upload.id, "message.custom_discs.error.corrupt");
			return;
		}

		Object result = measure(upload.data);

		if (result instanceof String errorKey) {
			uploadError(player, upload.id, errorKey, DiscInfo.formatDuration(ServerConfig.get().maxDurationSeconds * 20));
			return;
		}

		if (!TrackStore.write(upload.hash, upload.data)) {
			uploadError(player, upload.id, "message.custom_discs.error.server_storage");
			return;
		}

		DURATIONS.put(upload.hash, (Integer) result);
		status(player, upload.id, UploadStatus.STORED, upload.received, Component.empty());
	}

	public static void handleCancel(ServerPlayer player, ModPayloads.UploadCancel cancel) {
		Upload upload = UPLOADS.get(player.getUUID());

		if (upload != null && upload.id == cancel.uploadId()) {
			UPLOADS.remove(player.getUUID());
		}
	}

	public static void handleBurn(ServerPlayer player, ModPayloads.BurnDisc request) {
		ServerConfig config = ServerConfig.get();

		if (!canBurn(player)) {
			burnError(player, "message.custom_discs.error.no_permission");
			return;
		}

		DiscBurnerMenu menu = openBurner(player, request.pos());

		if (menu == null) {
			burnError(player, "message.custom_discs.error.burner_closed");
			return;
		}

		String slotError = checkSlots(menu);

		if (slotError != null) {
			burnError(player, slotError);
			return;
		}

		String title = cleanText(request.title());

		if (title.isEmpty()) {
			burnError(player, "message.custom_discs.error.no_title");
			return;
		}

		if (request.tracks().isEmpty() || request.tracks().size() > config.maxTracksPerDisc) {
			burnError(player, "message.custom_discs.error.too_many_tracks", config.maxTracksPerDisc);
			return;
		}

		Long last = LAST_BURN.get(player.getUUID());
		long waitMs = last == null ? 0 : last + config.burnCooldownSeconds * 1000L - System.currentTimeMillis();

		if (waitMs > 0) {
			burnError(player, "message.custom_discs.error.cooldown", (waitMs + 999) / 1000);
			return;
		}

		List<DiscInfo.Track> tracks = new ArrayList<>();
		long totalTicks = 0;

		for (ModPayloads.TrackRequest requested : request.tracks()) {
			Integer duration = Hashing.isValidHash(requested.hash()) ? storedDuration(requested.hash()) : null;

			if (duration == null) {
				burnError(player, "message.custom_discs.error.missing_track");
				return;
			}

			String trackTitle = cleanText(requested.title());

			if (request.tracks().size() == 1) {
				trackTitle = title;
			} else if (trackTitle.isEmpty()) {
				trackTitle = "Track " + (tracks.size() + 1);
			}

			tracks.add(new DiscInfo.Track(requested.hash(), trackTitle, duration));
			totalTicks += duration;
			TrackStore.touch(requested.hash());
		}

		if (totalTicks > (config.maxDiscDurationSeconds + 1) * 20L) {
			burnError(player, "message.custom_discs.error.disc_too_long", DiscInfo.formatDuration(config.maxDiscDurationSeconds * 20));
			return;
		}

		DiscInfo disc = new DiscInfo(title, cleanText(request.artist()), request.explicit(), request.shuffle(), tracks);
		ItemStack item = new ItemStack(ModRegistry.CUSTOM_MUSIC_DISC);
		item.set(ModRegistry.DISC, disc);

		Container container = menu.getContainer();
		container.removeItem(DiscBurnerBlockEntity.SLOT_INPUT, 1);
		container.setItem(DiscBurnerBlockEntity.SLOT_OUTPUT, item);
		container.setChanged();
		menu.broadcastChanges();

		LAST_BURN.put(player.getUUID(), System.currentTimeMillis());
		CustomDiscs.LOGGER.info("{} burned \"{}\" ({} tracks)", player.getName().getString(), disc.displayName().getString(), tracks.size());
		ServerPlayNetworking.send(player, new ModPayloads.BurnResult(true, disc.displayName()));
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
		DURATIONS.clear();
	}
}
