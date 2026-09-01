package com.dawidpawliczek.app.auth

import com.dawidpawliczek.app.auth.user.User
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Base64
import java.util.Date
import javax.crypto.SecretKey

@Service
class JwtService(
    @param:Value("\${jwt.issuer}") private val issuer: String,
    @param:Value("\${jwt.access-ttl-seconds}") private val accessTtl: Long,
    @param:Value("\${jwt.secret}") private val secretB64: String,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secretB64))

    fun issueAccessToken(user: User): String =
        Jwts
            .builder()
            .issuer(issuer)
            .subject(user.id.toString())
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + accessTtl * 1000))
            .signWith(key, Jwts.SIG.HS256)
            .compact()

    fun parse(token: String) =
        Jwts
            .parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
}
