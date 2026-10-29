package com.chatapp.messages.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Random;
import java.util.stream.Stream;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CursorCodecTest {

    private static final String HEX = "6716c3f2a1b2c3d4e5f60718";
    private static final String MS = "1760955330123";

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void roundTripsTenThousandGeneratedPositionsAndEmitsOnlyUrlSafeUnpaddedText() {
        Random random = new Random(20261020L);   // fixed seed: failures are reproducible
        for (int n = 0; n < 10_000; n++) {
            byte[] oid = new byte[12];
            random.nextBytes(oid);
            CursorPosition p = new CursorPosition(Instant.ofEpochMilli(random.nextLong(0, 4_102_444_800_000L)), new ObjectId(oid));

            String encoded = CursorCodec.encode(p);

            assertThat(encoded).matches("^[A-Za-z0-9_-]+$").doesNotContain("=").hasSizeLessThanOrEqualTo(CursorCodec.MAX_ENCODED_LENGTH);
            assertThat(CursorCodec.decode(encoded)).isEqualTo(p);
        }
    }

    @Test
    void roundTripsTheEdges() {
        for (CursorPosition p : new CursorPosition[] {
                new CursorPosition(Instant.EPOCH, new ObjectId("000000000000000000000000")),
                new CursorPosition(Instant.ofEpochMilli(Long.MAX_VALUE), new ObjectId("ffffffffffffffffffffffff")) }) {
            assertThat(CursorCodec.decode(CursorCodec.encode(p))).isEqualTo(p);
        }
    }

    static Stream<String> brokenCursors() {
        String valid = CursorCodec.encode(new CursorPosition(Instant.ofEpochMilli(Long.parseLong(MS)), new ObjectId(HEX)));
        return Stream.of(
                "",                                              // empty
                "   ",                                           // whitespace
                "abc+def/",                                      // plain-base64 alphabet, not URL-safe
                "dGVzdA==",                                      // padded
                "a",                                             // one char: not a whole base64 unit
                "%%%",                                           // not base64 at all
                "x".repeat(65),                                  // over the length cap
                b64("v1:" + MS),                                 // two fields
                b64("v1:" + MS + ":" + HEX + ":extra"),          // four fields
                b64("v2:" + MS + ":" + HEX),                     // unknown version
                b64("v1:abc:" + HEX),                            // non-numeric timestamp
                b64("v1:-1:" + HEX),                             // negative timestamp
                b64("v1:99999999999999999999:" + HEX),           // long overflow
                b64("v1:0" + MS + ":" + HEX),                    // leading zero: non-canonical
                b64("v1:" + MS + ":zz" + HEX.substring(2)),      // invalid hex
                b64("v1:" + MS + ":" + HEX.toUpperCase()),       // upper-case hex: non-canonical
                b64("v1:" + MS + ":" + HEX.substring(1)),        // 23 hex chars
                valid + "A",                                     // tampered: one char appended
                b64("{\"$gt\":\"\"}"));                          // injection-shaped payload
    }

    @ParameterizedTest
    @MethodSource("brokenCursors")
    void rejectsEveryBrokenInputWithOneOpaqueException(String input) {
        assertThatThrownBy(() -> CursorCodec.decode(input))
                .isExactlyInstanceOf(InvalidCursorException.class)
                .hasMessage("Invalid cursor");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> CursorCodec.decode(null))
                .isExactlyInstanceOf(InvalidCursorException.class)
                .hasMessage("Invalid cursor");
    }
}
