package com.studenthaven.android.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SessionStore {
    private static final String ALIAS = "student-haven-backend-session";
    private final SharedPreferences preferences;

    public SessionStore(Context context) {
        this(context, "backend-session");
    }

    SessionStore(Context context, String preferenceName) {
        preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE);
    }

    public String load() throws BackendException {
        String encrypted = preferences.getString("ciphertext", null);
        if (encrypted == null) return null;
        try {
            String iv = preferences.getString("iv", null);
            if (iv == null) throw new GeneralSecurityException("Missing session IV");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                    Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException exception) {
            throw new BackendException("Cannot read the saved sign-in. Use Forget local sign-in, then sign in again.", exception);
        }
    }

    public void save(String value) throws BackendException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            String encrypted = Base64.encodeToString(cipher.doFinal(
                    value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
            if (!preferences.edit().putString("ciphertext", encrypted)
                    .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).commit()) {
                throw new IOException("Session write failed");
            }
        } catch (GeneralSecurityException | IOException exception) {
            throw new BackendException("Cannot securely save your sign-in on this device.", exception);
        }
    }

    public void clear() throws BackendException {
        if (!preferences.edit().clear().commit()) {
            throw new BackendException("Cannot remove the saved sign-in. Please try again.", 0);
        }
    }

    private SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return (SecretKey) store.getKey(ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
