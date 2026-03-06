package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

public class JoinGameHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String gamesTable = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(Map.of(
                            "Access-Control-Allow-Origin", "*",
                            "Access-Control-Allow-Methods", "POST,OPTIONS",
                            "Access-Control-Allow-Headers", "*"
                    ));
        }

        context.getLogger().log("JoinGame ejecutado");

        try {

            JsonNode body = objectMapper.readTree(request.getBody());
            String username = body.get("username").asText();
            String codigoGame = body.get("codigoGame").asText();
            int posicionSeleccionada = body.get("posicionSeleccionada").asInt();

            Table table = dynamoDB.getTable(gamesTable);

            // 🔍 Buscar partida por codigoGame con SCAN
            ScanSpec scanSpec = new ScanSpec()
                    .withFilterExpression("codigoGame = :cg")
                    .withValueMap(new ValueMap().withString(":cg", codigoGame));

            ItemCollection<ScanOutcome> items = table.scan(scanSpec);

            Item gameItem = null;

            for (Item item : items) {
                gameItem = item;
                break;
            }

            if (gameItem == null) {
                return createResponse(404, "Partida no encontrada");
            }

            String idGame = gameItem.getString("idGame");

            // === listaPlayers ===
            ArrayNode listaPlayersNode = (ArrayNode) objectMapper.readTree(
                    objectMapper.writeValueAsString(gameItem.getList("listaPlayers"))
            );

            if (posicionSeleccionada < 1 || posicionSeleccionada > listaPlayersNode.size()) {
                return createResponse(400, "Posición inválida");
            }

            ObjectNode jugadorNode = (ObjectNode) listaPlayersNode.get(posicionSeleccionada - 1);
            jugadorNode.put("username", username);

            // === json ===
            String jsonStr = gameItem.getString("json");
            ObjectNode jsonNode = (ObjectNode) objectMapper.readTree(jsonStr);

            String jugadorKey = "jugador" + posicionSeleccionada;
            jsonNode.put(jugadorKey, username);

            // === Update DynamoDB ===
            UpdateItemSpec updateItemSpec = new UpdateItemSpec()
                    .withPrimaryKey("idGame", idGame)
                    .withUpdateExpression("set listaPlayers = :lp, #js = :jsonVal")
                    .withNameMap(Map.of("#js", "json"))
                    .withValueMap(Map.of(
                            ":lp", objectMapper.convertValue(listaPlayersNode, java.util.List.class),
                            ":jsonVal", jsonNode.toString()
                    ))
                    .withReturnValues(ReturnValue.UPDATED_NEW);

            table.updateItem(updateItemSpec);

            return createResponse(200, "Jugador agregado correctamente");

        } catch (Exception e) {
            context.getLogger().log("Error en JoinGameHandler: " + e.getMessage());
            return createResponse(500, "Error interno: " + e.getMessage());
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