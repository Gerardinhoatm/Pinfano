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
    private final String TABLE_NAME = System.getenv().getOrDefault("CONNECTIONS_TABLE", "PinfanoConnections");

    @Override
    public Object handleRequest(APIGatewayV2WebSocketEvent event, Context context) {

        String connectionId = event.getRequestContext().getConnectionId();
        Map<String, String> headers = event.getHeaders() != null ? event.getHeaders() : new HashMap<>();

        // Valores enviados desde Android Studio
        String username = headers.getOrDefault("X-Username", "unknown");
        String codigoPartida = headers.get("X-Codigo");

        // Guardar en DynamoDB
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("connectionId", new AttributeValue(connectionId));
        item.put("username", new AttributeValue(username));
        item.put("codigoPartida", new AttributeValue(codigoPartida));

        // Estado de usuario
        item.put("connected", new AttributeValue().withBOOL(true));

        PutItemRequest request = new PutItemRequest()
                .withTableName(TABLE_NAME)
                .withItem(item);

        try {
            dynamoDb.putItem(request);
            context.getLogger().log("✅ Conexión guardada en DynamoDB");
        } catch (Exception e) {
            context.getLogger().log("❌ Error guardando conexión: " + e.getMessage());
        }

        return Map.of(
                "statusCode", 200,
                "body", "Connected!"
        );
    }
}