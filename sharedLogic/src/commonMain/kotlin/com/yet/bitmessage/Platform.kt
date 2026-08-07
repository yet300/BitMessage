package com.yet.bitmessage

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform