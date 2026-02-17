package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

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
            // ============================
            // Leer BODY desde el cliente
            // ============================
            JsonNode body = objectMapper.readTree(request.getBody());
            String codigo = body.get("codigoGame").asText();
            String username = body.get("username").asText();

            Table table = dynamoDB.getTable(gamesTable);

            // ============================
            // Buscar partida por código
            // ============================
            QuerySpec query = new QuerySpec()
                    .withKeyConditionExpression("codigoGame = :v")
                    .withValueMap(new ValueMap().withString(":v", codigo));

            ItemCollection<QueryOutcome> results = table.query(query);

            Iterator<Item> iterator = results.iterator();
            if (!iterator.hasNext()) {
                return createResponse(200, "{\"joined\":false, \"reason\":\"NO_EXISTE\"}");
            }

            Item game = iterator.next();

            String estado = game.getString("estado");
            List<Object> players = game.getList("listaPlayers");
            String idGame = game.getString("idGame");

            // ============================
            // Validar estado
            // ============================
            if (!"P".equalsIgnoreCase(estado)) {
                return createResponse(200, "{\"joined\":false, \"reason\":\"NO_PERMITE_UNIRSE\"}");
            }

            // ============================
            // 1. Buscar hueco NULL
            // ============================
            int indexLibre = -1;

            for (int i = 0; i < players.size(); i++) {
                Object p = players.get(i);

                if (p instanceof Map<?,?> map) {
                    if (map.containsKey("NULL") && Boolean.TRUE.equals(map.get("NULL"))) {
                        indexLibre = i;
                        break;
                    }
                }
            }

            if (indexLibre == -1) {
                return createResponse(200, "{\"joined\":false, \"reason\":\"LLENO\"}");
            }

            // ============================
            // 2. Insertar el jugador
            // ============================
            Map<String, Object> nuevoJugador = new HashMap<>();
            nuevoJugador.put("S", username);

            players.set(indexLibre, nuevoJugador);

            // ============================
            // 3. Comprobar si queda algún NULL
            // ============================
            boolean quedanHuecos = players.stream().anyMatch(p ->
                    p instanceof Map<?,?> map &&
                            map.containsKey("NULL") &&
                            Boolean.TRUE.equals(map.get("NULL"))
            );

            if (!quedanHuecos) {
                estado = "A"; // Todos los jugadores están → activar partida
            }

            // ============================
            // 4. Guardar en DynamoDB
            // ============================
            table.updateItem(
                    "idGame", idGame,
                    "SET listaPlayers = :p, estado = :e",
                    new ValueMap()
                            .withList(":p", players)
                            .withString(":e", estado)
            );

            // ============================
            // 5. Respuesta OK
            // ============================
            return createResponse(200,
                    "{ \"joined\": true, \"estadoFinal\":\"" + estado + "\" }");

        } catch (Exception e) {
            return createResponse(200,
                    "{\"joined\":false, \"reason\":\"ERROR_INTERNO: " + e.getMessage() + "\"}");
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
