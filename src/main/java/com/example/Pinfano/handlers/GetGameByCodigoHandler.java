package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import java.util.Map;

public class GetGameByCodigoHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String gameTable = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {

        if ("OPTIONS".equalsIgnoreCase(event.getHttpMethod())) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(Map.of(
                            "Access-Control-Allow-Origin", "*",
                            "Access-Control-Allow-Methods", "GET, OPTIONS",
                            "Access-Control-Allow-Headers", "*"
                    ));
        }

        try {
            // Leemos el header X-CodigoGame
            Map<String, String> headers = event.getHeaders();
            String codigoGame = headers != null ? headers.get("X-codigoGame") : null;
            String username = headers != null ? headers.get("x-username") : null;

            context.getLogger().log("📌 Header recibido - x-codigogame: " + codigoGame + ", x-username: " + username);
            if (codigoGame == null || codigoGame.isEmpty()) {
                return createResponse(400, "Falta X-codigoGame en headers");
            }

            Table table = dynamoDB.getTable(gameTable);
            Item item = table.getItem("codigoGame", codigoGame);

            if (item == null) {
                return createResponse(404, "Game no encontrado");
            }

            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(Map.of(
                            "Access-Control-Allow-Origin", "*",
                            "Access-Control-Allow-Headers", "*"
                    ))
                    .withBody(item.toJSONPretty());

        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
            return createResponse(500, "Error interno");
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int code, String msg) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(code)
                .withHeaders(Map.of(
                        "Access-Control-Allow-Origin", "*",
                        "Access-Control-Allow-Headers", "*"
                ))
                .withBody("{\"message\":\"" + msg + "\"}");
    }
}