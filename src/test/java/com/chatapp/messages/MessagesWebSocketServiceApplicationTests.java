package com.chatapp.messages;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Placeholder Mongo credentials only satisfy the URI placeholders: the driver connects lazily,
// and nothing here issues a query. Eureka is off, as in cloud-gateway's context test.
@SpringBootTest(properties = {
		"MONGO_APP_USERNAME=test",
		"MONGO_APP_PASSWORD=test",
		"eureka.client.enabled=false"
})
class MessagesWebSocketServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
