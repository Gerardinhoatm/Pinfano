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

        context.getLogger().log("[CONTINUE] ---- INICIO CONTINUE GAME HANDLER ----\n");

        try {
            // --- Leer body ---
            context.getLogger().log("[CONTINUE] Body recibido: " + request.getBody() + "\n");

            JsonNode body = objectMapper.readTree(request.getBody());
            String username = body.get("username").asText();
            String codigoGame = body.get("codigoGame").asText();

            context.getLogger().log("[CONTINUE] username recibido: " + username + "\n");
            context.getLogger().log("[CONTINUE] codigoGame recibido: " + codigoGame + "\n");

            // --- Buscar partida por codigoGame ---
            Table table = dynamoDB.getTable(gamesTable);
            context.getLogger().log("[CONTINUE] Usando tabla: " + gamesTable + "\n");

            ScanSpec scan = new ScanSpec()
                    .withFilterExpression("codigoGame = :v")
                    .withValueMap(new ValueMap().withString(":v", codigoGame));

            context.getLogger().log("[CONTINUE] Ejecutando scan sobre codigoGame ...\n");

            Iterator<Item> iterator = table.scan(scan).iterator();

            if (!iterator.hasNext()) {
                context.getLogger().log("[CONTINUE] ERROR: No se encontró ninguna partida con código " + codigoGame + "\n");
                return createResponse(200,
                        "{\"success\":false, \"reason\":\"Partida no encontrada\"}");
            }

            Item game = iterator.next();
            context.getLogger().log("[CONTINUE] Partida encontrada: " + game.toJSON() + "\n");

            // --- Sacar listaPlayers ---
            List<Object> players = game.getList("listaPlayers");
            context.getLogger().log("[CONTINUE] listaPlayers: " + players + "\n");

            // Identificar qué jugador soy yo
            int playerIndex = -1;
            for (int i = 0; i < players.size(); i++) {
                Object p = players.get(i);
                context.getLogger().log("[CONTINUE] Revisando jugador[" + i + "]: " + p + "\n");
                if (p instanceof String && p.equals(username)) {
                    playerIndex = i;
                    context.getLogger().log("[CONTINUE] MATCH → El jugador es índice de array: " + i + "\n");
                    break;
                }
            }

            if (playerIndex == -1) {
                context.getLogger().log("[CONTINUE] ERROR: El jugador " + username + " no está en esta partida\n");
                return createResponse(200,
                        "{\"success\":false, \"reason\":\"El jugador no está en esta partida\"}");
            }

            // --- Sacar turno desde json interno ---
            Map<String, Object> jsonData = game.getMap("json");
            int turno = 0;
            if (jsonData != null && jsonData.get("turno") != null) {
                turno = ((Number) jsonData.get("turno")).intValue();
            }
            context.getLogger().log("[CONTINUE] Turno actual de la partida: " + turno + "\n");

            // --- Convertir playerIndex a 1-based para tu lógica de juego ---
            int playerNumber = playerIndex + 1;
            context.getLogger().log("[CONTINUE] playerNumber (1-4): " + playerNumber + "\n");

            // --- Respuesta final ---
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("codigoGame", codigoGame);
            response.put("jsonGame", game.asMap());
            response.put("playerIndex", playerNumber);
            response.put("turno", turno);

            String jsonResponse = objectMapper.writeValueAsString(response);

            context.getLogger().log("[CONTINUE] RESPUESTA FINAL: " + jsonResponse + "\n");
            context.getLogger().log("[CONTINUE] ---- FIN CONTINUE GAME HANDLER ----\n");

            return createResponse(200, jsonResponse);

        } catch (Exception e) {
            context.getLogger().log("[CONTINUE] EXCEPCIÓN: " + e.getMessage() + "\n");
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