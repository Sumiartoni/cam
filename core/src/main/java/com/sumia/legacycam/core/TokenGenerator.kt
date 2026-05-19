package com.sumia.legacycam.core

import java.security.SecureRandom

object TokenGenerator {
    private const val TOKEN_LENGTH = 6
    private const val ALLOWED = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    fun create(): String = buildString(TOKEN_LENGTH) {
        repeat(TOKEN_LENGTH) {
            append(ALLOWED[random.nextInt(ALLOWED.length)])
        }
    }
}
