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
import com.google.protobuf.util.JsonFormat; // Import this
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
      String eventType = event.path("commonEventObject").path("eventType").asText();
      if (eventType.isEmpty()) {
        eventType = event.path("type").asText();
      }
      logger.info("Detected event type: {}", eventType);

      if ("ADDED_TO_SPACE".equals(eventType)) {
        logger.info("Received ADDED_TO_SPACE event. Returning 200 OK.");
        JsonNode spaceNode = chatNode.path("space");
        if (spaceNode.isMissingNode()) {
          spaceNode = event.path("space");
        }
        String spaceName = spaceNode.path("name").asText();
        if (!spaceName.isEmpty()) {
          reply(spaceName, "Thanks for adding me!");
        } else {
          logger.warn("Received ADDED_TO_SPACE but could not find space name.");
        }
        return;
      }

      JsonNode messagePayload = chatNode.path("messagePayload");
      if (messagePayload.isMissingNode()) {
        // Try appCommandPayload for slash commands
        messagePayload = chatNode.path("appCommandPayload");
      }

      if (messagePayload.isMissingNode()) {
        logger.warn(
            "Event.chat is missing 'messagePayload' and 'appCommandPayload' field. Check if this is"
                + " a different event type.");
        return;
      }
      logger.info("Found payload field, processing event.");
      handleMessageEvent(messagePayload);

    } catch (Exception e) {
      logger.error("Error in receiveMessage", e);
      e.printStackTrace(); // Ensure stack trace is in stdout
    }
    logger.info("receiveMessage END - Returning HTTP 200 OK");
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

    String threadName = messageNode.path("thread").path("name").asText();

    // Check for Slash Command
    if (messageNode.has("slashCommand")) {
      logger.info("Slash command detected.");
      reply(spaceName, threadName, "You invoked the /pubsubtest slash command.");
      return;
    }

    String text = messageNode.path("text").asText();
    String senderName = senderNode.path("displayName").asText();

    if (text.isEmpty()) {
      text = "[Media/Attachment]";
    }

    reply(spaceName, threadName, "Hello " + senderName + ", you said: " + text);
    logger.info("handleMessageEvent END");
  }

  private void reply(String spaceName, String threadName, String text) {
    if (chatServiceClient == null) {
      logger.error("ChatServiceClient is not initialized, cannot send reply.");
      return;
    }
    try {
      Message.Builder messageBuilder = Message.newBuilder().setText(text);

      // If we have a thread name, reply in that thread
      if (threadName != null && !threadName.isEmpty()) {
        messageBuilder.setThread(
            com.google.chat.v1.Thread.newBuilder().setName(threadName).build());
      }

      Message message = messageBuilder.build();
      CreateMessageRequest request =
          CreateMessageRequest.newBuilder().setParent(spaceName).setMessage(message).build();

      logger.info("Attempting to send reply to {} (thread: {}): {}", spaceName, threadName, text);

      // Log request payload to stdout safely
      logProto("Chat API Request Payload", request);

      Message response = chatServiceClient.createMessage(request);
      logger.info("Sent reply to {}, response ID: {}", spaceName, response.getName());

      // Log response payload to stdout safely
      logProto("Chat API Response Payload", response);

    } catch (Exception e) {
      logger.error("Failed to send reply to " + spaceName, e);
    }
  }

  // Overload for ADDED_TO_SPACE (no thread)
  private void reply(String spaceName, String text) {
    reply(spaceName, null, text);
  }

  private void logProto(String label, com.google.protobuf.MessageOrBuilder proto) {
    try {
      if (proto instanceof CreateMessageRequest) {
        CreateMessageRequest req = (CreateMessageRequest) proto;
        String parent = req.getParent();
        Message msg = req.getMessage();
        System.out.println(
            label + ": {parent=" + parent + ", message=" + messageToString(msg) + "}");
      } else if (proto instanceof Message) {
        System.out.println(label + ": " + messageToString((Message) proto));
      } else {
        System.out.println(label + ": (Unknown proto type: " + proto.getClass().getName() + ")");
      }
    } catch (Throwable e) {
      System.out.println(label + ": (Failed to log payload manually: " + e + ")");
    }
  }

  private String messageToString(Message msg) {
    if (msg == null) return "null";
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    sb.append("name=").append(msg.getName());
    sb.append(", text=").append(msg.getText());
    if (msg.hasThread()) {
      sb.append(", thread.name=").append(msg.getThread().getName());
    }
    sb.append("}");
    return sb.toString();
  }
}
