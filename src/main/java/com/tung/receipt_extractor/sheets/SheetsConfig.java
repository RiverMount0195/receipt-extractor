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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Properties;

@Configuration
public class SheetsConfig {

    static Properties loadCredentials(String credentialsPath) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(Path.of(credentialsPath))) {
            properties.load(in);
        }
        return properties;
    }

    @Bean
    public Sheets sheetsClient(@Value("${sheets.credentials-path}") String credentialsPath)
            throws GeneralSecurityException, IOException {
        Properties sheetsCredentials = loadCredentials(credentialsPath);

        UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId(sheetsCredentials.getProperty("client-id"))
                .setClientSecret(sheetsCredentials.getProperty("client-secret"))
                .setRefreshToken(sheetsCredentials.getProperty("refresh-token"))
                .build();

        return new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("receipt-extractor")
                .build();
    }

    @Bean
    public SheetsProperties sheetsProperties(@Value("${sheets.credentials-path}") String credentialsPath)
            throws IOException {
        Properties sheetsCredentials = loadCredentials(credentialsPath);

        return new SheetsProperties(
                sheetsCredentials.getProperty("spreadsheet-id"),
                sheetsCredentials.getProperty("sheet-name"));
    }
}
