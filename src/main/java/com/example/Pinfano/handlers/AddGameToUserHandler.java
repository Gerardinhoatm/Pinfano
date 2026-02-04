package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

public class AddGameToUserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

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
            String codigoGame = body.get("codigoGame").asText();

            Table table = dynamoDB.getTable(userTable);
            Item user = table.getItem("username", username);

            if (user == null) {
                return createResponse(404, "Usuario no encontrado.");
            }

            List<String> games = user.getList("games");
            if (games == null) games = new ArrayList<>();

            if (!games.contains(codigoGame)) {
                games.add(codigoGame);

                UpdateItemSpec updateSpec = new UpdateItemSpec()
                        .withPrimaryKey("username", username)
                        .withUpdateExpression("set games = :g")
                        .withValueMap(new ValueMap().withList(":g", games));

                table.updateItem(updateSpec);
            }

            return createResponse(200, "Partida añadida correctamente al usuario.");

        } catch (Exception e) {
            context.getLogger().log("Error en AddGameToUser: " + e.getMessage());
            return createResponse(500, "Error interno.");
        }
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
