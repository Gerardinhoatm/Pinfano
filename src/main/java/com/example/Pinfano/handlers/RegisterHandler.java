package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
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

public class RegisterHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String userTable = System.getenv().getOrDefault("USERS_TABLE", "PinfanoUsers");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(Map.of(
                            "Access-Control-Allow-Origin", "*",
                            "Access-Control-Allow-Methods", "POST, OPTIONS",
                            "Access-Control-Allow-Headers", "*"
                    ));
        }

        try {
            JsonNode body = objectMapper.readTree(request.getBody());
            String username = body.get("username").asText();
            String password = body.get("password").asText();
            String name = body.has("name") ? body.get("name").asText() : "";
            String email = body.has("email") ? body.get("email").asText() : "";
            int age = body.has("age") ? body.get("age").asInt() : 0;
            int rango = body.has("rango") ? body.get("rango").asInt() : 0;
            Boolean logIn = body.has("logIn") ? body.get("logIn").asBoolean(): false;
            JsonNode numbersNode = body.has("numbers") ? body.get("numbers") : objectMapper.createArrayNode();


            Table table = dynamoDB.getTable(userTable);

            if (table.getItem("username", username) != null) {
                return createResponse(400, "El usuario ya existe.");
            }

            ScanSpec scanSpec = new ScanSpec()
                    .withFilterExpression("email = :emailVal")
                    .withValueMap(Map.of(":emailVal", email));

            ItemCollection<ScanOutcome> items = table.scan(scanSpec);

            if (items.iterator().hasNext()) {
                return createResponse(400, "El email ya está registrado.");
            }

            String passwordHash = hashPassword(password);

            table.putItem(new Item()
                    .withPrimaryKey("username", username)
                    .withString("passwordHash", passwordHash)
                    .withString("name", name)
                    .withString("email", email)
                    .withInt("age", age)
                    .withInt("rango", rango)
                    .withBoolean("logIn", logIn)
                    .withList("numbers", objectMapper.convertValue(numbersNode, java.util.List.class)));

            return createResponse(200, "Usuario registrado correctamente.");

        } catch (Exception e) {
            context.getLogger().log("Error en registro: " + e.getMessage());
            return createResponse(500, "Error interno.");
        }
    }

    private String hashPassword(String password) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*",
                        "Access-Control-Allow-Headers", "*",
                        "Access-Control-Allow-Methods", "OPTIONS,POST"))
                .withBody("{\"message\":\"" + body + "\"}");
    }
}
