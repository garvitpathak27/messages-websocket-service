package com.chatapp.messages.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

/** Proves the stored shape with Spring Data's real converter — no database needed. */
class MessageMappingTest {

    private static final String ROOM = "93359bfa-3f1d-4544-bb6a-5a303689e159";
    private static final Instant T = Instant.parse("2026-10-20T10:15:30.123Z");

    private MappingMongoConverter converter;

    /** Wired the way Spring Boot wires the real one: the conversions also tell the mapping context
     *  which types are simple, which is what makes Instant a BSON date rather than a nested object. */
    @BeforeEach
    void setUp() {
        MongoCustomConversions conversions = new MongoCustomConversions(List.of());
        MongoMappingContext context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
    }

    @Test
    void storesTheFieldNamesAndBsonTypesTheIndexesAndCursorsDependOn() {
        String hex = new ObjectId().toHexString();
        Message m = new Message(hex, ROOM, "usr_9a8b7c6d5e", "hello", MessageType.TEXT,
                "6f1c2b9e-0d4a-4f7e-9a51-2b8c1d3e4f50", T, null, null, null);

        Document doc = new Document();
        converter.write(m, doc);

        assertThat(doc.get("_id")).isInstanceOf(ObjectId.class).hasToString(hex);
        assertThat(doc.get("roomId")).isEqualTo(ROOM);
        assertThat(doc.get("senderId")).isEqualTo("usr_9a8b7c6d5e");
        assertThat(doc.get("type")).isEqualTo("TEXT");
        assertThat(doc.get("createdAt")).isInstanceOf(Date.class);
        assertThat(((Date) doc.get("createdAt")).getTime()).isEqualTo(T.toEpochMilli());
        assertThat(doc).doesNotContainKeys("editedAt", "deletedAt", "deletedBy");
    }

    @Test
    void absentClientMessageIdIsNotWrittenSoThePartialUniqueIndexSkipsTheDocument() {
        Message m = Message.newText(ROOM, "usr_9a8b7c6d5e", "no client id", null, T);

        Document doc = new Document();
        converter.write(m, doc);

        assertThat(doc).doesNotContainKey("clientMessageId");
    }

    @Test
    void newTextTruncatesCreatedAtToTheMillisecondABsonDateCanHold() {
        Message m = Message.newText(ROOM, "usr_9a8b7c6d5e", "c", "k",
                Instant.parse("2026-10-20T10:15:30.123456789Z"));

        assertThat(m.getCreatedAt()).isEqualTo(T);
        assertThat(m.getType()).isEqualTo(MessageType.TEXT);
        assertThat(m.getId()).isNull();
        assertThat(m.isDeleted()).isFalse();
    }

    @Test
    void readsBackThroughThePersistenceConstructorWithTheIdAsAString() {
        String hex = new ObjectId().toHexString();
        Document doc = new Document();
        converter.write(new Message(hex, ROOM, "usr_9a8b7c6d5e", "x", MessageType.TEXT, null, T, null, T, "usr_admin"), doc);

        Message back = converter.read(Message.class, doc);

        assertThat(back.getId()).isEqualTo(hex);
        assertThat(back.getCreatedAt()).isEqualTo(T);
        assertThat(back.isDeleted()).isTrue();
        assertThat(back.getDeletedBy()).isEqualTo("usr_admin");
        assertThat(back.getContent()).isEqualTo("x");   // stored; read paths null it (Step 27)
    }
}
