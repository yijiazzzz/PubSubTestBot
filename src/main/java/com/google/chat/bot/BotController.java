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

  // Slash Command IDs from Google Cloud Console Configuration
  private static final long CMD_PUBSUBTEST = 1;
  private static final long CMD_CREATE_CARD = 2;

  // Card Action Method Name
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
      String eventType = event.path("type").asText();
      logger.info("Detected event type: {}", eventType);

      switch (eventType) {
        case "ADDED_TO_SPACE":
          handleAddedToSpace(event);
          break;
        case "MESSAGE":
          handleMessageEvent(event);
          break;
        case "CARD_CLICKED":
          handleCardClicked(event);
          break;
        default:
          logger.warn("Unhandled event type: {}", eventType);
      }

    } catch (Exception e) {
      logger.error("Error in receiveMessage", e);
    }
    logger.info("receiveMessage END");
  }

  private void handleAddedToSpace(JsonNode event) {
    logger.info("Handling ADDED_TO_SPACE event.");
    JsonNode spaceNode = event.path("space");
    String spaceName = spaceNode.path("name").asText();
    if (!spaceName.isEmpty()) {
      reply(spaceName, null, "Thanks for adding me!");
    } else {
      logger.warn("Received ADDED_TO_SPACE but could not find space name.");
    }
  }

  private void handleMessageEvent(JsonNode event) {
    logger.info("handleMessageEvent START");
    JsonNode messageNode = event.path("message");
    JsonNode spaceNode = event.path("space");
    String spaceName = spaceNode.path("name").asText();

    if (spaceName.isEmpty()) {
      logger.warn("Space name is missing or empty.");
      return;
    }

    JsonNode senderNode = messageNode.path("sender");
    if ("BOT".equals(senderNode.path("type").asText())) {
      logger.info("Ignoring message from BOT sender.");
      return;
    }

    String threadName = messageNode.path("thread").path("name").asText();

    if (messageNode.has("slashCommand")) {
      long commandId = messageNode.path("slashCommand").path("commandId").asLong();
      logger.info("Slash command detected: {}", commandId);

      switch ((int) commandId) {
        case (int) CMD_PUBSUBTEST:
          reply(spaceName, threadName, "You invoked the /pubsubtest slash command.");
          break;
        case (int) CMD_CREATE_CARD:
          sendCardWithButton(spaceName, threadName);
          break;
        default:
          reply(spaceName, threadName, "Unknown slash command.");
          logger.warn("Unhandled slash command ID: {}", commandId);
      }
    } else {
      // Regular message
      String text = messageNode.path("text").asText();
      String senderName = senderNode.path("displayName").asText();
      reply(spaceName, threadName, "Hello " + senderName + ", you said: " + text);
    }
    logger.info("handleMessageEvent END");
  }

  private void handleCardClicked(JsonNode event) {
    String actionMethodName = event.path("action").path("actionMethodName").asText();
    logger.info("Handling card click with action: {}", actionMethodName);
    String spaceName = event.path("space").path("name").asText();
    // The message and thread from the original card click event
    JsonNode messageNode = event.path("message");
    String threadName = messageNode.path("thread").path("name").asText();

    if (spaceName.isEmpty()) {
      logger.warn("Space name missing in card click event.");
      return;
    }

    if (ACTION_SEND_MESSAGE.equals(actionMethodName)) {
      reply(spaceName, threadName, "You clicked the button!");
    } else {
      logger.warn("Unhandled card action: {}", actionMethodName);
    }
  }

  private void reply(String spaceName, String threadName, String text) {
    if (chatServiceClient == null) {
      logger.error("ChatServiceClient is not initialized, cannot send reply.");
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
      logger.error("ChatServiceClient is not initialized, cannot send card.");
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
              .setHeader(CardHeader.newBuilder().setTitle("Interactive Card"))
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
