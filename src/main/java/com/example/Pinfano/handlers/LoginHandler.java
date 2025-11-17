package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.UpdateItemOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

public class LoginHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String userTable = System.getenv().getOrDefault("USERS_TABLE", "PinfanoUsers");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            JsonNode body = objectMapper.readTree(request.getBody());
            String username = body.get("username").asText();
            String password = body.get("password").asText();

            if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
                return createResponse(400, "Username or password missing");
            }

            Table table = dynamoDB.getTable(userTable);
            Item userItem = table.getItem("username", username);

            if (userItem == null) {
                return createResponse(401, "User not found");
            }

            String storedHash = userItem.getString("passwordHash");
            String inputHash = hashPassword(password);

            if (!storedHash.equals(inputHash)) {
                return createResponse(401, "Invalid credentials");
            }

            // ✅ Si las credenciales son correctas, actualizar el campo logIn a true
            UpdateItemSpec updateSpec = new UpdateItemSpec()
                    .withPrimaryKey("username", username)
                    .withUpdateExpression("set logIn = :val")
                    .withValueMap(Map.of(":val", true));

            UpdateItemOutcome outcome = table.updateItem(updateSpec);
            context.getLogger().log("✅ Usuario " + username + " logueado correctamente. Campo logIn actualizado a true.");

            return createResponse(200, "Login successful");

        } catch (Exception e) {
            context.getLogger().log("❌ Error LoginHandler REST: " + e.getMessage());
            return createResponse(500, "Internal server error");
        }
    }

    private String hashPassword(String password) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*",
                        "Access-Control-Allow-Headers", "*",
                        "Access-Control-Allow-Methods", "OPTIONS,POST,PATCH"))
                .withBody("{\"message\":\"" + message + "\"}");
    }
}
