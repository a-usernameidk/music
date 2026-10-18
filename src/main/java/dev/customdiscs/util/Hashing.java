package dev.customdiscs.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class Hashing {
	private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

	private Hashing() {
	}

	public static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Track ids are used as file names, so only accept exactly 64 lowercase hex characters. */
	public static boolean isValidHash(String hash) {
		return hash != null && SHA256_HEX.matcher(hash).matches();
	}
}
