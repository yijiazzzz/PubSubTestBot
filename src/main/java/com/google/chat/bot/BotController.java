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
  private static final String ACTION_CARD_CLICK = "handleChatAction";

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
    logger.info("receiveMessage START - Raw Body: {}", body);
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

      JsonNode commonEventObject = event.path("commonEventObject");
      JsonNode chatNode = event.path("chat");

      // GWAO Event Dispatching
      if (commonEventObject.has("invokedFunction")) {
        logger.info("Detected invokedFunction, routing to handleCardClicked");
        handleCardClicked(event);
      } else if (chatNode.has("appCommandPayload")) {
        logger.info("Detected appCommandPayload, treating as slash command.");
        handleAppCommand(chatNode.path("appCommandPayload"));
      } else if (chatNode.has("messagePayload")) {
        logger.info("Detected messagePayload, treating as standard message.");
        handleChatMessage(chatNode.path("messagePayload"));
      } else if (chatNode.has("addedToSpacePayload")) {
        logger.info("Detected addedToSpacePayload.");
        handleAddedToSpace(chatNode.path("addedToSpacePayload"));
      } else {
        logger.warn("Unhandled Chat event structure.");
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
    JsonNode commonEventObject = event.path("commonEventObject");
    String actionMethodName = commonEventObject.path("invokedFunction").asText();
    logger.info("Handling card click with function: {}", actionMethodName);

    // Get spaceName from hostAppMetadata or fall back to event.chat
    String spaceName =
        commonEventObject.path("hostAppMetadata").path("chat").path("space").path("name").asText();
    if (spaceName.isEmpty()) {
      logger.info("Space name not in hostAppMetadata, trying event.chat.space");
      spaceName = event.path("chat").path("space").path("name").asText();
    }

    if (spaceName.isEmpty()) {
      logger.error("CRITICAL: Space name missing in card click event. Cannot reply.");
      return;
    }

    if (ACTION_CARD_CLICK.equals(actionMethodName)) {
      logger.info("Matched ACTION_CARD_CLICK. Space: {}", spaceName);
      // Card click events from GWAO don't easily provide the original thread context
      // To reply in thread, you'd need to pass the thread name as a parameter in the Action
      reply(spaceName, null, "Button clicked on Chaddon card!");
    } else {
      logger.warn("Unhandled card action: {}", actionMethodName);
    }
    logger.info("handleCardClicked END");
  }

  // --- Helper Methods ---
  private void reply(String spaceName, String threadName, String text) {
    if (chatServiceClient == null) {
      logger.error("ChatServiceClient not initialized.");
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
      logger.error("ChatServiceClient not initialized.");
      return;
    }
    try {
      Button button =
          Button.newBuilder()
              .setText("Click Me")
              .setOnClick(
                  OnClick.newBuilder()
                      .setAction(
                          Action.newBuilder()
                              .setFunction(ACTION_CARD_CLICK)
                              .setLoadIndicator(Action.LoadIndicator.SPINNER)
                              .addParameters(
                                  Action.ActionParameter.newBuilder()
                                      .setKey("original_action")
                                      .setValue("onCardClick")
                                      .build())))
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
