package com.example.Pinfano.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;

import com.amazonaws.services.apigatewaymanagementapi.AmazonApiGatewayManagementApi;
import com.amazonaws.services.apigatewaymanagementapi.AmazonApiGatewayManagementApiClientBuilder;
import com.amazonaws.services.apigatewaymanagementapi.model.PostToConnectionRequest;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class SendJsonHandler implements RequestHandler<APIGatewayV2WebSocketEvent, Object> {

    private final AmazonDynamoDB dynamoDb = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamo = new DynamoDB(dynamoDb);
    private final String TABLE = System.getenv().getOrDefault("CONNECTIONS_TABLE", "PinfanoConnections");

    @Override
    public Object handleRequest(APIGatewayV2WebSocketEvent event, Context context) {
        context.getLogger().log("[WS-INFO] SendJsonHandler invocado, no se procesa nada aquí.");
        return null;
    }

    public void sendUpdateToAll(JSONObject data, Context context) {
        var logger = context.getLogger();
        logger.log("[WS-START] Iniciando sendUpdateToAll");

        try {
            String codigoGame = data.getString("codigoGame");
            int turno = data.getInt("turno");

            Table table = dynamo.getTable(TABLE);
            List<String> connections = new ArrayList<>();

            for (Item item : table.scan()) {
                if (codigoGame.equals(item.getString("codigoGame"))) {
                    connections.add(item.getString("connectionId"));
                }
            }

            JSONObject msg = new JSONObject();
            msg.put("type", "gameUpdated");
            msg.put("codigoGame", codigoGame);

            // 🔥 AHORA SE LLAMA "json"
            msg.put("json", data.getJSONObject("json"));

            msg.put("turno", turno);

            String domain = System.getenv("WS_DOMAIN");
            String stage = System.getenv("WS_STAGE");

            String endpoint = "https://" + domain + "/" + stage;
            logger.log("[WS-CONFIG] Endpoint: " + endpoint);

            AmazonApiGatewayManagementApi api = AmazonApiGatewayManagementApiClientBuilder.standard()
                    .withEndpointConfiguration(
                            new AmazonApiGatewayManagementApiClientBuilder.EndpointConfiguration(
                                    endpoint, "eu-central-1"))
                    .build();

            for (String id : connections) {
                try {
                    logger.log("[WS-SENDING] Enviando a " + id);

                    api.postToConnection(
                            new PostToConnectionRequest()
                                    .withConnectionId(id)
                                    .withData(ByteBuffer.wrap(msg.toString().getBytes()))
                    );

                } catch (Exception e) {
                    logger.log("[WS-ERROR] Error con " + id + " → " + e.getMessage());
                }
            }

            logger.log("[WS-END] Envío finalizado");

        } catch (Exception e) {
            logger.log("[WS-FATAL] " + e.getMessage());
        }
    }
}