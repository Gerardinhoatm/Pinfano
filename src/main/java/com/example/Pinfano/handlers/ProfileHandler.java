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

public class ProfileHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String userTable = System.getenv().getOrDefault("USERS_TABLE", "PinfanoUsers");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        // 🔹 CORS OPTIONS
        if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withHeaders(Map.of(
                            "Access-Control-Allow-Origin", "*",
                            "Access-Control-Allow-Methods", "GET, OPTIONS",
                            "Access-Control-Allow-Headers", "*"
                    ));
        }

        try {
            Map<String, String> queryParams = request.getQueryStringParameters();
            if (queryParams == null || !queryParams.containsKey("username")) {
                return createResponse(400, "{\"message\":\"Falta el parámetro 'username'\"}");
            }

            String username = queryParams.get("username");
            context.getLogger().log("Consultando perfil de: " + username);

            Table table = dynamoDB.getTable(userTable);
            Item item = table.getItem("username", username);

            if (item == null) {
                return createResponse(404, "{\"message\":\"Usuario no encontrado\"}");
            }

            String jsonResponse = String.format(
                    "{" +
                            "\"username\":\"%s\"," +
                            "\"name\":\"%s\"," +
                            "\"nickname\":\"%s\"," +
                            "\"email\":\"%s\"," +
                            "\"age\":%d," +
                            "\"rank\":%d" +
                            "}",
                    item.getString("username"),
                    item.getString("name") != null ? item.getString("name") : "",
                    item.getString("nickname") != null ? item.getString("nickname") : "",
                    item.getString("email") != null ? item.getString("email") : "",
                    item.isPresent("age") ? item.getInt("age") : 0,
                    item.isPresent("rank") ? item.getInt("rank") : 0
            );

            return createResponse(200, jsonResponse);

        } catch (Exception e) {
            context.getLogger().log("Error al obtener perfil: " + e.getMessage());
            return createResponse(500, "{\"message\":\"Error interno del servidor\"}");
        }
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(statusCode)
                .withHeaders(Map.of(
                        "Content-Type", "application/json",
                        "Access-Control-Allow-Origin", "*",
                        "Access-Control-Allow-Headers", "*",
                        "Access-Control-Allow-Methods", "OPTIONS,GET"))
                .withBody(body);
    }
}
