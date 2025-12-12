package com.google.chat.bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature; // Required for pretty printing
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.apps.card.v1.Action;
import com.google.apps.card.v1.Button;
import com.google.apps.card.v1.ButtonList;
import com.google.apps.card.v1.Card;
import com.google.apps.card.v1.DecoratedText;
import com.google.apps.card.v1.Card.CardHeader;
import com.google.apps.card.v1.Card.Section;
import com.google.apps.card.v1.OnClick;
import com.google.apps.card.v1.SelectionInput;
import com.google.apps.card.v1.Widget;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.chat.v1.CardWithId;
import com.google.chat.v1.ChatServiceClient;
import com.google.chat.v1.ChatServiceSettings;
import com.google.chat.v1.CreateMessageRequest;
import com.google.chat.v1.Message;
import com.google.chat.v1.Thread;
import com.google.chat.v1.UpdateMessageRequest;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.FieldMask;
import com.google.protobuf.util.JsonFormat; // Import for converting Proto to JSON
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BotController {

  private static final Logger logger = LoggerFactory.getLogger(BotController.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private ChatServiceClient chatServiceClient;

  private static final String CHAT_API_ENDPOINT = "chat.googleapis.com:443";
  private static final String CHAT_SCOPE = "https://www.googleapis.com/auth/chat.bot";

  private static final long CMD_PUBSUBTEST = 1;
  private static final long CMD_CREATE_CARD = 2;
  private static final long CMD_UPDATE_MESSAGE_CARD = 3;
  private static final long CMD_STATIC_SUGGESTIONS = 4;
  private static final long CMD_PLATFORM_SUGGESTIONS = 5;
  private static final long CMD_ACCESSORY_WIDGET = 6;
  private static final String ACTION_CARD_CLICK =
      "projects/pubsubchaddontestapp/topics/testpubsubtopic";
  private static final String ACTION_TYPE_UPDATE_MESSAGE = "update_message";
  private static final String ACTION_KEY_STATIC_SUGGESTIONS_SUBMIT = "static_suggestions_submit";
  private static final String ACTION_KEY_PLATFORM_SUGGESTIONS_SUBMIT =
      "platform_suggestions_submit";
  private static final String ACTION_KEY_ACCESSORY_WIDGET_CLICK = "accessory_widget_click";
  private static final String ACTION_KEY_GENERIC_CLICK = "action_value";

  @PostConstruct
  public void init() {
    try {
      logger.info(
          "Initializing ChatServiceClient with endpoint: {} and scope: {}",
          CHAT_API_ENDPOINT,
          CHAT_SCOPE);
      // Enable pretty printing for JSON logs
      objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

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

  // ... other methods remain the same ...
  // receiveMessage, handleAddedToSpace, handleAppCommand, handleChatMessage, handleCardClicked,
  // reply

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
      logger.info("DEBUG: Decoded Pub/Sub Data: {}", decodedData); // *** CRUCIAL LOG ***

      JsonNode event = objectMapper.readTree(decodedData);

      // Echo the received event to the chat for debugging
      String spaceName = extractSpaceName(event);
      if (spaceName != null && !spaceName.isEmpty() && !isBotMessage(event)) {
        String threadName = extractThreadName(event);
        String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(event);
        reply(spaceName, threadName, "Received Event:\n```\n" + prettyJson + "\n```");
      }

      JsonNode commonEventObject = event.path("commonEventObject");
      JsonNode chatNode = event.path("chat");

      if (commonEventObject.has("invokedFunction")) {
        logger.info("DEBUG: Detected commonEventObject.invokedFunction");
        handleCardClicked(event);
      } else if (chatNode.has("buttonClickedPayload")) {
        logger.info("DEBUG: Detected chat.buttonClickedPayload");
        handleCardClicked(event);
      } else if (chatNode.has("appCommandPayload")) {
        logger.info("DEBUG: Detected chat.appCommandPayload");
        handleAppCommand(chatNode.path("appCommandPayload"));
      } else if (chatNode.has("messagePayload")) {
        logger.info("DEBUG: Detected chat.messagePayload");
        handleChatMessage(chatNode.path("messagePayload"));
      } else if (chatNode.has("addedToSpacePayload")) {
        logger.info("DEBUG: Detected chat.addedToSpacePayload");
        handleAddedToSpace(chatNode.path("addedToSpacePayload"));
      } else {
        logger.warn("DEBUG: Unhandled Chat event structure. Keys: {}", event.fieldNames());
      }

    } catch (IOException e) {
      logger.error("Error processing JSON in receiveMessage", e);
    } catch (Exception e) {
      logger.error("Error in receiveMessage", e);
    }
    logger.info("receiveMessage END");
  }

  private String extractSpaceName(JsonNode event) {
    JsonNode chatNode = event.path("chat");
    if (chatNode.has("messagePayload")) {
      return chatNode.path("messagePayload").path("space").path("name").asText();
    }
    if (chatNode.has("appCommandPayload")) {
      return chatNode.path("appCommandPayload").path("space").path("name").asText();
    }
    if (chatNode.has("addedToSpacePayload")) {
      return chatNode.path("addedToSpacePayload").path("space").path("name").asText();
    }
    if (chatNode.has("buttonClickedPayload")) {
      return chatNode.path("buttonClickedPayload").path("space").path("name").asText();
    }
    if (chatNode.has("space")) {
      return chatNode.path("space").path("name").asText();
    }
    JsonNode commonEventObject = event.path("commonEventObject");
    if (commonEventObject.has("hostAppMetadata")) {
      return commonEventObject
          .path("hostAppMetadata")
          .path("chat")
          .path("space")
          .path("name")
          .asText();
    }
    return null;
  }

  private String extractThreadName(JsonNode event) {
    JsonNode chatNode = event.path("chat");
    if (chatNode.has("messagePayload")) {
      return chatNode.path("messagePayload").path("message").path("thread").path("name").asText();
    }
    if (chatNode.has("appCommandPayload")) {
      return chatNode
          .path("appCommandPayload")
          .path("message")
          .path("thread")
          .path("name")
          .asText();
    }
    return null;
  }

  private boolean isBotMessage(JsonNode event) {
    JsonNode chatNode = event.path("chat");
    if (chatNode.has("messagePayload")) {
      return "BOT"
          .equals(
              chatNode.path("messagePayload").path("message").path("sender").path("type").asText());
    }
    return false;
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
    // ... (rest of the method as before)
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
    // Log the entire metadata for debugging
    logger.info("App command metadata: {}", metadata.toString());
    switch ((int) commandId) {
      case (int) CMD_PUBSUBTEST:
        logger.info("Matched CMD_PUBSUBTEST");
        reply(spaceName, threadName, "Chaddon slash command /pubsubtest invoked!");
        break;
      case (int) CMD_CREATE_CARD:
        logger.info("Matched CMD_CREATE_CARD");
        sendCardWithButton(spaceName, threadName);
        break;
      case (int) CMD_UPDATE_MESSAGE_CARD:
        logger.info("Matched CMD_UPDATE_MESSAGE_CARD");
        sendUpdateCard(spaceName, threadName);
        break;
      case (int) CMD_STATIC_SUGGESTIONS:
        logger.info("Matched CMD_STATIC_SUGGESTIONS");
        sendStaticSuggestionsCard(spaceName, threadName);
        break;
      case (int) CMD_PLATFORM_SUGGESTIONS:
        logger.info("Matched CMD_PLATFORM_SUGGESTIONS");
        sendPlatformSuggestionsCard(spaceName, threadName);
        break;
      case (int) CMD_ACCESSORY_WIDGET:
        logger.info("Matched CMD_ACCESSORY_WIDGET");
        sendAccessoryWidgetCard(spaceName, threadName);
        break;
      default:
        logger.warn("Unhandled app command ID: {}", commandId);
        reply(spaceName, threadName, "Unknown slash command.");
    }
    logger.info("handleAppCommand END");
  }

  private void handleChatMessage(JsonNode messagePayload) {
    // ... (rest of the method as before)
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
    JsonNode chatNode = event.path("chat");

    String actionMethodName = "MISSING_FUNCTION";
    String spaceName = "";

    // 1. Try to get Action Name
    if (commonEventObject.has("invokedFunction")) {
      actionMethodName = commonEventObject.path("invokedFunction").asText();
    } else if (chatNode.has("buttonClickedPayload")
        && chatNode.path("buttonClickedPayload").has("actionMethodName")) {
      actionMethodName = chatNode.path("buttonClickedPayload").path("actionMethodName").asText();
    }
    logger.info("DEBUG: Card click invokedFunction/actionMethodName: {}", actionMethodName);

    // 2. Try to get Space Name
    if (chatNode.has("space")) {
      spaceName = chatNode.path("space").path("name").asText();
    } else if (chatNode.has("buttonClickedPayload")
        && chatNode.path("buttonClickedPayload").has("space")) {
      spaceName = chatNode.path("buttonClickedPayload").path("space").path("name").asText();
    } else {
      // Try hostAppMetadata fallback
      spaceName =
          commonEventObject
              .path("hostAppMetadata")
              .path("chat")
              .path("space")
              .path("name")
              .asText();
    }

    if (spaceName.isEmpty()) {
      logger.error("DEBUG: Space name MISSING in card click event.");
      return;
    }
    logger.info("DEBUG: spaceName for card click reply: {}", spaceName);

    // 3. Process Action
    JsonNode parameters = commonEventObject.path("parameters");
    if (parameters.isMissingNode() && chatNode.has("buttonClickedPayload")) {
      parameters = chatNode.path("buttonClickedPayload").path("parameters");
    }

    boolean isUpdateMessage =
        ACTION_TYPE_UPDATE_MESSAGE.equals(parameters.path("action_type").asText());
    boolean isStaticSuggestionsSubmit =
        ACTION_KEY_STATIC_SUGGESTIONS_SUBMIT.equals(parameters.path("action_key").asText());
    boolean isPlatformSuggestionsSubmit =
        ACTION_KEY_PLATFORM_SUGGESTIONS_SUBMIT.equals(parameters.path("action_key").asText());
    boolean isAccessoryWidgetClick =
        ACTION_KEY_ACCESSORY_WIDGET_CLICK.equals(parameters.path("action_key").asText());
    boolean isGenericClick =
        ACTION_KEY_GENERIC_CLICK.equals(parameters.path("action_key").asText());

    // Check if it matches our expected function OR if we have valid parameters (fallback)
    boolean isActionMatch =
        ACTION_CARD_CLICK.equals(actionMethodName)
            || isUpdateMessage
            || isStaticSuggestionsSubmit
            || isPlatformSuggestionsSubmit
            || isAccessoryWidgetClick
            || isGenericClick;

    if (isActionMatch) {
      logger.info("DEBUG: Handling valid card action.");
      if (isUpdateMessage) {
        processUpdateMessageAction(chatNode, spaceName);
      } else if (isStaticSuggestionsSubmit) {
        processStaticSuggestionsSubmit(commonEventObject, spaceName);
      } else if (isPlatformSuggestionsSubmit) {
        processPlatformSuggestionsSubmit(commonEventObject, spaceName);
      } else if (isAccessoryWidgetClick) {
        reply(spaceName, null, "Accessory widget button clicked!");
      } else {
        // Default handling for other button clicks (e.g., generic click)
        reply(spaceName, null, "Button clicked! (Action: " + actionMethodName + ")");
      }
    } else {
      logger.warn(
          "DEBUG: Unhandled card action: {}. Expected: {}", actionMethodName, ACTION_CARD_CLICK);
      reply(spaceName, null, "Unknown card action: " + actionMethodName);
    }
    logger.info("handleCardClicked END");
  }

  private void processUpdateMessageAction(JsonNode chatNode, String spaceName) {
    String messageName = "";
    if (chatNode.has("buttonClickedPayload")
        && chatNode.path("buttonClickedPayload").has("message")) {
      messageName = chatNode.path("buttonClickedPayload").path("message").path("name").asText();
    }
    if (!messageName.isEmpty()) {
      updateMessage(messageName, "The message has been updated successfully!");
    } else {
      logger.error("Could not find message name to update.");
      reply(spaceName, null, "Error: Could not find message to update.");
    }
  }

  private void processStaticSuggestionsSubmit(JsonNode commonEventObject, String spaceName) {
    logger.info("Handling static suggestions submit.");
    JsonNode formInputs = commonEventObject.path("formInputs");
    StringBuilder selectedOptions = new StringBuilder();
    if (formInputs.has("static_selection_input")) {
      JsonNode inputNode =
          formInputs.path("static_selection_input").path("stringInputs").path("value");
      if (inputNode.isArray() && inputNode.size() > 0) {
        for (JsonNode node : inputNode) {
          if (selectedOptions.length() > 0) {
            selectedOptions.append(", ");
          }
          selectedOptions.append(node.asText());
        }
      } else {
        selectedOptions.append("None");
      }
    } else {
      selectedOptions.append("None");
    }
    reply(spaceName, null, "You selected: " + selectedOptions.toString());
  }

  private void processPlatformSuggestionsSubmit(JsonNode commonEventObject, String spaceName) {
    logger.info("Handling platform suggestions submit.");
    JsonNode formInputs = commonEventObject.path("formInputs");
    StringBuilder selectedUsers = new StringBuilder();
    if (formInputs.has("platform_selection_input")) {
      JsonNode inputNode =
          formInputs.path("platform_selection_input").path("stringInputs").path("value");
      if (inputNode.isArray() && inputNode.size() > 0) {
        for (JsonNode node : inputNode) {
          if (selectedUsers.length() > 0) {
            selectedUsers.append(", ");
          }
          selectedUsers.append(node.asText());
        }
      } else {
        selectedUsers.append("None");
      }
    } else {
      selectedUsers.append("None");
    }
    reply(spaceName, null, "You selected users: " + selectedUsers.toString());
  }

  // --- Helper Methods ---
  private void updateMessage(String messageName, String text) {
    if (chatServiceClient == null) {
      logger.error("ChatServiceClient not initialized.");
      return;
    }
    try {
      Message message = Message.newBuilder().setName(messageName).setText(text).build();
      UpdateMessageRequest request =
          UpdateMessageRequest.newBuilder()
              .setMessage(message)
              .setUpdateMask(FieldMask.newBuilder().addPaths("text").addPaths("cards_v2").build())
              .build();
      logger.info("Attempting to update message: {}", messageName);
      chatServiceClient.updateMessage(request);
      logger.info("Updated message: {}", messageName);
    } catch (Exception e) {
      logger.error("Failed to update message " + messageName, e);
    }
  }

  private void sendUpdateCard(String spaceName, String threadName) {
    if (chatServiceClient == null) {
      logger.error("ChatServiceClient not initialized.");
      return;
    }
    try {
      Button button =
          Button.newBuilder()
              .setText("Click to Update")
              .setOnClick(
                  OnClick.newBuilder()
                      .setAction(
                          Action.newBuilder()
                              .setFunction(ACTION_CARD_CLICK)
                              .addParameters(
                                  Action.ActionParameter.newBuilder()
                                      .setKey("action_type")
                                      .setValue("update_message"))))
              .build();

      Card card =
          Card.newBuilder()
              .setHeader(CardHeader.newBuilder().setTitle("Update Message Card"))
              .addSections(
                  Section.newBuilder()
                      .addWidgets(
                          Widget.newBuilder()
                              .setButtonList(ButtonList.newBuilder().addButtons(button))))
              .build();

      CardWithId cardWithId =
          CardWithId.newBuilder().setCardId("update-card-1").setCard(card).build();

      Message.Builder messageBuilder = Message.newBuilder().addCardsV2(cardWithId);
      if (threadName != null && !threadName.isEmpty()) {
        messageBuilder.setThread(Thread.newBuilder().setName(threadName));
      }
      Message messageToSend = messageBuilder.build();

      CreateMessageRequest request =
          CreateMessageRequest.newBuilder().setParent(spaceName).setMessage(messageToSend).build();
      logger.info("Attempting to send update card to {} (thread: {})", spaceName, threadName);
      chatServiceClient.createMessage(request);
      logger.info("Sent update card to {}", spaceName);
    } catch (Exception e) {
      logger.error("Failed to send update card to " + spaceName, e);
    }
  }

  private void reply(String spaceName, String threadName, String text) {
    // ... (rest of the method as before)
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
                              .addParameters(
                                  Action.ActionParameter.newBuilder()
                                      .setKey("action_key")
                                      .setValue("action_value"))))
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
      Message messageToSend = messageBuilder.build();

      // *** ADDED LOGGING FOR OUTGOING CARD JSON ***
      try {
        // Use JsonFormat to convert the Message proto to JSON
        String messageJson = JsonFormat.printer().print(messageToSend);
        logger.info("DEBUG: Outgoing Message with Card JSON: {}", messageJson);
      } catch (Exception e) {
        logger.warn("DEBUG: Failed to serialize outgoing message to JSON for logging", e);
        logger.info("DEBUG: Outgoing Message with Card (Proto): {}", messageToSend.toString());
      }
      // *** END ADDED LOGGING ***

      CreateMessageRequest request =
          CreateMessageRequest.newBuilder().setParent(spaceName).setMessage(messageToSend).build();
      logger.info("Attempting to send card to {} (thread: {})", spaceName, threadName);
      chatServiceClient.createMessage(request);
      logger.info("Sent card with button to {}", spaceName);
    } catch (Exception e) {
      logger.error("Failed to send card to " + spaceName, e);
    }
  }

  private void sendStaticSuggestionsCard(String spaceName, String threadName) {
    if (chatServiceClient == null) {
      logger.error("ChatServiceClient not initialized.");
      return;
    }
    try {
      // Create SelectionInput
      SelectionInput selectionInput =
          SelectionInput.newBuilder()
              .setName("static_selection_input")
              .setLabel("Static Suggestions Input")
              .setType(
                  SelectionInput.SelectionType.MULTI_SELECT) // Stick to MULTI_SELECT as requested
              .addItems(
                  SelectionInput.SelectionItem.newBuilder()
                      .setText("Option 1")
                      .setValue("option_1"))
              .addItems(
                  SelectionInput.SelectionItem.newBuilder()
                      .setText("Option 2")
                      .setValue("option_2"))
              .addItems(
                  SelectionInput.SelectionItem.newBuilder()
                      .setText("Option 3")
                      .setValue("option_3"))
              .build();

      // Create a Button to submit the form (or just act as an action trigger)
      Button submitButton =
          Button.newBuilder()
              .setText("Submit")
              .setOnClick(
                  OnClick.newBuilder()
                      .setAction(
                          Action.newBuilder()
                              .setFunction(ACTION_CARD_CLICK)
                              .addParameters(
                                  Action.ActionParameter.newBuilder()
                                      .setKey("action_key")
                                      .setValue("static_suggestions_submit"))))
              .build();

      Card card =
          Card.newBuilder()
              .setHeader(CardHeader.newBuilder().setTitle("Static Suggestions Card"))
              .addSections(
                  Section.newBuilder()
                      .addWidgets(Widget.newBuilder().setSelectionInput(selectionInput))
                      .addWidgets(
                          Widget.newBuilder()
                              .setButtonList(ButtonList.newBuilder().addButtons(submitButton))))
              .build();

      CardWithId cardWithId =
          CardWithId.newBuilder().setCardId("static-suggestions-card").setCard(card).build();

      Message.Builder messageBuilder = Message.newBuilder().addCardsV2(cardWithId);
      if (threadName != null && !threadName.isEmpty()) {
        messageBuilder.setThread(Thread.newBuilder().setName(threadName));
      }
      Message messageToSend = messageBuilder.build();

      CreateMessageRequest request =
          CreateMessageRequest.newBuilder().setParent(spaceName).setMessage(messageToSend).build();
      logger.info(
          "Attempting to send static suggestions card to {} (thread: {})", spaceName, threadName);
      chatServiceClient.createMessage(request);
      logger.info("Sent static suggestions card to {}", spaceName);
    } catch (Exception e) {
      logger.error("Failed to send static suggestions card to " + spaceName, e);
    }
  }

  private void sendPlatformSuggestionsCard(String spaceName, String threadName) {
    if (chatServiceClient == null) {
      logger.error("ChatServiceClient not initialized.");
      return;
    }
    try {
      // Create SelectionInput with Platform Data Source (Users)
      SelectionInput selectionInput =
          SelectionInput.newBuilder()
              .setName("platform_selection_input")
              .setLabel("Platform Suggestions (Users)")
              .setType(SelectionInput.SelectionType.MULTI_SELECT)
              .setMultiSelectMaxSelectedItems(3)
              .setPlatformDataSource(
                  SelectionInput.PlatformDataSource.newBuilder()
                      .setCommonDataSource(SelectionInput.PlatformDataSource.CommonDataSource.USER))
              .build();

      // Create a Button to submit the form
      Button submitButton =
          Button.newBuilder()
              .setText("Submit")
              .setOnClick(
                  OnClick.newBuilder()
                      .setAction(
                          Action.newBuilder()
                              .setFunction(ACTION_CARD_CLICK)
                              .addParameters(
                                  Action.ActionParameter.newBuilder()
                                      .setKey("action_key")
                                      .setValue("platform_suggestions_submit"))))
              .build();

      Card card =
          Card.newBuilder()
              .setHeader(CardHeader.newBuilder().setTitle("Platform Suggestions Card"))
              .addSections(
                  Section.newBuilder()
                      .addWidgets(Widget.newBuilder().setSelectionInput(selectionInput))
                      .addWidgets(
                          Widget.newBuilder()
                              .setButtonList(ButtonList.newBuilder().addButtons(submitButton))))
              .build();

      CardWithId cardWithId =
          CardWithId.newBuilder().setCardId("platform-suggestions-card").setCard(card).build();

      Message.Builder messageBuilder = Message.newBuilder().addCardsV2(cardWithId);
      if (threadName != null && !threadName.isEmpty()) {
        messageBuilder.setThread(Thread.newBuilder().setName(threadName));
      }
      Message messageToSend = messageBuilder.build();

      CreateMessageRequest request =
          CreateMessageRequest.newBuilder().setParent(spaceName).setMessage(messageToSend).build();
      logger.info(
          "Attempting to send platform suggestions card to {} (thread: {})", spaceName, threadName);
      chatServiceClient.createMessage(request);
      logger.info("Sent platform suggestions card to {}", spaceName);
    } catch (Exception e) {
      logger.error("Failed to send platform suggestions card to " + spaceName, e);
    }
  }

  private void sendAccessoryWidgetCard(String spaceName, String threadName) {
    if (chatServiceClient == null) {
      logger.error("ChatServiceClient not initialized.");
      return;
    }
    try {
      Button button =
          Button.newBuilder()
              .setText("Accessory Button")
              .setOnClick(
                  OnClick.newBuilder()
                      .setAction(
                          Action.newBuilder()
                              .setFunction(ACTION_CARD_CLICK)
                              .addParameters(
                                  Action.ActionParameter.newBuilder()
                                      .setKey("action_key")
                                      .setValue(ACTION_KEY_ACCESSORY_WIDGET_CLICK))))
              .build();

      DecoratedText decoratedText =
          DecoratedText.newBuilder()
              .setText("This is a DecoratedText widget with an accessory button.")
              .setButton(button)
              .build();

      Card card =
          Card.newBuilder()
              .setHeader(CardHeader.newBuilder().setTitle("Accessory Widget Card"))
              .addSections(
                  Section.newBuilder()
                      .addWidgets(Widget.newBuilder().setDecoratedText(decoratedText)))
              .build();

      CardWithId cardWithId =
          CardWithId.newBuilder().setCardId("accessory-widget-card").setCard(card).build();

      Message.Builder messageBuilder = Message.newBuilder().addCardsV2(cardWithId);
      if (threadName != null && !threadName.isEmpty()) {
        messageBuilder.setThread(Thread.newBuilder().setName(threadName));
      }
      Message messageToSend = messageBuilder.build();

      CreateMessageRequest request =
          CreateMessageRequest.newBuilder().setParent(spaceName).setMessage(messageToSend).build();
      logger.info(
          "Attempting to send accessory widget card to {} (thread: {})", spaceName, threadName);
      chatServiceClient.createMessage(request);
      logger.info("Sent accessory widget card to {}", spaceName);
    } catch (Exception e) {
      logger.error("Failed to send accessory widget card to " + spaceName, e);
    }
  }
}
