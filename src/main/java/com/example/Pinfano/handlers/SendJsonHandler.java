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

        String codigoGame = data.getString("codigoGame");
        JSONObject jsonGame = data.getJSONObject("jsonGame");
        int turno = data.getInt("turno");

        // LEER TODAS LAS CONEXIONES DEL MISMO GAME
        Table table = dynamo.getTable(TABLE);
        List<String> connections = new ArrayList<>();

        for (Item item : table.scan()) {
            if (codigoGame.equals(item.getString("codigoGame"))) {
                connections.add(item.getString("connectionId"));
            }
        }

        // JSON A ENVIAR
        JSONObject msg = new JSONObject();
        msg.put("type", "gameUpdated");
        msg.put("codigoGame", codigoGame);
        msg.put("jsonGame", jsonGame);
        msg.put("turno", turno);

        String domain = System.getenv("WS_DOMAIN");
        String stage = System.getenv("WS_STAGE");

        AmazonApiGatewayManagementApi api = AmazonApiGatewayManagementApiClientBuilder.standard()
                .withEndpointConfiguration(
                        new AmazonApiGatewayManagementApiClientBuilder.EndpointConfiguration(
                                "https://" + domain + "/" + stage,
                                "eu-central-1"))
                .build();

        // ENVIAR A CADA CLIENTE
        for (String id : connections) {
            try {
                api.postToConnection(
                        new PostToConnectionRequest()
                                .withConnectionId(id)
                                .withData(ByteBuffer.wrap(msg.toString().getBytes()))
                );
            } catch (Exception e) {
                context.getLogger().log("Error enviando a " + id + ": " + e.getMessage());
            }
        }
    }
}