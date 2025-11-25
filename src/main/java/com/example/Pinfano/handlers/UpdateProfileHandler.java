package com.example.Pinfano.handlers;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

public class UpdateProfileHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final AmazonDynamoDB dynamoClient = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamoDB = new DynamoDB(dynamoClient);
    private final String userTable = System.getenv().getOrDefault("USERS_TABLE", "PinfanoUsers");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {

        context.getLogger().log("Método HTTP: " + request.getHttpMethod());
        context.getLogger().log("Body recibido: " + request.getBody());

        // CORS
        if ("OPTIONS".equalsIgnoreCase(request.getHttpMethod())) {
            return createResponse(200, "");
        }

        if (!"PATCH".equalsIgnoreCase(request.getHttpMethod())) {
            return createResponse(405, "{\"message\":\"Método no permitido\"}");
        }

        try {
            String bodyStr = request.getBody();
            if (bodyStr == null) {
                context.getLogger().log("ERROR: body es null");
                return createResponse(400, "{\"message\":\"Body vacío\"}");
            }

            JsonNode body = objectMapper.readTree(bodyStr);
            context.getLogger().log("JSON parseado correctamente: " + body.toString());

            // Validar que tengamos username
            if (!body.has("username")) {
                return createResponse(400, "{\"message\":\"Falta el parámetro 'username'\"}");
            }

            String username = body.get("username").asText();
            String name = body.has("name") ? body.get("name").asText() : null;
            Integer age = body.has("age") ? body.get("age").asInt() : null;

            context.getLogger().log("username=" + username + ", name=" + name + ", age=" + age);

            Table table = dynamoDB.getTable(userTable);
            Item item = table.getItem("username", username);
            if (item == null) {
                return createResponse(404, "{\"message\":\"Usuario no encontrado\"}");
            }

            // Construir expresión de actualización segura (evita usar palabras reservadas)
            StringBuilder updateExpr = new StringBuilder("set ");
            ValueMap valueMap = new ValueMap();
            Map<String, String> nameMap = new HashMap<>();
            boolean first = true;

            if (name != null) {
                updateExpr.append("#n = :name");
                valueMap.withString(":name", name);
                nameMap.put("#n", "name");
                first = false;
            }

            if (age != null) {
                if (!first) updateExpr.append(", ");
                updateExpr.append("age = :age");
                valueMap.withNumber(":age", age);
            }

            context.getLogger().log("UpdateExpression final: " + updateExpr);

            UpdateItemSpec updateSpec = new UpdateItemSpec()
                    .withPrimaryKey("username", username)
                    .withUpdateExpression(updateExpr.toString())
                    .withValueMap(valueMap)
                    .withNameMap(nameMap)
                    .withReturnValues("UPDATED_NEW");

            table.updateItem(updateSpec);

            return createResponse(200, "Perfil actualizado correctamente");

        } catch (Exception e) {
            context.getLogger().log("Error al actualizar perfil: " + e.getMessage());
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