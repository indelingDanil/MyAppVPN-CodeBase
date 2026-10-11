package com.myappvpn.service

/**
 * Which kind of session the VPN service is running.
 * AUTO = Xray balancer over a server pool (pinned via `xray api bo`);
 * MANUAL = a single user-chosen server (no silent failover).
 * OPENFLUX = OpenFlux (Beta): Xray chained to the local libofbridge SOCKS5 → the user's PHP exit.
 */
enum class ActiveMode { AUTO, MANUAL, OPENFLUX }
