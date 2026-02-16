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

import java.util.List;
import java.util.Map;

public class JoinGameHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String gamesTable = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return createResponse(200, "{\"message\":\"OK\"}");
        }

        try {
            JsonNode body = objectMapper.readTree(request.getBody());
            String codigo = body.get("codigoGame").asText();
            String username = body.get("username").asText();

            Table table = dynamoDB.getTable(gamesTable);

            // Buscar partida por codigoGame
            ScanSpec scan = new ScanSpec()
                    .withFilterExpression("codigoGame = :codigoVal")
                    .withValueMap(Map.of(":codigoVal", codigo));

            ItemCollection<ScanOutcome> results = table.scan(scan);

            if (!results.iterator().hasNext()) {
                return createResponse(200, "{\"joined\":false, \"reason\":\"NO_EXISTE\"}");
            }

            Item game = results.iterator().next();
            String estado = game.getString("estado");
            List<Object> players = game.getList("listaPlayers");
            String idGame = game.getString("idGame");

            if (!"P".equalsIgnoreCase(estado)) {
                return createResponse(200, "{\"joined\":false, \"reason\":\"NO_PERMITE_UNIRSE\"}");
            }

// Buscar primer hueco libre (solo { "NULL": true })
            int indexLibre = -1;
            for (int i = 0; i < players.size(); i++) {
                Object p = players.get(i);
                if (p instanceof Map) {
                    Map<?,?> map = (Map<?,?>) p;
                    if (map.containsKey("NULL") && Boolean.TRUE.equals(map.get("NULL"))) {
                        indexLibre = i;
                        break;
                    }
                }
            }

            if (indexLibre == -1) {
                return createResponse(200, "{\"joined\":false, \"reason\":\"LLENO\"}");
            }

            // Insertar jugador en el hueco
            players.set(indexLibre, username);

            // Comprobar si la lista ya está completa
            boolean lleno = true;
            for (Object p : players) {
                if (p instanceof Map) {
                    Map<?,?> map = (Map<?,?>) p;
                    if (map.containsKey("NULL") && Boolean.TRUE.equals(map.get("NULL"))) {
                        lleno = false;
                        break;
                    }
                }
            }

            if (lleno) estado = "A";

            // Guardar en DynamoDB
            table.updateItem(
                    "idGame", idGame,
                    "SET listaPlayers = :p, estado = :e",
                    Map.of(":p", players, ":e", estado)
            );

            return createResponse(200,
                    "{ \"joined\": true, \"estadoFinal\":\"" + estado + "\" }");

        } catch (Exception e) {
            return createResponse(200, "{\"joined\":false, \"reason\":\"ERROR_INTERNO: " + e.getMessage() + "\"}");
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
