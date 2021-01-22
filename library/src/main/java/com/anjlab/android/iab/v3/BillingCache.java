/**
 * Copyright 2014 AnjLab
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.anjlab.android.iab.v3;

import android.content.Context;
import android.text.TextUtils;

import com.android.billingclient.api.Purchase;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

class BillingCache extends BillingBase {
    private static final String ENTRY_DELIMITER = "#####";
    private static final String LINE_DELIMITER = ">>>>>";
    private static final String VERSION_KEY = ".version";

    private HashMap<String, Purchase> data;
    private String cacheKey;
    private String version;

    BillingCache(Context context, String key) {
        super(context);
        data = new HashMap<>();
        cacheKey = key;
        load();
    }

    private String getPreferencesCacheKey() {
        return getPreferencesBaseKey() + cacheKey;
    }

    private String getPreferencesVersionKey() {
        return getPreferencesCacheKey() + VERSION_KEY;
    }

    private void load() {
        String[] entries = loadString(getPreferencesCacheKey(), "").split(Pattern.quote(ENTRY_DELIMITER));
        for (String entry : entries) {
            if (!TextUtils.isEmpty(entry)) {
                String[] parts = entry.split(Pattern.quote(LINE_DELIMITER));
                Purchase purchase = null;
                if (parts.length > 2) {
                    purchase = newPurchase(parts[1], parts[2]);

                } else if (parts.length > 1) {
                    purchase = newPurchase(parts[1], null);
                }
                if (purchase != null) {
                    data.put(parts[0], purchase);
                }
            }
        }
        version = getCurrentVersion();
    }

    public Purchase newPurchase(String json, String signature) {
        try {
            Purchase purchase = new Purchase(json, signature);
            return purchase;
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void flush() {
        ArrayList<String> output = new ArrayList<>();
        for (String productId : data.keySet()) {
            Purchase info = data.get(productId);
            output.add(productId + LINE_DELIMITER + info.getOriginalJson() + LINE_DELIMITER +
                    info.getSignature());
        }
        saveString(getPreferencesCacheKey(), TextUtils.join(ENTRY_DELIMITER, output));
        version = Long.toString(new Date().getTime());
        saveString(getPreferencesVersionKey(), version);
    }

    boolean includesProduct(String productId) {
        reloadDataIfNeeded();
        return data.containsKey(productId);
    }

    Purchase getDetails(String productId) {
        reloadDataIfNeeded();
        return data.containsKey(productId) ? data.get(productId) : null;
    }

    void put(String productId, String details, String signature) {
        reloadDataIfNeeded();
        if (!data.containsKey(productId)) {
            Purchase purchase = newPurchase(details, signature);
            if (purchase!=null) {
                data.put(productId, purchase);
                flush();
            }
        }
    }

    void put(String productId, Purchase purchase) {
        reloadDataIfNeeded();
        if (!data.containsKey(productId)) {
            data.put(productId, purchase);
            flush();
        }
    }

    void remove(String productId) {
        reloadDataIfNeeded();
        if (data.containsKey(productId)) {
            data.remove(productId);
            flush();
        }
    }

    void clear() {
        reloadDataIfNeeded();
        data.clear();
        flush();
    }

    private String getCurrentVersion() {
        return loadString(getPreferencesVersionKey(), "0");
    }

    private void reloadDataIfNeeded() {
        if (!version.equalsIgnoreCase(getCurrentVersion())) {
            data.clear();
            load();
        }
    }

    List<String> getContents() {
        return new ArrayList<>(data.keySet());
    }

    @Override
    public String toString() {
        return TextUtils.join(", ", data.keySet());
    }
}
