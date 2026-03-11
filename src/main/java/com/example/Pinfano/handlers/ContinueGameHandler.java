package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

public class ContinueGameHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String gamesTable = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        try {
            // --- Leer body ---
            JsonNode body = objectMapper.readTree(request.getBody());
            String username = body.get("username").asText();
            String codigoGame = body.get("codigoGame").asText();

            // --- Buscar partida por codigoGame ---
            Table table = dynamoDB.getTable(gamesTable);

            ScanSpec scan = new ScanSpec()
                    .withFilterExpression("codigoGame = :v")
                    .withValueMap(new ValueMap().withString(":v", codigoGame));

            Iterator<Item> iterator = table.scan(scan).iterator();

            if (!iterator.hasNext()) {
                return createResponse(200,
                        "{\"success\":false, \"reason\":\"Partida no encontrada\"}");
            }

            Item game = iterator.next();

            // Convertimos el item completo a JSON
            String jsonGame = game.toJSON();

            // --- Sacar listaPlayers ---
            List<Object> players = game.getList("listaPlayers");

            // Identificar qué jugador soy yo
            int playerIndex = -1;

            for (int i = 0; i < players.size(); i++) {
                Object p = players.get(i);
                if (p instanceof String && p.equals(username)) {
                    playerIndex = i;  // índice REAL del jugador
                    break;
                }
            }

            if (playerIndex == -1) {
                return createResponse(200,
                        "{\"success\":false, \"reason\":\"El jugador no está en esta partida\"}");
            }

            // --- Sacar turno ---
            int turno = game.getInt("turno");

            // --- Respuesta final ---
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("codigoGame", codigoGame);
            response.put("jsonGame", objectMapper.readValue(jsonGame, Map.class));
            response.put("playerIndex", playerIndex);
            response.put("turno", turno);

            String jsonResponse = objectMapper.writeValueAsString(response);

            return createResponse(200, jsonResponse);

        } catch (Exception e) {
            return createResponse(200,
                    "{\"success\":false, \"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int status, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*",
                        "Access-Control-Allow-Headers", "*",
                        "Access-Control-Allow-Methods", "*"
                ))
                .withBody(body);
    }
}
