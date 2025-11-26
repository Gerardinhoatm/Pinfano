package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class TableGameHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);

    // Nombre tabla → configurable como variable Lambda
    private final String gameStatusTable = System.getenv().getOrDefault("GAMESTATUS_TABLE", "gameStatus");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            JsonNode body = mapper.readTree(request.getBody());

            String idGame = body.get("idGame").asText();

            if (idGame == null || idGame.isEmpty()) {
                return createResponse(400, "idGame missing");
            }

            Table table = dynamoDB.getTable(gameStatusTable);

            // Crear item DynamoDB
            Item item = new Item()
                    .withPrimaryKey("idGame", idGame)
                    .withNumber("pointsA", body.get("pointsA").asInt())
                    .withNumber("pointsB", body.get("pointsB").asInt())
                    .withNumber("turno", body.get("turno").asInt())
                    .withNumber("initPlayer", body.get("initPlayer").asInt())
                    .withNumber("winnerPoints", body.get("winnerPoints").asInt())
                    .with("pinfano", mapper.convertValue(body.get("pinfano"), Object.class))
                    .with("board", mapper.convertValue(body.get("board"), Object.class))
                    .withBoolean("status", body.get("status").asBoolean())
                    .with("valuesExtremes", mapper.convertValue(body.get("valuesExtremes"), Object.class));

            table.putItem(item);

            context.getLogger().log("✅ gameStatus creado correctamente: " + idGame);

            return createResponse(200, "gameStatus created");

        } catch (Exception e) {
            context.getLogger().log("❌ Error en tableGameHandler: " + e.getMessage());
            return createResponse(500, "Internal server error");
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String message) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*",
                        "Access-Control-Allow-Headers", "*",
                        "Access-Control-Allow-Methods", "OPTIONS,POST"))
                .withBody("{\"message\":\"" + message + "\"}");
    }
}
