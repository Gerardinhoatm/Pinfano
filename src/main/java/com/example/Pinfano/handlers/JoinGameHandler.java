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
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class JoinGameHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String gamesTable = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        // Preflight CORS
        if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(Map.of(
                            "Access-Control-Allow-Origin", "*",
                            "Access-Control-Allow-Methods", "POST,OPTIONS",
                            "Access-Control-Allow-Headers", "*"
                    ));
        }

        try {
            JsonNode body = objectMapper.readTree(request.getBody());
            String username = body.get("username").asText();
            String codigoGame = body.get("codigoGame").asText();
            int posicionSeleccionada = body.get("posicionSeleccionada").asInt();

            Table table = dynamoDB.getTable(gamesTable);

            // Buscar partida por código
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

            // ======================================================
            // NORMALIZAR listaPlayers (convertir posibles LinkedHashMap → String)
            // ======================================================
            List<Object> rawList = gameItem.getList("listaPlayers");
            List<String> listaPlayers = new ArrayList<>();

            for (Object o : rawList) {
                if (o instanceof String) {
                    listaPlayers.add((String) o);
                } else if (o instanceof Map) {
                    // DynamoDB lo envía como {S=valor}
                    Map<String, Object> map = (Map<String, Object>) o;
                    listaPlayers.add((String) map.get("S"));
                }
            }

            context.getLogger().log("LISTA NORMALIZADA: " + listaPlayers);

            // Validar posición
            if (posicionSeleccionada < 1 || posicionSeleccionada > listaPlayers.size()) {
                return createResponse(400, "Posición inválida");
            }

            // Actualizar posición del jugador
            listaPlayers.set(posicionSeleccionada - 1, username);

            // Lista final que se guarda en DynamoDB
            List<String> listaPlayersString = new ArrayList<>(listaPlayers);

            // ======================================================
            // Actualizar JSON interno de jugadores
            // ======================================================
            String jsonStr = gameItem.getString("json");
            ObjectNode jsonNode = (ObjectNode) objectMapper.readTree(jsonStr);

            String jugadorKey = "jugador" + posicionSeleccionada;
            jsonNode.put(jugadorKey, username);

            // ======================================================
            // Estado de partida
            // ======================================================
            boolean hayVacio = listaPlayersString.contains("VACIO");
            String estadoDB = hayVacio ? "P" : "A";
            String estadoPartida = hayVacio ? "pendiente" : "lleno";

            // ======================================================
            // Actualizar DynamoDB
            // ======================================================
            UpdateItemSpec updateItemSpec = new UpdateItemSpec()
                    .withPrimaryKey("idGame", idGame)
                    .withUpdateExpression("set listaPlayers = :lp, #js = :jsonVal, estado = :estadoVal")
                    .withNameMap(Map.of("#js", "json"))
                    .withValueMap(Map.of(
                            ":lp", listaPlayersString,
                            ":jsonVal", jsonNode.toString(),
                            ":estadoVal", estadoDB
                    ))
                    .withReturnValues(ReturnValue.UPDATED_NEW);

            table.updateItem(updateItemSpec);

            // ======================================================
            // Respuesta al Activity Android
            // ======================================================
            ObjectNode responseNode = objectMapper.createObjectNode();
            responseNode.put("estadoPartida", estadoPartida);
            responseNode.set("json", jsonNode);

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(Map.of(
                            "Content-Type", "application/json",
                            "Access-Control-Allow-Origin", "*"
                    ))
                    .withBody(responseNode.toString());

        } catch (Exception e) {
            context.getLogger().log("Error en JoinGameHandler: " + e.getMessage());
            return createResponse(500, "Error interno: " + e.getMessage());
        }
    }

    // Respuesta rápida
    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*"
                ))
                .withBody("{\"message\":\"" + body + "\"}");
    }
}