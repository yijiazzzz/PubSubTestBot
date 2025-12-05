package com.google.chat.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.chat.v1.ChatServiceClient;
import com.google.chat.v1.CreateMessageRequest;
import com.google.chat.v1.Message;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Base64;

@RestController
public class BotController {

  private static final Logger logger = LoggerFactory.getLogger(BotController.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private ChatServiceClient chatServiceClient;

  @PostConstruct
  public void init() {
    try {
      // chatServiceClient = ChatServiceClient.create();
      logger.info("ChatServiceClient initialization skipped for testing.");
    } catch (Exception e) {
      logger.error("Failed to initialize ChatServiceClient", e);
    }
  }

  @PreDestroy
  public void cleanup() {
    if (chatServiceClient != null) {
      chatServiceClient.close();
    }
  }

  @PostMapping("/")
  public void receiveMessage(@RequestBody String body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode messageNode = root.path("message");

      // Pub/Sub push messages wrap the payload in "message" -> "data"
      if (messageNode.isMissingNode()) {
        logger.warn("Invalid Pub/Sub request: missing 'message' field");
        return;
      }

      String data = messageNode.path("data").asText();
      if (data.isEmpty()) {
        logger.warn("Invalid Pub/Sub request: missing 'data' field");
        return;
      }

      String decodedData = new String(Base64.getDecoder().decode(data));
      logger.info("Received event: " + decodedData);

      JsonNode event = objectMapper.readTree(decodedData);
      String eventType = event.path("type").asText();

      if ("MESSAGE".equals(eventType)) {
        handleMessageEvent(event);
      }

    } catch (Exception e) {
      logger.error("Error processing message", e);
    }
  }

  private void handleMessageEvent(JsonNode event) {
    // Avoid infinite loops by ignoring messages from bots
    String senderType = event.path("user").path("type").asText();
    if ("BOT".equals(senderType)) {
      return;
    }

    String spaceName = event.path("space").path("name").asText();
    String text = event.path("message").path("text").asText();
    String senderName = event.path("user").path("displayName").asText();

    reply(spaceName, "Hello " + senderName + ", you said: " + text);
  }

  private void reply(String spaceName, String text) {
    if (chatServiceClient == null) {
      logger.error("ChatServiceClient is not initialized");
      return;
    }

    try {
      Message message = Message.newBuilder().setText(text).build();
      CreateMessageRequest request =
          CreateMessageRequest.newBuilder().setParent(spaceName).setMessage(message).build();
      chatServiceClient.createMessage(request);
      logger.info("Sent reply to " + spaceName);
    } catch (Exception e) {
      logger.error("Failed to send reply", e);
    }
  }
}
