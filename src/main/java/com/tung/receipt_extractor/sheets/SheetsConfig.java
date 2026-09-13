package com.tung.receipt_extractor.sheets;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.security.GeneralSecurityException;

@Configuration
public class SheetsConfig {

    @Bean
    public Sheets sheetsClient(
            @Value("${sheets.client-id}") String clientId,
            @Value("${sheets.client-secret}") String clientSecret,
            @Value("${sheets.refresh-token}") String refreshToken)
            throws GeneralSecurityException, IOException {
        UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRefreshToken(refreshToken)
                .build();

        return new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("receipt-extractor")
                .build();
    }

    @Bean
    public SheetsProperties sheetsProperties(
            @Value("${sheets.spreadsheet-id}") String spreadsheetId,
            @Value("${sheets.sheet-name}") String sheetName) {
        return new SheetsProperties(spreadsheetId, sheetName);
    }
}
