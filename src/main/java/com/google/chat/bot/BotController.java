package com.google.chat.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.chat.v1.ChatServiceClient;
import com.google.chat.v1.ChatServiceSettings; // Import this
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

  private static final String CHAT_API_ENDPOINT = "chat.googleapis.com:443";

  @PostConstruct
  public void init() {
    try {
      logger.info("Initializing ChatServiceClient with endpoint: {}", CHAT_API_ENDPOINT);
      ChatServiceSettings chatServiceSettings =
          ChatServiceSettings.newBuilder().setEndpoint(CHAT_API_ENDPOINT).build();
      chatServiceClient = ChatServiceClient.create(chatServiceSettings);
      logger.info("ChatServiceClient initialized successfully.");
    } catch (Exception e) {
      logger.error("Failed to initialize ChatServiceClient", e);
      // chatServiceClient will be null, and subsequent calls will fail
    }
  }

  @PreDestroy
  public void cleanup() {
    if (chatServiceClient != null) {
      logger.info("Closing ChatServiceClient.");
      chatServiceClient.close();
    }
  }

  // ... rest of the class remains the same ...

  @PostMapping("/")
  public void receiveMessage(@RequestBody String body) {
    if (chatServiceClient == null) {
      logger.error("Cannot process message, ChatServiceClient is not initialized.");
      return; // Or throw an error to indicate service unavailability
    }
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
    JsonNode userNode = event.path("user");
    if (userNode.isMissingNode() || "BOT".equals(userNode.path("type").asText())) {
      logger.debug("Ignoring message from bot or missing user type.");
      return;
    }

    String spaceName = event.path("space").path("name").asText();
    String text = event.path("message").path("text").asText();
    String senderName = userNode.path("displayName").asText();

    if (spaceName.isEmpty()) {
      logger.warn("Space name is missing in the event.");
      return;
    }

    reply(spaceName, "Hello " + senderName + ", you said: " + text);
  }

  private void reply(String spaceName, String text) {
    if (chatServiceClient == null) {
      logger.error("ChatServiceClient is not initialized, cannot send reply.");
      return;
    }

    try {
      Message message = Message.newBuilder().setText(text).build();
      CreateMessageRequest request =
          CreateMessageRequest.newBuilder().setParent(spaceName).setMessage(message).build();
      logger.info("Attempting to send reply to {}: {}", spaceName, text);
      chatServiceClient.createMessage(request);
      logger.info("Sent reply to " + spaceName);
    } catch (Exception e) {
      logger.error("Failed to send reply to " + spaceName, e);
    }
  }
}
