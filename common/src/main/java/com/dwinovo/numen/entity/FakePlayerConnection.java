package com.dwinovo.numen.entity;

/**
 * Marker for server-side player connections that have no remote client.
 * Kept in the transitional tool ABI so replacement lifecycle implementations
 * receive the same packet-listener short circuit as the historical body.
 */
public interface FakePlayerConnection {}
