package com.chatapp.messages.repository;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;

import org.bson.types.ObjectId;

/** Opaque cursor <-> CursorPosition. Format inside the encoding: "v1:<epochMillis>:<24 lowercase hex>". */
public final class CursorCodec {

    static final String VERSION = "v1";
    /** Longest legal cursor is 63 chars (19-digit millis); anything longer is rejected before decoding. */
    static final int MAX_ENCODED_LENGTH = 64;

    private static final Pattern URL_SAFE_ALPHABET = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();   // MUST pair with getUrlEncoder

    private CursorCodec() {
    }

    public static String encode(CursorPosition p) {
        String raw = VERSION + ":" + p.createdAt().toEpochMilli() + ":" + p.id().toHexString();
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    /** @throws InvalidCursorException for every input that is not exactly a cursor this codec produced. */
    public static CursorPosition decode(String encoded) {
        try {
            if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_ENCODED_LENGTH
                    || !URL_SAFE_ALPHABET.matcher(encoded).matches()) {
                throw new InvalidCursorException(null);
            }
            String raw = new String(DECODER.decode(encoded), StandardCharsets.US_ASCII);
            String[] parts = raw.split(":", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new InvalidCursorException(null);
            }
            long millis = Long.parseLong(parts[1]);          // NumberFormatException is an IllegalArgumentException
            if (millis < 0) {
                throw new InvalidCursorException(null);
            }
            CursorPosition p = new CursorPosition(Instant.ofEpochMilli(millis), new ObjectId(parts[2]));
            if (!encode(p).equals(encoded)) {                 // leading zeros, "+123", upper-case hex, ...
                throw new InvalidCursorException(null);
            }
            return p;
        } catch (InvalidCursorException e) {
            throw e;
        } catch (IllegalArgumentException | DateTimeException e) {
            // Illegal base64 character / Last unit does not have enough valid bits / For input string /
            // invalid hexadecimal representation of an ObjectId — all collapse to one opaque 400.
            throw new InvalidCursorException(e);
        }
    }
}
