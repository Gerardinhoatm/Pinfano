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

public class ValidateGameCodeHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

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
            String codigo = body.get("codigo").asText();

            Table table = dynamoDB.getTable(gamesTable);

            // Buscar partida por codigoGame
            ScanSpec scan = new ScanSpec()
                    .withFilterExpression("codigoGame = :codigoVal")
                    .withValueMap(Map.of(":codigoVal", codigo));

            ItemCollection<ScanOutcome> results = table.scan(scan);

            if (!results.iterator().hasNext()) {
                return createResponse(404, "{\"valid\":false, \"reason\":\"NO_EXISTE\"}");
            }

            Item game = results.iterator().next();

            String estado = game.getString("estado");
            List<Object> players = game.getList("listaPlayers");

            // Comprobar estado
            if (!"P".equalsIgnoreCase(estado)) {
                return createResponse(400, "{\"valid\":false, \"reason\":\"NO_PERMITE_UNIRSE\"}");
            }

            // Comprobar hueco
            boolean hayHueco = players.stream().anyMatch(p -> p == null);

            if (!hayHueco) {
                return createResponse(400, "{\"valid\":false, \"reason\":\"LLENO\"}");
            }

            // OK
            String responseJson =
                    "{ \"valid\": true, \"reason\":\"OK\" }";

            return createResponse(200, responseJson);

        } catch (Exception e) {
            return createResponse(500, "{\"error\":\"" + e.getMessage() + "\"}");
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

