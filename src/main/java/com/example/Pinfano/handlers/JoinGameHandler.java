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

public class JoinGameHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String gamesTable = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        try {
            JsonNode body = objectMapper.readTree(request.getBody());
            String codigo = body.get("codigoGame").asText();
            String username = body.get("username").asText();

            Table table = dynamoDB.getTable(gamesTable);

            // Buscar partida por código
            ScanSpec scan = new ScanSpec()
                    .withFilterExpression("codigoGame = :v")
                    .withValueMap(new ValueMap().withString(":v", codigo));

            Iterator<Item> iterator = table.scan(scan).iterator();

            if (!iterator.hasNext()) {
                return createResponse(200, "{\"joined\":false, \"reason\":\"NO_EXISTE\"}");
            }

            Item game = iterator.next();
            String estado = game.getString("estado");
            List<Object> players = game.getList("listaPlayers");

            boolean yaEsta = players.stream()
                    .anyMatch(p -> p instanceof String && ((String) p).equalsIgnoreCase(username));

            // Si ya está dentro → idempotente, joined=true
            if (yaEsta) {
                return createResponse(200, "{ \"joined\": true, \"estadoFinal\": \"" + estado + "\" }");
            }

            // Buscar primer "VACIO"
            boolean insertado = false;
            for (int i = 0; i < players.size(); i++) {
                Object p = players.get(i);
                if (p instanceof String && p.equals("VACIO")) {
                    players.set(i, username);
                    insertado = true;
                    break;
                }
            }

            if (!insertado) {
                // No había hueco y usuario no estaba → error
                return createResponse(200, "{\"joined\":false, \"reason\":\"SIN_HUECO\"}");
            }

            // Revisar si quedan huecos para cambiar estado
            boolean quedanHuecos = players.stream()
                    .anyMatch(p -> p instanceof String && p.equals("VACIO"));
            if (!quedanHuecos) {
                estado = "A"; // activar partida
            }

            // Guardar cambios en DynamoDB
            game.withList("listaPlayers", players)
                    .withString("estado", estado);
            table.putItem(game);

            return createResponse(200, "{ \"joined\": true, \"estadoFinal\": \"" + estado + "\" }");

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