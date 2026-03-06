package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.GetItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Map;

public class JoinGameHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String gamesTable = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        // CORS
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
            int posicionSeleccionada = body.get("posicionSeleccionada").asInt();

            Table table = dynamoDB.getTable(gamesTable);

            // Obtener partida
            Item gameItem = table.getItem(new GetItemSpec().withPrimaryKey("codigoGame", codigoGame));
            if (gameItem == null) {
                return createResponse(404, "Partida no encontrada.");
            }

            // === 1️⃣ Actualizar listaPlayers ===
            // ListaPlayers está como [{ "S": "gerar" }, { "S": "bot" }, ...]
            ArrayNode listaPlayersNode = (ArrayNode) objectMapper.readTree(objectMapper.writeValueAsString(gameItem.getList("listaPlayers")));
            if (posicionSeleccionada < 1 || posicionSeleccionada > listaPlayersNode.size()) {
                return createResponse(400, "Posición inválida.");
            }

            // Reemplazamos la posición seleccionada por el username
            ObjectNode jugadorNode = (ObjectNode) listaPlayersNode.get(posicionSeleccionada - 1);
            jugadorNode.put("S", username);

            // === 2️⃣ Actualizar json ===
            String jsonStr = gameItem.getString("json");
            ObjectNode jsonNode = (ObjectNode) objectMapper.readTree(jsonStr);
            String jugadorKey = "jugador" + posicionSeleccionada;
            jsonNode.put(jugadorKey, username);

            // === 3️⃣ Guardar cambios en DynamoDB ===
            UpdateItemSpec updateItemSpec = new UpdateItemSpec()
                    .withPrimaryKey("codigoGame", codigoGame)
                    .withUpdateExpression("set listaPlayers = :lp, #js = :jsonVal")
                    .withNameMap(Map.of("#js", "json"))
                    .withValueMap(Map.of(
                            ":lp", objectMapper.convertValue(listaPlayersNode, java.util.List.class),
                            ":jsonVal", jsonNode.toString()
                    ))
                    .withReturnValues(ReturnValue.UPDATED_NEW);

            table.updateItem(updateItemSpec);

            return createResponse(200, "Jugador agregado correctamente.");

        } catch (Exception e) {
            context.getLogger().log("Error en JoinGameHandler: " + e.getMessage());
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
