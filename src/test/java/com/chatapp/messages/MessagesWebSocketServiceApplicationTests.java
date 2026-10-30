package com.chatapp.messages;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Placeholder Mongo credentials only satisfy the URI placeholders: the driver connects lazily,
// and nothing here issues a query. Eureka is off, as in cloud-gateway's context test.
@SpringBootTest(properties = {
		"MONGO_APP_USERNAME=test",
		"MONGO_APP_PASSWORD=test",
		"CHAT_ROOM_DETAIL_PATH=/api/rooms/{roomId}/",
		"RABBITMQ_USERNAME=test",
		"RABBITMQ_PASSWORD=test",
		"CHAT_EVENTS_EXCHANGE=chat.events",
		"CHAT_EVENTS_EXCHANGE_TYPE=topic",
		"ROOM_DELETED_ROUTING_KEY=chat.events.room_deleted",
		"ROOM_DELETED_ROOM_ID_FIELD=room_id",
		"spring.rabbitmq.listener.simple.auto-startup=false",
		"eureka.client.enabled=false"
})
class MessagesWebSocketServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
