package com.vkyra.code.http;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Generic HTTP client using OkHttp
 * Supports GET, POST, PUT, DELETE with JSON
 */
public class HttpClient {
    private static final String TAG = "HttpClient";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private OkHttpClient client;
    private String baseUrl;
    private Map<String, String> defaultHeaders;
    
    public HttpClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * Set default headers for all requests
     */
    public void setDefaultHeaders(Map<String, String> headers) {
        this.defaultHeaders = headers;
    }
    
    /**
     * GET request
     */
    public String get(String endpoint) throws IOException {
        String url = baseUrl + endpoint;
        Request.Builder builder = new Request.Builder().url(url);
        addDefaultHeaders(builder);
        
        Request request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.message());
            }
            return response.body().string();
        }
    }
    
    /**
     * POST request with JSON body
     */
    public String post(String endpoint, JSONObject body) throws IOException {
        String url = baseUrl + endpoint;
        RequestBody requestBody = RequestBody.create(body.toString(), JSON);
        
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(requestBody);
        addDefaultHeaders(builder);
        
        Request request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new IOException("HTTP " + response.code() + ": " + errorBody);
            }
            return response.body().string();
        }
    }
    
    /**
     * POST request with custom body
     */
    public String post(String endpoint, String body, String contentType) throws IOException {
        String url = baseUrl + endpoint;
        MediaType mediaType = MediaType.parse(contentType);
        RequestBody requestBody = RequestBody.create(body, mediaType);
        
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(requestBody);
        addDefaultHeaders(builder);
        
        Request request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                throw new IOException("HTTP " + response.code() + ": " + errorBody);
            }
            return response.body().string();
        }
    }
    
    /**
     * PUT request with JSON body
     */
    public String put(String endpoint, JSONObject body) throws IOException {
        String url = baseUrl + endpoint;
        RequestBody requestBody = RequestBody.create(body.toString(), JSON);
        
        Request.Builder builder = new Request.Builder()
                .url(url)
                .put(requestBody);
        addDefaultHeaders(builder);
        
        Request request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.message());
            }
            return response.body().string();
        }
    }
    
    /**
     * DELETE request
     */
    public String delete(String endpoint) throws IOException {
        String url = baseUrl + endpoint;
        Request.Builder builder = new Request.Builder()
                .url(url)
                .delete();
        addDefaultHeaders(builder);
        
        Request request = builder.build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + response.message());
            }
            return response.body().string();
        }
    }
    
    /**
     * Add default headers to request
     */
    private void addDefaultHeaders(Request.Builder builder) {
        if (defaultHeaders != null) {
            for (Map.Entry<String, String> entry : defaultHeaders.entrySet()) {
                builder.addHeader(entry.getKey(), entry.getValue());
            }
        }
    }
    
    /**
     * Async GET request
     */
    public void getAsync(String endpoint, HttpCallback callback) {
        new Thread(() -> {
            try {
                String response = get(endpoint);
                callback.onSuccess(response);
            } catch (Exception e) {
                Log.e(TAG, "GET error", e);
                callback.onError(e.getMessage());
            }
        }).start();
    }
    
    /**
     * Async POST request
     */
    public void postAsync(String endpoint, JSONObject body, HttpCallback callback) {
        new Thread(() -> {
            try {
                String response = post(endpoint, body);
                callback.onSuccess(response);
            } catch (Exception e) {
                Log.e(TAG, "POST error", e);
                callback.onError(e.getMessage());
            }
        }).start();
    }
    
    /**
     * Callback interface for async operations
     */
    public interface HttpCallback {
        void onSuccess(String response);
        void onError(String error);
    }
}
