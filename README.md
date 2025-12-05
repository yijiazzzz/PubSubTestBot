# PubSubTestBot (Cloud Run)

This repository contains a Google Chat bot implemented in Java using Spring Boot, designed to run on Google Cloud Run. It receives Chat events via Cloud Pub/Sub push messages.

## Prerequisites

1.  **Google Cloud Project**: You need a GCP project.
2.  **APIs**: Enable the **Google Chat API** and **Cloud Pub/Sub API**.
3.  **Service Account**: The Cloud Run service will use the default compute service account or a user-managed one. Ensure it has permissions to call the Chat API.

## Architecture

1.  **Google Chat** sends an event (e.g., message received) to a **Cloud Pub/Sub Topic**.
2.  **Cloud Pub/Sub** pushes the message to the **Cloud Run** service endpoint.
3.  The **Cloud Run** service processes the message and posts a reply back to Google Chat using the client library.

## Setup & Deployment

### 1. Create Pub/Sub Topic and Subscription

Create a topic (e.g., `chat-events`) and a subscription.

```bash
gcloud pubsub topics create chat-events
```

### 2. Deploy to Cloud Run

You can deploy directly from the source code:

```bash
gcloud run deploy pubsub-test-bot \
  --source . \
  --region us-central1 \
  --allow-unauthenticated
```

*   `--allow-unauthenticated`: Required for Pub/Sub to push messages to the endpoint without additional auth configuration (for simplicity). For production, configure Pub/Sub to use an authenticated push subscription.

### Alternative: Deploy via Cloud Build (CI/CD)

If you are setting up **Continuous Deployment** from GitHub:

1.  **Build Type**: Select **Dockerfile**.
2.  **Source location**: Enter `/Dockerfile` (or just leave the default if it detects it).
    *   This path indicates that the `Dockerfile` is located at the root of your repository.
    *   The Docker build context will implicitly be the directory containing the Dockerfile (the root).

After deployment, note the **Service URL** (e.g., `https://pubsub-test-bot-xyz-uc.a.run.app`).

### 3. Configure Pub/Sub Subscription

Create a push subscription that points to your Cloud Run service.

```bash
gcloud pubsub subscriptions create chat-events-sub \
  --topic=chat-events \
  --push-endpoint=[YOUR_SERVICE_URL]
```

### 4. Configure Google Chat API

1.  Go to the **Google Chat API** page in the Google Cloud Console.
2.  Click **Manage** -> **Configuration**.
3.  Under **Connection settings**, select **Cloud Pub/Sub**.
4.  In the **Topic** field, enter the full topic name (e.g., `projects/YOUR_PROJECT_ID/topics/chat-events`).
5.  Save the configuration.

## Local Development

To run the application locally:

```bash
mvn spring-boot:run
```

The server will start on port 8080.

### Testing Locally

You can simulate a Pub/Sub push message using curl:

```bash
# Create a dummy payload
# The payload mimics the structure { "message": { "data": "BASE64_ENCODED_JSON" } }
curl -X POST http://localhost:8080/ \
  -H "Content-Type: application/json" \
  -d '{
        "message": {
          "data": "eyJ0eXBlIjoiTUVTU0FHRSIsInNwYWNlIjp7Im5hbWUiOiJzcGFjZXMvQUFBQSJ9LCJtZXNzYWdlIjp7InRleHQiOiJIZWxsbyJ9LCJ1c2VyIjp7ImRpc3BsYXlOYW1lIjoiVGVzdGVyIn19"
        }
      }'
```
*(The data above is a base64 encoded JSON for a simple MESSAGE event)*
