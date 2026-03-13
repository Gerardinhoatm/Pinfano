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

        // 1️⃣ Obtener la conexión que se desconecta
        Item playerItem = connectionsTable.getItem("connectionId", connectionId);

        if (playerItem == null) {
            context.getLogger().log("❌ Conexión no encontrada. Nada que hacer.");
            return null;
        }

        String username = playerItem.getString("username");
        String codigoGame = playerItem.getString("codigoGame");

        context.getLogger().log("👤 Jugador desconectado = " + username + " | Game = " + codigoGame);

        // 2️⃣ Eliminar la conexión
        connectionsTable.deleteItem("connectionId", connectionId);
        context.getLogger().log("🧹 Conexión eliminada correctamente.");

        // 3️⃣ Obtener game usando GSI (IMPORTANTE)
        Index gameIndex = gamesTable.getIndex("CodigoGame-index");

        QuerySpec gameQuery = new QuerySpec()
                .withKeyConditionExpression("codigoGame = :cg")
                .withValueMap(new ValueMap().withString(":cg", codigoGame));

        Item gameItem = null;
        ItemCollection<QueryOutcome> gameResults = gameIndex.query(gameQuery);

        for (Item item : gameResults) {
            gameItem = item;
            break; // solo 1 game por codigoGame
        }

        int maxPlayers = 4;
        if (gameItem != null && gameItem.isPresent("numJugadores")) {
            maxPlayers = gameItem.getInt("numJugadores");
        }

        context.getLogger().log("🎮 maxPlayers = " + maxPlayers);

        // 4️⃣ Buscar el resto de los jugadores conectados a ese game
        Index connectionsIndex = connectionsTable.getIndex("CodigoGame-index");
        QuerySpec spec = new QuerySpec()
                .withKeyConditionExpression("codigoGame = :cg")
                .withValueMap(new ValueMap().withString(":cg", codigoGame));

        List<String> targets = new ArrayList<>();
        int connectedPlayers = 0;

        for (Item i : connectionsIndex.query(spec)) {
            targets.add(i.getString("connectionId"));
            connectedPlayers++;
        }

        context.getLogger().log("📡 Jugadores conectados tras desconexión = " + connectedPlayers);

        // 5️⃣ Crear JSON del evento
        JSONObject json = new JSONObject();
        json.put("type", "playerLeft");
        json.put("username", username);
        json.put("connectedPlayers", connectedPlayers);
        json.put("maxPlayers", maxPlayers);

        // 6️⃣ Enviar notificaciones
        AmazonApiGatewayManagementApi apiClient =
                AmazonApiGatewayManagementApiClientBuilder.standard()
                        .withEndpointConfiguration(new AmazonApiGatewayManagementApiClientBuilder
                                .EndpointConfiguration(
                                "https://" + event.getRequestContext().getDomainName()
                                        + "/" + event.getRequestContext().getStage(),
                                "eu-central-1"))
                        .build();

        for (String id : targets) {
            try {
                apiClient.postToConnection(
                        new PostToConnectionRequest()
                                .withConnectionId(id)
                                .withData(ByteBuffer.wrap(json.toString().getBytes()))
                );
            } catch (Exception e) {
                context.getLogger().log("❌ Error enviando a " + id + ": " + e.getMessage());
            }
        }

        context.getLogger().log("📢 Notificación enviada a los jugadores restantes.");
        return null;
    }
}