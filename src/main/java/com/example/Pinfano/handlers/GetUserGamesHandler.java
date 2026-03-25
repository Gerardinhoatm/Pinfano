package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

public class GetUserGamesHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);

    private final String userTable = System.getenv().getOrDefault("USERS_TABLE", "PinfanoUsers");
    private final String gamesTable = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return buildResponse(200, "{}");
        }

        try {
            JsonNode body = objectMapper.readTree(request.getBody());
            String username = body.get("username").asText();

            Table userT = dynamoDB.getTable(userTable);
            Table gamesT = dynamoDB.getTable(gamesTable);

            Item userItem = userT.getItem("username", username);

            if (userItem == null) {
                return buildResponse(404, "{\"message\":\"Usuario no encontrado\"}");
            }

            List<Object> games = userItem.getList("games");
            JSONArray gamesResponse = new JSONArray();

            for (Object o : games) {
                String codigoGame = o.toString();

                Item gameItem = gamesT.getItem("codigoGame", codigoGame);

                boolean terminado = false;
                List<String> players = List.of();

                if (gameItem != null) {
                    terminado = gameItem.getBoolean("terminado");
                    players = gameItem.getList("players");
                }

                JSONObject obj = new JSONObject();
                obj.put("codigoGame", codigoGame);
                obj.put("terminado", terminado);
                obj.put("players", players);

                gamesResponse.put(obj);
            }

            JSONObject finalJson = new JSONObject();
            finalJson.put("username", username);
            finalJson.put("games", gamesResponse);

            return buildResponse(200, finalJson.toString());

        } catch (Exception e) {
            context.getLogger().log("Error en GetUserGames: " + e.getMessage());
            return buildResponse(500, "{\"message\":\"Error interno\"}");
        }
    }

    private APIGatewayProxyResponseEvent buildResponse(int status, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(Map.of(
                        "Access-Control-Allow-Origin", "*",
                        "Access-Control-Allow-Headers", "*",
                        "Access-Control-Allow-Methods", "POST,OPTIONS",
                        "Content-Type", "application/json"
                ))
                .withBody(body);
    }
}