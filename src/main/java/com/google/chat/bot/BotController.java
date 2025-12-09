package com.google.chat.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.gax.core.FixedCredentialsProvider; // Import this
import com.google.auth.oauth2.GoogleCredentials; // Import this
import com.google.apps.card.v1.Action;
import com.google.apps.card.v1.Button;
import com.google.apps.card.v1.ButtonList;
import com.google.apps.card.v1.Card;
import com.google.apps.card.v1.CardHeader;
import com.google.apps.card.v1.OnClick;
import com.google.apps.card.v1.Section;
import com.google.apps.card.v1.TextParagraph;
import com.google.apps.card.v1.Widget;
import com.google.chat.v1.CardWithId;
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

      if ("CARD_CLICKED".equals(eventType)) {
        handleCardClicked(event, chatNode);
        return;
      }

      if ("ADDED_TO_SPACE".equals(eventType) || chatNode.has("addedToSpacePayload")) {
        logger.info("Received ADDED_TO_SPACE event. Returning 200 OK.");
        JsonNode spaceNode = chatNode.path("space");
        if (spaceNode.isMissingNode()) {
          spaceNode = event.path("space");
        }
        if (spaceNode.isMissingNode()) {
          spaceNode = chatNode.path("addedToSpacePayload").path("space");
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
      long commandId = messageNode.path("slashCommand").path("commandId").asLong();
      logger.info("Slash command detected: {}", commandId);
      if (commandId == 1) {
        reply(spaceName, threadName, "You invoked the /pubsubtest slash command.");
      } else if (commandId == 2) {
        sendCardWithButton(spaceName, threadName);
      }
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

      Message response = chatServiceClient.createMessage(request);
      logger.info("Sent reply to {}, response ID: {}", spaceName, response.getName());

    } catch (Exception e) {
      logger.error("Failed to send reply to " + spaceName, e);
    }
  }

  // Overload for ADDED_TO_SPACE (no thread)
  private void reply(String spaceName, String text) {
    reply(spaceName, null, text);
  }

  private void sendCardWithButton(String spaceName, String threadName) {
    if (chatServiceClient == null) {
      logger.error("ChatServiceClient is not initialized, cannot send card.");
      return;
    }
    try {
      Widget buttonWidget =
          Widget.newBuilder()
              .setButtonList(
                  ButtonList.newBuilder()
                      .addButtons(
                          Button.newBuilder()
                              .setText("Click me")
                              .setOnClick(
                                  OnClick.newBuilder()
                                      .setAction(
                                          Action.newBuilder()
                                              .setFunction("sendTextMessage")
                                              .build())
                                      .build())
                              .build())
                      .build())
              .build();

      Card card =
          Card.newBuilder()
              .setHeader(CardHeader.newBuilder().setTitle("Card with Button").build())
              .addSections(Section.newBuilder().addWidgets(buttonWidget).build())
              .build();

      CardWithId cardWithId = CardWithId.newBuilder().setCardId("card-1").setCard(card).build();

      Message.Builder messageBuilder = Message.newBuilder().addCardsV2(cardWithId);

      if (threadName != null && !threadName.isEmpty()) {
        messageBuilder.setThread(
            com.google.chat.v1.Thread.newBuilder().setName(threadName).build());
      }

      CreateMessageRequest request =
          CreateMessageRequest.newBuilder()
              .setParent(spaceName)
              .setMessage(messageBuilder.build())
              .build();

      chatServiceClient.createMessage(request);
      logger.info("Sent card with button to {}", spaceName);

    } catch (Exception e) {
      logger.error("Failed to send card to " + spaceName, e);
    }
  }

  private void handleCardClicked(JsonNode event, JsonNode chatNode) {
    String function = event.path("commonEventObject").path("invokedFunction").asText();
    logger.info("Handling card click with function: {}", function);
    if ("sendTextMessage".equals(function)) {
      String spaceName = chatNode.path("space").path("name").asText();
      if (spaceName.isEmpty()) {
        spaceName = event.path("space").path("name").asText();
      }
      reply(spaceName, "You clicked the button!");
    }
  }
}
