package com.example.Pinfano.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.Index;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;

import com.amazonaws.services.apigatewaymanagementapi.AmazonApiGatewayManagementApi;
import com.amazonaws.services.apigatewaymanagementapi.AmazonApiGatewayManagementApiClientBuilder;
import com.amazonaws.services.apigatewaymanagementapi.model.PostToConnectionRequest;

import org.json.JSONObject;

import java.nio.ByteBuffer;

public class GetGameStatusHandler implements RequestHandler<APIGatewayV2WebSocketEvent, Object> {

    private final AmazonDynamoDB dynamoDb = AmazonDynamoDBClientBuilder.defaultClient();
    private final DynamoDB dynamo = new DynamoDB(dynamoDb);

    private final String CONNECTIONS_TABLE = System.getenv().getOrDefault("CONNECTIONS_TABLE", "PinfanoConnections");
    private final String GAMES_TABLE = System.getenv().getOrDefault("GAMES_TABLE", "PinfanoGames");

    @Override
    public Object handleRequest(APIGatewayV2WebSocketEvent event, Context context) {

        String connectionId = event.getRequestContext().getConnectionId();
        String codigoGame = event.getQueryStringParameters().get("codigoGame");

        context.getLogger().log("📡 GetGameStatus for game: " + codigoGame);

        Table connectionsTable = dynamo.getTable(CONNECTIONS_TABLE);
        Table gamesTable = dynamo.getTable(GAMES_TABLE);

        // Contar jugadores conectados
        Index index = connectionsTable.getIndex("CodigoGame-index");
        ItemCollection<QueryOutcome> items = index.query("codigoGame", codigoGame);
        int connectedPlayers = 0;
        for (Item item : items) {
            connectedPlayers++;
        }

        int maxPlayers = 4;
        Item gameItem = gamesTable.getItem("codigoGame", codigoGame);
        if (gameItem != null && gameItem.isPresent("numjugadores")) {
            maxPlayers = gameItem.getInt("numjugadores");
        }

        // Crear JSON y enviar solo al que pidió el status
        try {
            JSONObject json = new JSONObject();
            json.put("type", "gameStatus");
            json.put("connectedPlayers", connectedPlayers);
            json.put("maxPlayers", maxPlayers);

            AmazonApiGatewayManagementApi apiGatewayClient = AmazonApiGatewayManagementApiClientBuilder.standard()
                    .withEndpointConfiguration(
                            new AmazonApiGatewayManagementApiClientBuilder.EndpointConfiguration(
                                    "https://" + event.getRequestContext().getDomainName() + "/" + event.getRequestContext().getStage(),
                                    "eu-central-1"
                            )
                    ).build();

            PostToConnectionRequest request = new PostToConnectionRequest()
                    .withConnectionId(connectionId)
                    .withData(ByteBuffer.wrap(json.toString().getBytes()));

            apiGatewayClient.postToConnection(request);

        } catch (Exception e) {
            context.getLogger().log("❌ Error sending gameStatus: " + e.getMessage());
        }

        return null;
    }
}
