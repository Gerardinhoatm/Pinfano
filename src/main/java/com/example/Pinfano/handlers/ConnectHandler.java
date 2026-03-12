package com.example.Pinfano.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;

import com.amazonaws.services.apigatewaymanagementapi.AmazonApiGatewayManagementApi;
import com.amazonaws.services.apigatewaymanagementapi.AmazonApiGatewayManagementApiClientBuilder;
import com.amazonaws.services.apigatewaymanagementapi.model.PostToConnectionRequest;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ConnectHandler implements RequestHandler<APIGatewayV2WebSocketEvent, Object> {

    private final AmazonDynamoDB dynamoDb = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamo = new DynamoDB(dynamoDb);

    private final String CONNECTIONS_TABLE = "PinfanoConnections";
    private final String GAMES_TABLE = "PinfanoGames";

    @Override
    public Object handleRequest(APIGatewayV2WebSocketEvent event, Context context) {

        String connectionId = event.getRequestContext().getConnectionId();
        Map<String, String> headers = event.getHeaders();

        String username = headers.get("X-Username");
        String codigoGame = headers.get("X-Codigo");

        if (username == null || codigoGame == null) {
            context.getLogger().log("❌ Headers vacíos, no se guarda nada.");
            return null;
        }

        context.getLogger().log("🔌 Conectando: " + username + " al juego " + codigoGame);

        // 1️⃣ Guardar conexión en DynamoDB
        Table connectionsTable = dynamo.getTable(CONNECTIONS_TABLE);
        Item item = new Item()
                .withPrimaryKey("connectionId", connectionId)
                .withString("username", username)
                .withString("codigoGame", codigoGame);
        connectionsTable.putItem(item);

        // 2️⃣ Obtener numjugadores del juego
        Table gamesTable = dynamo.getTable(GAMES_TABLE);
        Item gameItem = gamesTable.getItem("codigoGame", codigoGame);
        int maxPlayers = gameItem != null && gameItem.isPresent("numjugadores") ? gameItem.getInt("numjugadores") : 4;

        // 3️⃣ Obtener todas las conexiones actuales del juego
        Index index = connectionsTable.getIndex("CodigoGame-index");
        QuerySpec spec = new QuerySpec()
                .withKeyConditionExpression("codigoGame = :cg")
                .withValueMap(new ValueMap().withString(":cg", codigoGame));

        List<String> targets = new ArrayList<>();
        int connectedPlayers = 0;

        for (Item i : index.query(spec)) {
            targets.add(i.getString("connectionId"));
            connectedPlayers++;
        }

        // 4️⃣ Enviar notificación a todos
        JSONObject json = new JSONObject();
        json.put("type", "playerJoined");
        json.put("username", username);
        json.put("connectedPlayers", connectedPlayers);
        json.put("maxPlayers", maxPlayers);

        AmazonApiGatewayManagementApi apiClient = AmazonApiGatewayManagementApiClientBuilder.standard()
                .withEndpointConfiguration(
                        new AmazonApiGatewayManagementApiClientBuilder.EndpointConfiguration(
                                "https://" + event.getRequestContext().getDomainName() + "/" + event.getRequestContext().getStage(),
                                "eu-central-1"
                        )
                ).build();

        for (String id : targets) {
            try {
                apiClient.postToConnection(new PostToConnectionRequest()
                        .withConnectionId(id)
                        .withData(ByteBuffer.wrap(json.toString().getBytes())));
            } catch (Exception e) {
                context.getLogger().log("❌ Error enviando mensaje a " + id + ": " + e.getMessage());
            }
        }

        context.getLogger().log("✅ Notificación enviada correctamente.");

        return Map.of("statusCode", 200, "body", "Connected!");
    }
}