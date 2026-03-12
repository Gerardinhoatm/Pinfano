package com.example.Pinfano.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;

import com.amazonaws.services.apigatewaymanagementapi.AmazonApiGatewayManagementApi;
import com.amazonaws.services.apigatewaymanagementapi.AmazonApiGatewayManagementApiClientBuilder;
import com.amazonaws.services.apigatewaymanagementapi.model.PostToConnectionRequest;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class SendPlayerJoinedHandler implements RequestHandler<APIGatewayV2WebSocketEvent, Object> {

    private final AmazonDynamoDB dynamoDb = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamo = new DynamoDB(dynamoDb);

    private final String CONNECTIONS_TABLE = System.getenv().getOrDefault("CONNECTIONS_TABLE", "PinfanoConnections");
    private final String GAMES_TABLE = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");

    @Override
    public Object handleRequest(APIGatewayV2WebSocketEvent event, Context context) {

        String connectionId = event.getRequestContext().getConnectionId();
        context.getLogger().log("🔗 Player joined: " + connectionId);

        String body = event.getBody();
        String username = "";
        String codigoGame = "";

        try {
            JSONObject json = new JSONObject(body);
            username = json.getString("username");
            codigoGame = json.getString("codigoGame");
        } catch (Exception e) {
            context.getLogger().log("❌ Error parsing JSON: " + e.getMessage());
        }

        Table connectionsTable = dynamo.getTable(CONNECTIONS_TABLE);
        Table gamesTable = dynamo.getTable(GAMES_TABLE);

        // Guardar la conexión en DynamoDB
        try {
            Item item = new Item()
                    .withPrimaryKey("connectionId", connectionId)
                    .withString("username", username)
                    .withString("codigoGame", codigoGame);
            connectionsTable.putItem(item);
            context.getLogger().log("✅ Connection saved in DynamoDB");
        } catch (Exception e) {
            context.getLogger().log("❌ Error saving connection: " + e.getMessage());
        }

        // ----------------------
        // TEMPORAL: Obtener conexiones del mismo juego SIN índice
        List<String> targetConnections = new ArrayList<>();
        int connectedPlayers = 0;

        try {
            ItemCollection<?> allItems = connectionsTable.scan(); // escanea toda la tabla
            for (Item item : allItems) {
                if (codigoGame.equals(item.getString("codigoGame"))) {
                    targetConnections.add(item.getString("connectionId"));
                    connectedPlayers++;
                }
            }
        } catch (Exception e) {
            context.getLogger().log("❌ Error scanning connections table: " + e.getMessage());
        }

        // ----------------------
        // TEMPORAL: Obtener numjugadores del juego usando scan
        int maxPlayers = 4; // valor por defecto
        try {
            ItemCollection<?> allGames = gamesTable.scan(); // escanea toda la tabla
            for (Item g : allGames) {
                if (codigoGame.equals(g.getString("codigoGame")) && g.isPresent("numjugadores")) {
                    maxPlayers = g.getInt("numjugadores");
                    break;
                }
            }
        } catch (Exception e) {
            context.getLogger().log("❌ Error scanning games table: " + e.getMessage());
        }

        // Crear JSON y notificar a todos los jugadores
        try {
            JSONObject msgJson = new JSONObject();
            msgJson.put("type", "playerJoined");
            msgJson.put("username", username);
            msgJson.put("connectedPlayers", connectedPlayers);
            msgJson.put("maxPlayers", maxPlayers);

            AmazonApiGatewayManagementApi apiGatewayClient = AmazonApiGatewayManagementApiClientBuilder.standard()
                    .withEndpointConfiguration(
                            new AmazonApiGatewayManagementApiClientBuilder.EndpointConfiguration(
                                    "https://" + event.getRequestContext().getDomainName() + "/" + event.getRequestContext().getStage(),
                                    "eu-central-1"
                            )
                    ).build();

            for (String target : targetConnections) {
                try {
                    PostToConnectionRequest request = new PostToConnectionRequest()
                            .withConnectionId(target)
                            .withData(ByteBuffer.wrap(msgJson.toString().getBytes()));
                    apiGatewayClient.postToConnection(request);
                } catch (Exception e) {
                    context.getLogger().log("❌ Error sending to " + target + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            context.getLogger().log("❌ Error creating JSON: " + e.getMessage());
        }

        return null;
    }
}