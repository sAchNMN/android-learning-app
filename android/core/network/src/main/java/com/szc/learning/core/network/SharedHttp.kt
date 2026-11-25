package com.szc.learning.core.network

import okhttp3.OkHttpClient

/**
 * 全 App 共享的 OkHttp 基础设施。
 *
 * 以前每个用到网络的地方各 new 一个 `OkHttpClient`（后端 API、平台直连、探活、
 * 房间 HTTP、房间 WebSocket、酷我、下载）。每个 client 自带一套连接池与线程池，
 * 互相之间不复用 TLS 连接 —— 冷启动时几个模块同时握手，多花好几轮 RTT。
 *
 * 现在统一从这里 `newBuilder()` 派生：连接池 / 分发器共享，超时和拦截器各自覆盖。
 * 配置互不影响，只是不再重复建池。
 */
object SharedHttp {

    /** 基础 client。只用默认配置，派生方按需覆盖超时。 */
    val base: OkHttpClient = OkHttpClient.Builder().build()
}
