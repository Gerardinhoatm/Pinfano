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

    private final String CONNECTIONS_TABLE = System.getenv().getOrDefault("CONNECTIONS_TABLE", "PinfanoConnections");
    private final String GAMES_TABLE = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");

    @Override
    public Object handleRequest(APIGatewayV2WebSocketEvent event, Context context) {

        String rawConnectionId = event.getRequestContext().getConnectionId();
        context.getLogger().log("🔌 DISCONNECT EVENT recibido para connectionId = " + rawConnectionId);

        Table connectionsTable = dynamo.getTable(CONNECTIONS_TABLE);
        Table gamesTable = dynamo.getTable(GAMES_TABLE);

        // 1️⃣ OBTENER username y codigo desde los HEADERS DEL CLIENTE
        String username = event.getHeaders() != null ? event.getHeaders().get("X-Username") : null;
        String codigoGame = event.getHeaders() != null ? event.getHeaders().get("X-Codigo") : null;

        context.getLogger().log("📩 Headers -> username: " + username + ", codigoGame: " + codigoGame);

        if (username == null || codigoGame == null) {
            context.getLogger().log("❌ Headers vacíos. No se puede identificar al jugador.");
            return null;
        }

        // 2️⃣ BUSCAR EN LA TABLA QUIÉN TIENE ESE USERNAME + CODIGO
        Index index = connectionsTable.getIndex("CodigoGame-index");

        QuerySpec spec = new QuerySpec()
                .withKeyConditionExpression("codigoGame = :cg")
                .withValueMap(new ValueMap().withString(":cg", codigoGame));

        Item playerItem = null;
        for (Item i : index.query(spec)) {
            if (i.getString("username").equals(username)) {
                playerItem = i;
                break;
            }
        }

        if (playerItem == null) {
            context.getLogger().log("❌ No se encontró en la tabla a " + username + " para codigoGame " + codigoGame);
            return null;
        }

        String connectionIdToDelete = playerItem.getString("connectionId");
        context.getLogger().log("🧹 Eliminando connectionId real: " + connectionIdToDelete);

        // 3️⃣ ELIMINAR LA CONEXIÓN
        connectionsTable.deleteItem("connectionId", connectionIdToDelete);

        // 4️⃣ OBTENER MAXPLAYERS
        Item gameItem = gamesTable.getItem("codigoGame", codigoGame);
        int maxPlayers = gameItem != null && gameItem.isPresent("numjugadores")
                ? gameItem.getInt("numjugadores") : 4;

        // 5️⃣ OBTENER CONEXIONES RESTANTES DEL GAME
        List<String> targets = new ArrayList<>();
        int connectedPlayers = 0;
        for (Item i : index.query(spec)) {
            targets.add(i.getString("connectionId"));
            connectedPlayers++;
        }

        // 6️⃣ PREPARAR JSON A ENVIAR
        JSONObject json = new JSONObject();
        json.put("type", "playerLeft");
        json.put("username", username);
        json.put("connectedPlayers", connectedPlayers);
        json.put("maxPlayers", maxPlayers);

        // 7️⃣ CLIENTE API GATEWAY MANAGEMENT
        AmazonApiGatewayManagementApi apiClient = AmazonApiGatewayManagementApiClientBuilder.standard()
                .withEndpointConfiguration(
                        new AmazonApiGatewayManagementApiClientBuilder.EndpointConfiguration(
                                "https://" + event.getRequestContext().getDomainName() + "/" + event.getRequestContext().getStage(),
                                "eu-central-1"
                        )
                ).build();

        // 8️⃣ NOTIFICAR A TODOS
        for (String id : targets) {
            try {
                apiClient.postToConnection(new PostToConnectionRequest()
                        .withConnectionId(id)
                        .withData(ByteBuffer.wrap(json.toString().getBytes())));
            } catch (Exception e) {
                context.getLogger().log("❌ Error notificando a " + id + ": " + e.getMessage());
            }
        }

        context.getLogger().log("🟢 Notificado playerLeft correctamente");

        return null;
    }
}