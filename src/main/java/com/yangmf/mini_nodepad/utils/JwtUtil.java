package com.yangmf.mini_nodepad.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

@Component
public class JwtUtil {
    /**
     * 生成 jwt
     * 使用 Hs256 算法，私匙使用固定秘钥
     *
     * @param secretKey jwt 秘钥
     * @param ttlMillis jwt 过期时间 (毫秒)
     * @param claims    设置的信息
     * @return
     */
    public  String createJWT(String secretKey, long ttlMillis, Map<String, Object> claims) {
        // 指定签名的时候使用的签名算法，也就是 header 那部分
        SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.HS256;

        // 生成 JWT 的时间
        long expMillis = System.currentTimeMillis() + ttlMillis;
        Date exp = new Date(expMillis);

        // 将字符串密钥转换为 SecretKey
        SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));

        // 设置 jwt 的 body
        return Jwts.builder()
                .setClaims(claims)
                .setExpiration(exp)
                .signWith(key, signatureAlgorithm)
                .compact();
    }

    /**
     * Token 解密
     *
     * @param secretKey jwt 秘钥 此秘钥一定要保留好在服务端，不能暴露出去，否则 sign 就可以被伪造，如果对接多个客户端建议改造成多个
     * @param token     加密后的 token
     * @return
     */


    public  Claims parseJWT(String secretKey, String token) {
        SecretKey key = Keys.hmacShaKeyFor(secretKey.getBytes(StandardCharsets.UTF_8));

        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public  boolean isTokenExpired(String secretKey, String token) {
        try {
            Claims claims = parseJWT(secretKey, token);
            return claims.getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        } catch (Exception e) {
            return true;
        }
    }

}
