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
        return null;
    }

    public void sendUpdateToAll(JSONObject data, Context context) {
        var logger = context.getLogger();
        logger.log("[WS-START] Iniciando sendUpdateToAll");

        try {
            String codigoGame = data.getString("codigoGame");
            int turno = data.getInt("turno");

            logger.log("[WS-INFO] Buscando conexiones para codigoGame: " + codigoGame);

            Table table = dynamo.getTable(TABLE);
            List<String> connections = new ArrayList<>();

            int totalScanned = 0;
            for (Item item : table.scan()) {
                totalScanned++;
                if (codigoGame.equals(item.getString("codigoGame"))) {
                    connections.add(item.getString("connectionId"));
                }
            }
            logger.log("[WS-INFO] Scan finalizado. Total en tabla: " + totalScanned + " | Encontradas para este juego: " + connections.size());

            JSONObject msg = new JSONObject();
            msg.put("type", "gameUpdated");
            msg.put("codigoGame", codigoGame);
            msg.put("jsonGame", data.getJSONObject("jsonGame"));
            msg.put("turno", turno);
            String domain = System.getenv("WS_DOMAIN");
            String stage = System.getenv("WS_STAGE");
            String endpoint = "https://" + domain + "/" + stage;
            logger.log("[WS-CONFIG] Endpoint configurado: " + endpoint);
            AmazonApiGatewayManagementApi api = AmazonApiGatewayManagementApiClientBuilder.standard()
                    .withEndpointConfiguration(
                            new AmazonApiGatewayManagementApiClientBuilder.EndpointConfiguration(
                                    endpoint, "eu-central-1"))
                    .build();

            for (String id : connections) {
                try {
                    logger.log("[WS-SENDING] Enviando a ConnectionId: " + id);
                    api.postToConnection(
                            new PostToConnectionRequest()
                                    .withConnectionId(id)
                                    .withData(ByteBuffer.wrap(msg.toString().getBytes()))
                    );
                    logger.log("[WS-OK] Enviado a " + id);
                } catch (Exception e) {
                    logger.log("[WS-ERROR] Fallo al enviar a " + id + ": " + e.getMessage());
                }
            }
            logger.log("[WS-END] Difusión finalizada");

        } catch (Exception e) {
            logger.log("[WS-FATAL] Error en sendUpdateToAll: " + e.getMessage());
        }
    }
}