package com.vkyra.code.signature;

import android.util.Log;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * 证书构建器 - 使用BouncyCastle生成自签名证书
 */
public class CertificateBuilder {
    private static final String TAG = "CertificateBuilder";
    
    /**
     * 构建自签名证书
     */
    public static X509Certificate build(KeyPair keyPair) throws Exception {
        Log.d(TAG, "构建自签名证书");
        
        // 证书有效期
        Date notBefore = new Date();
        Date notAfter = new Date(notBefore.getTime() + 365L * 24 * 60 * 60 * 1000 * 25); // 25年
        
        // 证书序列号
        BigInteger serialNumber = BigInteger.valueOf(System.currentTimeMillis());
        
        // 证书主题和颁发者（自签名，所以相同）
        X500Name issuer = new X500Name("CN=Android Debug,O=Android,C=US");
        X500Name subject = issuer;
        
        // 获取公钥信息
        SubjectPublicKeyInfo publicKeyInfo = SubjectPublicKeyInfo.getInstance(
            keyPair.getPublic().getEncoded()
        );
        
        // 创建证书构建器
        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
            issuer,
            serialNumber,
            notBefore,
            notAfter,
            subject,
            publicKeyInfo
        );
        
        // 使用私钥签名
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
            .build(keyPair.getPrivate());
        
        // 生成证书
        X509CertificateHolder certHolder = certBuilder.build(signer);
        
        // 转换为X509Certificate
        X509Certificate certificate = new JcaX509CertificateConverter()
            .getCertificate(certHolder);
        
        Log.d(TAG, "证书生成成功");
        return certificate;
    }
}
