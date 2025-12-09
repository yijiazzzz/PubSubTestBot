package com.google.chat.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
  public static void main(String[] args) {
    System.out.println("Starting Application...");
    try {
      String port = System.getenv("PORT");
      if (port == null) {
        port = "8080";
      }
      System.out.println("Attempting to start on port: " + port);
      System.setProperty("server.port", port);
      SpringApplication.run(Application.class, args);
      System.out.println("Application started successfully.");
    } catch (Throwable t) {
      System.err.println("Application startup failed:");
      t.printStackTrace();
      // Ensure the process exits on failure, which might help Cloud Run detect the issue faster.
      System.exit(1);
    }
  }
}
