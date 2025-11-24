package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

public class LogoffHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String userTable = System.getenv().getOrDefault("USERS_TABLE", "PinfanoUsers");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        context.getLogger().log("Método HTTP: " + request.getHttpMethod());
        context.getLogger().log("Body recibido: " + request.getBody());

        // Permitir CORS
        if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return createResponse(200, "");
        }

        if (!"PATCH".equalsIgnoreCase(request.getHttpMethod())) {
            return createResponse(405, "{\"message\":\"Método no permitido\"}");
        }

        try {
            String bodyStr = request.getBody();
            if (bodyStr == null) {
                return createResponse(400, "{\"message\":\"Body vacío\"}");
            }

            JsonNode body = objectMapper.readTree(bodyStr);
            if (!body.has("username")) {
                return createResponse(400, "{\"message\":\"Falta el parámetro 'username'\"}");
            }

            String username = body.get("username").asText();
            context.getLogger().log("Cerrando sesión para usuario: " + username);

            Table table = dynamoDB.getTable(userTable);

            // Actualiza logIn a false
            UpdateItemSpec updateSpec = new UpdateItemSpec()
                    .withPrimaryKey("username", username)
                    .withUpdateExpression("set logIn = :val")
                    .withValueMap(new ValueMap().withBoolean(":val", false))
                    .withReturnValues("UPDATED_NEW");

            table.updateItem(updateSpec);

            return createResponse(200, "{\"message\":\"Sesión cerrada correctamente\"}");

        } catch (Exception e) {
            context.getLogger().log("Error al cerrar sesión: " + e.getMessage());
            e.printStackTrace();
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
                        "Access-Control-Allow-Methods", "OPTIONS,PATCH"))
                .withBody(body);
    }
}