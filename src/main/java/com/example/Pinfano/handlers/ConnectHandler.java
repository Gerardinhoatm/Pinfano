package com.example.Pinfano.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;

import java.util.HashMap;
import java.util.Map;

public class ConnectHandler implements RequestHandler<APIGatewayV2WebSocketEvent, Object> {

    private final AmazonDynamoDB dynamoDb = AmazonDynamoDBClientBuilder.defaultClient();
    private final String TABLE_NAME = "PinfanoConnections";

    @Override
    public Object handleRequest(APIGatewayV2WebSocketEvent event, Context context) {

        String connectionId = event.getRequestContext().getConnectionId();
        Map<String, String> headers = event.getHeaders() != null ? event.getHeaders() : new HashMap<>();

        // ✔ Headers correctos
        String username = headers.get("X-Username");
        String codigoGame = headers.get("X-Codigo");

        context.getLogger().log("🔌 CONECT → connectionId=" + connectionId +
                " | username=" + username +
                " | game=" + codigoGame);

        if (username == null || codigoGame == null) {
            context.getLogger().log("❌ Headers vacíos, no se guarda nada.");
            return null;
        }

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("connectionId", new AttributeValue(connectionId));
        item.put("username", new AttributeValue(username));
        item.put("codigoGame", new AttributeValue(codigoGame));

        PutItemRequest request = new PutItemRequest()
                .withTableName(TABLE_NAME)
                .withItem(item);

        try {
            dynamoDb.putItem(request);
            context.getLogger().log("✅ Conexión guardada en DynamoDB");
        } catch (Exception e) {
            context.getLogger().log("❌ Error guardando conexión: " + e.getMessage());
        }

        return Map.of("statusCode", 200, "body", "Connected!");
    }
}