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

public class GetGamePlayersHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String gamesTable = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        try {

            JsonNode body = objectMapper.readTree(request.getBody());
            String codigo = body.get("codigoGame").asText();

            Table table = dynamoDB.getTable(gamesTable);

            ScanSpec scan = new ScanSpec()
                    .withFilterExpression("codigoGame = :v")
                    .withValueMap(new ValueMap().withString(":v", codigo));

            Iterator<Item> iterator = table.scan(scan).iterator();

            if (!iterator.hasNext()) {
                return createResponse(200, "{\"exists\":false}");
            }

            Item game = iterator.next();

            List<Object> players = game.getList("listaPlayers");

            List<Integer> emptySlots = new ArrayList<>();

            for (int i = 0; i < players.size(); i++) {

                Object p = players.get(i);

                if (p instanceof String && p.equals("VACIO")) {
                    emptySlots.add(i);
                }
            }
            Map<String, Object> response = new HashMap<>();
            response.put("exists", true);
            response.put("players", players);
            response.put("emptySlots", emptySlots);

            String jsonResponse = objectMapper.writeValueAsString(response);

            return createResponse(200, jsonResponse);

        } catch (Exception e) {

            return createResponse(200,
                    "{\"exists\":false, \"error\":\"" + e.getMessage() + "\"}");
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