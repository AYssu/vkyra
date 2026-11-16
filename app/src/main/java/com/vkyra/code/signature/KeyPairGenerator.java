package com.vkyra.code.signature;

import android.content.Context;
import android.util.Log;

import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

/**
 * 密钥对生成器
 */
public class KeyPairGenerator {
    private static final String TAG = "KeyPairGenerator";
    
    public KeyPairGenerator(Context context) {

    }
    
    /**
     * 生成密钥对并存储到KeyStore
     */
    public void generateKeyPair(KeyStore keyStore, String alias, String password) throws Exception {
        Log.d(TAG, "生成密钥对: " + alias);
        
        // 使用Java标准API生成RSA密钥对
        java.security.KeyPairGenerator keyPairGenerator = 
            java.security.KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        
        Log.d(TAG, "RSA密钥对生成成功");
        
        // 创建自签名证书
        X509Certificate cert = generateSelfSignedCertificate(keyPair);
        
        Log.d(TAG, "自签名证书生成成功");
        
        // 存储到KeyStore
        Certificate[] chain = {cert};
        keyStore.setKeyEntry(alias, keyPair.getPrivate(), password.toCharArray(), chain);
        
        Log.d(TAG, "密钥对已存储到KeyStore");
    }
    
    /**
     * 生成自签名证书
     */
    private X509Certificate generateSelfSignedCertificate(KeyPair keyPair) throws Exception {
        Log.d(TAG, "生成自签名证书");
        return CertificateBuilder.build(keyPair);
    }
}
