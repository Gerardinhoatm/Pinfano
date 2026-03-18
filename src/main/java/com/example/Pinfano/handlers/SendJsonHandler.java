package com.example.Pinfano.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;

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

    private final String CONNECTIONS_TABLE = System.getenv().getOrDefault("CONNECTIONS_TABLE", "PinfanoConnections");

    @Override
    public Object handleRequest(APIGatewayV2WebSocketEvent event, Context context) {

        String connectionId = event.getRequestContext().getConnectionId();
        context.getLogger().log("🔗 sendJson triggered by: " + connectionId);

        String body = event.getBody();
        String codigoGame = "";
        JSONObject jsonGame = null;
        int turno = -1;

        try {
            JSONObject json = new JSONObject(body);
            codigoGame = json.getString("codigoGame");
            jsonGame = json.getJSONObject("jsonGame");
            turno = json.getInt("turno");
        } catch (Exception e) {
            context.getLogger().log("❌ Error parsing JSON input: " + e.getMessage());
            return null;
        }

        Table connectionsTable = dynamo.getTable(CONNECTIONS_TABLE);
        List<String> targetConnections = new ArrayList<>();

        try {
            ScanSpec scanSpec = new ScanSpec().withConsistentRead(true);
            ItemCollection<?> allItems = connectionsTable.scan(scanSpec);

            for (Item item : allItems) {
                String gameCode = item.getString("codigoGame");
                if (codigoGame.equals(gameCode)) {
                    targetConnections.add(item.getString("connectionId"));
                }
            }

        } catch (Exception e) {
            context.getLogger().log("❌ Error scanning connections table: " + e.getMessage());
        }

        try {
            JSONObject msgJson = new JSONObject();
            msgJson.put("type", "gameUpdated");
            msgJson.put("codigoGame", codigoGame);
            msgJson.put("jsonGame", jsonGame);
            msgJson.put("turno", turno);

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
            context.getLogger().log("❌ Error creating WebSocket JSON: " + e.getMessage());
        }

        return null;
    }
}
