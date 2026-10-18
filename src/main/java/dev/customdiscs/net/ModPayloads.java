package dev.customdiscs.net;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import dev.customdiscs.CustomDiscs;
import dev.customdiscs.DiscInfo;

/**
 * All custom packets. Audio moves in chunks of at most {@link #CHUNK_SIZE} bytes, well under vanilla's 32 KiB
 * limit for serverbound custom payloads, and each chunk is acknowledged so a slow relay is never flooded.
 */
public final class ModPayloads {
	public static final int CHUNK_SIZE = 30 * 1024;
	private static final int HASH_LENGTH = 64;
	private static final int TEXT_LENGTH = DiscInfo.MAX_TEXT_LENGTH * 4;

	private ModPayloads() {
	}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String name) {
		return new CustomPacketPayload.Type<>(CustomDiscs.id(name));
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(UploadBegin.TYPE, UploadBegin.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(UploadChunk.TYPE, UploadChunk.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(UploadCancel.TYPE, UploadCancel.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(BurnDisc.TYPE, BurnDisc.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RequestTrack.TYPE, RequestTrack.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TrackChunkAck.TYPE, TrackChunkAck.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(ServerSettings.TYPE, ServerSettings.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(UploadStatus.TYPE, UploadStatus.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BurnResult.TYPE, BurnResult.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TrackChunk.TYPE, TrackChunk.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TrackUnavailable.TYPE, TrackUnavailable.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PlayCustomDisc.TYPE, PlayCustomDisc.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StopCustomDisc.TYPE, StopCustomDisc.CODEC);
	}

	// ---- Client to server ----

	/**
	 * Starts uploading one track file for the Disc Burner at {@code pos}. The file follows in {@link UploadChunk}s;
	 * the server answers {@link UploadStatus#STORED} straight away if it already has a file with this hash.
	 */
	public record UploadBegin(int uploadId, BlockPos pos, String hash, int size) implements CustomPacketPayload {
		public static final Type<UploadBegin> TYPE = typeOf("upload_begin");
		public static final StreamCodec<RegistryFriendlyByteBuf, UploadBegin> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, UploadBegin::uploadId,
				BlockPos.STREAM_CODEC, UploadBegin::pos,
				ByteBufCodecs.stringUtf8(HASH_LENGTH), UploadBegin::hash,
				ByteBufCodecs.VAR_INT, UploadBegin::size,
				UploadBegin::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record UploadChunk(int uploadId, int seq, byte[] data) implements CustomPacketPayload {
		public static final Type<UploadChunk> TYPE = typeOf("upload_chunk");
		public static final StreamCodec<RegistryFriendlyByteBuf, UploadChunk> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, UploadChunk::uploadId,
				ByteBufCodecs.VAR_INT, UploadChunk::seq,
				ByteBufCodecs.byteArray(CHUNK_SIZE), UploadChunk::data,
				UploadChunk::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record UploadCancel(int uploadId) implements CustomPacketPayload {
		public static final Type<UploadCancel> TYPE = typeOf("upload_cancel");
		public static final StreamCodec<RegistryFriendlyByteBuf, UploadCancel> CODEC = ByteBufCodecs.VAR_INT.map(UploadCancel::new, UploadCancel::uploadId).cast();

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record TrackRequest(String hash, String title) {
		public static final StreamCodec<RegistryFriendlyByteBuf, TrackRequest> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(HASH_LENGTH), TrackRequest::hash,
				ByteBufCodecs.stringUtf8(TEXT_LENGTH), TrackRequest::title,
				TrackRequest::new
		);
	}

	/** All tracks are uploaded: burn them onto the blank disc in the burner at {@code pos}. */
	public record BurnDisc(BlockPos pos, String title, String artist, boolean explicit, boolean shuffle, List<TrackRequest> tracks) implements CustomPacketPayload {
		public static final Type<BurnDisc> TYPE = typeOf("burn_disc");
		public static final StreamCodec<RegistryFriendlyByteBuf, BurnDisc> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, BurnDisc::pos,
				ByteBufCodecs.stringUtf8(TEXT_LENGTH), BurnDisc::title,
				ByteBufCodecs.stringUtf8(TEXT_LENGTH), BurnDisc::artist,
				ByteBufCodecs.BOOL, BurnDisc::explicit,
				ByteBufCodecs.BOOL, BurnDisc::shuffle,
				TrackRequest.CODEC.apply(ByteBufCodecs.list(DiscInfo.MAX_TRACKS)), BurnDisc::tracks,
				BurnDisc::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Lazy download: asks the server for a track this client does not have cached yet. */
	public record RequestTrack(String hash) implements CustomPacketPayload {
		public static final Type<RequestTrack> TYPE = typeOf("request_track");
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestTrack> CODEC = ByteBufCodecs.stringUtf8(HASH_LENGTH).map(RequestTrack::new, RequestTrack::hash).cast();

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record TrackChunkAck(String hash, int seq) implements CustomPacketPayload {
		public static final Type<TrackChunkAck> TYPE = typeOf("track_chunk_ack");
		public static final StreamCodec<RegistryFriendlyByteBuf, TrackChunkAck> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(HASH_LENGTH), TrackChunkAck::hash,
				ByteBufCodecs.VAR_INT, TrackChunkAck::seq,
				TrackChunkAck::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// ---- Server to client ----

	/** Sent on join so the client can check limits before converting and uploading. */
	public record ServerSettings(int maxTrackSeconds, int maxDiscSeconds, int maxTracks, int maxFileBytes, int maxBitrateKbps, boolean canBurn) implements CustomPacketPayload {
		public static final Type<ServerSettings> TYPE = typeOf("server_settings");
		public static final StreamCodec<RegistryFriendlyByteBuf, ServerSettings> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, ServerSettings::maxTrackSeconds,
				ByteBufCodecs.VAR_INT, ServerSettings::maxDiscSeconds,
				ByteBufCodecs.VAR_INT, ServerSettings::maxTracks,
				ByteBufCodecs.VAR_INT, ServerSettings::maxFileBytes,
				ByteBufCodecs.VAR_INT, ServerSettings::maxBitrateKbps,
				ByteBufCodecs.BOOL, ServerSettings::canBurn,
				ServerSettings::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record UploadStatus(int uploadId, int state, int received, Component message) implements CustomPacketPayload {
		/** Server is ready for chunks. */
		public static final int READY = 0;
		/** Acknowledges received bytes; the client may send more. */
		public static final int PROGRESS = 1;
		/** The track is stored on the server (uploaded now or already there). */
		public static final int STORED = 2;
		/** Upload rejected or failed; {@code message} says why. */
		public static final int ERROR = 3;

		public static final Type<UploadStatus> TYPE = typeOf("upload_status");
		public static final StreamCodec<RegistryFriendlyByteBuf, UploadStatus> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, UploadStatus::uploadId,
				ByteBufCodecs.VAR_INT, UploadStatus::state,
				ByteBufCodecs.VAR_INT, UploadStatus::received,
				ComponentSerialization.STREAM_CODEC, UploadStatus::message,
				UploadStatus::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Answer to {@link BurnDisc}. On success {@code message} is the disc name. */
	public record BurnResult(boolean success, Component message) implements CustomPacketPayload {
		public static final Type<BurnResult> TYPE = typeOf("burn_result");
		public static final StreamCodec<RegistryFriendlyByteBuf, BurnResult> CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL, BurnResult::success,
				ComponentSerialization.STREAM_CODEC, BurnResult::message,
				BurnResult::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record TrackChunk(String hash, int seq, int totalSize, byte[] data) implements CustomPacketPayload {
		public static final Type<TrackChunk> TYPE = typeOf("track_chunk");
		public static final StreamCodec<RegistryFriendlyByteBuf, TrackChunk> CODEC = StreamCodec.composite(
				ByteBufCodecs.stringUtf8(HASH_LENGTH), TrackChunk::hash,
				ByteBufCodecs.VAR_INT, TrackChunk::seq,
				ByteBufCodecs.VAR_INT, TrackChunk::totalSize,
				ByteBufCodecs.byteArray(CHUNK_SIZE), TrackChunk::data,
				TrackChunk::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record TrackUnavailable(String hash) implements CustomPacketPayload {
		public static final Type<TrackUnavailable> TYPE = typeOf("track_unavailable");
		public static final StreamCodec<RegistryFriendlyByteBuf, TrackUnavailable> CODEC = ByteBufCodecs.stringUtf8(HASH_LENGTH).map(TrackUnavailable::new, TrackUnavailable::hash).cast();

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * A jukebox at {@code pos} is playing {@code disc}, which started at server game time {@code startGameTime}.
	 * Sent when it starts and to anyone who comes into range later, who then seeks to the right position.
	 */
	public record PlayCustomDisc(BlockPos pos, DiscInfo disc, long startGameTime) implements CustomPacketPayload {
		public static final Type<PlayCustomDisc> TYPE = typeOf("play_custom_disc");
		public static final StreamCodec<RegistryFriendlyByteBuf, PlayCustomDisc> CODEC = StreamCodec.composite(
				BlockPos.STREAM_CODEC, PlayCustomDisc::pos,
				DiscInfo.STREAM_CODEC, PlayCustomDisc::disc,
				ByteBufCodecs.VAR_LONG, PlayCustomDisc::startGameTime,
				PlayCustomDisc::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record StopCustomDisc(BlockPos pos) implements CustomPacketPayload {
		public static final Type<StopCustomDisc> TYPE = typeOf("stop_custom_disc");
		public static final StreamCodec<RegistryFriendlyByteBuf, StopCustomDisc> CODEC = BlockPos.STREAM_CODEC.map(StopCustomDisc::new, StopCustomDisc::pos).cast();

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
