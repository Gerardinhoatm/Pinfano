package com.example.Pinfano.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;

import com.amazonaws.services.apigatewaymanagementapi.AmazonApiGatewayManagementApi;
import com.amazonaws.services.apigatewaymanagementapi.AmazonApiGatewayManagementApiClientBuilder;
import com.amazonaws.services.apigatewaymanagementapi.model.PostToConnectionRequest;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class DisconnectHandler implements RequestHandler<APIGatewayV2WebSocketEvent, Object> {

    private final AmazonDynamoDB dynamoDb = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamo = new DynamoDB(dynamoDb);

    private final String CONNECTIONS_TABLE = "PinfanoConnections";
    private final String GAMES_TABLE = "PinfanoGames";

    @Override
    public Object handleRequest(APIGatewayV2WebSocketEvent event, Context context) {

        String connectionId = event.getRequestContext().getConnectionId();
        context.getLogger().log("🔌 [$disconnect] connectionId = " + connectionId);

        Table connectionsTable = dynamo.getTable(CONNECTIONS_TABLE);
        Table gamesTable = dynamo.getTable(GAMES_TABLE);

        // 1️⃣ Buscar la conexión por connectionId (PRIMARY KEY)
        Item playerItem = connectionsTable.getItem("connectionId", connectionId);

        if (playerItem == null) {
            context.getLogger().log("❌ No existe esta conexión en la tabla. Se ignora.");
            return null;
        }

        String username = playerItem.getString("username");
        String codigoGame = playerItem.getString("codigoGame");

        context.getLogger().log("👤 Desconectando jugador = " + username + " del game = " + codigoGame);

        // 2️⃣ Borrar la conexión
        connectionsTable.deleteItem("connectionId", connectionId);
        context.getLogger().log("🧹 Conexión eliminada correctamente.");

        // 3️⃣ Buscar GAME por codigoGame mediante SCAN (NO hay GSI)
        Item gameItem = null;

        ScanSpec scanSpec = new ScanSpec()
                .withFilterExpression("codigoGame = :cg")
                .withValueMap(new ValueMap().withString(":cg", codigoGame));

        ItemCollection<ScanOutcome> scanResult = gamesTable.scan(scanSpec);

        for (Item item : scanResult) {
            gameItem = item;
            break;
        }

        int maxPlayers = 4;

        if (gameItem != null && gameItem.isPresent("numJugadores")) {
            maxPlayers = gameItem.getInt("numJugadores");
        }

        // 4️⃣ Obtener todas las conexiones restantes del mismo game con SCAN
        List<String> targets = new ArrayList<>();
        int connectedPlayers = 0;

        ScanSpec scanConnections = new ScanSpec()
                .withFilterExpression("codigoGame = :cg")
                .withValueMap(new ValueMap().withString(":cg", codigoGame));

        ItemCollection<ScanOutcome> connResults = connectionsTable.scan(scanConnections);

        for (Item i : connResults) {
            targets.add(i.getString("connectionId"));
            connectedPlayers++;
        }

        // 5️⃣ Preparar JSON
        JSONObject json = new JSONObject();
        json.put("type", "playerLeft");
        json.put("username", username);
        json.put("connectedPlayers", connectedPlayers);
        json.put("maxPlayers", maxPlayers);

        // 6️⃣ Enviar notificación
        AmazonApiGatewayManagementApi apiClient =
                AmazonApiGatewayManagementApiClientBuilder.standard()
                        .withEndpointConfiguration(
                                new AmazonApiGatewayManagementApiClientBuilder.EndpointConfiguration(
                                        "https://" + event.getRequestContext().getDomainName() + "/" +
                                                event.getRequestContext().getStage(),
                                        "eu-central-1"))
                        .build();

        for (String id : targets) {
            try {
                apiClient.postToConnection(new PostToConnectionRequest()
                        .withConnectionId(id)
                        .withData(ByteBuffer.wrap(json.toString().getBytes())));
            } catch (Exception e) {
                context.getLogger().log("❌ Error enviando mensaje a " + id + ": " + e.getMessage());
            }
        }

        context.getLogger().log("📢 PlayerLeft enviado correctamente.");

        return null;
    }
}