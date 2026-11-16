package com.vkyra.code.git;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Git 凭据管理器
 * 使用 Android Keystore 加密存储 Git 凭据
 */
public class GitCredentialManager {
    
    private static final String PREFS_NAME = "GitCredentials";
    private static final String KEY_CREDENTIALS = "credentials";
    private static final String KEYSTORE_ALIAS = "AkodeGitKey";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    
    public static class Credential {
        public String name;        // 凭据名称（用于显示）
        public String host;        // 主机名（如 github.com）
        public String username;
        public String password;    // 密码或 Token
        
        public Credential(String name, String host, String username, String password) {
            this.name = name;
            this.host = host;
            this.username = username;
            this.password = password;
        }
    }
    
    /**
     * 保存凭据
     */
    public static synchronized boolean saveCredential(Context context, Credential credential) {
        try {
            GitRemoteIdentity scope = GitRemoteIdentity.fromSavedScope(credential.host);
            if (scope == null || credential.username == null || credential.username.isEmpty()) {
                throw new IllegalArgumentException("凭据必须绑定到有效的 Git 主机和账户");
            }
            credential.host = scope.toOrigin();
            List<Credential> credentials = readCredentials(context);
            
            // 移除同主机同用户名的旧凭据
            credentials.removeIf(c -> scope.equals(GitRemoteIdentity.fromSavedScope(c.host))
                    && credential.username.equals(c.username));
            
            // 添加新凭据
            credentials.add(0, credential);
            
            // 保存
            return saveCredentials(context, credentials);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 获取所有凭据
     */
    public static List<Credential> getCredentials(Context context) {
        try {
            return readCredentials(context);
        } catch (Exception ignored) {
            // A corrupt/locked vault cannot authorize a network operation.
            return new ArrayList<>();
        }
    }

    private static List<Credential> readCredentials(Context context) throws Exception {
        List<Credential> credentials = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String encrypted = prefs.getString(KEY_CREDENTIALS, null);
        if (encrypted == null || encrypted.isEmpty()) return credentials;
        String json = decrypt(context, encrypted);
        if (json == null) throw new IllegalStateException("无法解密现有凭据，未修改存储");
        JSONArray array = new JSONArray(json);
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            credentials.add(new Credential(obj.optString("name", ""), obj.optString("host", ""),
                    obj.optString("username", ""), obj.optString("password", "")));
        }
        return credentials;
    }

    /**
     * 根据 URL 查找匹配的凭据
     */
    public static Credential findCredentialForUrl(Context context, String url) {
        if (url == null || url.isEmpty()) return null;
        
        GitRemoteIdentity target = GitRemoteIdentity.fromRemote(url);
        if (target == null || !target.isHttp()) return null;

        String requestedUser;
        try {
            requestedUser = new org.eclipse.jgit.transport.URIish(url).getUser();
        } catch (java.net.URISyntaxException e) {
            return null;
        }
        
        List<Credential> credentials = getCredentials(context);
        for (Credential c : credentials) {
            if (target.equals(GitRemoteIdentity.fromSavedScope(c.host))
                    && (requestedUser == null || requestedUser.equals(c.username))) {
                return c;
            }
        }
        return null;
    }
    
    /**
     * 删除凭据
     */
    public static synchronized boolean deleteCredential(Context context, String host, String username) {
        try {
            List<Credential> credentials = readCredentials(context);
            GitRemoteIdentity scope = GitRemoteIdentity.fromSavedScope(host);
            credentials.removeIf(c -> scope != null && scope.equals(GitRemoteIdentity.fromSavedScope(c.host))
                    && java.util.Objects.equals(c.username, username));
            return saveCredentials(context, credentials);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 清空所有凭据
     */
    public static synchronized boolean clearAll(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.edit().remove(KEY_CREDENTIALS).commit();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
    
    private static boolean saveCredentials(Context context, List<Credential> credentials) {
        try {
            JSONArray array = new JSONArray();
            for (Credential c : credentials) {
                JSONObject obj = new JSONObject();
                obj.put("name", c.name);
                obj.put("host", c.host);
                obj.put("username", c.username);
                obj.put("password", c.password);
                array.put(obj);
            }
            
            String encrypted = encrypt(context, array.toString());
            if (encrypted != null) {
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                return prefs.edit().putString(KEY_CREDENTIALS, encrypted).commit();
            }
        } catch (Exception ignored) {
            // Do not log JSON/crypto exceptions: they may contain decrypted credential material.
        }
        return false;
    }
    
    /**
     * 从 URL 提取主机名
     */
    public static String extractHost(String url) {
        GitRemoteIdentity identity = GitRemoteIdentity.fromRemote(url);
        return identity == null ? null : identity.getHost();
    }

    /** Store this scope instead of a bare hostname so non-default ports remain isolated. */
    public static String extractOrigin(String url) {
        GitRemoteIdentity identity = GitRemoteIdentity.fromRemote(url);
        return identity == null ? null : identity.toOrigin();
    }

    public static OriginCredentialsProvider createCredentialsProvider(Context context) {
        OriginCredentialsProvider provider = new OriginCredentialsProvider();
        for (Credential credential : getCredentials(context)) {
            provider.add(credential.host, credential.username, credential.password);
        }
        return provider;
    }
    
    // ========== 加密相关 ==========
    
    private static synchronized SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEYSTORE_ALIAS, null);
        }
        
        // 创建新密钥
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build();
        
        keyGenerator.init(spec);
        return keyGenerator.generateKey();
    }
    
    private static String encrypt(Context context, String plainText) {
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            
            // 将 IV 和密文组合
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            
            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }
    
    private static String decrypt(Context context, String encryptedText) {
        try {
            SecretKey key = getOrCreateKey();
            byte[] combined = Base64.decode(encryptedText, Base64.NO_WRAP);
            
            // 分离 IV 和密文
            byte[] iv = new byte[12]; // GCM IV 长度为 12
            byte[] encrypted = new byte[combined.length - 12];
            System.arraycopy(combined, 0, iv, 0, 12);
            System.arraycopy(combined, 12, encrypted, 0, encrypted.length);
            
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
