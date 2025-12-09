package com.google.chat.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.apps.card.v1.Action;
import com.google.apps.card.v1.Button;
import com.google.apps.card.v1.ButtonList;
import com.google.apps.card.v1.Card;
import com.google.apps.card.v1.Card.CardHeader;
import com.google.apps.card.v1.Card.Section;
import com.google.apps.card.v1.OnClick;
import com.google.apps.card.v1.Widget;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.chat.v1.CardWithId;
import com.google.chat.v1.ChatServiceClient;
import com.google.chat.v1.ChatServiceSettings;
import com.google.chat.v1.CreateMessageRequest;
import com.google.chat.v1.Message;
import com.google.chat.v1.Thread;
import com.google.common.collect.ImmutableList;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;

@RestController
public class BotController {

  private static final Logger logger = LoggerFactory.getLogger(BotController.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private ChatServiceClient chatServiceClient;

  private static final String CHAT_API_ENDPOINT = "chat.googleapis.com:443";
  private static final String CHAT_SCOPE = "https://www.googleapis.com/auth/chat.bot";

  private static final long CMD_PUBSUBTEST = 1;
  private static final long CMD_CREATE_CARD = 2;
  private static final String ACTION_SEND_MESSAGE = "sendTextMessage";

  @PostConstruct
  public void init() {
    try {
      logger.info(
          "Initializing ChatServiceClient with endpoint: {} and scope: {}",
          CHAT_API_ENDPOINT,
          CHAT_SCOPE);
      GoogleCredentials credentials =
          GoogleCredentials.getApplicationDefault().createScoped(ImmutableList.of(CHAT_SCOPE));
      ChatServiceSettings chatServiceSettings =
          ChatServiceSettings.newBuilder()
              .setEndpoint(CHAT_API_ENDPOINT)
              .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
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
    logger.info("receiveMessage START");
    if (chatServiceClient == null) {
      logger.error("Cannot process message, ChatServiceClient is not initialized.");
      return;
    }
    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode messageNode = root.path("message");

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
      logger.info("Received event raw: {}", decodedData);
      JsonNode event = objectMapper.readTree(decodedData);
      logger.info("Parsed event JSON: {}", event.toString());

      JsonNode chatNode = event.path("chat");
      if (chatNode.isMissingNode()) {
        logger.warn("Event is missing 'chat' field.");
        return;
      }

      // Chaddon event dispatching based on payload type
      if (chatNode.has("messagePayload")) {
        logger.info("Detected messagePayload, treating as standard message.");
        handleChatMessage(chatNode.path("messagePayload"));
      } else if (chatNode.has("appCommandPayload")) {
        logger.info("Detected appCommandPayload, treating as slash command.");
        handleAppCommand(chatNode.path("appCommandPayload"));
      } else if (chatNode.has("addedToSpacePayload")) {
        logger.info("Detected addedToSpacePayload.");
        handleAddedToSpace(chatNode.path("addedToSpacePayload"));
      } else if (event.path("commonEventObject").path("invokedFunction").isTextual()) {
        // Card clicks are identified by invokedFunction in commonEventObject
        String function = event.path("commonEventObject").path("invokedFunction").asText();
        logger.info("Detected CARD_CLICKED with function: {}", function);
        handleCardClicked(event);
      } else {
        // Fallback for other event types from commonEventObject if needed
        String hostApp = event.path("commonEventObject").path("hostApp").asText();
        if ("CHAT".equals(hostApp)) {
          // Try to get event type from commonEventObject, though it's not always standard
          String commonEventType = event.path("commonEventObject").path("eventType").asText();
          logger.warn("Unhandled Chat event structure. CommonEventType: '{}'", commonEventType);
        } else {
          logger.warn("Received event for non-Chat hostApp: {}", hostApp);
        }
      }

    } catch (Exception e) {
      logger.error("Error in receiveMessage", e);
    }
    logger.info("receiveMessage END");
  }

  private void handleAddedToSpace(JsonNode addedToSpacePayload) {
    logger.info("Handling ADDED_TO_SPACE event.");
    String spaceName = addedToSpacePayload.path("space").path("name").asText();
    if (!spaceName.isEmpty()) {
      reply(spaceName, null, "Thanks for adding me to this Chaddon!");
    } else {
      logger.warn("ADDED_TO_SPACE: Could not find space name.");
    }
  }

  private void handleAppCommand(JsonNode appCommandPayload) {
    logger.info("handleAppCommand START");
    JsonNode metadata = appCommandPayload.path("appCommandMetadata");
    long commandId = metadata.path("appCommandId").asLong(0);
    String spaceName = appCommandPayload.path("space").path("name").asText();
    String threadName = appCommandPayload.path("message").path("thread").path("name").asText();

    if (spaceName.isEmpty()) {
      logger.warn("APP_COMMAND: Space name missing.");
      return;
    }

    logger.info("App command ID: {}", commandId);
    switch ((int) commandId) {
      case (int) CMD_PUBSUBTEST:
        logger.info("Matched CMD_PUBSUBTEST");
        reply(spaceName, threadName, "Chaddon slash command /pubsubtest invoked!");
        break;
      case (int) CMD_CREATE_CARD:
        logger.info("Matched CMD_CREATE_CARD");
        sendCardWithButton(spaceName, threadName);
        break;
      default:
        logger.warn("Unhandled app command ID: {}", commandId);
        reply(spaceName, threadName, "Unknown slash command.");
    }
    logger.info("handleAppCommand END");
  }

  private void handleChatMessage(JsonNode messagePayload) {
    // Standard text messages from users
    logger.info("handleChatMessage START");
    JsonNode messageNode = messagePayload.path("message");
    String spaceName = messagePayload.path("space").path("name").asText();
    String threadName = messageNode.path("thread").path("name").asText();
    JsonNode senderNode = messageNode.path("sender");

    if ("BOT".equals(senderNode.path("type").asText())) {
      logger.info("Ignoring message from BOT sender.");
      return;
    }

    String senderName = senderNode.path("displayName").asText();
    String text = messageNode.path("text").asText();
    reply(spaceName, threadName, "Hello " + senderName + ", you said: " + text);
    logger.info("handleChatMessage END");
  }

  private void handleCardClicked(JsonNode event) {
    logger.info("handleCardClicked START - Full Event: {}", event.toString());
    String actionMethodName = event.path("commonEventObject").path("invokedFunction").asText();
    logger.info("Handling card click with function: {}", actionMethodName);

    JsonNode chatNode = event.path("chat");
    // The space can also be under commonEventObject.hostAppMetadata.chat.space
    String spaceName = chatNode.path("space").path("name").asText();
    if (spaceName.isEmpty()) {
      spaceName =
          event
              .path("commonEventObject")
              .path("hostAppMetadata")
              .path("chat")
              .path("space")
              .path("name")
              .asText();
      if (!spaceName.isEmpty()) logger.info("Found spaceName in commonEventObject");
    }
    if (spaceName.isEmpty()) {
      // Fallback to top level event.space
      spaceName = event.path("space").path("name").asText();
      if (!spaceName.isEmpty()) logger.info("Found spaceName in event.space");
    }

    if (spaceName.isEmpty()) {
      logger.warn("Space name missing in card click event.");
      return;
    }

    if (ACTION_SEND_MESSAGE.equals(actionMethodName)) {
      logger.info("Matched ACTION_SEND_MESSAGE. Space: {}", spaceName);
      reply(spaceName, null, "You clicked the button on the Chaddon card!");
    } else {
      logger.warn("Unhandled card action: {}", actionMethodName);
    }
    logger.info("handleCardClicked END");
  }

  // --- Helper Methods ---

  private void reply(String spaceName, String threadName, String text) {
    if (chatServiceClient == null) {
      logger.error("ChatServiceClient is not initialized.");
      return;
    }
    try {
      Message.Builder messageBuilder = Message.newBuilder().setText(text);
      if (threadName != null && !threadName.isEmpty()) {
        messageBuilder.setThread(Thread.newBuilder().setName(threadName));
      }
      CreateMessageRequest request =
          CreateMessageRequest.newBuilder()
              .setParent(spaceName)
              .setMessage(messageBuilder.build())
              .build();
      logger.info("Attempting to send reply to {} (thread: {}): {}", spaceName, threadName, text);
      Message response = chatServiceClient.createMessage(request);
      logger.info("Sent reply to {}, response ID: {}", spaceName, response.getName());
    } catch (Exception e) {
      logger.error("Failed to send reply to " + spaceName, e);
    }
  }

  private void sendCardWithButton(String spaceName, String threadName) {
    if (chatServiceClient == null) {
      logger.error("ChatServiceClient is not initialized.");
      return;
    }
    try {
      Button button =
          Button.newBuilder()
              .setText("Click Me")
              .setOnClick(
                  OnClick.newBuilder()
                      .setAction(Action.newBuilder().setFunction(ACTION_SEND_MESSAGE)))
              .build();
      Card card =
          Card.newBuilder()
              .setHeader(CardHeader.newBuilder().setTitle("Chaddon Interactive Card"))
              .addSections(
                  Section.newBuilder()
                      .addWidgets(
                          Widget.newBuilder()
                              .setButtonList(ButtonList.newBuilder().addButtons(button))))
              .build();
      CardWithId cardWithId =
          CardWithId.newBuilder().setCardId("interactive-card-1").setCard(card).build();
      Message.Builder messageBuilder = Message.newBuilder().addCardsV2(cardWithId);
      if (threadName != null && !threadName.isEmpty()) {
        messageBuilder.setThread(Thread.newBuilder().setName(threadName));
      }
      CreateMessageRequest request =
          CreateMessageRequest.newBuilder()
              .setParent(spaceName)
              .setMessage(messageBuilder.build())
              .build();
      logger.info("Attempting to send card to {} (thread: {})", spaceName, threadName);
      chatServiceClient.createMessage(request);
      logger.info("Sent card with button to {}", spaceName);
    } catch (Exception e) {
      logger.error("Failed to send card to " + spaceName, e);
    }
  }
}
