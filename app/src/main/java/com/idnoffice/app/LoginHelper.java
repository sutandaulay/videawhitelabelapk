package com.idnoffice.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;
import java.io.IOException;

public class LoginHelper {

    private static final OkHttpClient client = new OkHttpClient();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public interface LoginCallback {
        void onLoading(boolean isLoading);
        void onError(String message);
        void onSuccess();
    }

    public static void onLogin(Context context,
                               String tenantCode,
                               String registeredNumber,
                               String fcmToken,
                               String deviceType,
                               LoginCallback callback) {

        try {
            // JSON body
            JSONObject json = new JSONObject();
            json.put("tenant_code", tenantCode);
            json.put("registered_number", registeredNumber);
            json.put("fcm_token", fcmToken);
            json.put("device_type", deviceType);

            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request request = new Request.Builder()
                    .url("https://videaclass.com/api/v1/fcm-token")
                    .post(body)
                    .build();

            callback.onLoading(true);

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postToUI(() -> {
                        callback.onLoading(false);
                        callback.onError("Network Error");
                    });
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = response.body().string();
                    boolean isSuccess = response.isSuccessful();

                    Log.d("LOGIN_API", "Status: " + response.code());
                    Log.d("LOGIN_API", "Body: " + responseBody);

                    postToUI(() -> {
                        callback.onLoading(false);

                        if (!isSuccess) {
                            callback.onError("Kode lembaga atau User tidak ditemukan");
                            return;
                        }

                        PreferenceHelper.saveLoginData(context, tenantCode, registeredNumber);

                        callback.onSuccess();
                    });
                }
            });

        } catch (Exception e) {
            callback.onError("Unexpected Error");
        }
    }

    private static void postToUI(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }
}
