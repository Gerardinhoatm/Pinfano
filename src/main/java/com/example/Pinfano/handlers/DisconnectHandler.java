package com.example.Pinfano.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;

public class DisconnectHandler implements RequestHandler<APIGatewayV2WebSocketEvent, Object> {

    private final AmazonDynamoDB dynamoDb = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamo = new DynamoDB(dynamoDb);
    private final String CONNECTIONS_TABLE = System.getenv().getOrDefault("CONNECTIONS_TABLE", "PinfanoConnections");

    @Override
    public Object handleRequest(APIGatewayV2WebSocketEvent event, Context context) {

        String connectionId = event.getRequestContext().getConnectionId();
        context.getLogger().log("🔌 [$disconnect] Solicitado para connectionId: " + connectionId);

        try {
            Table connectionsTable = dynamo.getTable(CONNECTIONS_TABLE);
            connectionsTable.deleteItem("connectionId", connectionId);

            context.getLogger().log("🧹 Conexión " + connectionId + " eliminada de la tabla correctamente.");

        } catch (Exception e) {
            context.getLogger().log("❌ Error al limpiar la conexión: " + e.getMessage());
        }
        return null;
    }
}