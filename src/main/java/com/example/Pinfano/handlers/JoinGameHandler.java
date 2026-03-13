package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
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

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class JoinGameHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String gamesTable = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");
    private final String addGameUserAPI = System.getenv().getOrDefault("ADD_GAME_USER_API",
            "https://8806xqh414.execute-api.eu-central-1.amazonaws.com/Prod/addGameToUser");
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------
    // Normalizar JSON interno
    // ------------------------------
    private ObjectNode normalizeJson(ObjectNode jsonNode) {
        ObjectNode fixed = objectMapper.createObjectNode();

        jsonNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();

            // DynamoDB-style: {"S": "valor"}
            if (value.isObject() && value.has("S")) {
                fixed.put(key, value.get("S").asText());
            } else {
                fixed.set(key, value);
            }
        });

        return fixed;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        try {
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

            // Leer body del request
            JsonNode body = objectMapper.readTree(request.getBody());
            String username = body.get("username").asText();
            String codigoGame = body.get("codigoGame").asText();
            int posicion = body.get("posicionSeleccionada").asInt();

            context.getLogger().log("Join request: user=" + username + " código=" + codigoGame + " pos=" + posicion);

            Table table = dynamoDB.getTable(gamesTable);

            // Buscar partida por códigoGame
            ItemCollection<ScanOutcome> items = table.scan(new ScanSpec()
                    .withFilterExpression("codigoGame = :cg")
                    .withValueMap(new ValueMap().withString(":cg", codigoGame)));

            Item gameItem = null;
            for (Item item : items) {
                gameItem = item;
                break;
            }

            if (gameItem == null) {
                return response(404, "Partida no encontrada");
            }

            String idGame = gameItem.getString("idGame");

            // ------------------------------
            // Normalizar listaPlayers
            // ------------------------------
            List<Object> rawList = gameItem.getList("listaPlayers");
            List<String> listaPlayers = new ArrayList<>();
            for (Object o : rawList) {
                if (o instanceof String) {
                    listaPlayers.add((String) o);
                } else if (o instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) o;
                    listaPlayers.add((String) map.get("S"));
                }
            }

            context.getLogger().log("LISTA NORMALIZADA: " + listaPlayers);

            if (posicion < 1 || posicion > listaPlayers.size()) {
                return response(400, "Posición inválida");
            }

            // Insertar username en listaPlayers
            listaPlayers.set(posicion - 1, username);

            // ------------------------------
            // Normalizar JSON interno
            // ------------------------------
            Object jsonAttr = gameItem.get("json");
            ObjectNode jsonNode;
            if (jsonAttr instanceof String) {
                jsonNode = (ObjectNode) objectMapper.readTree((String) jsonAttr);
            } else if (jsonAttr instanceof Map) {
                String jsonStr = objectMapper.writeValueAsString(jsonAttr);
                jsonNode = (ObjectNode) objectMapper.readTree(jsonStr);
            } else {
                throw new RuntimeException("Formato inesperado para campo 'json'");
            }

            jsonNode = normalizeJson(jsonNode);

            // Actualizar jugador en JSON
            String jugadorKey = "jugador" + posicion;
            jsonNode.put(jugadorKey, username);

            // Actualizar listaPlayers también dentro del JSON
            ArrayNode listaPlayersJson = objectMapper.createArrayNode();
            for (String s : listaPlayers) {
                listaPlayersJson.add(s);
            }
            jsonNode.set("listaPlayers", listaPlayersJson);

            // ------------------------------
            // Estado de partida
            // ------------------------------
            boolean hayVacio = listaPlayers.contains("VACIO");
            String estadoDB = hayVacio ? "P" : "A";
            String estadoPartida = hayVacio ? "pendiente" : "lleno";

            // ------------------------------
            // Actualizar DynamoDB
            // ------------------------------
            UpdateItemSpec updateItemSpec = new UpdateItemSpec()
                    .withPrimaryKey("idGame", idGame)
                    .withUpdateExpression("set listaPlayers = :lp, #js = :jsonVal, estado = :estadoVal")
                    .withNameMap(Map.of("#js", "json"))
                    .withValueMap(Map.of(
                            ":lp", listaPlayers,
                            ":jsonVal", jsonNode.toString(),
                            ":estadoVal", estadoDB
                    ))
                    .withReturnValues(ReturnValue.UPDATED_NEW);

            table.updateItem(updateItemSpec);

            // ------------------------------
            // Añadir juego al usuario
            // ------------------------------
            try {
                URL url = new URL(addGameUserAPI);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                ObjectNode bodyAddGame = objectMapper.createObjectNode();
                bodyAddGame.put("username", username);
                bodyAddGame.put("codigoGame", codigoGame);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = bodyAddGame.toString().getBytes("utf-8");
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                context.getLogger().log("AddGameToUser response code: " + code);

            } catch (Exception e) {
                context.getLogger().log("Error llamando AddGameToUser: " + e.getMessage());
            }

            // ------------------------------
            // RESPUESTA FINAL PARA ANDROID
            // ------------------------------
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
            context.getLogger().log("Error en JoinGameHandler: " + e);
            return response(500, "Error interno: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent response(int code, String msg) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(code)
                .withHeaders(Map.of("Access-Control-Allow-Origin", "*"))
                .withBody("{\"message\":\"" + msg + "\"}");
    }
}