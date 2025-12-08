package com.google.chat.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.gax.core.FixedCredentialsProvider; // Import this
import com.google.auth.oauth2.GoogleCredentials; // Import this
import com.google.chat.v1.ChatServiceClient;
import com.google.chat.v1.ChatServiceSettings;
import com.google.chat.v1.CreateMessageRequest;
import com.google.chat.v1.Message;
import com.google.common.collect.ImmutableList; // Import this
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

@RestController
public class BotController {

  private static final Logger logger = LoggerFactory.getLogger(BotController.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private ChatServiceClient chatServiceClient;

  private static final String CHAT_API_ENDPOINT = "chat.googleapis.com:443";
  private static final String CHAT_SCOPE = "https://www.googleapis.com/auth/chat.bot";

  @PostConstruct
  public void init() {
    try {
      logger.info(
          "Initializing ChatServiceClient with endpoint: {} and scope: {}",
          CHAT_API_ENDPOINT,
          CHAT_SCOPE);

      // Obtain Application Default Credentials and add the chat.bot scope
      GoogleCredentials credentials =
          GoogleCredentials.getApplicationDefault().createScoped(ImmutableList.of(CHAT_SCOPE));

      ChatServiceSettings chatServiceSettings =
          ChatServiceSettings.newBuilder()
              .setEndpoint(CHAT_API_ENDPOINT)
              .setCredentialsProvider(
                  FixedCredentialsProvider.create(credentials)) // Use scoped credentials
              .build();

      chatServiceClient = ChatServiceClient.create(chatServiceSettings);
      logger.info("ChatServiceClient initialized successfully.");
    } catch (Exception e) {
      logger.error("Failed to initialize ChatServiceClient", e);
    }
  }

  @PreDestroy
  public void cleanup() {
    if (chatServiceClient != null) {
      logger.info("Closing ChatServiceClient.");
      chatServiceClient.close();
    }
  }

  @PostMapping("/")
  public void receiveMessage(@RequestBody String body) {
    System.out.println(
        "receiveMessage START - Raw body length: " + (body != null ? body.length() : "null"));
    logger.info("receiveMessage START - Raw body: {}", body);
    if (chatServiceClient == null) {
      logger.error("Cannot process message, ChatServiceClient is not initialized.");
      return;
    }
    try {
      logger.info("receiveMessage TRY block entry");
      JsonNode root = objectMapper.readTree(body);
      JsonNode messageNode = root.path("message");

      if (messageNode.isMissingNode()) {
        logger.warn("Invalid Pub/Sub request: missing 'message' field");
        return;
      }
      logger.info("Found 'message' field");

      String data = messageNode.path("data").asText();
      if (data.isEmpty()) {
        logger.warn("Invalid Pub/Sub request: missing 'data' field");
        return;
      }
      logger.info("Found 'data' field");

      String decodedData = new String(Base64.getDecoder().decode(data));
      // Log to stdout to guarantee visibility
      System.out.println("Received event raw: " + decodedData);
      logger.info("Received event raw: " + decodedData.replace("\n", "\\n").replace("\r", "\\r"));

      JsonNode event = objectMapper.readTree(decodedData);
      logger.info("Successfully parsed decodedData");

      JsonNode chatNode = event.path("chat");
      if (chatNode.isMissingNode()) {
        logger.warn("Event is missing 'chat' field.");
        return;
      }
      logger.info("Found 'chat' field");

      // Check for ADDED_TO_SPACE event
      JsonNode eventTypeNode = event.path("commonEventObject").path("eventType");
      if (!eventTypeNode.isMissingNode() && "ADDED_TO_SPACE".equals(eventTypeNode.asText())) {
        logger.info("Received ADDED_TO_SPACE event. Returning 200 OK.");
        JsonNode spaceNode = chatNode.path("space");
        String spaceName = spaceNode.path("name").asText();
        reply(spaceName, "Thanks for adding me!");
        return;
      }

      JsonNode messagePayload = chatNode.path("messagePayload");
      if (messagePayload.isMissingNode()) {
        logger.warn(
            "Event.chat is missing 'messagePayload' field. Check if this is a different event"
                + " type.");
        return;
      }
      logger.info("Found 'chat.messagePayload' field, processing as message event.");
      handleMessageEvent(messagePayload);

    } catch (Exception e) {
      logger.error("Error in receiveMessage", e);
      e.printStackTrace(); // Ensure stack trace is in stdout
    }
    logger.info("receiveMessage END");
  }

  private void handleMessageEvent(JsonNode messagePayload) {
    logger.info("handleMessageEvent START");

    JsonNode messageNode = messagePayload.path("message");
    if (messageNode.isMissingNode()) {
      logger.warn("messagePayload is missing 'message' field.");
      return;
    }

    JsonNode senderNode = messageNode.path("sender");
    if (senderNode.isMissingNode()) {
      logger.warn("Sender node is missing in message.");
      return;
    }
    String senderType = senderNode.path("type").asText("UNKNOWN");
    logger.info("Handling message from sender type: {}", senderType);

    if ("BOT".equals(senderType)) {
      logger.info("Ignoring message because sender is a BOT.");
      return;
    }

    JsonNode spaceNode = messagePayload.path("space");
    String spaceName = spaceNode.path("name").asText();
    if (spaceName.isEmpty()) {
      logger.warn("Space name is missing or empty in messagePayload.space.");
      return;
    }
    logger.info("Processing message in space: {}", spaceName);

    String text = messageNode.path("text").asText();
    String senderName = senderNode.path("displayName").asText();

    reply(spaceName, "Hello " + senderName + ", you said: " + text);
    logger.info("handleMessageEvent END");
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
      Message response = chatServiceClient.createMessage(request);
      logger.info("Sent reply to {}, response ID: {}", spaceName, response.getName());
    } catch (Exception e) {
      logger.error("Failed to send reply to " + spaceName, e);
    }
  }
}
