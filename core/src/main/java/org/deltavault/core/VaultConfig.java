package org.deltavault.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.cryptomator.cryptolib.api.Masterkey;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Strict parser and authenticator for the supported format-8 password vault metadata. */
final class VaultConfig {
	private static final String KID = "masterkeyfile:masterkey.cryptomator";
	private static final int MIN_SHORTENING_THRESHOLD = 32;
	private static final int MAX_SHORTENING_THRESHOLD = 220;

	final String cipherCombo;
	final int shorteningThreshold;

	private VaultConfig(String cipherCombo, int shorteningThreshold) {
		this.cipherCombo = cipherCombo;
		this.shorteningThreshold = shorteningThreshold;
	}

	static void preflightMasterkey(byte[] bytes) throws IOException {
		JsonObject root = parseStrictObject(decodeUtf8(bytes), "masterkey file");
		int version = integerClaim(root, "version");
		int n = integerClaim(root, "scryptCostParam");
		int r = integerClaim(root, "scryptBlockSize");
		if (version != 999) {
			throw new IOException("Only Cryptomator format-8 password masterkey files are supported");
		}
		if (n < 2 || n > 65_536 || (n & (n - 1)) != 0 || r < 1 || r > 8) {
			throw new IOException("Masterkey scrypt parameters exceed the supported safety limits");
		}
		// Cryptolib 2.2.0 fixes scrypt p to 1. Reject a nonstandard field rather than ignore another value.
		JsonElement pClaim = root.get("scryptParallelizationParameter");
		if (pClaim != null && integerClaim(root, "scryptParallelizationParameter") != 1) {
			throw new IOException("Masterkey scrypt parallelization exceeds the supported safety limit");
		}
		byte[] salt = base64Claim(root, "scryptSalt");
		byte[] enc = base64Claim(root, "primaryMasterKey");
		byte[] mac = base64Claim(root, "hmacMasterKey");
		byte[] versionMac = base64Claim(root, "versionMac");
		try {
			if (salt.length != 8 || enc.length != 40 || mac.length != 40 || versionMac.length != 32) {
				throw new IOException("Malformed Cryptomator masterkey file");
			}
		} finally {
			Arrays.fill(salt, (byte) 0);
			Arrays.fill(enc, (byte) 0);
			Arrays.fill(mac, (byte) 0);
			Arrays.fill(versionMac, (byte) 0);
		}
	}

	static VaultConfig authenticate(byte[] bytes, Masterkey masterkey) throws IOException {
		String token = decodeUtf8(bytes);
		String[] segments = token.split("\\.", -1);
		if (segments.length != 3 || segments[0].isEmpty() || segments[1].isEmpty() || segments[2].isEmpty()) {
			throw new IOException("Malformed vault configuration token");
		}
		byte[] headerBytes = decodeJwtSegment(segments[0]);
		byte[] payloadBytes = decodeJwtSegment(segments[1]);
		byte[] signature = decodeJwtSegment(segments[2]);
		try {
			JsonObject header = parseStrictObject(decodeUtf8(headerBytes), "vault configuration header");
			if (!hasExactlyKeys(header, "alg", "kid", "typ")) {
				throw new IOException("Unsupported vault configuration header");
			}
			String algorithm = stringClaim(header, "alg");
			String macAlgorithm;
			int signatureLength;
			switch (algorithm) {
				case "HS256" -> { macAlgorithm = "HmacSHA256"; signatureLength = 32; }
				case "HS384" -> { macAlgorithm = "HmacSHA384"; signatureLength = 48; }
				case "HS512" -> { macAlgorithm = "HmacSHA512"; signatureLength = 64; }
				default -> throw new IOException("Unsupported vault configuration signature algorithm");
			}
			if (signature.length != signatureLength) {
				throw new IOException("Invalid vault configuration signature length");
			}
			if (!KID.equals(stringClaim(header, "kid")) || !"JWT".equals(stringClaim(header, "typ"))) {
				throw new IOException("Unsupported vault configuration key source or token type");
			}
			byte[] rawMasterkey = masterkey.getEncoded(); // Keep this copy intact while the cryptor is alive.
			if (rawMasterkey.length != 64) {
				throw new IOException("Invalid raw masterkey length");
			}
			byte[] expectedSignature = signJwt(macAlgorithm, rawMasterkey, segments[0] + "." + segments[1]);
			try {
				if (!MessageDigest.isEqual(signature, expectedSignature)) {
					throw new IOException("Vault configuration authentication failed");
				}
			} finally {
				Arrays.fill(expectedSignature, (byte) 0);
			}

			JsonObject payload = parseStrictObject(decodeUtf8(payloadBytes), "vault configuration payload");
			if (!hasExactlyKeys(payload, "format", "cipherCombo", "shorteningThreshold", "jti")) {
				throw new IOException("Unsupported vault configuration claims");
			}
			if (integerClaim(payload, "format") != 8) {
				throw new IOException("Only Cryptomator vault format 8 is supported");
			}
			String combo = stringClaim(payload, "cipherCombo");
			if (!"SIV_GCM".equals(combo) && !"SIV_CTRMAC".equals(combo)) {
				throw new IOException("Unsupported Cryptomator cipher combo: " + combo);
			}
			int threshold = integerClaim(payload, "shorteningThreshold");
			if (threshold < MIN_SHORTENING_THRESHOLD || threshold > MAX_SHORTENING_THRESHOLD) {
				throw new IOException("Vault filename shortening threshold is outside supported limits");
			}
			String jti = stringClaim(payload, "jti");
			try {
				if (!UUID.fromString(jti).toString().equals(jti)) {
					throw new IllegalArgumentException("Noncanonical UUID");
				}
			} catch (IllegalArgumentException e) {
				throw new IOException("Invalid vault ID in configuration", e);
			}
			return new VaultConfig(combo, threshold);
		} catch (GeneralSecurityException e) {
			throw new IOException("Unable to authenticate vault configuration", e);
		} finally {
			Arrays.fill(headerBytes, (byte) 0);
			Arrays.fill(payloadBytes, (byte) 0);
			Arrays.fill(signature, (byte) 0);
		}
	}

	private static byte[] signJwt(String algorithm, byte[] key, String data) throws GeneralSecurityException {
		Mac mac = Mac.getInstance(algorithm);
		mac.init(new SecretKeySpec(key, algorithm));
		return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
	}

	private static byte[] decodeJwtSegment(String segment) throws IOException {
		if (!segment.matches("[A-Za-z0-9_-]+")) {
			throw new IOException("Malformed base64url JWT segment");
		}
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(segment);
			if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(segment)) {
				Arrays.fill(decoded, (byte) 0);
				throw new IOException("Noncanonical base64url JWT segment");
			}
			return decoded;
		} catch (IllegalArgumentException e) {
			throw new IOException("Malformed base64url JWT segment", e);
		}
	}

	private static JsonObject parseStrictObject(String json, String label) throws IOException {
		try (JsonReader reader = new JsonReader(new StringReader(json))) {
			reader.setLenient(false);
			JsonElement value = readStrictJson(reader, 0);
			if (reader.peek() != JsonToken.END_DOCUMENT || !value.isJsonObject()) {
				throw new IOException("Invalid " + label);
			}
			return value.getAsJsonObject();
		} catch (IllegalStateException | NumberFormatException e) {
			throw new IOException("Invalid " + label, e);
		}
	}

	private static JsonElement readStrictJson(JsonReader reader, int depth) throws IOException {
		if (depth > 32) {
			throw new IOException("JSON nesting exceeds the supported limit");
		}
		return switch (reader.peek()) {
			case BEGIN_OBJECT -> {
				reader.beginObject();
				JsonObject object = new JsonObject();
				Set<String> names = new HashSet<>();
				while (reader.hasNext()) {
					String name = reader.nextName();
					if (!names.add(name)) {
						throw new IOException("Duplicate JSON property: " + name);
					}
					object.add(name, readStrictJson(reader, depth + 1));
				}
				reader.endObject();
				yield object;
			}
			case BEGIN_ARRAY -> {
				reader.beginArray();
				JsonArray array = new JsonArray();
				while (reader.hasNext()) {
					array.add(readStrictJson(reader, depth + 1));
				}
				reader.endArray();
				yield array;
			}
			case STRING -> new JsonPrimitive(reader.nextString());
			case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
			case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
			case NULL -> {
				reader.nextNull();
				yield JsonNull.INSTANCE;
			}
			default -> throw new IOException("Invalid JSON value");
		};
	}

	private static int integerClaim(JsonObject object, String key) throws IOException {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IOException("Missing or invalid numeric property: " + key);
		}
		try {
			return new BigDecimal(value.getAsString()).intValueExact();
		} catch (ArithmeticException | NumberFormatException e) {
			throw new IOException("Invalid integer property: " + key, e);
		}
	}

	private static String stringClaim(JsonObject object, String key) throws IOException {
		JsonElement value = object.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IOException("Missing or invalid string property: " + key);
		}
		return value.getAsString();
	}

	private static boolean hasExactlyKeys(JsonObject object, String... expected) {
		if (object.size() != expected.length) {
			return false;
		}
		for (String key : expected) {
			if (!object.has(key)) {
				return false;
			}
		}
		return true;
	}

	private static byte[] base64Claim(JsonObject object, String key) throws IOException {
		String value = stringClaim(object, key);
		try {
			return Base64.getDecoder().decode(value);
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid base64 property: " + key, e);
		}
	}

	private static String decodeUtf8(byte[] bytes) throws IOException {
		try {
			return StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			throw new IOException("Remote object is not valid UTF-8", e);
		}
	}
}
