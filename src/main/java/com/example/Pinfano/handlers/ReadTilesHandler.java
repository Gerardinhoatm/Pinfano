package com.example.Pinfano.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class ReadTilesHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final String BUCKET = "pinfano-data";
    private static final String KEY = "fichas.json";

    private final S3Client s3 = S3Client.builder()
            .region(Region.EU_CENTRAL_1)
            .build();

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {

        try {
            ResponseBytes<GetObjectResponse> fileBytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(BUCKET)
                            .key(KEY)
                            .build()
            );

            String contenido = fileBytes.asString(StandardCharsets.UTF_8);

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(200)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(contenido)
                    .build();

        } catch (Exception e) {
            e.printStackTrace();
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withBody("Error leyendo JSON: " + e.getMessage())
                    .build();
        }
    }
}
