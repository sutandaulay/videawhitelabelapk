package com.idnoffice.app;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferenceHelper {

    private static final String PREF_NAME = "user_pref";

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static void saveLoginData(Context context, String tenantCode, String registeredNumber) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.putString("tenant_code", tenantCode);
        editor.putString("registered_number", registeredNumber);
        editor.apply();
    }

    public static String getTenantCode(Context context) {
        return prefs(context).getString("tenant_code", null);
    }

    public static String getRegisteredNumber(Context context) {
        return prefs(context).getString("registered_number", null);
    }

    public static void clear(Context context) {
        SharedPreferences.Editor editor = prefs(context).edit();
        editor.clear();
        editor.apply();
    }
}
