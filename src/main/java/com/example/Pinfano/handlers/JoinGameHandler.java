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
                // Esto solo ocurre si algo muy raro pasó, no por validación
                return createResponse(500, "{\"joined\":false, \"reason\":\"NO_EXISTE\"}");
            }

            Item game = results.iterator().next();

            String estado = game.getString("estado");
            List<Object> players = game.getList("listaPlayers");
            String idGame = game.getString("idGame");

            // Buscar primer NULL
            int indexLibre = -1;
            for (int i = 0; i < players.size(); i++) {
                if (players.get(i) == null) {
                    indexLibre = i;
                    break;
                }
            }

            if (indexLibre == -1) {
                // Si ya no hay hueco, pero llegamos aquí significa que la validación falló antes
                return createResponse(500, "{\"joined\":false, \"reason\":\"NO_HUECO_LIBRE\"}");
            }

            // Insertar jugador en el primer hueco libre
            players.set(indexLibre, username);

            // Si ya no hay huecos → cambiar estado a A
            boolean lleno = players.stream().noneMatch(p -> p == null);
            if (lleno) estado = "A";

            // Guardar en DynamoDB
            table.updateItem(
                    "idGame", idGame,
                    "SET listaPlayers = :p, estado = :e",
                    Map.of(":p", players, ":e", estado)
            );

            // Devuelve siempre 200 si todo se ejecutó correctamente
            return createResponse(200,
                    "{ \"joined\": true, \"estadoFinal\":\"" + estado + "\" }");

        } catch (Exception e) {
            return createResponse(500, "{\"joined\":false, \"error\":\"" + e.getMessage() + "\"}");
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
