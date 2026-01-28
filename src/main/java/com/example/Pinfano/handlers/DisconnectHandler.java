package com.example.Pinfano.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.DeleteItemRequest;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;

import java.util.HashMap;
import java.util.Map;

public class DisconnectHandler implements RequestHandler<APIGatewayV2WebSocketEvent, Object> {

    private final AmazonDynamoDB dynamoDb = AmazonDynamoDBClientBuilder.defaultClient();
    private final String TABLE_NAME = System.getenv().getOrDefault("CONNECTIONS_TABLE", "PinfanoConnections");

    @Override
    public Object handleRequest(APIGatewayV2WebSocketEvent event, Context context) {

        String connectionId = event.getRequestContext().getConnectionId();
        context.getLogger().log("❌ Desconectando connectionId: " + connectionId);

        Map<String, AttributeValue> key = new HashMap<>();
        key.put("connectionId", new AttributeValue(connectionId));

        DeleteItemRequest request = new DeleteItemRequest()
                .withTableName(TABLE_NAME)
                .withKey(key);

        try {
            dynamoDb.deleteItem(request);
            context.getLogger().log("✅ Conexión eliminada de DynamoDB");
        } catch (Exception e) {
            context.getLogger().log("❌ Error eliminando conexión: " + e.getMessage());
        }

        return Map.of(
                "statusCode", 200,
                "body", "Disconnected"
        );
    }
}
